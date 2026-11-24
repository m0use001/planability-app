package io.github.m0use001.planability.ui.edit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Recurrence
import io.github.m0use001.planability.ui.common.DatePickerModal
import io.github.m0use001.planability.ui.common.SelectDropdownChip
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * 统一重复/频率编辑器（日程与待办共用）。
 * 覆盖：不重复 / 每天 / 每周(星期多选) / 每两周 / 每月(按日号 或 第N个星期X) / 每年 / 每 N 天，
 * 结束条件：永久 / 截止日。展示实时描述文本。
 */
@Composable
fun RepeatEditor(anchor: LocalDate, rec: Recurrence, onChange: (Recurrence) -> Unit) {
    // 频率/星期/结束这些全是「选择」控件 → Select
    val haptics = LocalHaptics.current
    var showUntilPicker by remember { mutableStateOf(false) }
    // 「重复」节标题由调用方渲染（Editors.kt 两处均已有 SectionLabel），此处不再重复
    // 频率选项改 FlowRow（审查差距 13）：原来单行横滚，7 个选项里「每年/间隔」顶到屏幕右缘
    // 被裁成半截、也没有滚动提示；换行后全部选项一眼可见，仍保持 32dp/8dp 的 chip 规范形状。
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        data class Opt(val label: String, val match: (Recurrence) -> Boolean, val make: (Recurrence) -> Recurrence)
        val options = listOf(
            Opt("不重复", { !it.repeats }, { Recurrence.NONE }),
            Opt("每天", { it.kind == Recurrence.Kind.DAILY }, { it.copy(kind = Recurrence.Kind.DAILY) }),
            Opt("每周", { it.kind == Recurrence.Kind.WEEKLY && !it.biweekly }, { it.copy(kind = Recurrence.Kind.WEEKLY, biweekly = false, weekdays = emptySet()) }),
            Opt("每两周", { it.kind == Recurrence.Kind.WEEKLY && it.biweekly }, { it.copy(kind = Recurrence.Kind.WEEKLY, biweekly = true, weekdays = emptySet()) }),
            Opt("每月", { it.kind == Recurrence.Kind.MONTHLY }, { it.copy(kind = Recurrence.Kind.MONTHLY) }),
            Opt("每年", { it.kind == Recurrence.Kind.YEARLY }, { it.copy(kind = Recurrence.Kind.YEARLY) }),
            Opt("间隔", { it.kind == Recurrence.Kind.INTERVAL }, { it.copy(kind = Recurrence.Kind.INTERVAL, intervalDays = if (it.intervalDays > 1) it.intervalDays else 2) })
        )
        options.forEach { opt ->
            FilterChip(
                colors = filterChipColors(),
                selected = opt.match(rec),
                onClick = { onChange(opt.make(rec)); haptics.perform(HapticEngine.Kind.Select) },
                label = { Text(opt.label, maxLines = 1) }
            )
        }
    }

    when {
        rec.kind == Recurrence.Kind.WEEKLY -> {
            Spacer(Modifier.width(2.dp))
            WeekdayCircleRow(
                selected = if (rec.weekdays.isEmpty()) setOf(anchor.dayOfWeek.value) else rec.weekdays,
                onToggle = { dow ->
                    val base = if (rec.weekdays.isEmpty()) setOf(anchor.dayOfWeek.value) else rec.weekdays
                    val next = if (dow in base) (base - dow).let { if (it.isEmpty()) base else it } else base + dow
                    onChange(rec.copy(weekdays = next))
                }
            )
        }
        rec.kind == Recurrence.Kind.MONTHLY -> {
            Spacer(Modifier.width(2.dp))
            // M3E connected button group（替代已弃用的 SegmentedButton；切换触感由组件内置）
            io.github.m0use001.planability.ui.common.AppButtonGroup(
                options = listOf("按日期", "按星期"),
                selectedIndex = if (rec.byWeekday) 1 else 0,
                onSelect = { idx ->
                    if (idx == 1) onChange(rec.copy(byWeekday = true, nth = rec.nth.coerceIn(1, 5)))
                    else onChange(rec.copy(byWeekday = false))
                }
            )
            Spacer(Modifier.width(4.dp))
            if (rec.byWeekday) {
                // 2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」：编辑器卡片里的
                // 横滑胶囊行改单个下拉胶囊（选中值即摘要）。
                val nthOptions = listOf(1 to "第1周", 2 to "第2周", 3 to "第3周", 4 to "第4周", 5 to "最后")
                SelectDropdownChip(
                    summary = nthOptions.firstOrNull { it.first == rec.nth }?.second ?: "第1周",
                    options = nthOptions.map { it.second },
                    selectedIndex = nthOptions.indexOfFirst { it.first == rec.nth },
                    onSelect = { idx ->
                        onChange(rec.copy(nth = nthOptions[idx].first)); haptics.perform(HapticEngine.Kind.Select)
                    }
                )
                WeekdayCircleRow(
                    selected = setOf(rec.monthWeekday),
                    onToggle = { onChange(rec.copy(monthWeekday = it)) }
                )
            } else {
                val current = if (rec.nth in 1..31) rec.nth else anchor.dayOfMonth
                SelectDropdownChip(
                    summary = "第 $current 日",
                    options = (1..31).map { "第 $it 日" },
                    selectedIndex = current - 1,
                    onSelect = { idx ->
                        onChange(rec.copy(nth = idx + 1)); haptics.perform(HapticEngine.Kind.Select)
                    }
                )
            }
        }
        rec.kind == Recurrence.Kind.INTERVAL -> {
            var text by remember(rec.intervalDays) { mutableStateOf(rec.intervalDays.toString()) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it.filter { c -> c.isDigit() }.take(3) },
                    label = { Text("间隔天数") },
                    singleLine = true,
                    modifier = Modifier.width(140.dp)
                )
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    onChange(rec.copy(intervalDays = (text.toIntOrNull() ?: 1).coerceIn(1, 365)))
                }) { Text("确定") }
            }
        }
    }

    // 2026-10-21：**不重复时不给「结束」**。
    // 之前无论选哪种频率都能设截止日，于是出现"不重复 + 三天后截止"这种自相矛盾的组合
    // （用户实测踩到）。结束属于重复规则的一部分，合并进同一个分区；
    // 切回「不重复」时 until 会被 Recurrence.NONE 一并清掉。
    if (rec.repeats) {
        Text(
            "结束",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp)
        )
        // 2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」：永久/有截止改下拉胶囊；
        // 「至 x月x日」的日期入口保留在胶囊右侧（选中「有截止」才出现）。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            SelectDropdownChip(
                summary = if (rec.untilDayKey == 0L) "永久" else "有截止",
                options = listOf("永久", "有截止"),
                selectedIndex = if (rec.untilDayKey == 0L) 0 else 1,
                onSelect = { idx ->
                    if (idx == 0) {
                        onChange(rec.copy(untilDayKey = 0))
                    } else {
                        onChange(rec.copy(untilDayKey = AppTime.dayKey(anchor.plusMonths(3))))
                    }
                    haptics.perform(HapticEngine.Kind.Select)
                }
            )
            if (rec.untilDayKey > 0L) {
                OutlinedButton(onClick = { showUntilPicker = true }) {
                    Text("至 ${rec.untilDayKey.toLocalDate().formatMonthDay()}")
                }
            }
        }
    }

    // v14：这里原来还有一行「将重复：xxx」的摘要。用户反馈它是多余的——
    // 上面的频率/间隔/星期几/结束 chips 已经把规则说全了，这行只是把同一件事
    // 用句子重写一遍，而且默认档位下永远显示「将重复：不重复」，最像 bug 不像信息。

    if (showUntilPicker) {
        DatePickerModal(
            rec.untilDayKey.toLocalDate(),
            { showUntilPicker = false },
            {
                onChange(rec.copy(untilDayKey = AppTime.dayKey(it)))
                showUntilPicker = false
            }
        )
    }
}

@Composable
private fun WeekdayCircleRow(selected: Set<Int>, onToggle: (Int) -> Unit) {
    // 星期几多选圆点与 FilterChip 同属「选择」→ Select，别让同一屏两种手感
    val haptics = LocalHaptics.current
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DayOfWeek.entries.forEach { dow ->
            val on = dow.value in selected
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(
                        if (on) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh
                    )
                    .clickable { haptics.perform(HapticEngine.Kind.Select); onToggle(dow.value) }
            ) {
                Text(
                    dow.getDisplayName(TextStyle.NARROW, Locale.CHINESE),
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
