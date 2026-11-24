package io.github.m0use001.planability.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import io.github.m0use001.planability.data.Graph
import java.time.LocalDate

/**
 * 全局统一标签：一份清单 + 一份颜色，所有页面共用。
 *
 * 之前每个页面自己算清单，两套算法还各漏一块：
 * - 笔记页只并 `notebooks + activeTodos`，看不到日程的标签；
 * - 计划页只并 `schedules + activeTodos`，看不到笔记本的标签。
 *
 * 于是同一个标签在两处的候选列表里可能缺席（比如只在笔记本上用的标签，
 * 在计划页没法勾选；只在日程上用的标签，在笔记页没法勾）。
 * 这里收口成一个仓库：三处来源全并上，另外把 `label_colors` 的键也算进来
 * ——那些键是「曾经被设过色」的标签，哪怕条目已删也不该从清单里消失。
 *
 * 排序按**使用频次降序**（用得多的排前面），同频次按名称排，
 * 保证各页看到的顺序完全一致。
 */
data class GlobalLabels(
    /** 全部标签词，按频次降序 */
    val names: List<String>,
    /** 标签名 -> 色相 0..360；没设过色的不在表里 */
    val hues: Map<String, Float>,
    /**
     * 标签名 -> 完整颜色（色相 + 用户调过的浓淡）。
     * 键集合与 [hues] 一致；`sat` / `value` 为 null = 用户没调过，按主题派生。
     */
    val tints: Map<String, LabelTint> = emptyMap(),
    /** 标签名 -> 引用次数（日程/待办/笔记本里出现的次数）。分类管理页用来显示「N 处」 */
    val counts: Map<String, Int> = emptyMap(),
) {
    /** 取一个标签的颜色；没设过色就用 [fallback] 色相 */
    fun tintOf(name: String, fallback: Float = 210f): LabelTint =
        tints[name] ?: LabelTint(hues[name] ?: fallback)
}

/**
 * 一个标签的颜色。
 *
 * ## 为什么不只存色相
 * 原来 `label_colors` 只有 `hue`，饱和度和明度被主题写死 —— 用户只能"换个颜色方向"，
 * 调不出「同色系但更淡/更深」，不符合 M3 Expressive 的用法。补上 `sat` / `value` 后：
 * - 同一套色相可以做出深浅层次（比如工作=深蓝、生活=浅蓝）；
 * - 饱和度调到 0 就是中性灰，标签可以"弱化"而不必换色相。
 *
 * 存储落在设置表的通用 KV（`label.sat.<名字>` / `label.val.<名字>`），**没有改表结构、
 * 没有迁移**：缺键就退回主题派生值，老数据行为完全不变。
 */
data class LabelTint(
    val hue: Float,
    /** 饱和度 0..1；null = 跟随主题 */
    val sat: Float? = null,
    /** 明度（HSV 的 V）0..1；null = 跟随主题 */
    val value: Float? = null,
)

