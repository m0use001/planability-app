package io.github.m0use001.planability.focus

import android.content.Intent
import android.service.quicksettings.TileService
import io.github.m0use001.planability.MainActivity
import io.github.m0use001.planability.data.Graph

/** 快速设置瓷贴：轻点开始番茄钟（按番茄设置的时长）；再点暂停/继续 */
class PomodoroTileService : TileService() {

    override fun onStartListening() {
        update()
    }

    override fun onTileAdded() {
        update()
    }

    override fun onClick() {
        val ctx = applicationContext
        val workMin = runCatching { Graph.settings.snapshot.value.pomoWorkMin }.getOrDefault(25)
        when (FocusTimerState.phase.value) {
            FocusTimerState.Phase.Running -> FocusTimerService.pause(ctx)
            FocusTimerState.Phase.Paused -> FocusTimerService.resume(ctx)
            FocusTimerState.Phase.Finished, FocusTimerState.Phase.Idle -> {
                runCatching { FocusTimerService.start(ctx, workMin) }.onFailure {
                    // 后台启动前台服务受限时，退回打开应用
                    runCatching {
                        startActivityAndCollapse(
                            Intent(this, MainActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
            }
        }
        update()
    }

    private fun update() {
        val tile = qsTile ?: return
        val phase = FocusTimerState.phase.value
        val active = phase == FocusTimerState.Phase.Running || phase == FocusTimerState.Phase.Paused
        val stageLabel = when (FocusTimerState.stage.value) {
            FocusTimerState.Stage.Work -> if (FocusTimerState.countUp) "正计时" else "专注"
            FocusTimerState.Stage.ShortBreak -> "短休"
            FocusTimerState.Stage.LongBreak -> "长休"
        }
        tile.label = "番茄钟"
        tile.subtitle = when (phase) {
            FocusTimerState.Phase.Running -> "$stageLabel…（点按暂停）"
            FocusTimerState.Phase.Paused -> "$stageLabel 暂停（点按继续）"
            FocusTimerState.Phase.Finished -> "已完成（点按再来一轮）"
            else -> "轻点开始番茄"
        }
        tile.state = if (active) 2 /* STATE_ACTIVE */ else 1 /* STATE_INACTIVE */
        tile.updateTile()
    }
}
