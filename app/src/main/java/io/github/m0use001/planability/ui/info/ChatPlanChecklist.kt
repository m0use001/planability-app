package io.github.m0use001.planability.ui.info

import io.github.m0use001.planability.data.ChatMessage

/**
 * v53 工作流胶囊：从会话消息里解析 **AI 给出的计划**（Markdown 勾选清单）。
 *
 * 用户在笔记本里要的是「AI 给出的计划的具体推进进度」。AI 排计划最稳定的形态就是
 * `- [ ] / - [x]` 勾选列表——它不需要新表、不需要工具协议，模型自己就会写；
 * 每轮 AI 重发计划时以**最新一条**为准（旧的自然作废）。
 *
 * 口径：
 * - 只看 assistant 消息；
 * - 行首（允许缩进）`-` 或 `*` + `[ ]`/`[x]`；
 * - 一条消息里凑齐 ≥2 个勾选项才算「计划」——单个 checkbox 多半是正文里的例子；
 * - 代码块里的假清单不排除（代价可接受：假清单也是模型写给用户看的进度语义）。
 */
data class PlanCheckItem(val done: Boolean, val text: String)

data class PlanChecklist(
    val messageId: Long,
    val at: Long,
    val items: List<PlanCheckItem>
) {
    val doneCount: Int get() = items.count { it.done }
    val totalCount: Int get() = items.size
}

private val CHECK_RE = Regex("""^\s*[-*]\s\[([ xX])]\s+(.*)$""")

/**
 * 2026-11-12 用户拍板：工作流二级菜单只吃 `- [ ]` 勾选清单是漏的——AI 排计划更常用
 * **计划组件**（```plan JSON，steps 是数字序号），那种计划在菜单里看不见。口径：
 * - 计划组件消息也解析为 PlanChecklist，步骤全部视为未完成（组件本身没有完成态，
 *   完成圈在菜单里是可选展示，不承载状态）；
 * - 想改菜单里的计划，就让 AI 重发一个计划组件（与勾选清单同一条「最新覆盖旧」的规则）。
 *
 * 2026-11-15 用户「模型调用了计划组件，但工作流中的计划没有同步更新」：
 * 原来这里用 ```` ```plan ```` 正则直接扫**原文**，而正文渲染走的是
 * [ChatComp.parse] —— 它先过 [normalizeComponentMarkup] 把模型吐的
 * `<｜DSML｜ plan>…</｜DSML｜>` 标签翻译成 ```` ```plan ```` 围栏。
 * 于是同一条消息：聊天里 plan 卡正常显示，工作流面板却解析不到（正则看不见 DSML 原文）。
 * 修法：工作流也走 [ChatComp.parse]，与渲染端**同一条卡片提取链路**——
 * 正文能渲染出来的 plan 卡，工作流就能解析到（DSML / 单行写法 / 未闭合围栏全覆盖）。
 */

/**
 * 步骤名的序号口径统一走 [planStepLabel]（2026-11-19 用户实报「自动编号把模型输出吃了」：
 * 步骤名「3.8 反导数」里的「3.」被当序号剥掉）。渲染端（[PlanCard]）与本处共用同一口径，
 * 免得「聊天里显示 3.8、工作流菜单里显示 8」两副面孔。
 */
private fun stepNameText(raw: String): String =
    io.github.m0use001.planability.ui.common.planStepLabel(raw).text

fun parsePlanChecklists(messages: List<ChatMessage>): List<PlanChecklist> =
    messages.asSequence()
        .filter { it.role == "assistant" && it.content.isNotBlank() }
        .flatMap { m ->
            val checklist = m.content.lines().mapNotNull { line ->
                val g = CHECK_RE.find(line)?.groupValues ?: return@mapNotNull null
                PlanCheckItem(done = g[1].isNotBlank(), text = g[2].trim())
            }
            // 计划组件块：用 ChatComp.parse 提取（与正文渲染同一条卡片提取链路，
            // 正确处理嵌套 JSON / 单行写法 / 未闭合围栏）。取本条消息里最后一个 plan 卡。
            val cardSteps = ChatComp.parse(m.content)
                .filterIsInstance<ChatSegment.Card>()
                .filter { it.type == "plan" }
                .lastOrNull()
                ?.json?.optJSONArray("steps")?.let { arr ->
                    (0 until arr.length()).mapNotNull { i ->
                        arr.optJSONObject(i)?.optString("name")
                            ?.let { stepNameText(it) }
                            ?.takeIf { it.isNotBlank() }
                            ?.let { PlanCheckItem(done = false, text = it) }
                    }
                }.orEmpty()
            // 两者都凑不齐 ≥2 项就不算计划（单条目是举例/孤步）
            when {
                checklist.size >= 2 -> listOf(PlanChecklist(m.id, m.createdAt, checklist))
                cardSteps.size >= 2 -> listOf(PlanChecklist(m.id, m.createdAt, cardSteps))
                else -> emptyList()
            }
        }
        .sortedBy { it.at }
        .toList()

/** 聚合口径：以**最新一条计划**为准（旧计划的勾选状态会被新一轮整体覆盖） */
fun latestPlan(messages: List<ChatMessage>): PlanChecklist? =
    parsePlanChecklists(messages).lastOrNull()
