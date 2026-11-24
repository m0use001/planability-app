package io.github.m0use001.planability

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v19.42：**事件触发**（充电 / 解锁 / 开机 / 打开应用）的源码级断言。
 *
 * ## 为什么这些必须钉在源码层
 * 事件触发最贵的失败模式不是崩溃，是**安静地不触发**：一条永不触发的自动化
 * 不会报错、不会通知、不会有执行记录，用户只能靠「它怎么没动」来发现，
 * 而排查方向往往会先怀疑 App 挂了。所以这里钉的全是「漏了不会有任何报错」的地方：
 *
 * 1. **双跑**：巡检过滤是 `enabled && hasTrigger`，而 `hasTrigger` 把事件也算进去了。
 *    少一个排除条件 → 事件型任务每 15 分钟跑一遍。症状是「我只插了一次充电，它跑了一晚上」。
 * 2. **迁移漏列**：实体加了字段、迁移没 ALTER → 老用户升级当场崩（Room 校验 schema）。
 * 3. **事件字面量拼错**：build 出来的是一条永不触发的空壳。
 * 4. **广播在白名单外**：manifest 里声明了也收不到，于是「加了没反应」。
 */
class EventTriggerTest {

    private fun repoFile(rel: String): File {
        val root = generateSequence(File(System.getProperty("user.dir") ?: ".").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "app/src/main/java/io/github/m0use001/planability").isDirectory }
        assertTrue("找不到仓库根", root != null)
        val f = File(root, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f
    }

    private fun src(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)

    private val db by lazy { src("app/src/main/java/io/github/m0use001/planability/data/AppDatabase.kt") }
    private val entity by lazy { src("app/src/main/java/io/github/m0use001/planability/data/AutomationEntity.kt") }
    private val worker by lazy { src("app/src/main/java/io/github/m0use001/planability/notify/AutomationWorker.kt") }
    private val signal by lazy { src("app/src/main/java/io/github/m0use001/planability/notify/AutomationSignal.kt") }
    private val manifest by lazy { src("app/src/main/AndroidManifest.xml") }
    private val screen by lazy { src("app/src/main/java/io/github/m0use001/planability/ui/info/AutomationScreen.kt") }
    private val toolDefs by lazy { src("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolDefs.kt") }
    private val toolExec by lazy { src("app/src/main/java/io/github/m0use001/planability/ai/ptl/ToolExecImpls.kt") }

    @Test
    fun `事件型任务不会被定时巡检捡走`() {
        // ① 最贵的一条：双跑
        assertTrue(
            "AutomationSweep 的过滤没有排除事件触发 —— 事件型任务会每 15 分钟跑一遍" +
                "（症状：只插了一次充电，它跑了一晚上）",
            worker.contains("!it.hasEventTrigger")
        )
        assertTrue(
            "hasTrigger 没把事件算进去 —— 那样事件型任务在界面上会显示成「快捷指令」（无触发）",
            entity.contains("hasGeoTrigger || hasEventTrigger")
        )
        // ② 每日分支必须自己判合法：位置触发型任务 interval=0/daily=-1，
        //    没有这道判断就会拿 dailyHour=-1 去算「今天已过没」，而它本来就不该进这个分支
        assertTrue(
            "巡检的每日分支没判 dailyHour 合法 —— 非每日型的任务会拿 -1 去比时刻",
            worker.contains("a.dailyHour in 0..23")
        )
    }

    @Test
    fun `事件两列有迁移且挂上了`() {
        val ver = Regex("""version = (\d+)""").find(db)?.groupValues?.get(1)?.toInt() ?: -1
        assertTrue("version 没解析到", ver >= 34)
        assertTrue("MIGRATION_${ver - 1}_$ver 没定义", db.contains("private val MIGRATION_${ver - 1}_$ver"))
        assertTrue("MIGRATION_${ver - 1}_$ver 没挂进 addMigrations", db.contains("MIGRATION_${ver - 1}_$ver)"))
        listOf("triggerKind", "triggerArg").forEach { col ->
            assertTrue(
                "迁移里缺 ALTER TABLE `automations` ADD COLUMN `$col` —— 老用户升级当场崩（Room 校验 schema）",
                db.contains("ALTER TABLE `automations` ADD COLUMN `$col` TEXT NOT NULL DEFAULT ''")
            )
        }
    }

