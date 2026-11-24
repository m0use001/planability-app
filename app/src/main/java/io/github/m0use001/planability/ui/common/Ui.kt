package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.SelectableChipColors
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.HapticTicks
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.ui.theme.SeedColors
import io.github.m0use001.planability.ui.theme.ShapeTokens
import kotlin.math.roundToInt
import io.github.m0use001.planability.ui.common.filterChipColors

/** 分类标识色：SeedTones 色相派生，不引入硬编码色 */
@Composable
fun categoryColor(hue: Float): Color =
    SeedColors.tone(hue, androidx.compose.foundation.isSystemInDarkTheme())

/**
 * 标签标识色：**用户调过浓淡就用它**，没调过则完全等同 [categoryColor]。
 *
 * 关键点是"没调过"和"调成了默认值"必须表现一致：前者走 `SeedColors.tone`
 * （按主题算），后者按同一组数值算，两者在界面上看不出来 —— 用户不会因为
 * 把滑块拖回去就发现颜色变了。
 */
@Composable
fun categoryColor(tint: LabelTint): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val base = SeedColors.tone(tint.hue, dark)
    if (tint.sat == null && tint.value == null) return base
    return SeedColors.hsl(
        tint.hue,
        tint.sat ?: SeedColors.satOf(base),
        tint.value ?: SeedColors.lightnessOf(base)
    )
}

@Composable
fun categoryContainerColor(hue: Float): Color =
    SeedColors.toneContainer(hue, androidx.compose.foundation.isSystemInDarkTheme())

/**
 * 标签底色（chip 背景）：色相跟随标签，**但饱和度按用户的"浓度"等比缩放**。
 *
 * 只照搬 hue 而忽略 sat 的话，把标签调成灰色、chip 背景却还是一块彩色 ——
 * 用户会觉得"明暗没生效"。这里按 `用户饱和度 / 主题默认饱和度` 缩放容器的饱和度，
 * 0 饱和时容器也跟着变中性灰，比例调节（更深/更淡）也自然延续。
 */
@Composable
fun categoryContainerColor(tint: LabelTint): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    val c = SeedColors.toneContainer(tint.hue, dark)
    if (tint.sat == null) return c
    val baseSat = SeedColors.satOf(SeedColors.tone(tint.hue, dark))
    val ratio = if (baseSat > 0.02f) (tint.sat / baseSat).coerceIn(0f, 2f) else 1f
    return SeedColors.hsl(tint.hue, (SeedColors.satOf(c) * ratio).coerceIn(0f, 1f), SeedColors.lightnessOf(c))
}

fun CategoryEntity?.displayName(): String = this?.name ?: "未分类"

// ---------- v12：多体系标签 ----------

/** 没有任何预置标签：候选清单**只来自用户已用过的数据**（用户 2026-10-20 明确要求）。 */
val LABEL_PRESETS: List<String> = emptyList()

fun parseLabels(csv: String): List<String> =
    csv.split(',').map { it.trim() }.filter { it.isNotEmpty() }

fun labelsCsv(labels: List<String>): String = labels.joinToString(",")

