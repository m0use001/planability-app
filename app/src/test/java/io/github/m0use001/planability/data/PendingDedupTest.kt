package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11 用户「待处理审核中一堆重复的」回归用例。
 *
 * 真机取证（真机）：40 组重复全部来自 capture 提纯——同一 (群, 日) 提纯多轮，
 * 每轮重插同一批事件（11-10 单日 879 条），时间措辞每轮略变。用例样本全部取自真机行。
 */
class PendingDedupTest {

    private fun item(
        title: String,
        kind: String = "todo",
        time: String = "",
        place: String = "",
        extra: String = "",
        id: Long = 0
    ) = PendingItem(
        id = id, title = title, kind = kind, timeText = time, place = place,
        extra = extra, status = "pending", createdAt = id
    )

    @Test
    fun `真机重复样本_时间措辞不同仍是同一事件`() {
        // 真机：「完成心理测评」×5（2026年10月10日 17:00 / 10月10日 17:00 / 10月10日 17:00前）
        val a = item("完成心理测评", time = "2026年10月10日 17:00")
        for (t in listOf("10月10日 17:00", "10月10日 17:00前", "2026年10月10日 17:00")) {
            assertTrue("「$t」应判重", PendingDedup.isDuplicate(a, item("完成心理测评", time = t)))
        }
        // 真机：「2026级本科生体质健康测评」vs「2026级本科生学生体质健康测评」（措辞插字）
        assertTrue(
            PendingDedup.isDuplicate(
                item("2026级本科生体质健康测评", "schedule", "11月15日"),
                item("2026级本科生学生体质健康测评", "schedule", "11月15日")
            )
        )
        // 真机：「参加院运会裁判培训」vs「院运会裁判培训」
        assertTrue(
            PendingDedup.isDuplicate(
                item("参加院运会裁判培训", "schedule"),
                item("院运会裁判培训", "schedule")
            )
        )
    }

    @Test
    fun `不同日期的同类事件不判重_每周组会`() {
        // 真机：组会 ×2（835/1393）——同名但不同周的会，不能并掉
        assertFalse(
            PendingDedup.isDuplicate(
                item("组会", "schedule", "11月3日 19:00"),
                item("组会", "schedule", "11月10日 19:00")
            )
        )
    }

    @Test
    fun `一边无日期视为不完整抽取_判重`() {
        assertTrue(
            PendingDedup.isDuplicate(
                item("组会", "schedule", "11月3日"),
                item("组会", "schedule", "")
            )
        )
    }

    @Test
    fun `会话工具斜杠日期参与判重_同标题不同日不并`() {
        // 会话工具 timeText 是「11/10 15:00」（fmtDay），不是「11月10日」
        assertTrue(
            PendingDedup.isDuplicate(
                item("买牛奶", time = "11/10 15:00"),
                item("买牛奶", time = "2026年11月10日 15:00")
            )
        )
        assertFalse(
            PendingDedup.isDuplicate(
                item("买牛奶", time = "11/12 15:00"),
                item("买牛奶", time = "11/13 15:00")
            )
        )
        assertFalse(
            PendingDedup.isDuplicate(
                item("开会", "schedule", "2026-11-12 10:00"),
                item("开会", "schedule", "2026-11-13 10:00")
            )
        )
    }

    @Test
    fun `编号不同的作业不判重`() {
        assertFalse(PendingDedup.isDuplicate(item("完成实验作业1"), item("完成实验作业3")))
    }

    @Test
    fun `类型不同不判重_报名表与比赛本身`() {
        assertFalse(
            PendingDedup.isDuplicate(
                item("提交南粤乡村故事演讲大赛报名表", "todo"),
                item("南粤乡村故事演讲大赛", "schedule", "11月20日")
            )
        )
    }

    @Test
    fun `同批内自判重`() {
        val batch = listOf(
            item("提交个人简介", time = "10月8日"),
            item("提交个人简介", time = "10月8日"),
            item("缴纳团费", time = "10月9日")
        )
        assertEquals(2, PendingDedup.freshOf(emptyList(), batch).size)
        // 与现有 pending 判重
        val existing = listOf(item("提交个人简介", time = "10月8日", id = 9))
        assertEquals(1, PendingDedup.freshOf(existing, batch).size)
    }

    @Test
    fun `重复命中时旧行空字段被补全`() {
        val old = item("峻德书院第四届院运会", "schedule", time = "", id = 471)
        val fresh = item("峻德书院第四届院运会", "schedule", time = "10月31日 13:00", place = "D6体育场")
        val filled = PendingDedup.fillBlanks(old, fresh)
        assertEquals("10月31日 13:00", filled.timeText)
        assertEquals("D6体育场", filled.place)
        // 旧行非空字段不被覆盖
        val keep = PendingDedup.fillBlanks(
            item("峻德书院第四届院运会", "schedule", time = "10月31日 13:00 或 11月1日 08:00", place = "D6 体育场"),
            fresh
        )
        assertEquals("10月31日 13:00 或 11月1日 08:00", keep.timeText)
    }

    @Test
    fun `存量清理_保留数据最全的一条其余置忽略并补字段`() {
        val a = item("峻德书院第四届院运会", "schedule", time = "", id = 471)
        val b = item("峻德书院第四届院运会", "schedule", time = "10月31日 13:00 或 11月1日 08:00", id = 527)
        val c = item("峻德书院第四届院运会", "schedule", time = "2026年10月31日", id = 585)
        val plan = PendingDedup.planHeal(listOf(a, b, c))
        assertEquals(listOf(471L, 585L), plan.ignoreIds)
        assertTrue("保留行应是 527（有完整 timeText）", plan.updates.isEmpty() || plan.updates[0].id == 527L)
    }

    @Test
    fun `清理计划不误伤不同事件`() {
        val all = listOf(
            item("组会", "schedule", "11月3日 19:00", id = 835),
            item("组会", "schedule", "11月10日 19:00", id = 1393),
            item("完成实验作业1", id = 1500),
            item("完成实验作业3", id = 1501)
        )
        val plan = PendingDedup.planHeal(all)
        assertEquals(emptyList<Long>(), plan.ignoreIds)
        assertTrue(plan.updates.isEmpty())
    }

    @Test
    fun `入库口与审核页都接了判重`() {
        val repo = java.io.File("src/main/java/io/github/m0use001/planability/data/DomainRepos.kt").readText()
        assertTrue("addPendingItems 没接 PendingDedup", repo.contains("PendingDedup.freshOf"))
        assertTrue("缺 healPendingDuplicates", repo.contains("suspend fun healPendingDuplicates"))
        val hub = java.io.File("src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt").readText()
        assertTrue("审核页没接存量清扫", hub.contains("healPendingDuplicates"))
    }
}
