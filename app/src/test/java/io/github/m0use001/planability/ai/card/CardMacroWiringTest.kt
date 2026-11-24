package io.github.m0use001.planability.ai.card

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v56：角色卡宏展开的**接线**守卫。
 *
 * 光有 [CardMacros] 纯函数不够——它必须真的接在提示词组装路径上。
 * 这批测试钉住三处接线（源码断言，与本仓库既有风格一致）：
 * 1. ContextEngine 装配卡时调用 `rendered(...)` 展开宏；
 * 2. 人设/文风示例/收尾禁令/深度注都过 sanitize（外部卡是不可信内容）；
 * 3. 世界书正文也展开宏。
 *
 * 路径安全（2026-11-15 Mimosa L2 复查提出）：本文件**不接受任何路径参数**——
 * 每个源码文件用固定常量名 + 白名单目录定位，不做相对路径拼接，
 * 因此不存在把仓库外路径带进来的构造。
 */
class CardMacroWiringTest {

    /** 源码子目录（相对仓库根），只读、不可由外部传入。 */
    private val srcDir = "app/src/main/java/io/github/m0use001/planability"

    /** 仓库根：从当前工作目录向上找带 [srcDir] 的那一层。 */
    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, srcDir).isDirectory) {
            dir = dir.parentFile
        }
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    /** 读仓库内某个**白名单**源码文件。名字必须是本文件里列出的常量，不接受外部输入。 */
    private fun readSource(fileName: String): String {
        require(fileName in ALLOWED) { "不在白名单里的文件：$fileName" }
        val f = File(File(repoRoot, srcDir), fileName).canonicalFile
        val allowedDir = File(repoRoot, srcDir).canonicalFile
        assertTrue("路径越界：$fileName", f.path.startsWith(allowedDir.path + File.separator))
        assertTrue("源码不在了：$fileName", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private fun contextEngine() = readSource("ai/ContextEngine.kt")
    private fun chatEngine() = readSource("ai/ChatEngine.kt")

    @Test
    fun `ContextEngine 装配卡时展开宏`() {
        val ctx = contextEngine()
        assertTrue(
            "卡装配处没调 rendered(...)——宏不会展开，模型会吐字面占位符",
            ctx.contains(".rendered(")
        )
        assertTrue(
            "渲染没传角色名",
            ctx.contains("charName = assistant?.name.orEmpty()")
        )
        assertTrue(
            "渲染没传用户称呼（应复用 accountName）",
            ctx.contains("userName = runCatching")
        )
    }

    @Test
    fun `人设与文风示例过 sanitize`() {
        val ctx = contextEngine()
        assertTrue(
            "人设没过 sanitize（角色卡是外部不可信内容）",
            ctx.contains("persona = sanitize(c.persona")
        )
        assertTrue(
            "文风示例没过 sanitize",
            ctx.contains("styleExamples = sanitize(c.styleExamples")
        )
        assertTrue(
            "收尾禁令没过 sanitize",
            ctx.contains("postHistory = sanitize(c.postHistory")
        )
    }

    @Test
    fun `世界书正文展开宏并清洗`() {
        val ctx = contextEngine()
        assertTrue(
            "世界书正文没展开宏",
            ctx.contains("CardMacros.expand(content, loreChar, loreUser)")
        )
        assertTrue(
            "世界书正文没过 sanitize",
            ctx.contains("fun loreBody(content: String)")
        )
    }

    @Test
    fun `ChatEngine 新会话落开场白`() {
        val ce = chatEngine()
        // v56：seedGreeting 已随 728d514b 落地（ChatEngine.kt 的开场白接线），
        // 这里从「条件断言」改回**必跑**——接线不在了就要红。
        assertTrue(
            "ChatEngine 里没有 seedGreeting——新会话不会自动落开场白",
            ce.contains("seedGreeting")
        )
        assertTrue(
            "开场白函数没读卡",
            ce.contains("AgentCard.parse(assistant.card, assistant.systemPrompt)")
        )
        assertTrue(
            "开场白没展开宏",
            ce.contains("CardMacros")
        )
    }

    private companion object {
        /** 允许读取的源码文件白名单（相对 [srcDir]）。 */
        val ALLOWED = setOf("ai/ContextEngine.kt", "ai/ChatEngine.kt")
    }
}
