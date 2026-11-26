package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentCardTest {

    @Test
    fun 空卡_回落到旧systemPrompt() {
        val card = AgentCard.parse(null, "旧人格")
        assertEquals("旧人格", card.persona)
        // 脚手架默认全开 = 旧行为
        assertTrue(card.scaffoldMarkdown)
        assertTrue(card.scaffoldComponents)
        assertTrue(card.scaffoldCharts)
        assertTrue(card.scaffoldTools)
        assertNull(card.temperature)
    }

    @Test
    fun 空字符串同样回落() {
        assertEquals("旧人格", AgentCard.parse("", "旧人格").persona)
        assertEquals("旧人格", AgentCard.parse("   ", "旧人格").persona)
    }

    @Test
    fun 坏JSON不炸_回落() {
        val card = AgentCard.parse("{not valid json", "旧人格")
        assertEquals("旧人格", card.persona)
    }

    @Test
    fun 卡内persona空白_回落fallback() {
        val card = AgentCard.parse("""{"schema":1,"persona":""}""", "旧人格")
        assertEquals("旧人格", card.persona)
    }

    @Test
    fun 全字段往返一致() {
        val src = AgentCard(
            persona = "人设正文",
            styleExamples = "示例一\n示例二",
            postHistory = "先问再动",
            depthText = "第三人称限制视角",
            depth = 6,
            depthRole = "user",
            scaffoldMarkdown = false,
            scaffoldComponents = false,
            scaffoldCharts = true,
            scaffoldTools = false,
            temperature = 0.9,
            greeting = "开篇",
        )
        val back = AgentCard.parse(src.toJson(), "fallback")
        assertEquals(src, back)
    }

    @Test
    fun 创作预设_排版类全关但工具保留() {
        // 2026-11-25：工具开关不再随创作预设关掉——关掉=模型根本看不见工具清单，
        // 用户明确要求删记忆/查资料时只能回"没这项能力"（◆ 实锤）。
        val card = AgentCard(persona = "x").creative()
        assertFalse(card.scaffoldMarkdown)
        assertFalse(card.scaffoldComponents)
        assertFalse(card.scaffoldCharts)
        assertTrue("创作预设必须保留工具（否则模型看不见任何工具）", card.scaffoldTools)
        // 其它字段不动
        assertEquals("x", card.persona)
    }

    @Test
    fun 深度夹取_防止荒谬值() {
        val card = AgentCard.parse("""{"persona":"x","depthPrompt":{"text":"t","depth":9999}}""")
        assertEquals(200, card.depth)
        val neg = AgentCard.parse("""{"persona":"x","depthPrompt":{"text":"t","depth":-5}}""")
        assertEquals(0, neg.depth)
    }

    @Test
    fun 渲染展开所有文本字段的宏() {
        val card = AgentCard(
            persona = "{{char}}住在旧城",
            styleExamples = "{{user}}: 早\n{{char}}: 嗯",
            postHistory = "以{{char}}的身份回话",
            depthText = "{{char}}不跳出角色",
            greeting = "{{char}}推开门",
        )
        val r = card.rendered("◆", "阿明")
        assertEquals("◆住在旧城", r.persona)
        assertTrue(r.styleExamples.contains("阿明: 早"))
        assertTrue(r.styleExamples.contains("◆: 嗯"))
        assertEquals("以◆的身份回话", r.postHistory)
        assertEquals("◆不跳出角色", r.depthText)
        assertEquals("◆推开门", r.greeting)
    }

    @Test
    fun 渲染不动脚手架开关() {
        val card = AgentCard(persona = "x").creative()
        val r = card.rendered("◆", "我")
        assertFalse(r.scaffoldMarkdown)
        assertTrue("rendered 不应改变工具开关（创作预设保留工具）", r.scaffoldTools)
        assertEquals("x", r.persona)
    }

    // ---------- v56：角色扮演默认收尾禁令 ----------

    @Test
    fun 角色扮演默认禁令包含三条关键约束() {
        val p = AgentCard.ROLEPLAY_POST_HISTORY
        // ① 模型专属口癖黑名单（DeepSeek 出厂语言习惯最容易让人物变回助手）
        assertTrue("缺 DeepSeek 口癖黑名单", p.contains("这波我认"))
        assertTrue("缺口癖黑名单说明", p.contains("不要使用这些模型口癖"))
        // ② 被动触发锁 / 就事论事原则（治「设定推销员病」）
        assertTrue("缺被动触发锁", p.contains("不要主动推销"))
        // ③ 不自称 AI / 不总结 / 不用 Markdown
        assertTrue("缺不自称 AI", p.contains("不要自称 AI"))
        assertTrue("缺不用 Markdown", p.contains("不要使用 Markdown"))
    }

    @Test
    fun 角色扮演默认禁令刻意不含字数硬锁() {
        // 字数硬锁会把有文风示例的卡压成电报体，只适合「碎片短句流」人格，
        // 默认加会毁文采——必须由用户在编辑页自己决定。
        assertFalse(
            "默认禁令不应含字数硬锁",
            AgentCard.ROLEPLAY_POST_HISTORY.contains("字以内") ||
                AgentCard.ROLEPLAY_POST_HISTORY.contains("不超过") ||
                AgentCard.ROLEPLAY_POST_HISTORY.contains("句以内")
        )
    }
}
