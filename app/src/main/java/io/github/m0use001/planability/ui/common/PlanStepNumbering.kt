package io.github.m0use001.planability.ui.common

/**
 * 计划卡 / 工作流菜单里**步骤名的序号口径**（2026-11-19 用户实报）。
 *
 * 症状（用户截图）：模型在计划组件里写的步骤名是「3.8 反导数：定义与公式表」，
 * 渲染端用正则剥行首序号 `^\s*\d+\s*[.、．)）]?\s*` → 把「3.」当成列表序号剥掉，
 * 剩下的「8 反导数…」再配上卡片自己的自动编号「1.」→ 屏幕上成了「1. 8 反导数」。
 * **模型输出的章节号「3.」被吃掉了。**
 *
 * 口径（本文件唯一真源，渲染端与工作流菜单共用）：
 * - 「1.」「2、」「3)」「1 」这类**列表序号**：剥掉，卡片照旧渲染自己的 1. 2. 3.；
 * - 「3.8」「2.9」这类**小数/章节号**（分隔符后紧跟数字）：**原样保留**，
 *   并且**不再渲染卡片自己的自动序号**——模型给的是章节号，再套一个「1.」只会更乱；
 * - 「2024 年的计划」这类**数字不是序号**（位数 ≥3 且无分隔符）：不剥，保留原文。
 */
data class PlanStepLabel(
    /** 是否渲染卡片自己的自动序号（1. 2. 3.）。 */
    val showIndex: Boolean,
    /** 最终显示的步骤名。 */
    val text: String,
)

/**
 * 行首序号：`数字` +（可选）分隔符 +（可选）空白。
 * 分组：1=数字，3=分隔符。
 */
private val STEP_NUM_PREFIX = Regex("""^\s*(\d+)(\s*([.、．)）]))?(\s*)""")

/** 无分隔符时，只有 1~2 位数字才当序号（"1 备份" 是序号，"2024 年计划" 不是）。 */
private const val BARE_INDEX_MAX_DIGITS = 2

/**
 * 把模型写的步骤名解析成「要不要自动序号 + 显示什么」。
 *
 * 纯函数、无 Compose 依赖，JVM 可直接单测（[PlanStepNumberingTest]）。
 */
fun planStepLabel(rawName: String): PlanStepLabel {
    val name = rawName.trim()
    if (name.isEmpty()) return PlanStepLabel(showIndex = false, text = name)
    val m = STEP_NUM_PREFIX.find(name) ?: return PlanStepLabel(showIndex = true, text = name)
    val digits = m.groupValues[1]
    val delim = m.groupValues[3]
    val rest = name.substring(m.range.last + 1)
    if (delim.isEmpty()) {
        // 无分隔符："1 备份"（序号） vs "2024 年的计划"（不是序号）
        return if (digits.length <= BARE_INDEX_MAX_DIGITS && rest.isNotBlank()) {
            PlanStepLabel(showIndex = true, text = rest)
        } else {
            PlanStepLabel(showIndex = true, text = name)
        }
    }
    // 有分隔符：分隔符后紧跟数字 = 小数/章节号（"3.8 反导数"）→ 不剥、不加自动序号
    if (rest.isNotEmpty() && rest[0].isDigit()) {
        return PlanStepLabel(showIndex = false, text = name)
    }
    // 真的是列表序号："1. 备份" → 剥掉，卡片自己编号
    return PlanStepLabel(showIndex = true, text = rest.ifBlank { name })
}
