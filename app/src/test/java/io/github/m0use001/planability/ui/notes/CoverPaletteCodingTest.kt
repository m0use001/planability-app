package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.m0use001.planability.data.COVER_SOLID

/**
 * 封面编号 / 配色编码的约定测试。
 *
 * ## 为什么这些约定值得用源码测试钉住
 * `coverStyle` 是一个 Int，里面低位放款式、高位放配色（见 CoverProtocol）。这种"一个数装两个维度"
 * 的编码一旦被某处绕过编码函数直接写库，表现是**静默画成另一款封面**：
 * 不报错、不崩溃，只是颜色/图案变了。反过来，款式编号被回收后，老库里残留的编号也会静默变成素色。
 * 这两类都不该靠肉眼在真机上看出来。
 */
class CoverPaletteCodingTest {

    @Test
    fun `编号低位款式高位配色且可往返`() {
        for (base in listOf(0, 1, 5, 9, 14)) {
            for (pal in 0 until CoverPalettes.countOf(base)) {
                val code = encodeCoverStyle(base, pal)
                assertEquals("款式应能拆回来", base, coverBaseStyle(code))
                assertEquals("配色应能拆回来", pal, coverPaletteIndex(code))
            }
        }
    }

    @Test
    fun `素色用负数码带配色且负一保持实体默认值`() {
        // -1 是 coverStyle 的默认值 / Room 迁移目标值 / AI 建默认笔记本用的值。
        // 一旦素色改走 base+palette*100 或挪成正编号，真机上绝大多数素色行都要迁移。
        // 所以素色走负数码：-1 = 素色 + 第 0 套，零迁移。
        assertEquals(-1, encodeCoverStyle(COVER_SOLID, 0))
        assertEquals(COVER_SOLID, coverBaseStyle(COVER_SOLID))
        assertEquals(0, coverPaletteIndex(COVER_SOLID))
        for (pal in 0 until CoverPalettes.countOf(COVER_SOLID)) {
            val code = encodeCoverStyle(COVER_SOLID, pal)
            assertEquals("素色的负数码应能拆回来", COVER_SOLID, coverBaseStyle(code))
            assertEquals("素色的配色序号应能拆回来", pal, coverPaletteIndex(code))
        }
    }

    @Test
    fun `文字款走正常步长编码`() {
        // 文字款原来被排除在编码之外（换色按钮直接改 hue），v19.34 起也有配色方案了，
        // 于是 90 / 190 / 290 必须各自拆回 90。
        assertEquals(COVER_TEXT, encodeCoverStyle(COVER_TEXT, 0))
        assertEquals(COVER_TEXT + COVER_PALETTE_STRIDE, encodeCoverStyle(COVER_TEXT, 1))
        assertEquals(COVER_TEXT, coverBaseStyle(COVER_TEXT))
        assertEquals(0, coverPaletteIndex(COVER_TEXT))
        assertEquals(COVER_TEXT, coverBaseStyle(COVER_TEXT + 2 * COVER_PALETTE_STRIDE))
        assertEquals(2, coverPaletteIndex(COVER_TEXT + 2 * COVER_PALETTE_STRIDE))
    }

    @Test
    fun `素色和文字款都有多套配色可选`() {
        // 删掉「换色」按钮之后，这两个款式的换色能力**完全**靠配色表撑着。
        // 它们要是退回单套配色，界面上就再没有换颜色的入口了。
        assertTrue(
            "素色款至少要 2 套配色，否则删掉换色按钮后无法换色",
            CoverPalettes.countOf(COVER_SOLID) > 1
        )
        assertTrue(
            "文字款至少要 2 套配色，否则删掉换色按钮后无法换色",
            CoverPalettes.countOf(COVER_TEXT) > 1
        )
    }

    @Test
    fun `不同款式与配色不会撞码`() {
        val seen = mutableMapOf<Int, Pair<Int, Int>>()
        // v19.47：COVER_TEXT 早已进选择器列表，再 `+ COVER_TEXT` 会让 90 迭代两遍、
        // 自己撞自己（报「编码 90 重复了：款式90配色0 与 款式90配色0」，纯属测试自身）。
        // distinct 兜住重复，查重的本意不变。
        for (base in (COVER_PICKER_STYLES + COVER_TEXT).distinct()) {
            for (pal in 0 until CoverPalettes.countOf(base)) {
                val code = encodeCoverStyle(base, pal)
                val prev = seen[code]
                assertTrue(
                    "编码 $code 重复了：${prev?.let { "款式${it.first}配色${it.second}" }} 与 款式${base}配色$pal",
                    prev == null
                )
                seen[code] = base to pal
            }
        }
    }