/**
 * 多体系标签编辑行：已有标签 chips 多选，末尾可输入新标签。
 *
 * 不再有预置标签（`LABEL_PRESETS` 已清空）：一条数据都没有时，这里只剩输入框，
 * 不会凭空冒出「重要/工作/生活/学习/等待中」。
 *
 * v28：**长按任一标签 → 改它的颜色**（存进 `label_colors`），chip 前面的小圆点即当前颜色。
 * 单击仍是选中/取消选中 —— 两个动作挤一个手势上必有一个难触发，所以改色走长按，
 * 并在下面明写一行提示，不指望用户自己发现。
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun LabelEditorRow(
    value: String,
    known: List<String>,
    hues: Map<String, Float> = emptyMap(),
    /** 标签名 -> 浓淡（色相 + 饱和度/明度）。比 [hues] 更细，优先用它 */
    tints: Map<String, LabelTint> = emptyMap(),
    onColor: (String, LabelTint?) -> Unit = { _, _ -> },
    /**
     * 全局改名：把 old 换成 new，范围是**所有**引用该标签的条目。
     * 当前这一条由本组件自己换掉（见 onSave），调用方只需管"别的条目"。
     */
    onRename: (String, String) -> Unit = { _, _ -> },
    // onChange 必须放最后：Kotlin 的尾随 lambda 绑到"最后一个函数类型参数"上，
    // 放中间会让老调用点 `LabelEditorRow(a, b) { ... }` 把 lambda 认成 onColor（真踩过一次）
    onChange: (String) -> Unit
) {
    val selected = parseLabels(value).toSet()
    val candidates = (known + LABEL_PRESETS).distinct()
    // 2026-10-21：chips 从「候选 ∪ 已选」渲染。之前只渲染候选，新加的标签不在
    // 全局清单里就没有芯片——用户按了「添加」界面毫无反应，以为没保存（真机反馈）。
    val chipList = (candidates + selected).distinct()
    // 勾/取消标签是"选择"，用全场最轻的 Select（HIG selection → Kind.Select，映射表见 core/Haptics.kt）
    val haptics = LocalHaptics.current
    /** 正在改色的标签；null = 没开弹层 */
    var colorTarget by remember { mutableStateOf<String?>(null) }
    /** 「＋ 新建标签」弹层 */
    var createOpen by remember { mutableStateOf(false) }
    /** 长按成功后屏蔽一次点击：手指抬起时芯片自己的 clickable 还会补一次 onClick，
     *  不挡的话「改颜色」会顺带把这个标签勾上/取消（长按与点击共用一根手指，必须显式互斥） */
    var suppressClick by remember { mutableStateOf<String?>(null) }
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        chipList.forEach { label ->
            val on = label in selected
            Box(
                Modifier.pointerInput(label) {
                    // **不要用 detectTapGestures**：它会消费 down/up，芯片自己的 clickable
                    // 就再也收不到 —— 真机实测表现为「点不动、也长按不出弹层」，等于标签功能全废。
                    // 这里只观察不消费：按住超过 longPressTimeout 且没拖出 touchSlop 才算长按。
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val slop = viewConfiguration.touchSlop
                        var dragged = false
                        val lifted = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            var ev = awaitPointerEvent()
                            while (ev.changes.isNotEmpty() && ev.changes[0].pressed && !dragged) {
                                if ((ev.changes[0].position - down.position).getDistance() > slop) {
                                    dragged = true
                                }
                                ev = awaitPointerEvent()
                            }
                            true
                        } != null
                        if (!lifted && !dragged) {
                            suppressClick = label
                            colorTarget = label
                        }
                    }
                }
            ) {
                FilterChip(
                    colors = filterChipColors(),
                    selected = on,
                    onClick = {
                        if (suppressClick == label) {
                            suppressClick = null
                        } else {
                            val next = selected.toMutableSet()
                            if (on) next.remove(label) else next.add(label)
                            onChange(labelsCsv(next.toList()))
                            // 只有真的改了选中才振：被长按"吃掉"的那次点击不改状态，也就没有反馈
                            haptics.perform(HapticEngine.Kind.Select)
                        }
                    },
                    leadingIcon = {
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(categoryColor(tints[label] ?: LabelTint(hues[label] ?: 210f)), CircleShape)
                        )
                    },
                    label = { Text(label) }
                )
            }
        }
        // ＋ 新建标签：完整的新建（文字 + 颜色 + 浓淡），不再是底部那根裸输入框。
        // 保存时颜色立即落库（所有页面立刻能用），并自动勾到当前这一条上。
        FilterChip(
            colors = filterChipColors(),
            selected = false,
            onClick = { createOpen = true },
            label = { Text("＋ 新建标签") }
        )
    }
    if (createOpen) {
        LabelEditDialog(
            name = "",
            current = null,
            knownNames = chipList,
            creating = true,
            onDismiss = { createOpen = false },
            onSave = { newName, tint ->
                createOpen = false
                if (newName.isNotBlank()) {
                    // 颜色先落库：dataVersion 一跳，全局清单/各页候选都会刷新
                    onColor(newName, tint)
                    // 再勾到当前这一条（保存条目时随 labels 一起持久化）
                    if (newName !in selected) onChange(labelsCsv((selected + newName).toList()))
                }
            }
        )
    }
    Text(
        "长按标签可改名字与颜色",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    colorTarget?.let { name ->
        LabelEditDialog(
            name = name,
            current = tints[name] ?: hues[name]?.let { LabelTint(it) },
            knownNames = candidates,
            // 顺手清掉可能残留的屏蔽位：长按后如果手指是拖走的，抬起时不会有 onClick 来消费它，
            // 不清的话这个标签的下一次点击会被白白吞掉
            onDismiss = { suppressClick = null; colorTarget = null },
            onSave = { newName, tint ->
                suppressClick = null
                if (newName != name) {
                    // 当前这条自己换词；别的条目交给全局改名（颜色由改名一并带过去）
                    onChange(replaceLabelIn(value, name, newName))
                    onRename(name, newName)
                }
                onColor(newName, tint)
                colorTarget = null
            }
        )
    }
}

