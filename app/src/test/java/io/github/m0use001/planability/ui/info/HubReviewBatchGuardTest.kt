package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-12 用户「待处理审核还是一千多条」回归守卫。
 *
 * 真机取证：判重清扫已生效（1360→1249，111 条进已忽略），但剩下的 1249 条是 capture
 * 两天提纯出的互不相同草稿——判重管不住产量，审核页必须能批量处理。
 * 本守卫钉死批量模式的四件套：开关、整卡勾选、全选当前筛选、批量忽略（确认弹窗+分批落库）。
 */
class HubReviewBatchGuardTest {

    private val hub = java.io.File("src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt").readText()
    private val repo = java.io.File("src/main/java/io/github/m0use001/planability/data/DomainRepos.kt").readText()

    @Test
    fun `审核页有批量模式开关与选区状态`() {
        assertTrue("缺 selectMode 状态", hub.contains("var selectMode by remember"))
        assertTrue("缺选区状态", hub.contains("var selected by remember { mutableStateOf<Set<Long>>(emptySet()) }"))
        assertTrue("顶栏缺批量开关按钮", Regex("Icon\\(\\s*Icons\\.Rounded\\.Checklist").containsMatchIn(hub))
    }

    @Test
    fun `批量模式整卡可点勾选且逐条按钮收起`() {
        assertTrue("卡片没接 selectMode 参数", hub.contains("selectMode = selectMode"))
        assertTrue("批量模式没把整卡变点击勾选", Regex("if \\(selectMode\\) Modifier\\.clickable \\{ onToggle\\(\\) \\}").containsMatchIn(hub))
        // 逐条编辑/入库/忽略按钮必须包在 if (!selectMode) 里，防误触
        assertTrue("逐条操作按钮没在批量模式下收起", hub.contains("if (!selectMode) {"))
    }

    @Test
    fun `全选当前筛选与批量忽略入口`() {
        assertTrue("缺「全选当前筛选」", hub.contains("全选当前筛选"))
        assertTrue("缺「忽略所选」入口", hub.contains("忽略所选（"))
        assertTrue("批量忽略没走确认弹窗", hub.contains("confirmBatchIgnore"))
        assertTrue("弹窗没提示找回路径（已忽略可在历史找回）", hub.contains("审核历史"))
    }

    @Test
    fun `批量忽略走 batchMarkPending 且落库分批`() {
        Regex("Graph\\.info\\.batchMarkPending\\(ids, \"ignored\"\\)").find(hub)
            ?: throw AssertionError("批量忽略没调 Graph.info.batchMarkPending(ids, \"ignored\")")
        // 上千 id 一次 IN 撞 SQLite 变量上限 999，必须分批
        Regex("ids\\.chunked\\(500\\)\\.forEach \\{ pendingDao\\.batchMarkStatus\\(it, status\\) \\}").find(repo)
            ?: throw AssertionError("batchMarkPending 没按 500 分批")
    }

    @Test
    fun `列表刷新时选区剔除已消失的行`() {
        assertTrue("选区没随 items 刷新剔除失效 id", hub.contains("selected = selected intersect items.map { it.id }.toSet()"))
    }
}
