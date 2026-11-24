package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v49：**单一事实源**的源码级断言（2026-11-03）。
 *
 * ## 抓的是什么
 *
 * 2026-11-02/03 一晚 8 个 bug 里有 4 个属于同一族：**同一事实被两处各自定义**。
 * - 「完成权重」公式在 `HomeCards.kt` 与 `TodayStatsWidget.kt` 各抄一份（今晚没炸，
 *   但改一处忘一处就是下一个「完成率 200%」——仓库记忆里这一族已连犯 4 次）；
 * - 「胶囊满 ⟺ 100%」的判定曾同时活在 `TodoStatusPill`、`PlanScreen`、时间轴三处；
 * - 「percent 的启用判断」`>= 0` 散落各处，改成别的哨兵值时漏改就是「设 0% 没反应」。
 *
 * 这份测试把「只许有一份」变成**可执行规则**：公式文本只允许出现在 `TodoProgress.kt`，
 * 别处出现即红。新增第三份复制会在 CI/本地单测当场被抓，不用等真机。
 *
 * ## 为什么钉在源码层而不是运行时
 *
 * 这四份实现各自都能编译过、单测绿，且分属 UI / widget / data 三层，
 * 运行时断言跨不过去。仓库已有先例：`MigrationShapeTest` 就是这样钉迁移三件套的。
 *
 * ## 验收方式（防假绿）
 *
 * 往任意被禁文件里粘一行被禁公式，本测试必须红；删掉后恢复绿。
 * （与 pc-b 的 contract-drift 测试同一验收纪律：断言太松 = 假绿。）
 */
class SingleSourceTest {

    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            if (File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) return dir
            dir = dir.parentFile
        }
        throw AssertionError("找不到仓库根——这条测试要读源码")
    }

    /** 扫全部 Kotlin 源码（跳过 build/ 与测试自身） */
    private fun sources(): List<Pair<String, String>> {
        val root = File(repoRoot(), "app/src/main/java")
        val out = mutableListOf<Pair<String, String>>()
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            out += f.relativeTo(repoRoot()).path to f.readText(Charsets.UTF_8)
        }
        return out
    }

    private fun assertOnlyIn(pattern: String, allowedFile: String, why: String) {
        val offenders = sources().filter { (path, text) ->
            !path.endsWith(allowedFile) && text.contains(pattern)
        }
        assertTrue(
            "「$pattern」只允许出现在 $allowedFile（$why）。\n" +
                "违规文件：${offenders.joinToString { it.first }}\n" +
                "改法：删掉本地重算，改为调用 TodoProgress 对应函数。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `完成权重公式只活在 TodoProgress`() {
        // 真机 200% 事故的公式形状：done 优先、启用按 percent/100 加权
        assertOnlyIn(
            "if (it.done) 1.0 else if (it.percentDone in 0..99)",
            allowedFile = "TodoProgress.kt",
            why = "完成权重是单一事实——HomeCards 与 TodayStatsWidget 曾各抄一份，改一处忘一处就是下一个 200%"
        )
    }

    @Test
    fun `百分比不许开后门改写打卡状态`() {
        // 这条是照 v49 真抓到的洞写的：`PlanTools.kt:136` 曾把 percent=100 翻成
        // `copy(done = true, doneAt = ...)`——被废除的第二条完成通道。它绕过 doneLog /
        // 滚期 / 监督打卡，直接让时间轴与打卡账本分裂。现在钉死：
        // **任何地方都不许把 percentDone 的判定和 done=true 的赋值写在同一处**。
        val offenders = sources().filter { (_, text) ->
            // 「percentDone ... 比较」与「done = true」出现在同一个 if 表达式里
            Regex("percentDone\\s*[><=]+\\s*1?0*0?[)\\s]*.*done\\s*=\\s*true").containsMatchIn(text) ||
                Regex("done\\s*=\\s*true.*percentDone\\s*[><=]+").containsMatchIn(text)
        }
        assertTrue(
            "百分比被用来直接改写 done —— 那是被废除的第二条完成通道（v48.6/v49）。\n" +
                "违规文件：${offenders.joinToString { it.first }}\n" +
                "改法：percent 只落 percentDone；标完成只走 completeTodo（UI）或 todo_complete（AI）。",
            offenders.isEmpty()
        )
    }

    @Test
    fun `百分比启用判断的哨兵比较只活在 TodoProgress`() {
        // 「启用」的判据（>=0）换哨兵值时只许改一处
        assertOnlyIn(
            "percentDone >= 0",
            allowedFile = "TodoProgress.kt",
            why = "启用判断（哨兵 -1）是 percentDone 语义的根，散落各处后改哨兵必漏"
        )
    }

    @Test
    fun `胶囊完成态判定只活在 TodoProgress`() {
        // 「启用时 100 才算满、未启用看 done」这条三态判定
        assertOnlyIn(
            "percentDone >= 100",
            allowedFile = "TodoProgress.kt",
            why = "「满 ⟺ 100%」是用户拍板的终态规则，写两处必漂移（胶囊锁死 100% 的根）"
        )
    }

    @Test
    fun `百分比除法比例只活在 TodoProgress`() {
        // 0.96 空隙系数与 /100f 的几何换算
        assertOnlyIn(
            "SHORT_OF_FULL_SCALE",
            allowedFile = "TodoProgress.kt",
            why = "填充几何只许一处——50% 被固定 padding 减成三成的事故来自几何写死"
        )
    }

    @Test
    fun `TodoProgress 自己确实存在这些判定 防白名单空转`() {
        // 断言太松 = 假绿：确认被豁免的文件真的包含这些形状，
        // 否则哪天 TodoProgress 改名/删函数，上面几条会静默变成"全绿但什么都没拦"。
        // 注意要去掉注释再判——这些形状在注释里也出现过（讲历史教训），只搜全文会被注释骗过。
        val self = File(repoRoot(), "app/src/main/java/io/github/m0use001/planability/data/TodoProgress.kt")
        assertTrue("TodoProgress.kt 不见了——单一事实源被删了？", self.isFile)
        val code = stripComments(self.readText(Charsets.UTF_8))
        for ((shape, what) in listOf(
            "percentDone >= 0" to "启用判断",
            "percentDone >= 100" to "满判据",
            "SHORT_OF_FULL_SCALE" to "填充空隙系数"
        )) {
            assertTrue(
                "TodoProgress.kt 的**代码**里找不到 $shape（$what）——白名单与实现脱节，上面几条断言在空转",
                code.contains(shape)
            )
        }
    }

    /** 去掉 // 行注释与 /* */ 块注释，只留代码 */
    private fun stripComments(text: String): String =
        text.replace(Regex("/\\*[\\s\\S]*?\\*/"), "")
            .lines()
            .joinToString("\n") { line ->
                val i = line.indexOf("//")
                // 粗略但够用：不处理字符串里含 // 的情况（本仓库这两处没有）
                if (i >= 0) line.substring(0, i) else line
            }
}