/**
 * v28：自由标签的调色板（滑块）；v19.39 升级为**改名 + 色环**。
 *
 * - 色相画成环而不是线：色相本就是个环（滑块最右与最左是相邻的两种颜色），
 *   在线上却隔着整个量程，看不出"其实挨着"。
 * - 改名会同步到所有用到该标签的条目（笔记本/待办/日程），所以做成"全局改名"，
 *   而不是只改当前这一条——否则同一个词在别处还在，用户会以为改坏了。
 * - 「恢复默认色」传 null（= 删掉 `label_colors` 里的记录），不是存一个"默认值"占位，
 *   否则"没设过"和"设成了默认"在库里分不开。
 */
@Composable
internal fun LabelEditDialog(
    name: String,
    current: LabelTint?,
    knownNames: List<String>,
    /** true = 新建标签（标题/文案不同；不显示「恢复默认色」，因为还没有旧颜色） */
    creating: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (newName: String, tint: LabelTint?) -> Unit
) {
    // 改名与颜色在同一层里：用户长按一个标签，意图就是"这个标签本身要改"，
    // 让他分两次、还分两个弹窗，等于逼他记住两个入口。
    var textName by remember(name) { mutableStateOf(name) }
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    var hue by remember(current) { mutableStateOf(current?.hue ?: 210f) }
    // 滑块的初值 = 当前**实际生效**的饱和度/明度（用户没调过时就是主题派生的那组），
    // 这样第一次拖动是连续的，不会从别的位置"跳"过来。
    val baseTone = SeedColors.tone(current?.hue ?: 210f, dark)
    var sat by remember(current) { mutableStateOf(current?.sat ?: SeedColors.satOf(baseTone)) }
    var value by remember(current) { mutableStateOf(current?.value ?: SeedColors.lightnessOf(baseTone)) }
    val trimmed = textName.trim()
    // 不允许改成另一个已存在的标签名：两个词合并会让引用关系和颜色都乱掉
    val clash = trimmed.isNotEmpty() && trimmed != name && trimmed in knownNames
    // 预览用的颜色：和芯片走同一个函数，改滑块时所见即所得
    val preview = LabelTint(hue, sat, value)

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (creating) "新建标签" else "编辑标签") },
        text = {
            Column(
                Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = textName,
                    onValueChange = { textName = it },
                    label = { Text("标签文字") },
                    singleLine = true,
                    isError = clash,
                    supportingText = if (clash) {
                        { Text("\u300c$trimmed\u300d已经存在，换个名字") }
                    } else null,
                    modifier = Modifier.fillMaxWidth()
                )
                // ① 色相：色环（色相是环，滑块两端其实相邻）
                HueRing(hue = hue, onHueChange = { hue = it })
                // ② 预览：直接给真芯片，比任何色块都直观（新建时还没打字就先给个占位词）
                TagChip(text = trimmed.ifEmpty { if (creating) "标签预览" else name }, tint = preview)
                Text(
                    "色相 ${hue.toInt()}\u00b0",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // ③ 浓淡：只给色环就只能"换方向"，调不出同色系的深浅，所以必须两个轴都给
                TintSlider(
                    label = "浓度",
                    value = sat,
                    onChange = { sat = it }
                )
                TintSlider(
                    label = "明度",
                    value = value,
                    onChange = { value = it }
                )
                Text(
                    if (creating) "保存后颜色立即生效（所有页面共用），并自动勾到当前这一条"
                    else "改名会同步更新所有用到它的条目",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = trimmed.isNotEmpty() && !clash,
                onClick = { onSave(trimmed, preview) }
            ) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (current != null) {
                    // 只清颜色，不动名字
                    TextButton(onClick = { onSave(name, null) }) { Text("恢复默认色") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}

/**
 * 浓淡滑块。
 *
 * 用 `Slider` 而不是自己画：位置、无障碍、拖动手感都是系统给的。
 * 但**轨道颜色不能用主题的 primary** —— 我们在调"颜色本身"，轨道若是一根固定的
 * 主题色，用户会误以为那就是结果色（用户反馈过"滑块颜色像锁死"）。
 * 这里把轨道按当前取值填成「从无色到满值」的渐变，拖动过程本身就是预览。
 */
@Composable
private fun TintSlider(label: String, value: Float, onChange: (Float) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                "${(value * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.fillMaxWidth().height(28.dp)
        )
    }
}

/** 「未分类」灰色 chip（区别于分类色） */
@Composable
fun UnclassifiedChip(text: String = "未分类", modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(7.dp)
                .background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape)
        )
        Text(
            " $text",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 优先级语义色（高红/中黄/低蓝，保留标准色相） */
@Composable
fun priorityColor(priority: Int): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (priority) {
        0 -> SeedColors.priorityHigh(dark)
        2 -> SeedColors.priorityLow(dark)
        else -> SeedColors.priorityMid(dark)
    }
}

