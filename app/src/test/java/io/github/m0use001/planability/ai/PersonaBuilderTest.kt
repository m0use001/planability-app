package io.github.m0use001.planability.ai

import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.MemoryScene
import org.junit.Assert.assertTrue
import org.junit.Test

/** v45 P3：画像 prompt 的结构约束（真跑模型前唯一能自动化的部分）。 */
class PersonaBuilderTest {

    @Test
    fun `画像 system prompt 四段齐全且有防编造约束`() {
        val p = PersonaBuilder.buildPersonaSystemPrompt()
        for (key in listOf("## 基础锚点", "## 兴趣图谱", "## 交互协议", "## 认知内核")) {
            assertTrue("画像少了一段：$key", p.contains(key))
        }
        assertTrue("没写总长上限", p.contains("2000"))
        assertTrue("没禁止编造", p.contains("不推断") && p.contains("不编造"))
    }

    @Test
    fun `画像输入带场景与优先级，死命令显式标出`() {
        val scenes = listOf(MemoryScene(id = 1, name = "C++ 复习", summary = "在准备期末"))
        val memories = listOf(
            MemoryEntity(name = "a", body = "用户要求以后都用中文", priority = -1, type = "feedback"),
            MemoryEntity(name = "b", body = "习惯早上学数学", priority = 80, type = "project"),
        )
        val p = PersonaBuilder.buildPersonaUserPrompt(scenes, memories)
        assertTrue(p.contains("<scenes>") && p.contains("C++ 复习"))
        assertTrue(p.contains("<memories>"))
        assertTrue("死命令要可辨认，否则画像会把硬规则写丢", p.contains("[死命令]"))
        assertTrue(p.contains("[p80]"))
        assertTrue(p.contains("用户要求以后都用中文"))
    }

    @Test
    fun `画像输入为空也有明确占位`() {
        val p = PersonaBuilder.buildPersonaUserPrompt(emptyList(), emptyList())
        assertTrue(p.contains("（无）"))
    }
}
