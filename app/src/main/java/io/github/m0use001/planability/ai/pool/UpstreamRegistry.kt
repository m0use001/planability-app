package io.github.m0use001.planability.ai.pool

import io.github.m0use001.planability.data.AiModel
import io.github.m0use001.planability.data.AiProvider
import io.github.m0use001.planability.data.ModelConfig

/**
 * 端内多账号池的**注册表**：把「同一个模型的多个账号」组织起来。
 *
 * ## 为什么复用现有 provider 模型而不再加一张表
 * 用户的诉求是「免费额度别被刷进惩罚态」，本质上就是**同一模型有多个 key**。
 * 现有 `ai_providers` + `ai_models` 已经能表达"多个账号指向同一模型名"
 * （比如两行 provider 都叫 AMD、各自一个 key、模型都写 `mimo-v2.6-flash`），
 * 再加 `routing_upstreams` 表就得处理迁移、备份兼容、双份真相——不值得。
 *
 * 约定（不改库、不改 UI 也能用；以后想给用户配 RPM 再加表）：
 * - **池键 = 模型名**（大小写不敏感）。同名模型出现在 N 个启用 provider 里 = N 个账号。
 * - 只有 1 个账号时不启用池逻辑（退化成本次请求直发，零额外开销）。
 * - 调度参数走各 provider 的默认档；需要调时改 [defaultParamsFor]。
 *
 * ## 与 Routability 的对应
 * `config.json` 的 `routes[model] = [upstreamId...]` 在这里由"同名模型"自动推出，
 * 用户不必维护一份额外的路由表。
 */
object UpstreamRegistry {

    /**
     * 从现有数据构建**每个模型名 → 上游池**。
     *
     * @param providers 全部 provider（含 key 已是明文——调用方负责 [io.github.m0use001.planability.data.AiProviderRepo.refresh] 之后传入）
     * @param models 全部模型行
     * @return 模型名（小写）→ 池；只有单账号的模型也会出现在结果里（调用方按 [UpstreamPool.all] 大小决定是否走池）
     */
    fun buildPools(providers: List<AiProvider>, models: List<AiModel>): Map<String, UpstreamPool> {
        val enabledProviders = providers.filter { it.enabled && it.baseUrl.isNotBlank() }
        if (enabledProviders.isEmpty()) return emptyMap()
        val byProvider = enabledProviders.associateBy { it.id }

        // 模型名（小写）→ 该名下所有账号的配置 + 各自的调度状态
        val grouped = LinkedHashMap<String, MutableList<Pair<AiProvider, AiModel>>>()
        models.filter { it.enabled }.forEach { m ->
            val p = byProvider[m.providerId] ?: return@forEach
            val key = m.name.trim().lowercase()
            if (key.isEmpty()) return@forEach
            grouped.getOrPut(key) { mutableListOf() } += p to m
        }
        if (grouped.isEmpty()) return emptyMap()

        return grouped.mapValues { (modelKey, rows) ->
            val states = rows.mapIndexed { idx, (p, _) ->
                // 上游 id 用 provider id，稳定且能在日志里对回设置页
                defaultParamsFor("p${p.id}", p.name, idx).let { params ->
                    UpstreamState(
                        id = params.id,
                        name = params.name,
                        priority = params.priority,
                        startRpm = params.startRpm,
                        rpmMin = params.rpmMin,
                        rpmMax = params.rpmMax,
                        cooldownBaseSec = params.cooldownBaseSec,
                        cooldownMaxSec = params.cooldownMaxSec,
                        dailyLimit = params.dailyLimit
                    )
                }
            }
            UpstreamPool(modelKey, states)
        }
    }

