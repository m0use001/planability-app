package io.github.m0use001.planability.ui.notes

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v19.44~46：封面图标链路 + 抽屉/编辑器几处 UI 的源码级断言。
 *
 * ## 为什么钉在源码层
 * 这条链路上最贵的失败**都不会编译失败、也不会让任何既有测试变红**：
 *
 * 1. **R8 把反射用到的图标删掉**。`MaterialIconSet` 是按包名反射枚举
 *    `androidx.compose.material.icons.filled.*Kt` 的，**R8 看不见反射**，
 *    于是把三千个「没人直接引用」的图标当死代码删掉 —— 真机表现是
 *    `MaterialIconSet.COUNT == 0`：ICON 款封面回退到 ◆ 数组、选择器里没有可选项。
 *    界面只会显示「选择图标（1 / 0）」，看上去像计数写错，实际是整批素材没了。
 *    这条唯一的防线就是 `proguard-rules.pro` 里那行 keep 规则。
 * 2. **图标下标没在两个素材池之间迁移**。两套素材共用 `coverIcon` 一个 Int、区间不同，
 *    不迁移的表现是「选完图标没反应」（选中值在 MC 池、选择器锁在 Material 池、
 *    绘制又对非 Material 回退到 offset）—— 每一步都"正常"，只有结果是错的。
 * 3. **抽屉用 padding 收窄视口**。内容在视口底边被切、下面留一条空带，看着像「列表被截断」。
 * 4. **剪纸款底部多出一条近白色带**。层填到 960 而内框到 1030、画布到 1120。
 */
class CoverIconPoolTest {

