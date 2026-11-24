package io.github.m0use001.planability.ai

import io.github.m0use001.planability.ai.office.AiHealthCheck
import io.github.m0use001.planability.ai.office.OfficeText
import io.github.m0use001.planability.ai.ptl.WebSearch
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.delay
import java.io.File

/**
 * **AI 评估用例表**（2026-11-05，用户拍板：体检面板扩成 promptfoo 式）。
 *
 * ## 为什么照 promptfoo 的思路，而不是把 promptfoo 搬上手机
 *
 * promptfoo 是 Node CLI，手机跑不了；但它的内核只有三件事：
 * **用例（输入）→ 断言（可检查的输出条件）→ 成败表（含每条的证据）**。
 * 我们的场景不需要"换 provider 矩阵"——模型/工具是我们自己的，真正要防的是
 * **某条能力悄悄坏掉**（.ppt 读不了、搜索跑偏、镜像与库不一致）。
 *
 * 所以这里把它落成一张**只读、无副作用**的用例表：
 * - 每条用例 = 名字 + 分组 + 断言描述（给人看）+ 运行体（返回 PASS/FAIL 与证据）；
 * - 断言以 `assert:` 文案写出（字符串包含 / 正则 / 相关性），失败时给实测值——
 *   这正是 promptfoo 回执的形状，出了问题能直接对照；
 * - **全部只读**：不写用户数据、不改配置（记忆用例只做"镜像与库一致"的比对）。
 *
 * 与体检（[AiHealthCheck]）的分工：体检回答"通道通不通"（能不能读、能不能搜），
 * 评估用例回答"**行为对不对**"（该命中的命中了吗？失败契约守住了吗？数据一致吗？）。
 */
object EvalSuite {

    data class Result(
        val passed: Boolean,
        val evidence: String,
        val ms: Long
    )

    data class Case(
        val group: String,
        val name: String,
        /** 给人看的断言描述（promptfoo 风格，例：`assert: 结果含 neea.edu.cn`） */
        val assertion: String,
        /** 需要联网（离线时结果会 FAIL，UI 上要提示） */
        val network: Boolean = false,
        val run: suspend () -> Result
    )

    private suspend fun timed(block: suspend () -> Pair<Boolean, String>): Result {
        val t0 = System.currentTimeMillis()
        val (ok, ev) = runCatching { block() }
            .getOrElse { false to ("异常：" + (it.message ?: it.javaClass.simpleName)) }
        return Result(ok, ev, System.currentTimeMillis() - t0)
    }

    private fun contains(haystack: String, needle: String) =
        haystack.contains(needle, ignoreCase = true)