/** 全部来源汇总（笔记本 / 待办 / 日程 / 已设色的历史标签 / 预置词）。 */
suspend fun loadGlobalLabels(): GlobalLabels {
    val notebooks = Graph.notes.notebooks()
    val todos = Graph.plan.activeTodos()
    // 日程按 occurrence 读：日程本身是「规则 + 例外」的模型，没有"全部日程"这个直接查询，
    // 只能按区间展开。取今年一整年，够覆盖用户实际在用的日程；区间外的老日程标签
    // 仍能通过 label_colors 的键回到清单里。
    val today = LocalDate.now()
    val from = today.withDayOfYear(1)
    val to = today.withDayOfYear(today.lengthOfYear())
    val schedules = runCatching {
        Graph.plan.scheduleOccurrences(from, to).map { it.schedule }
    }.getOrDefault(emptyList())

    val hues = Graph.plan.labelColors()

    // 浓淡（饱和度/明度）存在设置表的通用 KV 里，一次读全表再按前缀取，避免逐个标签查一次。
    val kv = runCatching { Graph.settings.rawAll() }.getOrDefault(emptyMap())
    val tints = hues.mapValues { (name, hue) ->
        LabelTint(
            hue = hue,
            sat = kv["label.sat.$name"]?.toFloatOrNull(),
            value = kv["label.val.$name"]?.toFloatOrNull()
        )
    }

    val freq = LinkedHashMap<String, Int>()
    fun add(csv: String) {
        for (lb in parseLabels(csv)) freq[lb] = (freq[lb] ?: 0) + 1
    }
    notebooks.forEach { add(it.labels) }
    todos.forEach { add(it.labels) }
    // 日程按 occurrence 展开读，同一条重复日程会出现很多次；计数按 id 去重，
    // 否则「课程」会显示成 148 处（其实是 15 条规则展开的次数）
    schedules.distinctBy { it.id }.forEach { add(it.labels) }
    hues.keys.forEach { freq.putIfAbsent(it, 0) }
    // v19.50：删掉 `LABEL_PRESETS.forEach { putIfAbsent(it, 0) }`。
    // 那行会把六个预置标签无条件塞进来，于是**一条数据都没有时**，
    // 笔记页顶端那一排仍然显示「工作/生活/学习/等待中」——用户 10-18 反馈的
    // "笔记本界面顶端还留有写死的标签"。
    // 预置标签只该出现在**编辑器里供挑选**（Ui.kt 的 LabelEditorRow 已单独读 LABEL_PRESETS），
    // 不该污染"全局标签"这个**按数据汇总**的列表。

    val names = freq.entries
        .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }
            .thenBy { it.key })
        .map { it.key }
    return GlobalLabels(names, hues, tints, freq)
}

/**
 * 跟随数据版本号刷新。挂一次就够：三个页签常驻组合，谁组合谁刷新，
 * 但读的是同一份算法，所以清单永远一致。
 */
@Composable
fun rememberGlobalLabels(): GlobalLabels {
    val version by Graph.dataVersion.flow.collectAsState()
    var snap by remember { mutableStateOf(GlobalLabels(emptyList(), emptyMap())) }
    LaunchedEffect(version) {
        snap = loadGlobalLabels()
    }
    return snap
}

/** CSV 里把一个标签词换成另一个（精确匹配整段，不是子串）。 */
fun replaceLabelIn(csv: String, old: String, new: String): String =
    labelsCsv(parseLabels(csv).map { if (it == old) new else it }.distinct())

/** CSV 里删掉一个标签词（精确匹配整段）。 */
fun removeLabelFrom(csv: String, name: String): String =
    labelsCsv(parseLabels(csv).filter { it != name })

/**
 * 全局改名：把一个标签词在**所有**引用它的地方换成新词，并让它已有的颜色跟着走。
 *
 * 覆盖范围：笔记本（全量）、未完成待办、当年日程（按 occurrence 去重后写回）。
 * 回收站与已完成待办不在查询范围内——它们的标签改了也没有 UI 会看到，
 * 真要连它们一起改，得让 PlanRepo 暴露全量待办读取（已在 agents_log 给 A 线留了请求）。
 */
