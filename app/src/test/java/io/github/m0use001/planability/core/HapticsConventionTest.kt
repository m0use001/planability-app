package io.github.m0use001.planability.core

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触感约定（**源码级**断言）：钉住 `core/Haptics.kt` 里五条"改着改着就没了"的约定——
 * 顶≠底、成功≠错误、轻重档位真的拉开、HIG 映射表还在、每个 Kind 还有一句中文说明。
 *
 * 为什么用源码断言而不是运行时断言：
 * 1. `VibrationEffect` 的参数只在真机上才谈得上"手感"，单测跑的是 JVM，摸不到振动器；
 * 2. 这些约定全是**参数关系**（顶≠底、成功≠错误、映射表在不在），
 *    运行时要把两个分支都触发一遍才能比，而 `edgeHapticPair` 挂在懒列表状态上，
 *    测试里要造出"先离开尽头再回来"的时序，代价远高于读一眼源码。
 *
 * 背景（为什么值得钉）：HIG playing-haptics 的核心是**每种语义只有一种手感**，
 * 用户摸一两下就能反推"这是选中 / 这是不可逆 / 这是出错了"。
 * 一旦有人把顶和底调成同一档、或把成功和错误调成同一段波形，
 * 区分度就静默归零——代码照样编译、功能照样跑，只有用户觉得"震动好像都一样"。
 * 这类回归没有报错、没有日志，只有测试能拦。
 */
class HapticsConventionTest {

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

