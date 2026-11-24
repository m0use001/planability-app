package io.github.m0use001.planability.ui.profile

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「我的 → 更多设置」合并页的源码级断言。
 *
 * 口径沿革：
 * - v28（2026-10-21）：着色开关从「我的」主屏收进「计划设置」二级页（当时用户拍板
 *   「一屏硬塞设置项会把入口淹没」）；
 * - **2026-11-20 用户改判**：「1 个页面全显示一项设置也太浪费了」——「功能开关」（4 开关）、
 *   「计划设置」（2 开关）、「目标设定」（2 输入框）各占一整页，跳进去还要跳出来。
 *   三张合并成一页 **MoreSettingsScreen**（计划 / 开关 / 目标 三段共 8 项），
 *   「我的」主列表只留一个「更多设置」入口。
 */
class ProfilePlanSettingsTest {

    private val src: String by lazy {
        val f = File("src/main/java/io/github/m0use001/planability/ui/profile/ProfileScreens.kt")
        assertTrue("找不到 ProfileScreens.kt（测试的工作目录不对？）", f.exists())
        f.readText()
    }

    /** 去掉行注释，免得断言被注释里提到的词绊倒（第一版就踩过这个） */
    private fun stripComments(s: String) =
        s.lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")

    private fun profileBody(): String = stripComments(
        src.substringAfter("fun ProfileScreen(").substringBefore("// ====================")
    )

    private fun moreBody(): String = stripComments(
        // 合并页 = 「底栏排序」段（NavOrderSection）+ MoreSettingsScreen 本体（含 ToggleSetting），一起算
        src.substringAfter("==================== 底栏排序（并入「更多设置」）")
            .substringBefore("// ==================== 使用指引")
    )

    // ---------- 主列表 ----------

    @Test
    fun `主列表只留一个更多设置入口`() {
        val p = profileBody()
        assertTrue(
            "「我的」这一屏要有「更多设置」入口行",
            p.contains("\"更多设置\"") && p.contains("ProfileSub.MoreSettings")
        )
        assertTrue(
            "入口行没有接到合并页上（v68 起经 ProfileSubScreen 分发口：路由表必须指向 MoreSettingsScreen）",
            src.contains("ProfileSub.MoreSettings -> MoreSettingsScreen(onDone = onDone, deferBack = deferBack)")
        )
        // 四张旧二级页的入口不许复活
        assertFalse("旧「计划设置」入口又回来了", p.contains("\"计划设置\""))
        assertFalse("旧「功能开关」入口又回来了", p.contains("\"功能开关\""))
        assertFalse("旧「目标设定」入口又回来了", p.contains("\"目标设定\""))
        assertFalse("旧「底栏排序」入口又回来了", p.contains("\"底栏排序\""))
        // 账号页已改就地对话框（原整页只有昵称一个输入框）
        assertFalse("旧 AccountScreen 整页又回来了", p.contains("showAccount = true"))
        assertTrue("账号卡要点开昵称对话框", p.contains("showAccountDialog = true"))
    }

    // ---------- 合并页内容 ----------

    @Test
    fun `合并页有标题和返回`() {
        val b = moreBody()
        assertTrue(
            "合并页标题不是「更多设置」，和入口对不上",
            b.contains("Text(\"更多设置\", fontWeight = FontWeight.SemiBold)")
        )
        assertTrue(
            "合并页没有 PredictiveBackOverlay 返回（既有二级页形态；v68 起透传 deferBack）",
            b.contains("PredictiveBackOverlay(onBack = onDone, deferBack = deferBack)")
        )
        // 一页 8 项 + 顶栏，小屏要能滚
        assertTrue("合并页内容要能滚动", b.contains("verticalScroll(rememberScrollState())"))
    }

    @Test
    fun `计划开关在合并页里且写同一个设置字段`() {
        val b = moreBody()
        // v19.48：文案随产品改过——从「计划页按紧急·重要着色」改成「计划页显示紧急·重要胶囊」。
        // 这里要守的是 "有这颗开关 + 写同一个字段"，不是钉死某一版措辞。
        assertTrue("合并页里没有紧急·重要开关", b.contains("计划页显示紧急·重要胶囊"))
        assertTrue(
            "合并页没读设置快照，开关会永远显示旧值",
            b.contains("Graph.settings.snapshot.collectAsState()")
        )
        assertTrue(
            "紧急·重要开关没写 settings.planColorByUrgencyImportance —— 切了不落库，重启即失效",
            b.contains("it.copy(planColorByUrgencyImportance = !it.planColorByUrgencyImportance)")
        )
    }

    @Test
    fun `时间轴备注开关在合并页里且写同一个字段`() {
        val b = moreBody()
        assertTrue(
            "合并页里没有「首页时间轴显示备注」开关（用户 2026-10-21 要求）",
            b.contains("首页时间轴显示备注")
        )
        assertTrue(
            "开关没绑 settings.timelineShowNote —— 切了不落库，重启即失效",
            b.contains("it.copy(timelineShowNote = !it.timelineShowNote)")
        )
    }

    @Test
    fun `功能开关四行都在合并页里且逐个落库`() {
        val b = moreBody()
        assertTrue("缺「左缘搜索抽屉」开关", b.contains("searchEdgeEnabled = !it.searchEdgeEnabled"))
        assertTrue("缺「剪贴板导入浮条」开关", b.contains("clipboardEnabled = !it.clipboardEnabled"))
        assertTrue("缺「专注悬浮胶囊」开关", b.contains("floatBallEnabled = !it.floatBallEnabled"))
        assertTrue(
            "缺「后台冻结」开关（旁路 KV，走 UiFreeze.setEnabled）",
            b.contains("UiFreeze.setEnabled(freezeOn)")
        )
    }

    @Test
    fun `目标设定两个输入框在合并页里且写同一字段`() {
        val b = moreBody()
        assertTrue("缺「每日专注目标」输入框", b.contains("每日专注目标（分钟）"))
        assertTrue("缺「每日待办目标」输入框", b.contains("每日待办完成目标（个）"))
        assertTrue(
            "目标没写 settings.focusGoalMin/todoGoal —— 保存了也不落库",
            b.contains("focusGoalMin = focusMin.toIntOrNull() ?: 120") &&
                b.contains("todoGoal = todoGoal.toIntOrNull() ?: 5")
        )
    }

    @Test
    fun `底栏排序并进了合并页且写入字段不变`() {
        val b = moreBody()
        assertTrue("合并页里没有「底栏排序」段", b.contains("NavOrderSection()"))
        assertTrue(
            "底栏排序的显隐开关没写 settings.hiddenTabs",
            b.contains("it.copy(hiddenTabs = if (show) it.hiddenTabs - id else it.hiddenTabs + id)")
        )
        assertTrue(
            "底栏排序的上移/下移没写 settings.navOrder",
            b.contains("it.copy(navOrder = o)")
        )
    }

    @Test
    fun `旧二级页不许复活`() {
        val code = stripComments(src)
        assertFalse("旧 PlanSettingsScreen 又回来了", code.contains("fun PlanSettingsScreen("))
        assertFalse("旧 FeatureTogglesScreen 又回来了", code.contains("fun FeatureTogglesScreen("))
        assertFalse("旧 GoalSettingsScreen 又回来了", code.contains("fun GoalSettingsScreen("))
        assertFalse("旧 NavOrderScreen 又回来了", code.contains("fun NavOrderScreen("))
        assertFalse("旧 AccountScreen 又回来了", code.contains("fun AccountScreen("))
    }
}
