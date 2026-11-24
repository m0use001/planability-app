package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * 「行日期越过系列截止」的闸（2026-11-19 用户报障）。
 *
 * 现场：一条「每周二 · 至 11/7」的待办，11/19 16:10 被写成 `date=11/19`（due/endAt 同步
 * +16 天，其余列一个没动、没有完成流水/监督卡/百分比流水）。此后它天天出现在「今天」，
 * 既滚不走也删不掉——因为 `overdueDayView` 以「系列已结束」把它判死，而 `byDate(今天)`
 * 又确实把它算进今日列表。
 *
 * 根因不是某一次点击，而是**没有任何一处校验「行日期不得越过 repeatEnd」**：
 * 编辑器保存、长按改期、撤销完成时写回显示副本，三条路都能造出这种模型外的行。
 * 收口点选在两条「唯一闸口」上：
 *  · [PlanRepo.saveTodo] —— 所有保存（编辑器/改期/AI 工具/导入）都从这里入库；
 *  · `completeTodo` 的两个写回分支 —— 撤销与「超出期限标记完成」，两者的 todo 都可能是
 *    「今天的显示副本」。
 * 存量脏行由启动自愈 `repairDeadSeries` 收回。
 */
class DeadSeriesClampTest {

    private val day = LocalDate.of(2026, 11, 19)
    private val until = LocalDate.of(2026, 11, 7)
    private fun key(d: LocalDate): Long = d.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private val dayKey = key(day)
    private val untilKey = key(until)

    private fun todo(
        date: Long = dayKey,
        due: Long = 0L,
        endAt: Long = 0L,
        repeatEnd: Long = untilKey,
        permanent: Boolean = false,
        repeatType: String = "weekly"
    ) = Todo(
        id = 39L, title = "四级：原速刷听力2篇（题感恢复·不精听）", date = date,
        due = due, endAt = endAt, repeatType = repeatType, repeatEnd = repeatEnd,
        permanent = permanent
    )

    // ---------------- 纯函数：收回系列内 ----------------

    @Test
    fun `越过截止的行_日期收回截止日且时段同步位移_时长不变`() {
        val due = day.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endAt = day.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val c = clampIntoSeries(todo(due = due, endAt = endAt))

        assertEquals(untilKey, c.date)
        // 收回后：截止那天 19:30–20:00，时长仍是 30 分钟（只搬日期不搬时刻）
        assertEquals(until.atTime(19, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), c.due)
        assertEquals(until.atTime(20, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), c.endAt)
        assertEquals(30 * 60_000L, c.endAt - c.due)
    }

    @Test
    fun `没越界的行原样返回_必须是同一实例`() {
        val atEnd = todo(date = untilKey)
        val before = todo(date = key(until.minusDays(3)))
        // 同实例（===）：自愈靠它判「有没有东西要写库」，复制一份会变成每次启动都写库
        assertSame(atEnd, clampIntoSeries(atEnd))
        assertSame(before, clampIntoSeries(before))
    }

    @Test
    fun `无截止_永久_无日期的行不动`() {
        val open = todo(repeatEnd = 0L)
        val perm = todo(permanent = true)
        val noDate = todo(date = 0L)
        assertSame(open, clampIntoSeries(open))
        assertSame(perm, clampIntoSeries(perm))
        assertSame(noDate, clampIntoSeries(noDate))
    }

    @Test
    fun `无时间的行_收回日期后 due 与 endAt 仍是 0`() {
        val c = clampIntoSeries(todo(due = 0L, endAt = 0L))
        assertEquals(untilKey, c.date)
        assertEquals(0L, c.due)
        assertEquals(0L, c.endAt)
    }

    // ---------------- 接线闸：唯一闸口真的套了 ----------------

    private val repoPath = "app/src/main/java/io/github/m0use001/planability/data/PlanRepo.kt"

