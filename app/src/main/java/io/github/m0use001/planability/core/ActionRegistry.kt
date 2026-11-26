package io.github.m0use001.planability.core

import android.content.Context
import io.github.m0use001.planability.ai.ChatEngine
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.notify.Notify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v25：自动化流水线步骤 —— **不经过 AI** 的确定性动作序列（类 Apple 快捷指令）。
 *
 * ## 为什么要有这条链路
 * 原来的自动化只有一条路：触发 → 把 prompt 发给 AI → 通知回复。像"每天把下载目录里的
 * PDF 归到大学文件"这种**纯机械搬运**也走 AI，既慢（一次网络往返 + token）、又不可靠
 * （AI 只能"说"，不能真的移动文件），还要配模型才能用。
 *
 * [FlowRunner] 提供另一条路：触发 → 按顺序执行动作 → 汇总通知。每一步都是本地确定性
 * 代码，无网络、无 token、失败原因精确到第几步。
 *
 * ## 数据形状
 * 步骤本身是数据（[AutomationStep]），存在 `automations.stepsJson` 里；
 * 每步做什么由 [ActionRegistry] 按 `type` 分发。加新动作 = 在 registry 里加一个条目，
 * 存储格式和执行器都不用动。
 */
data class AutomationStep(
    /** 动作类型键，见 [ActionRegistry.defs] */
    val type: String,
    /** 参数：键见各动作的 [ActionDef.params]。值支持占位符替换，见 [FlowContext.subst] */
    val params: Map<String, String> = emptyMap()
) {
    companion object {
        /** JSON → 步骤列表。任何解析失败都退化成空列表（宁可流水线为空也不让整个编辑器崩） */
        fun decodeList(json: String): List<AutomationStep> = runCatching {
            val arr = JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                val po = o.optJSONObject("params") ?: JSONObject()
                val m = mutableMapOf<String, String>()
                val it = po.keys()
                while (it.hasNext()) {
                    val k = it.next()
                    m[k] = po.optString(k)
                }
                AutomationStep(o.getString("type"), m)
            }
        }.getOrDefault(emptyList())

        fun encodeList(steps: List<AutomationStep>): String = runCatching {
            val arr = JSONArray()
            steps.forEach { s ->
                val o = JSONObject()
                o.put("type", s.type)
                val po = JSONObject()
                s.params.forEach { (k, v) -> po.put(k, v) }
                o.put("params", po)
                arr.put(o)
            }
            arr.toString()
        }.getOrDefault("[]")
    }
}

/** 单个动作的参数定义（UI 按它渲染输入框，hint 是给用户的落点示例） */
data class ParamDef(val key: String, val label: String, val hint: String = "")

/** 注册表里的一个动作类型 */
data class ActionDef(
    val key: String,
    /** 卡片/步骤列表里显示的名字 */
    val label: String,
    /** 一句话说明（添加步骤的选择行里展示） */
    val desc: String,
    val params: List<ParamDef> = emptyList(),
    /** false = 参数固定、UI 不渲染输入框（如回收站） */
    val editable: Boolean = true
)

object ActionRegistry {

