package io.github.m0use001.planability.ai.pool

import io.github.m0use001.planability.ai.StreamEvent
import io.github.m0use001.planability.data.ModelConfig
import io.github.m0use001.planability.data.ROutabilitySeed
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * **Routability 路由发送**：把本机网关 `D:\◆\Routability` 的调度算法用在 App 里。
 *
 * ## 用户视角
 * 设置页只有「Routability」一家供应商 + `auto`（以及 7 个 1M 模型）。选 `auto` 时，
 * 下面这段代码按网关的 `routes.auto` 候选链（NVIDIA×2 → 华为×2 → 魔搭×2 → AMD）
 * 挑一个能发的账号；429 冷却指数退避、5xx 降速软降级、健康优先、按优先级+最久未用调度。
 *
 * ## 与 `ai_providers` 表的关系
 * Routability 是**虚拟供应商**：上游账号存在 settings KV（[ROutabilitySeed.loadUpstreams]），
 * 模型→上游的映射来自 [RoutabilityCatalog.MODEL_ROUTES]——与 providers 表无关，
 * 所以设置页只看到一行。
 *
 * ## 何时直发（零开销）
 * 非 Routability 模型、KV 里没有上游 key、该模型只有一个可用上游——三种情况都直接
 * 调 [send]，不包 Flow、不碰状态机，行为与加这个功能之前完全一致。
 */
object RoutedStream {

    /** 一次发送在池上的最长排队时间（秒）：超过就报错，让上层决定重试 */
    private const val MAX_QUEUE_SEC = 120.0

    /** 进程级池缓存：池里带运行时状态（滑动窗口/RPM/冷却），**必须跨请求复用** */
    private var poolCache: MutableMap<String, UpstreamPool> = mutableMapOf()

    /** 该模型名是否走 Routability 路由（供 UI/诊断显示） */
    fun isRoutabilityModel(modelName: String): Boolean =
        modelName.trim().lowercase() in RoutabilityCatalog.MODEL_ROUTES.keys

