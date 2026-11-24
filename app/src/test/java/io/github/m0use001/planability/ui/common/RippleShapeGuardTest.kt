package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「按压水波必须被圆角裁剪」守卫（2026-11-20）。
 *
 * 用户报障（平板截图）：「许多地方的选中特效都是突兀的正方形」。机理是 Compose 的
 * `Modifier.clickable` 的 indication 由链上**最近的 clip** 裁剪：
 * - 没有 clip → 水波/悬停层是整块直角矩形；
 * - `clip(0.dp)`（宽屏 flat 行）→ 同样是直角，而且只在平板出现；
 * - `Card(shape = …)` 但 clickable 挂在 `modifier` 上 → 水波画在卡片形状裁剪**之外**，
 *   四个圆角处戳出去。
 *
 * 这个测试是三条里最不会误报的一条：**可点容器不许把裁剪半径设成 0**。
 * 它不试图判断布局，只钉住那个曾经出现过的具体错误写法
 * `clip(RoundedCornerShape(if (flat) 0.dp else …))` 和 `RoundedCornerShape(0.dp)`。
 *
 * 为什么不做「clickable 前必须有 clip」的全量断言：合法的无 clip 点很多（图标按钮、
 * 文字里的行内链接、`indication = null` 的全屏遮罩），全量断言会变成一张豁免清单，
 * 而不是守卫。这里只钉「已经爆过的形状」。
 */
class RippleShapeGuardTest {

    private val srcDir = File("src/main/java/io/github/m0use001/planability")

    private fun ktFiles(): List<File> = srcDir.walkTopDown()
        .filter { it.isFile && it.extension == "kt" && "/.mimosa/" !in it.path }
        .toList()

    @Test
    fun 可点容器的裁剪半径不许写成零() {
        // 允许的零圆角只有"明确要直角"的装饰层，且必须有注释说明；这些文件/写法不在扫描里：
        // 全屏遮罩（scrim）与 Canvas 裁剪不靠 RoundedCornerShape(0.dp)。
        val offenders = mutableListOf<String>()
        for (f in ktFiles()) {
            f.readLines().forEachIndexed { i, line ->
                val t = line.trim()
                if (t.startsWith("//") || t.startsWith("*")) return@forEachIndexed
                // 爆过的写法 1：clip 半径随 flat/宽屏参数归零
                if (t.contains("RoundedCornerShape(if (") && t.contains("0.dp")) {
                    offenders += "${f.name}:${i + 1}  ${t.take(80)}"
                }
                // 爆过的写法 2：显式零圆角（RectangleShape 请直接用它，不要用 0dp 圆角伪装）
                if (t.contains("RoundedCornerShape(0.dp)")) {
                    offenders += "${f.name}:${i + 1}  ${t.take(80)}"
                }
            }
        }
        assertTrue(
            "这些地方的裁剪半径是 0——可点容器的水波/选中底会变成直角方块（2026-11-20 用户报障，" +
                "要看整体形状请用 ui/common/ClickFeedback.kt 的 flatRowShape(flat)）：$offenders",
            offenders.isEmpty()
        )
    }

    @Test
    fun 扁平行形状在宽屏也必须圆角() {
        // flatRowShape 是扁平行圆角的唯一出处；退化成 0 就等于把老 bug 请回来。
        val f = File(srcDir, "ui/common/ClickFeedback.kt")
        assertTrue("ClickFeedback.kt 不见了（扁平行形状的唯一出处）", f.isFile)
        val src = f.readText(Charsets.UTF_8)
        assertTrue(
            "flatRowShape 在 flat=true 时必须给非零圆角",
            Regex("""fun flatRowShape\(flat: Boolean\): Shape =\s*\n?\s*if \(flat\) RoundedCornerShape\((\d+(?:\.\d+)?)\.dp\)""")
                .find(src)?.groupValues?.get(1)?.toFloat()?.let { it >= 1f } == true
        )
        assertTrue(
            "flat=false 分支必须复用 ShapeTokens.listRow（不许出现第二个圆角字面量）",
            src.contains("else ShapeTokens.listRow")
        )
    }

    @Test
    fun `计划页扁平行改走 flatRowShape`() {
        val f = File(srcDir, "ui/plan/PlanScreen.kt")
        assertTrue("PlanScreen.kt 不见了", f.isFile)
        val src = f.readText(Charsets.UTF_8)
        val hits = Regex("flatRowShape\\(flat\\)").findAll(src).count()
        assertTrue(
            "计划页的扁平行（日程行 / 待办行）必须用 flatRowShape(flat) 决定裁剪，现在只有 $hits 处",
            hits >= 2
        )
    }

    @Test
    fun 笔记网格格子的水波跟着封面圆角走() {
        // 用户截图那一处：笔记本网格格子原来没有 clip，按压高亮是整格直角方块，
        // 封面不透明把上半截盖住，只剩文字区露出一块灰方块。
        val f = File(srcDir, "ui/notes/NotesScreen.kt")
        assertTrue("NotesScreen.kt 不见了", f.isFile)
        val lines = f.readLines()
        // 直接在 items(shown, key = …) 的格子块里找：combinedClickable 前 20 行内必须有 clip
        val gridItem = lines.indexOfFirst { it.contains("items(shown, key = { it.id })") }
        assertTrue("笔记网格的 items(shown, …) 锚点丢了", gridItem >= 0)
        val block = lines.drop(gridItem).take(20).joinToString("\n")
        assertTrue(
            "笔记网格格子没有圆角 clip——按压水波又会是整格直角方块（2026-11-20 用户截图报障）",
            block.contains("clip(RoundedCornerShape(COVER_RADIUS))")
        )
    }

    @Test
    fun 共享修法在位() {
        val f = File(srcDir, "ui/common/ClickFeedback.kt")
        assertTrue("ui/common/ClickFeedback.kt 不见了", f.isFile)
        val src = f.readText(Charsets.UTF_8)
        assertTrue("clickableRounded 丢了", src.contains("fun Modifier.clickableRounded("))
        assertTrue("combinedClickableRounded 丢了", src.contains("fun Modifier.combinedClickableRounded("))
        assertTrue("flatRowShape 丢了", src.contains("fun flatRowShape("))
    }
}
