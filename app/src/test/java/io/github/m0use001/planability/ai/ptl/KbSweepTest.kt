package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.rag.KbSweep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * kb_ask「三库补扫」回归（2026-11-11 用户：「AI 会查错地方」）。
 *
 * 实录（session 1293，消息 6160-6173）：用户早让 AI 查过崩3剧情并落了盘——
 * ◆/#135 + 项目文件/◆三世界观设定与时间线.md——问细节时 kb_ask 只搜
 * **笔记**，返回 0% 覆盖，模型就报「本地笔记里没有」转去搜网页；最后还是靠
 * file_library_list 才翻到那份文档。修：笔记证据不足时补扫记忆与项目文件。
 * 这批钉住补扫的纯函数行为与三处范围口径。
 */
class KbSweepTest {

    // ---------- 纯函数：KbSweep ----------

    @Test
    fun `firstHitTerm 命中首个词元且大小写不敏感，无命中返回 null`() {
        assertEquals("琪亚娜", KbSweep.firstHitTerm("琪亚娜在千羽学园入学", listOf("琪亚娜", "千羽学园")))
        assertEquals("K423", KbSweep.firstHitTerm("她代号k423。", listOf("K423")))
        assertNull(KbSweep.firstHitTerm("完全无关的内容", listOf("琪亚娜")))
        assertNull(KbSweep.firstHitTerm("", listOf("琪亚娜")))
        // 单字符词元不算（噪声太大）
        assertNull(KbSweep.firstHitTerm("◆", listOf("崩")))
    }

    @Test
    fun `snippetAround 以命中点为中心带省略号，未命中退前缀`() {
        val content = "前".repeat(200) + "琪亚娜" + "后".repeat(200)
        val s = KbSweep.snippetAround(content, "琪亚娜")
        assertTrue("应含命中词", s.contains("琪亚娜"))
        assertTrue("两侧截断要带省略号", s.startsWith("…") && s.endsWith("…"))
        assertTrue("节选要有界（前后各 160 + 词元 + 省略号）", s.length <= 160 * 2 + 3 + 2)
        val head = KbSweep.snippetAround("短文", "琪亚娜")
        assertEquals("未命中退全文前缀", "短文", head)
    }

    // ---------- 范围口径：只扫研究沉淀子树 ----------

    @Test
    fun `补扫只认项目文件与项目记忆子树，对话镜像不算证据`() {
        assertTrue(LifeTools.kbSweepScope("项目文件/◆三世界观设定与时间线.md"))
        assertTrue(LifeTools.kbSweepScope("项目记忆/设定卡.md"))
        assertTrue(LifeTools.kbSweepScope("项目文件/主线/2026.md"))
        // 对话镜像：任何问题都会在里面"命中"用户刚问的话，是纯噪声
        assertFalse(LifeTools.kbSweepScope("当前会话/新对话__1293/对话.md"))
        assertFalse(LifeTools.kbSweepScope("已归档会话/旧会话/对话.md"))
        assertFalse(LifeTools.kbSweepScope("对话.md"))
    }

    // ---------- 结构：三库口径进实现与提示词 ----------

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `kb_ask 笔记未命中时补扫记忆与文件库，全空才下「本地没有」`() {
        val tools = source("app/src/main/java/io/github/m0use001/planability/ai/ptl/LifeTools.kt")
        val ask = tools.substringAfter("private suspend fun kbAsk(")
        assertTrue("kb_ask 没接 sessionId（拿不到项目作用域）", ask.contains("sessionId: Long"))
        assertTrue("没补扫记忆", ask.contains("Graph.memories.recall"))
        assertTrue("没补扫文件库", ask.contains("kbFileSweep"))
        assertTrue(
            "全空结论没改口（仍只说笔记）",
            ask.contains("笔记、记忆、项目文件三处都没有")
        )
        // 笔记有证据时不做补扫（省一次查询；证据够用）
        assertTrue(
            "补扫应只在笔记未命中时进行",
            ask.contains("if (r.evidence.isEmpty())")
        )
    }

    @Test
    fun `检索硬规则同步改口：kb_ask 三库齐扫，没命中才是真的没有`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val idx = ctx.indexOf("检索策略：用户问到自己的笔记")
        assertTrue("检索硬规则被删了", idx >= 0)
        val after = ctx.substring(idx)
        assertTrue("没说 kb_ask 三库齐扫", after.contains("笔记、记忆、项目文件"))
        assertTrue("没给转外部的告知要求", after.contains("先向用户说明"))
    }
}
