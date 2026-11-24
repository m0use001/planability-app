package io.github.m0use001.planability

import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.widget.TodayStatsArt
import io.github.m0use001.planability.widget.artColors
import org.junit.Test
import java.io.File

/**
 * 「今日数据」四环版式的**落盘验收**（与 TodayArtRenderTest 同一路）：
 * 横卡（4×2 实占）与竖长摆位（用户实拍的问题形态）各渲一张 PNG，
 * 验证聚拢居中改版——四环阵落在卡的中部，不再四角摊开。pull 下来人工核对。
 */
class TodayStatsArtRenderTest {

    @Test
    fun renderTwoOrientations() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        val outDir = File(ctx.getExternalFilesDir(null), "todaystats-art").apply { mkdirs() }
        val data = TodayStatsArt.Data(
            listOf(
                TodayStatsArt.Row("待办", "3/11", 0.27f),
                TodayStatsArt.Row("睡眠", "6h58m", 1.0f),
                TodayStatsArt.Row("专注", "25m", 0.52f),
                TodayStatsArt.Row("屏幕", "6h20m", 0.79f),
            )
        )
        // 横卡：手机 4×2 实占口径（1064×484px @3.5）
        val wide = androidx.compose.ui.unit.DpSize(304.dp, 138.dp)
        // 竖长摆位：用户实拍的问题形态（≈125×187dp）
        val tall = androidx.compose.ui.unit.DpSize(125.dp, 187.dp)
        listOf("todaystats-wide" to wide, "todaystats-tall" to tall).forEach { (name, size) ->
            val ctx2 = InstrumentationRegistry.getInstrumentation().targetContext
            val bitmap = io.github.m0use001.planability.widget.TodayStatsArt.render(
                ctx2, size, artColors(ctx2, size), 400, "default", 1.0f, data
            )
            File(outDir, "$name.png").outputStream().use {
                bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