    @Test
    fun `接收器声明的是白名单内的广播`() {
        assertTrue("manifest 里没有 AutomationSignalReceiver", manifest.contains(".notify.AutomationSignalReceiver"))
        listOf(
            "android.intent.action.BOOT_COMPLETED",
            "android.intent.action.MY_PACKAGE_REPLACED",
            "android.intent.action.ACTION_POWER_CONNECTED",
            "android.intent.action.ACTION_POWER_DISCONNECTED",
            "android.intent.action.ACTION_USER_PRESENT"
        ).forEach { act ->
            assertTrue(
                "manifest 少了 $act —— 这条广播不会送到，事件型自动化就静默不触发",
                manifest.contains("<action android:name=\"$act\"")
            )
        }
        // 白名单外的三个不能出现：声明了也收不到，白写；写上去还会让人以为「配了就是开着的」
        listOf(
            "android.intent.action.SCREEN_ON",
            "android.intent.action.SCREEN_OFF",
            "android.intent.action.HEADSET_PLUG"
        ).forEach { act ->
            assertTrue(
                "manifest 里有 $act —— Android 8 起它不在隐式广播白名单里，manifest 声明了也收不到",
                !manifest.contains(act)
            )
        }
    }

    @Test
    fun `接收器不阻塞主线程且有去重`() {
        assertTrue(
            "onReceive 没有 goAsync —— 执行链路里有数据库与模型调用，同步做必被 ANR 杀",
            signal.contains("goAsync()")
        )
        assertTrue("没切到 IO 线程", signal.contains("Dispatchers.IO"))
        // 事件触发没有 lastRun 那种天然护栏：系统广播会重投，manifest 与动态注册也可能同时收到
        assertTrue(
            "没有去重窗口 —— 同一次插电可能跑两遍，而用户看不出区别",
            signal.contains("DEDUP_MS")
        )
        assertTrue("去重窗口是 0（等于没有）", signal.contains("private const val DEDUP_MS = 120_000L"))
    }

    @Test
    fun `编辑器有事件档且 app_open 必须填包名`() {
        assertTrue("编辑器没有事件档", screen.contains("useEvent"))
        assertTrue("编辑器没引用事件类型常量（会与 notify 那份走偏）", screen.contains("AutomationSignal.KIND_"))
        // 没包名 = 匹配不到任何窗口 = 一条永不触发的空壳，而且不会有任何报错
        assertTrue(
            "app_open 没校验包名非空 —— 存出来是一条永不触发的空壳",
            screen.contains("eventKind == AutomationSignal.KIND_APP_OPEN && eventArg.isBlank()")
        )
        // 换档必须显式清空 triggerKind，否则「改成按间隔」会变成两种触发同时成立
        val saveSeg = screen.substringAfter("val a = when {").substringBefore("saveErr = when {")
        assertTrue(
            "切到非事件档时没清空 triggerKind —— 一条任务会同时按时间和按事件触发",
            saveSeg.contains("triggerKind = \"\", triggerArg = \"\"")
        )
        // 卡片文案：事件型不能掉到「手动」那一档去
        assertTrue("列表卡片没有事件的描述", screen.contains("AutomationSignal") && screen.contains("describe"))
    }

    @Test
    fun `AI 也能配事件触发且拼错会被挡`() {
        assertTrue("automation_create 没有 event 参数", toolDefs.contains("\"event\""))
        assertTrue("automation_edit 没有 event 参数", toolExec.contains("parseEventTrigger"))
        // 三种「拼错但不报错」的写法都要挡住
        assertTrue(
            "没校验事件类型 —— 拼错会建出一条永不触发的空壳",
            toolExec.contains("head !in kinds")
        )
        assertTrue(
            "power 的参数没限定 on/off —— 写成「充电」会安静地匹配不到任何广播",
            toolExec.contains("KIND_POWER && arg !in listOf(\"on\", \"off\")")
        )
        assertTrue(
            "app_open 没要求带包名",
            toolExec.contains("KIND_APP_OPEN && arg.isBlank()")
        )
        // 给了事件就必须清掉定时字段，否则两种触发一起响
        val create = toolExec.substringAfter("\"automation_create\" ->")
            .substringBefore("\"automation_run\" ->")
        assertTrue(
            "create 给了 event 也没清定时字段 —— 「插上电」和「每 60 分钟」会一起响",
            create.contains("if (event != null) 0 else interval")
        )
        assertTrue("edit 支持用 event=none 清掉事件触发", toolExec.contains("eventRaw == \"none\""))
    }

    @Test
    fun `广播接收器只调一次 goAsync`() {
        // 2026-10-22 phone-c 真踩：一次 onReceive 里调了两次 goAsync()，
        // 第二次返回 null → pending.finish() NPE → 每次开机/解锁广播都把进程打死。
        val receiver = signal.substringAfter("class AutomationSignalReceiver")
        // 只数真正的调用（"= goAsync()"）：注释里提到 goAsync() 不算调用
        val n = Regex("=\\s*goAsync\\(\\)").findAll(receiver).count()
        assertTrue(
            "AutomationSignalReceiver 里调了 $n 次 goAsync() —— 一次广播只能调一次，第二次返回 null",
            n == 1
        )
    }

}