package io.github.m0use001.planability.ai.pool

import kotlin.math.max
import kotlin.math.min
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * 2026-11-02：**多账号上游池**（把本机 `D:\◆\Routability` 网关的核心调度算法
 * 移植进 App，让手机脱离电脑也能自己轮询免费额度）。
 *
 * ## 为什么需要
 * 免费/低价通道（AMD Radeon Cloud 的 MiMo、魔搭、NVIDIA NIM…）几乎都按 **RPM + 429**
 * 约束：单账号连发就进惩罚态，一挂就是一整天。Routability 在电脑上解决了这件事，
 * 但它只绑 127.0.0.1——手机出门就用不了。这里把同一套算法落在端内。
 *
 * ## 移植了什么（对照 route.py 的 Upstream 类）
 * | 机制 | Routability | 这里 |
 * |---|---|---|
 * | 60s 滑动窗 + 最小间隔 | `compute_wait` | [UpstreamState.nextAllowedAt] + [UpstreamState.sentAt] |
 * | 429 → RPM 减半 + 指数冷却 | `on_429` | [UpstreamState.on429] |
 * | 5xx/网络 → RPM ×0.7 + 连击 2 次起冷却 | `on_5xx` | [UpstreamState.on5xx] |
 * | 成功 20 连 → RPM ×1.1（封顶 max） | `on_success` | [UpstreamState.onSuccess] |
 * | 近期失败软降级（让位健康候选） | `is_demoted` | [UpstreamState.isDemoted] |
 * | 每日额度 | `daily_limit` | [UpstreamState.dailyRemaining] |
 * | 按优先级 + 最久未用调度 | `pick` | [UpstreamPool.pick] |
 * | 冷却中先看别人能否立刻用 | `pick`/等待分支 | [UpstreamPool.pick] 同款 |
 *
 * ## 刻意没移植
 * codebuddy / zen 这类需要特殊协议方言与指纹改写的渠道（端内没有对应凭据体系）、
 * 图片裁剪与上下文适配（[io.github.m0use001.planability.ai.AiClient] 已有 contextWindowOf 口径）。
 * 只做 OpenAI 兼容路径——这与 App 现有 provider 模型一致。
 *
 * ## 纯函数性
 * 本文件不依赖 Android（只用 kotlin 标准库 + 注入的时钟），全部逻辑都能 JVM 单测。
 */

/** 滑动窗口长度（秒）——与 Routability 的 WINDOW_SEC 一致：RPM 就是"60 秒内最多几次" */
const val WINDOW_SEC = 60.0

/** 近期失败软降级窗口（秒）：这段时间内出过 5xx/网络错的账号在调度上让位 */
const val FAIL_DEMOTE_SEC = 120.0

