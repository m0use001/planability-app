package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v49：待办进度语义的**穷举断言**（2026-11-03）。
 *
 * ## 这份测试为什么存在
 *
 * 2026-11-02/03 一晚修了 8 个 bug，逐个修极其低效。归类后发现共同点：
 * **判定逻辑写在 composable 里**，所以既能「编译过、单测绿」，又能「真机一个组合就炸」。
 * 修法不是再补 8 个针对性测试，而是把判定提纯（[TodoProgress]）后**把状态空间走完**。
 *
 * 被这份测试钉死的真机事故（每条都对应一个 @Test）：
 * - 「设成 0% 了但时间轴没变化」→ 0% 不画填充（与未启用同形），1% 起才画
 * - 「打卡后锁死 100%，即使我只选了 70%」→ **填充比例只由 percent 决定**，打卡不覆盖它
 *   （2026-11-14 三次报障的终态：填充管进度（fillFraction）、勾管满（showsCheck），
 *   用户定稿「100% 才能打勾」）
 * - 「胶囊必须在 100% 时显示满，其他任何时候都不能」→ `fillFraction(p) == 1f ⟺ p >= 100`
 * - 「选 50% 但胶囊实际填充远不到一半」→ 50% 的比例必须 ≈ 0.5（只允许按比例留空隙）
 * - 「撤销打卡失效」→ 撤销后行上百分比**一律清空**（undoRollbackPercent），胶囊回空轮廓
 * - 「完成率两头不一致」→ 权重与胶囊几何是两套数字，不许混用
 *
 * ## 写法
 *
 * 全组合枚举而不是抽样：状态空间就 `done ∈ {false,true} × percent ∈ {-1,0..100}` = 204 个取值，
 * 走完只要几毫秒。**枚举穷尽的好处是「没想到的组合」也被覆盖**——今晚的 bug 全是
 * 「用户试了我没试的那一格」。
 */
class TodoProgressTest {

    // 百分比的全部取值：-1（未启用）+ 0..100
    private val allPercents: List<Int> = listOf(-1) + (0..100).toList()

    // ------------------------------------------------------------------ 不变量：满 ⟺ 100%

    @Test
    fun `填充比例等于 1 当且仅当百分之百`() {
        for (p in allPercents) {
            val f = TodoProgress.fillFraction(p)
            if (p >= 100) {
                assertEquals("percent=$p 应该正好填满", 1f, f, 0f)
            } else {
                assertTrue(
                    "percent=$p 的填充比例 $f 达到了满格——「满」就不再等于 100%，" +
                        "用户会以为已经完成（今晚「胶囊锁死 100%」正犯此病）",
                    f < 1f
                )
                assertTrue("percent=$p 的填充比例 $f 不该为负", f >= 0f)
            }
        }
    }

    @Test
    fun `未启用百分比时填充为零`() {
        assertEquals(0f, TodoProgress.fillFraction(-1), 0f)
        assertEquals(0f, TodoProgress.fillFraction(-100), 0f)
        assertEquals(0f, TodoProgress.fillFraction(Int.MIN_VALUE), 0f)
    }

    @Test
    fun `超出范围的值先夹紧再算 不许溢出成满格`() {
        assertEquals(1f, TodoProgress.fillFraction(150), 0f)
        assertEquals(1f, TodoProgress.fillFraction(Int.MAX_VALUE), 0f)
    }

    @Test
    fun `填充比例随百分比单调不减`() {
        var prev = -1f
        for (p in 0..100) {
            val f = TodoProgress.fillFraction(p)
            assertTrue("percent=$p 的比例 $f 比上一档 $prev 还小——滑条往回拖会变长", f >= prev)
            prev = f
        }
    }

    // ------------------------------------------------------------------ 真机事故 1：50% 看着远不到一半

    @Test
    fun `一半的比例接近一半 只允许按比例留空隙`() {
        val half = TodoProgress.fillFraction(50)
        // 允许为「不满」留比例空隙（0.96 系数），但**绝不能**像 v48.7 那样拿 2.5dp
        // 固定 padding 去减 — 在一个 20dp 的胶囊里那等于减掉 12.5%，渲染出来只有三成。
        assertTrue("50% 渲染成 $half，视觉上一眼就是「远不到一半」", half >= 0.45f)
        assertTrue("50% 渲染成 $half，超过了一半", half <= 0.5f)
    }

    @Test
    fun `九十九必须肉眼可辨地不满`() {
        val almost = TodoProgress.fillFraction(99)
        assertTrue("99% 与 100% 的填充差 $almost vs 1.0 太接近，用户分不出「满」", 1f - almost >= 0.03f)
    }

