package io.github.m0use001.planability.data

import io.github.m0use001.planability.BuildConfig
import io.github.m0use001.planability.ai.pool.RoutabilityCatalog
import io.github.m0use001.planability.core.SecretBox

/**
 * 2026-11-02：**Routability 虚拟供应商**的播种。
 *
 * ## 设计取舍
 * 设置页只看得到**一家**供应商「Routability」和它下面 `auto` + 7 个 1M 模型。
 * 背后的 9 个免费账号（NVIDIA×2 / 魔搭×2 / 华为×2 / OpenRouter / Cloudflare / AMD）
 * 存在 `settings` KV 的 `routability.upstreams` 里（密文），由
 * [io.github.m0use001.planability.ai.pool.UpstreamPool] 调度——**不进 `ai_providers` 表**，
 * 所以用户在供应商列表/模型列表里看不到它们。
 *
 * ## 为什么这样设计
 * 设计目标："只显示一个虚拟供应商和一个 auto 模型"。把 9 个账号都塞进 providers 表
 * 会让设置页变成 9 行噪音，而且"为什么同一个模型出现 9 次"会让人困惑。KV 旁路表
 * 天然对 UI 不可见，且备份/恢复会一起带走（settings 表在备份范围内）。
 *
 * ## 幂等
 * 判据是 KV 键存在与否 + 版本号。用户在设置页删掉 Routability 后，会在 KV 里留下
 * `routability.deleted=1` 墓碑，不再被塞回（与 10-31 那条"内置供应商删了会复活"的教训同款）。
 *
 * ## key 来源
 * 全部走 `local.properties` → BuildConfig（已 .gitignore，不进仓库）。缺哪个就跳过哪个
 * 账号——池里少一个候选，不影响其余。**key 一律 SecretBox 加密后落 KV**。
 */
internal object ROutabilitySeed {

    private const val KV_ON = "routability.enabled"
    private const val KV_UPSTREAMS = "routability.upstreams"
    private const val KV_DELETED = "routability.deleted"
    private const val SEED_VERSION = "1"

    /** 种子版本（配置表变了就 +1，触发重新播种） */
    private const val KV_VERSION = "routability.seedVersion"

    /**
     * 播种/更新。由 [AiProviderRepo.ensureCatalogProviders] 调用（启动时一次）。
     */
    suspend fun ensure(repo: AiProviderRepo) {
        val kv = Graph.settings
        // 用户主动删过 → 不再复活
        if (kv.kvGet(KV_DELETED) == "1") return
        // 已经是当前版本 → 什么都不做
        if (kv.kvGet(KV_VERSION) == SEED_VERSION && kv.kvGet(KV_UPSTREAMS) != null) {
            warmCache()   // 已经是当前版本：只需刷新进程内缓存
            return
        }

        val keys = upstreamKeys()
        if (keys.isEmpty()) {
            // 一个 key 都没配：不建空壳供应商（界面平白多一行）
            return
        }

        // 1) 落上游账号（密文）
        val payload = keys.entries.joinToString("\n") { (id, key) -> "$id\t${SecretBox.seal(key)}" }
        kv.kvPut(KV_UPSTREAMS, payload)
        kv.kvPut(KV_ON, "1")
        kv.kvPut(KV_VERSION, SEED_VERSION)

        // 2) 建（或更新）那一家可见供应商 + 模型
        repo.ensureRoutabilityProvider()
        warmCache()
        android.util.Log.i("ROutabilitySeed", "seeded ${keys.size} upstream accounts (hidden)")
    }

    /** 从 BuildConfig 收集已配置的上游 key（空串跳过） */
    internal fun upstreamKeys(): Map<String, String> {
        val pairs = listOf(
            "nvidia1" to BuildConfig.NVIDIA_KEY,
            "nvidia2" to BuildConfig.NVIDIA_KEY2,
            "huawei" to BuildConfig.HUAWEI_KEY,
            "huawei2" to BuildConfig.HUAWEI_KEY2,
            "modelscope" to BuildConfig.MODELSCOPE_KEY,
            "modelscope2" to BuildConfig.MODELSCOPE_KEY2,
            "openrouter" to BuildConfig.OPENROUTER_KEY,
            "cloudflare" to BuildConfig.CLOUDFLARE_KEY,
            "amd" to BuildConfig.AMD_KEY
        )
        return pairs.mapNotNull { (id, k) ->
            val v = k.trim()
            if (v.isNotEmpty()) id to v else null
        }.toMap()
    }

    /** 读取上游账号（解密）；没播种过返回空 */
    suspend fun loadUpstreams(): Map<String, String> {
        val raw = Graph.settings.kvGet(KV_UPSTREAMS) ?: return emptyMap()
        return raw.lineSequence().mapNotNull { line ->
            val i = line.indexOf('\t')
            if (i <= 0) null
            else {
                val id = line.substring(0, i)
                val sealed = line.substring(i + 1)
                val plain = runCatching { SecretBox.open(sealed) }.getOrDefault("")
                if (plain.isBlank()) null else id to plain
            }
        }.toMap()
    }

    suspend fun isEnabled(): Boolean =
        Graph.settings.kvGet(KV_ON) == "1" && Graph.settings.kvGet(KV_DELETED) != "1"

    /** 用户在设置页删除时调用：立墓碑，避免下次启动复活 */
    suspend fun markDeleted() {
        Graph.settings.kvPut(KV_DELETED, "1")
        Graph.settings.kvPut(KV_ON, "0")
        Graph.settings.kvDelete(KV_UPSTREAMS)
        cachedEnabled = false
        cachedUpstreams = emptyMap()
    }

    // ---------- 同步访问（RoutedStream 在 Flow 里不能 suspend） ----------

    /**
     * 进程级缓存：启动时预热一次，之后同步读。
     *
     * 为什么不在每次请求读 KV：`kvGet` 是 suspend（要过 Room），而
     * [io.github.m0use001.planability.ai.pool.RoutedStream.streamChatPooled] 是普通函数
     * （要在 Flow builder 之前就决定"走不走池"）。内存缓存既避开 suspend，
     * 也避免每轮对话多两次 DB 查询。
     */
    @Volatile
    private var cachedUpstreams: Map<String, String> = emptyMap()

    @Volatile
    private var cachedEnabled: Boolean = false

    /** 预热缓存（播种后 / 启动时 / 用户在设置页改过 key 后调用） */
    suspend fun warmCache() {
        cachedEnabled = isEnabled()
        cachedUpstreams = if (cachedEnabled) loadUpstreams() else emptyMap()
    }

    /** 同步：是否启用（RoutedStream 据此决定走不走池） */
    fun isEnabledBlocking(): Boolean = cachedEnabled

    /** 同步：上游账号表（id → 明文 key） */
    fun loadUpstreamsBlocking(): Map<String, String> = cachedUpstreams
}
