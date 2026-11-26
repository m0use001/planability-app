package io.github.m0use001.planability.data

import io.github.m0use001.planability.ai.AgentCard
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v72 回归网：内置「写作助手」创作卡的工具开关自愈（2026-11-25 ◆ 复发）。
 *
 * 病灶链：`card.scaffoldTools=false` → ChatEngine `toolsEnabled=false` → 工具清单
 * **根本不传给模型** → 用户明确要求"删记忆/查资料"，模型只能回"没这项能力"，
 * 开完全访问也无用。而写作助手人设本就写着"按要求查资料"——自相矛盾。
 *
 * 三条命脉：
 *  ① 创作预设（creative()）只关排版/组件/图表，**保留工具**；
 *  ② 写作助手种子卡 tools=true，且 postHistory 带"用户明确要求时照办"的例外；
 *  ③ 存量旧卡自愈：只翻 tools + 补例外句，用户自定义人设/文风一字不动（幂等）。
 */
class AssistantWriterToolsGuardTest {

    @Test
    fun `创作预设保留工具只关排版`() {
        val c = AgentCard().creative()
        assertTrue("创作预设不该关工具（关了模型就看不见任何工具）", c.scaffoldTools)
        assertFalse("创作预设仍应关 Markdown 排版", c.scaffoldMarkdown)
        assertFalse("创作预设仍应关组件卡", c.scaffoldComponents)
        assertFalse("创作预设仍应关图表块", c.scaffoldCharts)
    }

    @Test
    fun `写作助手种子卡工具开启且人设不矛盾`() {
        val w = AssistantRepo.builtinAssistants().first { it.key == AssistantRepo.KEY_WRITER }
        val card = AgentCard.parse(w.card)
        assertTrue("写作助手种子卡必须 tools=true（否则模型看不见工具）", card.scaffoldTools)
        assertTrue(
            "postHistory 必须给『用户明确要求时照办』的例外，否则有工具也不敢用",
            card.postHistory.contains("用户明确要求查资料")
        )
        assertFalse("写作助手仍不该带 Markdown 排版（正文干净）", card.scaffoldMarkdown)
    }

    @Test
    fun `存量旧卡自愈：工具翻开且保留自定义人设与其余开关`() {
        val old = JSONObject().apply {
            put("schema", 1)
            put("persona", "用户自定义的人设 XYZ，不要动我")
            put("postHistory", "未经用户明确要求，不调用任何工具（不搜网页、不查记忆、不读写待办日程）；随便写点")
            put("scaffold", JSONObject().apply {
                put("markdown", false); put("components", false)
                put("charts", false); put("tools", false)
            })
        }.toString()
        val fixed = upgradeWriterCard(old)
        assertTrue("旧卡（tools=false）必须被判定需要修补", fixed != null)
        val o = JSONObject(fixed!!)
        assertTrue("scaffold.tools 必须翻成 true", o.getJSONObject("scaffold").getBoolean("tools"))
        assertEquals(
            "用户自定义人设一个字符都不许动",
            "用户自定义的人设 XYZ，不要动我", o.getString("persona")
        )
        assertFalse("Markdown 仍保持关", o.getJSONObject("scaffold").getBoolean("markdown"))
        assertTrue(
            "旧『完全不调工具』那句应被换成带例外的版本",
            o.getString("postHistory").contains("用户明确要求查资料")
        )
    }

    @Test
    fun `已修卡幂等：新种子卡无需再改`() {
        val w = AssistantRepo.builtinAssistants().first { it.key == AssistantRepo.KEY_WRITER }
        assertNull("新版种子卡已 tools=true + 含例外，upgradeWriterCard 应返回 null", upgradeWriterCard(w.card))
    }

    @Test
    fun `坏卡与空卡不崩`() {
        assertNull(upgradeWriterCard(""))
        assertNull(upgradeWriterCard("   "))
        assertNull(upgradeWriterCard("{这不是合法 JSON"))
    }

    @Test
    fun `用户已自己打开工具的卡不被误改`() {
        val ok = JSONObject().apply {
            put("persona", "x")
            put("scaffold", JSONObject().apply { put("tools", true) })
        }.toString()
        assertNull(upgradeWriterCard(ok))
    }
}
