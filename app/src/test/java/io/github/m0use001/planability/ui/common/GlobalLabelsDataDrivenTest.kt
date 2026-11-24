package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 全局标签列表必须**纯由数据汇总**（源码级断言）。
 *
 * ## 为什么要有
 * 用户 10-18 反馈：「笔记本界面顶端还留有写死的标签」。
 * 现象：测试数据全删之后，笔记页顶端那一排筛选 chip 仍然显示
 * 「工作 / 生活 / 学习 / 等待中」——因为 `loadGlobalLabels()` 里有一行
 * `LABEL_PRESETS.forEach { freq.putIfAbsent(it, 0) }`，把六个预置标签无条件塞了进来。
 *
 * ## 为什么这是 bug 而不是功能
 * - 这一排 chip 的数据源是 `rememberGlobalLabels()`，语义是"**用过了哪些标签**"，
 *   按 notebooks/todos/schedules 的出现频次汇总；
 * - 预置标签只该出现在**编辑器里供挑选**（`LabelEditorRow` 自己会读 `LABEL_PRESETS`，
 *   那处是合理的）；
 * - 一条数据都没有时，顶排应该只剩「全部」——显示六个从没建过的标签，
 *   既不反映数据，也会让用户以为这些标签已经存在。
 *
 * ## 为什么用源码断言
 * `loadGlobalLabels()` 读 Graph（Room + Settings），JVM 单测里起不来，
 * 只能读源码。而这条约定的失效症状**不崩、不报错**，只是多几个 chip。
 */
class GlobalLabelsDataDrivenTest {

    private fun src(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/io/github/m0use001/planability/ui/common/LabelStore.kt")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 LabelStore.kt")
    }

    @Test
    fun `loadGlobalLabels 不许把预置标签掺进汇总结果`() {
        val body = src().substringAfter("fun loadGlobalLabels").substringBefore("\n}")
        assertTrue("找不到 loadGlobalLabels", body.isNotEmpty())

        assertTrue(
            "loadGlobalLabels 又把 LABEL_PRESETS 塞进 freq 了——" +
                "那会让一条数据都没有时，笔记页顶排仍显示「工作/生活/学习/等待中」（用户 10-18 反馈）",
            !body.contains("LABEL_PRESETS.forEach { freq.putIfAbsent")
        )
        // 但数据源三个都要在： notebooks / todos / schedules 漏一个，汇总就少一块
        for (src in listOf("notebooks", "todos", "schedules")) {
            // schedules 是 occurrence 展开读的，同一条重复日程会出现很多次；
            // 计数按 id 去重（2026-10-19 起），两种写法都算"汇总了这一处"
            val aggregated = body.contains("$src.forEach { add(") ||
                body.contains("$src.distinctBy { it.id }.forEach { add(")
            assertTrue(
                "loadGlobalLabels 没有汇总 $src 的标签——那一处的标签就永远不出现在筛选里",
                aggregated
            )
        }
    }

    @Test
    fun `预置标签只归编辑器用`() {
        val editor = src().let { s ->
            // LabelEditorRow 在 Ui.kt，那里读 LABEL_PRESETS 是正当的
            var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
            while (dir != null) {
                val f = File(dir, "app/src/main/java/io/github/m0use001/planability/ui/common/Ui.kt")
                if (f.isFile) return@let f.readText(Charsets.UTF_8)
                dir = dir.parentFile
            }
            throw AssertionError("找不到 Ui.kt")
        }
        assertTrue(
            "Ui.kt 的 LabelEditorRow 不读 LABEL_PRESETS 了——编辑器里就没有可点的预置标签了",
            editor.contains("val candidates = (known + LABEL_PRESETS).distinct()")
        )
    }
}