/** 上游单次状态机：限流窗口、自适应 RPM、冷却、每日额度。 */
class UpstreamState(
    val id: String,
    val name: String,
    /** 调度优先级：小的先选（NVIDIA=10 / 魔搭=40 / AMD=50，与 config.json 口径一致） */
    val priority: Int = 100,
    /** 起始 RPM（每分钟请求数） */
    startRpm: Double = 6.0,
    val rpmMin: Double = 2.0,
    val rpmMax: Double = 12.0,
    val cooldownBaseSec: Double = 60.0,
    val cooldownMaxSec: Double = 3600.0,
    /** 每日请求上限；0 = 不限 */
    val dailyLimit: Int = 0
) {
    var rpm: Double = startRpm
        private set

    /** 最近一次发送时间（用来算最小间隔） */
    var lastSentAt: Double = 0.0
        private set

    /** 60 秒窗口内的发送时间戳（按时间升序，只留 WINDOW_SEC 内） */
    private val sentAt = ArrayDeque<Double>()

    var cooldownUntil: Double = 0.0
        private set
    var lastFailAt: Double = 0.0
        private set

    private var strike429 = 0
    private var strike5xx = 0
    private var successStreak = 0
    private var lastBoostAt = 0.0

    /** 每日计数（按本地日期滚动） */
    private var dailyDay: String = ""
    private var dailyUsed: Int = 0

    // ---- 统计（给 UI/日志看） ----
    var sent = 0; private set
    var okCount = 0; private set
    var err429 = 0; private set
    var err5xx = 0; private set
    var errNet = 0; private set
    var lastStatus: Int? = null; private set
    var lastError: String? = null; private set

    private fun prune(now: Double) {
        while (sentAt.isNotEmpty() && sentAt.first() <= now - WINDOW_SEC) sentAt.removeFirst()
    }

    private fun rollDay(nowEpochSec: Double) {
        val day = javaTimeDay(nowEpochSec)
        if (day != dailyDay) {
            dailyDay = day
            dailyUsed = 0
        }
    }

    private fun javaTimeDay(epochSec: Double): String {
        val d = java.time.Instant.ofEpochSecond(epochSec.toLong())
            .atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        return d.toString()
    }

    /** 当前生效的最小发送间隔（秒） */
    fun minInterval(): Double = WINDOW_SEC / max(rpm, 0.5)

    /** 距离下一次允许发送还需等待多久（0 = 现在就能发） */
    fun waitSec(now: Double): Double {
        prune(now)
        var wait = 0.0
        if (lastSentAt > 0) wait = max(wait, lastSentAt + minInterval() - now)
        val cap = max(1, rpm.roundToInt())
        if (sentAt.size >= cap) wait = max(wait, sentAt[sentAt.size - cap] + WINDOW_SEC - now)
        return max(0.0, wait)
    }

    /** 冷却剩余秒（含每日额度用尽 → 等到明天） */
    fun cooldownRemaining(now: Double): Double {
        rollDay(now)
        var rem = max(0.0, cooldownUntil - now)
        if (dailyLimit > 0 && dailyUsed >= dailyLimit) {
            rem = max(rem, secondsUntilLocalMidnight(now))
        }
        return rem
    }

    private fun secondsUntilLocalMidnight(nowEpochSec: Double): Double {
        val zdt = java.time.Instant.ofEpochSecond(nowEpochSec.toLong())
            .atZone(java.time.ZoneId.systemDefault())
        val next = zdt.toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.systemDefault())
        return max(0.0, next.toEpochSecond() - nowEpochSec)
    }

    /** 近期出过 5xx/网络错 → 调度上让位 */
    fun isDemoted(now: Double): Boolean = (now - lastFailAt) < FAIL_DEMOTE_SEC

    /** 记录一次已发送（进入窗口 + 计日额度） */
    fun markSent(now: Double) {
        sentAt.addLast(now)
        lastSentAt = now
        sent++
        rollDay(now)
        dailyUsed++
    }

    fun onSuccess(now: Double) {
        strike429 = 0
        strike5xx = 0
        successStreak++
        lastFailAt = 0.0
        okCount++
        lastStatus = 200
        // 连续 20 次成功后按 1.1 倍缓慢提速（每分钟最多提一次）
        if (successStreak >= 20 && (now - lastBoostAt) >= WINDOW_SEC) {
            rpm = min(rpmMax, round(rpm * 1.1 * 1000) / 1000)
            successStreak = 0
            lastBoostAt = now
        }
    }

    fun on429(now: Double) {
        strike429++
        strike5xx = 0
        successStreak = 0
        rpm = max(rpmMin, round(rpm * 0.5 * 1000) / 1000)
        val sec = min(cooldownMaxSec, cooldownBaseSec * Math.pow(2.0, (strike429 - 1).toDouble()))
        cooldownUntil = max(cooldownUntil, now + sec)
        err429++
        lastStatus = 429
        lastError = "429 限流（冷却 ${sec.toInt()}s）"
    }

    fun on5xx(now: Double, status: Int? = null) {
        strike5xx++
        strike429 = 0
        successStreak = 0
        lastFailAt = now
        rpm = max(rpmMin, round(rpm * 0.7 * 1000) / 1000)
        // 单次 5xx 只软降级；连击 2 次才进冷却
        if (strike5xx >= 2) {
            val sec = min(900.0, cooldownBaseSec * Math.pow(2.0, (strike5xx - 2).toDouble()))
            cooldownUntil = max(cooldownUntil, now + sec)
        }
        err5xx++
        lastStatus = status
        lastError = "5xx（连续 $strike5xx 次）"
    }

    fun onNetworkError(now: Double) {
        errNet++
        lastError = "网络错误"
        on5xx(now)
    }

    /** 快照（日志/诊断用） */
    fun snapshot(now: Double): String {
        prune(now)
        return buildString {
            append("$id rpm=").append(round(rpm * 100) / 100)
            append(" 窗口=").append(sentAt.size).append('/').append(max(1, rpm.roundToInt()))
            val cd = cooldownRemaining(now)
            if (cd > 0) append(" 冷却=").append(cd.toInt()).append('s')
            if (dailyLimit > 0) append(" 今日=").append(dailyUsed).append('/').append(dailyLimit)
            append(" 429=").append(err429).append(" 5xx=").append(err5xx)
        }
    }
}

/**
 * 上游池：一组跑**同一个模型**的上游账号，负责挑一个现在能发的。
 *
 * 一个模型对应一个池（对应 Routability 的 `routes[model] = [upstreamId...]`）。
 */
class UpstreamPool(
    val modelKey: String,
    private val states: List<UpstreamState>
) {
    /** 全部上游快照（诊断页/日志） */
    fun snapshot(now: Double): List<String> = states.map { it.snapshot(now) }

    fun all(): List<UpstreamState> = states

    fun byId(id: String): UpstreamState? = states.firstOrNull { it.id == id }

    /**
     * 选一个上游。
     *
     * 与 Routability 的 `pick` 同序：
     * 1. 先排除本次已试过的（[tried]）；
     * 2. **不在冷却**的优先；全在冷却就退而取冷却最短的（调用方会等待）；
     * 3. 近期 5xx 的软降级让位健康候选；
     * 4. 按 (优先级, 最久未用, id) 排序取第一个。
     *
     * @return null = 没有可用上游（池空或全试过）
     */
    fun pick(now: Double, tried: Set<String> = emptySet()): UpstreamState? {
        val pool = states.filter { it.id !in tried }
        if (pool.isEmpty()) return null
        val fresh = pool.filter { it.cooldownRemaining(now) <= 0 }
        var use = if (fresh.isNotEmpty()) fresh else pool
        if (use.size > 1) {
            val healthy = use.filter { !it.isDemoted(now) }
            if (healthy.isNotEmpty()) use = healthy
        }
        return use.sortedWith(
            compareBy({ it.priority }, { it.lastSentAt }, { it.id })
        ).firstOrNull()
    }

    /**
     * 是否所有上游此刻都发不出去（都在冷却或都在限流间隔内）。
     * 调用方据此决定"等一会儿"还是"直接报错"。
     */
    fun allBlocked(now: Double): Boolean = states.all {
        it.cooldownRemaining(now) > 0 || it.waitSec(now) > 0
    }

    /** 全池最短等待时间（秒）；调用方用作退避时长 */
    fun shortestWait(now: Double): Double =
        states.minOfOrNull { s ->
            val cd = s.cooldownRemaining(now)
            if (cd > 0) cd else s.waitSec(now)
        } ?: 0.0
}
