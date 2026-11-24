package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-11 用户三条 UI 定案的源码守卫（组合期逻辑 JVM 测不到，钉源码——
 * 与 MarkdownMathTest 的容器宽守卫同一套打法）：
 * ①私人助理会话：中枢功能入口在标题栏底部胶囊行（同款小胶囊、占智能体选择位），⋮ 菜单项只留非私人助理；
 * ②私人助理会话锁定智能体：顶栏更换胶囊与发送设置「智能体」行都以 isHubAssistant 收走；
 * ③助理中枢：「更多」（项目文件/项目记忆）在顶栏右上角，入口卡不再是更多。
 */
class ChatHubLockSourceGuardTest {

    private val chatSrc =
        java.io.File("src/main/java/io/github/m0use001/planability/ui/info/ChatScreen.kt").readText()

    @Test
    fun `私人助理判定存在且智能体胶囊被isHubAssistant收走`() {
        assertTrue("缺 isHubAssistant 判定（KEY_HUB）", chatSrc.contains("AssistantRepo.KEY_HUB"))
        // 顶栏胶囊：if (!isHubAssistant) Text(assistantPlainName...（更换智能体入口）
        // 2026-11-16：改用 assistantPlainName（纯名字）——带 emoji 的 assistantLabel 在
        // 固定宽的顶栏胶囊行里放不下（用户报障）。判定条件本身不变。
        Regex("if \\(!isHubAssistant\\) Text\\(\\s*assistantPlainName").find(chatSrc)
            ?: error("顶栏的更换智能体胶囊没有用 isHubAssistant 收走")
    }

    @Test
    fun `中枢功能胶囊在底部胶囊行且菜单项只留非私人助理`() {
        // 2026-11-11 三定：中枢功能从标题右侧撤下，落进底部胶囊行（占智能体选择位）
        Regex("if \\(isHubAssistant && onOpenHub != null\\) Text\\(\\s*\"中枢功能\"").find(chatSrc)
            ?: error("中枢功能胶囊不在底部胶囊行（isHubAssistant 条件 + 中枢功能 文案）")
        // ⋮ 菜单项：私人助理界面不再出现（避免同一入口两处）
        assertTrue(
            "⋮ 菜单的中枢功能项没有排除私人助理界面",
            chatSrc.contains("if (onOpenHub != null && !isHubAssistant)")
        )
    }

    @Test
    fun `发送设置智能体行也被锁定`() {
        // 行与 { 之间允许夹注释行；Row( 到 SmartToy 之间是整条 modifier 链（~450 字符）
        Regex("if \\(!isHubAssistant\\) \\{\\s*(//[^\\n]*\\n\\s*)*Row\\([\\s\\S]{0,600}SmartToy").find(chatSrc)
            ?: error("发送设置里的智能体行没有用 isHubAssistant 收走（锁死不能只堵顶栏一个入口）")
    }

    @Test
    fun `中枢更多入口在顶栏右上角而非首屏入口卡`() {
        val hubSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt").readText()
        // topBar 的 actions 里有「更多」菜单
        Regex("actions\\s*=\\s*\\{[\\s\\S]{0,600}MoreHoriz").find(hubSrc)
            ?: error("「更多」没有在 HubTitleBar 的 actions（顶栏右上角）")
        // 首屏不再有「更多」入口卡
        assertTrue(
            "首屏的「更多」入口卡没有撤掉",
            !Regex("HubEntryCard\\([\\s\\S]{0,200}MoreHoriz").containsMatchIn(hubSrc)
        )
    }

    @Test
    fun `从会话进中枢必须先收对话页否则被z-order盖住`() {
        // 中枢页组合在对话页之前=画在下层：不收对话页，中枢永远被盖住
        //（2026-11-11 真机实锤：点胶囊"没反应"）
        val infoSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/ui/info/InfoCenterScreen.kt").readText()
        Regex("onOpenHub\\s*=\\s*\\{[\\s\\S]{0,200}hubReturnSessionId[\\s\\S]{0,200}showChat\\s*=\\s*false[\\s\\S]{0,200}showAssistantHub\\s*=\\s*true")
            .find(infoSrc)
            ?: error("onOpenHub 没有先收对话页（showChat=false）再开中枢——中枢会被对话页盖住")
        // 返回要回到原会话
        assertTrue(
            "中枢 onDone 没有经 hubReturnSessionId 回到原会话",
            Regex("hubReturnSessionId[\\s\\S]{0,300}chatSessionId\\s*=\\s*ret[\\s\\S]{0,200}showChat\\s*=\\s*true").containsMatchIn(infoSrc)
        )
    }

