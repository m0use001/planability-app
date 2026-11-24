package io.github.m0use001.planability.app

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
// `updateAll` 是 androidx.glance.appwidget 的**顶层扩展函数**（字节码里在
// GlanceAppWidgetKt 这个文件类上），不是 GlanceAppWidget 的成员函数——
// 不 import 扩展本身就会得到 "Unresolved reference 'updateAll'"（javap glance-appwidget-1.1.1 的 classes.jar 核实）
import androidx.glance.appwidget.updateAll
import android.net.Uri
import io.github.m0use001.planability.data.Automation
import io.github.m0use001.planability.data.AutomationRepo
import io.github.m0use001.planability.notify.AutomationSweep
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * v19.43：把自动化/快捷指令发到**桌面**（长按 App 图标 → 直接跑）。
 *
 * ## 落地靠的是已有机制，不是新窗口
 * `MainActivity.handleShortcut()` 是一张按 action 分流的路由表（待办/倒计时/专注/通知跳转都在那），
 * 本文件只做两件它没做的事：**发布**这份清单、以及在路由表上加一个 action 分支。
 * 不新开 Activity 的理由：主界面已经在跑（`singleTop` + `onNewIntent` 现成的），
 * 新开窗口会让用户看到一个空白壳再跳走，多一次闪烁也多一点出错面。
 *
 * ## 三条容易踩的规矩（都写在代码里，不是文档里）
 * 1. **每个快捷方式的 Intent 必须唯一**。同 action + 同 extra 的两个 Intent 会被启动器
 *    当成同一个，于是两个快捷方式点下去跑的是同一条。做法是给每个 Intent 一个
 *    **唯一 data Uri**（`planability://automation/<id>`）——这是官方文档指定的去重维度。
 * 2. **不能超过 `getMaxShortcutCountPerActivity()`**。超了 `setDynamicShortcuts` 直接抛异常，
 *    所以这里先排序再截断，而不是先全传再靠 catch 兜。
 * 3. **发布有配额**：`isRateLimitingActive` 为真时系统的更新请求被限流，
 *    这时候还去发就是白白消耗额度、把之后真正的更新也挤掉。正确做法是这轮直接跳过。
 */
object AutomationShortcuts {

    /** 与 `MainActivity.handleShortcut` 里的分支一一对应 */
    const val ACTION_RUN = "io.github.m0use001.planability.RUN_AUTOMATION"
    const val EXTRA_AUTOMATION_ID = "automationId"

    private val scope = CoroutineScope(Dispatchers.Default)

    /** 一条自动化 → 触发它的 Intent（唯一 data 是关键，见类注释第 1 条） */
    fun intentFor(id: Long, label: String): Intent =
        Intent(Intent.ACTION_MAIN, Uri.parse("planability://automation/$id"))
            .setClassName("io.github.m0use001.planability", "io.github.m0use001.planability.MainActivity")
            .putExtra(EXTRA_AUTOMATION_ID, id)
            .putExtra(Intent.EXTRA_TITLE, label)

    /**
     * 桌面承载共用的一份清单：**手动（快捷指令）在前**，然后按「最近跑过的在前」。
     *
     * 长按图标与小组件都读它——两处各排一次的话，改排序规则时一定会漏掉一处，
     * 然后用户看到「长按里在前的是 A、小组件里在前的是 B」。
     *
     * 手动优先是因为这两个入口都是「我现在就想做点什么」的场景，按间隔/每日时刻那些属于
     * 「到点自动做」，把它们排在前面会让人以为这里是配置面板。
     */
    suspend fun listForLauncher(): List<Automation> =
        AutomationRepo.all().filter { it.enabled }.sortedWith(
            compareBy<Automation> { if (it.hasTrigger) 1 else 0 }
                .thenByDescending { it.lastRun }
                .thenByDescending { it.createdAt }
        )

    /** 同步发布。返回真正发出去几条（配额限流/上限为 0 时会是 0） */
    suspend fun publish(context: Context): Int {
        // **不要用 `ShortcutManager.from(context)`**：那个静态方法是 @hide 的系统 API，
        // 公开 SDK 里根本没有（javap android-36 的 android.jar 核实：公开的只有 getSystemService）。
        // 照记忆写就会得到一个莫名其妙的 "Unresolved reference 'from'"。
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return 0
        if (sm.isRateLimitingActive) {
            // 限流期间发布等于把额度花在一次没人看的更新上
            return 0
        }
        val all = listForLauncher()
        val cap = sm.maxShortcutCountPerActivity
        if (cap <= 0) return 0
        val take = all.take(cap)
        val infos = take.map { a ->
            ShortcutInfo.Builder(context, "automation-${a.id}")
                .setShortLabel(a.name.take(24))
                .setLongLabel("${a.name}｜${triggerLabel(a)}")
                // 用应用图标：一条指令一个专属图标要自己画，成本远大于收益
                .setIcon(Icon.createWithResource(context, io.github.m0use001.planability.R.mipmap.ic_launcher))
                .setIntent(intentFor(a.id, a.name))
                .setLongLived(true)
                .build()
        }
        // 失败也不该把调用方带崩：桌面快捷方式是锦上添花，跑不了自动化照跑
        runCatching { sm.dynamicShortcuts = infos }.onFailure { return 0 }
        return infos.size
    }

    /** 异步发布（UI 侧调用：保存/删除之后） */
    fun publishAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { runCatching { publish(app) } }
    }

    /**
     * 请求把某条指令**钉**成桌面上的独立图标。
     *
     * 返回 false = 这台 ROM/启动器不支持或拒绝了（常见于用户关掉了「允许添加快捷方式」）。
     * 这不是错误——它本来就只是个便利功能，UI 上不弹审批卡。
     */
    fun pin(context: Context, a: Automation): Boolean {
        val sm = context.getSystemService(ShortcutManager::class.java) ?: return false
        if (sm.isRequestPinShortcutSupported.not()) return false
        val info = ShortcutInfo.Builder(context, "automation-pinned-${a.id}")
            .setShortLabel(a.name.take(24))
            .setLongLabel("${a.name}｜点一下就跑")
            .setIcon(Icon.createWithResource(context, io.github.m0use001.planability.R.mipmap.ic_launcher))
            .setIntent(intentFor(a.id, a.name))
            .build()
        return runCatching { sm.requestPinShortcut(info, null); true }.getOrDefault(false)
    }

    /** 卡片上的触发描述（与自动化页那段文案同口径，但这里不能依赖 UI 层的私有函数） */
    private fun triggerLabel(a: Automation): String = when {
        !a.hasTrigger -> "手动"
        a.hasEventTrigger -> io.github.m0use001.planability.notify.AutomationSignal.describe(a.triggerKind, a.triggerArg)
        a.intervalMinutes > 0 -> "每 ${a.intervalMinutes} 分钟"
        a.dailyHour >= 0 -> "每日 %02d:%02d".format(a.dailyHour, a.dailyMinute)
        else -> "自动"
    }

    /**
     * 被桌面图标点中时的执行入口。放在这里而不是 Activity 里，
     * 是为了让「跑一条指令」这件事**只有一份实现**（UI 的运行按钮、AI 的 automation_run、
     * 桌面图标全都走 `AutomationSweep.execute`），执行记录与通知因此完全一致。
     */
    suspend fun runById(context: Context, id: Long): Boolean {
        val a = AutomationRepo.all().firstOrNull { it.id == id } ?: return false
        AutomationSweep.execute(context, a)
        return true
    }
}