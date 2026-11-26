package io.github.m0use001.planability.notify

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import io.github.m0use001.planability.core.DeviceFeatures
import io.github.m0use001.planability.data.Graph

/**
 * 采集类自动化（AI 路线的 phone_* 工具、流水线的 app_open/a11y_* 步骤）跑完之后
 * 把前台「还」给用户——采集期间手机会被带到微信/QQ/设置等别的应用，跑完停在那里
 * 会让用户以为手机失控了（群聊采集那条链路已有同样的收尾，见 GroupCaptureEngine
 * 的 finally：「停止/异常中断时把用户带回本应用」；这里把同一语义补给定时/事件触发的自动化）。
 *
 * ## 判定表（见 [decide]，纯函数、单测覆盖）
 *  - 前台没变（== 跑前）→ 自动化没有霸占前台（比如纯后台的通知类任务）→ 不动；
 *  - 现在在自家 / 桌面 → 已经是干净状态 → 不动；
 *  - 跑前是自家（用户本来在 Planability 里）→ 拉回 Planability（比扔去桌面更贴合「原位」）；
 *  - 其他（跑前在 X、跑完停在自动化打开的 Y）→ 回桌面。
 *
 * **不把用户拉回任意第三方应用 X**：`getLaunchIntentForPackage(X)` 只能落到 X 的主界面，
 * 会丢掉用户在 X 里的上下文；部分应用（银行/锁屏保护）还会拒绝后台拉起。
 * 回桌面是可预测的中性状态，与 GroupCaptureEngine「带回本应用」的取向一致。
 *
 * ## 不动手的两种情况（写清楚，免得后人以为漏了）
 *  - 无障碍服务没开（读屏采集网关为 null，设备功能包未安装时即此态）：没有全局动作通道，
 *    后台又不允许 startActivity 拉别的应用——什么都不做，别硬来；
 *  - 读不到前台（currentForegroundPkg() 返回 null，微信/QQ 反自动化窗口下可能为空）：
 *    瞎按 HOME 可能把正在用手机的用户踢回桌面，宁可不动。
 *
 * 开关：KV `auto.returnHome`，值为 `off` 时整体关闭；缺省（未设）= 开启。
 */
object AutoReturn {

    /** 与 [io.github.m0use001.planability.ai.ptl.ToolExecImpls] 的 KV 风格一致：字符串字面量，缺省=开 */
    const val KV = "auto.returnHome"

    /** 判定结果：不动 / 回桌面 / 拉回自家 */
    enum class Action { NONE, HOME, BACK_TO_OWN }

    /**
     * 纯判定函数。参数全部显式传入（含 launcher 包名，由系统 resolveActivity 得出，
     * ColorOS 是 com.oppo.launcher、类原生是 launcher3——写死字符串必漏）。
     *
     * 顺序不能换：先判「现在已经在干净状态」再判「前台变没变」——
     * 用户中途自己回了桌面时 nowFg=launcher，若先比 prevFg≠nowFg 会误判成要拉回。
     */
    fun decide(prevFg: String?, nowFg: String?, own: String, launcherPkg: String?): Action {
        if (prevFg.isNullOrBlank() || nowFg.isNullOrBlank()) return Action.NONE
        if (nowFg == own) return Action.NONE
        if (launcherPkg != null && nowFg == launcherPkg) return Action.NONE
        if (prevFg == nowFg) return Action.NONE
        return if (prevFg == own) Action.BACK_TO_OWN else Action.HOME
    }

    /** 当前桌面（HOME）应用的包名。resolve 不到返回 null（decide 里按「未知」处理，不影响其余分支） */
    fun launcherPkg(context: Context): String? = runCatching {
        context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY
        )?.activityInfo?.packageName
    }.getOrNull()

    /** 跑自动化之前调用，记住当时的前台（没开无障碍时为 null，restore 会按兵不动） */
    fun currentFg(): String? = DeviceFeatures.a11y.currentForegroundPkg()

    /**
     * 跑完自动化之后调用：按 [decide] 的结果把前台还给用户。
     * 全程 runCatching——收尾动作绝不允许把执行记录/通知那半截带崩。
     */
    suspend fun restore(prevFg: String?) {
        runCatching {
            if (Graph.settings.kvGet(KV) == "off") return
            // 2026-11-26 开源根治：读屏通道经 DeviceFeatures.a11y（公开版 NoOp=按兵不动）
            val a11y = DeviceFeatures.a11y
            if (!a11y.isBound) return
            val ctx = Graph.appContext
            val now = a11y.currentForegroundPkg() ?: return
            when (decide(prevFg, now, ctx.packageName, launcherPkg(ctx))) {
                Action.BACK_TO_OWN -> {
                    val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
                        ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                    if (intent != null) ctx.startActivity(intent)
                }
                Action.HOME -> a11y.pressGlobal(
                    android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME
                )
                Action.NONE -> Unit
            }
        }
    }
}