    /** 全部可用动作。顺序 = UI「添加步骤」选择行的顺序（按使用频率排） */
    val defs: List<ActionDef> = listOf(
        ActionDef(
            "file_scan", "扫描文件", "找出目录里匹配的文件，供后面移动/复制",
            listOf(
                ParamDef("dir", "目录（相对应用目录）", "如 synced/Download"),
                ParamDef("pattern", "文件名匹配", "*.pdf，留空=全部"),
                ParamDef("sub", "含子文件夹", "填 1 = 递归")
            )
        ),
        ActionDef(
            "file_move", "移动文件", "把上一步（或 from 目录）的文件移到 to 目录",
            listOf(
                ParamDef("from", "来源目录（可选）", "留空=用上一步扫描结果"),
                ParamDef("to", "目标目录", "如 synced/大学文件/编程")
            )
        ),
        ActionDef(
            "file_copy", "复制文件", "复制而不移动，源文件保留",
            listOf(
                ParamDef("from", "来源目录（可选）", "留空=用上一步扫描结果"),
                ParamDef("to", "目标目录", "如 synced/备份")
            )
        ),
        ActionDef(
            "file_rename", "重命名", "按模板改名，{name} 为原名（不含扩展名）",
            listOf(ParamDef("template", "模板", "如 期末_{name}"))
        ),
        ActionDef(
            "file_trash", "移入回收站", "挪进 _trash/ 时间戳目录，不真删",
            listOf(ParamDef("from", "来源目录（可选）", "留空=用上一步扫描结果")),
            editable = false
        ),
        ActionDef(
            "notify", "发通知", "把结果/消息推送成系统通知",
            listOf(
                ParamDef("title", "标题", "如 整理完成"),
                ParamDef("text", "内容", "支持 \$count / \$last")
            )
        ),
        ActionDef(
            "todo_add", "新建待办", "把一条待办放进助理中枢草稿，用户确认后才进计划页",
            listOf(ParamDef("title", "标题", "支持 \$count / \$last"))
        ),
        ActionDef(
            "http_get", "网络请求 GET", "拉一个 URL 的响应体（前 4000 字）",
            listOf(ParamDef("url", "地址", "https://…"))
        ),
        ActionDef(
            "ai", "调用 AI", "把前面的结果交给模型处理（需已配置模型）",
            listOf(ParamDef("prompt", "指令", "支持 \$count / \$files / \$last"))
        ),

        // ---- v19.40：对标 Apple 快捷指令，把"完全自动化"的动作面扩开 ----
        // 下面这几个**都不调模型**：零 token、确定性、可预测。快捷指令的主体应该是它们。

        ActionDef(
            "app_open", "打开应用/页面",
            "跳到另一个应用：填应用名、具体页面类名或深链 URI。" +
                "非导出的页面跳不动，会自动降级到深链、再降到应用主页",
            listOf(
                ParamDef("app", "应用（名称或包名）", "如 微信 / com.tencent.mm"),
                ParamDef("activity", "页面类名（可选）", "如 com.tencent.mm.ui.LauncherUI"),
                ParamDef("uri", "深链（可选）", "如 weixin://dl/notifications")
            )
        ),
        ActionDef(
            "wait", "等待",
            "暂停指定毫秒再继续（两步之间留间隔，或等一个界面稳定）",
            listOf(ParamDef("ms", "毫秒", "如 1500；上限 60000"))
        ),
        ActionDef(
            "vibrate", "振动",
            "给手机一个提示振动（跑到某一步需要提醒你时）",
            listOf(ParamDef("ms", "时长毫秒（可选）", "如 120；缺省 60"))
        ),
        ActionDef(
            "clipboard_write", "写剪贴板",
            "把文本写进系统剪贴板，接着去别的应用里粘贴",
            listOf(ParamDef("text", "内容", "支持 \$last / \$count"))
        ),
        ActionDef(
            "sys_status", "读系统状态",
            "读电量与充电状态。结果写进 \$last，下一步可用它拼通知文案",
            listOf(ParamDef("unused", "无参数", "留空即可"))
        ),
        ActionDef(
            "schedule_add", "新建日程",
            "把一条日程放进助理中枢草稿，用户确认后才进计划页",
            listOf(
                ParamDef("title", "标题", "支持 \$last"),
                ParamDef("time", "开始时间", "今天 08:00 / 明天 14:30 / HH:mm"),
                ParamDef("minutes", "时长分钟（可选）", "缺省 60")
            )
        ),
        // ---- v19.41：无障碍模拟操作 ----
        // 这四个动作共用已授权的读屏采集服务（设备功能包提供，不用调模型）。
        // 它们是「完全自动化」里最缺的一块：前面 15 个动作全在自己 App 里打转，
        // 真正要「顺手把手机上别的事做了」只能靠模拟操作。
        ActionDef(
            "◆_text", "按文字点击",
            "在当前界面找到含指定文字的控件并点它（如「确认」「下一步」）",
            listOf(
                ParamDef("text", "要找的文字", "如 确认；含匹配即可"),
                ParamDef("index", "第几个（可选）", "缺省 1；同名控件多个时用它选")
            )
        ),
        ActionDef(
            "a11y_wait_text", "等文字出现",
            "反复找指定文字，出现了才往下走；等不到就算失败（后面的步骤不会瞎点）",
            listOf(
                ParamDef("text", "等哪个文字", "如 已完成"),
                ParamDef("timeout", "超时秒（可选）", "缺省 20，上限 120")
            )
        ),
        ActionDef(
            "a11y_scroll", "滑动屏幕",
            "在当前界面上/下/左/右滑动。方向按**手指**方向：上=看更新的内容",
            listOf(
                ParamDef("dir", "方向", "上 / 下 / 左 / 右，缺省 下"),
                ParamDef("times", "次数（可选）", "缺省 1，上限 20")
            )
        ),
        ActionDef(
            "run_shortcut", "运行另一条指令",
            "按名字跑另一条自动化/快捷指令，**等它跑完**再继续。长指令拆成小指令再拼起来靠它",
            listOf(
                ParamDef("name", "指令名（完整名）", "支持 \$last"),
                ParamDef("reason", "为什么跑它（可选）", "只写进执行记录，不影响行为")
            )
        ),
        // ---- v19.43：操作系统设置 ----
        // 这批全部**不经过 AI**，且分两类：
        //   免授权：手电、音量、系统面板（点一下就能用）
        //   要授权：亮度（WRITE_SETTINGS）、勿扰（通知策略），没授权时**动作会把设置页拉起来**
        //   并明确失败，而不是静默无效——静默无效的自动化比报错难查得多。
        // WiFi / 蓝牙 / 飞行模式的开关要 shell 权限（见 ◆ 里给 phone-a 的 assign），
        // 这里用 `sys_panel` + `◆_text` 组合达成同样的事：不必等 shell，也不必撒谎。
        ActionDef(
            "sys_flashlight", "手电筒",
            "打开/关闭/闪一下手电（免授权）",
            listOf(
                ParamDef("mode", "模式", "开 / 关 / 闪；缺省 闪"),
                ParamDef("ms", "闪的时长毫秒", "缺省 1200")
            )
        ),
        ActionDef(
            "sys_volume", "音量",
            "调某个声音流的音量，或静音/取消静音（免授权）",
            listOf(
                ParamDef("stream", "声音流", "音乐 / 铃 / 通知 / 闹钟 / 系统；缺省 音乐"),
                ParamDef("level", "音量 0-100", "也可用 静音 / 取消静音 / 升 / 降"),
                ParamDef("max", "音量上限", "可省，留空用系统上限")
            )
        ),
        ActionDef(
            "sys_brightness", "屏幕亮度",
            "设屏幕亮度或切自动/手动（**需要用户授权**「修改系统设置」）",
            listOf(
                ParamDef("level", "亮度 0-100，或 自动", "缺省 50"),
                ParamDef("apply", "是否立即生效", "填 1 = 顺带把当前屏幕亮度设成这个值")
            )
        ),
        ActionDef(
            "sys_dnd", "勿扰模式",
            "开/关勿扰（**需要用户授权**「通知使用权」；未授权时会拉起设置页）",
            listOf(
                ParamDef("mode", "模式", "开 / 关；缺省 开"),
                ParamDef("level", "打扰级别", "全部 / 仅优先；缺省 仅优先")
            )
        ),
        ActionDef(
            "sys_panel", "打开系统面板",
            "拉起系统设置面板（WiFi / 蓝牙）。配合「按文字点击」就能自动点开关——" +
                "这样不需要 shell 权限也能开关 WiFi",
            listOf(ParamDef("which", "面板", "WiFi / 蓝牙；缺省 WiFi"))
        ),
        // ---- v19.44：**真开关**（回应 phone-c 的 assign，走 A 侧刚补的串行化通道）----
        // 与上面「sys_panel + 按文字点击」并存，不互相替代：
        //   · 真开关：一条命令、不看前台、不怕机型面板长得不一样；**前置条件是沙箱通道就绪**
        //   · 点面板：零前置条件，但依赖界面上那一下能不能点到、面板长什么样
        // 两个是「能力更强 vs 门槛更低」的关系，所以都留。
        ActionDef(
            "sys_wifi", "WiFi 开关",
            "**真开关**：直接开关 WiFi，不依赖前台界面。" +
                "需要沙箱通道就绪（文件页右上角锁 → 授权一次）",
            listOf(ParamDef("mode", "模式", "开 / 关 / 查；缺省 开"))
        ),
        ActionDef(
            "sys_bt", "蓝牙开关",
            "**真开关**：直接开关蓝牙，不依赖前台界面。需要沙箱通道就绪",
            listOf(ParamDef("mode", "模式", "开 / 关 / 查；缺省 开"))
        ),
        ActionDef(
            "sys_airplane", "飞行模式",
            "**真开关**：直接开关飞行模式（会连带断 WiFi/蓝牙，这是系统行为）。" +
                "需要沙箱通道就绪",
            listOf(ParamDef("mode", "模式", "开 / 关 / 查；缺省 开"))
        )
    )

    fun byKey(key: String): ActionDef? = defs.firstOrNull { it.key == key }
    fun labelOf(key: String): String = byKey(key)?.label ?: key
}

/**
 * 流水线执行器。
 *
 * ## 失败语义：**遇错即停 + 必出通知**
 * 与"每步都吞掉错误继续跑"相反——搬运做到一半失败还继续发"整理完成"通知是撒谎。
 * 所以任何一步失败：立即停止、汇总通知写清第几步和原因。成功时通知列出每步结果。
 *
 * ## 路径安全
 * 所有文件路径都相对 `getExternalFilesDir()`（与文件管理页同一根），
 * `..` 一律剥掉——自动化指令可能来自 AI，不能让它写出应用目录外。
 */
