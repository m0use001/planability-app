package io.github.m0use001.planability.ui.plan

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 计划页筛选状态的源码级断言。
 *
 * 踩过的坑：切换筛选维度时，各维度的档位筛选（分类 / 标签 / 重要性 / 紧急性）原本不会失效。
 * 于是「紧急性=偏低 → 切回状态」之后，列表仍然按那个**界面上根本看不见**的紧急性档位过滤：
 * 真机 6 条未完成待办只剩 1 条，而筛选行显示的是「状态 / 进行中」，用户完全无从判断。
 * UI 行为没法用普通单测覆盖，所以把「换维度必须清掉隐藏筛选」钉在源码上。
 */
class PlanFilterStateTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/plan/PlanScreen.kt")
        assertTrue("找不到 PlanScreen.kt（测试的工作目录不对？）", f.exists())
        f.readText()
    }

    private fun code(): String =
        src.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `换维度要清掉看不见的筛选`() {
        val c = code()
        // 下拉项的 onClick 里，四种筛选各自都要有「切到这个维度就不再显示 -> 置 null」的判断
        for (pair in listOf(
            "i != 1" to "tagFilter = null",
            "i != 2" to "labelFilter = null",
            "i != 3" to "impFilter = null",
            "i != 4" to "urgFilter = null",
        )) {
            assertTrue(
                "维度下拉的 onClick 少了 `${pair.first} -> ${pair.second}`：" +
                    "切维度后旧筛选会在界面上隐形地继续生效（列表凭空少条目）",
                c.contains(pair.first) && c.contains(pair.second)
            )
        }
    }

    @Test
    fun `五个维度都在清单里且下拉与标题同源`() {
        val c = code()
        assertTrue(
            "维度清单必须是同一个常量同时供标题和下拉项使用（各写一份就会一个加了另一个没加）",
            c.contains("private val FILTER_DIMS = listOf(\"状态\", \"分类\", \"标签\", \"重要性\", \"紧急性\")")
        )
        assertTrue(
            "下拉项必须遍历 FILTER_DIMS，不能另写一份 listOf",
            c.contains("FILTER_DIMS.forEachIndexed")
        )
        assertTrue("下拉标题没走 FILTER_DIMS[filterDim]", c.contains("FILTER_DIMS[filterDim] + \" ▾\""))
    }

    @Test
    fun `紧急重要筛选要挂在重算列表的 key 上`() {
        val c = code()
        assertTrue(
            "重算待办列表的 LaunchedEffect 没有把 impFilter/urgFilter 放进 key —— " +
                "改了档位不会重新过滤（漏一个就是\"点了没反应\"）",
            Regex("""LaunchedEffect\([^)]*impFilter[^)]*urgFilter""").containsMatchIn(c)
        )
    }

    @Test
    fun `筛选口径与编辑器同一套档位`() {
        val c = code()
        assertTrue(
            "重要性筛选必须直接比 importance 列（0-4 档），别另立一套映射",
            c.contains("t.importance == impFilter")
        )
        assertTrue(
            "紧急性筛选必须直接比 urgency 列（0-4 档），别另立一套映射",
            c.contains("t.urgency == urgFilter")
        )
    }
}
