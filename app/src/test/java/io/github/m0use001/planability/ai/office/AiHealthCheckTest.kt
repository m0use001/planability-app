package io.github.m0use001.planability.ai.office

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * AI 能力体检的**判定逻辑**回归（2026-11-05）。
 *
 * 成功路径直接跑真实现：本对象现场生成的最小合法 docx/xlsx/pptx，
 * 走 `OfficeText.read` 真实抽取管线——也就是说，这份测试同时是
 * 「样本生成器 ⇆ 解析器」的往返锁：一边改坏，这里就红。
 */
class AiHealthCheckTest {

    private val tmp = createTempDir("aihealth")

    private fun realParse(f: File): String = OfficeText.read(f)

    @Test
    fun `三种格式的内置样本都能抽到特征词`() {
        val items = AiHealthCheck.checkFormats(tmp) { f -> realParse(f) }
        assertEquals(3, items.size)
        items.forEach {
            assertEquals(
                "${it.name} 应为 OK，实际：${it.detail}",
                AiHealthCheck.Level.OK, it.level
            )
        }
    }

    @Test
    fun `解析器坏了会报 FAIL 而不是静默通过`() {
        // 用一个永远返回空串的假 parse 模拟"解析器坏了"
        val items = AiHealthCheck.checkFormats(tmp) { "" }
        assertTrue(items.all { it.level == AiHealthCheck.Level.FAIL })
        assertTrue(items.all { it.advice.isNotBlank() })
    }

    @Test
    fun `语料审计分类正确`() {
        val dir = File(tmp, "corpus").apply { mkdirs() }
        // 正常：文本文件
        File(dir, "notes.txt").writeText("这是正常可读的笔记内容".repeat(3))
        // 残缺：40KB+ 但只读出几个字（模拟整页图片的 PPT）
        File(dir, "slides.ppt").writeBytes(ByteArray(120_000))
        val files = dir.listFiles()!!.toList()
        val item = AiHealthCheck.checkCorpus(files = files, parse = { f ->
            if (f.name.endsWith(".txt")) "正常内容" +
                "x".repeat(100) else "x"
        })
        assertEquals(AiHealthCheck.Level.WARN, item.level)
        assertTrue(item.detail.contains("slides.ppt"))
        assertTrue(item.detail.contains("notes.txt").not())
    }

    @Test
    fun `语料全好时是 OK`() {
        val dir = File(tmp, "corpus-ok").apply { mkdirs() }
        File(dir, "a.txt").writeText("内容")
        val item = AiHealthCheck.checkCorpus(files = dir.listFiles()!!.toList(), parse = { "读到了很多内容" })
        assertEquals(AiHealthCheck.Level.OK, item.level)
    }

    @Test
    fun `语料为空时是 SKIP`() {
        val item = AiHealthCheck.checkCorpus(files = emptyList(), parse = { "" })
        assertEquals(AiHealthCheck.Level.SKIP, item.level)
    }

    @Test
    fun `搜索探测_三组全通才OK`() {
        val results = AiHealthCheck.SEARCH_PROBES.map { p ->
            p to when (p.label) {
                "官网直达" -> listOf("全国大学英语四、六级考试 (CET) - 中国教育考试网" to "https://cet.neea.edu.cn/")
                "多词内容页" -> listOf("【◆3】《后◆书》剧情全流程" to "https://www.bilibili.com/video/BV1")
                else -> listOf("番茄炒蛋的家常做法" to "https://www.jianshu.com/p/1")
            }
        }
        assertEquals(AiHealthCheck.Level.OK, AiHealthCheck.judgeSearchProbes(results).level)
    }

    @Test
    fun `搜索探测_单词品牌绿但多词内容页红 = WARN`() {
        // 2026-11-08 真机形态：◆3 三条 query 全废的同一天，单词品牌 query 依旧通——
        // 这正是旧体检"永远绿"的原因，新探测必须把这一格判红
        val results = AiHealthCheck.SEARCH_PROBES.map { p ->
            p to if (p.label == "官网直达") listOf("全国大学英语四、六级考试 (CET)" to "https://cet.neea.edu.cn/")
            else listOf("《◆：◆铁道》官方网站" to "https://sr.◆.com/")
        }
        val item = AiHealthCheck.judgeSearchProbes(results)
        assertEquals(AiHealthCheck.Level.WARN, item.level)
        assertTrue(item.detail.contains("多词内容页"))
        assertTrue(item.detail.contains("菜谱正文"))
        assertFalse(item.detail.contains("官网直达"))
    }

    @Test
    fun `搜索探测_全部无结果 = FAIL`() {
        val results = AiHealthCheck.SEARCH_PROBES.map { it to emptyList<Pair<String, String>>() }
        assertEquals(AiHealthCheck.Level.FAIL, AiHealthCheck.judgeSearchProbes(results).level)
    }

    @Test
    fun `搜索降级史_零次OK_有记录WARN`() {
        assertEquals(AiHealthCheck.Level.OK, AiHealthCheck.judgeDegradedHistory(0, 7).level)
        val warn = AiHealthCheck.judgeDegradedHistory(3, 7)
        assertEquals(AiHealthCheck.Level.WARN, warn.level)
        assertTrue(warn.detail.contains("3"))
    }

    @Test
    fun `报告汇总计数正确`() {
        val r = AiHealthCheck.Report(
            listOf(
                AiHealthCheck.Item("a", AiHealthCheck.Level.OK, ""),
                AiHealthCheck.Item("b", AiHealthCheck.Level.WARN, ""),
                AiHealthCheck.Item("c", AiHealthCheck.Level.FAIL, "")
            ),
            0
        )
        assertEquals(1, r.failCount)
        assertEquals(1, r.warnCount)
        assertEquals(AiHealthCheck.Level.FAIL, r.overall)
        assertTrue(r.summary().contains("1 项不可用"))
    }
}
