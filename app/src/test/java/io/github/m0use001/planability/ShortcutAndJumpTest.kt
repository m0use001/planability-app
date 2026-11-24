package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 快捷指令化（v19.40）+ 跨应用跳转 的源码级断言。
 *
 * ## 为什么钉在源码层
 * 这几条漏掉都不会编译失败，只会表现成"**某个功能看着在、点了没反应**"：
 *  - 注册表里列了动作、exec 里没分支 → 运行到那一步才抛"未实现的动作"；
 *  - sweep 的过滤条件和"手动快捷指令"的定义不一致 → 手动的那条**半夜自己跑起来了**；
 *  - 包可见性权限没进去 → 跨应用列表一片空白，而界面上只表现为"0 个应用"；
 *  - tab 数量和 initialTab 上界不一致 → 从「我的 → 导入」进来直接落在隔壁标签。
 */
class ShortcutAndJumpTest {

    private fun repoFile(rel: String): File {
        // 工作目录先取成局部变量：原来写在字符串模板里 ${System.getProperty("user.dir")}，
        // 文件里那个引号前多出一个反斜杠（"user.dir"），把闭合引号转义掉了 ——
        // 字符串从这里开始就没关上，语法错误报到几十行外，后面全部级联成 Unresolved reference。
        val cwd = System.getProperty("user.dir") ?: "."
        var dir: File? = File(cwd).absoluteFile
        while (dir != null) {
            val f = File(dir, rel)
            if (f.isFile) return f
            dir = dir.parentFile
        }
        throw AssertionError("找不到 " + rel + "（工作目录 " + cwd + "）")
    }

    private fun src(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)

    private val registry by lazy {
        src("app/src/main/java/io/github/m0use001/planability/core/ActionRegistry.kt")
    }
    private val automationScreen by lazy {
        src("app/src/main/java/io/github/m0use001/planability/ui/info/AutomationScreen.kt")
    }
    private val worker by lazy {
        src("app/src/main/java/io/github/m0use001/planability/notify/AutomationWorker.kt")
    }
    private val manifest by lazy { src("app/src/main/AndroidManifest.xml") }

    @Test
    fun `注册表里的每个动作都有执行分支`() {
        // 注册表与实现是两份清单：少一个分支**编译照过**，只在那一步跑到时才抛
        // 「未实现的动作」——而那通常是半自动任务跑到一半。
        // 原始字符串**不要以引号收尾**：以 4 个引号结尾时 Kotlin 只认前 3 个当结束符，
        // 剩下一个孤立引号会让解析吞掉后面的正文，错误报到几十行开外（本文件实测踩过）。
        // 键名里可能有数字，字符类必须带 0-9：
        // 写成 [a-z_]+ 时带数字的键名只匹配出前缀，断言会报出与真实原因无关的错误。
        val keys = Regex("""ActionDef\(\s*"([a-z][a-z0-9_]*)""").findAll(registry)
            .map { it.groupValues[1] }.toList()
        assertTrue("ActionDef 一个都没解析到，正则或结构变了", keys.isNotEmpty())

        // 只在 FlowRunner 那一段里找分支，避免匹配到 defs 定义本身
        val runner = registry.substringAfter("object FlowRunner")
        val missing = keys.filter { !runner.contains("\"$it\" ->") }
        assertTrue(
            "注册了但 exec 没有分支的动作（会在跑到那一步时抛『未实现的动作』）：$missing",
            missing.isEmpty()
        )
        // 兜底分支本身也必须在：它是上面这条断言失效时唯一的运行期防线
        assertTrue(
            "FlowRunner 没有 else 兜底 —— when 作为表达式必须穷尽，这条一旦删掉就是编译期信号",
            runner.contains("未实现的动作")
        )
    }

    @Test
    fun `手动快捷指令不会被巡检跑起来`() {
        // 「手动」的定义就是"没有触发"，而 sweep 只跑有触发的——
        // 两条必须一致，否则你存的快捷指令会在半夜自己执行。
        assertTrue(
            "AutomationSweep 不再按 hasTrigger 过滤 —— 无触发的快捷指令会被当成到期任务跑掉",
            worker.contains("it.enabled && it.hasTrigger")
        )
        // 而 execute() 本身**不能**过滤，否则「运行」按钮点了什么都不会发生
        assertTrue(
            "AutomationSweep.execute 里不能有 hasTrigger 判断 —— 手动运行正是靠它不判触发才成立",
            worker.substringAfter("suspend fun execute").substringBefore("private suspend fun record")
                .let { !it.contains("hasTrigger") }
        )
    }

    @Test
    fun `快捷指令分组与运行入口都在`() {
        assertTrue(
            "编辑器没有「手动」触发档 —— 存不出无触发的快捷指令",
            automationScreen.contains("useManual")
        )
        assertTrue(
            "保存判据没放行手动档 —— 只认 hasTrigger 的话快捷指令永远存不下去",
            automationScreen.contains("!a.hasTrigger && !useManual")
        )
        assertTrue(
            "保存失败没给原因（原实现是静默的：按钮点了没反应）",
            automationScreen.contains("saveErr")
        )
        assertTrue(
            "列表没按 快捷指令 / 自动化 两栏分组",
            automationScreen.contains("SectionHeader(\"快捷指令\"") &&
                automationScreen.contains("SectionHeader(\"自动化\"")
        )
        assertTrue(
            "没有「运行」入口 —— 无触发的快捷指令这是唯一的执行方式",
            automationScreen.contains("AutomationSweep.execute")
        )
    }

