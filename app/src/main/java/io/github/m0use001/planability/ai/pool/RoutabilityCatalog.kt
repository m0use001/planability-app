package io.github.m0use001.planability.ai.pool

/**
 * 2026-11-02：**Routability 虚拟供应商**的端内配置表。
 *
 * 完整复刻本机 `D:\◆\Routability\config.json` 的 routes / modelMap，
 * 但按用户明确要求做了两处裁剪：
 * 1. **不含 codebuddy / zen**——这两条是"伪装官方客户端"的渠道（WAF 指纹、UA 逐字匹配、
 *    会话 id 编码校验），端内复刻既脆弱又等于替第三方免费池刷量，留在电脑网关里。
 * 2. **只收 1M 上下文的模型**（用户指定）。`deepseek-v4.1-flash` / `step-5-preview`
 *    只挂在 codebuddy 上，排除该上游后候选为空 → 不收。
 *
 * 用户视角：设置页只有「Routability」一家供应商 + `auto` 一个模型（外加 7 个显式模型），
 * 背后是 NVIDIA×2 / 魔搭×2 / OpenRouter / Cloudflare / 华为×2 / AMD 共 9 个账号在轮询。
 *
 * ## 维护
 * 这是**快照**，不是实时读取——网关配置在电脑上改了，这里要跟着改。
 * 每个上游的 key 不放源码，走 `local.properties`（见 [UpstreamKeys]）。
 */
internal object RoutabilityCatalog {

    /** 供应商在设置页的显示名（用户只会看到这一家） */
    const val VENDOR_NAME = "Routability"

    /** auto 模型名（对应网关的 `auto` + autoTarget） */
    const val AUTO_MODEL = "auto"

    /** auto 落到各上游时用的目标模型（网关的 autoTarget） */
    private const val AUTO_TARGET = "z-ai/glm-5.3-flash"

    /** 只收 1M 上下文（用户指定；262K/192K 的一律不进） */
    private val ONE_M = 1048576L

    /**
     * 一个上游账号：地址 + key 来源 + 该上游的 模型别名→真实名 映射。
     *
     * [keyProp] 是 `local.properties` 里的属性名；空串表示该账号没配 key（跳过）。
     */
    internal data class UpstreamSpec(
        val id: String,
        val displayName: String,
        val baseUrl: String,
        val keyProp: String,
        /** 上游真实模型名映射：对外别名 → 该上游的真实 id */
        val modelMap: Map<String, String>,
        /** 该上游对某模型的真实上下文（覆盖全局） */
        val modelLimits: Map<String, Long> = emptyMap(),
        /** 调度优先级（小的先选，照抄 config.json） */
        val priority: Int,
        val startRpm: Double,
        val rpmMin: Double,
        val rpmMax: Double,
        val cooldownBaseSec: Double,
        val cooldownMaxSec: Double,
        val dailyLimit: Int = 0
    )

    /**
     * 模型清单：对外模型名 → 该模型可用的上游（按顺序 = 优先级）。
     *
     * `auto` 的候选链 = 网关 routes.auto 去掉 codebuddy/zen 后的顺序。
     */
    internal val MODEL_ROUTES: Map<String, List<String>> = linkedMapOf(
        // auto：网关 autoTarget=z-ai/glm-5.3-flash，候选链取 routes.auto 的可用子集
        AUTO_MODEL to listOf(
            "nvidia1", "nvidia2", "huawei", "huawei2", "modelscope", "modelscope2", "amd"
        ),
        "deepseek-ai/deepseek-v4.1-flash" to listOf("huawei2", "nvidia1", "nvidia2", "amd"),
        "z-ai/glm-5.3" to listOf("nvidia1", "nvidia2", "huawei"),
        "z-ai/glm-5.3-flash" to listOf("nvidia1", "nvidia2", "modelscope", "modelscope2", "amd"),
        "stepfun-ai/step-5-preview" to listOf("nvidia1", "nvidia2"),
        "hw-glm-5.3" to listOf("huawei"),
        "hw-deepseek-v4.1-flash" to listOf("huawei2"),
        "deepseek-v4-flash" to listOf("amd")
    )

