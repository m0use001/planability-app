package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P1-4 守卫：模型**显式传** priority/urgency 绕过 v53「缺省不写 normal」（2026-11-09 审计动态实录）。
 *
 * 事故形状：用户只说「明天下午三点提醒我交作业」，模型自己补 `priority:"high","urgency":"high"`。
 * 工具层无法区分「用户说的」和「模型编的」——但能在确认卡上**指出**这一点：
 * 拿本轮最后一条用户原话比对，没提过重要/紧急就把「用户原话未提及」标到确认卡上。
 */
class FabricationNoteGuardTest {

    private fun source(): String {
        val f = File("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecutor.kt")
        assertTrue("源码不在了（cwd 应为 app 模块目录）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `确认卡必须标注用户原话未提及的优先级字段`() {
        val src = source()
        assertTrue(
            "确认卡要注入 _提示：priority/urgency 是模型传的、而用户原话没提过时必须标出来",
            src.contains("_提示") && src.contains("用户原话没有提到"),
        )
    }

    @Test
    fun `比对基准是本轮最后一条用户消息`() {
        val src = source()
        assertTrue(
            "标注逻辑必须拿会话里最后一条 user 消息做比对（不能凭空猜）",
            src.contains("role == \"user\"") || src.contains("role=\"user\""),
        )
    }
}
