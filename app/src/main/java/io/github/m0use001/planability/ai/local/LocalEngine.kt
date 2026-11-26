package io.github.m0use001.planability.ai.local

import android.content.Context
import com.geniex.sdk.GenieXSdk
import com.geniex.sdk.LlmWrapper
import com.geniex.sdk.ModelManagerWrapper
import com.geniex.sdk.bean.ChatMessage
import com.geniex.sdk.bean.ComputeUnitValue
import com.geniex.sdk.bean.GenerationConfig
import com.geniex.sdk.bean.HubSource
import com.geniex.sdk.bean.LlmCreateInput
import com.geniex.sdk.bean.LlmStreamResult
import com.geniex.sdk.bean.ModelConfig
import com.geniex.sdk.bean.ModelPullInput
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * 端侧小模型引擎（阶段 1）——高通官方 GenieX SDK（com.qualcomm.qti:geniex-android）薄封装。
 * 方案与验收标准：◆/planability-ondevice-model.md。
 *
 * 约定：
 * - **不自动拉模型**（首次 ~500MB，绝不能藏进对话路径）：模型先由 [pullModel]（设置页/验收触发）
 *   或 LOCALFS 侧载导入；未就绪时 [chat] 抛错，HTTP 层转 503，AiClient 侧表现=普通失败。
 * - 单实例单路：llama.cpp 后端一次只跑一路生成；并发在 HTTP server 层用互斥串行化。
 * - runtime=llama_cpp（任意 GGUF）；compute_unit=null 走运行时默认（HYBRID，NPU 优先卸载）。
 *   NPU 真落盘证据拿到后再考虑钉死 "npu"。
 */
object LocalEngine {

    /**
     * 可选模型目录（方案 §7）。**引擎单实例，一次只加载一个**；切换即卸旧装新。
     * 选择结果落 `filesDir/local-model.txt`——:localai 是独立进程，读不了主进程的 KV，
     * 用一个小文件做跨进程选择同步（SharedPreferences 跨进程不可靠）。
     */
    data class Spec(
        val pullName: String,
        val label: String,
        val precision: String?,
        val runtime: String,
        val hub: HubSource,
        val chipset: String? = null,
        /** AI Hub 通道必填（ModelPullInput KDoc：hub=AIHUB 时 display_name 必填） */
        val displayName: String? = null,
        val approxMb: Int,
    )

    val CATALOG: List<Spec> = listOf(
        // llama.cpp 腿：任意 GGUF，Q4_0 = Hexagon NPU 推荐精度
        Spec("unsloth/Qwen3-0.6B-GGUF", "Qwen3-0.6B · 轻任务（命名/记忆/压缩）",
            "Q4_0", "llama_cpp", HubSource.MODELSCOPE, null, approxMb = 382),
        Spec("unsloth/Qwen3-1.7B-GGUF", "Qwen3-1.7B · 平衡档（质量/内存折中）",
            "Q4_0", "llama_cpp", HubSource.MODELSCOPE, null, approxMb = 1008),
        // ── 2026-11-03 端侧模型调研新增（AI Hub 目录已确认支持 8 Elite Gen 5 = SM8850）──
        // Qwen3.5 系列：官方称推理/指令遵循优于 Qwen3，Qwen3.5-2B 权重 1.21GB（Q4_0，与 1.7B 同量级）。
        // AI Hub 页面的 geniex_llamacpp runtime 明确支持 Model Scope 同款 GGUF 仓库名。
        Spec("unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B · 新版轻任务（Qwen3 同档替代）",
            "Q4_0", "llama_cpp", HubSource.MODELSCOPE, null, approxMb = 507),
        Spec("unsloth/Qwen3.5-2B-GGUF", "Qwen3.5-2B · 新版平衡档（质量/内存折中）",
            "Q4_0", "llama_cpp", HubSource.MODELSCOPE, null, approxMb = 1215),
        // 4B 质量档：**2026-11-03 调研重新开放**（此前 10-22 判定两条路都堵，均已被上游修复）：
        //  · QAIRT 路：GenieX 源码 detect.rs 确认非 Galaxy 的 SM8850 显式映射到目录里的
        //    `SM8850-AD` 别名（chipset 名不必写 "SM8850"）；0.10.0 的 ggml-htp 扩到 v85 覆盖 8 Elite Gen 5。
        //    QAIRT 是纯 NPU（w4a16），比 llama.cpp-HTP 快且省电，是 4B 的首选路。
        //  · GGUF 路：0.10.0 实测 Qwen3.5-2B（1.7G 落盘）pull 全程不崩，0.8.0 的大文件 SIGSEGV 已不复现。
        Spec("unsloth/Qwen3-4B-GGUF", "Qwen3-4B · 质量档（离线主对话候选）",
            "Q4_0", "llama_cpp", HubSource.MODELSCOPE, null, approxMb = 2376),
        // QAIRT 预编译（纯 NPU，w4a16）：chipset 用 AI Hub 目录别名（GenieX 也接受 SM8850 由内部映射）
        Spec("ai-hub-models/Qwen3-4B", "Qwen3-4B · QAIRT 纯 NPU 档",
            null, "qairt", HubSource.AIHUB, chipset = "SM8850-AD",
            displayName = "Qwen3-4B", approxMb = 3000),
    )

