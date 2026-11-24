package io.github.m0use001.planability.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v54 思考档位五档（关/自适应/low/high/max）的契约测试。
 *
 * 2026-11-06 对 DeepSeek 官方 API 真探针实测的结论钉进代码：
 * - `thinking:{"type":"disabled"}` → 完全不思考（reasoning_content 空、completion_tokens=1）
 * - `reasoning_effort: low/high/max` → 均被接受（/models 声明 supported_levels，默认 high）
 * - 未知端点不能乱塞 `reasoning_effort`（未知字段有 400 风险）→ 退化为 thinking enabled
 */
class ThinkingLevelsTest {

    // ---------- 档位集合与展示 ----------

    @Test
    fun `五档集合顺序与唯一性`() {
        assertEquals(listOf("off", "", "low", "high", "max"), ThinkingLevels.ALL)
        assertEquals(ThinkingLevels.ALL.size, ThinkingLevels.ALL.distinct().size)
    }

    @Test
    fun `展示名映射`() {
        assertEquals("关", ThinkingLevels.label("off"))
        assertEquals("自适应", ThinkingLevels.label(""))
        assertEquals("low", ThinkingLevels.label("low"))
        assertEquals("high", ThinkingLevels.label("high"))
        assertEquals("max", ThinkingLevels.label("max"))
        // 旧三档的 "on" 只在展示层兼容
        assertEquals("开", ThinkingLevels.label("on"))
    }

    // ---------- 滑条落位 ----------

    @Test
    fun `滑条落位与旧on兼容`() {
        assertEquals(0, ThinkingLevels.sliderIndex("off"))
        assertEquals(1, ThinkingLevels.sliderIndex(""))
        assertEquals(2, ThinkingLevels.sliderIndex("low"))
        assertEquals(3, ThinkingLevels.sliderIndex("high"))
        assertEquals(4, ThinkingLevels.sliderIndex("max"))
        // 旧 "on"（默认力度）落 high
        assertEquals(3, ThinkingLevels.sliderIndex("on"))
        // 未知值不越界
        assertEquals(0, ThinkingLevels.sliderIndex("bogus"))
    }

    // ---------- 请求体翻译（openai 兼容分支） ----------

    @Test
    fun `DeepSeek 力度档发 reasoning_effort`() {
        for (e in listOf("low", "high", "max")) {
            val b = JSONObject()
            applyOpenAiThinking(b, e, "https://api.deepseek.com")
            assertEquals(e, b.optString("reasoning_effort"))
            assertFalse("不应同时塞 thinking 字段", b.has("thinking"))
        }
    }

    @Test
    fun `DeepSeek 关档发 thinking disabled 自适应不发参数`() {
        val off = JSONObject()
        applyOpenAiThinking(off, "off", "https://api.deepseek.com")
        assertEquals("disabled", off.optJSONObject("thinking")?.optString("type"))
        assertFalse(off.has("reasoning_effort"))

        val auto = JSONObject()
        applyOpenAiThinking(auto, "", "https://api.deepseek.com")
        assertFalse("自适应=不发任何思考参数", auto.has("thinking") || auto.has("reasoning_effort"))
    }

    @Test
    fun `非 effort 端点力度档退化为 thinking enabled`() {
        for (e in listOf("low", "high", "max")) {
            val b = JSONObject()
            applyOpenAiThinking(b, e, "https://open.bigmodel.cn/api/paas/v4")
            assertEquals("enabled", b.optJSONObject("thinking")?.optString("type"))
            assertFalse("未知字段不塞（GLM 等可能 400）", b.has("reasoning_effort"))
        }
    }

    @Test
    fun `旧on在任意端点都发 thinking enabled`() {
        val b = JSONObject()
        applyOpenAiThinking(b, "on", "https://api.deepseek.com")
        assertEquals("enabled", b.optJSONObject("thinking")?.optString("type"))
    }

    @Test
    fun `effort 判定只认 DeepSeek 官方域名`() {
        assertTrue(ThinkingLevels.supportsEffort("https://api.deepseek.com"))
        assertTrue(ThinkingLevels.supportsEffort("https://API.DeepSeek.com/v1"))
        assertFalse(ThinkingLevels.supportsEffort("https://api.deepinfra.com/v1/openai"))
        assertFalse(ThinkingLevels.supportsEffort("https://open.bigmodel.cn/api/paas/v4"))
    }

    // ---------- Anthropic 分支 ----------

    @Test
    fun `Anthropic 力度档退化为扩展思考 关与自适应不发`() {
        val on = JSONObject()
        applyAnthropicThinking(on, "on")
        assertEquals("extended", on.optJSONObject("thinking")?.optString("type"))

        for (e in listOf("low", "high", "max")) {
            val b = JSONObject()
            applyAnthropicThinking(b, e)
            assertEquals("力度档在 Anthropic 退化为扩展思考", "extended", b.optJSONObject("thinking")?.optString("type"))
        }

        val off = JSONObject()
        applyAnthropicThinking(off, "off")
        val auto = JSONObject()
        applyAnthropicThinking(auto, "")
        assertNull("off/自适应都不该发 thinking", off.optJSONObject("thinking"))
        assertNull(auto.optJSONObject("thinking"))
    }
}