fun priorityLabel(priority: Int): String = when (priority) {
    0 -> "高"; 2 -> "低"; else -> "中"
}

// ---------- v28：计划页着色（标签色 / 紧急·重要 两种模式，开关在「我的」） ----------

/**
 * 没有标签时的颜色：**中性、跟随主题**，不是纯白。
 *
 * ## 语义不变，实现变了
 *
 * 用户口径一直是「无标签 = 没染色」。这个语义要保留——
 * 染成别的颜色会撒谎（用户会以为那颜色是某个标签）。
 * 变的只是"没染色"用什么来表达。
 *
 * 原来写死 `Color.White`，在暗色模式下有两个问题：
 * - 一块刺眼亮斑，和界面整体脱节；
 * - 它是整套配色里唯一的孤儿——标签色跟着 `labelHues`（用户自定义）走、
 *   紧急·重要色跟着等级走，只有无标签是硬编码的颜色。
 *
 * 换成 surfaceContainerHighest 后：和背景有层次（看得出这是一个块），
 * 明暗两种模式都成立；调用方按 `luminance() > 0.6f` 翻转文字色那条路径
 * 也能正确工作。
 *
 * 从 `val` 改成 `@Composable val`：主题色只能在组合期读，
 * 写成常量正是原来这个问题的根源。
 */
val NoLabelColor: Color
    @Composable get() = MaterialTheme.colorScheme.surfaceContainerHighest


/**
 * 筛选芯片的**统一**配色：走 primary 家族，不走 FilterChip 的默认 secondary。
 *
 * ## 为什么要覆盖默认
 *
 * `FilterChip` 默认的 `selectedContainerColor` 是 `secondaryContainer`，
 * 而 secondary 在 Theme.kt 里是 `h + 28f`——比主色相**偏了 28°**。
 * 于是同一页面里出现两套色系：FAB / 底栏选中 / 顶栏按钮用 `primary`（原色相），
 * 筛选芯片用"色相 +28°"。换种子色时芯片会跳到另一个色系去：
 * 玫红种子（348°）下芯片底变成 16°（红），其它控件还是玫红。
 * 真机取色实测：蓝色种子 #FF576FF5（231°）下，「全部」芯片选中底是 259°，
 * 正好 231 + 28。
 *
 * 所以这里显式指定：选中底 `primaryContainer`、选中字 `onPrimaryContainer`、
 * 未选底透明、边框 `outline`——与同页面其它控件同源。
 *
 * 抽成函数而不是各写各的：五处调用点（计划页三组、专注时长、笔记标签），
 * 各写一遍迟早会漂。
 *
 * 两个版本事实（都是踩出来的，别再猜）：
 * 1. Material3 1.4.0 的 `FilterChipDefaults.filterChipColors(...)`
 *    **不接受颜色参数**——class 签名只有 filterChipColors(Composer, int)。
 * 2. 它的 `SelectableChipColors` 构造器是 13 个参数，
 *    **没有** iconColor / borderColor / disabledSelectedLabelColor。
 *    所以这里只钉底色与文字色；边框由 outlined 形态的默认描边负责，
 *    强行给 borderColor 会直接编不过。
 * 参数名是从 material3 的 class 字节码常量池里读出来的，不是猜的。
 */
