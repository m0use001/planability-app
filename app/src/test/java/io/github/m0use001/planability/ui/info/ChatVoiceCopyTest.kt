package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 聊天界面不许再提「语音」。
 *
 * 聊天输入栏的麦克风按钮已经删掉了，但空态那句「或直接输入 / 语音」还留着，
 * 用户照着提示去找入口找不到（2026-10-12 用户当面指出）。
 *
 * 这类"功能已删、文案/图标残留"的坑单测平时看不见，直到用户照着文案找入口。
 * 所以把"聊天这块界面不许再提语音、也不许留失效的 Mic import"钉在源码上。
 *
 * 注意范围：**只管聊天**。语音输入本体还在抽屉与笔记里（笔记的语音转文字要留着），
 * 所以不能一刀切全仓库搜"语音"——那会把还能用的功能一起判死。
 */
class ChatVoiceCopyTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        assertTrue("找不到 ChatScreen.kt（测试的工作目录不对？）", f.exists())
        f.readText()
    }

    private fun code(): String =
        src.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    @Test
    fun `空态文案不许再提语音`() {
        assertFalse(
            "聊天空态还在说「或直接输入 / 语音」——聊天输入栏已经没有麦克风按钮了，" +
                "用户照着提示找不到入口（2026-10-12 用户指出）",
            code().contains("语音")
        )
        assertTrue(
            "空态那句引导语不见了 —— 用户需要知道这里可以点建议或直接输入",
            code().contains("工作台已就绪")
        )
    }

    @Test
    fun `不许留失效的 Mic import`() {
        assertFalse(
            "ChatScreen 里只剩 import 没有使用的 Icons.Rounded.Mic —— " +
                "麦克风按钮删了 import 忘了清（编译器只给警告，人不看）",
            src.contains("import androidx.compose.material.icons.rounded.Mic")
        )
        assertFalse(
            "聊天里又出现了语音入口（Mic 图标 / 语音弹层）—— 本轮只清文案与残留，功能本体在抽屉和笔记",
            code().contains("VoiceInputSheet")
        )
    }
}