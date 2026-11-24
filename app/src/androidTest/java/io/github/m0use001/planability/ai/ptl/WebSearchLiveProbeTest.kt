package io.github.m0use001.planability.ai.ptl

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-14：Tavily 主源的**设备端活体探针**（模拟器跑，禁真机）。
 *
 * 为什么不用单元测试盖这一段：真值在「真机网络 + 真 key + 真 DNS/TLS」上，
 * 单测只能验解析（见 WebSearchTavilyTest）。本探针用 `am instrument -e tavilyKey <key>`
 * 把 key 传进来，**key 绝不进仓库**：
 *
 *     adb -s emulator-5554 shell am instrument -w \
 *       -e tavilyKey tvly-xxx \
 *       -e class io.github.m0use001.planability.ai.ptl.WebSearchLiveProbeTest \
 *       io.github.m0use001.planability.test/androidx.test.runner.AndroidJUnitRunner
 *
 * 传了 key 才跑断言；没传直接 return（CI/无关跑批不会因为这个挂）。
 */
@RunWith(AndroidJUnit4::class)
class WebSearchLiveProbeTest {

    private fun arg(name: String): String? =
        InstrumentationRegistry.getArguments().getString(name)?.trim()?.takeIf { it.isNotEmpty() }

    @Test
    fun chineseQueryHitsTavilyAsPrimarySource() {
        val key = arg("tavilyKey") ?: return
        // 写进设置（库里走 SecretBox 密文），让 searchWithSource 真的按产品路径走
        io.github.m0use001.planability.data.Graph.settings.update { it.copy(searchTavilyKey = key) }

        val outcome = WebSearch.searchWithSource("期末考试 高数 复习方法", 5)
        assertEquals("没走 Tavily 主源（source=${outcome.source}，结果=${outcome.hits.size} 条）",
            "tavily", outcome.source)
        assertTrue("Tavily 返回不足 2 条：${outcome.hits.size}", outcome.hits.size >= 2)
        assertTrue("结果里没有中文相关条目：${outcome.hits.map { it.title }}",
            outcome.hits.any { h -> h.title.any { it.code in 0x4E00..0x9FFF } })
        // 摘要不是空的（Tavily 的 content 直接当摘要用，空串意味着模型只拿到标题）
        assertTrue("摘要是空的", outcome.hits.any { it.snippet.isNotBlank() })

        // 英文技术 query 同样走 Tavily
        val en = WebSearch.searchWithSource("Kotlin Compose ModalBottomSheet", 5)
        assertEquals("英文 query 没走 Tavily", "tavily", en.source)
        assertTrue("英文 query 结果不足 2 条", en.hits.size >= 2)
    }
}
