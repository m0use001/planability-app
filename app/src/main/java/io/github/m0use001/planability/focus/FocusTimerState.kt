package io.github.m0use001.planability.focus

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** 番茄钟共享状态：UI 与前台服务共同观测（进程内单例）。 */
object FocusTimerState {
    enum class Phase { Idle, Running, Paused, Finished }

    /** 番茄阶段：专注 / 短休 / 长休 */
    enum class Stage { Work, ShortBreak, LongBreak }

    private val _phase = MutableStateFlow(Phase.Idle)
    val phase: StateFlow<Phase> = _phase

    private val _remainSec = MutableStateFlow(0)
    val remainSec: StateFlow<Int> = _remainSec

    private val _totalSec = MutableStateFlow(0)
    val totalSec: StateFlow<Int> = _totalSec

    private val _stage = MutableStateFlow(Stage.Work)
    val stage: StateFlow<Stage> = _stage

    private val _round = MutableStateFlow(1)
    val round: StateFlow<Int> = _round

    /** 当前阶段时长（分钟）：Work=专注时长 / 休息=休息时长 */
    var plannedMin: Int = 25
        private set

    /** 正计时模式（Work 阶段不倒计时，只累计） */
    var countUp: Boolean = false
        private set

    /** 正计时已累计秒数（Work 阶段） */
    var elapsedSec: Int = 0
        private set

    /** 长休前的工作轮数 */
    var roundsTotal: Int = 4
        private set

    val running: Boolean get() = phase.value == Phase.Running

    /** 开始一轮番茄流程（从 Work 起步）；countUpMode 时 min 仅作展示默认 */
    fun startFocus(min: Int, countUpMode: Boolean, rounds: Int) {
        roundsTotal = rounds.coerceAtLeast(1)
        countUp = countUpMode
        _round.value = 1
        if (countUpMode) {
            // 正计时：Work 阶段没有总时长，**从 0 起算**（2026-11-03：原先借用 enterStage
            // 的 25 分占位，第一秒就被 elapsed 覆盖，界面先闪一下 25:00 再跳到 0:0x）。
            _stage.value = Stage.Work
            plannedMin = min.coerceAtLeast(1)
            _totalSec.value = 0
            _remainSec.value = 0
            elapsedSec = 0
            _phase.value = Phase.Running
        } else {
            enterStage(Stage.Work, min)
        }
    }

    /** 切入下一阶段并立即 Running */
    fun enterStage(stage: Stage, min: Int) {
        _stage.value = stage
        plannedMin = min.coerceAtLeast(1)
        _totalSec.value = plannedMin * 60
        _remainSec.value = plannedMin * 60
        elapsedSec = 0
        _phase.value = Phase.Running
    }

    /** Work 完成 → 应进入的休息阶段 */
    fun nextAfterWork(): Stage =
        if (_round.value >= roundsTotal) Stage.LongBreak else Stage.ShortBreak

    /** 休息完成 → 回 Work（轮数+1）；长休结束返回 null（整轮完成） */
    fun nextAfterBreak(): Stage? {
        return when (_stage.value) {
            Stage.ShortBreak -> {
                _round.value += 1
                Stage.Work
            }
            else -> null
        }
    }

    fun pause() {
        if (_phase.value == Phase.Running) _phase.value = Phase.Paused
    }

    fun resume() {
        if (_phase.value == Phase.Paused) _phase.value = Phase.Running
    }

    /**
     * 每秒 tick。返回本次完成的阶段，未完成返回 null。
     * 正计时的 Work 永不完成，只把 remainSec 当 elapsedSec 累加。
     */
    fun tick(): Stage? {
        if (_phase.value != Phase.Running) return null
        val st = _stage.value
        if (st == Stage.Work && countUp) {
            elapsedSec += 1
            _remainSec.value = elapsedSec
            return null
        }
        val r = _remainSec.value - 1
        return if (r <= 0) {
            _remainSec.value = 0
            _phase.value = Phase.Finished
            st
        } else {
            _remainSec.value = r
            null
        }
    }

    /** 手动结束正计时：返回已专注分钟数 */
    fun stopCountUpMinutes(): Int = elapsedSec / 60

    /**
     * 进度环比例；**null = 这一幕不画进度弧**。
     *
     * 2026-11-03 用户「正计时还显示一个环」：正计时（Work 阶段）没有总时长——
     * [remainSec] 是**已用秒数**、[totalSec] 是 25 分钟占位，`remain/total` 会从 1
     * 瞬间掉到 ≈0 再花 25 分钟爬满，环的语义是错的。正计时一律返回 null（UI 只画轨道环）；
     * 倒计时（专注/短休/长休）按 remain/total；Idle 也返回 null。
     */
    fun ringFraction(): Float? {
        if (_phase.value == Phase.Idle) return null
        if (_stage.value == Stage.Work && countUp) return null
        val total = _totalSec.value
        if (total <= 0) return null
        return (_remainSec.value.toFloat() / total).coerceIn(0f, 1f)
    }

    fun reset() {
        _phase.value = Phase.Idle
        _remainSec.value = 0
        _totalSec.value = 0
        elapsedSec = 0
        countUp = false
        _stage.value = Stage.Work
        _round.value = 1
    }
}