object FlowRunner {

    /** 一次运行的结果：ok 决定通知前缀，log 是每步一句的明细 */
    data class Result(val ok: Boolean, val summary: String, val log: List<String>)

    /** 指令互相调用的层数上限。3 层够拼（主流程 → 子流程 → 兜底），再多多半是环 */
    private const val MAX_NEST = 3
    private val depthCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /** 执行期间在步骤间传递的状态 */
    class FlowContext(
        /** 自动化 id（AI 步骤用它复用同一条锚定会话；试运行时为 0） */
        val automationId: Long,
        /** 自动化名（通知/AI 会话标题用） */
        val title: String
    ) {
        /** 上一步扫描命中的文件（file_scan 写入，file_move/copy/rename/trash 消费） */
        var files: List<File> = emptyList()
        /** 上一步的返回文本，占位符 $last */
        var last: String = ""
        /** 上一步命中/处理的数量，占位符 $count */
        var count: Int = 0

        /**
         * 占位符替换。四个就够用了——再多就变成一门脚本语言，那该用 AI 路线而不是流水线：
         * `$count` 数量 / `$files` 文件名换行拼接 / `$first` 第一个文件名 / `$last` 上一步输出
         */
        fun subst(text: String): String {
            if (text.isEmpty()) return text
            return text
                .replace("\$count", count.toString())
                .replace("\$files", files.joinToString("\n") { it.name })
                .replace("\$first", files.firstOrNull()?.name ?: "")
                .replace("\$last", last)
        }
    }

    /**
     * 根目录 = 应用专属外部目录（文件管理页受限模式的根、Syncthing 的 `files/` 所在）。
     * 自动化的文件动作全部落在它下面。
     */
    fun root(): File = Graph.appContext.getExternalFilesDir(null) ?: Graph.appContext.filesDir

    /** 相对路径 → File。剥 `..`、剥前导 `/`，永远出不了 [root] */
    fun resolve(path: String): File {
        val t = path.trim().replace("..", "").removePrefix("/")
        return if (t.isEmpty()) root() else File(root(), t)
    }

    /** 展示用相对路径（通知里出现的都应该是 `synced/xx` 而不是一长串绝对路径） */
    fun rel(f: File): String = runCatching { f.relativeTo(root()).path }.getOrDefault(f.absolutePath)

    /**
     * 运行一条流水线。内部切到 IO：文件 + HTTP 都不该在主线程。
     *
     * 参数按原始值传（id/名字/stepsJson）而不是收 [io.github.m0use001.planability.data.Automation]：
     * 执行器不该和某个实体的字段布局绑死在编译期（该实体正被并发改动中）。
     * 试运行时 automationId=0、stepsJson 传当前草稿即可，不落库。
     */
    suspend fun run(
        context: Context,
        automationId: Long,
        title: String,
        stepsJson: String
    ): Result {
        // 嵌套深度闸门（v19.41）。`run_shortcut` 让指令能调指令，于是「A 调 B 调 C…」有了上限的必要。
        // 用**进程级**计数器而不是 ThreadLocal：run() 内部会 withContext(IO)，
        // 嵌套那次很可能落在另一个 IO 线程上，ThreadLocal 到那儿就是空的、根本拦不住。
        // 代价是并发跑的无关指令会共享这个计数——宁可少放行几条，也别让一个环跑到 ANR。
        if (depthCounter.get() >= MAX_NEST) {
            return Result(false, "❌ 指令嵌套超过 $MAX_NEST 层（多半是指令互相调用成了环）", emptyList())
        }
        depthCounter.incrementAndGet()
        return try {
            runInner(context, automationId, title, stepsJson)
        } finally {
            depthCounter.decrementAndGet()
        }
    }

    private suspend fun runInner(
        context: Context,
        automationId: Long,
        title: String,
        stepsJson: String
    ): Result = withContext(Dispatchers.IO) {
        val steps = AutomationStep.decodeList(stepsJson)
        val ctx = FlowContext(automationId, title)
        val log = mutableListOf<String>()
        if (steps.isEmpty()) return@withContext Result(false, "❌ 没有配置任何步骤", log)
        steps.forEachIndexed { i, step ->
            val def = ActionRegistry.byKey(step.type)
            if (def == null) {
                val m = "第 ${i + 1} 步类型未知：${step.type}"
                log += m
                return@withContext Result(false, "❌ $m", log)
            }
            val r = runCatching { exec(context, def, step, ctx) }
            val msg = r.getOrNull()
            if (msg == null) {
                val m = "第 ${i + 1} 步「${def.label}」失败：" + (r.exceptionOrNull()?.message ?: "未知错误")
                log += m
                return@withContext Result(false, "❌ $m", log)
            }
            log += "${def.label}：$msg"
            // 占位符要吃的是**这一步的结果**，所以先记 log 再更新 last
            ctx.last = msg
        }
        Result(true, "✅ " + log.joinToString(" · ").take(400), log)
    }

    // ---------- 各动作的实现 ----------