    const val DEFAULT_MODEL = "unsloth/Qwen3-0.6B-GGUF"
    private const val SELECT_FILE = "local-model.txt"
    private const val N_CTX = 4096

    fun specFor(name: String?): Spec = CATALOG.firstOrNull { it.pullName == name } ?: CATALOG[0]

    /** 当前选中的模型名（若无选择文件则默认 0.6B） */
    fun selectedName(context: Context): String =
        runCatching { File(context.applicationContext.filesDir, SELECT_FILE).readText().trim() }
            .getOrNull()?.takeIf { it.isNotBlank() } ?: DEFAULT_MODEL

    /**
     * 切换模型：落选择文件 + 卸下当前实例（不同模型不能共用一个 LlmWrapper）。
     * 由 UI 调用（同时会先写主进程 KV）；:localai 进程重启后按文件恢复选择。
     */
    fun select(context: Context, name: String): Spec {
        val spec = specFor(name)
        runCatching { File(context.applicationContext.filesDir, SELECT_FILE).writeText(spec.pullName) }
        runCatching { llm?.close() }
        llm = null
        loadedSpec = null
        return spec
    }

    @Volatile private var sdkInited = false
    @Volatile private var managerInited = false
    @Volatile private var llm: LlmWrapper? = null
    /** 当前已加载实例对应的目录项与算力单元（热切换判据，见 [ensureLoaded]） */
    @Volatile private var loadedSpec: Spec? = null
    @Volatile private var loadedUnit: String? = null
    private val loadMutex = Mutex()

    fun isReady(): Boolean = llm != null

    /**
     * 卸载并让下次请求重新加载（**引擎自愈**）。
     *
     * 2026-11-05 真机实测：GenieX/QAIRT 实例在连续请求后会进入"永久拒绝"状态——
     * 哪怕只发 400 字也返回 `LLM generate failed: Input prompt too long`，
     * 重启 App（重建实例）才恢复。后台批量提纯很容易把它打进去，所以出错时先卸实例、
     * 下一轮 ensureLoaded 会重新加载，不必杀进程。
     */
    suspend fun reload() {
        loadMutex.withLock {
            runCatching { llm?.close() }
            llm = null
        }
    }

    /**
     * 拉取当前选中模型（断点续传；只应由设置页/验收脚本触发，不进对话路径）。
     * GGUF 走 **MODELSCOPE**（本机实测 huggingface.co 不可达、modelscope.cn 54ms 可达）；
     * QAIRT 走 **AIHUB**（Android 无自动探测，须显式传 chipset）。
     */
    suspend fun pullModel(context: Context, onProgress: (String) -> Unit = {}) {
        ensureManager(context)
        val spec = specFor(selectedName(context))
        ModelManagerWrapper.pullFlow(
            ModelPullInput(
                model_name = spec.pullName,
                precision = spec.precision,
                hub = spec.hub,
                chipset = spec.chipset,
                display_name = spec.displayName,
            )
        ).collect { e ->
            when (e) {
                is ModelManagerWrapper.PullEvent.Progress -> { /* 节流与展示归 UI 层 */ }
                ModelManagerWrapper.PullEvent.Completed -> onProgress("完成")
                is ModelManagerWrapper.PullEvent.Error -> onProgress("失败 rc=${e.code} ${e.message}")
            }
        }
    }

