package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P2 守卫（2026-11-09 审计三处粒度/幂等 + 一处能力缺口）。
 *
 * ① todo_edit 没有 due 参数：「改成明天下午三点」只能改日期，旧时刻保留、工具回 ok；
 * ② checkin_add 的 undo 直接删整天记录——同日已有记录会被一起带走（addCheckin 是覆盖语义，
 *    undo 应还原被覆盖的那条，而不是删掉当天全部）；
 * ③ memory_add 同名重放产生两行（name 无唯一约束），undo 按名只删第一条，残留一条；
 * ④ fs_delete 真删且无 undo 登记，文案里必须写明「不可撤销」。
 */
class WriteToolP2GuardTest {

    private fun source(rel: String): String {
        val f = File(rel)
        assertTrue("源码不在了（cwd 应为 app 模块目录）：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `todo_edit 必须支持 due 参数`() {
        val defs = source("../shared/src/jvmShared/kotlin/io/github/m0use001/planability/shared/ToolDefs.kt")
        val seg = defs.substringAfter("name = \"todo_edit\"").substringBefore("ToolDef(")
        assertTrue("todo_edit 参数面必须含 due（否则改不了时刻，只能改日期）", seg.contains("ToolParam(\"due\""))
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val edit = plan.substringAfter("private suspend fun todoEdit(").substringBefore("private suspend fun todoDelete(")
        assertTrue("todoEdit 必须解析 due 且解析失败显式 fail", edit.contains("optString(\"due\")") && edit.contains("截止时刻看不懂"))
    }

    /** P0（2026-11-12 中枢会话实报）：todo_edit 改 due 只写 start、endAt 从不跟 →
     *  时间轴显示「23:30–17:20」负时长，AI 按分钟排期 11 条排坏 3 条。时段必须成对写：
     *  改时刻=保持时长平移 end；due:"0" 清时刻=due/endAt 同时归零（不残留幽灵 end）。 */
    @Test
    fun `todo_edit 改 due 必须成对联动 endAt_不残留旧 end`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt").replace("\r\n", "\n")
        val edit = plan.substringAfter("private suspend fun todoEdit(").substringBefore("private suspend fun todoDelete(")
        val dueBranch = edit.substringAfter("a.optString(\"due\")").take(900)
        // 清零分支必须 due/endAt 同时归零（旧版只 copy(due=0) 残留 endAt）
        assertTrue("清时刻必须成对清：due=0 同时 endAt=0",
            dueBranch.contains("nt.copy(due = 0L, endAt = 0L)"))
        // 改时刻分支必须计算 newDue 后判断「原有时段」→ 平移 endAt 保时长
        assertTrue("改 due 必须先算 newDue 再按旧时段决定是否平移 endAt",
            dueBranch.contains("val newDue =") &&
                dueBranch.contains("old.due > 0 && old.endAt >= old.due"))
        assertTrue("有结束时段时必须平移 endAt（newDue + (old.endAt - old.due)）",
            dueBranch.contains("endAt = newDue + (old.endAt - old.due)"))
        // 原本无 end（endAt==0）的 fallback 是合法的「不凭空造」——不在此排除；
        // 上面三条已钉住：成对清 + 平移条件 + 平移公式，旧 bug（有 end 也不跟）必红。
    }

    @Test
    fun `checkin_add 的撤销要还原被覆盖记录而不是删整天`() {
        val plan = source("src/main/java/io/github/m0use001/planability/ai/ptl/PlanTools.kt")
        val seg = plan.substringAfter("private suspend fun checkinAdd(").substringBefore("private suspend fun screenTimeQuery(")
        assertTrue(
            "checkinAdd 必须先快照原记录（checkinOn），undo 时还原；只有原来没有记录才删",
            seg.contains("checkinOn(") && seg.contains("prevCheckin"),
        )
    }

    @Test
    fun `memory_add 同名必须原地更新而不是产生两行`() {
        val exec = source("src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt")
        val seg = exec.substringAfter("\"memory_add\" -> {").substringBefore("\"memory_forget\" -> {")
        // v57（2026-11-21 串味审计）：同名判定必须是**同项目**（byNameInProject）——
        // 全局 byName 会让 A 项目写记忆覆盖 B 项目同名行（id 复用=静默数据丢失）。
        assertTrue(
            "memory_add 必须按（同项目）名字查已有记忆并复用 id（否则同名重放出现两行，undo 只删一条；跨项目同名必须另起一行）",
            seg.contains("byNameInProject(") && seg.contains("existing"),
        )
        assertTrue(
            "memory_add 的同名查找不许再走全局 byName（跨项目覆盖口子必须关死）",
            !seg.contains("byName(memName)"),
        )
    }

    @Test
    fun `fs_delete 文案必须写明不可撤销`() {
        val fs = source("src/main/java/io/github/m0use001/planability/ai/ptl/FsShellTools.kt")
        // 2026-11-26：◆ 分支已迁可选源集，切片终点从该分支改为 when 的 else
        val seg = fs.substringAfter("\"fs_delete\" -> {").substringBefore("else -> null")
        assertTrue("fs_delete 无 undo 登记，ok 文案必须写「不可撤销」", seg.contains("不可撤销"))
    }
}
