package io.github.m0use001.planability.ai

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 容错 JSON 解析（模型输出专用）单测。
 * 样本取自 2026-11-05 真机库「小说写作助手」会话的原始坏 JSON，
 * 以及历史实测坏法（尾逗号/全角/单引号/截断/括号反写/顶层垃圾）。
 */
class JsonRepairTest {

    // ---------- ① 未转义内层引号（1255 实测：qa 卡整块回落成代码框的根因） ----------

    @Test
    fun `unescaped inner quotes in option value`() {
        val broken = """{"questions": [{"q": "这次改稿，最终想拿去干嘛？", "options": ["投稿 / 平台连载（要读者留存）", "自己满意、能读完就行", "给朋友看、要"好看"", "练笔，重点是拆解自己写法"]}]}"""
        val o = JsonRepair.parseObject(broken)
        assertNotNull("1255 实测坏样本必须能解析", o)
        val opts = o!!.optJSONArray("questions")!!.optJSONObject(0)!!.optJSONArray("options")!!
        assertEquals(4, opts.length())
        assertEquals("给朋友看、要\"好看\"", opts.optString(2))
    }

    @Test
    fun `value string containing fullwidth punctuation is untouched`() {
        // 全角标点落在字符串内 = 内容，绝不能被结构化改写
        val s = """{"note": "因为你节奏不定，不排固定日程；最多设一条低频钩子。"}"""
        assertEquals("因为你节奏不定，不排固定日程；最多设一条低频钩子。", JsonRepair.parseObject(s)!!.optString("note"))
    }

    // ---------- ② 全角定界符/结构位标点 ----------

    @Test
    fun `fullwidth delimiters and structural punctuation`() {
        val s = "{\"title\"：\"计划”，“note”：“备注”}" // 模拟全角冒号/逗号/引号混用
        val o = JsonRepair.parseObject(s)
        assertNotNull(o)
        assertEquals("计划", o!!.optString("title"))
        assertEquals("备注", o.optString("note"))
    }

    // ---------- ③ 单引号字符串 ----------

    @Test
    fun `single quoted strings`() {
        val s = "{'a': 'x', 'b': \"y\"}"
        val o = JsonRepair.parseObject(s)!!
        assertEquals("x", o.optString("a"))
        assertEquals("y", o.optString("b"))
    }

    // ---------- ④ 尾逗号 ----------

    @Test
    fun `trailing comma dropped`() {
        val s = """{"a": 1, "b": [1, 2, 3,]}"""
        val o = JsonRepair.parseObject(s)!!
        assertEquals(3, o.optJSONArray("b")!!.length())
    }

    // ---------- ⑤ 括号反写 / ⑥ 截断缺闭合 ----------

    @Test
    fun `swapped closing bracket repaired`() {
        val swapped = JsonRepair.parseObject("""{"a": [1, 2]]""")
        assertNotNull(swapped) // 尾部垃圾被截断
        assertEquals(2, swapped!!.optJSONArray("a")!!.length())
    }

    @Test
    fun `truncated json closers are filled`() {
        // 流式半截 / 模型漏收尾：字符串断在中间 + 两层括号没闭合
        val o = JsonRepair.parseObject("""{"questions": [{"q": "卡在哪一步？", "options": ["没有故事", "写不动正""")
        assertNotNull(o)
        assertEquals("卡在哪一步？", o!!.optJSONArray("questions")!!.optJSONObject(0)!!.optString("q"))
    }

    // ---------- ⑦ 顶层之后的垃圾 ----------

    @Test
    fun `garbage after top level truncated`() {
        val o = JsonRepair.parseObject("""{"a": 1} 一些多余的正文 }]]""")
        assertNotNull(o)
        assertEquals(1, o!!.optInt("a"))
    }

    // ---------- 非 JSON 不硬造 ----------

    @Test
    fun `non json returns null`() {
        assertNull(JsonRepair.parseObject("这不是 JSON"))
        assertNull(JsonRepair.parseObject(""))
    }

    // ---------- 严格 JSON 零风险（原样通过） ----------

    @Test
    fun `strict json passes through untouched`() {
        val s = """{"q": "含\"引号\"与,逗号", "n": 1.5, "ok": true, "arr": []}"""
        val o = JsonRepair.parseObject(s)!!
        assertEquals("含\"引号\"与,逗号", o.optString("q"))
        assertTrue(o.optBoolean("ok"))
    }

    // ---------- 1255 完整 plan 样本（含步骤名自带序号问题的原始数据） ----------

    @Test
    fun `real plan fence parses`() {
        // 原文是一行（inline 闭合写法），此处按原样保留空格
        val raw = "{\"title\": \"小说改稿工作流 v0.1\",\"goal\":\"在不重写整部书的前提下，把你指定的稿子改到你自己认可的状态，且每次开工不用重新讲一遍背景\",\"steps\":[{\"name\":\"1 锁定范围与验收标准\",\"detail\":\"确认改哪一份、改到什么叫完成\"},{\"name\":\"2 建作品档案\",\"detail\":\"抽出世界观设定、人物表、主线时间线\"}],\"note\":\"因为你节奏不定，不排固定日程；最多设一条低频钩子（如每周一次「推进一章」），由你决定要不要。\"}"
        val o = JsonRepair.parseObject(raw)
        assertNotNull(o)
        assertEquals(2, o!!.optJSONArray("steps")!!.length())
        // 步骤名原样带序号（剥序号是 PlanCard 渲染层职责，见 ChatComponents）
        assertEquals("1 锁定范围与验收标准", o.optJSONArray("steps")!!.optJSONObject(0)!!.optString("name"))
    }

    @Test
    fun `JSONObject remains usable for strict path`() {
        // 防退化哨兵：严格路径行为不变（JSONObject 直接构造）
        assertEquals("x", JSONObject("""{"a":"x"}""").optString("a"))
    }
}