    private suspend fun exec(context: Context, def: ActionDef, step: AutomationStep, ctx: FlowContext): String {
        val p = step.params
        return when (def.key) {

            // 扫描：写入 ctx.files，后面所有文件动作默认吃它
            "file_scan" -> {
                val dir = resolve(ctx.subst(p["dir"].orEmpty()))
                if (!dir.isDirectory) throw IllegalArgumentException("目录不存在：${rel(dir)}")
                val pat = ctx.subst(p["pattern"].orEmpty()).trim()
                val rec = p["sub"].orEmpty().trim() == "1"
                val seq: Sequence<File> = if (rec) {
                    dir.walkTopDown().onEnter { !it.name.startsWith(".") }
                } else {
                    dir.listFiles()?.asSequence() ?: emptySequence()
                }
                val files = seq
                    .filter { it.isFile && !it.name.startsWith(".") && matchGlob(pat, it.name) }
                    .take(500)
                    .toList()
                ctx.files = files
                ctx.count = files.size
                "扫描到 ${files.size} 个"
            }

            // 移动：显式 from 优先，否则吃上一步的扫描结果
            "file_move" -> transfer(ctx, p, deleteSource = true, copyFirst = false)
            "file_copy" -> transfer(ctx, p, deleteSource = false, copyFirst = true)

            "file_rename" -> {
                val tpl = ctx.subst(p["template"].orEmpty()).ifBlank { "{name}" }
                val targets = requireSource(ctx, p, "重命名")
                var n = 0
                val renamed = ArrayList<File>(targets.size)
                targets.forEach { f ->
                    val base = f.name.substringBeforeLast('.')
                    val ext = if (f.name.contains('.')) "." + f.name.substringAfterLast('.') else ""
                    val newName = sanitize(tpl.replace("{name}", base)) + ext
                    val dst = if (newName == f.name) f else {
                        val d = uniqueDst(File(f.parentFile, newName))
                        if (!f.renameTo(d)) throw IllegalStateException("改名失败：${f.name}")
                        n++
                        d
                    }
                    renamed += dst
                }
                // 消费链要续上：后续步骤（如 file_move）吃的应该是改名**之后**的文件
                ctx.files = renamed
                ctx.count = n
                "改名 $n 个"
            }

            // 回收站：绝不硬删。挪进 _trash/<时间戳>/，用户反悔还能捞回来
            "file_trash" -> {
                val targets = requireSource(ctx, p, "回收")
                val ts = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                val dest = File(File(root(), "_trash"), ts)
                if (!dest.isDirectory && !dest.mkdirs()) throw IllegalStateException("无法创建回收目录")
                targets.forEach { moveInto(it, dest) }
                ctx.count = targets.size
                "回收 ${targets.size} 个 → ${rel(dest)}"
            }

            "notify" -> {
                val t = ctx.subst(p["title"].orEmpty()).ifBlank { "自动化：" + ctx.title }
                val body = ctx.subst(p["text"].orEmpty())
                // 通知 id 取时间戳尾段：自动化本身用 NOTIF_BASE+id，试运行/短间隔并发时不能撞
                Notify.remind(
                    context, 4_000_000 + (System.currentTimeMillis() % 500_000).toInt(),
                    t, body, Notify.ROUTE_AUTOMATION
                )
                "已通知"
            }

            "todo_add" -> {
                val title = ctx.subst(p["title"].orEmpty()).ifBlank { ctx.title }
                if (title.isBlank()) throw IllegalArgumentException("标题为空")
                // 2026-11-12 用户「AI 生成的所有日程待办全都要先进助理中枢」：这里原来直接
                // saveTodo 进时间轴——AI 用 automation_create 建条流水线再 automation_run，
                // 内容就绕过中枢审核落库了。改走与 todo_create/pending_add 同一条通道：
                // PendingItem 草稿（payload 带原始参数，中枢 ✓ 由 PendingImporter 忠实重建）。
                if (ctx.automationId == 0L) return "试运行：将建待办草稿「$title」（未真的建）"
                // timeText 带完整日期（yyyy-M-d）：PendingDedup 靠 timeText 里的日期区分
                // 「不同天的同类事件」，每日自动化隔天再跑不该被并成一条草稿
                val dayFmt = SimpleDateFormat("yyyy-M-d", Locale.getDefault())
                val ids = Graph.info.addPendingItems(
                    listOf(
                        io.github.m0use001.planability.data.PendingItem(
                            title = title, kind = "todo",
                            timeText = dayFmt.format(Date(AppTime.nowMillis())),
                            status = "pending", createdAt = AppTime.nowMillis(),
                            source = "automation",
                            payload = org.json.JSONObject().put("title", title).toString()
                        )
                    )
                )
                if (ids.isEmpty()) "已有同名草稿（助理中枢待审），未重复添加"
                else "已放进助理中枢待审（草稿 #" + ids.first() + "）：待办「" + title + "」，确认后才进时间轴"
            }

            "http_get" -> {
                val url = ctx.subst(p["url"].orEmpty())
                if (!url.startsWith("http")) throw IllegalArgumentException("URL 需以 http 开头")
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.connectTimeout = 8000
                conn.readTimeout = 8000
                try {
                    val code = conn.responseCode
                    if (code >= 400) throw IllegalStateException("HTTP $code")
                    val body = conn.inputStream.bufferedReader().use { it.readText() }.take(4000)
                    ctx.last = body
                    "HTTP $code · ${body.length} 字"
                } finally {
                    conn.disconnect()
                }
            }

            "ai" -> {
                val prompt = ctx.subst(p["prompt"].orEmpty())
                if (prompt.isBlank()) throw IllegalArgumentException("指令为空")
                val (_, answer) = runAnchored(ctx, prompt)
                ctx.last = answer
                "AI 回复 ${answer.length} 字"
            }

            // ---- v19.40：快捷指令动作（全部不调模型：零 token、确定性）----

            "app_open" -> {
                val built = io.github.m0use001.planability.core.AppJump.buildTarget(
                    app = ctx.subst(p["app"].orEmpty()).trim(),
                    activity = ctx.subst(p["activity"].orEmpty()).trim(),
                    uri = ctx.subst(p["uri"].orEmpty()).trim()
                )
                val err = io.github.m0use001.planability.core.AppJump.jump(built.first, built.second)
                if (err != null) throw IllegalStateException(err)
                "已跳转 → " + (built.second.label.ifBlank { built.first })
            }

            "wait" -> {
                val ms = (ctx.subst(p["ms"].orEmpty()).ifBlank { "500" }).filter { it.isDigit() }.toLongOrNull()
                    ?: throw IllegalArgumentException("毫秒不是数字")
                if (ms > 60_000L) throw IllegalArgumentException("等待上限 60 秒（防止一条流水线把设备占死）")
                kotlinx.coroutines.delay(ms)
                "已等待 ${ms}ms"
            }

            "vibrate" -> {
                val ms = (ctx.subst(p["ms"].orEmpty()).filter { it.isDigit() }.ifBlank { "60" })
                    .toLong().coerceIn(10L, 2_000L)
                val vm = context.getSystemService(android.os.VibratorManager::class.java)
                    ?: throw IllegalStateException("取不到振动服务")
                vm.defaultVibrator.vibrate(
                    android.os.VibrationEffect.createOneShot(ms, android.os.VibrationEffect.DEFAULT_AMPLITUDE)
                )
                "已振动 ${ms}ms"
            }

            "clipboard_write" -> {
                val text = ctx.subst(p["text"].orEmpty())
                if (text.isBlank()) throw IllegalArgumentException("内容为空")
                val cm = context.getSystemService(android.content.ClipboardManager::class.java)
                    ?: throw IllegalStateException("取不到剪贴板服务")
                cm.setPrimaryClip(android.content.ClipData.newPlainText("Planability", text))
                // Android 10+ 后台写剪贴板会弹系统提示——那不是我们的故障，先记进返回值免得被当 bug 查
                "已写入剪贴板（${text.length} 字）"
            }

            "sys_status" -> {
                val bm = context.getSystemService(android.os.BatteryManager::class.java)
                    ?: throw IllegalStateException("取不到 BatteryManager")
                val pct = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                // 返回值就是 $last：下一步 notify 直接引用它，不必经过模型
                "电量 ${pct}%，" + (if (bm.isCharging) "充电中" else "未充电")
            }

            "schedule_add" -> {
                val title = ctx.subst(p["title"].orEmpty())
                if (title.isBlank()) throw IllegalArgumentException("标题为空")
                val start = parseWhen(ctx.subst(p["time"].orEmpty()))
                    ?: throw IllegalArgumentException("看不懂时间（支持 HH:mm / 今天 HH:mm / 明天 HH:mm / yyyy-MM-dd HH:mm）")
                val min = (p["minutes"].orEmpty()).filter { it.isDigit() }.toIntOrNull()?.takeIf { it > 0 } ?: 60
                val end = start + min * 60_000L
                // 2026-11-12 与 todo_add 同修：不再直接 saveSchedule 绕过中枢，改落草稿。
                // 时间放 suggestedStart/suggestedEnd（PendingImporter 的兜底字段，毫秒无歧义），
                // payload 只带 title——不往 payload 里塞格式化文本让入库时再猜一遍。
                if (ctx.automationId == 0L) return "试运行：将建日程草稿「$title」（未真的建）"
                val fmt = SimpleDateFormat("yyyy-M-d HH:mm", Locale.getDefault())
                val ids = Graph.info.addPendingItems(
                    listOf(
                        io.github.m0use001.planability.data.PendingItem(
                            title = title, kind = "schedule",
                            timeText = fmt.format(Date(start)) + "-" + AppTime.millisToTime(end),
                            status = "pending", createdAt = AppTime.nowMillis(),
                            source = "automation",
                            suggestedStart = start, suggestedEnd = end,
                            payload = org.json.JSONObject().put("title", title).toString()
                        )
                    )
                )
                if (ids.isEmpty()) "已有同名草稿（助理中枢待审），未重复添加"
                else "已放进助理中枢待审（草稿 #" + ids.first() + "）：日程「" + title + "」，确认后才进时间轴"
            }

            // ---- v19.41：无障碍模拟操作 ----

            "◆_text" -> {
                val svc = a11y() ?: throw IllegalStateException(A11Y_OFF)
                val want = ctx.subst(p["text"].orEmpty()).trim()
                if (want.isEmpty()) throw IllegalArgumentException("没说要点哪个文字")
                val hits = svc.findNodesByText(want)
                ctx.count = hits.size
                if (hits.isEmpty()) {
                    // 最常见的原因是「界面还没打开」而不是「没有这个控件」——
                    // 所以错误信息直接教人加一步 wait，而不是只说「找不到」。
                    throw IllegalStateException("当前界面上找不到「$want」（页面可能还没打开，前面加一步 wait）")
                }
                val idx = (ctx.subst(p["index"].orEmpty()).filter { it.isDigit() }
                    .toIntOrNull() ?: 1).coerceIn(1, hits.size) - 1
                val node = hits[idx]
                val what = node.label.take(20)
                val ok = svc.tapNode(node)
                if (!ok) throw IllegalStateException("点不动「$what」（控件不可点击）")
                "已点击「$what」（第 ${idx + 1}/${hits.size} 个命中）"
            }

            "a11y_wait_text" -> {
                val svc = a11y() ?: throw IllegalStateException(A11Y_OFF)
                val want = ctx.subst(p["text"].orEmpty()).trim()
                if (want.isEmpty()) throw IllegalArgumentException("没说要等哪个文字")
                val sec = (ctx.subst(p["timeout"].orEmpty()).filter { it.isDigit() }
                    .toIntOrNull() ?: 20).coerceIn(1, 120)
                val deadline = System.currentTimeMillis() + sec * 1000L
                var seen = false
                var rootMissing = true
                while (System.currentTimeMillis() < deadline) {
                    val screen = svc.captureScreenText()
                    if (screen.isNotEmpty()) rootMissing = false
                    if (screen.contains(want)) { seen = true; break }
                    kotlinx.coroutines.delay(600)
                }
                // 空界面和「等不到」是两回事：空界面是权限/前台的问题，等下去也没用，
                // 报成「等不到」会让人以为是时序问题、去调超时时间，方向就错了。
                if (rootMissing) throw IllegalStateException("读不到界面内容（无障碍没生效或当前没有可读界面）")
                if (!seen) throw IllegalStateException("等 ${sec}s 也没等到「$want」")
                "已等到「$want」"
            }

            "a11y_scroll" -> {
                val svc = a11y() ?: throw IllegalStateException(A11Y_OFF)
                val dir = ctx.subst(p["dir"].orEmpty()).trim().ifBlank { "下" }
                val times = (ctx.subst(p["times"].orEmpty()).filter { it.isDigit() }
                    .toIntOrNull() ?: 1).coerceIn(1, 20)
                val dm = context.resources.displayMetrics
                val cx = dm.widthPixels / 2f
                val cy = dm.heightPixels / 2f
                val w = dm.widthPixels * 0.5f
                val h = dm.heightPixels * 0.5f
                var done = 0
                repeat(times) {
                    val ok = when (dir) {
                        // 方向按**手指**方向命名：「上」=手指往上划=看更新的内容，
                        // 这跟「向上翻页」的日常说法正好相反，所以文案里写明是手指方向。
                        "上" -> svc.swipeRaw(cx, cy - h, cx, cy + h, 300)
                        "下" -> svc.swipeRaw(cx, cy + h, cx, cy - h, 300)
                        "左" -> svc.swipeRaw(cx + w, cy, cx - w, cy, 300)
                        "右" -> svc.swipeRaw(cx - w, cy, cx + w, cy, 300)
                        else -> throw IllegalArgumentException("方向只认 上/下/左/右（手指方向），收到的是「$dir」")
                    }
                    if (ok) done++
                    kotlinx.coroutines.delay(350)
                }
                "已向$dir 滑动 $done/$times 次"
            }

            "run_shortcut" -> {
                val want = ctx.subst(p["name"].orEmpty()).trim()
                if (want.isEmpty()) throw IllegalArgumentException("没说要跑哪条指令")
                val all = io.github.m0use001.planability.data.AutomationRepo.all()
                // 先精确后包含：包含匹配是给「名字记不全」的人用的兜底，
                // 但必须排在精确后面，否则「备份」会抢走「备份到云端」。
                val target = all.firstOrNull { it.name.trim() == want }
                    ?: all.firstOrNull { it.name.contains(want) }
                    ?: throw IllegalStateException(
                        if (all.isEmpty()) "一条指令都没有"
                        else "没有叫「$want」的指令（现有：${all.take(6).joinToString("、") { it.name }}）"
                    )
                if (target.id != 0L && target.id == ctx.automationId) {
                    // 自己调自己 = 无限递归，每一层都在发通知、占连接，最后 ANR。
                    // 这条判据比深度上限更准：深度上限拦不住 A→B→A 这种环。
                    throw IllegalStateException("不能运行自己（会无限递归）")
                }
                io.github.m0use001.planability.notify.AutomationSweep.execute(context, target)
                // execute 是 suspend 且已经跑完了。读它刚落的那条执行记录来报真实结果，
                // 而不是回一句「已运行」——目标失败时后者是撒谎，而这一步唯一的价值就是知道成没成。
                val rec = io.github.m0use001.planability.data.AutomationRepo.runsOf(target.id, 1).firstOrNull()
                if (rec != null && !rec.ok) {
                    throw IllegalStateException("「${target.name}」跑失败：${rec.summary}")
                }
                val reason = p["reason"].orEmpty().trim()
                // Kotlin 没有三元运算符（只有 elvis `?:`），写成 `? :` 是语法错误
                val tail = if (reason.isEmpty() || rec == null) "" else "（$reason）"
                "已运行「${target.name}」$tail"
            }

            // ---- v19.43：操作系统设置 ----

            "sys_flashlight" -> {
                val cm = context.getSystemService(android.hardware.camera2.CameraManager::class.java)
                    ?: throw IllegalStateException("取不到相机服务")
                // 没有闪光灯的设备（平板/部分模拟器）直接说清楚，而不是等 setTorchMode 静默无效
                val camId = cm.cameraIdList.firstOrNull { id ->
                    runCatching {
                        cm.getCameraCharacteristics(id)
                            .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                    }.getOrDefault(false)
                } ?: throw IllegalStateException("这台设备没有闪光灯")
                val mode = ctx.subst(p["mode"].orEmpty()).trim().ifBlank { "闪" }
                when (mode) {
                    "开", "on" -> {
                        cm.setTorchMode(camId, true)
                        "手电已打开"
                    }
                    "关", "off" -> {
                        cm.setTorchMode(camId, false)
                        "手电已关闭"
                    }
                    "闪", "blink" -> {
                        val ms = (ctx.subst(p["ms"].orEmpty()).filter { it.isDigit() }
                            .toIntOrNull() ?: 1200).coerceIn(100, 10_000)
                        cm.setTorchMode(camId, true)
                        // 必须真的等够时长再关：设完立刻关等于闪了 0ms，用户看不到任何东西
                        kotlinx.coroutines.delay(ms.toLong())
                        cm.setTorchMode(camId, false)
                        "手电已闪 ${ms}ms"
                    }
                    else -> throw IllegalArgumentException("模式只认 开 / 关 / 闪（收到「$mode」）")
                }
            }

            "sys_volume" -> {
                val am = context.getSystemService(android.media.AudioManager::class.java)
                    ?: throw IllegalStateException("取不到音频服务")
                val stream = when (ctx.subst(p["stream"].orEmpty()).trim()) {
                    "", "音乐", "music" -> android.media.AudioManager.STREAM_MUSIC
                    "铃", "响铃", "ring" -> android.media.AudioManager.STREAM_RING
                    "通知", "notification" -> android.media.AudioManager.STREAM_NOTIFICATION
                    "闹钟", "alarm" -> android.media.AudioManager.STREAM_ALARM
                    "系统", "system" -> android.media.AudioManager.STREAM_SYSTEM
                    else -> throw IllegalArgumentException("声音流只认 音乐/铃/通知/闹钟/系统")
                }
                val levelRaw = ctx.subst(p["level"].orEmpty()).trim()
                // max 用系统真实上限，不用 MAX_VALUE：那个常量是 32767，
                // 传给 setStreamVolume 在部分机型上会直接被夹掉，用户看到「设了没反应」
                val max = ctx.subst(p["max"].orEmpty()).filter { it.isDigit() }.toIntOrNull()
                    ?: am.getStreamMaxVolume(stream)
                val cur = am.getStreamVolume(stream)
                val target = when (levelRaw) {
                    "静音", "mute" -> 0
                    "取消静音", "unmute" -> if (cur == 0) max else cur
                    "升", "up" -> (cur + max / 4).coerceAtMost(max)
                    "降", "down" -> (cur - max / 4).coerceAtLeast(0)
                    "", "+", "保持" -> cur
                    else -> levelRaw.filter { it.isDigit() }.toIntOrNull()
                        ?.let { pct ->
                            if (pct > 100) throw IllegalArgumentException("音量用 0-100 的百分数（收到 $pct）")
                            max * pct / 100
                        }
                        ?: throw IllegalArgumentException("看不懂音量「$levelRaw」（可用 0-100 / 静音 / 取消静音 / 升 / 降）")
                }
                am.setStreamVolume(stream, target, 0)
                "音量 $cur → $target（上限 $max）"
            }

            "sys_brightness" -> {
                // WRITE_SETTINGS 是「特殊权限」：没有它 putInt 直接 SecurityException。
                // 这里先把设置页拉起来再失败——用户跑完这一条就知道下一步该点哪里，
                // 而不是一个看不懂的「未授权」。
                if (!android.provider.Settings.System.canWrite(context)) {
                    openSystemSettings(context, android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS)
                    throw IllegalStateException(
                        "还没有「修改系统设置」权限，已帮你打开那个设置页：打开后重跑一次即可"
                    )
                }
                val levelRaw = ctx.subst(p["level"].orEmpty()).trim().ifBlank { "50" }
                // 注意：Settings.System 是**类**不是对象，不能 `val ss = Settings.System` 那样起别名
                // （那会报「does not have a companion object, so it cannot be used as an expression」）
                when {
                    levelRaw == "自动" || levelRaw == "auto" -> {
                        android.provider.Settings.System.putInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
                        )
                        "屏幕亮度已切到自动"
                    }
                    levelRaw == "手动" || levelRaw == "manual" -> {
                        android.provider.Settings.System.putInt(
                            context.contentResolver,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                            android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
                        )
                        "屏幕亮度已切到手动"
                    }
                    else -> {
                        val pct = levelRaw.filter { it.isDigit() }.toIntOrNull()
                            ?: throw IllegalArgumentException("亮度用 0-100 的百分数，或写 自动 / 手动")
                        if (pct > 100) throw IllegalArgumentException("亮度用 0-100 的百分数（收到 $pct）")
                        if (ctx.subst(p["apply"].orEmpty()).trim() == "1") {
                            // 0-255 是系统刻度；下限取 1 而不是 0，最暗也要看得见
                            // （0 在部分机型上就是全黑，用户会以为「设了没反应」）
                            android.provider.Settings.System.putInt(
                                context.contentResolver,
                                android.provider.Settings.System.SCREEN_BRIGHTNESS,
                                (255 * pct / 100).coerceIn(1, 255)
                            )
                            "屏幕亮度已设为 $pct%"
                        } else {
                            "屏幕亮度 $pct%（没写 apply=1，所以只算好数、没真改屏幕）"
                        }
                    }
                }
            }

            "sys_dnd" -> {
                val nm = context.getSystemService(android.app.NotificationManager::class.java)
                    ?: throw IllegalStateException("取不到通知服务")
                if (!nm.isNotificationPolicyAccessGranted) {
                    openSystemSettings(
                        context,
                        android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS
                    )
                    throw IllegalStateException(
                        "还没有「通知使用权」权限，已帮你打开那个设置页：打开后重跑一次即可"
                    )
                }
                val mode = ctx.subst(p["mode"].orEmpty()).trim().ifBlank { "开" }
                val level = ctx.subst(p["level"].orEmpty()).trim().ifBlank { "仅优先" }
                val on = when (mode) {
                    "开", "on" -> true
                    "关", "off" -> false
                    else -> throw IllegalArgumentException("模式只认 开 / 关（收到「$mode」）")
                }
                val filter = when {
                    !on -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    level == "全部" || level == "all" -> android.app.NotificationManager.INTERRUPTION_FILTER_ALL
                    level == "仅优先" || level == "priority" ->
                        android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY
                    else -> throw IllegalArgumentException("打扰级别只认 全部 / 仅优先（收到「$level」）")
                }
                nm.setInterruptionFilter(filter)
                if (!on) "勿扰已关闭" else "勿扰已开（" + (if (filter == android.app.NotificationManager.INTERRUPTION_FILTER_PRIORITY) "仅优先" else "全部") + "）"
            }

            "sys_panel" -> {
                val which = ctx.subst(p["which"].orEmpty()).trim().ifBlank { "WiFi" }
                // 用系统面板（Settings.Panel）而不是整个设置页：面板上就一个开关，
                // 配合 ◆_text 能直接点掉，比让用户自己在设置里翻要短得多。
                // 蓝牙**没有**公开面板常量（`Settings.Panel.ACTION_BLUETOOTH` 是 @hide，
                // javap android-36 的 android.jar 核实过，公开的只有
                // ACTION_WIFI / ACTION_NFC / ACTION_VOLUME / ACTION_INTERNET_CONNECTIVITY），
                // 所以退到经典设置页——那一页顶部就是蓝牙开关。
                val panel = when (which) {
                    "WiFi", "wifi", "WIFI" -> android.provider.Settings.Panel.ACTION_WIFI
                    "蓝牙", "bluetooth", "BT" -> android.provider.Settings.ACTION_BLUETOOTH_SETTINGS
                    else -> throw IllegalArgumentException("面板只认 WiFi / 蓝牙（收到「$which」）")
                }
                val opened = openSystemSettings(context, panel)
                if (!opened) {
                    "系统没给「$which」面板（这台 ROM 可能裁掉了），请改用其他方式"
                } else {
                    "已打开$which 面板（要自动点开关的话，下一步用「按文字点击」）"
                }
            }

            // ---- v19.44：真开关，走 A 侧刚补的串行化特权通道 ----
            // 三条纪律：
            //   1. 通道没就绪就**明确失败并指路**，不静默无效（静默无效的自动化最难查）
            //   2. 已经是要的状态就**不动**（`svc wifi enable` 在开着时也会走一遍开关流程）
            //   3. 发完命令**读回复核**：报"已打开"而实际没变是最坏的结果，
            //      因为后续步骤会基于错误状态继续走
            "sys_wifi", "sys_bt", "sys_airplane" -> {
                val key = def.key
                val label = when (key) {
                    "sys_wifi" -> "WiFi"
                    "sys_bt" -> "蓝牙"
                    else -> "飞行模式"
                }
                val readCmd = when (key) {
                    "sys_wifi" -> "settings get global wifi_on"
                    "sys_bt" -> "settings get global bluetooth_on"
                    else -> "settings get global airplane_mode_on"
                }
                if (!io.github.m0use001.planability.data.SandboxAccess.isReady()) {
                    throw IllegalStateException(
                        "沙箱通道没就绪，开不了「$label」真开关。" +
                            "去「文件 → 右上角锁」把调试端口授权一次；" +
                            "不想授权的话，改用「打开系统面板」+「按文字点击」那条路线"
                    )
                }
                val sb = io.github.m0use001.planability.data.SandboxAccess
                val mode = ctx.subst(p["mode"].orEmpty()).trim().ifBlank { "开" }
                val want: Boolean? = when (mode) {
                    "开", "on", "启用" -> true
                    "关", "off", "停用" -> false
                    "查", "状态", "status" -> null
                    else -> throw IllegalArgumentException("模式只认 开 / 关 / 查（收到「$mode」）")
                }
                val nowOn = sb.shell(readCmd, 15_000)?.trim() == "1"
                when {
                    want == null -> "「$label」现在是" + (if (nowOn) "开着" else "关着")
                    want == nowOn -> "「$label」本来就已经" + (if (want) "开着" else "关着") + "，没动"
                    else -> {
                        when (key) {
                            "sys_wifi" -> sb.shell(
                                "svc wifi " + (if (want) "enable" else "disable"), 15_000)
                            "sys_bt" -> sb.shell(
                                "svc bluetooth " + (if (want) "enable" else "disable"), 15_000)
                            else -> {
                                // 飞行模式要**两步**：先写全局设置，再广播。
                                // 只写 settings → 开关界面不动（用户看着没反应）；
                                // 只广播 → 这次变了，但下次开机读数还是旧的。
                                sb.shell("settings put global airplane_mode_on " +
                                    (if (want) 1 else 0), 15_000)
                                sb.shell("am broadcast -a android.intent.action.AIRPLANE_MODE" +
                                    " --ez state " + want, 15_000)
                            }
                        }
                        // 给系统一点生效时间再回读（`svc wifi` 是异步的）
                        kotlinx.coroutines.delay(1500)
                        val after = sb.shell(readCmd, 15_000)?.trim() == "1"
                        if (after == want) {
                            "「$label」已" + (if (want) "打开" else "关闭")
                        } else {
                            "「$label」命令发出去了，但读回来还是" +
                                (if (after) "开着" else "关着") +
                                "（这台 ROM 可能不允许 shell 改它）"
                        }
                    }
                }
            }

            else -> throw IllegalArgumentException("未实现的动作：${def.key}")
        }
    }

    /** 无障碍没开时统一用这句：只说「失败」的话用户不知道要去哪开 */
    private const val A11Y_OFF =
        "无障碍服务没开或没连上（设置 → 辅助功能 → 已下载的应用 → Planability → 打开）"

    /**
     * 拉起系统设置里的某个授权页/面板。返回 false = 这台 ROM 上拉不起来。
     *
     * 两个「特殊权限」页各自需要一点额外参数才能**直达本 App 的开关**，
     * 少给了用户就会看到一个列表还要自己找——所以这两个 case 收在函数里，不散在调用处。
     *
     * 必须带 `FLAG_ACTIVITY_NEW_TASK`：流水线跑在 Worker/后台，没有 Activity 上下文。
     */
    private fun openSystemSettings(context: Context, action: String): Boolean = runCatching {
        val i = android.content.Intent(action).apply {
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            when (action) {
                android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS ->
                    data = android.net.Uri.parse("package:" + context.packageName)
                android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS ->
                    putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
            }
        }
        context.startActivity(i)
        true
    }.getOrDefault(false)

    /**
     * 读屏通道（2026-11-26 开源根治）：经 [DeviceFeatures.a11y] 网关取——服务类本体
     * 在可选源集，公开版 NoOp（isBound=false，动作给 [A11Y_OFF] 提示）。
     */
    private fun a11y(): io.github.m0use001.planability.core.DeviceFeatures.A11yGateway? =
        io.github.m0use001.planability.core.DeviceFeatures.a11y.takeIf { it.isBound }

    /**
     * 解析"今天 08:00"这类相对时刻。看不懂返回 **null 而不是猜**——
     * 猜错会把日程建到错误的时间，那是比"建不了"更糟的结果。
     *
     * 只认三种写法是有意的：再往下就是一门自然语言解析器，
     * 而"说不清楚的时间"本就该交给 AI 路线（`ai` 动作）先解析成标准格式。
     */
    private fun parseWhen(text: String): Long? {
        val t = text.trim()
        if (t.isEmpty()) return null

        // 完整日期：yyyy-MM-dd HH:mm
        Regex("""^(\d{4})-(\d{2})-(\d{2})\s+(\d{1,2}):(\d{2})$""").find(t)?.let { m ->
            val g = m.groupValues
            return AppTime.millisOf(
                java.time.LocalDate.of(g[1].toInt(), g[2].toInt(), g[3].toInt()),
                g[4].toInt(), g[5].toInt()
            )
        }

        // 今天/明天/后天 HH:mm，或裸 HH:mm（= 今天）
        // 注意是顶层的 `kotlin.text.MatchResult`，不是 `Regex.MatchResult`——
        // 后者在 stdlib 里根本不存在（Regex 上没有这个嵌套类），写出来就是 Unresolved reference。
        val hm: kotlin.text.MatchResult
        val dayOffset: Long
        val bare = Regex("""^(\d{1,2}):(\d{2})$""").find(t)
        if (bare != null) {
            hm = bare; dayOffset = 0
        } else {
            val prefix = listOf("今天" to 0L, "明天" to 1L, "后天" to 2L)
                .firstOrNull { t.startsWith(it.first) } ?: return null
            val rest = t.removePrefix(prefix.first).trim()
            hm = Regex("""^(\d{1,2}):(\d{2})$""").find(rest) ?: return null
            dayOffset = prefix.second
        }
        val hour = hm.groupValues[1].toInt()
        val minute = hm.groupValues[2].toInt()
        if (hour !in 0..23 || minute !in 0..59) return null
        return AppTime.millisOf(AppTime.today().plusDays(dayOffset), hour, minute)
    }

    /**
     * AI 步骤复用与 AI 路线自动化**同一条锚定会话**（`auto.session:<id>`）——
     * 否则一条流水线跑 5 次就开 5 个会话，对话页被刷屏。
     */
    private suspend fun runAnchored(ctx: FlowContext, prompt: String): Pair<Long, String> {
        val kvKey = "auto.session:" + ctx.automationId
        var anchor = runCatching { Graph.settings.kvGet(kvKey)?.toLongOrNull() ?: 0L }.getOrDefault(0L)
        if (anchor != 0L && runCatching { Graph.chat.sessions().none { it.id == anchor } }.getOrDefault(true)) {
            anchor = 0L
        }
        val (sid, answer) = ChatEngine.runAction(
            "general", "自动化·" + ctx.title, prompt, sessionIdIn = anchor
        )
        if (anchor == 0L || anchor != sid) {
            runCatching { Graph.settings.kvPut(kvKey, sid.toString()) }
        }
        return sid to answer
    }

    // ---------- 文件搬运的公共部分 ----------

    /** 源文件集：显式 from 目录优先，否则用上一步扫描结果；都没有 → 精确报错 */
    private fun requireSource(ctx: FlowContext, p: Map<String, String>, action: String): List<File> {
        val from = p["from"].orEmpty()
        val list = if (from.isBlank()) ctx.files else run {
            val dir = resolve(ctx.subst(from))
            if (!dir.isDirectory) throw IllegalArgumentException("目录不存在：${rel(dir)}")
            dir.listFiles { f -> f.isFile && !f.name.startsWith(".") }?.toList() ?: emptyList()
        }
        if (list.isEmpty()) throw IllegalArgumentException("$action：没有源文件（先扫描或填来源目录）")
        return list
    }

    /** move/copy 共用：目标目录 + 逐个搬 + 重名自动编号 */
    private fun transfer(
        ctx: FlowContext,
        p: Map<String, String>,
        deleteSource: Boolean,
        copyFirst: Boolean
    ): String {
        val targets = requireSource(ctx, p, if (deleteSource) "移动" else "复制")
        val toPath = p["to"].orEmpty()
        if (toPath.isBlank()) throw IllegalArgumentException("没填目标目录")
        val dest = resolve(ctx.subst(toPath))
        if (!dest.isDirectory && !dest.mkdirs()) throw IllegalStateException("无法创建目标目录：${rel(dest)}")
        var n = 0
        targets.forEach { f ->
            val dst = uniqueDst(File(dest, f.name))
            val ok = if (copyFirst || !deleteSource) {
                f.copyTo(dst, overwrite = false).exists()
            } else {
                // 同一文件系统内 rename 是原子的；跨卷（极少见）退化成复制+删除
                f.renameTo(dst) || run {
                    f.copyTo(dst, overwrite = false).exists().also { if (it) f.delete() }
                }
            }
            if (!ok) throw IllegalStateException("搬运失败：${f.name}")
            n++
        }
        ctx.count = n
        return "$n 个 → ${rel(dest)}"
    }

    /** 目标已存在就编号：`a.txt` → `a (1).txt`，绝不覆盖同名文件 */
    private fun uniqueDst(dst: File): File {
        if (!dst.exists()) return dst
        val base = dst.name.substringBeforeLast('.', dst.name)
        val ext = if (dst.name.contains('.')) "." + dst.name.substringAfterLast('.') else ""
        var i = 1
        while (true) {
            val c = File(dst.parentFile, "$base ($i)$ext")
            if (!c.exists()) return c
            i++
        }
    }

    private fun moveInto(src: File, destDir: File) {
        val dst = uniqueDst(File(destDir, src.name))
        if (!src.renameTo(dst)) {
            src.copyTo(dst, overwrite = false)
            src.delete()
        }
    }

    private fun sanitize(name: String): String =
        name.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_").take(120).ifBlank { "未命名" }

    /** `*` `?` 通配；空模式 = 全匹配。转义后整串锚定，避免 `report.pdf.bak` 被 `*.pdf` 命中 */
    private fun matchGlob(pat: String, name: String): Boolean {
        if (pat.isBlank()) return true
        val rx = pat.split("*").joinToString(".*") { part ->
            part.split("?").joinToString(".") { Regex.escape(it) }
        }
        return Regex(rx, RegexOption.IGNORE_CASE).matches(name)
    }
}
