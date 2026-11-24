package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具**注册**的闸门。
 *
 * ## 这不是形式主义，是一次真实事故的补锅
 *
 * v14.3 加 `file_build_deck` 时只写了 [ToolRegistry.ALL] 和执行实现，忘了登记进
 * [ToolRegistry.PLUGINS] 的 office 包。后果不是报错，而是**模型看不见这个工具**：
 * 真机实测它改用 `file_export_document` 顶着做 pptx，传 .pptx 文件名配 docx 内容，
 * 产出一个打不开的假文件。而"工具能跑""包能开""单测全绿"全都正常——
 * 缺的那一环没有任何现有检查覆盖。
 *
 * 所以这里补上：凡是写进能力包的，必须真实存在；凡是能力包里列的，必须能对上 ALL。
 * 以后再加工具忘了登记，测试会失败。
 */
class ToolDefsTest {

    @Test
    fun everyPluginToolActuallyExists() {
        ToolRegistry.PLUGINS.forEach { (pkg, tools) ->
            tools.forEach { t ->
                assertTrue(
                    "能力包 $pkg 里列了不存在的工具「$t」——会静默失效（模型看不见、也不报错）",
                    ToolRegistry.ALL.any { it.name == t }
                )
            }
        }
    }

    @Test
    fun officePluginContainsAllOfficeTools() {
        val office = ToolRegistry.PLUGINS["office"].orEmpty()
        listOf(
            "file_read_document",   // 读
            "file_export_document", // 写 docx/xlsx/csv
            "file_build_deck",      // 生成 pptx
            "file_edit_deck"        // 改 pptx
        ).forEach {
            assertTrue(
                "office 能力包漏了「$it」：模型看不到它，只能拿别的工具硬凑，" +
                    "产出打不开的假文件（P0 踩过一次）",
                office.contains(it)
            )
        }
    }

    @Test
    fun everyToolHasChineseDescriptionAndUseCase() {
        // 模型是靠这两段中文判断"该不该用这个工具"的，空着等于半个哑工具
        ToolRegistry.ALL.forEach { d ->
            assertTrue("工具 ${d.name} 缺中文说明", d.cnDescription.isNotBlank())
            assertTrue("工具 ${d.name} 缺使用场景", d.whenToUse.isNotBlank())
            assertTrue(
                "工具 ${d.name} 的参数 ${d.params.firstOrNull()?.name} 缺说明",
                d.params.all { it.description.isNotBlank() }
            )
        }
    }

    @Test
    fun everyToolIsReachableSomewhere() {
        // 2026-10-20 盘查发现：file_read_image / sleep_query / work_plan_start /
        // automation_actions / automation_run / automation_pin 六个工具定义+实现都在，
        // 却**谁都没登记**进 DOMAINS/PLUGINS。toToolsJson 只发登记过的，
        // 于是模型永远看不到它们；而提示词里还让模型去调 automation_actions、sleep_query，
        // 真机表现就是"未知工具"。这类漏登记不报错、不崩溃，只能靠测试守。
        val covered = ToolRegistry.DOMAINS.values.flatten().toSet() +
            ToolRegistry.PLUGINS.values.flatten().toSet()
        val orphans = ToolRegistry.ALL.map { it.name }.filter { it !in covered }
        assertTrue(
            "这些工具既不在 DOMAINS 也不在 PLUGINS，模型永远拿不到：$orphans",
            orphans.isEmpty()
        )
    }

    @Test
    fun toolNamesAreUnique() {
        val dup = ToolRegistry.ALL.groupBy { it.name }.filterValues { it.size > 1 }.keys
        assertTrue("有重名的工具：$dup", dup.isEmpty())
    }

    // 曾试过加一条"必填参数必须排在可选参数前面"的检查，结果扫出的是 memory_add 等
    // **既有**工具——说明本项目本来就没这条约定，而不是我新写的工具违反了它。
    // 为了让一条自造规则通过而去改动一堆别人的工具不划算，所以这里不加。
    // 真要减少模型漏填，靠的是参数说明写清楚（上面那条已在检查），不是参数顺序。
}