    private fun hapticsSource(): String {
        val f = File(
            repoRoot(),
            "app/src/main/java/io/github/m0use001/planability/core/Haptics.kt"
        )
        assertTrue("Haptics.kt 不见了（改名/挪包了？约定请跟着搬家）", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** `fun perform(kind: Kind)` 里 `when (kind)` 的函数体（各 Kind 的参数都在里面）。 */
    private fun whenBody(src: String): String {
        val fn = src.indexOf("fun perform(kind: Kind)")
        assertTrue("Haptics.kt 里找不到 perform(kind: Kind)（函数改名/被挪走了？）", fn >= 0)
        val w = src.indexOf("when (kind) {", fn)
        assertTrue("perform 里找不到 when (kind) {（分支被换成 if/else 了？）", w >= 0)
        val open = src.indexOf('{', w)
        // v19.47：改成**花括号配平**取值。
        // 旧写法按 `\n        }\n    }\n}` 这段固定缩进切尾，等于要求 perform 是整个文件
        // 最后一个函数；谁往它后面加一个函数，切出来就是空串，于是三条断言的报错全都写成
        // "结构变了、请同步调切法"——**误导**：约定一条没破，只是切法脆。
        // 配平只认括号：重排、函数换序、缩进变化都不受影响。
        var depth = 0
        var i = open
        while (i < src.length) {
            when (src[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return src.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("when (kind) { 的花括号没有配平（源码被截断了？）")
    }

    private fun branch(whenBody: String, from: String, to: String? = null): String {
        val b = whenBody.substringAfter(from, "")
        val out = if (to == null) b else b.substringBefore(to, "")
        assertTrue("找不到分支 `$from`（Kind 被删了还是改名了？）", out.isNotEmpty())
        return out
    }

    /** 表格行：取含指定概念的那一行（HIG 映射表的行；行首是 KDoc 的 ` * ` 前缀）。 */
    private fun higRow(src: String, concept: String): String {
        val line = src.lineSequence().firstOrNull {
            // 去掉 KDoc 的行首 ` * ` 再判断是不是表格行，免得被标题句误命中
            val t = it.trimStart().removePrefix("*").trimStart()
            it.contains(concept) && t.startsWith("|")
        }
        assertTrue(
            "HIG 映射表里找不到「$concept」这一行 —— 映射表是全 app 触感的唯一口径，" +
                "删了它，下一个改 Kind 的人就没有依据可循（哪个语义该用哪档全靠猜）",
            line != null
        )
        return line!!
    }

    // ---------------------------------------------------------------- 1) 顶 ≠ 底

    @Test
    fun `列表到顶与到底必须用不同手感（顶轻、底实）`() {
        val src = hapticsSource()
        val edge = src
            .substringAfter("private fun edgeHapticPair", "")
            .substringBefore("class HapticEngineFeedback", "")
        assertTrue("edgeHapticPair 不见了", edge.isNotEmpty())

        // 2026-11-03 竞争根修：两分支合并进单一 snapshotFlow collect（同帧合并的
        // end/scrolling 不再有记账竞争）——分支抽取按新的判定行切。
        val endBlock = edge.substringAfter("if (e && !prevEnd", "")
            .substringBefore("if (s && !prevStart")
        val startBlock = edge.substringAfter("if (s && !prevStart", "")

        assertTrue(
            "到顶应该用轻冲击 Kind.ImpactLight —— 顶是「回到开头」，" +
                "用重档会和「到底」撞在一起，用户分不清自己在哪一端",
            endBlock.contains("Kind.ImpactLight").not() &&
                startBlock.contains("Kind.ImpactLight")
        )
        assertTrue(
            "到底应该用重冲击 Kind.ImpactHeavy —— 底是「内容没了、撞到墙」，" +
                "要比到顶明显更实（HIG impactHeavy）",
            endBlock.contains("Kind.ImpactHeavy")
        )
        assertTrue(
            "顶和底用了同一个 Kind —— 同档就摸不出差别，" +
                "19 个 reachedEndHaptic 调用点会一起退化成'两端一个手感'",
            !startBlock.contains("Kind.ImpactHeavy") && !endBlock.contains("Kind.ImpactLight")
        )

        // 档位本身也要真的差开：真机实证 ColorOS 只区分硬件原语，顶=校准中档、底=HEAVY_CLICK
        val light = branch(whenBody(src), "Kind.ImpactLight, Kind.Rigid ->", "Kind.ImpactHeavy ->")
        val heavy = branch(whenBody(src), "Kind.ImpactHeavy ->", "Kind.Success ->")
        assertTrue("ImpactLight 应走校准后的中档原语", light.contains("createPredefined(mediumPrimitive)"))
        assertTrue("ImpactHeavy 应为 EFFECT_HEAVY_CLICK（重档）", heavy.contains("EFFECT_HEAVY_CLICK"))
        assertTrue(
            "顶/底的物理档位被调成一样了 —— 到顶与到底会重新变得摸不出区别",
            !light.contains("EFFECT_HEAVY_CLICK") && !heavy.contains("mediumPrimitive")
        )
    }

    // -------------------------------------------------------------- 2) 成功 ≠ 错误

    @Test
    fun `Success 与 Error 的波形参数必须不同`() {
        val src = hapticsSource()
        val wb = whenBody(src)
        val error = branch(wb, "Kind.Error ->")
        val success = branch(wb, "Kind.Success ->", "Kind.Error ->")

        assertTrue(
            "成功与错误的分支参数必须不同：成功是「短轻接长重、间隔长」的上扬两段，" +
                "错误是「等重两段、间隔短」的急促报警 —— 两者一旦调成一样，" +
                "用户摸到的'保存成功'和'保存失败'将是同一种手感（HIG notify 的意义就没了）",
            error.trim() != success.trim()
        )

        // 错误：等重两段 30ms + 40ms 短间隔，振幅拉平 255/255（平直、急促）——保留波形结构
        assertTrue("Error 应是两段等长 30ms：${error.trim()}", error.contains("longArrayOf(0, 30, 40, 30)"))
        assertTrue("Error 振幅应为等重的 255/255：${error.trim()}", error.contains("intArrayOf(0, 255, 0, 255)"))

        // 成功：EFFECT_DOUBLE_CLICK（"叮—咚"双击原语，真机实测结构被保留）
        assertTrue("Success 应为 EFFECT_DOUBLE_CLICK：${success.trim()}",
            success.contains("EFFECT_DOUBLE_CLICK"))

        // 结构差异逐项再钉一次：任一侧被"顺手统一"都会红
        assertTrue("Success 不该退回波形（会与 Error 抢同一种结构）", !success.contains("createWaveform"))
        assertTrue("Error 不该用 DOUBLE 原语（会与 Success 撞车）", !error.contains("EFFECT_DOUBLE_CLICK"))
    }

    // -------------------------------------------------------------- 3) 物理档位映射

    @Test
    fun `七个 Kind 到硬件原语的映射必须保持三档加双击（2026-10-25 真机调档）`() {
        val src = hapticsSource()
        val wb = whenBody(src)
        // 真机实证（OPPO PLK110/ColorOS 16）：自定义 oneshot/waveform 被归一成 OEM 预置段，
        // 只有硬件原语能保住区分度；且 Oplus 把 EFFECT_CLICK 映得比 EFFECT_TICK 还轻
        // （CLICK→35ms/1600 < TICK→44ms/STRONG），轻/中两档必须按厂牌校准。
        // 谁想改回自定义波形，先在真机上用 dumpsys vibrator_manager 复核五档还分得开再说。
        val selectTap = branch(wb, "Kind.Select, Kind.Tap ->", "Kind.ImpactLight, Kind.Rigid ->")
        val click = branch(wb, "Kind.ImpactLight, Kind.Rigid ->", "Kind.ImpactHeavy ->")
        val heavy = branch(wb, "Kind.ImpactHeavy ->", "Kind.Success ->")
        assertTrue("Select/Tap 应走校准后的轻档原语", selectTap.contains("createPredefined(lightPrimitive)"))
        assertTrue("ImpactLight/Rigid 应走校准后的中档原语", click.contains("createPredefined(mediumPrimitive)"))
        assertTrue("轻/中两档必须经厂牌校准（Oplus 上 TICK/CLICK 轻重与 AOSP 相反）",
            src.contains("lightPrimitive = if (oplus)") && src.contains("mediumPrimitive = if (oplus)"))
        assertTrue("ImpactHeavy 应为 EFFECT_HEAVY_CLICK（重档）", heavy.contains("EFFECT_HEAVY_CLICK"))
        assertTrue("ImpactHeavy 不该退回轻/中档", !heavy.contains("lightPrimitive") && !heavy.contains("mediumPrimitive"))
    }

    // -------------------------------------------------------------- 4) HIG 映射表

    @Test
    fun `HIG 映射表必须留在 Haptics_kt 顶部且每行指向正确的 Kind`() {
        val src = hapticsSource()

        assertTrue(
            "找不到「本项目 HIG 映射表」标题 —— 这张表是全 app 触感语义的唯一口径（HIG 概念 → Kind），" +
                "丢了它，'选择该用哪一档'就变成每个改文件的人各自拍脑袋",
            src.contains("本项目 HIG 映射表")
        )

        // 每个 HIG 概念所在行必须点名对应的 Kind —— 概念和 Kind 对不上比没有表更危险
        val rows = mapOf(
            "selection（选择）" to "`Select`",
            "impactLight（轻冲击）" to "`ImpactLight`",
            "impactMedium（中冲击）" to "`Rigid`",
            "impactHeavy（重冲击）" to "`ImpactHeavy`",
            "notify · success（成功）" to "`Success`",
            "notify · error（错误）" to "`Error`"
        )
        rows.forEach { (concept, kind) ->
            val row = higRow(src, concept)
            assertTrue(
                "HIG 映射表里「$concept」这一行没有指向 $kind —— 概念与 Kind 对不上，" +
                    "照着表写代码的人会用错手感",
                row.contains(kind)
            )
        }

        // 顶/底的语义也必须写在表里（否则"顶轻底实"只是某次提交里的口头约定）
        assertTrue("映射表要写明列表到顶用 ImpactLight", higRow(src, "impactLight").contains("到顶"))
        assertTrue("映射表要写明列表到底用 ImpactHeavy", higRow(src, "impactHeavy").contains("到底"))
    }

    // -------------------------------------------------------------- 5) 每种 Kind 有中文说明

    @Test
    fun `每个 Kind 都配了一句中文说明（何时用、为什么这样）`() {
        val src = hapticsSource()
        val enumBlock = src
            .substringAfter("enum class Kind {", "")
            .substringBefore("\n    }", "")
        assertTrue("找不到 enum class Kind", enumBlock.isNotEmpty())

        listOf("Tap", "Select", "Rigid", "ImpactLight", "ImpactHeavy", "Error", "Success")
            .forEach { name ->
                // 形如 `/** … */\n        Tap,` —— 注释必须紧贴枚举项，隔着一段距离的不算
                val doc = Regex("(?s)/\\*\\*.*?\\*/\\s*$name\\b").find(enumBlock)
                assertTrue(
                    "Kind.$name 没有自己的 KDoc 注释 —— " +
                        "每种手感都要写清「什么时候用、为什么是这个参数」，" +
                        "否则下一个人调参时不知道这档动了会影响谁",
                    doc != null
                )
                val text = doc!!.value
                assertTrue(
                    "Kind.$name 的注释里要有中文（说明用意，不是空壳 /** */）",
                    text.contains(Regex("[\\u4e00-\\u9fa5]"))
                )
            }
    }
}
