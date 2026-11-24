package io.github.m0use001.planability.ui.common

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 动效规范（**源码级**断言）：钉住 `ui/common/Motion.kt` 这一轮动效改造的底线——
 *
 * 1. 全局规范常量还在：三档时长、缓动、spring、展开/收起进出场、弹层进入；
 * 2. 我们负责的文件里**不再出现新的硬编码 `tween(毫秒)`**；
 * 3. 跨页面（全屏子页关闭）与关键交互（二级 tab/分段、弹层、展开收起）的时长来自 Motion。
 *
 * 为什么用源码断言而不是运行时断言：
 * 1. 这些约定全是**引用关系**（调用点有没有指向 Motion），运行时要把每个页面都点一遍才比得出来，
 *    而弹层/展开这些路径散在 9 个文件里，UI 测试的代价远高于读一眼源码；
 * 2. "写死的毫秒数"根本不是行为，是**代码里的字面量**——回归时代码照样编译、动画照样播，
 *    只是悄悄和全 app 不一个节奏了。这类静默回归只有源码断言能拦（同 HapticsConventionTest 的思路）。
 */
class MotionConventionTest {

    /** 本轮动效改造负责的文件（新增 Motion.kt 之外，允许改动的那些）。 */
    private val owned = listOf(
        "ui/common/Motion.kt",
        "app/PredictiveBackOverlay.kt",
        "app/NavShell.kt",
        "ui/plan/PlanScreen.kt",
        "ui/info/InfoCenterScreen.kt",
        "ui/info/ChatScreen.kt",
        "ui/info/ChatComponents.kt",
        "ui/notes/NotesScreen.kt",
        "ui/notes/PdfNoteScreen.kt",
        "ui/files/FilesScreen.kt"
    )