    /**
     * 池键 → 该账号的 [ModelConfig]（真正发请求用）。
     *
     * 池里记的是「上游 id」，要把它映射回具体那条 provider+model 的配置。
     * 因为池就是按 provider 建的，`p<providerId>` 能直接反查。
     */
    fun configFor(
        pool: UpstreamPool,
        upstreamId: String,
        providers: List<AiProvider>,
        models: List<AiModel>
    ): ModelConfig? {
        val pid = upstreamId.removePrefix("p").toLongOrNull() ?: return null
        val p = providers.firstOrNull { it.id == pid } ?: return null
        if (!p.enabled || p.baseUrl.isBlank()) return null
        // 该 provider 下与池键同名的模型（池键来自模型名）
        val m = models.firstOrNull {
            it.providerId == pid && it.enabled && it.name.trim().lowercase() == pool.modelKey
        } ?: return null
        return ModelConfig(
            provider = "p" + p.id,
            baseUrl = p.baseUrl,
            apiKey = p.apiKey,
            modelName = m.name,
            enabled = true,
            authStyle = p.authStyle,
            apiFormat = p.apiFormat
        )
    }

    /** 默认调度参数（对照 Routability config.json 里 NVIDIA=10 / 魔搭=40 / AMD=50 的量级） */
    internal data class Params(
        val id: String,
        val name: String,
        val priority: Int,
        val startRpm: Double,
        val rpmMin: Double,
        val rpmMax: Double,
        val cooldownBaseSec: Double,
        val cooldownMaxSec: Double,
        val dailyLimit: Int
    )

    /**
     * 按 provider 名猜一个保守的调度档。
     *
     * 免费通道的真实配额各家不同且会变，这里取**保守起始值**：宁可一开始慢一点，
     * 也不要一上来就把新账号刷进冷却（那正是 Routability 存在的理由）。
     * 账号变多时（idx > 0）优先级依次后移，让主账号先用。
     */
    internal fun defaultParamsFor(id: String, name: String, idx: Int): Params {
        val n = name.lowercase()
        val (start, max, daily) = when {
            n.contains("amd") || n.contains("radeon") -> Triple(3.0, 8.0, 200)
            n.contains("nvidia") || n.contains("nim") -> Triple(6.0, 12.0, 0)
            n.contains("modelscope") || n.contains("魔搭") -> Triple(3.0, 8.0, 200)
            n.contains("local") || n.contains("本地") || n.contains("127.0.0.1") -> Triple(30.0, 60.0, 0)
            else -> Triple(4.0, 10.0, 0)
        }
        return Params(
            id = id, name = name,
            priority = 40 + idx * 10,
            startRpm = start, rpmMin = 1.0, rpmMax = max,
            cooldownBaseSec = 60.0, cooldownMaxSec = 3600.0,
            dailyLimit = daily
        )
    }

    /**
     * 全局池实例缓存（进程级）。
     *
     * 池里带**运行时状态**（滑动窗口、RPM、冷却），必须跨请求复用——
     * 每次请求重建一个池等于把学到的限额全忘了，RPM 永远停在起始值，
     * 冷却也不会生效（这是最容易写错的地方）。
     */
    private var cached: Map<String, UpstreamPool> = emptyMap()
    private var cachedSignature: String = ""

    /** 重建（数据变化时调用；签名没变就复用，保住运行时状态） */
    @Synchronized
    fun pools(providers: List<AiProvider>, models: List<AiModel>): Map<String, UpstreamPool> {
        val sig = signature(providers, models)
        if (sig != cachedSignature) {
            cached = buildPools(providers, models)
            cachedSignature = sig
        }
        return cached
    }

    /** 结构签名：只有「账号集合/地址/模型名」变了才重建，key 换了不影响调度状态 */
    private fun signature(providers: List<AiProvider>, models: List<AiModel>): String =
        buildString {
            providers.filter { it.enabled }.sortedBy { it.id }.forEach { p ->
                append(p.id).append(':').append(p.baseUrl).append(';')
            }
            append('|')
            models.filter { it.enabled }.sortedBy { it.id }.forEach { m ->
                append(m.id).append(':').append(m.providerId).append(':')
                    .append(m.name.lowercase()).append(';')
            }
        }

    /** 测试/诊断用：清掉缓存 */
    @Synchronized
    fun resetForTest() {
        cached = emptyMap()
        cachedSignature = ""
    }
}
