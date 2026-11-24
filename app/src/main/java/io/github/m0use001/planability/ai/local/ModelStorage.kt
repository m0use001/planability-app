package io.github.m0use001.planability.ai.local

import android.content.Context
import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.data.Graph
import java.io.File

/**
 * 端侧模型存储治理（2026-11-10 优化体检 P0-1）。
 *
 * 背景：真机实测 files/geniex 9.38GB，CATALOG 六个模型全下载，其中只有
 * 「引擎当前选择 + 提纯 worker（ai.workerModelId → ai_models.name）+ 会话压缩槽
 * （ctx.compactModel → ai_models.name，解析不到时 AiClient.compactConfig 回退 worker）」
 * 三类引用是**活的**；其余每个模型 ~0.4-3.1GB 纯占位。
 *
 * 为什么不用 ModelManagerWrapper 的注册表做大小/目录：getPaths 只给文件路径，
 * 没有大小；AI Hub 模型落盘布局（models/qualcomm/<Name>）与 pullName
 * （ai-hub-models/<Name>）不同名——这里按「直接路径 → qualcomm 派生 → 唯一同名目录
 * 兜底」三级解析，纯 java.io，JVM 可测。
 *
 * 引用核对为什么在主进程做：:localai 是独立进程读不了 Room/KV（LocalEngine.kt:34
 * 注释），而引用真源（settings KV + ai_models 表）都在主进程；UI 层拿到 refs 后
 * 删除仍走既有 LocalModelClient.select+remove 管线（引擎单实例卸载语义不破坏）。
 */
object ModelStorage {

    data class OnDiskModel(
        val pullName: String,
        val dir: File,
        val bytes: Long,
        /** 非空 = 被活动槽位引用，删除会打断对应功能 */
        val refs: List<String>,
    ) {
        val inUse: Boolean get() = refs.isNotEmpty()
    }

    /** 引用核对（纯函数，JVM 可测）：pullName 命中任一活动槽位即「在用」。 */
    fun refsFor(
        pullName: String,
        selected: String?,
        workerName: String?,
        compactName: String?,
    ): List<String> = buildList {
        if (selected != null && pullName == selected) add("引擎当前选择")
        if (workerName != null && pullName == workerName) add("提纯 worker")
        if (compactName != null && pullName == compactName) add("会话压缩槽")
    }

    /**
     * pullName → 落盘目录三级解析（纯 java.io，JVM 可测）：
     * ① 直接路径 models/<pullName>（MODELSCOPE GGUF："unsloth/X" → models/unsloth/X，实测✓）；
     * ② AI Hub 派生 models/qualcomm/<末段>（"ai-hub-models/Qwen3-4B" → models/qualcomm/Qwen3-4B，
     *    metadata.json 实测 model_id=qwen3_4b）；
     * ③ 兜底扫 models 两层子目录找同名目录，**唯一**命中才认（多命中返回 null，宁可显示不出大小也不删错目录）。
     */
    fun findDir(modelsRoot: File, pullName: String): File? {
        if (!modelsRoot.isDirectory) return null
        val last = pullName.substringAfterLast('/')
        File(modelsRoot, pullName).takeIf { it.isDirectory }?.let { return it }
        File(modelsRoot, "qualcomm/$last").takeIf { it.isDirectory }?.let { return it }
        val hubs = modelsRoot.listFiles()?.filter { it.isDirectory } ?: return null
        val hits = hubs.flatMap { hub -> hub.listFiles()?.filter { it.isDirectory && it.name == last } ?: emptyList() }
        return hits.singleOrNull()
    }

    fun sizeOf(dir: File): Long =
        runCatching { dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)

    fun modelsRoot(context: Context): File =
        File(context.applicationContext.filesDir, "geniex/models")

    /**
     * 活动槽位引用表（suspend：读 settings KV + ai_models 状态）。
     * worker：ai.workerModelId → ai_models 行（state.worker 原样，无回退）。
     * compact：ctx.compactModel 存的是**模型名**，但只有能解析回 ai_models 启用行才算
     * 活引用——AiClient.compactConfig 对解析不到的名字回退 worker（实测本机
     * "ai-hub-models/Qwen3-4B" 就不在表里，压缩实际走 2B GGUF），这类 dangling 值
     * 不能挡删除（QAIRT 4B 3.1GB 是否可清就靠这条判定）。若未来 compactConfig 增加
     * 「本地引擎直连」分支，这里同步放开。
     */
    suspend fun usages(context: Context): Triple<String?, String?, String?> {
        val selected = runCatching { LocalEngine.selectedName(context) }.getOrNull()
        val providerState = runCatching { Graph.aiProviders.state.value }.getOrNull()
        val workerName = providerState?.worker?.modelName
        val compactName = runCatching {
            Graph.settings.kvGet(AiClient.KEY_COMPACT_MODEL)?.trim()?.takeIf { it.isNotBlank() }
        }.getOrNull()?.takeIf { raw ->
            providerState?.models?.any { it.name == raw && it.enabled } == true
        }
        return Triple(selected, workerName, compactName)
    }

    /** 一次性拉全量：CATALOG ∩ 已落盘，带真实字节与引用（UI 主路径）。 */
    suspend fun listOnDisk(context: Context): List<OnDiskModel> {
        val (selected, workerName, compactName) = usages(context)
        val root = modelsRoot(context)
        return LocalEngine.CATALOG.mapNotNull { spec ->
            val dir = findDir(root, spec.pullName) ?: return@mapNotNull null
            OnDiskModel(
                pullName = spec.pullName,
                dir = dir,
                bytes = sizeOf(dir),
                refs = refsFor(spec.pullName, selected, workerName, compactName),
            )
        }
    }
}
