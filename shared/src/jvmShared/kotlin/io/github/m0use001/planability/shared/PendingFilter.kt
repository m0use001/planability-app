package io.github.m0use001.planability.shared

import java.time.LocalDate

/**
 * 待处理草稿的批量筛选谓词（pending_query / pending_purge 共用；纯函数，单测可直调）。
 * M2 从 app/ai/ptl/PendingFilter.kt 迁入（jvmShared：用 java.time，无 Android 依赖）。
 *
 * 为什么按 timeText 而不是 createdAt 分月：2026-11 真机库里 1360 条待处理**全是 11 月创建的**
 * （信息提取批量跑出来），而用户说的「9 月的」指的是草稿里的事件日期（「10月24日」这种）。
 * 按 createdAt 分月对这批数据毫无区分度。
 */
object PendingFilter {

    /** 月份归一：接受 "10" / "10月" / "2026-10"，输出两位 "10"；越界/看不懂返回 null */
    fun normMonth(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val mm = Regex("""^\d{4}-(\d{1,2})$""").find(t)?.groupValues?.get(1)
            ?: Regex("""^(\d{1,2})月?$""").find(t)?.groupValues?.get(1)
            ?: return null
        return mm.toIntOrNull()?.takeIf { it in 1..12 }?.toString()?.padStart(2, '0')
    }

    /**
     * timeText 是否属于某月。覆盖真机里的实际写法：
     * 「10月24日（周六）」「2026年10月10日 17:00」「10/24」「10-24」「10月21日 21:00」。
     * 无时间描述（空串）一律不算命中。
     */
    fun timeTextInMonth(timeText: String, month2: String): Boolean {
        if (timeText.isBlank()) return false
        val m = month2.trimStart('0').ifEmpty { "0" }
        val patterns = listOf(
            Regex("(?:^|[^\\d])" + Regex.escape(m) + "月"),
            Regex("(?:^|[^\\d])" + Regex.escape(m) + "/\\d{1,2}"),
            Regex("\\d{4}-0?" + Regex.escape(m) + "-\\d{1,2}"),
        )
        return patterns.any { it.containsMatchIn(timeText) }
    }

    /** "true/false/空" → Boolean?（空=不限） */
    fun normTriState(raw: String): Boolean? = when (raw.trim().lowercase()) {
        "true" -> true
        "false" -> false
        else -> null
    }

    /**
     * 从 timeText 解析**首个日期**（日期区间过滤用）。真机写法覆盖：
     * 「2026年10月10日 17:00」「10月24日（周六）」「10/24」「10-24」「10月起」「11月上旬」。
     * 相对年用 [refYear]（一般取 createdAt 的年）。解析不到（空/「尽快」「测评后」「志愿活动后30天」）→ null。
     *
     * 2026-11-22 用户「pending_purge 只有 month 太粗，11 月上旬过期/中下旬有效要能按日期范围清」。
     */
    fun firstDateInText(timeText: String, refYear: Int): LocalDate? {
        val t = timeText.trim()
        if (t.isEmpty()) return null
        fun d(y: Int, m: Int, day: Int): LocalDate? = runCatching { LocalDate.of(y, m, day) }.getOrNull()
        Regex("""(\d{4})\s*[-/年]\s*(\d{1,2})\s*[-/月]\s*(\d{1,2})""").find(t)?.let {
            d(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())?.let { dd -> return dd }
        }
        Regex("""(\d{1,2})\s*月\s*(\d{1,2})""").find(t)?.let {
            d(refYear, it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { dd -> return dd }
        }
        Regex("""(?:^|[^\d])(\d{1,2})\s*[/\-]\s*(\d{1,2})(?![0-9])""").find(t)?.let {
            d(refYear, it.groupValues[1].toInt(), it.groupValues[2].toInt())?.let { dd -> return dd }
        }
        Regex("""(\d{1,2})\s*月\s*([上中下])旬""").find(t)?.let {
            val day = when (it.groupValues[2]) { "上" -> 5; "中" -> 15; else -> 25 }
            d(refYear, it.groupValues[1].toInt(), day)?.let { dd -> return dd }
        }
        Regex("""(\d{1,2})\s*月""").find(t)?.let {
            d(refYear, it.groupValues[1].toInt(), 1)?.let { dd -> return dd }
        }
        return null
    }

    /** 解析「边界日」参数：yyyy-M-d / M-d / M月d日 / 今天 / 昨天 / 明天。today 由调用方给（AppTime.today）。 */
    fun parseDay(raw: String, today: LocalDate): LocalDate? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        when (t) {
            "今天", "今日", "today" -> return today
            "昨天", "昨日", "yesterday" -> return today.minusDays(1)
            "明天", "明日" -> return today.plusDays(1)
        }
        fun d(y: Int, m: Int, day: Int): LocalDate? = runCatching { LocalDate.of(y, m, day) }.getOrNull()
        Regex("""^(\d{4})[-/](\d{1,2})[-/](\d{1,2})$""").find(t)?.let {
            return d(it.groupValues[1].toInt(), it.groupValues[2].toInt(), it.groupValues[3].toInt())
        }
        Regex("""^(\d{1,2})[-/](\d{1,2})$""").find(t)?.let {
            return d(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        Regex("""^(\d{1,2})月(\d{1,2})日?$""").find(t)?.let {
            return d(today.year, it.groupValues[1].toInt(), it.groupValues[2].toInt())
        }
        return null
    }
}
