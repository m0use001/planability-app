package io.github.m0use001.planability.ai.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56 角色卡宏展开。
 *
 * 背景（2026-11-15 用户：「我想要 AI 按照某个二次元人物的人设说话」）：
 * 从酒馆社区导入的卡，`mes_example` / `first_mes` / 世界书正文里全是
 * `{{char}}` / `{{user}}` / `<START>`。不展开的话模型把占位符当字面文本，
 * 回复里直接吐 `{{char}}: …`，文风示例也失效。
 */
class CardMacrosTest {

    // ---------- 基本替换 ----------

    @Test
    fun `char 与 user 宏被替换成实际名字`() {
        val src = "{{char}} 对 {{user}} 说：早上好。"
        assertEquals("◆ 对 我 说：早上好。", CardMacros.expand(src, "◆", "我"))
    }

    @Test
    fun `user 宏用自定义称呼`() {
        assertEquals(
            "阿明，早安",
            CardMacros.expand("{{user}}，早安", "◆", "阿明")
        )
    }

    @Test
    fun `宏名忽略大小写与内部空格`() {
        assertEquals("◆", CardMacros.expand("{{ CHAR }}", "◆"))
        assertEquals("我", CardMacros.expand("{{User}}", "◆"))
    }

    @Test
    fun `START 分隔符展开成可见分隔线`() {
        val src = "<START>\n{{user}}: 你回来了？\n{{char}}: 嗯。"
        val out = CardMacros.expand(src, "◆", "我")
        assertTrue("应含分隔线", out.contains(CardMacros.START_MARK))
        assertFalse("不应残留 <START>", out.contains("<START>"))
        assertTrue(out.contains("◆: 嗯。"))
    }

    // ---------- 边界 ----------

    @Test
    fun `空文本原样返回`() {
        assertEquals("", CardMacros.expand("", "◆"))
    }

    @Test
    fun `没有宏时文本一字不改`() {
        val src = "你是◆，住在三峡口的旧城。"
        assertEquals(src, CardMacros.expand(src, "◆"))
    }

    @Test
    fun `空名字回落到默认称呼而不是空串`() {
        // 空名字会让「{{char}}：」变成「：」，很难看
        assertTrue(CardMacros.expand("{{char}}：你好", "").startsWith(CardMacros.DEFAULT_CHAR))
        assertTrue(CardMacros.expand("{{user}}：你好", "◆", "  ").contains(CardMacros.DEFAULT_USER))
    }

    @Test
    fun `未识别的宏原样保留而不是吞掉`() {
        // 用户要能看出卡里写了什么没生效，而不是静默变空
        val out = CardMacros.expand("{{getvar::x}} 和 {{custom}}", "◆")
        assertTrue(out.contains("{{getvar::x}}"))
        assertTrue(out.contains("{{custom}}"))
    }

    // ---------- 动作宏识别（编辑页要提示「这条不生效」） ----------

    @Test
    fun `动作宏被识别`() {
        assertTrue(CardMacros.hasActionMacros("{{random::a::b}}"))
        assertTrue(CardMacros.hasActionMacros("{{roll:1d6}}"))
        assertTrue(CardMacros.hasActionMacros("{{getvar::name}}"))
    }

    @Test
    fun `纯文本宏不算动作宏`() {
        assertFalse(CardMacros.hasActionMacros("{{char}}"))
        assertFalse(CardMacros.hasActionMacros("{{user}}"))
        assertFalse(CardMacros.hasActionMacros("没有宏的正文"))
    }

    @Test
    fun `宏名可列出用于提示`() {
        val names = CardMacros.macroNames("{{char}} {{user}} {{random::x}} {{char}}")
        assertEquals(listOf("char", "user", "random"), names)
    }
}
