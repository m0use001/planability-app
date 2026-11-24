package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具**可见性**回归（2026-11-08，L5 的"能力被路由筛掉"这一类）。
 *
 * 事故原型：小说会话 里用户要「完整列出星轨3 剧情」，模型搜到 wiki 章节页却说
 * **「我没有抓取网页的能力」**——而 `web_fetch` 与 `browser_*` 都实现了、也都成功用过
 * （tools_audit：web_fetch ok 4 次）。查证方向：`ChatEngine` 每轮把全量 schema 交给
 * `ToolRouter.route` 按词法 top-24 窄化，**web/browser 组不在召回地板里**。
 * 被筛掉的代价不是报错，是模型"看不见"→断言自己没有这个能力（比崩溃更难发现）。
 *
 * 这组断言只问一件事：**说到"读网页/查资料"时，读网页的工具还在不在模型眼前。**
 * 它是纯函数（不打网络、不碰 DB），工具描述改坏/地板被删都会红。
 */
class ToolVisibilityTest {

    private val allNames = ToolRegistry.ALL.map { it.name }

    /** 该能力族：读网页的最小可用集（open 之后才有 click/extract 的对象，所以三个一起看） */
    private val pageReading = listOf("web_fetch", "browser_open", "browser_extract")

    private fun kept(query: String): Set<String> =
        ToolRouter.select(allNames, query, emptySet()) ?: allNames.toSet()

    private fun assertPageReadingVisible(query: String) {
        val keep = kept(query)
        val missing = pageReading.filter { it !in keep }
        assertTrue(
            "query=「" + query + "」时读网页工具被路由筛掉：" + missing +
                "（保留 " + keep.size + " 个；模型会因此自称没有抓网页能力）",
            missing.isEmpty()
        )
    }

    @Test
    fun `要读正文或列资料时 读网页工具必须可见`() {
        // 真机原话（真机会话）：搜到 wiki 章节页后模型说"我没有抓取网页的能力"
        assertPageReadingVisible("你先把星轨三游戏原剧情完整列出，包括支线与后星轨书。先检查网络查询是否可用")
        assertPageReadingVisible("把这个网页的正文读完，提取全部章节名")
        assertPageReadingVisible("帮我查一下最新的资料，要读原文，不要只给搜索结果")
    }

    @Test
    fun `重试类轮次不窄化 读网页工具仍在`() {
        // 「重试一下」这类弱信号本来就该原样返回全量（失败契约），这里锁住它别被"优化"掉
        assertPageReadingVisible("重试一下")
    }

    @Test
    fun `要记住事情时 memory_add 必须可见`() {
        // 2026-11-08 用户实报的另一半：AI 把记忆写进了「项目记忆」文件夹（镜像目录）而不是库里。
        // 一半原因是那条路没被堵（已在 ToolExecImpls 加 [MIRROR_READONLY] 拒绝），
        // 另一半就是这里——「记住这件事」的轮次里 memory_add 不能被路由筛掉。
        listOf(
            "记住：以后写小说都用短句，少用形容词",
            "这条要长期记住，存到项目记忆里",
            "帮我记一下我的写作偏好",
        ).forEach { q ->
            assertTrue(
                "query=「$q」时 memory_add 被路由筛掉（模型只能用写文件这条路，等于没进记忆库）",
                "memory_add" in kept(q)
            )
        }
    }
}