@Composable
fun filterChipColors(): SelectableChipColors = SelectableChipColors(
    containerColor = Color.Transparent,
    labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
    leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    trailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
    disabledContainerColor = Color.Transparent,
    disabledLabelColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
    disabledLeadingIconColor = Color.Transparent,
    disabledTrailingIconColor = Color.Transparent,
    // 颜色分工原则：**可交互控件统一主色族**。筛选 chip 属操作类，选中态用
    // primaryContainer——"同类按钮一红一绿"是不合理的（用户 2026-10-24 反馈）。
    // 第二/三色只出现在非交互语义点缀（小组件徽标、图表、分类标识）里。
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    disabledSelectedContainerColor =
        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
    selectedTrailingIconColor = MaterialTheme.colorScheme.onPrimaryContainer
)


/**
 * 两套档位色阶：色相都**从主题种子色算出来**（不再写死暖/冷两组固定色相）。
 *
 * - 紧急性：在种子色相附近小幅偏转（±25°），5 档靠色相微转 + 饱和度/明度推进；
 * - 重要性：取种子色的**补色一侧**（+155°~+205°），与紧急性天然拉开一大段色相距离。
 *
 * 这样换主题色（青绿 / 紫 / 蓝…）时两套色阶都跟着变，但**永远是两个色系**：
 * 一条贴着主题色，一条在补色，不会撞在一起。
 *
 * 同时保留五档可分辨：`levelRamp` 把色相、饱和度（0.42→0.90）、明度（0.98→0.70）
 * 一起单调推进，相邻两档不只差一点浓度。
 *
 * 之前是写死 hue 48→6（琥珀→红）和 200→276（青→紫）：换主题后和界面配色脱节，
 * 绿色主题配一片红胶囊很突兀。
 */
@Composable
fun urgencyColor(level: Int): Color {
    val h = SeedColors.hueOf(MaterialTheme.colorScheme.primary)
    return levelRamp(h - 25f, h + 25f, level)
}

@Composable
fun importanceColor(level: Int): Color {
    val h = SeedColors.hueOf(MaterialTheme.colorScheme.primary)
    return levelRamp(h + 155f, h + 205f, level)
}

/** t=0 浅而淡，t=1 深而浓；色相同时从 [hueLow] 走到 [hueHigh] */
private fun levelRamp(hueLow: Float, hueHigh: Float, level: Int): Color {
    val t = level.coerceIn(0, 4) / 4f
    // v19.48：**色相必须 wrap 回 0..360**，否则 Color.hsv 直接抛
    // IllegalArgumentException("HSV (387.6, 0.9, 0.7) must be in range (0..360, 0..1, 0..1)")
    // 然后整个计划页崩（真机 10-17 21:52 / 22:56 两次 FATAL EXCEPTION 都是这里）。
    //
    // 起因：色相改成"由主题色相派生"（急=主题色±25°，重=补色区 +155~205°），
    // 而主题色相本身可能接近 360（例如 346°），加 205 就 551°，减 25 就 321° 也可能溢出。
    // 色相是**环**，387.6° 与 27.6° 是同一个颜色，所以取模而不是 clamp
    // （clamp 会把 551° 压成 360°=红，而它本该是 191°=青，颜色会静默变错）。
    val hue = ((hueLow + (hueHigh - hueLow) * t) % 360f + 360f) % 360f
    return Color.hsv(
        hue,
        0.42f + 0.48f * t,
        0.98f - 0.28f * t
    )
}