    private fun repoRoot(): File {
        // 单测的工作目录通常是模块目录（app/），也可能被构建配置挪动，向上找总没错
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val marker = File(dir, "app/src/main/java/io/github/m0use001/planability")
            if (marker.isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError(
            "找不到仓库根（从 ${System.getProperty("user.dir")} 一路向上都没见到 " +
                "app/src/main/java/io/github/m0use001/planability）——这条测试要读源码，" +
                "找不到就说明工作目录不对，别把它当成'约定没被破坏'"
        )
    }

    private fun src(rel: String): String {
        val f = File(repoRoot(), "app/src/main/java/io/github/m0use001/planability/$rel")
        assertTrue("$rel 不见了（改名/挪包了？动效规范请跟着搬家）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    private fun motionSource(): String = src("ui/common/Motion.kt")

    private fun ms(src: String, name: String): Int {
        val m = Regex("""const val $name = (\d+)""").find(src)
        assertTrue(
            "Motion.$name 不见了——三档时长是全 app 动效的唯一口径，删了它就回到'各写各的毫秒'",
            m != null
        )
        return m!!.groupValues[1].toInt()
    }

    // ------------------------------------------------------------ 1) 全局规范常量

    @Test
    fun `三档时长真的分档（quick _ standard _ emphasized，且相邻至少差 1_3 倍）`() {
        val src = motionSource()
        assertTrue("找不到 object Motion —— 它是本轮动效的唯一真相源", src.contains("object Motion"))

        val quick = ms(src, "quickMs")
        val standard = ms(src, "standardMs")
        val emphasized = ms(src, "emphasizedMs")

        assertTrue("quick($quick) 应短于 standard($standard)", quick < standard)
        assertTrue("standard($standard) 应短于 emphasized($emphasized)", standard < emphasized)
        // 档位之间至少差 1.3 倍：任意两档贴得太近，"快 = 微反馈、慢 = 有分量"的区分度就归零了
        assertTrue(
            "standard 与 quick 太接近（$standard / $quick）——两档会摸不出区别，" +
                "用户分不清'这是微反馈'和'这是常规切换'",
            standard >= (quick * 1.3).toInt()
        )
        assertTrue(
            "emphasized 与 standard 太接近（$emphasized / $standard）——弹层会和普通切换一个节奏，" +
                "'有分量'的感觉就没了",
            emphasized >= (standard * 1.3).toInt()
        )
    }

    @Test
    fun `每个档位都配了一句中文说明（写清什么交互用哪档、为什么）`() {
        val src = motionSource()
        listOf("quickMs", "standardMs", "emphasizedMs").forEach { name ->
            // 形如 `/** … */\n    const val quickMs = …` —— 注释必须紧贴常量（中间不许夹别的注释块）
            val doc = Regex("""(?s)/\*\*(?!\*/)(?:(?!\*/).)*\*/\s*const val $name\b""").find(src)
            assertTrue("Motion.$name 没有自己的 KDoc —— 每个档位都要写清「什么交互用、为什么是这个时长」", doc != null)
            assertTrue("Motion.$name 的注释里要有中文（说明用意，不是空壳 /** */）", doc!!.value.contains(Regex("[\\u4e00-\\u9fa5]")))
        }
    }

    @Test
    fun `规范里必须有缓动、spring、进出场与弹层进入（缺一个就会有人自己拍脑袋）`() {
        val src = motionSource()
        assertTrue("缺标准缓动（FastOutSlowIn）——内容切换的默认曲线", src.contains("FastOutSlowInEasing"))
        assertTrue("缺 emphasized 缓动（弹层用）", src.contains("CubicBezierEasing"))
        assertTrue(
            "缺 val contentSize（尺寸变化走 spring 而不是定长 tween：高度终点取决于内容，" +
                "tween 会在内容多时提前断掉、内容少时拖尾）",
            src.contains("val contentSize") && src.contains("spring(")
        )
        assertTrue("缺展开进场 expandIn（AnimatedVisibility 的 enter）", src.contains("val expandIn") && src.contains("expandVertically"))
        assertTrue("缺收起出场 collapseOut（AnimatedVisibility 的 exit）", src.contains("val collapseOut") && src.contains("shrinkVertically"))
        assertTrue("缺弹层进入 Modifier.motionEnter() —— 弹窗是独立窗口，只有它能给弹窗做进场", src.contains("fun Modifier.motionEnter()"))
        assertTrue(
            "motionEnter 必须用规范里的 default（standard）档——弹窗属\"部分遮挡屏幕\"，" +
                "官方速度表归 Default 而非 Slow（第二轮审查 P2-3）；写毫秒更是违规",
            src.contains("progress.animateTo(1f, Motion.standard())")
        )
    }

    @Test
    fun `wobble 是过冲强度档（沉降介于 standard 与 emphasized 之间，不套节奏档间距）`() {
        val src = motionSource()
        val m = Regex("""const val wobbleMs = (\d+)""").find(src)
        assertTrue("Motion.wobbleMs 不见了——wobble 档的参考沉降时长要留在规范里", m != null)
        val standard = ms(src, "standardMs")
        val emphasized = ms(src, "emphasizedMs")
        val wobble = m!!.groupValues[1].toInt()
        assertTrue(
            "wobble($wobble) 应介于 standard($standard) 与 emphasized($emphasized) 之间——" +
                "它是 MediumBouncy 级过冲（0.5/380）的自然沉降，不在 1.3× 节奏档间距约束内" +
                "（quick/standard/emphasized 区分「多快落定」，wobble 区分「抖得多明显」，两个维度）",
            wobble > standard && wobble < emphasized
        )
    }

    @Test
    fun `一次性抖动必须走 wobbleOn（不许各写各的 Animatable 抖动）`() {
        val src = motionSource()
        assertTrue(
            "缺 fun Modifier.wobbleOn( —— 状态翻转的「抖一下」唯一入口，" +
                "自带首组不抖（seen 门闩）与减弱动画跳过（ANIMATOR_DURATION_SCALE=0）；" +
                "调用点各写 Animatable 会丢这两条兜底",
            src.contains("fun Modifier.wobbleOn(")
        )
    }

    // -------------------------------------------- 1.5) 弹簧参数同样不许绕过 Motion 单源

    @Test
    fun `owned 文件里不许出现裸 spring 字面量（Motion_kt 是唯一定义处）`() {
        // v57 批 0（用户拍板「抖」推广全软件）：tween(毫秒) 有禁令，spring(dampingRatio=…)
        // 字面量同样会悄悄偏离全 app 的节奏——PillNavBar 曾有两处 0.6/800 恰好等于
        // fastSpatial 所以行为没事，但参数一旦手改就没有任何东西拦。
        // 范围 = owned 列表（Motion.kt 豁免：wobble/contentSize 的唯一定义处）；
        // 不在 owned 里的文件（如 AppButtonGroup 的裸弹簧）等推广计划批 2-4 逐批收口。
        val banned = Regex("""spring\(dampingRatio\s*=\s*\d""")
        owned.forEach { rel ->
            if (rel == "ui/common/Motion.kt") return@forEach
            val s = src(rel)
            assertTrue(
                "$rel 出现裸 spring(dampingRatio=…) 字面量——弹簧参数一律从 Motion 取" +
                    "（quick/standard/emphasized/wobble/sizeSpring），否则会悄悄和全 app 不一个节奏",
                !banned.containsMatchIn(s)
            )
        }
    }

    @Test
    fun `底栏页签横滑走 Motion_pageSlide 弹簧，不再用定长 tween`() {
        // v57 批 5（2026-11-08 用户拍板「换」）：NavShell 的页签横滑是全 app 最后一处
        // 用定长 tween 的大块位移。改成 spring 后：距离自适应不需要分段加时公式，
        // 且与全 app 一个物理体系。反向钉住的是**别退回去**——一旦有人重新引入
        // tween(durationMillis=…) 驱动 pager，这条会红。
        val shell = src("app/NavShell.kt")
        assertTrue(
            "NavShell 的 pager 切页没接 Motion.pageSlide() —— 页级横滑要与其他动效同源（临界阻尼弹簧）",
            shell.contains("Motion.pageSlide()")
        )
        assertTrue(
            "NavShell 又用 tween 驱动 animateScrollToPage 了 —— 页级横滑应交给弹簧按物理积分，" +
                "不再靠手算时长（10-29 的分段加时公式已随批 5 退役）",
            !Regex("""animateScrollToPage\((?s).{0,200}?tween\(""").containsMatchIn(shell)
        )
    }

    // -------------------------------------------- 2) 没有新的硬编码 tween(毫秒)

    @Test
    fun `本轮负责的文件里不允许再出现硬编码的 tween(毫秒)`() {
        val banned = Regex("""tween\(\s*\d""")
        owned.forEach { rel ->
            val s = src(rel)
            assertTrue(
                "$rel 里出现了硬编码时长 tween(数字…)——请改用 Motion 的 quick()/standard()/emphasized()，" +
                    "否则这一处会悄悄和全 app 不一个节奏",
                !banned.containsMatchIn(s)
            )
        }
        // 具体到改之前仅有的两处：关闭 180ms / 看门狗弹回 160ms
        val overlay = src("app/PredictiveBackOverlay.kt")
        assertTrue("PredictiveBackOverlay 里还留着写死的 180/160", !overlay.contains("tween(180)") && !overlay.contains("tween(160)"))
    }

    // -------------------------------------------- 3) 跨屏/关键交互的时长来自 Motion

    @Test
    fun `跨屏关闭动画（全屏子页）的时长来自 Motion，不许再写死毫秒`() {
        val overlay = src("app/PredictiveBackOverlay.kt")
        assertTrue(
            "全屏子页的程序化关闭应使用 Motion.standard()（= 180ms，沿用改造前手感）——" +
                "所有'顶栏返回 / 保存后关闭'共用这一个时长",
            overlay.contains("Motion.standard()")
        )
        assertTrue("看门狗把卡住的半途进度弹回，应使用 Motion.quick()（回到原状，越干脆越好）", overlay.contains("Motion.quick()"))
        assertTrue("PredictiveBackOverlay 的注释里要把口径指到 Motion（否则下一个人不知道这个时长从哪来）", overlay.contains("Motion"))
    }

    @Test
    fun `二级 Tab 与分段切换一律硬切，不做过渡交接`() {
        listOf("ui/plan/PlanScreen.kt", "ui/info/InfoCenterScreen.kt").forEach { rel ->
            val s = src(rel)
            // 2026-10-25 用户实锤：横向/交叉过渡的中间帧一定把两段内容同时画在屏幕上
            // （分类管理叠字、笔记列表⇄回收站旧行压在空提示上），看起来就是"切换时重叠"。
            // 结论：页级 tab / 分段内容一律硬切；动效只留给元素级（弹窗、卡片进出、悬浮岛）。
            // 这两条反向钉死，防止有人再按 M3E 审查意见把过渡加回来。
            assertTrue("$rel 还留着 Crossfade——切换瞬间两段内容同位重叠", !s.contains("Crossfade("))
            if (rel.endsWith("InfoCenterScreen.kt")) {
                // v74（2026-11-22 守卫清偿）：v70h 用户拍板「会话界面点击副框，只是主框内容
                // 左右移动变化」——信息页主框三态（首页/会话列表/对话）是**板内详情推送**
                // （方向推挤，键 Triple(pane, sessionId, nonce)，label "chat-pane-push"，
                // 几何由 ChatWidePanePushGuardTest 另行钉），不是 tab/分段切换，不进本条禁令。
                // 例外只此一处：再冒出任何 AnimatedContent 仍算违规。
                val count = Regex("AnimatedContent\\(").findAll(s).count()
                assertTrue(
                    "InfoCenterScreen 的 AnimatedContent 只允许 v70h 主框推挤一处（现 $count 处）——" +
                        "其余 tab/分段内容仍按用户 2026-10-25 拍板硬切",
                    count == 1
                )
                assertTrue(
                    "InfoCenterScreen 唯一的 AnimatedContent 缺 chat-pane-push 标记（不是 v70h 主框推挤）",
                    s.contains("chat-pane-push")
                )
            } else {
                assertTrue(
                    "$rel 的 tab/分段内容还在用 AnimatedContent 交接——过渡中间帧两块内容同屏，" +
                        "用户 2026-10-25 已明确要求硬切",
                    !s.contains("AnimatedContent(")
                )
            }
        }
    }

    @Test
    fun `展开_收起用 AnimatedVisibility + Motion 的进出场`() {
        listOf("ui/plan/PlanScreen.kt", "ui/info/ChatScreen.kt").forEach { rel ->
            val s = src(rel)
            assertTrue(
                "$rel 的展开/收起没有接 Motion.expandIn / Motion.collapseOut —— " +
                    "if 硬切会让下方内容被瞬间顶下去，用户看不出'多了几行'",
                s.contains("Motion.expandIn") && s.contains("Motion.collapseOut")
            )
        }
    }

    @Test
    fun `弹层（AlertDialog）进场统一走 motionEnter`() {
        listOf(
            "ui/files/FilesScreen.kt",
            "ui/notes/NotesScreen.kt",
            "ui/info/ChatScreen.kt",
            "ui/plan/PlanScreen.kt",
            "ui/info/InfoCenterScreen.kt",
            "ui/info/ChatComponents.kt"
        ).forEach { rel ->
            val lines = src(rel).lines()
            lines.forEachIndexed { i, line ->
                if (line.trim().endsWith("AlertDialog(")) {
                    // motionEnter 是 AlertDialog 的第二个实参，正常在声明后 3 行内；这里放宽到 6 行
                    val window = lines.drop(i).take(6).joinToString("\n")
                    assertTrue(
                        "$rel 第 ${i + 1} 行的 AlertDialog 没接 Modifier.motionEnter()——" +
                            "弹层进场要统一走 Motion（否则弹窗是'啪'一下出现的）",
                        window.contains("Modifier.motionEnter()")
                    )
                }
            }
        }
    }

    @Test
    fun `列表项落位动画（animateItem）在本轮碰过的列表里都还在`() {
        listOf(
            "ui/files/FilesScreen.kt",
            "ui/notes/NotesScreen.kt",
            "ui/info/InfoCenterScreen.kt",
            "ui/plan/PlanScreen.kt",
            "ui/info/ChatScreen.kt"
        ).forEach { rel ->
            assertTrue(
                "$rel 丢了 Modifier.animateItem() —— 新增/删除列表项会变成'凭空出现/凭空消失'，" +
                    "这轮改造只允许加、不允许减",
                src(rel).contains("animateItem()")
            )
        }
    }

    @Test
    fun `M3E 迁移：三档从 MotionScheme expressive 现取，参考毫秒只做文档`() {
        val src = motionSource()
        assertTrue("Motion 必须从 MotionScheme.expressive() 取官方弹簧（别把参数抄成魔数）", src.contains("MotionScheme.expressive()"))
        assertTrue("quick 应接 fastSpatial", src.contains("expressive.fastSpatialSpec()"))
        assertTrue("standard 应接 defaultSpatial", src.contains("expressive.defaultSpatialSpec()"))
        assertTrue("emphasized 应接 slowSpatial", src.contains("expressive.slowSpatialSpec()"))
        // 弹簧体系不再用 tween 驱动：允许注释里出现"tween"字样，但不允许还有 tween(数字) 的活代码
        // （owned 文件的 tween(数字) 禁令在别的测试里，这里守 Motion.kt 自己）
        val code = src.lines()
            .filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") || it.trim().startsWith("/*") }
            .joinToString("\n")
        assertTrue("Motion.kt 的可执行代码里不该再有 tween(数字)", !Regex("""tween\(\s*\d""").containsMatchIn(code))
    }

    @Test
    fun `计划页展开落位配了受守卫的 Select 触感`() {
        // M3E 弹簧把内容"推"出来之后要有"到了"的那一下：监听 visibleState 到 Expanded → Kind.Select
        // （AnimatedVisibility 没有 onExpanded 参数，visibleState 是唯一动画结束信号——别再试那个 API）
        // 2026-10-31 会话页用户实测「展开工具调用震动 2 次」后，落位 Select 只保留在计划页
        // （带 sawRunning 守卫防重组虚发）；会话页三处改为一次手势只配一记 Tap，见下一条。
        val rel = "ui/plan/PlanScreen.kt"
        val lines = src(rel).lines()
        var expandBlocks = 0
        lines.forEachIndexed { i, line ->
            if (line.contains("enter = Motion.expandIn")) {
                expandBlocks++
                // 24 行（原 16）：2026-10-30 的落位触感守卫（先见过 running=true 才发 Select）
                // 多了 4 行，把 Kind.Select 推出旧窗口导致误报——放宽窗口而不是回退功能。
                val window = lines.drop(i).take(24).joinToString("\n")
                assertTrue(
                    "$rel 第 ${i + 1} 行附近的展开没接落位 Select 触感 —— " +
                        "弹簧推出内容后没有'到了'那一下（见 Haptics.kt 弹簧搭配一节）",
                    window.contains("EnterExitState.Visible") && window.contains("Kind.Select")
                )
            }
        }
        assertTrue("$rel 里找不到 Motion.expandIn 展开点（搬家了？这条测试要跟着改）", expandBlocks > 0)
    }

    @Test
    fun `会话页展开块一次手势只配一记触感（不配落位 Select）`() {
        // 2026-10-31 用户：「展开调用会震动2次」=点击 Tap + 弹簧落定 Select 两记。
        // 已删落位触感（ToolCallsBlock/ToolResultRow/思考块三处同款）。
        // 这条反向钉住：会话页的 Motion.expandIn 展开窗口里**不许**再出现落位 Select，
        // 防止以后"补手感"时把第二记加回来。验收口径=dumpsys vibrator_manager 只一记。
        val rel = "ui/info/ChatScreen.kt"
        val lines = src(rel).lines()
        var expandBlocks = 0
        lines.forEachIndexed { i, line ->
            if (line.contains("enter = Motion.expandIn")) {
                expandBlocks++
                val window = lines.drop(i).take(24).joinToString("\n")
                assertTrue(
                    "$rel 第 ${i + 1} 行附近的展开窗口里出现了落位 Select —— " +
                        "一次手势只能有一记触感（点开那一记在行的 clickable 上）",
                    !window.contains("Kind.Select")
                )
            }
        }
        assertTrue("$rel 里找不到 Motion.expandIn 展开点（搬家了？这条测试要跟着改）", expandBlocks > 0)
    }
}
