package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertTrue
import org.junit.Test
import io.github.m0use001.planability.data.COVER_SOLID
import java.io.File

/**
 * 每款封面的绘制分支与配色表必须一一对应。
 *
 * ## 为什么需要这条
 * 配色和绘制是**两份独立的清单**：款式编号在 NotebookCover 的 `when` 里，
 * 颜色在 CoverPalettes 里。少任何一边都不会编译失败——
 * 少画则画成素色（用户以为选了包豪斯，其实是空白），
 * 少配色则 [CoverPal] 取不到颜色，返回品红（用户以为选了孟菲斯，封面是紫的）。
 * 两种都是"看起来能跑、实际全错"的故障，只能在源码层拦。
 */
class CoverStyleCoverageTest {

    private fun src(file: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/io/github/m0use001/planability/ui/notes/$file")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 $file（工作目录不对？）")
    }

    @Test
    fun `选择器里的每个款式都有对应的绘制分支`() {
        val notes = src("NotesScreen.kt")
        // v19.47：两个特殊款（文字 90 / 图标 16）**不走 drawCoverX 数字分发**：
        // 90 在 when 里是表达式体，16 走语义入口 drawCoverIcon，
        // 源码里写常量名而不是数字，按数字正则永远匹配不到（旧断言因此恒红）。
        // 它们改按「分流行不在」断言；图案款 0..14 仍按数字断言。
        val special = mapOf(
            COVER_TEXT to "COVER_TEXT ->",
            COVER_ICON to "COVER_ICON ->"
        )
        for ((st, marker) in special) {
            assertTrue(
                "特殊款 $st 在选择器里，但 NotebookCover 的 when 里没有 $marker 这个分支",
                notes.contains(marker)
            )
        }
        for (st in COVER_PICKER_STYLES.filter { it != COVER_SOLID && it !in special.keys }) {
            assertTrue(
                "款式 $st 在 COVER_PICKER_STYLES 里，但 NotebookCover 的 when 没有它的绘制分支",
                Regex("""(^|\n)\s*$st\s*->\s*drawCover""").containsMatchIn(notes)
            )
        }
    }

    @Test
    fun `绘制分支与配色表一一对应`() {
        val notes = src("NotesScreen.kt")
        // v19.33 重构：配色表已并入 CoverProtocol.kt（原先是单独的 CoverPalettes.kt）
        val palettes = src("CoverProtocol.kt")
        val drawn = Regex("""(\d+)\s*->\s*drawCover(\w+)""").findAll(notes)
            .map { it.groupValues[1].toInt() to it.groupValues[2] }.toList()
        assertTrue("一个绘制分支都没匹配到，when 的写法变了？", drawn.isNotEmpty())
        for ((num, fn) in drawn) {
            assertTrue(
                "款式 $num 有绘制分支 drawCover$fn，但 CoverPalettes 里没有它的配色",
                Regex("""\b$num\s+to\s+""").containsMatchIn(palettes) ||
                    Regex("""\b$num\s*to\s*listOf""").containsMatchIn(palettes)
            )
            // 绘制代码必须真的存在（函数名拼错编译期才发现，但这里能提前一步）。
            // 注意是 DrawScope 的**扩展函数**（DrawScope.drawCoverX），正则要带上接收者，
            // 写成 "fun drawCoverX(" 会一个都匹配不到，而且它失败得很安静。
            val found = listOf("CoverArtA.kt", "CoverArtB.kt", "CoverArtC.kt")
                .any { Regex("""fun\s+(?:DrawScope\.)?drawCover$fn\s*\(""").containsMatchIn(src(it)) }
            assertTrue("款式 $num 的 drawCover$fn 在任何 CoverArt*.kt 里都找不到", found)
        }
    }

    @Test
    fun `黑金配色只出现在ArtDeco`() {
        // 用户 2026-10-14 的硬要求：黑金只给 Art Deco，别的款式不许出现
        val palettes = src("CoverProtocol.kt")
        val decoBlock = palettes.substringAfter("private val artdeco").substringBefore("private val morandi")
        assertTrue("Art Deco 的配色表里应该有黑金", decoBlock.contains("黑金"))
        val others = palettes
            .substringBefore("private val artdeco") + palettes.substringAfter("private val morandi")
        assertTrue(
            "黑金只允许出现在 Art Deco，其他款式的配色表里也出现了「黑金」",
            !others.contains("\"黑金\"")
        )
    }

    @Test
    fun `每套配色的第一项都是原文档色`() {
        // 保证对照表里永远能拿到"说明书原样"那张，配方改动不会悄悄改掉原色
        for (st in COVER_PICKER_STYLES.filter { it != COVER_SOLID }) {
            val pals = CoverPalettes.of(st)
            assertTrue("款式 $st 没有配色", pals.isNotEmpty())
            // v19.47：上限从 3 放宽。删掉「换色」按钮之后，素色/文字/MC 款各有 4~5 套配色
            // （换色全靠配色表撑着），1..3 是只有图案款时的残留。
            // 仍保留一个宽上限：防止有人顺手贴十几套把选择器撑爆。
            assertTrue("款式 $st 的配色数应为 1..8", pals.size in 1..8)
            assertTrue("款式 $st 的第一套配色要有名字", pals.first().label.isNotBlank())
        }
    }

    @Test
    fun `素色与文字款的配色表都有 base 角色`() {
        // 这两款不画图案，整张封面就是 `pal["base"]` 推出来的上下渐变。
        // 少一个 "base" → [CoverPal] 返回品红 → 封面变成一块紫红，
        // 而这种错**不会**编译失败，也不报错，只是画错了。
        for (st in listOf(COVER_SOLID, COVER_TEXT)) {
            val pals = CoverPalettes.of(st)
            assertTrue("款式 $st 没有配色", pals.isNotEmpty())
            for (p in pals) {
                assertTrue(
                    "款式 $st 的配色「${p.label}」缺 base 角色",
                    p["base"] != MISSING_PALETTE
                )
                assertTrue("款式 $st 的配色要有名字", p.label.isNotBlank())
            }
        }
    }
}