/**
 * 按紧急性 + 重要性合成的行颜色。
 *
 * **色相随重要性**（220° 冷 → 0° 暖，5 档），**饱和度随紧急性**（0.35 → 0.95，5 档）——
 * 两维各占一个视觉通道，比把两维加权成一个分数更好读：
 * 「很重要但不急」= 暖色低饱和，「很急但不重要」= 冷色高饱和，一眼分得开。
 * 只用一个通道的话，需求里的"根据紧急性、重要性显示颜色"就名存实亡了。
 */
@Composable
fun urgencyImportanceColor(importance: Int, urgency: Int): Color {
    // 任一维未设置（-1）：不参与着色，用中性灰——"没填"不能长得像"低"
    if (importance < 0 || urgency < 0) return MaterialTheme.colorScheme.surfaceContainerHighest
    val imp = importance.coerceIn(0, 4)
    val urg = urgency.coerceIn(0, 4)
    val hue = 220f - 220f * imp / 4f
    val sat = 0.35f + 0.60f * urg / 4f
    return Color.hsv(hue, sat, 0.95f)
}

/**
 * 计划页行首色条/圆点的统一取色，两种模式一个入口：
 *
 * - 默认（[byUrgencyImportance] = false）= 第一个自由标签的 hue，**没标签 = 白色**；
 * - 打开开关 = [urgencyImportanceColor]。
 *
 * 多个标签时取第一个：行首色条只有一条，取第一个和取最后一个都说得通，
 * 但"第一个"与用户在标签行里排第一的位置一致，规则可预测。
 */
@Composable
fun planItemColor(
    labels: String,
    labelHues: Map<String, Float>,
    importance: Int,
    urgency: Int,
    byUrgencyImportance: Boolean,
    /** 标签浓淡（饱和度/明度）。有就优先用，行首色条与标签芯片才不会一深一浅 */
    labelTints: Map<String, LabelTint> = emptyMap()
): Color {
    if (byUrgencyImportance) return urgencyImportanceColor(importance, urgency)
    val first = parseLabels(labels).firstOrNull() ?: return NoLabelColor
    labelTints[first]?.let { return categoryColor(it) }
    val hue = labelHues[first] ?: return NoLabelColor
    return categoryColor(hue)
}

@Composable
fun SectionCard(
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (title != null) {
                Text(title, style = MaterialTheme.typography.titleMedium)
            }
            content()
        }
    }
}

