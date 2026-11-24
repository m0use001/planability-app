package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.formatMonthDay
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CheckinDay
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.CheckinKind
import io.github.m0use001.planability.data.CheckinRecord
import io.github.m0use001.planability.data.Todo
import java.time.LocalDate
import java.time.LocalTime
import io.github.m0use001.planability.ui.common.filterChipColors

/**
 * v23：补交 / 改单条打卡数据的统一弹窗。
 *
 * 三个入口共用它（计划页「已过期」列表点某天、首页过去日期点待办、待办编辑器里的
 * 「补交/修改打卡」），所以放在 ui/common 而不是各自文件里 ——
 * 三处各自写一遍必然出现"这处能填那处不能"的不一致。
 *
 * 语义：
 * - [day] 是**应打的那天**（不是提交那天）。at 记实际提交时刻，延迟天数由它算出。
 * - [existing] 有值 = 改这一条；null = 补交新的一天。
 * - time 型只补一个时刻（默认 12:00，不是"提交那一刻"，否则补交会把 3 天前的打卡说成凌晨 3 点）。
 */
@Composable
fun BackfillCheckinDialog(
    todo: Todo,
    day: LocalDate,
    existing: CheckinRecord?,
    onDismiss: () -> Unit,
    onSave: (record: CheckinRecord?) -> Unit
) {
    val kind = todo.dataRecordKind.ifBlank { CheckinKind.TIME }
    val label = todo.dataRecordLabel.ifBlank { todo.title }
    val isEdit = existing != null
    val delay = run {
        val dayKey = AppTime.dayKey(day)
        io.github.m0use001.planability.data.CheckinLedger.delayDays(dayKey, AppTime.nowMillis(), AppTime.zone)
    }

    var numText by remember(existing) {
        mutableStateOf(
            if (existing != null && (kind == CheckinKind.NUMBER || kind == CheckinKind.COUNT))
                CheckinFormat.trimNum(existing.numValue) else ""
        )
    }
    var textValue by remember(existing) {
        mutableStateOf(if (existing != null && kind == CheckinKind.TEXT) existing.textValue else "")
    }
    var picked by remember(existing) {
        mutableStateOf(
            if (existing != null && kind == CheckinKind.TAGS)
                existing.tags.split(',').map { it.trim() }.filter { it.isNotEmpty() }.toSet()
            else emptySet()
        )
    }
    var extraTag by remember(existing) { mutableStateOf("") }
    var timeValue by remember(existing) {
        mutableStateOf(
            // time 型：已有记录用记录时刻，否则给个中性的 12:00
            existing?.let { r ->
                java.time.Instant.ofEpochMilli(r.at).atZone(AppTime.zone).toLocalTime()
            } ?: LocalTime.of(12, 0)
        )
    }
    var showTime by remember { mutableStateOf(false) }
    val options = remember(todo.dataRecordOptions) { CheckinFormat.parseOptions(todo.dataRecordOptions) }

    val valid = when (kind) {
        CheckinKind.NUMBER, CheckinKind.COUNT -> numText.trim().toDoubleOrNull() != null
        CheckinKind.TEXT -> textValue.isNotBlank()
        CheckinKind.TAGS -> picked.isNotEmpty() || CheckinFormat.parseOptions(extraTag).isNotEmpty()
        else -> true
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = {
            Text((if (isEdit) "修改打卡 · " else "补交打卡 · ") + day.formatMonthDay())
        },
        text = {
            Column {
                Text(
                    label + (if (delay > 0) " · 迟 $delay 天补交（算 ${100 - 25 * delay}%）" else " · 按时"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    CheckinKind.hint(kind),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                when (kind) {
                    CheckinKind.NUMBER -> OutlinedTextField(
                        value = numText, onValueChange = { numText = it },
                        label = {
                            Text("数值" + if (todo.dataRecordUnit.isNotBlank()) "（${todo.dataRecordUnit}）" else "")
                        },
                        singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.COUNT -> OutlinedTextField(
                        value = numText, onValueChange = { numText = it },
                        label = { Text("次数") }, singleLine = true, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.TEXT -> OutlinedTextField(
                        value = textValue, onValueChange = { textValue = it },
                        label = { Text("这次的内容") }, minLines = 2, modifier = Modifier.fillMaxWidth()
                    )
                    CheckinKind.TAGS -> {
                        options.chunked(3).forEach { row ->
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)
                            ) {
                                row.forEach { o ->
                                    FilterChip(
                                        colors = filterChipColors(),
                                        selected = o in picked,
                                        onClick = {
                                            picked = if (o in picked) picked - o else picked + o
                                        },
                                        label = { Text(o) }
                                    )
                                }
                            }
                        }
                        OutlinedTextField(
                            value = extraTag, onValueChange = { extraTag = it },
                            label = { Text("其他（逗号分隔，可留空）") },
                            singleLine = true, modifier = Modifier.fillMaxWidth()
                        )
                    }
                    else -> {
                        // time 型：让用户确认那一刻的时间，而不是默默记 12:00
                        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text(
                                "%02d:%02d".format(timeValue.hour, timeValue.minute),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(vertical = 4.dp)
                            )
                            TextButton(onClick = { showTime = true }) { Text("改时刻") }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    val dayKey = AppTime.dayKey(day)
                    val base = existing ?: CheckinRecord(
                        todoId = todo.id, date = dayKey,
                        at = AppTime.nowMillis(), kind = kind, unit = todo.dataRecordUnit
                    )
                    val rec = when (kind) {
                        CheckinKind.NUMBER, CheckinKind.COUNT ->
                            base.copy(numValue = numText.trim().toDouble())
                        CheckinKind.TEXT -> base.copy(textValue = textValue.trim())
                        CheckinKind.TAGS -> base.copy(
                            tags = CheckinFormat.formatOptions(
                                (picked + CheckinFormat.parseOptions(extraTag)).toList()
                            )
                        )
                        else -> base.copy(
                            // 那一刻的绝对时刻；日期取应打的那天
                            at = day.atTime(timeValue).atZone(AppTime.zone).toInstant().toEpochMilli()
                        )
                    }
                    onSave(rec)
                }
            ) { Text(if (isEdit) "保存" else "补交") }
        },
        dismissButton = {
            Row {
                if (isEdit) {
                    // 改错了就撤掉这天（数据记录和打卡一起撤）
                    TextButton(onClick = { onSave(null) }) { Text("撤销这天") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
    if (showTime) {
        TimePickerModal(timeValue, { showTime = false }, { timeValue = it; showTime = false })
    }
}

/**
 * 「已过期」行展开后的逐日清单。
 *
 * 一行一天：日期 + 第几天 + 状态（未完成/补交/按时），补交和未完成要能点进去补交。
 * 只展示最近 [maxDays] 天（默认 90）—— 常驻待办可能攒了两年，全列出来没法看。
 */
@Composable
fun CheckinDayList(
    days: List<CheckinDay>,
    maxDays: Int = 90,
    onPickDay: (CheckinDay) -> Unit
) {
    val shown = days.take(maxDays)
    Column(Modifier.fillMaxWidth()) {
        shown.forEach { d ->
            val (tag, color) = when (d.state) {
                CheckinDay.MISSED -> "未完成" to MaterialTheme.colorScheme.error
                CheckinDay.LATE -> "补交" to MaterialTheme.colorScheme.tertiary
                CheckinDay.PENDING -> "今天" to MaterialTheme.colorScheme.onSurfaceVariant
                else -> "按时" to MaterialTheme.colorScheme.primary
            }
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1.dp)
                    .clickable { onPickDay(d) }
                    .padding(horizontal = 4.dp, vertical = 6.dp)
            ) {
                Text(
                    d.date.toLocalDate().formatMonthDay(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "第${d.dayNo}天",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    // PENDING 不写百分数：它还没进分母，写"0%"会被读成"这天我欠了"
                    if (d.state == CheckinDay.MISSED || d.state == CheckinDay.PENDING) tag
                    else tag + " · ${d.credit}%" + (if (d.late) " · 迟${d.delayDays}天" else ""),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                    color = color
                )
            }
        }
        if (days.size > maxDays) {
            Text(
                "…更早还有 ${days.size - maxDays} 天",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, top = 4.dp)
            )
        }
    }
}