    // ------------------------------------------------------------------ 真机事故 2：打卡后锁死 100%

    /**
     * 2026-11-14 用户三次报障后的终态：**填充管进度、勾管满**，谁也不覆盖谁。
     *
     * ①「先打卡再取消…卡死在不显示已打卡」→ 打卡必须有个真实的完成事实；
     * ②「设置为 80% 完成度保存打卡，时间轴上却显示胶囊填满」→ 打卡不许改填充比例；
     * ③「**100% 才能打勾**」（用户原话，定稿）→ 启用百分比时勾 ⟺ 100%，
     *    80% 打卡就是八成电池、不打勾。
     */
    @Test
    fun `打卡不改填充比例 但不是百分百就不打勾`() {
        // 有进度比例可画（1..100）时绝不是"整颗实心"：填充走 percent
        for (p in 1..99) {
            assertFalse(
                "percent=$p 打卡后被画成整颗实心——80% 打卡会显示成满格（用户报障②）",
                TodoProgress.showsSolidChecked(done = true, percentDone = p)
            )
            assertFalse(
                "percent=$p 打了卡却画了勾——用户定稿「100% 才能打勾」",
                TodoProgress.showsCheck(done = true, percentDone = p)
            )
        }
        // 80% 打卡的唯一期望形态：八成电池、无勾（已打卡在浮层文案/时间轴暗色/已归档里）
        assertFalse("80% 打卡画了勾", TodoProgress.showsCheck(done = true, percentDone = 80))
        assertFalse(
            "80% 打卡被画成满格实心",
            TodoProgress.showsSolidChecked(done = true, percentDone = 80)
        )
        assertTrue("80% 的填充比例没到满格", TodoProgress.fillFraction(80) < 1f)
        // 到 100% 才有勾，且是满格（fillFraction(100)==1f + 挖孔勾）
        assertTrue("100% 没画勾", TodoProgress.showsCheck(done = true, percentDone = 100))
        assertTrue("100% 没画勾", TodoProgress.showsCheck(done = false, percentDone = 100))
        assertEquals("100% 必须满格", 1f, TodoProgress.fillFraction(100), 0f)
    }

    @Test
    fun `未启用百分比时用打卡当勾`() {
        // 没有"电量"可看 → done 就是勾（实心 + 挖孔勾）
        assertTrue(TodoProgress.showsCheck(done = true, percentDone = -1))
        assertFalse(TodoProgress.showsCheck(done = false, percentDone = -1))
        assertTrue(TodoProgress.showsSolidChecked(done = true, percentDone = -1))
        assertFalse(TodoProgress.showsSolidChecked(done = false, percentDone = -1))
        // 0% 也遵守「100% 才能打勾」：开了百分比就有"电量"可言，0% 打卡 = 空胶囊轮廓
        // （已打卡在浮层文案 / 时间轴暗色 / 已归档里可见，不在胶囊上）
        assertFalse("0% 打卡画了勾", TodoProgress.showsCheck(done = true, percentDone = 0))
        assertFalse(TodoProgress.showsSolidChecked(done = true, percentDone = 0))
        // 未启用时也不该画进度填充
        assertFalse(TodoProgress.showsProgressFill(-1))
    }

    // ------------------------------------------------------------------ 撤销打卡后的行值归宿

    @Test
    fun `撤销打卡后百分比一律清空`() {
        // 2026-11-14 用户定稿「撤销打卡后百分比与胶囊都要清空」：启用百分比的一律归 0，
        // 胶囊回到空轮廓——不留「撤销了但还填着八成」的中间态（那种状态下分不清
        // 是撤销成功还是没生效）。原「半途（0..99）保留」子规则已废弃。
        for (p in 0..150) {
            assertEquals("percent=$p 撤销后没清空", 0, TodoProgress.undoRollbackPercent(p))
        }
        // 未启用（负哨兵）原样返回——没有"清空"这回事
        assertEquals(-1, TodoProgress.undoRollbackPercent(-1))
        assertEquals(Int.MIN_VALUE, TodoProgress.undoRollbackPercent(Int.MIN_VALUE))
        // 清空后的胶囊形态：空轮廓（不填充、不打勾）
        assertFalse(TodoProgress.showsProgressFill(0))
        assertFalse(TodoProgress.showsCheck(done = false, percentDone = 0))
    }

    // ------------------------------------------------------------------ 真机事故 3：设成 0% 但没变化

