package io.github.m0use001.planability.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 用户实报（连续多次，且是本人复读第二遍的强调）：
 *
 * > 「每次我让 AI 生成/修改计划，AI 都以为是搞 .md 计划文件，
 * > 都忘记了工作流里面的那个计划，我要的是后者。」
 *
 * 根因（不是模型笨，是提示词把话说反了）：
 *
 * - App 里「计划」有三种含义：① 计划页的日程/待办数据库 ② **工作流面板里的那份计划**
 *   （由 ```plan 组件卡或正文 `- [ ]` 勾选清单解析而来，最新一条覆盖旧一条）
 *   ③ 项目文件里的 .md 计划文档。用户要的是 ②。
 * - 提示词只教了 plan 卡的 JSON 写法，**一句都没说它就是工作流面板里那份计划**；
 * - 工具描述里反而反复出现「项目文件/计划/步骤.md」「计划/2026」这类示例路径——
 *   工具描述是模型最照抄的模板，等于天天在教它「计划 = 项目文件里的 md」。
 *
 * 这里钉源码：模型听不听提示词无法在 JVM 里稳定复现，但「提示词里有没有把话说清楚」
 * 是可钉的，而且这是防线的第一道。
 */
class PlanWorkflowSemanticsGuardTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        // M4：rel 以 shared/ 开头 = 仓库根相对（ToolDefs 等真实现已迁 :shared）
        val f = if (rel.startsWith("shared/")) File(requireNotNull(dir) { "找不到仓库根" }, rel)
        else File(requireNotNull(dir) { "找不到仓库根" }, "app/src/main/java/io/github/m0use001/planability/$rel")
        assertTrue("$rel 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private val contextEngine by lazy { source("ai/ContextEngine.kt") }
    private val toolDefs by lazy { source("shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt") }

    @Test
    fun `提示词必须点明计划就是工作流面板里那份`() {
        val s = contextEngine
        assertTrue(
            "没有【计划】规则段（模型无从知道用户说的是工作流面板里的计划）",
            s.contains("【计划：用户说的是「工作流面板里那份计划」】")
        )
        assertTrue(
            "规则里没说清「工作流面板」",
            s.contains("工作流面板")
        )
        assertTrue(
            "规则里没说清两种载体（plan 卡 / - [ ] 勾选清单）",
            s.contains("- [ ]") && s.contains("plan")
        )
        assertTrue(
            "规则里没说清「最新一条覆盖旧一条」（模型会以为要增量改）",
            s.contains("最新一条覆盖旧的一条")
        )
    }

    @Test
    fun `提示词必须禁止把计划写成md文件`() {
        val s = contextEngine
        assertTrue(
            "没有明令禁止把计划写进 .md",
            s.contains("严禁") && s.contains(".md 文件")
        )
        assertTrue(
            "没有点名要禁的写法（项目文件/计划/xxx.md）",
            s.contains("项目文件/计划/xxx.md")
        )
        assertTrue(
            "没有留归档口子（用户明确说导出时才落盘）",
            s.contains("导出/归档成文档") || s.contains("归档成文档")
        )
    }

    @Test
    fun `计划规则要无条件下发`() {
        val s = contextEngine
        val idx = s.indexOf("【计划：用户说的是「工作流面板里那份计划」】")
        assertTrue("找不到【计划】规则段", idx >= 0)
        // 不能挂在 card.scaffoldComponents / scaffoldMarkdown 的 if 里——
        // 创作卡关掉组件协议时，用户照样会说「给我排个计划」。
        // 判据用缩进：函数体内的无条件段是 8 空格，if 块内是 12 空格。
        val lineStart = s.lastIndexOf('\n', idx).let { if (it < 0) 0 else it + 1 }
        val line = s.substring(lineStart, s.indexOf('\n', idx))
        assertTrue(
            "【计划】规则没放在函数体顶层（缩进 $line）——可能被关进了 scaffoldComponents/scaffoldMarkdown 的 if 里",
            line.startsWith("        sb.append") && !line.startsWith("            ")
        )
        // 再确认它不在两个 scaffold if 的正中间：规则段之前必须已经出现过
        // scaffoldMarkdown 块的收尾（组件协议段先于它）
        val scaffoldIdx = s.indexOf("if (card.scaffoldMarkdown)")
        assertTrue("找不到 scaffoldMarkdown 分支", scaffoldIdx >= 0)
        assertTrue("【计划】规则应在排版段之后", idx > scaffoldIdx)
    }

    @Test
    fun `工具描述不许再用计划目录做示例`() {
        // 只看**真正下发给模型的描述与参数说明**——注释里复述旧写法不算。
        val s = toolDefs.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
        // file_library_write 的示例路径原来全是「项目文件/计划/步骤.md」——
        // 模型照抄模板 = 每次都把计划落成 md。
        assertFalse(
            "file_library_write 的示例里还有「项目文件/计划/...」",
            s.contains("项目文件/计划/步骤.md")
        )
        assertFalse(
            "file_library_mkdir 的示例里还有「计划/2026」",
            s.contains("计划/2026")
        )
        assertTrue(
            "file_library_write 没写明「计划不要用本工具落盘」",
            s.contains("不要用本工具落盘")
        )
    }

    @Test
    fun `文件卡路径必须来自工具回执`() {
        val s = contextEngine
        assertTrue(
            "file 卡规则没要求路径逐字复制回执（模型会自己编一个 exports/xxx.md）",
            s.contains("逐字复制")
        )
        assertTrue(
            "没禁止「没导出就输出 file 卡」",
            s.contains("不要输出 file 卡")
        )
    }
}
