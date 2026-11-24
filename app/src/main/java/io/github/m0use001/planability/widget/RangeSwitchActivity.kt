package io.github.m0use001.planability.widget

import android.os.Bundle
import androidx.activity.ComponentActivity
import kotlinx.coroutines.launch

/**
 * 「多天总览」范围直选的**透明中转页**（2026-10-28）。
 *
 * 为什么是 Activity 而不是 actionRunCallback（广播 trampoline）：
 * - 广播在 ColorOS 上受后台限制 + 进程冷启动，用户实测「点击后切换延迟极大」甚至整段被吞
 *   （「完全无法切换」）；组件点按启动 Activity 不受后台启动限制，链路毫秒级。
 * - 直选三段需要三份互不相同的 PendingIntent：actionRunCallback 的 Intent 只有 receiver
 *   组件名（filterEquals 不含 extras），同组件多 callback 全命中第一份（829303c7 实测回滚）。
 *   现在三个热区的 Intent 各带**不同的 data Uri**（filterEquals 包含 data），
 *   由本页从 `intent.data` 读回档位——去重坑与直选一并解决。
 *
 * 职责只有一件事：**前台门票**——写档位偏好 → 把推送闭环派发给进程级作用域 →
 * 本帧 finish（2026-11-02 五修：闭环在页内等完要 250-530ms，用户感知为「尝试进入软件
 * 然后被打断」；Activity 只负责让进程拿到前台优先级，推送全在后台完成）。
 * noHistory + excludeFromRecents + 透明主题，用户全程无感。
 */
class RangeSwitchActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ordinal = intent?.data?.lastPathSegment?.toIntOrNull()
        val current = WidgetRangeStore.current(this).ordinal
        val changed = ordinal != null && ordinal != current
        if (changed) {
            WidgetRangeStore.set(this, ordinal)
            android.util.Log.i(
                "RangeWidget",
                "直选 ${WidgetArt.Range.entries.getOrElse(ordinal) { WidgetArt.Range.Month }}（Activity 中转）"
            )
            // 2026-11-02 五修（用户「每次切换都尝试进入软件然后被打断」）：透明中转页是
            // ColorOS 广播限流的解（前台门票），但 v2-v4 的推送闭环要 250-530ms——
            // Activity 开着这么久，桌面端就能感知「进软件又退出」。改为**开门即走**：
            // 闭环挪到进程级作用域（[RangeBitmapCache.scope]），Activity 本帧 finish。
            // finish 后进程仍有数秒冻结宽限期，闭环全程 <600ms，被截断的风险可忽略；
            // 即便截断，30 分钟 updatePeriod 也会按持久化档位兜底重绘。
            val appCtx = applicationContext
            val range = WidgetArt.Range.entries.getOrElse(ordinal) { WidgetArt.Range.Month }
            val cold = RangeBitmapCache.lastLayout == null
            val t0 = android.os.SystemClock.elapsedRealtime()
            RangeBitmapCache.scope.launch {
                runCatching {
                    val data = buildRangeData(range)
                    RangeBitmapCache.warmOne(appCtx, range, data)
                }
                val pushed = pushRangeWidgetVerified(appCtx)
                android.util.Log.i(
                    "RangeWidget",
                    "直选完成：cold=$cold 推送确认=$pushed 取数+渲染+推送 ${android.os.SystemClock.elapsedRealtime() - t0}ms（中转页已退场）"
                )
            }
            finish()
        } else {
            // 2026-11-01 用户「点第一下可以、剩下不行」：点到的**已经是当前档**时原先静默
            // finish，界面上毫无反馈（位图本来就是这个档，推送也看不出变化），体感就是
            // 「点了没反应」。仍然重推一次位图（防止 launcher 缓存与实际档位脱节时
            // 永远纠不回来）。同样开门即走（2026-11-02 五修，理由同上）。
            android.util.Log.i("RangeWidget", "点到的已是当前档=$current，重推一次（原先静默 finish）")
            val appCtx = applicationContext
            RangeBitmapCache.scope.launch {
                pushRangeWidgetVerified(appCtx)
            }
            finish()
        }
    }
}
