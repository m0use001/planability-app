package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Graph
import java.time.LocalDate

/**
 * v59 宽屏工作台首页的两个补充面板（2026-11-18）。
 *
 * 为什么有它：宽屏上工作台首页只有「问候语 + 输入卡 + 同步中心」三块，
 * 下面 500dp 全是空的（用户 16:10 截图点名「大而空」）。
 * 按 iPad HIG 的 split view 思路，空出来的空间应该放**这一页的主人真在用的东西**：
 * 今天的安排（日程 + 待办）和最近的会话——它们本来就散在计划页和抽屉里，
 * 现在常驻在首页右侧/下方，不必先想「我要去哪个 tab」。
 *
 * 数据全部取自既有仓储（[Graph.plan] / [Graph.chat]），不新增任何存储或口径。
 */
@Composable
fun InfoTodayPlanPanel(
    date: LocalDate = AppTime.today(),
    onOpenTodo: (Long) -> Unit,
    onOpenSchedule: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val version by Graph.dataVersion.flow.collectAsState()
    var bundle by remember { mutableStateOf<DayBundle?>(null) }
    LaunchedEffect(version, date) {
        bundle = runCatching { Graph.plan.loadDay(date) }.getOrNull()
    }
    val schedules = bundle?.schedules.orEmpty()
    val todos = bundle?.todos.orEmpty().filter { !it.done }
    if (schedules.isEmpty() && todos.isEmpty()) return

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "今日安排",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            schedules.forEach { occ ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSchedule(occ.schedule.id) }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    Text(
                        AppTime.millisToTime(occ.start),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.width(46.dp)
                    )
                    Text(
                        occ.schedule.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
            todos.take(8).forEach { t ->
                Row(
                    verticalAlignment = Alignment.Top,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenTodo(t.id) }
                        .padding(vertical = 6.dp, horizontal = 4.dp)
                ) {
                    Icon(
                        Icons.Rounded.FiberManualRecord,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        t.title,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    if (t.due > 0) {
                        Text(
                            AppTime.millisToTime(t.due),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/** 最近几条会话（点进去直接开聊）。 */
@Composable
fun InfoRecentSessionsPanel(
    limit: Int = 6,
    onOpenSession: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val version by Graph.dataVersion.flow.collectAsState()
    var list by remember { mutableStateOf<List<io.github.m0use001.planability.data.ChatSession>>(emptyList()) }
    LaunchedEffect(version) {
        list = Graph.chat.recentSessions(limit)
    }
    if (list.isEmpty()) return

    Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(14.dp)) {
            Text(
                "最近会话",
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(8.dp))
            list.forEach { s ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onOpenSession(s.id) }
                        .padding(vertical = 7.dp, horizontal = 4.dp)
                ) {
                    Box(
                        Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        s.title.ifBlank { "新对话" },
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}