    /**
     * 确保引擎就绪。[modelName] 非空时**按请求名加载**（2026-11-04 部署组合：
     * worker 槽跑小模型、压缩槽跑 4B，两个请求名映射到不同权重，引擎单实例需按名热切）。
     * 名字解析不到目录时回退到选择文件里的当前模型（保持旧行为）。
     * [compute] 非空时覆盖该 spec 的默认算力单元（gpu/npu/hybrid/cpu，GPU 路调查用）。
     */
    suspend fun ensureLoaded(
        context: Context,
        modelName: String? = null,
        compute: String? = null,
    ): LlmWrapper {
        val spec = modelName?.takeIf { it.isNotBlank() }
            ?.let { name -> CATALOG.firstOrNull { it.pullName == name } }
            ?: specFor(selectedName(context))
        val unit = compute?.takeIf { it.isNotBlank() } ?: defaultUnit(spec)
        // 已被同一模型+同一算力单元装好 → 直接复用（热切只在真的换了模型时发生）
        llm?.let { if (loadedSpec?.pullName == spec.pullName && loadedUnit == unit) return it }
        loadMutex.withLock {
            llm?.let { if (loadedSpec?.pullName == spec.pullName && loadedUnit == unit) return it }
            ensureManager(context)
            val paths = ModelManagerWrapper.getPaths(spec.pullName)
                ?: throw IllegalStateException("端侧模型未下载：${spec.pullName}")
            // 换模型/换算力单元：先卸旧实例（GenieX 单实例）
            runCatching { llm?.close() }
            llm = null
            val wrapper = LlmWrapper.builder()
                .llmCreateInput(
                    LlmCreateInput(
                        model_path = paths.model_path,
                        config = if (spec.runtime == "qairt") {
                            // **QAIRT（AI Hub 预编译包）必须 nCtx=0 / nGpuLayers=0**：
                            // 上下文在编译期已固定（Qwen3-4B w4a16=4096），传非零值直接
                            // "Parameter not supported by this plugin"（2026-11-03 真机；
                            // 与官方样例 geniex_chat_android 的 qairt 分支一致）。
                            ModelConfig(nCtx = 0, nGpuLayers = 0)
                        } else {
                            ModelConfig(nCtx = N_CTX)
                        },
                        runtime_id = spec.runtime,
                        // 默认：llama_cpp 走 "npu"（源码 KDoc 明确 null=HYBRID(CPU+GPU)，
                        // 2026-10-22 实测 null 时进程内无 QNN/HTP 库）；qairt 只认 NPU 传 null。
                        // 显式 compute 覆盖时按传入值（GPU 走 OpenCL 的 Adreno 后端）。
                        compute_unit = unit
                    )
                ).build().getOrThrow()
            llm = wrapper
            loadedSpec = spec
            loadedUnit = unit
            return wrapper
        }
    }

    /** 该 spec 的默认算力单元：qairt 必须 NPU(null)，llama_cpp 钉 "npu" */
    private fun defaultUnit(spec: Spec): String? =
        if (spec.runtime == "llama_cpp") ComputeUnitValue.NPU.value else null

    /**
     * 删除已下载的模型文件（设置页「删除模型」）：先卸下已加载实例，再删缓存。
     * 删除后 isReady()=false，下一次请求会提示未下载。
     */
    suspend fun removeModel(context: Context) {
        loadMutex.withLock {
            runCatching { llm?.close() }
            llm = null
            ensureManager(context)
            ModelManagerWrapper.remove(specFor(selectedName(context)).pullName)
        }
    }

    /** 模型文件是否已在设备上（status 接口用；与「已加载」是两回事） */
    suspend fun isModelPresent(context: Context): Boolean =
        isModelPresent(context, specFor(selectedName(context)).pullName)

    /** 指定模型是否已落盘（模型列表逐行状态用） */
    suspend fun isModelPresent(context: Context, name: String): Boolean =
        runCatching {
            ensureManager(context)
            ModelManagerWrapper.getPaths(name) != null
        }.getOrDefault(false)

    private suspend fun ensureManager(context: Context) {
        if (!sdkInited) {
            GenieXSdk.getInstance().init(context.applicationContext)
            sdkInited = true
        }
        if (!managerInited) {
            val dir = File(context.applicationContext.filesDir, "geniex").apply { mkdirs() }
            ModelManagerWrapper.init(dir.absolutePath).getOrThrow()
            managerInited = true
        }
    }

    /**
     * 生成一条回复。[onToken] 非空即流式回调（HTTP SSE 用），返回值恒为全文。
     * 必须先 applyChatTemplate——原生管线只吃模板化文本（官方 quickstart 的 Warning）。
     */
    suspend fun chat(
        context: Context,
        messages: List<ChatMessage>,
        maxTokens: Int,
        onToken: ((String) -> Unit)? = null,
        modelName: String? = null,
        compute: String? = null,
    ): String {
        val engine = ensureLoaded(context, modelName, compute)
        // 2026-11-05 关键修复：**每轮生成前先 reset**。
        // SDK 的实例是"有状态"的——上一轮的 prompt/生成会留在引擎上下文里，下一次
        // generateStreamFlow 会在已有序列后面继续追加；连续请求后累计长度超过模型上下文
        // （QAIRT 预编译包固定 4096），从此**任何请求**都立刻返回
        // `LLM generate failed: Input prompt too long`（连 6 个字也报），只有重建实例才恢复。
        // 这就是"端侧引擎退化/拒绝态"的机制；reset() 会释放上一轮占用的上下文。
        runCatching { engine.reset() }
        val templated = engine.applyChatTemplate(messages.toTypedArray(), null, false)
            .getOrThrow().formattedText
        val sb = StringBuilder()
        engine.generateStreamFlow(
            templated,
            config = GenerationConfig(maxTokens = maxTokens.coerceIn(1, 4096))
        ).collect { r ->
            when (r) {
                is LlmStreamResult.Token -> { sb.append(r.text); onToken?.invoke(r.text) }
                is LlmStreamResult.Error -> throw r.throwable
                is LlmStreamResult.Completed -> {}
            }
        }
        return sb.toString()
    }
}
