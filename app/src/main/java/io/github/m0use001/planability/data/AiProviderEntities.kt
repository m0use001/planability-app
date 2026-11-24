package io.github.m0use001.planability.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Article
import androidx.compose.material.icons.rounded.EditNote
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Laptop
import androidx.compose.material.icons.rounded.LocalLibrary
import androidx.compose.material.icons.rounded.School
import androidx.compose.material.icons.rounded.Science
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.TrackChanges
import androidx.compose.material.icons.rounded.Work
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.SecretBox
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

// ==================== v7 多供应商模型体系 ====================
// ai_providers + ai_models 两表；active/worker 模型 id 存 settings KV（ai.activeModelId / ai.workerModelId）。
// 迁移后旧 settings.model / modelw.* 不再读取（保留兼容不清理）。

@Entity(tableName = "ai_providers")
data class AiProvider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val iconKey: String = "cloud",
    val baseUrl: String = "",
    /** openai / anthropic（anthropic 本期仅占位，transport 未实现） */
    val apiFormat: String = "openai",
    val apiKey: String = "",
    val authStyle: String = AUTH_BEARER,
    val enabled: Boolean = true,
    val builtin: Boolean = false,
    val sort: Int = 0
)

@Entity(tableName = "ai_models", indices = [Index("providerId")])
data class AiModel(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val providerId: Long,
    val name: String,
    /** 逗号分隔标签，如 "1M,视觉" */
    val tags: String = "",
    val enabled: Boolean = true
)

@Dao
interface AiProviderDao {
    @Query("SELECT * FROM ai_providers ORDER BY sort, id")
    suspend fun all(): List<AiProvider>

    @Query("SELECT * FROM ai_providers WHERE id = :id")
    suspend fun byId(id: Long): AiProvider?

    @Query("SELECT COUNT(*) FROM ai_providers")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(p: AiProvider): Long

    @Update
    suspend fun update(p: AiProvider)

