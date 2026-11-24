package io.github.m0use001.planability.ui.home

import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import kotlin.math.roundToInt
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import io.github.m0use001.planability.data.Todo

/**
 * 首页的两个快捷操作浮层（2026-11-03 用户）：
 *
 * ① [UntimedPlanSheet]——「未设置时间计划：N」胶囊点开不再跳计划页，直接弹二级菜单：
 *    列出当天**无时刻**（due<=0）且未完成的待办（loadDay 口径，含欠账副本），
 *    点行即勾选/取消（与时间轴轻点同一条 completeTodo 路径，滚期模型不变）；
 *    启用了百分比（v46 percentDone 0..99）的行点击则改弹 [PercentQuickMenu]。
 *    日程必有起止时间，不存在"未设置时间"的日程，列表只有待办。
 *
 * ② [PercentQuickMenu]——轻点/菜单点按启用了百分比的待办时弹出的**悬浮小菜单**：
 *    滑条 + 百分比大字 + 手动输入，确定即写库（setPercentDone，只动 percentDone
 *    不碰 done/滚期）。用 Dialog 而不是叠一层 BottomSheet：二级菜单打开时也能从
 *    行上唤起（Dialog 窗口盖住 sheet），且形态上就是"悬浮小菜单"。
 */

/**
 * 二级菜单的列表内容：当天**无时刻**（due<=0）的全部待办（2026-11-03 用户：勾完也要
 * 留在菜单里显示已完成，不能消失）。未完成的排前面；胶囊计数（untimedPendingCount）
 * 仍只数未完成，两边口径各自正确。
 */
internal fun untimedTodos(todos: List<Todo>): List<Todo> =
    todos.filter { it.due <= 0 }.sortedByDescending { it.done }   // false<true → 未完成在前