    @Test
    fun `归一化把陌生款式收敛到素色但保留合法配色`() {
        // 老库里存在查无此号的遗留值（早期 AI 工具/调试写入）
        assertEquals(COVER_SOLID, normalizeCoverStyle(9999))
        assertEquals(COVER_SOLID, normalizeCoverStyle(18))
        // 合法款式 + 超范围的配色序号 → 收敛到第 0 套，而不是留一个画不出来的编号
        assertEquals(encodeCoverStyle(0, 0), normalizeCoverStyle(encodeCoverStyle(0, 9)))
        // 合法款式 + 合法配色 → 原样保留
        assertEquals(encodeCoverStyle(5, 2), normalizeCoverStyle(encodeCoverStyle(5, 2)))
        // 文字款原样（它不在 COVER_PICKER_STYLES 里，靠单独放行才不被误打成素色）
        assertEquals(COVER_TEXT, normalizeCoverStyle(COVER_TEXT))
        assertEquals(
            encodeCoverStyle(COVER_TEXT, 2), normalizeCoverStyle(encodeCoverStyle(COVER_TEXT, 2))
        )
        // 素色原样；带配色的负数码也要原样保留（否则用户在素色上选的颜色一开框就丢）
        assertEquals(COVER_SOLID, normalizeCoverStyle(COVER_SOLID))
        for (pal in 0 until CoverPalettes.countOf(COVER_SOLID)) {
            val code = encodeCoverStyle(COVER_SOLID, pal)
            assertEquals("素色第 $pal 套配色应保留", code, normalizeCoverStyle(code))
        }
        // 素色配色序号越界 → 收敛到第 0 套（-1），而不是留一个画不出来的负数码
        assertEquals(COVER_SOLID, normalizeCoverStyle(-99))
    }

    @Test
    fun `选择器里的款式都有配色可取`() {
        for (st in COVER_PICKER_STYLES) {
            assertTrue("款式 $st 没有配色可取", CoverPalettes.of(st).isNotEmpty())
        }
        assertTrue("文字款不在选择器里，但也要有配色可取", CoverPalettes.of(COVER_TEXT).isNotEmpty())
    }

    @Test
    fun `配色序号越界时收敛到第0套`() {
        for (st in (COVER_PICKER_STYLES + COVER_TEXT).distinct()) {
            assertEquals(0, CoverPalettes.clampPalette(st, -1))
            assertEquals(0, CoverPalettes.clampPalette(st, 999))
        }
        assertEquals("合法序号应原样返回", 1, CoverPalettes.clampPalette(0, 1))
    }

    @Test
    fun `款式编号从0开始且不含已删除的旧图案款`() {
        val styles = COVER_PICKER_STYLES.filter { it != COVER_SOLID }
        // v19.47：选择器顺序是「图案款 0..14 → 文字 90 → 图标 16 → ◆ 15」，
        // 三个特殊款**故意不按号序排**（各有独立编辑界面，要紧跟图案款成组）。
        // 旧断言要求整表按数字升序，等于在要求"特殊款必须拆散到数字里去"——与设计冲突。
        // 现在只钉图案款那段：0 起、连续、升序；特殊款单独点名，一个都不能少。
        val pattern = styles.filter { it !in setOf(COVER_TEXT, COVER_ICON, COVER_MC) }
        assertEquals("图案款应从 0 起连续编号", (0 until pattern.size).toList(), pattern)
        assertEquals("图案款应有 15 款", 15, pattern.size)
        assertEquals("图案款要按升序", pattern.sorted(), pattern)
        assertTrue("图案款段里应含 0 与 14", pattern.contains(0) && pattern.contains(14))
        assertTrue("文字款不在选择器里", styles.contains(COVER_TEXT))
        assertTrue("图标款不在选择器里", styles.contains(COVER_ICON))
        // 2026-11-26：COVER_MC 来自封面素材包（可选源集），注册了才可选。
        // unit test 环境无 ContentProvider → 未注册 → 不在列表（正是公开版的形态）。
        assertTrue(
            "素材包未注册时◆款不该在选择器里",
            !styles.contains(COVER_MC) && !io.github.m0use001.planability.core.CoverArtPack.available
        )
        // 用户删掉的三款（等距轴测/数据可视化/像素8-bit）曾占过编号，此处只钉连续，
        // 具体哪一款被删由设计定，测试不该钉死风格清单
    }

    @Test
    fun `配色表取不到的颜色会亮出来而不是静默变黑`() {
        // MISSING_PALETTE 是品红：写错 key 要一眼看见，而不是悄悄画成黑/透明
        val p = CoverPal("t", mapOf("bg" to androidx.compose.ui.graphics.Color.Red))
        assertEquals(androidx.compose.ui.graphics.Color.Red, p["bg"])
        assertEquals(MISSING_PALETTE, p["nope"])
    }
}