package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-07 检索触发策略（Step 1）的源码级回归。
 *
 * 本仓无 Robolectric，`ContextEngine.assemble` 要 Graph——照 [ContextCostTest] 的路子
 * 钉源码约定：硬规则在 toolsEnabled 段内、易变尾部之前（保 provider 前缀缓存），
 * 且强制 kb_ask 优先 + 没覆盖明说没有（不许编）。
 *
 * 为什么钉源码而不是行为：模型是否真调 kb_ask 取决于云端/本地模型本身，单测无法稳定复现；
 * 但「提示词里有没有这条硬约束」是可钉的，且是防线第一道——少了它，模型连引导都没有。
 */
class ContextEngineRetrievalRuleTest {

    private fun source(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" },
            "app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        assertTrue("ContextEngine.kt 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun `检索硬规则在 toolsEnabled 段内且提及 kb_ask 与拒答`() {
        val s = source()
        val ruleIdx = s.indexOf("检索策略：用户问到自己的笔记")
        assertTrue("检索硬规则被删了（kb_ask 必须从'想用才用'升级为硬策略）", ruleIdx >= 0)
        // 规则三要素：先取证、没覆盖明说没有、不许编——任一缺失防线就漏
        val after = s.substring(ruleIdx)
        assertTrue("规则丢了'先 kb_ask 检索证据'", after.contains("kb_ask 检索证据"))
        assertTrue("规则丢了'没覆盖明说没有'（拒答闸）", after.contains("笔记里没有相关记录"))
        assertTrue("规则丢了'不许编造'", after.contains("不许编造"))
        // note_search / library_search 的分流也带上（否则模型只会 kb_ask 一个口子）
        assertTrue("规则丢了 note_search 分流", after.contains("note_search"))
        assertTrue("规则丢了 library_search 分流", after.contains("library_search"))
    }

    @Test
    fun `检索硬规则在易变尾部之前（不破坏 provider 前缀缓存）`() {
        val s = source()
        val ruleIdx = s.indexOf("检索策略：用户问到自己的笔记")
        val tsIdx = s.indexOf("当前时间：")
        assertTrue("定位失败：规则/$ruleIdx 时间/$tsIdx", ruleIdx >= 0 && tsIdx >= 0)
        assertTrue(
            "检索规则挪到了易变尾部之后——每轮都会变的前缀让 provider 前缀缓存失效（ContextCostTest 同口径）",
            ruleIdx < tsIdx
        )
    }

    @Test
    fun `检索硬规则落在工具门内（关工具时不该出现 kb_ask 引导）`() {
        val s = source()
        val ruleIdx = s.indexOf("检索策略：用户问到自己的笔记")
        // v55：门从 if (toolsEnabled) 改为 if (toolsEnabledByCard)（= 全局能力开关 × 卡策略），
        // 语义不变——创作卡关工具时同样不该出现 kb_ask 引导。
        val blockStart = s.lastIndexOf("if (toolsEnabledByCard)", ruleIdx)
        assertTrue("检索规则不在任何工具门内（toolsEnabledByCard）", blockStart >= 0)
        // 往后找该块收尾段——assemble 里工具块以 .append 收尾，附近应有内置智能体/ask_expert
        val tail = s.substring(ruleIdx, ruleIdx + 1200)
        assertTrue("检索规则段疑似飘出了工具门块（没紧跟工具规则上下文）",
            tail.contains("内置智能体") || tail.contains("ask_expert"))
    }

    @Test
    fun `记忆卫生硬规则在位 - 作废必先作废旧条目 + 设定卡权威载体`() {
        // 2026-11-12 ◆（用户三轮「我不是早说了…作废了吗？？」后定性「agent 没提醒
        // 你整理更新记忆文件」）：AI 只会 memory_add 新条目/作废声明，旧条目没人作废；
        // 设定卡.md 躺在项目文件里没人读。这两条硬规则是防线第一道，被删就先红在这里。
        val s = source()
        val ruleIdx = s.indexOf("记忆卫生（作废/更改设定时）")
        assertTrue("记忆卫生硬规则被删了", ruleIdx >= 0)
        // 同检索策略：必须在易变尾部之前（保 provider 前缀缓存）
        val tsIdx = s.indexOf("当前时间：")
        assertTrue("定位失败：规则/$ruleIdx 时间/$tsIdx", ruleIdx >= 0 && tsIdx >= 0)
        assertTrue("记忆卫生规则挪到了易变尾部之后——前缀缓存会失效", ruleIdx < tsIdx)
        val after = s.substring(ruleIdx)
        assertTrue("规则丢了 memory_invalidate 作废动作", after.contains("memory_invalidate"))
        assertTrue("规则丢了「只新增作废声明不算完成」闸", after.contains("作废声明"))
        assertTrue("规则丢了设定卡权威载体", after.contains("以项目文件里的权威文件为准"))
        assertTrue("规则丢了设定卡指针登记", after.contains("memory_add 登记一条指针"))
    }
}