/** 待办是否启用了百分比进度（v46：-1=未启用，0..99=进行中；100 视为做完未打卡） */
internal fun percentEnabled(t: Todo): Boolean = t.percentDone in 0..100

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
internal fun UntimedPlanSheet(
    todos: List<Todo>,
    onDismiss: () -> Unit,
    onToggleDone: (Todo) -> Unit,
    onOpenPercent: (Todo) -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 28.dp)
        ) {
            Text(
                "未设置时间计划",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                "点条目直接勾选完成；带百分比的条目点开进度滑条。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp, bottom = 10.dp)
            )
            val untimed = untimedTodos(todos)
            if (untimed.isEmpty()) {
                Text(
                    "没有未设置时间的待办",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 14.dp)
                )
            } else {
                untimed.forEach { t ->
                    val pct = percentEnabled(t)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            // v48.4：百分比待办**已打卡也点得开浮动滑条**（菜单里带
                            // 「改记录数据/撤销打卡」）。此前 done 行点击=撤销打卡，
                            // 用户「填完数据后」再也改不了百分比。
                            .clickable { if (pct) onOpenPercent(t) else onToggleDone(t) }
                            .padding(horizontal = 6.dp, vertical = 9.dp)
                    ) {
                        // 勾选圈：与时间轴轻点同一语义，点击圈=勾选/取消（不受百分比分流影响）
                        androidx.compose.material3.Icon(
                            if (t.done) Icons.Rounded.CheckCircle
                            else Icons.Rounded.FiberManualRecord,
                            contentDescription = if (t.done) "已完成" else "未完成",
                            tint = if (t.done) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)   // 2026-11-03 用户「勾小一点」（原 20dp）
                                .clickable { onToggleDone(t) }
                        )
                        Spacer(Modifier.width(10.dp))
                        // 2026-11-03 用户：勾完的条目留在菜单里显示已完成——标题减淡+
                        // 删除线，点击（非百分比）= 取消勾选回到未完成
                        Text(
                            t.title,
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (t.done) MaterialTheme.colorScheme.onSurfaceVariant else androidx.compose.ui.graphics.Color.Unspecified,
                            textDecoration = if (t.done) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        if (pct) {
                            Text(
                                "${t.percentDone}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .clip(CircleShape)
                                    .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                    .padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 百分比快捷浮层：滑条 + 百分比大字 + 手动输入（0-100），确定/收起即写库。
 * 只改 percentDone，不碰 done——勾选语义仍在原处（时间轴轻点/计划页）。
 *
 * v48.1（用户「同时设置百分比与数据记录，记录完百分比一直不让我记录数据」）：
 * 首页轻点百分比待办只弹这个浮层，打卡/数据记录没有入口。底部补一枚
 * 「打卡完成」——打卡后需要手填的数据记录由调用方弹录入框。
 */
@Composable
internal fun PercentQuickMenu(
    todo: Todo,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
    /** v48.1：打卡并记录（不传则不显示按钮）；2026-11-14 改为**携带滑条当前值**——
     *  调用方先落进度再打卡（挂起串行），原来 onSet/check 两条协程并发，
     *  打卡可能把旧百分比写进当天流水 */
    onCheckIn: ((Int) -> Unit)? = null,
    /** v48.3：已打卡后**改当天数据记录**（真机「一旦打卡数据记录就锁死了」——此前录完没法再改） */
    onEditData: (() -> Unit)? = null,
    /** v48.3：已打卡后撤销打卡（恢复未完成，数据记录一并在仓储层撤销） */
    onUndoCheckIn: (() -> Unit)? = null
) {
    var value by remember(todo.id) { mutableIntStateOf(todo.percentDone.coerceAtLeast(0)) }
    var input by remember(todo.id) { mutableStateOf(value.toString()) }
    // 已落库的值（键盘输入只在它与输入不同时才写一次，避免重复写库/重复触发刷新）
    var submitted by remember(todo.id) { mutableIntStateOf(value) }
    val keyboard = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current
    /** 手动输入生效：值合法且与已落库值不同才写；**不关浮层**（接着打卡是连贯动作） */
    fun commitInput() {
        val n = input.toIntOrNull() ?: return
        if (n !in 0..100 || n == submitted) return
        submitted = n
        onSet(n)
    }
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp)) {
                Text(
                    todo.title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "完成进度（不改变勾选状态）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = value.toFloat(),
                        onValueChange = {
                            // 5% 步进在值里做（吸到 5 的倍数）：steps 会沿轨道画一排档位点，
                            // 用户嫌丑（2026-11-03）；连续拖动 + 数值吸附观感一致、轨道干净
                            value = ((it / 5f).roundToInt() * 5).coerceIn(0, 100)
                            input = value.toString()
                        },
                        onValueChangeFinished = { onSet(value); submitted = value },
                        valueRange = 0f..100f,
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = MaterialTheme.colorScheme.surfaceContainerHighest
                        ),
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "$value%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(52.dp)
                    )
                }
                // 2026-11-14 用户两次报障定稿：「确定」和「打卡完成」重复 → 上一版只是改名
                // 「保存进度」，**仍然是两个按钮**。现在彻底删掉输入框旁的按钮：
                // 手动输入**打完按键盘「完成」、或点别处失焦即生效**，浮层里只剩底部
                // 一个动作按钮（打卡完成 / 改记录数据 / 撤销打卡）。
                OutlinedTextField(
                    value = input,
                    onValueChange = { s ->
                        val clean = s.filter { it.isDigit() }.take(3)
                        input = clean
                        // 输入即同步滑条（但**不落库**——失焦/回车/打卡时才写）
                        val n = clean.toIntOrNull()
                        if (n != null && n in 0..100) value = n
                    },
                    singleLine = true,
                    label = { Text("手动输入 0-100") },
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = androidx.compose.ui.text.input.ImeAction.Done
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onDone = {
                            commitInput()
                            keyboard?.hide()
                        }
                    ),
                    isError = input.toIntOrNull() !in 0..100,
                    modifier = Modifier
                        .fillMaxWidth()
                        .onFocusChanged { st -> if (!st.isFocused) commitInput() }
                )
                // v48.1：打卡入口——同时开了数据记录的待办，在首页也能打卡并弹录入框
                // （此前只有计划页的勾选会弹，首页轻点百分比待办只能改百分比，数据永远录不上）
                // v48.3：已打卡的待办不再"锁死"——给「改记录数据」「撤销打卡」两个出口。
                if (onCheckIn != null || onEditData != null || onUndoCheckIn != null) {
                    Spacer(Modifier.height(6.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (todo.done) "今天已打卡"
                            // 2026-11-14：未打卡时的提示明确「滑动条改了就生效」，
                            // 与上方「保存进度」的分工写清楚（避免用户以为还要点两下）
                            else "滑动条拖动即保存；也可在下方直接打卡",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        if (todo.done) {
                            // 2026-11-14：没开数据记录的待办**不出现**「改记录数据」——
                            // 原来只要 done 就显示，用户（没开数据记录）也被引导去录数据
                            // （实测「我还让我记录数据，我都没开数据记录按钮」）
                            if (todo.dataRecordEnabled) onEditData?.let { edit ->
                                androidx.compose.material3.TextButton(onClick = { onSet(value); edit() }) {
                                    Text("改记录数据", fontWeight = FontWeight.SemiBold)
                                }
                            }
                            onUndoCheckIn?.let { undo ->
                                androidx.compose.material3.TextButton(onClick = undo) {
                                    Text("撤销打卡", fontWeight = FontWeight.SemiBold)
                                }
                            }
                        } else {
                            onCheckIn?.let { check ->
                                androidx.compose.material3.TextButton(
                                    onClick = {
                                        // 滑条当前值随回调带走（调用方先落进度再打卡，串行不竞态）
                                        check(value)
                                    }
                                ) {
                                    Text(
                                        if (todo.dataRecordEnabled) "打卡并记录" else "打卡完成",
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
