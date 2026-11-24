package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.embed.Embedder
import io.github.m0use001.planability.ai.embed.EmbeddingModel
import io.github.m0use001.planability.ai.embed.EmbeddingModels
import io.github.m0use001.planability.ai.embed.ModelStore
import io.github.m0use001.planability.ai.embed.NoteVectors
import io.github.m0use001.planability.ui.common.motionEnter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v52 知识库：语义检索（本地向量）面板——**两档模型二选一**。
 *
 * 轻量 bge-small-zh（24MB，索引几毫秒/条）与重型 Qwen3-Embedding-0.6B（约 590MB，
 * 300ms–1s/条）。按用户拍板都走**按需下载**：没下载时检索自动退回纯词法。
 *
 * 切换模型要清掉旧模型的向量吗？**不用**——`note_embeddings` 主键是 (noteId, modelId)，
 * 两套向量并存互不干扰；但重型模型索引慢，面板里会显示「已索引 N 篇 / 还差 M 篇」，
 * 索引在后台分批跑（每批 4 篇），关掉面板也会继续。
 */
@Composable
fun SemanticIndexDialog(onDismiss: () -> Unit) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(ModelStore.selected(ctx)) }
    val ready = remember { mutableStateMapOf<String, Boolean>() }
    val counts = remember { mutableStateMapOf<String, Int>() }
    val pending = remember { mutableStateMapOf<String, Int>() }
    var downloadingId by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var label by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }

    fun refresh(model: EmbeddingModel) {
        ready[model.id] = ModelStore.isReady(ctx, model)
        scope.launch {
            counts[model.id] = withContext(Dispatchers.IO) { NoteVectors.indexedCountFor(ctx, model) }
            pending[model.id] = withContext(Dispatchers.IO) { NoteVectors.pendingCount(model) }
        }
    }

    LaunchedEffect(Unit) { EmbeddingModels.ALL.forEach { refresh(it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.motionEnter(),
        title = { Text("语义检索（本地向量）") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "开启后笔记在本地向量化，检索在词法命中之外再补一路「意思相近」的结果。" +
                        "全程离线，不上传任何内容。",
                    style = MaterialTheme.typography.bodySmall
                )
                EmbeddingModels.ALL.forEach { model ->
                    val isReady = ready[model.id] == true
                    val isSelected = selected.id == model.id
                    Surface(
                        color = if (isSelected) MaterialTheme.colorScheme.secondaryContainer
                        else MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = {
                                        selected = model
                                        ModelStore.select(ctx, model)
                                        Embedder.close()   // 换模型必须换会话（重型 590MB 不能并存）
                                        message = "已切换到「" + model.label + "」"
                                        refresh(model)
                                    }
                                )
                                Column(Modifier.weight(1f)) {
                                    Text(model.label, style = MaterialTheme.typography.titleSmall)
                                    Text(
                                        model.desc,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        if (isReady) "已下载（" + ModelStore.sizeMb(ctx, model) + "MB）· 已索引 " +
                                            (counts[model.id] ?: 0) + " 篇" +
                                            ((pending[model.id] ?: 0).let { if (it > 0) "，还差 $it 篇" else "" })
                                        else "未下载",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (!isReady) {
                                    TextButton(
                                        enabled = downloadingId == null,
                                        onClick = {
                                            downloadingId = model.id
                                            progress = 0f
                                            message = "正在下载…"
                                            scope.launch {
                                                val r = ModelStore.download(ctx, model, { p -> progress = p }, { l -> label = l })
                                                downloadingId = null
                                                if (r.isSuccess) {
                                                    ready[model.id] = true
                                                    message = "模型就绪，开始建立索引…"
                                                    // 后台分批跑，不阻塞面板
                                                    scope.launch(Dispatchers.IO) { NoteVectors.ensureIndexed(ctx, model) }
                                                    refresh(model)
                                                } else {
                                                    message = "下载失败：" + (r.exceptionOrNull()?.message ?: "网络错误")
                                                }
                                            }
                                        }
                                    ) {
                                        Text(if (model.approxMb > 100) "下载（" + model.approxMb + "MB，建议 WiFi）"
                                        else "下载并启用")
                                    }
                                } else {
                                    TextButton(enabled = downloadingId == null, onClick = {
                                        scope.launch {
                                            message = "重建索引中…"
                                            val n = withContext(Dispatchers.IO) {
                                                NoteVectors.clear(model)
                                                NoteVectors.ensureIndexed(ctx, model, 500)
                                            }
                                            // 一条都没算出来时把底层原因带出来（模型坏了 / 内存不足），
                                            // 否则用户只会看到"完成 0 篇"不知道为什么
                                            val err = Embedder.lastError
                                            message = if (n == 0 && err != null)
                                                "重建失败：" + err + "\n" +
                                                    withContext(Dispatchers.IO) { Embedder.describe(ctx) }
                                            else "重建完成（本次 $n 篇）"
                                            refresh(model)
                                        }
                                    }) { Text("重建索引") }
                                    TextButton(enabled = downloadingId == null, onClick = {
                                        scope.launch {
                                            withContext(Dispatchers.IO) {
                                                NoteVectors.clear(model)
                                                ModelStore.delete(ctx, model)
                                                Embedder.close()
                                            }
                                            ready[model.id] = false
                                            counts[model.id] = 0
                                            message = "已删除「" + model.label + "」的模型与向量"
                                            refresh(model)
                                        }
                                    }) { Text("删除") }
                                }
                            }
                        }
                    }
                }
                if (downloadingId != null) {
                    LinearProgressIndicator(
                        progress = { if (progress < 0f) 0f else progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        (if (label.isBlank()) "下载中" else "下载 " + label) +
                            (if (progress >= 0f) " " + (progress * 100).toInt() + "%" else "…") +
                            "（可关闭面板，下次继续会断点续传）",
                        style = MaterialTheme.typography.labelSmall
                    )
                }
                if (message.isNotBlank()) {
                    Text(
                        message,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } }
    )
}
