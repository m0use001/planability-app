package io.github.m0use001.planability.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.FiberManualRecord
import androidx.compose.material3.HorizontalDivider
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
import io.github.m0use001.planability.core.formatCn
import io.github.m0use001.planability.data.DayBundle
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.PaneChip
import io.github.m0use001.planability.ui.common.PaneEmptyBlock
import io.github.m0use001.planability.ui.common.PaneSectionBlock
import java.time.LocalDate

/**
 * 计划页宽屏右栏的「今天」概览（v60 初版 2026-11-18，v62 收成单卡 2026-11-19）。
 *
 * 它填的是「宽屏的空」：左板列表短的时候，右边放这一页主人真在用的东西——
 * 今天的完成情况、今天的日程、还没做完的待办。数据全部取自既有仓储
 * （[Graph.plan.loadDay]），不新增任何存储或口径；点一行照旧走计划页原有的编辑入口。
 *
 * v62 为什么从「三张独立卡片」改成「一张卡内部分节」：
 * 三张 18dp 圆角卡并排堆在渐变底上，本身就是用户说的「内容块割裂」——
 * 每张卡各自浮着，和左板的行卡、顶栏的浮层互不相干。收成一卡 + 节间细线之后，
 * 右栏读起来是**一块**，与左板同一套材质（圆角/底色由宿主统一给）。
 * 同时去掉了 `take(8)` 的静默截断：面板自己能滚，没理由把第 9 条藏掉且不说。
 *
 * v72（2026-11-21）：节升级成 [PaneSectionBlock] 色调圆角块 + 计数挂 [PaneChip] +
 * 空态 [PaneEmptyBlock]——用户「文字太多了，仅靠排版难以区分，应该加入圆角矩形来区分」。
 * 禁的仍是浮起卡（守卫钉着）；块是同材质家族的扁平色调，不是 v62 反的那个形状。
 */
@Composable
fun PlanTodayPane(
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
    val todos = bundle?.todos.orEmpty()
    val doneTodos = todos.count { it.done }
    val pendingTodos = todos.filter { !it.done }

    Column(modifier.fillMaxSize()) {
        // ---- 栏头：标题 + 日期 + 三格统计（钉住不滚：往下翻也始终知道「今天几件事」）----
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    "今天",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                Text(
                    date.formatCn(),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 3.dp)
                )
            }
            Spacer(Modifier.height(10.dp))
            // v72.2：三格独立小矩形 → 并进同一个矩形（一行标题由栏头「今天/日期」承担）
            io.github.m0use001.planability.ui.common.PaneStatRow(
                listOf(
                    "${schedules.size}" to "日程",
                    "${todos.size}" to "待办",
                    "$doneTodos" to "已完成"
                )
            )
            Spacer(Modifier.height(12.dp))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp)
        ) {
            // v72（2026-11-21 用户「文字太多了，仅靠排版难以区分，应该加入圆角矩形来区分」）：
            // 板内节全部装进共享词汇表的色调圆角块（PaneSectionBlock），空态升级成
            // PaneEmptyBlock 占位块；节计数挂块标题尾槽的小胶囊。板仍是「一块」，块是
            // 同材质家族的扁平色调（无描边无阴影），与 v62 反掉的「三张浮起卡」不是一回事。
            PaneSectionBlock(
                "今日日程",
                titleTrailing = { PaneChip("共 ${schedules.size}") }
            ) {
                if (schedules.isEmpty()) {
                    PaneEmptyBlock("今天没有日程")
                } else {
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
                }
            }

            Spacer(Modifier.height(12.dp))

            PaneSectionBlock(
                "待办",
                titleTrailing = { PaneChip("${pendingTodos.size}/${todos.size}") }
            ) {
                if (pendingTodos.isEmpty()) {
                    PaneEmptyBlock(if (todos.isEmpty()) "今天没有待办" else "今天的待办都完成了")
                } else {
                    pendingTodos.forEach { t ->
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
    }
}

// v72.2：PlanStatCell 退役（三格并进一枚 PaneStatRow）。