    private val repoRoot: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main").isDirectory) dir = dir.parentFile
        requireNotNull(dir) { "找不到仓库根" }.canonicalFile
    }

    private fun repoText(): String {
        val root = repoRoot.canonicalFile
        val f = File(root, repoPath).canonicalFile
        assertTrue("路径越界：$repoPath", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$repoPath", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `保存待办必须走收回闸口`() {
        val src = repoText()
        val body = src.substringAfter("suspend fun saveTodo(item: Todo): Long {").substringBefore("\n    }")
        assertTrue(
            "saveTodo 没把 todo 收回系列内就入库 —— 编辑器/改期/AI/导入四条路会再次写出「系列之外的行」",
            body.contains("clampIntoSeries(item)")
        )
        assertTrue(
            "saveTodo 仍直接把入参写库（越过截止的行会原样落库）",
            !body.contains("todoDao.upsert(item.copy(projectId = 0)")
        )
    }

    @Test
    fun `completeTodo 的三个写回分支都必须收回系列内`() {
        val src = repoText()
        val start = src.indexOf("suspend fun completeTodo(")
        assertTrue("找不到 completeTodo", start >= 0)
        val end = src.indexOf("private fun nextOccurrence(", start)
        assertTrue("找不到 completeTodo 的结束边界", end > start)
        val body = src.substring(start, end)
        // 撤销 / 超期标记完成 / 一次性完成 —— 三处 update 的入参都可能是「今天的显示副本」
        assertTrue(
            "还有写回点没套收回闸口：todoDao.update(todo.copy(...)) 会把这些天的日期污染进行",
            !body.contains("todoDao.update(todo.copy(")
        )
        assertEquals(
            "三处写回都应该过 clampIntoSeries（撤销 1 + 超期 1 + 一次性 1）",
            3, Regex("clampIntoSeries\\(").findAll(body).count()
        )
    }

    @Test
    fun `启动自愈必须接线`() {
        val root = repoRoot.canonicalFile
        val f = File(root, "app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt").canonicalFile
        val src = f.readText(Charsets.UTF_8).replace("\r\n", "\n")
        assertTrue("存量脏行没人修 —— 用户那条 11/19 会一直赖在「今天」", src.contains("plan.repairDeadSeries()"))
    }

    // ---------------- 入口闸：编辑器不给越界日期、面板不给死系列打今天的卡 ----------------

    private fun uiFile(rel: String): String {
        val root = repoRoot.canonicalFile
        val f = File(root, rel).canonicalFile
        assertTrue("路径越界：$rel", f.path.startsWith(root.path + File.separator))
        assertTrue("文件不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    @Test
    fun `编辑器保存必须把日期收回系列内且给出提示`() {
        val src = uiFile("app/src/main/java/io/github/m0use001/planability/ui/edit/Editors.kt")
        assertTrue(
            "buildTodo 没套 clampIntoSeries —— 日期越界时保存（以及按它排的提醒）会落在系列之外",
            src.contains("clampIntoSeries(Todo(")
        )
        assertTrue(
            "越界时没有提示 —— 用户会看到「跨度：… · 至 11月7日」配日期 11月19日却不被告知",
            src.contains("当前日期晚于") || src.contains("日期晚于重复截止")
        )
        assertTrue(
            "没有越界判据（ui 侧要能自己判断 date > until 才能提示）",
            src.contains("rec.untilDayKey > 0 && AppTime.dayKey(date) > rec.untilDayKey")
        )
    }

    @Test
    fun `打卡面板今天 chip 不校验应打日就会给死系列打卡`() {
        val src = uiFile("app/src/main/java/io/github/m0use001/planability/ui/plan/CheckinBoardScreen.kt")
        assertTrue(
            "「今天」chip 又对已结束的系列开放了打卡 —— 今天不属于这个系列，" +
                "completeTodo 会判「没有下一期」并把行钉在今天",
            src.contains("val seriesEnded = it.todo.repeatEnd in 1 until gateTodayKey")
        )
        assertTrue(
            "「今天」chip 对非应打日（每周二却周四点）也开打卡 —— 同样会把行钉在今天",
            src.contains("val offPhase = it.todo.repeatType != \"none\" &&")
        )
        assertTrue(
            "两种拦截没有真的生效（判据算出来但没用于拦）",
            src.contains("if (it.supervision && (seriesEnded || offPhase)) {") &&
                src.contains("return@TodayChip")
        )
    }
}
