package io.github.m0use001.planability.shared

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 全局时间入口：所有日期时间一律经此获取，禁止直接 LocalDate.now()。
 * 支持用户覆盖系统时区（设置 → 时区）。
 * M2 批 2 从 app/core/AppTime.kt 迁入（jvmShared：纯 java.time + Locale，零 Android 依赖）——
 * 它是 ai/ptl 后续批次（PlanTools/LifeTools/PendingImporter/ToolExecutor…全引用它）的最大公共前置。
 */
object AppTime {

    @Volatile
    var zoneOverride: String? = null
        set(value) {
            field = value
            cachedZone = null
        }

    @Volatile
    private var cachedZone: ZoneId? = null

    val zone: ZoneId
        get() {
            cachedZone?.let { return it }
            val z = zoneOverride?.let { runCatching { ZoneId.of(it) }.getOrNull() }
                ?: ZoneId.systemDefault()
            cachedZone = z
            return z
        }

    fun today(): LocalDate = LocalDate.now(zone)

    fun now(): LocalDateTime = LocalDateTime.now(zone)

    fun nowMillis(): Long = System.currentTimeMillis()

    /** 某个本地日期在这一天的起始 epoch millis（用作「日」主键） */
    fun dayKey(date: LocalDate): Long =
        date.atStartOfDay(zone).toInstant().toEpochMilli()

    fun dayKeyOf(millis: Long): Long =
        dayKey(Instant.ofEpochMilli(millis).atZone(zone).toLocalDate())

    fun LocalDate.toMillis(): Long = dayKey(this)

    fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

    fun millisOf(date: LocalDate, hour: Int, minute: Int): Long =
        date.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    fun millisToTime(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().format(timeFmt)

    fun millisToFull(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(zone).toLocalDateTime().format(fullFmt)

    // ---------- 格式化器与顶层扩展 ----------

    private val cnDateFmt = DateTimeFormatter.ofPattern("yyyy年M月d日 EEEE", Locale.CHINESE)
    private val cnShortFmt = DateTimeFormatter.ofPattern("M月d日 EEE", Locale.CHINESE)
    private val monthDayFmt = DateTimeFormatter.ofPattern("M月d日", Locale.CHINESE)
    private val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.CHINESE)
    private val fullFmt = DateTimeFormatter.ofPattern("yyyy年M月d日 HH:mm", Locale.CHINESE)

    internal val cnDateFmtAccess get() = cnDateFmt
    internal val cnShortFmtAccess get() = cnShortFmt
    internal val monthDayFmtAccess get() = monthDayFmt
}

fun LocalDate.formatCn(): String = format(AppTime.cnDateFmtAccess)
fun LocalDate.formatShort(): String = format(AppTime.cnShortFmtAccess)
fun LocalDate.formatMonthDay(): String = format(AppTime.monthDayFmtAccess)
fun Long.toLocalDate(): LocalDate = Instant.ofEpochMilli(this).atZone(AppTime.zone).toLocalDate()
fun LocalDate.toDayKey(): Long = AppTime.dayKey(this)