    @Test
    fun `零不画填充 与未启用同形`() {
        assertFalse("0% 画了进度轨——用户的定义是「没任何完成就是空胶囊」", TodoProgress.showsProgressFill(0))
        assertFalse(TodoProgress.showsProgressFill(-1))
        // 但 1% 就要画（哪怕填充很细），否则滑条从 0 拖到 5 都没反应
        assertTrue("1% 没画填充，滑条前几档会像卡死", TodoProgress.showsProgressFill(1))
    }

    @Test
    fun `一以上的任何启用值都画填充`() {
        for (p in 1..100) {
            assertTrue("percent=$p 没画填充", TodoProgress.showsProgressFill(p))
        }
    }

    // ------------------------------------------------------------------ 完成率权重

    @Test
    fun `打卡权重恒为一 与百分比无关`() {
        for (p in allPercents) {
            assertEquals("done=true percent=$p 的权重不是 1", 1.0, TodoProgress.contribution(true, p), 0.0)
        }
    }

    @Test
    fun `未打卡时权重等于百分比 未启用为零`() {
        for (p in 0..100) {
            assertEquals(
                "percent=$p 的权重应是 $p/100",
                p / 100.0, TodoProgress.contribution(false, p), 1e-9
            )
        }
        assertEquals(0.0, TodoProgress.contribution(false, -1), 0.0)
    }

    @Test
    fun `权重与胶囊几何是两套数字 不许混用`() {
        // 99% 的语义权重必须是 0.99（评分显示 99%），
        // 而胶囊为了「不满」只画 0.9504。混用会让评分和胶囊两个数字对不上。
        assertEquals(0.99, TodoProgress.contribution(false, 99), 1e-9)
        assertTrue(
            "fillFraction 被误用到权重里了——评分会显示 95% 而不是 99%",
            TodoProgress.fillFraction(99) < TodoProgress.contribution(false, 99)
        )
    }

    @Test
    fun `权重永不超过一 也不为负`() {
        for (p in allPercents) {
            for (done in listOf(false, true)) {
                val w = TodoProgress.contribution(done, p)
                assertTrue("done=$done percent=$p 权重 $w 超了 1", w <= 1.0)
                assertTrue("done=$done percent=$p 权重 $w 为负", w >= 0.0)
            }
        }
    }

    @Test
    fun `进行中计数排除已打卡与已满与未启用`() {
        assertTrue(TodoProgress.inProgress(done = false, percentDone = 50))
        assertTrue(TodoProgress.inProgress(done = false, percentDone = 99))
        assertFalse("100% 不算进行中", TodoProgress.inProgress(done = false, percentDone = 100))
        assertFalse("已打卡不算进行中", TodoProgress.inProgress(done = true, percentDone = 50))
        assertFalse("未启用不算进行中", TodoProgress.inProgress(done = false, percentDone = -1))
    }

    // ------------------------------------------------------------------ 汇总：混合列表

    @Test
    fun `混合列表的加权完成数与完成率`() {
        val items = listOf(
            TodoProgressItem(done = true, percentDone = -1),   // 1.0
            TodoProgressItem(done = false, percentDone = 50),  // 0.5
            TodoProgressItem(done = false, percentDone = -1),  // 0.0
            TodoProgressItem(done = false, percentDone = 100)  // 1.0（满进度也算完成）
        )
        assertEquals(2.5, TodoProgress.weightedDone(items), 1e-9)
        assertEquals(0.625f, TodoProgress.rate(items, extraDone = 0.0, total = 4), 1e-6f)
    }

    @Test
    fun `完成率封顶一 即使补位数据被翻倍污染`() {
        val items = listOf(TodoProgressItem(done = true, percentDone = -1))
        val r = TodoProgress.rate(items, extraDone = 5.0, total = 2)
        assertEquals("完成率没封顶——真机出现过 200%", 1f, r, 0f)
    }

    @Test
    fun `总数为零时完成率为零 不出现除零`() {
        assertEquals(0f, TodoProgress.rate(emptyList(), extraDone = 0.0, total = 0), 0f)
    }

    @Test
    fun `权重与补位求和后再算率 顺序不影响结果`() {
        // 防止将来有人把 extraDone 先算进 total 或先乘 100 等改动造成口径漂移
        val items = listOf(
            TodoProgressItem(done = false, percentDone = 25),
            TodoProgressItem(done = false, percentDone = 75)
        )
        val a = TodoProgress.rate(items, extraDone = 1.0, total = 4)
        assertEquals(2.0 / 4.0, a.toDouble(), 1e-9)
        assertEquals(50, TodoProgress.ratePercent(a))
    }
}