    /**
     * 内置用例表。分三组：
     * - 搜索：权威命中 + 多词内容页（用户踩过「不」字百科、曼恩卡车、首词站点卡三次）；
     *   **判据必须是独立 oracle**（结果里有没有那条内容页），不许拿 WebSearch 自己的质量闸当判据。
     * - 文档：三种格式往返 + 失败契约（空串是历史大坑）；
     * - 数据：日程查询可执行 + 记忆镜像与库一致（P5 资产镜像的回归）。
     */
    fun builtin(cacheDir: File): List<Case> {
        val sampleDocx = runCatching { AiHealthCheck.sampleDocx(cacheDir) }.getOrNull()
        val samplePptx = runCatching { AiHealthCheck.samplePptx(cacheDir) }.getOrNull()
        val sampleXlsx = runCatching { AiHealthCheck.sampleXlsx(cacheDir) }.getOrNull()

        return listOf(
            // ── 搜索 ──
            Case(
                "搜索", "权威站点命中",
                "assert: 「全国大学英语四六级考试官网」结果含 neea.edu.cn 或「四、六级」",
                network = true
            ) {
                timed {
                    val hits = WebSearch.search("全国大学英语四六级考试官网", 5)
                    val hit = hits.firstOrNull { h ->
                        h.url.contains("neea.edu.cn") || contains(h.title, "四、六级") ||
                            contains(h.title, "四六级") || contains(h.title, "CET")
                    }
                    (hit != null) to (if (hit != null) "命中：" + hit.title.take(40)
                    else "未命中，首条：" + (hits.firstOrNull()?.title?.take(40) ?: "无结果"))
                }
            },
            Case(
                "搜索", "多词 query 出内容页",
                "assert: 「星轨3 后星轨书 剧情」结果里有标题含「后星轨书」且非站点根页的条目",
                network = true
            ) {
                timed {
                    val q = "星轨3 后星轨书 剧情"
                    val hits = WebSearch.search(q, 6)
                    // 2026-11-08：这条用例原先断言 `WebSearch.isGoodEnough(hits, q)`——
                    // 拿被测的质量闸给自己当判据，闸瞎了用例一起绿（三次报障都漏在这）。
                    // 换成**独立 oracle**：结果里必须真的有一条讲这件事的内容页。
                    val hit = hits.firstOrNull { h ->
                        contains(h.title, "后星轨书") && !WebSearch.isRootPage(h.url)
                    }
                    (hit != null) to (if (hit != null) "命中：" + hit.title.take(40)
                    else "未命中，首条：" + (hits.firstOrNull()?.title?.take(40) ?: "无结果"))
                }
            },

            // ── 文档 ──
            Case("文档", "docx 往返抽取", "assert: 抽取结果含样本特征词「体检样本段落一」") {
                timed {
                    val f = sampleDocx ?: return@timed false to "样本生成失败"
                    val t = OfficeText.fromDocx(f, withIndex = true)
                    contains(t, "体检样本段落一") to ("读出 " + t.length + " 字")
                }
            },
            Case("文档", "pptx 往返抽取", "assert: 抽取结果含「体检样本幻灯片标题」") {
                timed {
                    val f = samplePptx ?: return@timed false to "样本生成失败"
                    val t = OfficeText.fromPptx(f, withIndex = true)
                    contains(t, "体检样本幻灯片标题") to ("读出 " + t.length + " 字")
                }
            },
            Case("文档", "xlsx 往返抽取", "assert: 抽取结果含「体检表格标题」（共享字符串表）") {
                timed {
                    val f = sampleXlsx ?: return@timed false to "样本生成失败"
                    val t = OfficeText.read(f)   // fromXlsx 是私有实现，read() 会按扩展名分派
                    contains(t, "体检表格标题") to ("读出 " + t.length + " 字")
                }
            },
            Case("文档", "失败契约：坏文件不回空串", "assert: 损坏的 .docx 抽取结果以「[」开头（可读提示）") {
                timed {
                    val bad = File(cacheDir, "eval-bad.docx")
                    bad.writeBytes(ByteArray(2048) { (it % 251).toByte() })
                    val t = io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDocText(bad)
                    bad.delete()
                    (t.trimStart().startsWith("[") && t.isNotBlank()) to
                        ("返回：" + t.take(60))
                }
            },

            // ── 数据 ──
            Case("数据", "日程查询可执行", "assert: 查询今天日程不抛异常（读操作）") {
                timed {
                    val today = AppTime.today()
                    val occ = Graph.plan.scheduleOccurrences(today, today)
                    true to ("今天 " + occ.size + " 场")
                }
            },
            Case("数据", "记忆镜像与库一致", "assert: 每个项目的 项目记忆/MEMORY.md 条数与库内条数一致") {
                timed {
                    val all = Graph.db.memoryDao().all()
                    val byProject = all.groupBy { it.projectId }
                    val mism = ArrayList<String>()
                    byProject.forEach { (pid, mems) ->
                        val dir = io.github.m0use001.planability.data.ProjectDirs.memoryDir(pid)
                        val idx = File(dir, "MEMORY.md")
                        // 0 条时按设计不写索引（本轮改过），跳过
                        if (mems.isEmpty()) return@forEach
                        if (!idx.isFile) {
                            mism += "项目" + pid + " 缺 MEMORY.md"
                        } else {
                            val n = idx.readText().lineSequence().count { it.startsWith("- [") }
                            if (n != mems.size) mism += "项目" + pid + " 索引 " + n + "≠库 " + mems.size
                        }
                    }
                    mism.isEmpty() to (if (mism.isEmpty())
                        byProject.size.toString() + " 个项目镜像一致"
                    else mism.take(3).joinToString("；"))
                }
            }
        )
    }

    data class Snapshot(
        val results: List<Pair<Case, Result>>
    ) {
        val passed get() = results.count { it.second.passed }
        val total get() = results.size
        fun summary(): String = passed.toString() + "/" + total + " 通过"
    }

    /** 逐条跑（顺序执行，逐条回调，UI 边跑边出结果）；用例之间留一点间隔避免打满网络 */
    suspend fun run(
        cases: List<Case>,
        onEach: (Case, Result) -> Unit
    ): Snapshot {
        val out = ArrayList<Pair<Case, Result>>()
        cases.forEachIndexed { i, c ->
            if (i > 0) delay(300)
            val r = runCatching { c.run() }.getOrElse {
                Result(false, "异常：" + (it.message ?: "未知"), 0)
            }
            out += c to r
            onEach(c, r)
        }
        return Snapshot(out)
    }
}