    /**
     * 每个 1M 模型在**全局**的上下文（照抄 config.json 的 modelInfo/modelLimits）。
     * 上游有自己的 modelLimits 时以上游为准。
     */
    internal val MODEL_CONTEXT: Map<String, Long> = linkedMapOf(
        AUTO_MODEL to ONE_M,
        "deepseek-ai/deepseek-v4.1-flash" to ONE_M,
        "z-ai/glm-5.3" to ONE_M,
        "z-ai/glm-5.3-flash" to ONE_M,
        "stepfun-ai/step-5-preview" to ONE_M,
        "hw-glm-5.3" to ONE_M,
        "hw-deepseek-v4.1-flash" to ONE_M,
        "deepseek-v4-flash" to ONE_M
    )

    /** 支持视觉的模型（图里有「视觉」标的；auto 视上游而定，保守不标） */
    internal val VISION_MODELS: Set<String> = setOf(
        "deepseek-ai/deepseek-v4.1-flash", "z-ai/glm-5.3-flash", "stepfun-ai/step-5-preview"
    )

    /**
     * 上游清单（key 走 local.properties 的属性名）。
     *
     * 顺序与 config.json 一致；priority 也用原值（NVIDIA 10 / 魔搭 40 / AMD 50 …），
     * 保证"优先用免费额度最宽松的通道"这个隐含意图不变。
     */
    internal fun upstreamSpecs(): List<UpstreamSpec> = listOf(
        UpstreamSpec(
            id = "nvidia1", displayName = "NVIDIA 账号1",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyProp = "nvidia_key",
            modelMap = emptyMap(),   // NVIDIA 用别名原名直发（网关也是 modelMap 为空）
            priority = 10, startRpm = 6.0, rpmMin = 2.0, rpmMax = 12.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 3600.0
        ),
        UpstreamSpec(
            id = "nvidia2", displayName = "NVIDIA 账号2",
            baseUrl = "https://integrate.api.nvidia.com/v1",
            keyProp = "nvidia_key2",
            modelMap = emptyMap(),
            priority = 10, startRpm = 6.0, rpmMin = 2.0, rpmMax = 12.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 3600.0
        ),
        UpstreamSpec(
            id = "huawei", displayName = "华为 ModelArts",
            baseUrl = "https://api.modelarts-maas.com/v1",
            keyProp = "huawei_key",
            modelMap = mapOf(
                "hw-glm-5.3" to "glm-5.3",
                "hw-glm-5.2" to "glm-5.2",
                "hw-glm-5.1" to "glm-5.1",
                "z-ai/glm-5.3" to "glm-5.3"
            ),
            priority = 25, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 900.0
        ),
        UpstreamSpec(
            id = "huawei2", displayName = "华为 ModelArts v2",
            baseUrl = "https://api.modelarts-maas.com/v2",
            keyProp = "huawei_key2",
            modelMap = mapOf(
                "hw-deepseek-v4.1-flash" to "deepseek-v4.1-flash",
                "deepseek-ai/deepseek-v4.1-flash" to "deepseek-v4.1-flash"
            ),
            priority = 25, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 900.0
        ),
        UpstreamSpec(
            id = "modelscope", displayName = "ModelScope 魔搭",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            keyProp = "modelscope_key",
            modelMap = mapOf(
                "z-ai/glm-5.3-flash" to "ZhipuAI/GLM-5.3-Flash",
                "qwen3.8-flash-next" to "Qwen/Qwen3.8-Flash-Next"
            ),
            modelLimits = mapOf("z-ai/glm-5.3-flash" to 262144L),
            priority = 40, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 600.0, dailyLimit = 200
        ),
        UpstreamSpec(
            id = "modelscope2", displayName = "ModelScope 魔搭账号2",
            baseUrl = "https://api-inference.modelscope.cn/v1",
            keyProp = "modelscope_key2",
            modelMap = mapOf(
                "z-ai/glm-5.3-flash" to "ZhipuAI/GLM-5.3-Flash",
                "qwen3.8-flash-next" to "Qwen/Qwen3.8-Flash-Next"
            ),
            modelLimits = mapOf("z-ai/glm-5.3-flash" to 262144L),
            priority = 40, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 600.0, dailyLimit = 200
        ),
        UpstreamSpec(
            id = "openrouter", displayName = "OpenRouter",
            baseUrl = "https://openrouter.ai/api/v1",
            keyProp = "openrouter_key",
            modelMap = mapOf(
                "inkling-small" to "thinkingmachines/inkling-small:free",
                "dots-3-note" to "dots-studio/dots-3-note-preview:free"
            ),
            priority = 60, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 900.0
        ),
        UpstreamSpec(
            id = "cloudflare", displayName = "Cloudflare Workers AI",
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/87ea26e48c2ce1ae6f70fd6f0d1cb2b2/ai/v1",
            keyProp = "cloudflare_key",
            modelMap = mapOf(
                "cf-r1-32b" to "@cf/deepseek-ai/deepseek-r1-distill-qwen-32b",
                "llama-3.1-8b" to "@cf/meta/llama-3.1-8b-instruct-fp8",
                "gpt-oss-120b" to "@cf/openai/gpt-oss-120b"
            ),
            priority = 60, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 900.0
        ),
        UpstreamSpec(
            id = "amd", displayName = "AMD Radeon Cloud",
            baseUrl = "https://developer.amd.com.cn/radeon/api/v1",
            keyProp = "amd_key",
            modelMap = mapOf(
                "z-ai/glm-5.3-flash" to "GLM-5.3-Flash",
                "qwen3.8-flash-next" to "Qwen3.8-Flash-Next",
                "mimo-v2.6-flash" to "MiMo-V2.6-Flash",
                "deepseek-ai/deepseek-v4.1-flash" to "DeepSeek-V4.1-Flash",
                "qwen3.8-27b" to "Qwen3.8-27B",
                "gemma-4-31b-it" to "Gemma-4-31B-IT",
                "deepseek-v4-flash" to "DeepSeek-V4-Flash",
                "minicpm-v-4.7-35b-a3b" to "MiniCPM-V-4.7-35B-A3B"
            ),
            modelLimits = mapOf(
                "z-ai/glm-5.3-flash" to 262144L,
                "deepseek-ai/deepseek-v4.1-flash" to 1048576L,
                "mimo-v2.6-flash" to 1048576L,
                "deepseek-v4-flash" to 1048576L
            ),
            priority = 50, startRpm = 3.0, rpmMin = 1.0, rpmMax = 8.0,
            cooldownBaseSec = 60.0, cooldownMaxSec = 900.0
        )
    )

    /** auto 请求落到某上游时的目标模型（网关 `target_model` 同义） */
    internal fun autoTargetFor(@Suppress("UNUSED_PARAMETER") upId: String): String = AUTO_TARGET

    /** 某上游对某模型的真实名（没配映射就是原名） */
    internal fun realModelName(up: UpstreamSpec, alias: String): String =
        if (alias == AUTO_MODEL) up.modelMap[AUTO_TARGET] ?: AUTO_TARGET
        else up.modelMap[alias] ?: alias

    /** 某上游跑该模型的真实上下文 */
    internal fun contextFor(up: UpstreamSpec, alias: String): Long {
        val real = realModelName(up, alias)
        return up.modelLimits[real] ?: up.modelLimits[alias]
        ?: MODEL_CONTEXT[alias] ?: MODEL_CONTEXT[AUTO_TARGET] ?: 32768L
    }
}