    @Test
    fun `跨应用跳转的前提都在`() {
        assertTrue(
            "AndroidManifest 缺 QUERY_ALL_PACKAGES —— Android 11+ 只看得见 <queries> 声明过的包，" +
                "而跨应用跳转是开放式的，没有它列表必然是空的",
            manifest.contains("android.permission.QUERY_ALL_PACKAGES")
        )
        val jump = src("app/src/main/java/io/github/m0use001/planability/core/AppJump.kt")
        assertTrue("AppJump 缺少组件/深链/主页三条降级路径", jump.contains("KIND_DEEP") && jump.contains("KIND_LAUNCHER"))
        assertTrue(
            "jump 里不能有「cls 为空就 return null」—— 那个 null 在这里表示**成功**，" +
                "会把没组件的目标判成跳转成功、后面两条压根不执行（写的时候踩过）",
            !jump.contains("if (target.cls == null || uriOnly) null")
        )
        val appJumpScreen = src("app/src/main/java/io/github/m0use001/planability/ui/info/AppJumpScreen.kt")
        assertTrue("跳转页没显示「未导出」标记", appJumpScreen.contains("未导出"))
    }

    @Test
    fun `tab 数量与 initialTab 上界一致`() {
        // 调用方按 0=自动化 1=MCP 2=导入 传 initialTab（ProfileScreens/InfoCenterScreen），
        // 加 tab 必须**追加**而不是插中间，且上界要跟着走，否则入口错位。
        assertTrue(
            "标签顺序应保持 自动化/MCP/导入 在前三个位置",
            automationScreen.contains("""listOf("自动化", "MCP", "导入", "跳转")""")
        )
        assertTrue(
            "initialTab 的上界要跟着标签数走（否则从「我的 → 导入」进来会落在旧上限）",
            automationScreen.contains("initialTab.coerceIn(0, 3)")
        )
    }

    // ========== v19.41：AI 编排闭环（动作清单 → 编流水线 → 立刻跑）==========

    private val toolDefs by lazy {
        src("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt")
    }
    private val toolExec by lazy {
        src("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
    }

    @Test
    fun `动作清单必须来自注册表而不是另抄一份`() {
        assertTrue(
            "ToolDefs 里没有 automation_actions —— 模型看不见有哪些动作，就会照记忆编出 file_mov 这类名字",
            toolDefs.contains("name = \"automation_actions\"")
        )
        // 这一条才是要害：清单要是硬编码在工具里，注册表加了动作 AI 那边看不见，
        // 于是「AI 编排」只在下一次有人记得同步的时候成立。
        assertTrue(
            "automation_actions 的实现没读 ActionRegistry.defs —— 清单必须是注册表的投影",
            toolExec.contains("ActionRegistry.defs")
        )
        // 只读的查询不进写工具表，也不该弹审批卡
        val i = toolDefs.indexOf("name = \"automation_actions\"")
        val chunk = toolDefs.substring(i, (i + 400).coerceAtMost(toolDefs.length))
        assertTrue(
            "automation_actions 的风险档应该是 AUTO（只读查询）",
            chunk.contains("Risk.AUTO")
        )
    }

    @Test
    fun `AI 建流水线时会先挡住拼错的动作名`() {
        val i = toolExec.indexOf("\"automation_create\" ->")
        assertTrue("ToolExecImpls 里找不到 automation_create 分支", i >= 0)
        val seg = toolExec.substring(i, (i + 2600).coerceAtMost(toolExec.length))
        assertTrue(
            "automation_create 不认 steps —— AI 只能建 AI 型任务，做不出「完全不指挥 AI」的那类",
            seg.contains("optString(\"steps\")")
        )
        assertTrue(
            "建流水线时不校验动作名 —— 坏流水线要等到真跑那天才炸，那时用户早忘了建过什么",
            seg.contains("ActionRegistry.byKey")
        )
        assertTrue(
            "steps 解析失败必须当错误回，不能退化成空列表继续存（否则存出一条 stepsJson=[] 的空壳）",
            seg.contains("steps 解析不出来")
        )
        // 窗口要够长：校验块本身就有 40 行，kind=flow 在再往下的 draft 构造里
        assertTrue(
            "流水线任务没有标 kind=flow，执行时会走 AI 分支",
            toolExec.contains("kind = if (isFlow) \"flow\" else \"ai\"")
        )
    }

    @Test
    fun `立刻运行必须回报真实成败`() {
        val i = toolExec.indexOf("\"automation_run\" ->")
        assertTrue("ToolDefs/ToolExec 里没有 automation_run", toolDefs.contains("name = \"automation_run\"") && i >= 0)
        val seg = toolExec.substring(i, (i + 1400).coerceAtMost(toolExec.length))
        // execute() 是 suspend 且已跑完；不回读执行记录的话，失败也会被报成「已运行」，
        // 而"成没成"恰恰是这个工具唯一要回答的问题。
        assertTrue(
            "automation_run 没有回读执行记录 —— 失败会被报成成功",
            seg.contains("runsOf")
        )
        assertTrue(
            "automation_run 找不到指令时应报出现有指令名，而不是只说找不到",
            seg.contains("没有叫")
        )
    }

    @Test
    fun `指令互相调用有环的护栏`() {
        // run_shortcut 让指令能调指令。没有护栏就是 A→B→A，每层都发通知占连接，最后 ANR。
        assertTrue(
            "没有自引用判据（自己调自己是最容易踩的环）",
            registry.contains("不能运行自己")
        )
        assertTrue(
            "没有嵌套深度闸门 —— A→B→C→D 这种链也该有个上限",
            registry.contains("MAX_NEST")
        )
        // ThreadLocal 在这里是不成立的：run() 内部 withContext(IO)，嵌套那次会落到别的线程
        assertTrue(
            "深度计数用了 ThreadLocal —— 嵌套那次会跑到另一个 IO 线程上，拦不住",
            !registry.contains("ThreadLocal.withInitial")
        )
    }

}
