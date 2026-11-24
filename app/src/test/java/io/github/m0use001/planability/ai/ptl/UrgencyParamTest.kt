package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-02 用户「AI 把重要性拉满，紧急性直接不设置」的防回归钉。
 *
 * 根因：四象限的**紧急轴**在 AI 工具参数里根本不存在——todo_create/todo_edit/
 * schedule_create/schedule_update 四个工具只有 priority（重要轴），AI 建的条目
 * urgency 永远 -1（未设置）。修复 = 四个工具补 urgency 参数（high/normal/low →
 * 五档 4/2/0，未传=null 保持未设置）+ 参数描述里写明四象限两轴分别设置。
 */
class UrgencyParamTest {

    @Test
    fun `四个计划工具都有urgency参数`() {
        for (name in listOf("todo_create", "todo_edit", "schedule_create", "schedule_update")) {
            val def = ToolRegistry.byName(name)
            assertNotNull("工具 $name 不存在了（搬家了？这条测试要跟着改）", def)
            assertTrue(
                "工具 $name 没有 urgency 参数——四象限「紧急」轴对 AI 又不可设了",
                def!!.params.any { it.name == "urgency" }
            )
            assertTrue(
                "工具 $name 的 priority 参数描述丢了四象限口径",
                def.params.firstOrNull { it.name == "priority" }
                    ?.description?.contains("四象限") == true
            )
        }
    }

    @Test
    fun `urgency参数三档映射五档_未传为null`() {
        fun a(vararg kv: Pair<String, String>): org.json.JSONObject {
            val o = org.json.JSONObject()
            kv.forEach { (k, v) -> o.put(k, v) }
            return o
        }
        assertEquals(4, ToolExecImpls.urgencyOfOrNull(a("urgency" to "high")))
        assertEquals(2, ToolExecImpls.urgencyOfOrNull(a("urgency" to "normal")))
        assertEquals(0, ToolExecImpls.urgencyOfOrNull(a("urgency" to "low")))
        assertEquals(4, ToolExecImpls.urgencyOfOrNull(a("urgency" to "4")))
        assertEquals(null, ToolExecImpls.urgencyOfOrNull(a("title" to "x")))
        assertEquals(null, ToolExecImpls.urgencyOfOrNull(a("urgency" to "随便乱写")))
    }
}
