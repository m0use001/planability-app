package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两处切模型面板实录修复（2026-11-12 用户同轮报的两条）：
 *
 * ①「deepseek 4.1 flash 切到 auto，上下文 33%→100%」——contextWindowOf 是字符串 contains
 *    猜测表，对 Routability 的精确模型名（auto / deepseek-ai/deepseek-v4.1-flash 等）不认识：
 *    deepseek 分支给 128k、auto 全不命中掉 32k 兜底。切到 auto 的瞬间同一份历史按 32k 折算
 *    直接爆表。真源是 RoutabilityCatalog.MODEL_CONTEXT（全表 1M，auto 也在），现在优先查它。
 *
 * ②「模型与思考面板弹出时思考强度行被手势区遮住」——两轮修复：navigationBarsPadding
 *    前置/后置在 ModalBottomSheet 里都实测无效（inset 被消费 → padding 返回 0，v19.14
 *    同款教训）。终版=滚动内容尾部放 windowInsetsBottomHeight(WindowInsets.navigationBars)
 *    的显式 Spacer，直读根窗口 inset 不受消费影响。
 */
class ModelSheetInsetsTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        // Windows checkout 是 CRLF，结构化断言先归一化
        return f.readText(Charsets.UTF_8).replace("\r\n", "\n")
    }

    // ---------- ① auto 的上下文窗口 ----------

    @Test
    fun `contextWindowOf 优先查 RoutabilityCatalog，auto 不再掉 32k 兜底`() {
        val catalog = io.github.m0use001.planability.ai.pool.RoutabilityCatalog
        // 真源表里每个模型（含 auto）都得给 1M（目录快照只收 1M 模型）
        val oneM = catalog.MODEL_CONTEXT.getValue("auto")
        assertTrue("目录快照的 1M 常量异常：$oneM", oneM >= 1_000_000L)
        catalog.MODEL_CONTEXT.forEach { (name, win) ->
            assertEquals("MODEL_CONTEXT[$name] 应为 1M", oneM, win)
        }
        // 实际函数：auto 与显式模型都命中真源表
        assertEquals(oneM.toInt(), io.github.m0use001.planability.data.contextWindowOf("auto"))
        assertEquals(
            oneM.toInt(),
            io.github.m0use001.planability.data.contextWindowOf("deepseek-ai/deepseek-v4.1-flash")
        )
        // 目录外的名字仍走老猜测表（deepseek 官方 1M / 老托管 128k / 未知 32k 兜底）
        assertEquals(1_000_000, io.github.m0use001.planability.data.contextWindowOf("deepseek-v4-flash-official"))
        assertEquals(128_000, io.github.m0use001.planability.data.contextWindowOf("deepseek-r1"))
        assertEquals(32_000, io.github.m0use001.planability.data.contextWindowOf("some-nim-model"))
    }

    @Test
    fun `contextWindowOf 的猜测表在真源表之后（优先级结构）`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/data/SettingsRepo.kt")
        val fn = src.substringAfter("fun contextWindowOf(")
        val catalogIdx = fn.indexOf("RoutabilityCatalog.MODEL_CONTEXT")
        val guessIdx = fn.indexOf("m.contains(\"mimo\")")
        assertTrue("contextWindowOf 没查真源表", catalogIdx >= 0)
        assertTrue("真源表查询必须先于字符串猜测（否则精确名被 contains 抢走）", catalogIdx < guessIdx)
    }

    // ---------- ② sheet 尾部被手势区遮住 ----------

    private fun sheetColumn(src: String, marker: String): String {
        val sheet = src.indexOf(marker)
        assertTrue("找不到 sheet（$marker）", sheet >= 0)
        val col = src.indexOf("Column(", sheet)
        assertTrue("sheet 里找不到内容 Column", col >= 0)
        return src.substring(col, src.indexOf(") {", col).coerceAtLeast(col))
    }

    /** 2026-11-12 二修（用户「思考强度仍被手势区挡住，要手动上滑」）：navigationBarsPadding
     *  前置/后置在 ModalBottomSheet 里都实测无效——inset 被上游消费后 padding 返回 0
     *  （v19.14 同款教训，ChatScreen InputBar 注释「那个 padding 返回 0」）。终版方案=
     *  滚动内容**尾部**放显式量导航栏高度的 Spacer（windowInsetsBottomHeight 直读根窗口
     *  inset，不受消费影响），内容滚到底时最后一行整段抬到手势条上方。
     *
     *  2026-11-12 三修（用户「最底下思考强度汉字还是被遮住」第三报）：①②两刀都能让
     *  内容滚到，但模型多时**档位行在折叠线以下**、打开即不可见（20:59 真机截图实证）。
     *  终版=结构重排：模型列表 LazyColumn(weight(1f,fill=false)) 自己滚，思考强度整段
     *  落在滚动区之外**钉底**，打开即完整可见（模拟器 22:51 实测：1080×2400、导航栏顶
     *  2337、"max" bottom=2289）；尾部显式 Spacer 保留（底缘留白）。 */
    @Test
    fun `模型与思考 sheet：思考强度钉底、列表内滚`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt")
        val sheet = src.indexOf("fun ModelPickerSheet")
        assertTrue("找不到 ModelPickerSheet", sheet >= 0)
        val nextFun = Regex("\nfun |\\nprivate fun |\\ninternal fun ").find(src, sheet)?.range?.first
            ?: src.length
        val body = src.substring(sheet, nextFun)
        assertTrue("模型列表必须 LazyColumn(weight(1f, fill = false)) 自己滚",
            body.contains("androidx.compose.foundation.lazy.LazyColumn") &&
                body.contains("weight(1f, fill = false)"))
        // 思考强度整段（标题/滑条/五档行）必须落在 LazyColumn 之后=滚动区之外
        val afterList = body.substringAfter("HorizontalDivider")
        assertTrue("思考强度行+滑条+档位行必须钉在列表之后（打开即见，不依赖滚动）",
            afterList.contains("思考强度") && afterList.contains("Slider") &&
                afterList.contains("ThinkingLevels.label(lv)"))
        // 尾部显式导航栏 Spacer 保留（sheet 底缘与手势条之间留白）
        assertTrue("sheet 尾部必须保留显式导航栏高度 Spacer",
            body.contains("Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))"))
        // 整列 verticalScroll 不许回潮（它把档位行送进折叠线以下，①②两刀的教训）
        assertTrue("整列 verticalScroll 必须移除",
            !body.substringBefore("LazyColumn").contains("verticalScroll"))
    }

    @Test
    fun `工作流 sheet 同款尾 Spacer（同一坑）`() {
        val src = source("app/src/main/java/io/github/m0use001/planability/ui/info/ChatWorkflowSheet.kt")
        // 2026-11-15：所有 ModalBottomSheet 统一加了 dragHandle = null（去掉默认小横条），
        // 锚点不能再假设 onDismissRequest 紧跟左括号——改成定位 ModalBottomSheet( 本身。
        val sheet = src.indexOf("ModalBottomSheet(")
        assertTrue("找不到工作流 sheet", sheet >= 0)
        val tail = src.substring(sheet)
        assertTrue("工作流 sheet 滚动内容尾部必须放显式导航栏高度 Spacer",
            tail.contains("Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))"))
        val col = sheetColumn(src, "ModalBottomSheet(")
        assertTrue("工作流 sheet 的 Column 外层同样不应再挂 navigationBarsPadding",
            !col.contains(".navigationBarsPadding()"))
    }
}
