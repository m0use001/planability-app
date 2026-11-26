package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 权限档位的闸门（2026-10-29 审查后新增）。
 *
 * ## 为什么要有这组断言
 *
 * 同一天的审查发现两处"名字承诺、代码不认"：
 * ①「计划模式」凭 `WRITE_TOOLS` 黑名单拦写操作，而 `work_plan_start` / `automation_pin` /
 *    `automation_run` 三个会写数据的工具**不在名单里** —— 于是"不改动任何数据"的档位能建文件、
 *    改库、跑自动化。漏登记不报错、不崩溃，只有真的点一次才知道。
 * ②「自动执行」的描述只说日程/待办/笔记，实际连 `fs_write_text`（改整机文件）、
 *    `◆`（操控别的应用）、`mcp_connect`（改系统配置）都静默放行。
 *
 * 所以这里把两条纪律变成会失败的测试：
 * - 凡是 `risk != AUTO` 的工具，必须登记进 [PermPolicy.WRITE_TOOLS]；
 * - [PermPolicy.AUTO_TOOLS] 只能是"业务数据"类，不能夹带文件面/设备操控/系统配置。
 */
class PermCoverageTest {

    @Test
    fun everyNonAutoToolIsDeclaredAsWrite() {
        val missing = ToolRegistry.ALL
            .filter { it.risk != Risk.AUTO }
            .map { it.name }
            .filter { it !in PermPolicy.WRITE_TOOLS }
        assertTrue(
            "这些工具会改数据但没登记进 WRITE_TOOLS（计划模式/旧黑名单会漏拦）：$missing",
            missing.isEmpty()
        )
    }

    @Test
    fun autoToolsAreDeclaredWriteTools() {
        val bad = PermPolicy.AUTO_TOOLS - PermPolicy.WRITE_TOOLS
        assertTrue("AUTO_TOOLS 里有不是写工具的名字（配置不一致）：$bad", bad.isEmpty())
    }

    @Test
    fun dangerToolsAreNeverAutoAllowed() {
        val bad = ToolRegistry.ALL
            .filter { it.risk == Risk.DANGER }
            .map { it.name }
            .filter { it in PermPolicy.AUTO_TOOLS }
        assertTrue("删除类（DANGER）工具不许在「自动执行」里免确认：$bad", bad.isEmpty())
    }

    @Test
    fun autoTierStaysBusinessOnly() {
        // 自动档的边界：文件面、设备操控、系统配置一律不得自动放行。
        // 以后想把某类加进自动档，先过这条——它逼你想清楚"这个名字还配不配"。
        val forbiddenPrefixes = listOf(
            "fs_", "◆", "phone_swipe", "phone_back", "phone_home", "phone_open_app", "◆",
            "automation_", "mcp_", "plugin_", "capability_", "model_switch", "◆",
            "browser_click", "browser_fill", "work_plan_start"
        )
        val leaked = PermPolicy.AUTO_TOOLS.filter { t -> forbiddenPrefixes.any { t == it || t.startsWith(it) } }
        assertTrue(
            "这些工具属于「文件面/设备操控/系统配置」，不该在「自动执行」里免确认：$leaked",
            leaked.isEmpty()
        )
    }

    @Test
    fun readOnlyDeniesEverythingExceptPureReads() {
        ToolRegistry.ALL.forEach { d ->
            val shouldDeny = d.risk != Risk.AUTO
            assertEquals(
                "计划模式的判据应恒等于「不是纯查询」（工具 ${d.name}，risk=${d.risk}）",
                shouldDeny, PermPolicy.readOnlyDenied(d.risk)
            )
        }
    }

    @Test
    fun everyTierRoundTripsThroughPermJson() {
        PermPolicy.TIERS.forEach { tier ->
            val json = PermPolicy.permJsonFor(tier.key)
            assertEquals("档位 ${tier.key} 存下来再读回来变了样：$json", tier.key, PermPolicy.modeForPermJson(json))
        }
    }

    @Test
    fun legacyPermJsonWithoutModeIsNarrowed() {
        // 旧「自动」会话：allow 里含 fs_*/phone_* 等（当时的口径）。
        // 没有 mode 字段时按老口径判成 auto，但实际放行集合换成新的 AUTO_TOOLS —— 必须收窄。
        val legacyAuto = """
            {"allow":["schedule_create","fs_write_text","◆","mcp_connect"],"deny":[]}
        """.trimIndent()
        val mode = PermPolicy.modeForPermJson(legacyAuto)
        assertEquals("auto", mode)
        val effective = setOf("schedule_create", "fs_write_text", "◆", "mcp_connect") intersect
            PermPolicy.allowedSet(mode)
        assertEquals(
            "旧自动会话里越权的那部分必须被收窄掉：$effective",
            setOf("schedule_create"), effective
        )
    }

    @Test
    fun writeToolsAllExistInRegistry() {
        val names = ToolRegistry.ALL.map { it.name }.toSet()
        val ghosts = PermPolicy.WRITE_TOOLS - names
        assertTrue("WRITE_TOOLS 里列了不存在的工具（拼错名字等于没拦）：$ghosts", ghosts.isEmpty())
    }
}