@Composable
fun EmptyHint(text: String, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun TagChip(text: String, hue: Float, modifier: Modifier = Modifier) =
    TagChip(text, LabelTint(hue), modifier)

/**
 * 胶囊外形（标签胶囊与「紧急·重要」胶囊共用这一套）。
 *
 * 尺寸/圆角/底色只写一份：两边各自写一份的话，稍微改一处就会高低不齐，
 * 而它们本来就要在同一行里并排。
 */
@Composable
fun DotCapsule(text: String, dot: Color, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .background(
                MaterialTheme.colorScheme.surfaceContainerHighest,
                ShapeTokens.pill
            )
            .padding(start = 6.dp, end = 9.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier
                .size(9.dp)
                .background(dot, CircleShape)
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall.copy(
                // 中文字形在默认行框里上下留白不对称（下边缘总比上边缘松），
                // 胶囊会显得"上薄下厚"（用户反馈「上下厚度不同」）。
                // 关掉字体的额外留白，让行框紧贴字形；**不要再压 lineHeight**——
                // 行高小于字形自然高度时，墨迹会从行框下沿溢出，反而整行偏下。
                platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
    }
}

/** 带浓淡的版本：色相 + 用户调过的饱和度/明度 */
@Composable
fun TagChip(text: String, tint: LabelTint, modifier: Modifier = Modifier) {
    // 胶囊形态的取舍：
    // - 底色**不跟标签色走**，而是主题同步的低饱和色（surfaceContainerHighest 本身就是
    //   seed 色相 + 约 8% 饱和）。原来底色是标签色的高饱和容器（浅色下 85% 饱和），
    //   一行里三个标签就是三块抢眼的彩色，反倒看不出哪个标签重要。
    // - 标签色只由左边那个圆点承担，所以点从 7dp 放大到 9dp：底色退到背景层之后，
    //   颜色信息只剩下这一点，太小就认不出来了。
    DotCapsule(text = text, dot = categoryColor(tint), modifier = modifier)
}

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    confirmText: String = "删除",
    danger: Boolean = true
) {
    val haptics = LocalHaptics.current
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        text = { Text(text) },
        confirmButton = {
            TextButton(onClick = {
                // 破坏性确认给全场最重的一下（HIG impactHeavy → Kind.ImpactHeavy）：
                // "这一步不可逆"要在指尖上就有分量，和"成功/出错"的两段波形也摸得出区别；
                // danger=false 的普通确认只给轻触，别让"确认改个设置"听起来像删库。
                haptics.perform(if (danger) HapticEngine.Kind.ImpactHeavy else HapticEngine.Kind.Tap)
                onConfirm(); onDismiss()
            }) {
                Text(confirmText, color = if (danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("取消") }
        }
    )
}

fun formatMinutes(minutes: Int): String = when {
    minutes >= 60 -> "${minutes / 60} 小时 ${minutes % 60} 分钟"
    else -> "$minutes 分钟"
}

/** mm:ss */
fun formatClock(sec: Int): String =
    "%02d:%02d".format(sec / 60, sec % 60)

/**
 * FAB 统一入口。名字沿用 B3-B 的 MediumFab，尺寸为 M3 **默认 56dp**
 * （2026-10-24：80dp 超规范加大被用户否掉——"所有新建按钮都太大"）。
 * 2026-10-25 用户定稿：**圆形、放在屏幕底部中间**——shape 钉 CircleShape（M3 默认 16dp
 * 圆角矩形被否），位置由各调用点 align BottomCenter / FabPosition.Center 负责。
 * 六个调用点（计划×3/自动化×2/对话回顶上方新建）+ 笔记页新增共用。
 */
@Composable
fun MediumFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    androidx.compose.material3.FloatingActionButton(
        onClick = onClick,
        shape = androidx.compose.foundation.shape.CircleShape,
        modifier = modifier.pressScale(),
        content = content
    )
}

/**
 * 七页统一标题栏：M3 **small app bar 几何**（容器最小高 64dp、标题走 22sp token 的
 * headlineSmall——用户字体体系的 22sp 档，勿改回 56dp/28sp 的旧第三种写法）、
 * 前置/尾随图标垂直居中——各页切换时标题不再跳位。
 * subtitle（如首页日期）向下顺排，不改变标题顶距。
 * startHorizontalPadding：首页评分胶囊挤占尾随宽度时，前置按钮可左移让位给标题列
 * （默认 = horizontalPadding，保持其余六页的 32dp 基准不变）。
 * 标题与副行限一行（Ellipsis）——空间不足时截断而非折行把标题栏顶高。
 * 不做底色容器：本 app 的页面语言是透明头部 + 悬浮底栏，上底色会出现色带
 * （要彻底换成库 TopAppBar 涉及 4 个一级页的 inset 行为，另议）。
 */
@Composable
fun PageHeader(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    horizontalPadding: androidx.compose.ui.unit.Dp = 20.dp,
    startHorizontalPadding: androidx.compose.ui.unit.Dp = horizontalPadding,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null
) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .padding(start = startHorizontalPadding, end = horizontalPadding)
    ) {
        if (leading != null) {
            // 必须 Row 排布：Box 会把多个图标叠在同一格（文件页双图标重叠根因）
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterVertically).padding(end = 4.dp)
            ) { leading() }
        }
        Column(Modifier.weight(1f).padding(top = 16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
            }
        }
        if (trailing != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.align(Alignment.CenterVertically)
            ) { trailing() }
        }
    }
}

/**
 * 顶栏返回按钮（带触感）：按下先振 [HapticEngine.Kind.Tap] 再返回。
 *
 * 为什么单独抽一个组件：十几个子页各写各的 `IconButton(onClick = onDone)`，
 * 漏一处就少一处反馈，而返回是全 app 最高频的动作之一——集中在这里，将来改 Kind 只改一处。
 * 为什么用 Tap 而不是 Select：返回是导航不是选择（HIG selection → chip/tab/switch/滑块），
 * 中性轻触正好；映射表见 `core/Haptics.kt`。
 */
@Composable
fun HapticBackButton(onDone: () -> Unit) {
    val haptics = LocalHaptics.current
    IconButton(
        onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() },
        modifier = Modifier.pressScale()
    ) {
        Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
    }
}