    @Query("DELETE FROM ai_providers WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface AiModelDao {
    @Query("SELECT * FROM ai_models")
    suspend fun all(): List<AiModel>

    @Query("SELECT * FROM ai_models WHERE id = :id")
    suspend fun byId(id: Long): AiModel?

    @Query("SELECT * FROM ai_models WHERE providerId = :pid ORDER BY id")
    suspend fun ofProvider(pid: Long): List<AiModel>

    @Query("SELECT COUNT(*) FROM ai_models")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(m: AiModel): Long

    @Update
    suspend fun update(m: AiModel)

    @Query("DELETE FROM ai_models WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM ai_models WHERE providerId = :pid")
    suspend fun deleteOfProvider(pid: Long)
}

/** 供 UI/客户端同步读取的已解析模型（ModelConfig + 来源名） */
data class ResolvedModel(
    val modelId: Long,
    val providerId: Long,
    val cfg: ModelConfig,
    val modelName: String,
    val providerName: String
)

class AiProviderRepo(
    private val providerDao: AiProviderDao,
    private val modelDao: AiModelDao,
    private val settingsDao: SettingsDao,
    private val version: MutableVersion
) {

    private val _state = MutableStateFlow(ModelsState())
    val state: StateFlow<ModelsState> = _state

    data class ModelsState(
        val providers: List<AiProvider> = emptyList(),
        val models: List<AiModel> = emptyList(),
        /** 主模型（对话/工具）解析结果；未配置时 null */
        val active: ResolvedModel? = null,
        /** 轻任务/智能体模型；未设置时回退 active */
        val worker: ResolvedModel? = null
    )

    /**
     * 落盘加密（v44）：库里存密文，**对外一律明文**。
     *
     * 只有这一层知道 `apiKey` 是密文：refresh() 解出来放进 state，
     * 任何写入口（create/update/seed）封回去。下游（AiStream/AiClient/UI/导出）
     * 拿到的都是明文，不需要改。
     */
    private fun sealKey(raw: String) = SecretBox.seal(raw)

    suspend fun refresh() {
        val stored = providerDao.all()
        // 解不开（重装后 Keystore 密钥已销毁）时 SecretBox 返回空串：UI 看到空 key，
        // 用户重填即可，不会把密文当 token 发出去。
        val providers = stored.map { p ->
            if (p.apiKey.isBlank()) p else p.copy(apiKey = SecretBox.open(p.apiKey))
        }
        val models = modelDao.all()
        val byIdP = providers.associateBy { it.id }
        fun resolve(modelId: Long): ResolvedModel? {
            val m = models.firstOrNull { it.id == modelId } ?: return null
            val p = byIdP[m.providerId] ?: return null
            return ResolvedModel(
                modelId = m.id, providerId = p.id,
                cfg = ModelConfig(
                    provider = "p" + p.id,
                    baseUrl = p.baseUrl,
                    apiKey = p.apiKey,
                    modelName = m.name,
                    enabled = p.enabled && m.enabled,
                    authStyle = p.authStyle,
                    apiFormat = p.apiFormat
                ),
                modelName = m.name, providerName = p.name
            )
        }
        _state.value = ModelsState(
            providers = providers,
            models = models,
            active = resolve(kvLong(KEY_ACTIVE)),
            worker = resolve(kvLong(KEY_WORKER))
        )
    }

    private suspend fun kvLong(key: String): Long =
        settingsDao.get(key)?.toLongOrNull() ?: 0L

    private suspend fun kvPut(key: String, v: Long) {
        settingsDao.put(SettingKV(key, v.toString()))
    }

    // ---------- 同步读取（AiClient/ChatEngine 用） ----------

    fun activeConfig(): ModelConfig? = _state.value.active?.cfg?.takeIf { it.enabled }

    /** 主模型未配置（被删/停用）时回退到第一个启用模型，避免落到已过期的旧 settings.model */
    fun fallbackConfig(): ModelConfig? {
        val s = _state.value
        val m = s.models.firstOrNull { it.enabled } ?: return null
        val p = s.providers.firstOrNull { it.id == m.providerId && it.enabled } ?: return null
        return ModelConfig(
            provider = "p" + p.id, baseUrl = p.baseUrl, apiKey = p.apiKey,
            modelName = m.name, enabled = true, authStyle = p.authStyle, apiFormat = p.apiFormat
        )
    }

    fun workerConfig(): ModelConfig? =
        _state.value.worker?.cfg?.takeIf { it.enabled } ?: activeConfig()

    /** 按模型名在启用模型中解析（会话模型快照用），找不到回退 active */
    fun configByName(name: String): ModelConfig? {
        if (name.isBlank()) return activeConfig()
        val s = _state.value
        val m = s.models.firstOrNull { it.name == name && it.enabled } ?: return activeConfig()
        val p = s.providers.firstOrNull { it.id == m.providerId }?.takeIf { it.enabled } ?: return activeConfig()
        return ModelConfig(
            provider = "p" + p.id, baseUrl = p.baseUrl, apiKey = p.apiKey,
            modelName = m.name, enabled = true, authStyle = p.authStyle, apiFormat = p.apiFormat
        )
    }

    /**
     * 按 baseUrl 提示串找供应商配置（2026-11-14 云能力用：云 API 要的是 DeepSeek 的 key，
     * 不是某个模型——用户改了供应商名也不影响，内置播种的 baseUrl 恒含 "deepseek"）。
     * 要求供应商已启用且配了 key；没找到返回 null（云能力整体不可用，调用方空手回退）。
     */
    fun configByBaseUrlHint(hint: String): ModelConfig? {
        val s = _state.value
        val p = s.providers.firstOrNull {
            it.enabled && it.baseUrl.contains(hint, true) && it.apiKey.isNotBlank()
        } ?: return null
        return ModelConfig(
            provider = "p" + p.id, baseUrl = p.baseUrl, apiKey = p.apiKey,
            modelName = "", enabled = true, authStyle = p.authStyle, apiFormat = p.apiFormat
        )
    }

    // ---------- 写操作 ----------

    suspend fun setActiveModel(modelId: Long) {
        kvPut(KEY_ACTIVE, modelId); refresh(); version.bump()
    }

    suspend fun setWorkerModel(modelId: Long) {
        kvPut(KEY_WORKER, modelId); refresh(); version.bump()
    }

    suspend fun createProvider(name: String, baseUrl: String, apiFormat: String, apiKey: String, iconKey: String = "cloud"): Long {
        val id = providerDao.insert(
            AiProvider(
                name = name.trim(), baseUrl = baseUrl.trim(), apiFormat = apiFormat,
                apiKey = sealKey(apiKey.trim()), sort = 100
            )
        )
        refresh(); version.bump(); return id
    }

    /** 入参是**明文**（UI 拿到的就是明文）；这里封回密文再落库。 */
    suspend fun updateProvider(p: AiProvider) {
        providerDao.update(if (p.apiKey.isBlank()) p else p.copy(apiKey = sealKey(p.apiKey)))
        refresh(); version.bump()
    }

    /**
     * 一次性迁移：把 v43 及以前落的明文 key 封成密文。
     *
     * 幂等（`SecretBox.seal` 对已加密值原样返回），所以不需要额外的"迁移过"标记；
     * 放在启动链路里每次跑一遍也只是扫几行。
     */
    suspend fun migrateSecrets() {
        runCatching {
            var sealed = 0
            providerDao.all().forEach { p ->
                if (p.apiKey.isNotBlank() && !SecretBox.isSealed(p.apiKey)) {
                    providerDao.update(p.copy(apiKey = SecretBox.seal(p.apiKey)))
                    sealed++
                }
            }
            if (sealed > 0) {
                android.util.Log.i("AiProviderRepo", "已加密 $sealed 个供应商 key")
                refresh()
            }
        }
    }

    /** 删除供应商：其模型一并删；active/worker 指向它则重置 */
    suspend fun deleteProvider(id: Long) {
        modelDao.deleteOfProvider(id)
        providerDao.delete(id)
        if (kvLong(KEY_ACTIVE) == 0L) Unit
        if (_state.value.active?.providerId == id) kvPut(KEY_ACTIVE, 0)
        if (_state.value.worker?.providerId == id) kvPut(KEY_WORKER, 0)
        refresh(); version.bump()
    }

    suspend fun addModel(providerId: Long, name: String, tags: String): Long {
        val id = modelDao.insert(AiModel(providerId = providerId, name = name.trim(), tags = tags.trim()))
        refresh(); version.bump(); return id
    }

    suspend fun updateModel(m: AiModel) {
        modelDao.update(m); refresh(); version.bump()
    }

    suspend fun deleteModel(id: Long) {
        modelDao.delete(id)
        if (kvLong(KEY_ACTIVE) == id) kvPut(KEY_ACTIVE, 0)
        if (kvLong(KEY_WORKER) == id) kvPut(KEY_WORKER, 0)
        refresh(); version.bump()
    }

    // ---------- 迁移种子：旧 settings.model / modelw → 两表 ----------

    suspend fun seedIfNeeded(main: ModelConfig, worker: ModelConfig) {
        if (providerDao.count() > 0 || modelDao.count() > 0) { refresh(); return }
        // v19.52：**key 为空就不播种**。这两个供应商来自旧 settings.model / modelw 迁移，
        // 而用户 10-18 明确说"BigModel、MIMO 不用接入"——它们的 key 一直是空串，
        // 播进去只会得到两个连不上的供应商、把选择列表撑满。
        // 真正在用的供应商走 ensureCatalogProviders（DeepSeek / NVIDIA，key 来自 local.properties）。
        if (main.apiKey.isBlank() && worker.apiKey.isBlank()) { refresh(); return }
        val now = AppTime.nowMillis()
        val glmId = providerDao.insert(
            AiProvider(
                name = "BigModel", iconKey = "cloud", baseUrl = main.baseUrl,
                apiFormat = "openai", apiKey = sealKey(main.apiKey), authStyle = main.authStyle,
                enabled = main.enabled, builtin = true, sort = 0
            )
        )
        val glmModelId = modelDao.insert(AiModel(providerId = glmId, name = main.modelName, enabled = true))
        val mimoId = providerDao.insert(
            AiProvider(
                name = "MIMO", iconKey = "bolt", baseUrl = worker.baseUrl,
                apiFormat = "openai", apiKey = sealKey(worker.apiKey), authStyle = worker.authStyle,
                enabled = worker.enabled, builtin = true, sort = 1
            )
        )
        val mimoModelId = modelDao.insert(AiModel(providerId = mimoId, name = worker.modelName, enabled = true))
        kvPut(KEY_ACTIVE, glmModelId)
        kvPut(KEY_WORKER, if (worker.enabled) mimoModelId else 0)
        refresh()
        android.util.Log.e("AiProviderRepo", "seeded providers at $now")
    }

    // ---------- v19.51 供应商目录：DeepSeek / NVIDIA NIM ----------

    /**
     * 按本机 local.properties 里的 key 播种两个 OpenAI 兼容供应商。
     *
     * ## 为什么单独一个函数而不是塞进 seedIfNeeded
     * - `seedIfNeeded` 只在整个库为空时跑一次（迁移种子），语义是"初始化"；
     * - 这两个是**可选项**：用户可能只配了一个 key，也可能之后改主意把自己建的删了。
     *   所以判据是"baseUrl 已存在就跳过"，而不是"表空不空"——这样反复调用安全，
     *   用户手动删掉后下次启动也不会又被塞回来（除非 key 还在，这是预期：key 在就说明想用）。
     *
     * ## key 从哪来
     * `BuildConfig.DEEPSEEK_KEY` / `BuildConfig.NVIDIA_KEY`，由 app/build.gradle.kts 从
     * `local.properties` 读（已 .gitignore，不进仓库）。空串 = 没配 = 跳过该供应商。
     *
     * ## 模型列表
     * DeepSeek 官方就两款（chat / reasoner），直接写死；
     * NVIDIA 的模型 catalog 太大且常变，**只建供应商不建模型**，
     * 由用户在「模型与智能体」里点「扫描模型」拉取（见 AiClient.fetchModels）。
     */
    suspend fun ensureCatalogProviders(deepseekKey: String, nvidiaKey: String, amdKey: String = "") {
        val existing = providerDao.all()
        fun has(base: String) = existing.any { it.baseUrl.trimEnd('/') == base }

        if (deepseekKey.isNotBlank() && !has("https://api.deepseek.com")) {
            val id = providerDao.insert(
                AiProvider(
                    name = "DeepSeek", iconKey = "science",
                    baseUrl = "https://api.deepseek.com",
                    apiFormat = "openai", apiKey = sealKey(deepseekKey.trim()),
                    authStyle = AUTH_BEARER, enabled = true, builtin = true, sort = 2
                )
            )
            // 模型名**用官方 /models 的真实返回**（sk-key 在 2026-10-18 实测返回这两个）：
            //   deepseek-flash / deepseek-v4-pro
            // 之前写的 deepseek-chat / deepseek-reasoner 是旧版命名，现在会 400
            // （"Model does not exist"）——那种错在真机上表现为"能连上但一发消息就报错"，
            //  比连不上更难排查。写死后仍可用「扫描模型」拉最新清单。
            modelDao.insert(AiModel(providerId = id, name = "deepseek-flash", tags = "快速", enabled = true))
            modelDao.insert(AiModel(providerId = id, name = "deepseek-v4-pro", tags = "旗舰", enabled = true))
            android.util.Log.i("AiProviderRepo", "seeded DeepSeek provider")
        }

        if (nvidiaKey.isNotBlank() && !has("https://integrate.api.nvidia.com/v1")) {
            providerDao.insert(
                AiProvider(
                    name = "NVIDIA NIM", iconKey = "bolt",
                    baseUrl = "https://integrate.api.nvidia.com/v1",
                    apiFormat = "openai", apiKey = sealKey(nvidiaKey.trim()),
                    authStyle = AUTH_BEARER, enabled = true, builtin = true, sort = 3
                )
            )
            android.util.Log.i("AiProviderRepo", "seeded NVIDIA NIM provider (models via scan)")
        }

        // 2026-11-02：**Routability 虚拟供应商**（完整复刻本机网关，用户要求"只显示一个
        // 供应商 + auto 模型"）。一家 provider 对用户可见，背后 9 个账号存在
        // settings KV 里由 UpstreamPool 调度——不在 providers 表里出现，
        // 所以设置页只有一行、模型列表只有 auto + 7 个 1M 模型。
        ROutabilitySeed.ensure(this)
        refresh(); version.bump()
    }

    /**
     * 建/更新「Routability」那一家可见供应商与其模型（[ROutabilitySeed] 调用）。
     *
     * 供应商行的 baseUrl 填一个占位值（真正发请求时由 [io.github.m0use001.planability.ai.pool.RoutedStream]
     * 换成选中上游的地址）——**不能留空**，否则 `enabled/configured` 判据会把它当未配置。
     */
    internal suspend fun ensureRoutabilityProvider() {
        val name = io.github.m0use001.planability.ai.pool.RoutabilityCatalog.VENDOR_NAME
        val existing = providerDao.all().firstOrNull { it.name == name }
        val pid = existing?.id ?: providerDao.insert(
            AiProvider(
                name = name,
                iconKey = "hub",
                // 占位地址：真发请求前会被选中上游的 baseUrl 覆盖（见 RoutedStream）
                baseUrl = "https://routability.local/v1",
                apiFormat = "openai",
                apiKey = "",
                authStyle = AUTH_BEARER,
                enabled = true,
                builtin = true,
                sort = 1
            )
        )
        // 模型：auto + 7 个 1M 模型（幂等，已存在就不重插）
        val have = modelDao.all().filter { it.providerId == pid }.map { it.name }.toSet()
        val catalog = io.github.m0use001.planability.ai.pool.RoutabilityCatalog
        catalog.MODEL_ROUTES.keys.forEach { modelName ->
            if (modelName !in have) {
                val tags = buildString {
                    append("1M")
                    if (modelName in catalog.VISION_MODELS) append(",视觉")
                    if (modelName == catalog.AUTO_MODEL) append(",自动路由")
                }
                modelDao.insert(AiModel(providerId = pid, name = modelName, tags = tags, enabled = true))
            }
        }
    }

    companion object {
        const val KEY_ACTIVE = "ai.activeModelId"
        const val KEY_WORKER = "ai.workerModelId"
    }
}

/** 项目/助手的 material 图标映射（v7 图标统一：数据存 iconKey，UI 渲染图标） */
object WorkbenchIcons {

    fun assistant(key: String): ImageVector = when (key) {
        "planner" -> Icons.Rounded.Event
        "digest" -> Icons.AutoMirrored.Rounded.Article
        "tutor" -> Icons.Rounded.School
        "writer" -> Icons.Rounded.EditNote
        else -> Icons.Rounded.SmartToy
    }

    fun project(iconKey: String): ImageVector = when (iconKey) {
        "laptop" -> Icons.Rounded.Laptop
        "library" -> Icons.Rounded.LocalLibrary
        "target" -> Icons.Rounded.TrackChanges
        "science" -> Icons.Rounded.Science
        "home" -> Icons.Rounded.Home
        "work" -> Icons.Rounded.Work
        "terminal" -> Icons.Rounded.Terminal
        else -> Icons.Rounded.Folder
    }

    /** 新建项目图标网格候选 */
    val PROJECT_ICON_KEYS = listOf("folder", "laptop", "library", "target", "science", "home", "work", "terminal")
}