    private fun src(rel: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, rel)
        assertTrue("文件不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 封面素材源集（可选源集，公开版整体剔除）：目录按扫描发现，不在源码里点名内部目录名。 */
    private val cover: String by lazy {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
            ?: throw AssertionError("找不到仓库根")
        File(root, "app/src").listFiles { d -> d.isDirectory }.orEmpty()
            .filter { it.name !in setOf("main", "test", "androidTest", "benchmark") }
            .map { File(it, "kotlin/io/github/m0use001/planability/ui/notes/CoverMc.kt") }
            .firstOrNull { it.isFile }
            ?.readText(Charsets.UTF_8) ?: ""
    }
    private val coverCommon by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/notes/CoverIconCommon.kt") }
    private val artB by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/notes/CoverArtB.kt") }
    private val screen by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt") }
    private val files by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/files/FilesScreen.kt") }
    private val info by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt") }
    private val drawer by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/info/WorkbenchDrawer.kt") }
    private val proguard by lazy { src("app/proguard-rules.pro") }

    @Test
    fun `反射用到的 Material 图标必须有 keep 规则`() {
        // 没有这一行：R8 删掉整批 filled 图标 -> COUNT=0 -> ICON 封面全变成方块图标、
        // 选择器空。而**所有单元测试照样全绿**，只有真机看得见。
        assertTrue(
            "proguard-rules.pro 缺 `-keep class androidx.compose.material.icons.filled.**`：" +
                "MaterialIconSet 是按包名反射的，R8 看不见反射，会把这批图标当死代码删掉，" +
                "真机上 COUNT=0、ICON 款封面回退成 ◆ 素材、图标选择器里空无一物。",
            proguard.contains("-keep class androidx.compose.material.icons.filled.")
        )
        assertTrue(
            "MaterialIconSet 反射的包名变了，keep 规则也要跟着改",
            src("app/src/main/java/io/github/m0use001/planability/ui/notes/MaterialIconSet.kt")
                .contains("androidx.compose.material.icons.filled.")
        )
    }

    @Test
    fun `图标下标在两个素材池之间会迁移`() {
        // 2026-11-26：coerceIconToStyle 随贴图池根治移入 CoverIconCommon.kt（主模块）
        assertTrue("没有 coerceIconToStyle", coverCommon.contains("fun coerceIconToStyle"))
        val seg = coverCommon.substringAfter("fun coerceIconToStyle").substringBefore("\n}")
        assertTrue("ICON 款没迁到 Material 池", seg.contains("MaterialIconSet.offset"))
        assertTrue("非 ICON 款没迁回 MC 池", seg.contains("-> 0"))
        // 2026-11-26：NotesScreen 经 CoverArtPack.coerceIcon 槽位调（贴图池根治后迁移逻辑在主模块、
        // MC 语义由素材包注入）
        assertTrue(
            "打开编辑器时没迁移存量下标 —— 那颗 chip 会显示方块图标名、而封面画的是 Material 图标",
            screen.contains("CoverArtPack.coerceIcon(book?.coverIcon ?: 0")
        )
        assertTrue(
            "换款式时没迁移下标 —— MC→ICON 会变成「选择器锁在 Material、值却在 MC」，选完没反应",
            screen.contains("CoverArtPack.coerceIcon(customIcon, st)")
        )
        assertTrue("逐个位置的自定义图标没跟着迁", screen.contains("CoverArtPack.coerceIcon(v, st)"))
    }

    @Test
    fun `图标入口单元素与多元素是同一处`() {
        assertTrue(
            "「选择图标（…）」文字按钮还在 —— 用户 10-16 要求删掉它，单元素封面改用同一排 chip",
            !screen.contains("Text(\"选择图标（")
        )
        assertTrue(
            "单元素封面没有走同一排 chip（应见 selected = !single && ...）",
            screen.contains("selected = !single && v >= 0")
        )
        assertTrue("两种档位没有共用一个区块", screen.contains("val single = slotN <= 1"))
    }

    @Test
    fun `文件页顶栏三颗图标同一亮度`() {
        val tints = Regex("""tint = headerIcon""").findAll(files).count()
        assertTrue("三颗图标没都接 headerIcon（实际 $tints 处）", tints >= 3)
        assertTrue("没有 headerIcon 这个统一色", files.contains("val headerIcon = MaterialTheme.colorScheme.onSurface"))
        assertTrue(
            "顶栏又出现了按状态切换的着色（primary / onSurfaceVariant 二选一）",
            !files.contains("tint = if (clipboard != null) MaterialTheme.colorScheme.primary")
        )
    }

    @Test
    fun `抽屉底部用 contentPadding 而不是收窄视口`() {
        // Modifier.padding 收窄的是**视口**：内容在视口底边被切掉，下面留一条 84px 空带，
        // 手势条浮在里面 —— 用户截图上就是「最后一行切一半 + 下面空一块」。
        assertTrue(
            "抽屉还在用 navigationBarsPadding()/Modifier.padding 收窄视口 —— 底部会「切一半 + 空一条」",
            !drawer.contains(".navigationBarsPadding()")
        )
        assertTrue("抽屉没改用 contentPadding", drawer.contains("contentPadding ="))
        // 2026-11-01 抽屉改 WindowInsets(0) 全高铺底（ModalDrawerSheet 默认 systemBars 会把
        // 抽屉底边压在导航条上沿、手势条那条露遮罩）后，抽屉内部读不到 navigationBars——
        // 底部 inset 改为**外部量好传参**：InfoCenterScreen 量 navigationBars → bottomInset
        // 参数 → contentPadding.bottom。这里把整条链钉住。
        assertTrue(
            "contentPadding 底部没吃外部传入的 bottomInset —— 滚到底时最后一项会被手势条压住",
            drawer.contains("bottom = 24.dp + bottomInset")
        )
        // 2026-11-19 平板批：抽屉内容的接线抽成了 `drawerBody(bottomInset)` 这个 lambda
        // （宽屏把它常驻成左栏，接线只留一份）。整条链仍然钉住：
        // 宿主量 navigationBars → drawerBody(...) 传参 → WorkbenchDrawerContent(bottomInset = ...)。
        assertTrue(
            "宿主侧没从 WindowInsets.navigationBars 量 bottomInset（抽屉内部已读不到，只能外部量）",
            info.contains("WindowInsets.navigationBars.asPaddingValues()") &&
                info.contains("drawerBody(drawerBottomInset, true)")
        )
        assertTrue(
            "bottomInset 没接进 WorkbenchDrawerContent（滚到底时最后一项会被手势条压住）",
            info.contains("bottomInset = bottomInsetArg") ||
                info.contains("bottomInset = drawerBottomInset")
        )
    }

    @Test
    fun `剪纸款底部不留多出来的近白色带`() {
        val seg = artB.substringAfter("fun DrawScope.drawCoverPaperCut").substringBefore("// ---- 9")
        assertTrue("找不到剪纸款的绘制函数", seg.isNotEmpty())
        // 几何：画布 0..1120，内框 90..1030，层是「山脊线 + 底边」的多边形。
        // 层的底边必须落在**内框下沿 1030** 上，底框才与顶框(90)、左右(70) 同量级。
        assertTrue("内框底不是 1030，下面两条断言要跟着改", seg.contains("930f, 1030f"))
        assertTrue(
            "剪纸层没填到内框下沿（1030）—— 底部会多出一条近白色带" +
                "（[森林绿] 的 frame/inner 都是近白，160 单位 ≈ 封面高度的 14%）",
            seg.contains("O(70f, 1030f)")
        )
        assertTrue(
            "剪纸层还停在上限 960 —— 底部会多出 160 单位近白色带，比上边 90、左右 70 厚一倍",
            !seg.contains("O(70f, 960f)")
        )
    }

    @Test
    fun `封面圆角全款式统一，不许再按款式分叉`() {
        // 这条线走过 14 → 30 → 20dp 三次，每次都在「哪个数才对」上再赌一次；
        // 而同屏两个体系（选择器里一个圆一个方）比它解决的问题更多。
        // 现在唯一的半径来源是 COVER_RADIUS：观感一致、没有第二处要同步的常量。
        // v56.2a 追加迷你档契约（用户：概览小图=封面缩小、圆角等比例缩放）：
        // <40dp 的迷你渲染按 0.093（≈网格 14/150 的比例）缩半径，但**不许**按款式分叉、
        // 也不许在 drawRoundRect 里写死 COVER_RADIUS——写死后 clip 管不住，小图必成胶囊。
        assertTrue(
            "又给某个款式单开了圆角常量（MONDRIAN_RADIUS 那类）—— 分叉才是问题本身",
            !screen.contains("MONDRIAN_RADIUS")
        )
        assertTrue(
            "正常尺寸的半径没取自 COVER_RADIUS（≥40dp 必须严格用常数）",
            screen.contains("val radiusPx = if (size.minDimension >= 40.dp.toPx()) COVER_RADIUS.toPx()")
        )
        assertTrue(
            "迷你档没有按封面比例缩半径（应含 size.minDimension * 0.093f）",
            screen.contains("size.minDimension * 0.093f")
        )
        assertTrue(
            "drawRoundRect 里写死了 COVER_RADIUS 圆角——clip 管不住它，迷你封面会成胶囊；必须用 radiusPx",
            !Regex("""CornerRadius\(COVER_RADIUS\.toPx\(\)""").containsMatchIn(screen)
        )
        assertTrue(
            "又出现了按款式分支的半径选择",
            !screen.contains("if (baseStyle == 14) MONDRIAN_RADIUS")
        )
        val defs = Regex("""val COVER_RADIUS = \d+\.dp""").findAll(screen).count()
        assertTrue("COVER_RADIUS 定义了 $defs 次（应为 1）", defs == 1)
    }

    @Test
    fun `摆放方式这类 chip 点选不会变宽`() {
        // 选中时加一枚 leadingIcon 会让这颗 chip **变宽**，把后面的挤到下一行 ——
        // 用户点一下就看到整排跳一次（10-16 报的就是这个）。
        // 选中态本来就有 selectedContainerColor = primaryContainer 的实底，不需要对勾。
        val chips = screen.substringAfter("摆放方式").substringBefore("v19.44：图标入口")
        assertTrue(
            "摆放方式 chip 选了还会加 leadingIcon —— 点一下整排换行",
            // 注意匹配的是**代码形态** `leadingIcon =`：光匹配 leadingIcon 这个词
            // 会把上面那段解释性注释也算进来（本文件实测踩过，报了一个不存在的问题）
            !chips.contains("leadingIcon =")
        )
        // 同屏另外两排（文字排列 / 配色）同一毛病，一并对齐
        val beforeLayout = screen.substringBefore("摆放方式")
        val occurrences = Regex("""leadingIcon = if \(""").findAll(beforeLayout).count()
        assertTrue("编辑器里还有其它 chip 在选中时加 leadingIcon（$occurrences 处）", occurrences == 0)
    }

    @Test
    fun `工作台抽屉回归 M3 库默认形态`() {
        // 2026-10-24 用户拍板 B2-A：撤销 v19.44 的「铺满全宽 + 方角」刻意覆写。
        // M3E 已弃用该形态：规范 token 是 360dp 宽 + 右缘 16dp 圆角 + 可见遮罩——
        // 全宽方角等于把导航抽屉画成第二块全屏内容；3.4dp 缝在圆角 + 遮罩形态下
        // 是浮层的正常观感。这条反向钉死：不许再给 ModalDrawerSheet 套全宽/方角。
        assertTrue(
            "ModalDrawerSheet 又被铺满全宽了 —— 应保持库默认 360dp + 右缘 16dp 圆角（B2-A）",
            !info.contains("ModalDrawerSheet(\n                modifier = Modifier.fillMaxWidth()") &&
                !info.contains("drawerShape = androidx.compose.ui.graphics.RectangleShape")
        )
    }
}