suspend fun renameLabelEverywhere(old: String, new: String) {
    if (old == new || new.isBlank()) return

    // 笔记本。renameNotebook 其余参数传 null 即"保持原样"，只动 labels 一处。
    Graph.notes.notebooks()
        .filter { old in parseLabels(it.labels) }
        .forEach { nb ->
            Graph.notes.renameNotebook(
                nb, nb.name, nb.coverStyle, nb.coverHue,
                labels = replaceLabelIn(nb.labels, old, new)
            )
        }

    // 未完成待办
    Graph.plan.activeTodos()
        .filter { old in parseLabels(it.labels) }
        .forEach { td -> Graph.plan.saveTodo(td.copy(labels = replaceLabelIn(td.labels, old, new))) }

    // 当年日程：occurrence 展开后按 id 去重，避免同一条日程被重复写
    val today = LocalDate.now()
    runCatching {
        Graph.plan.scheduleOccurrences(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))
    }.getOrDefault(emptyList())
        .map { it.schedule }
        .filter { old in parseLabels(it.labels) }
        .distinctBy { it.id }
        .forEach { sc ->
            Graph.plan.saveSchedule(sc.copy(labels = replaceLabelIn(sc.labels, old, new)))
        }

    // 颜色跟着改名走：同一种颜色不该因为改个名就丢掉
    val hue = Graph.plan.labelColors()[old]
    if (hue != null) {
        Graph.plan.setLabelColor(old, null)
        Graph.plan.setLabelColor(new, hue)
    }
    // 浓淡也一样：改名后「更淡/更深」不该被重置回主题默认
    val sat = Graph.settings.raw("label.sat.$old")
    val value = Graph.settings.raw("label.val.$old")
    if (sat != null) Graph.settings.putRawValue("label.sat.$new", sat)
    if (value != null) Graph.settings.putRawValue("label.val.$new", value)
    if (sat != null || value != null) {
        Graph.settings.removeRaw("label.sat.$old")
        Graph.settings.removeRaw("label.val.$old")
    }
}

/**
 * 全局删除一个标签：把**所有**引用它的条目上的这个词去掉，并清掉它的颜色/浓淡。
 *
 * 覆盖范围与 [renameLabelEverywhere] 一致（笔记本全量、未完成待办、当年日程）：
 * 分类管理页是这些标签唯一的"总览"入口，只删半截的话，用户在已归档/回收站里还会看到它。
 *
 * 只删这个词，不动条目本身；条目上如果只剩这一个标签，`labels` 变成空串。
 */
suspend fun deleteLabelEverywhere(name: String) {
    if (name.isBlank()) return

    Graph.notes.notebooks()
        .filter { name in parseLabels(it.labels) }
        .forEach { nb ->
            Graph.notes.renameNotebook(
                nb, nb.name, nb.coverStyle, nb.coverHue,
                labels = removeLabelFrom(nb.labels, name)
            )
        }

    Graph.plan.activeTodos()
        .filter { name in parseLabels(it.labels) }
        .forEach { td -> Graph.plan.saveTodo(td.copy(labels = removeLabelFrom(td.labels, name))) }

    val today = LocalDate.now()
    runCatching {
        Graph.plan.scheduleOccurrences(today.withDayOfYear(1), today.withDayOfYear(today.lengthOfYear()))
    }.getOrDefault(emptyList())
        .map { it.schedule }
        .filter { name in parseLabels(it.labels) }
        .distinctBy { it.id }
        .forEach { sc -> Graph.plan.saveSchedule(sc.copy(labels = removeLabelFrom(sc.labels, name))) }

    // 颜色与浓淡一并清掉（setLabelColor(name, null) = 删掉这条颜色记录）
    Graph.plan.setLabelColor(name, null)
    Graph.settings.removeRaw("label.sat.$name")
    Graph.settings.removeRaw("label.val.$name")
}

/**
 * 保存一个标签的浓淡。
 *
 * @param tint null = 恢复默认（色相仍由 `label_colors` 管，这里只清浓淡两个键）
 *
 * 写设置表**不会** bump `dataVersion`（`SettingsRepo` 的既定行为），
 * 所以调用方改完要自己触发一次界面刷新 —— 见 `LabelEditorRow` 的 `onColor`
 * 里跟 `setLabelColor` 一起调用的地方。
 */
suspend fun saveLabelTint(name: String, tint: LabelTint?) {
    val satKey = "label.sat.$name"
    val valKey = "label.val.$name"
    if (tint?.sat == null) Graph.settings.removeRaw(satKey)
    else Graph.settings.putRawValue(satKey, tint.sat.toString())
    if (tint?.value == null) Graph.settings.removeRaw(valKey)
    else Graph.settings.putRawValue(valKey, tint.value.toString())
}