    @Test
    fun `中枢功能是同款小胶囊且标题右侧大胶囊已撤`() {
        // 2026-11-11 三定：跟底部小胶囊同一行、大小同款（labelSmall + surfaceContainerHigh）
        Regex("Text\\(\\s*\"中枢功能\"[\\s\\S]{0,500}labelSmall[\\s\\S]{0,500}surfaceContainerHigh[\\s\\S]{0,500}onOpenHub\\(\\)")
            .find(chatSrc)
            ?: error("中枢功能不是同款小胶囊（labelSmall + surfaceContainerHigh）")
        // 标题右侧大胶囊（headlineMedium×0.786）已撤：titleTrailing 槽位与缩放系数都不再出现
        assertTrue("titleTrailing 还在 ChatScreen（标题右侧大胶囊未撤干净）", !chatSrc.contains("titleTrailing"))
        assertTrue("0.786 缩放系数还残留（大胶囊字号逻辑未删）", !chatSrc.contains("0.786f"))
        // 副标题胶囊行：靠紧成组（二轮定案保持）。
        // v65（2026-11-20）：胶囊行从 M3ETitleBar 的 subtitleContent 内联提成
        // chatCapsuleRow 局部 lambda（宽屏 BoardHeader 与手机标题栏共用同一份），
        // 锚点跟着挪到 lambda 上；断言对象（spacedBy(6.dp) 靠紧、不满行铺开）不变。
        Regex("val chatCapsuleRow[\\s\\S]{0,400}spacedBy\\(6\\.dp\\)").find(chatSrc)
            ?: error("标题栏底部胶囊行没有靠紧（spacedBy）")
        assertTrue(
            "胶囊行还铺满整行（满行铺开未撤）",
            !Regex("chatCapsuleRow[\\s\\S]{0,300}Arrangement\\.SpaceBetween").containsMatchIn(chatSrc)
        )
    }

    @Test
    fun `中枢会话行对齐抽屉解剖且带管理菜单`() {
        val hubSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/ui/info/AssistantHubScreen.kt").readText()
        // 40dp 行高 + labelSmall 时间 + 48dp 尾块（SessionTreeRow 同款几何）
        Regex("height\\(40\\.dp\\)[\\s\\S]{0,1400}sessionTime[\\s\\S]{0,400}size\\(48\\.dp\\)").find(hubSrc)
            ?: error("中枢会话行没有对齐抽屉会话行解剖（40dp/时间/48dp 尾块）")
        // ⋮ 菜单三件事
        for (item in listOf("重命名", "归档", "删除")) {
            assertTrue("中枢会话 ⋮ 菜单缺「$item」", hubSrc.contains("Text(\"$item\""))
        }
    }

    @Test
    fun `中枢默认名私人助理纳入自动命名与清扫`() {
        val engineSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/ai/ChatEngine.kt").readText()
        assertTrue(
            "scheduleNaming 命名资格没盖住「私人助理」默认名",
            Regex("session\\.title != \"新对话\" && session\\.title != \"私人助理\"").containsMatchIn(engineSrc)
        )
        assertTrue(
            "healUntitledSessions 清扫没盖住「私人助理」",
            Regex("it\\.title\\.isBlank\\(\\) \\|\\| it\\.title == \"私人助理\"").containsMatchIn(engineSrc)
        )
        // 主会话去重：KV 失效先复用现存同名会话，不再新建
        val hubProjSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/data/HubProject.kt").readText()
        Regex("ensureMainSession[\\s\\S]{0,400}firstOrNull\\s*\\{[\\s\\S]{0,200}title == \"私人助理\"")
            .find(hubProjSrc)
            ?: error("ensureMainSession 没有先复用现存「私人助理」会话（KV 失效会堆重名会话）")
    }

    @Test
    fun `抽屉提纯与自动化入口有胶囊背景`() {
        val drawerSrc =
            java.io.File("src/main/java/io/github/m0use001/planability/ui/info/WorkbenchDrawer.kt").readText()
        assertTrue("LeafRow 缺 pillColor 参数", drawerSrc.contains("pillColor: androidx.compose.ui.graphics.Color? = null"))
        for (entry in listOf("自动化设置", "暂无自动化任务")) {
            Regex("LeafRow\\([\\s\\S]{0,200}" + Regex.escape(entry) + "[\\s\\S]{0,200}pillColor").find(drawerSrc)
                ?: error("「$entry」入口没有传 pillColor（胶囊背景）")
        }
    }
}
