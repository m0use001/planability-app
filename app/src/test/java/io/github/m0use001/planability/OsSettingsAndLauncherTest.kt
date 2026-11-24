package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v19.43：**操作系统设置**与**桌面快捷方式**的源码级断言。
 *
 * ## 这两块为什么特别需要钉死
 * - OS 设置：权限没拿到时的表现是**静默无效**（`Settings.System.putInt` 在没 WRITE_SETTINGS 时
 *   直接 SecurityException；`setInterruptionFilter` 在没通知策略时什么都不做）。
 *   用户看到的是「指令跑了，但屏幕没变」——最难自己查的一类。
 * - 桌面快捷方式：清单**超上限会抛异常**、Intent 不唯一会被启动器合并成同一个、
 *   配额限流时发出去的更新会被系统丢掉。这三条都不编译失败，也都不报错，只是「不生效」。
 */
class OsSettingsAndLauncherTest {

    private fun src(rel: String): String {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private val registry by lazy { src("app/src/main/java/io/github/m0use001/planability/core/ActionRegistry.kt") }
    private val shortcuts by lazy { src("app/src/main/java/io/github/m0use001/planability/app/AutomationShortcuts.kt") }
    private val mainActivity by lazy { src("app/src/main/java/io/github/m0use001/planability/MainActivity.kt") }
    private val screen by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/info/AutomationScreen.kt") }

    @Test
    fun `要授权的系统设置动作会先拉起授权页再失败`() {
        // 「静默无效」改「拉起设置页 + 说清去哪开」：用户跑完这一条就知道下一步点哪里
        assertTrue(
            "sys_brightness 没判 Settings.System.canWrite —— 没权限时 putInt 直接抛 SecurityException",
            registry.contains("Settings.System.canWrite(context)")
        )
        assertTrue(
            "sys_dnd 没判通知策略授权 —— 没授权时 setInterruptionFilter 什么都不做（静默无效）",
            registry.contains("isNotificationPolicyAccessGranted")
        )
        assertTrue(
            "没拉起系统设置页的辅助函数 —— 光报错等于让用户自己猜要去哪开",
            registry.contains("openSystemSettings")
        )
        assertTrue(
            "拉设置页时没带 FLAG_ACTIVITY_NEW_TASK —— 流水线跑在后台没有 Activity 上下文，会抛异常",
            registry.contains("FLAG_ACTIVITY_NEW_TASK")
        )
        assertTrue(
            "WRITE_SETTINGS 的授权页没带 package: uri —— 用户会看到一个应用列表还要自己找",
            registry.contains("package:\" + context.packageName")
        )
    }

    // 按**下一个分支**切段，而不是按固定字数切：
    // 固定字数会让 sys_volume 的窗口串进 sys_brightness（那里有 canWrite），
    // 于是一条完全正确的代码报出「免授权档里出现了权限检查」——假阳性比漏报更费时间。
    private fun branch(key: String): String {
        val start = registry.indexOf("\"$key\" ->")
        assertTrue("没有 $key 的执行分支", start >= 0)
        val next = Regex("\"[a-z0-9_]+\" ->").find(registry, start + 10)
        return registry.substring(start, if (next != null) next.range.first else registry.length)
    }

    @Test
    fun `免授权的系统设置动作没有多余要求`() {
        // 手电/音量/面板是这批里唯一三样不需要授权的，它们的实现里不该出现权限检查，
        // 一旦有人顺手加个 canWrite 进去，这条提醒他「这里本来就不需要授权」
        listOf("sys_flashlight", "sys_volume", "sys_panel").forEach { key ->
            val seg = branch(key)
            assertTrue(
                "$key 里出现了权限检查 —— 这一档本来是免授权的",
                !seg.contains("canWrite") && !seg.contains("NotificationPolicyAccess")
            )
        }
        // 手电必须有闪光灯的探测：没有闪光灯的机型上 setTorchMode 静默无效
        assertTrue("sys_flashlight 没探测闪光灯", registry.contains("FLASH_INFO_AVAILABLE"))
        // 音量的上限必须取系统真实值：那个常量是 32767，部分机型会被夹掉，用户看到「设了没反应」
        assertTrue(
            "sys_volume 用了 MAX_VALUE 当上限 —— 部分机型会直接夹掉",
            !branch("sys_volume").contains("MAX_VALUE")
        )
    }

    @Test
    fun `桌面快捷方式的清单不会超上限也不会被合并`() {
        assertTrue(
            "没读 maxShortcutCountPerActivity —— 超上限时 setDynamicShortcuts 直接抛异常",
            shortcuts.contains("maxShortcutCountPerActivity")
        )
        assertTrue(
            "没按上限截断（take）",
            shortcuts.contains(".take(cap)")
        )
        assertTrue(
            "每个快捷方式的 Intent 没有唯一 data —— 同 action 的两条会被启动器当成同一个，" +
                "点下去跑的是同一条指令",
            shortcuts.contains("planability://automation/")
        )
        assertTrue(
            "没判配额限流 —— 限流期发布会白耗额度，把之后真正的更新也挤掉",
            shortcuts.contains("isRateLimitingActive")
        )
        assertTrue(
            "没有 LongLived 标记 —— 动态快捷方式默认会被系统当临时的回收",
            shortcuts.contains("setLongLived(true)")
        )
    }

    @Test
    fun `桌面图标点进来跑的是同一条执行链路`() {
        assertTrue(
            "MainActivity 的路由表里没有桌面快捷方式的分支 —— 点了图标什么也不会发生",
            mainActivity.contains("AutomationShortcuts.ACTION_RUN")
        )
        assertTrue("桌面入口没有复用统一的执行入口", mainActivity.contains("AutomationShortcuts.runById"))
        assertTrue(
            "统一入口没走 AutomationSweep.execute —— 那会让桌面/UI/AI 三处各写一份执行逻辑，" +
                "执行记录与通知就会不一致",
            shortcuts.contains("AutomationSweep.execute")
        )
        assertTrue(
            "桌面入口没有先切到自动化页 —— 用户点了图标看不到跑的是哪一条、成没成",
            mainActivity.contains("Router.openAutomations()")
        )
    }

    @Test
    fun `清单会在该更新的时候更新`() {
        // 不挂 Application.onCreate（别人的文件），代价是「改完没进这个页面则菜单是旧的」，
        // 所以三条刷新点必须都在
        assertTrue("保存后没重发", screen.contains("AutomationShortcuts.publishAsync"))
        assertTrue("进页面没重发", screen.contains("AutomationShortcuts.publishAsync(context)"))
        val signal = src("app/src/main/java/io/github/m0use001/planability/notify/AutomationSignal.kt")
        assertTrue(
            "开机后没重发 —— 动态快捷方式在重启后不会自动恢复",
            signal.contains("KIND_BOOT") && signal.contains("AutomationShortcuts.publish")
        )
        // 2026-10-28 用户要求删除「快捷指令」等三个小组件后，发布清单不再顺带刷小组件
        // （唯一读这份清单的桌面入口只剩长按图标）。这条改成：别再引用被删的组件，
        // 否则是编译期能过、运行期空转的那种"看起来还在刷"。
        assertTrue(
            "AutomationShortcuts 还在引用已删除的 ShortcutsWidget —— 该小组件 2026-10-28 已按用户要求移除",
            !shortcuts.contains("ShortcutsWidget")
        )
        assertTrue(
            "发布清单没有真正写回动态快捷方式（sm.dynamicShortcuts）",
            shortcuts.contains("dynamicShortcuts")
        )
    }

    @Test
    fun `长按图标与静态快捷方式仍接在同一份清单上`() {
        assertTrue("长按图标没读共用的清单", shortcuts.contains("listForLauncher()"))
        assertTrue(
            "长按图标没走共用的 Intent —— 各处自造 Intent 的话，改 id 的传递方式要改两遍",
            shortcuts.contains("intentFor(")
        )
        // 静态快捷方式那份 XML 是别人早就写好的（res/xml/shortcuts.xml + 两个 launcher alias 都引用了）。
        // 这里钉住「别把它的引用删掉」：资源文件在、引用没了，是编译能过而功能全无的那种失效。
        val manifest = src("app/src/main/AndroidManifest.xml")
        assertTrue(
            "manifest 里没有 android.app.shortcuts —— res/xml/shortcuts.xml 是死的，三个静态快捷方式不生效",
            manifest.contains("android.app.shortcuts")
        )
        val xml = File(
            generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
                .first { File(it, "app/src/main/res/xml/shortcuts.xml").isFile },
            "app/src/main/res/xml/shortcuts.xml"
        )
        assertTrue("res/xml/shortcuts.xml 不见了", xml.isFile)
    }
}