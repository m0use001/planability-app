package io.github.m0use001.planability.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 权限档位**纯策略**行为（M2 批 3 迁移时新增）。
 *
 * 原 app 侧只有 PermCoverageTest，它是「读 ToolRegistry 断言覆盖」的源码守卫（依赖 M4 的
 * 工具注册表，留在 app）。这里补的是不依赖任何注册表的纯策略断言：档位 → 放行集合、
 * permJson 往返、只读闸语义。
 */
class PermPolicyTest {

    @Test
    fun `四档齐全且 key 稳定`() {
        assertEquals(setOf("ro", "confirm", "auto", "full"), PermPolicy.TIER_KEYS)
        assertEquals(listOf("ro", "confirm", "auto", "full"), PermPolicy.TIERS.map { it.key })
    }

    @Test
    fun `允许集合按档位`() {
        assertEquals(emptySet(), PermPolicy.allowedSet("ro"), "计划模式不看 allow，走白名单闸")
        assertEquals(emptySet(), PermPolicy.allowedSet("confirm"), "确认档全部走确认卡")
        assertEquals(PermPolicy.AUTO_TOOLS, PermPolicy.allowedSet("auto"))
        assertEquals(PermPolicy.WRITE_TOOLS, PermPolicy.allowedSet("full"))
        assertEquals(emptySet(), PermPolicy.allowedSet("不存在的档"))
    }

    @Test
    fun `只读闸只放行 AUTO`() {
        assertFalse(PermPolicy.readOnlyDenied(Risk.AUTO), "纯查询/读取应放行")
        assertTrue(PermPolicy.readOnlyDenied(Risk.CONFIRM), "创建修改应拒绝")
        assertTrue(PermPolicy.readOnlyDenied(Risk.DANGER), "删除应拒绝")
    }

    @Test
    fun `自动档不含文件面与设备操控`() {
        // 「自动执行」= 只放行自家业务数据；这两条是它与「完全访问」的本质差别。
        // （◆ / ◆ 已随 2026-11-26 开源根治移入设备包注册集——
        //   由下面的注册测试单独断言，shared 内置表不再硬编码它们。）
        listOf("fs_write_text", "mcp_connect").forEach { t ->
            assertFalse(t in PermPolicy.AUTO_TOOLS, "$t 不该自动放行")
            assertTrue(t in PermPolicy.WRITE_TOOLS, "$t 必须登记进写表")
        }
        // 业务数据类必须在自动档里
        listOf("todo_create", "schedule_create", "note_append", "memory_add").forEach { t ->
            assertTrue(t in PermPolicy.AUTO_TOOLS, "$t 应自动放行")
        }
    }

    @Test
    fun `设备包注册的写类工具并入写表`() {
        // 开源根治：◆ / phone_*（写类）经 registerExtraWriteTools 注入——
        // 与 ToolRegistry.registerExtra 同期由 DeviceFeaturesInstaller 调用。
        val injected = listOf("◆", "◆", "◆")
        injected.forEach { assertFalse(it in PermPolicy.WRITE_TOOLS, "$it 注册前不该在") }
        PermPolicy.registerExtraWriteTools(injected)
        injected.forEach { t ->
            assertFalse(t in PermPolicy.AUTO_TOOLS, "$t 不该自动放行")
            assertTrue(t in PermPolicy.WRITE_TOOLS, "$t 注册后必须进写表")
        }
    }

    @Test
    fun `permJson 往返`() {
        PermPolicy.TIER_KEYS.forEach { key ->
            val json = PermPolicy.permJsonFor(key)
            assertTrue(json.isNotEmpty(), "$key 的 permJson 不该为空")
            assertEquals(key, PermPolicy.modeForPermJson(json), "$key 往返应还原同一档位")
        }
        assertEquals("", PermPolicy.permJsonFor("不存在"))
    }

    @Test
    fun `旧 permJson 粗判仍给出可用的档位`() {
        // 空串 / 坏 JSON → 确认档（最安全）
        assertEquals("confirm", PermPolicy.modeForPermJson(""))
        assertEquals("confirm", PermPolicy.modeForPermJson("{坏 json"))
        // 老「完全访问」：allow 覆盖全部写工具 → full
        val oldFull = org.json.JSONObject()
            .put("allow", org.json.JSONArray(PermPolicy.WRITE_TOOLS.toList()))
            .toString()
        assertEquals("full", PermPolicy.modeForPermJson(oldFull))
        // 老「计划模式」：deny 覆盖全部写工具 → ro
        val oldRo = org.json.JSONObject()
            .put("deny", org.json.JSONArray(PermPolicy.WRITE_TOOLS.toList()))
            .toString()
        assertEquals("ro", PermPolicy.modeForPermJson(oldRo))
        // 只有部分 allow → auto
        val oldAuto = org.json.JSONObject()
            .put("allow", org.json.JSONArray(listOf("todo_create")))
            .toString()
        assertEquals("auto", PermPolicy.modeForPermJson(oldAuto))
    }
}