    /**
     * 发送。Routability 模型走池，其余走 [fallback] 直发。
     *
     * @param modelAlias 请求的模型名（如 `auto`）
     * @param fallback 直发用的配置（非池情况，以及配置解析失败时兜底）
     * @param send 真正的发送函数（调用方注入 `SseTransport.streamChat` 的部分应用，
     *             便于单测替身，本层不直接依赖网络实现）
     */
    fun streamChatPooled(
        modelAlias: String,
        fallback: ModelConfig,
        temperature: Double,
        tools: org.json.JSONArray?,
        thinking: String,
        send: (ModelConfig) -> Flow<StreamEvent>
    ): Flow<StreamEvent> {
        val key = modelAlias.trim().lowercase()
        val routes = RoutabilityCatalog.MODEL_ROUTES[key]
        // 不是 Routability 的模型 → 原样直发
        if (routes == null || !ROutabilitySeed.isEnabledBlocking()) return send(fallback)

        val upstreamKeys = ROutabilitySeed.loadUpstreamsBlocking()
        if (upstreamKeys.isEmpty()) return send(fallback)

        val specsById = RoutabilityCatalog.upstreamSpecs().associateBy { it.id }
        // 只保留「路由里有 + key 已配」的上游
        val usable = routes.mapNotNull { id ->
            val spec = specsById[id] ?: return@mapNotNull null
            val k = upstreamKeys[id] ?: return@mapNotNull null
            spec to k
        }
        if (usable.isEmpty()) return send(fallback)
        if (usable.size == 1) {
            // 单账号：直发（换成该上游的真实地址与模型名）
            val (spec, k) = usable.first()
            return send(configFor(spec, k, key))
        }

        val pool = poolFor(key, usable.map { it.first })

        return flow {
            val t0 = now()
            var tried = mutableSetOf<String>()
            var lastError: String? = null
            var queueWait = 0.0

            while (true) {
                val t = now()
                if (t - t0 > MAX_QUEUE_SEC) {
                    emit(StreamEvent.Failed(
                        "所有账号都在限流/冷却（等待 ${queueWait.toInt()}s）。最近：${lastError ?: "无"}"
                    ))
                    return@flow
                }

                val up = pool.pick(t, tried)
                if (up == null) {
                    // 全试过一遍：清空重试（冷却可能已过），先等一会儿
                    if (tried.isNotEmpty()) tried = mutableSetOf()
                    val w = minOf(pool.shortestWait(t), 5.0)
                    kotlinx.coroutines.delay((w * 1000).toLong())
                    queueWait += w
                    continue
                }

                val cd = up.cooldownRemaining(t)
                if (cd > 0) {
                    val w = minOf(cd, pool.shortestWait(t), 5.0)
                    kotlinx.coroutines.delay((w * 1000).toLong())
                    queueWait += w
                    continue
                }

                val rateWait = up.waitSec(t)
                if (rateWait > 0) {
                    val w = minOf(rateWait, 5.0)
                    kotlinx.coroutines.delay((w * 1000).toLong())
                    queueWait += w
                    continue
                }

                val entry = usable.firstOrNull { it.first.id == up.id }
                if (entry == null) { tried.add(up.id); continue }
                val cfg = configFor(entry.first, entry.second, key)

                up.markSent(t)
                var sawContent = false
                var failed = false

                try {
                    send(cfg).collect { ev ->
                        when (ev) {
                            is StreamEvent.Content, is StreamEvent.Reasoning,
                            is StreamEvent.ToolCallDelta -> {
                                if (!sawContent) { sawContent = true; up.onSuccess(now()) }
                                emit(ev)
                            }
                            is StreamEvent.Usage, is StreamEvent.Completed -> emit(ev)
                            // 2026-11-14 服务端搜索来源：也算「有输出」——否则整轮只搜不答时
                            // 池子会以为这个号没产出内容而误判冷却。
                            is StreamEvent.Sources -> {
                                if (!sawContent) { sawContent = true; up.onSuccess(now()) }
                                emit(ev)
                            }
                            is StreamEvent.Failed -> {
                                failed = true
                                val m = ev.message ?: ""
                                if (m.contains("429")) up.on429(now()) else up.on5xx(now())
                                emit(ev)
                            }
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    failed = true
                    up.onNetworkError(now())
                    lastError = "${entry.first.displayName}: ${e.message ?: "网络错误"}"
                }

                // 成功，或已流出内容（半截回复不换号重发，避免重复内容/重复计费）
                if (!failed || sawContent) return@flow

                tried.add(up.id)
                if (lastError == null) lastError = "${entry.first.displayName}: 首包失败"
                if (tried.size >= usable.size) {
                    tried = mutableSetOf()
                    val w = minOf(pool.shortestWait(now()), 5.0)
                    kotlinx.coroutines.delay((w * 1000).toLong())
                    queueWait += w
                }
            }
        }
    }

    /** 组装某上游跑某模型的实际配置（地址与模型名都换成该上游的真实值） */
    private fun configFor(
        spec: RoutabilityCatalog.UpstreamSpec,
        apiKey: String,
        alias: String
    ): ModelConfig = ModelConfig(
        provider = "routability:" + spec.id,
        baseUrl = spec.baseUrl,
        apiKey = apiKey,
        modelName = RoutabilityCatalog.realModelName(spec, alias),
        enabled = true,
        authStyle = io.github.m0use001.planability.data.AUTH_BEARER,
        apiFormat = "openai"
    )

    /** 取/建池；上游集合没变就复用（保住运行时状态：窗口、冷却、自适应 RPM） */
    @Synchronized
    private fun poolFor(modelKey: String, specs: List<RoutabilityCatalog.UpstreamSpec>): UpstreamPool {
        poolCache[modelKey]?.let { existing ->
            if (existing.all().map { it.id }.sorted() == specs.map { it.id }.sorted()) return existing
        }
        val states = specs.map { s ->
            UpstreamState(
                id = s.id, name = s.displayName, priority = s.priority,
                startRpm = s.startRpm, rpmMin = s.rpmMin, rpmMax = s.rpmMax,
                cooldownBaseSec = s.cooldownBaseSec, cooldownMaxSec = s.cooldownMaxSec,
                dailyLimit = s.dailyLimit
            )
        }
        return UpstreamPool(modelKey, states).also { poolCache[modelKey] = it }
    }

    private fun now(): Double = System.currentTimeMillis() / 1000.0
}
