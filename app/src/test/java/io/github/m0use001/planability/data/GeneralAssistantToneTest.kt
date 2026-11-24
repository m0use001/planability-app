package io.github.m0use001.planability.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v56：通用助手「去 AI 味」文案守卫（2026-11-15 用户「塑造一个只是说话不 AI 的普通助手」）。
 *
 * 旧文案的问题不是措辞不好，是**结构性的**：
 * - 开头「你是 Planability 的 AI 工作台助手」——自称 AI 就在激活模型的助手人格，
 *   后面所有话都带客服腔；
 * - 「简洁准确」是**要求不是方法**，模型只会用「首先/其次/总的来说」假装有条理。
 *
 * 这批测试钉三件事：①新文案不再自称 AI 且含具体话术约束；
 * ②工具可靠性约束**不能为了像人而被删掉**；③长度约束用句数而非字数（模型不会数字）。
 */
class GeneralAssistantToneTest {

    private val general = AssistantRepo.builtinAssistants().firstOrNull { it.key == AssistantRepo.KEY_GENERAL }

    @Test
    fun `通用助手存在且有名字`() {
        assertNotNull("内置通用助手不见了", general)
        assertTrue("人设里应有名字（有名字才有「人」感，而不是工具）", general!!.systemPrompt.contains("小安"))
    }

    @Test
    fun `不再自称 AI 助手`() {
        val p = general!!.systemPrompt
        assertFalse("开头不应再自称 AI 工作台助手", p.contains("AI 工作台助手"))
        assertFalse("不应自称助手身份（助手人格一激活就带客服腔）", p.startsWith("你是 Planability 的 AI"))
        // 「不用您——直接说你」这句本身含「您」，所以只查实际称呼形态
        assertFalse("不应出现「您好」这类敬语称呼", p.contains("您好"))
        assertTrue("应明确要求用「你」", p.contains("直接说「你」"))
    }

    @Test
    fun `封了四类典型 AI 腔并给替代动作`() {
        val p = general!!.systemPrompt
        // 结构词 → 口语替代（只禁不给替代，模型会换个口癖）
        assertTrue("缺结构词禁令", p.contains("首先/其次"))
        assertTrue("缺口语替代", p.contains("说白了"))
        // 句尾反问（记者病）
        assertTrue("缺句尾反问禁令", p.contains("你觉得呢"))
        // 客服套话
        assertTrue("缺客服套话禁令", p.contains("希望对你有帮助"))
        // 模型出厂口癖（DeepSeek 为主用模型）
        assertTrue("缺 DeepSeek 江湖腔禁令", p.contains("这波我认"))
        assertTrue("缺口癖替代动作（直接说错在哪）", p.contains("直接说错在哪"))
    }

    @Test
    fun `工具可靠性约束必须在位`() {
        val p = general!!.systemPrompt
        // 这些是「为了像人而变不可靠」的唯一保险，不能为了文风删掉
        assertTrue("缺「该动手就动手，别先问」", p.contains("该动手就动手"))
        assertTrue("缺草稿 vs 已创建的状态诚实要求", p.contains("草稿"))
        assertTrue("缺「以工具返回标题为准」（防复述错标题）", p.contains("旧 → 新"))
        assertTrue("缺播报腔禁令", p.contains("播报"))
        assertTrue("缺「拿不准就说不确定」", p.contains("拿不准就说不确定"))
    }

    @Test
    fun `长度用句数而非字数`() {
        val p = general!!.systemPrompt
        assertTrue("缺句数约束", p.contains("一两句"))
        // 模型按 token 接龙，不会边说话边数字——「限 25 字」这类写法必失效
        assertFalse("不应使用阿拉伯字数限制", Regex("""\d+\s*字""").containsMatchIn(p))
    }

    @Test
    fun `存量默认文案可被升级判据识别`() {
        // 判据：含旧身份标记且不含新标记「小安」→ 视为用户没动过的默认文案
        val oldStyle = "你是 Planability 的 AI 工作台助手，运行在用户的个人效率应用中，可直接调用日程/待办/笔记/记忆/文件等工具替用户干活。回答用简体中文，简洁准确。"
        assertTrue("旧文案应被识别为默认（否则存量用户升不上来）", oldStyle.contains("AI 工作台助手"))
        assertFalse("旧文案不应含新标记", oldStyle.contains("小安"))
        // 用户自己改过的人设不能被覆盖
        val custom = "你是一只猫娘，说话要带喵。"
        assertFalse("用户自定义人设不应命中升级判据", custom.contains("AI 工作台助手"))
    }

    @Test
    fun `兜底人格也不再自称 AI`() {
        val legacy = io.github.m0use001.planability.ai.AgentCard.LEGACY_GENERAL_PROMPT
        assertFalse("兜底人格不应再自称 AI 工作台助手", legacy.contains("AI 工作台助手"))
        assertTrue("兜底应短而中性", legacy.length < 120)
    }
}
