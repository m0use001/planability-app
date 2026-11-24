package io.github.m0use001.planability.ui.files

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.AiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * **文档中文对照阅读**（2026-11-05，用户问「PDFMathTranslate 这类怎么在手机实现」）。
 *
 * ## 手机上为什么不照搬 PDFMathTranslate
 *
 * 它是 Python + 版式重排（把译文嵌回原 PDF 的文本框里），需要完整 PDF 排版引擎——
 * Android 上没有，自己写一套不现实。但它对用户的核心价值其实只有一件：
 * **读英文课件时能逐段看中文，不用来回切翻译软件**。
 *
 * 这个价值不必依赖"重排 PDF"：我们的抽取器已经把 ppt/pptx/docx/xlsx 变成了带页标记的
 * 段落文本，只要在**原文下方逐段插入译文**就是同样的阅读体验，还天然支持 .ppt 老课件
 * 与多份文档——比原版 PDFMathTranslate 覆盖面更广（它只吃 PDF）。
 *
 * ## 为什么按段而不是整篇
 *
 * 整篇丢给模型：长课件会被截断、译文与原文对不上号。按段编号批量翻译
 * （一批 [BATCH] 段，`序号|译文` 协议）能逐段落位，批与批之间互不影响；
 * 某批解析失败就退回**逐段单发**（慢但准），不会整篇报废。
 */
object DocTranslate {

    /** 一批送给模型的段落数：太大容易漏段，太小请求数多 */
    internal const val BATCH = 12

    /** 单次一共最多翻多少段（防止 200 页课件把用户 token 打空） */
    internal const val MAX_PARAS = 160

    /** 一段原文 + 所在页（页标记来自抽取器的 [第 N 页] / [S0.0]） */
    data class Para(val page: String, val text: String)

    private val PAGE_HEAD = Regex("^\\[第\\s*(\\d+)\\s*页]$")
    private val PAGE_TAG = Regex("^\\[S(\\d+)\\.\\d+]\\s*(.*)$")
    private val PARA_TAG = Regex("^\\[P\\d+]\\s*(.*)$")

    /**
     * 把抽取结果切成可翻译段落。
     *
     * 跳过：空行、纯符号/纯数字（页眉页码、公式碎片）、过短的（<2 字符）、
     * 以及**纯大写代号行**（PPT 里常见的 "CET-4" 这类标题不需要翻译也不该消耗额度）——
     * 但保留英文句子。中文占比已经很高的段落也跳过（本来就是中文课件）。
     */
    internal fun splitParas(lines: List<String>): List<Para> {
        val out = ArrayList<Para>()
        var page = ""
        lines.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach
            PAGE_HEAD.find(line)?.let {
                page = "第 " + it.groupValues[1] + " 页"; return@forEach
            }
            val text = when {
                PAGE_TAG.matches(line) -> {
                    val m = PAGE_TAG.find(line)!!
                    page = "第 " + (m.groupValues[1].toIntOrNull()?.plus(1) ?: 0) + " 页"
                    m.groupValues[2].trim()
                }
                PARA_TAG.matches(line) -> PARA_TAG.find(line)!!.groupValues[1].trim()
                else -> line
            }
            if (!worthTranslating(text)) return@forEach
            out += Para(page, text)
        }
        return out.take(MAX_PARAS)
    }

    /** 值得翻 = 有拉丁字母、长度 ≥2、中文字符占比 < 30% */
    internal fun worthTranslating(s: String): Boolean {
        if (s.length < 2) return false
        val latin = s.count { it in 'a'..'z' || it in 'A'..'Z' }
        if (latin < 2) return false
        val cjk = s.count { it.code in 0x4E00..0x9FFF }
        return cjk * 10 < s.length * 3
    }

    /** 批量协议：`序号|原文` 每行一条；模型按同格式回 `序号|译文` */
    internal fun buildUserBlock(texts: List<String>): String = buildString {
        appendLine("把下面每条按序号翻译成简体中文。")
        appendLine("要求：① 只输出「序号|译文」每行一条，序号与输入一致；② 公式、符号、变量名、代码、专有名词保留原样；③ 不要解释、不要合并或拆分条目。")
        appendLine()
        texts.forEachIndexed { i, t -> appendLine((i + 1).toString() + "|" + t.replace("\n", " ")) }
    }

    private val RESULT_LINE = Regex("^\\s*(\\d+)\\s*[|｜]\\s*(.+)$")

    /** 解析模型回执 → 与输入等长的列表（缺失为 null） */
    internal fun parseBatch(raw: String, n: Int): List<String?> {
        val out = arrayOfNulls<String>(n)
        raw.lineSequence().forEach { line ->
            val m = RESULT_LINE.find(line) ?: return@forEach
            val idx = (m.groupValues[1].toIntOrNull() ?: 0) - 1
            if (idx in 0 until n) out[idx] = m.groupValues[2].trim()
        }
        return out.toList()
    }

    private const val SYSTEM = "你是学术文献翻译助手，把用户给的段落翻译成简明准确的简体中文。" +
        "公式、符号、变量名、代码、专有名词保持原样；不加解释；严格按用户要求的输出格式。"

    /**
     * 逐批翻译；每段翻好立刻通过 [onPara] 回调（UI 边翻边显示，不用等全部完成）。
     * 返回错误说明（全成功返回 null）。
     */
    suspend fun translate(
        paras: List<Para>,
        onProgress: (done: Int, total: Int) -> Unit,
        onPara: (index: Int, translation: String) -> Unit
    ): String? {
        if (paras.isEmpty()) return null
        val cfg = runCatching { AiClient.usableWorkerConfig() }.getOrNull()
            ?: return "没有可用的模型配置（先在「模型与智能体」里配一个能用的模型）"
        val total = paras.size
        var done = 0
        paras.chunked(BATCH).forEach { chunk ->
            val texts = chunk.map { it.text }
            val raw = runCatching {
                withContext(Dispatchers.IO) {
                    AiClient.chatWith(cfg, SYSTEM, buildUserBlock(texts), temperature = 0.2)
                }
            }.getOrNull()
            var parsed = raw?.let { parseBatch(it, chunk.size) } ?: List(chunk.size) { null }
            // 批解析失败/漏段：逐段补发（慢但准），仍失败就留空
            if (parsed.any { it == null }) {
                parsed = parsed.mapIndexed { i, v ->
                    v ?: runCatching {
                        withContext(Dispatchers.IO) {
                            AiClient.chatWith(
                                cfg,
                                "把这句话翻译成简体中文，只输出译文本身，不要解释。公式与专有名词保留原样。",
                                texts[i], temperature = 0.2
                            )
                        }
                    }.getOrNull()?.trim()
                }
            }
            parsed.forEachIndexed { i, t ->
                if (!t.isNullOrBlank()) onPara(done + i, t)   // done = 本批之前的段数
            }
            done += chunk.size
            onProgress(done, total)
        }
        return null
    }
}

/** 对照阅读视图：原文 + 译文逐段交替（译文未到之前只显示原文与进度） */
@Composable
internal fun BilingualReader(
    lines: List<String>,
    modifier: Modifier = Modifier
) {
    val paras = remember(lines) { DocTranslate.splitParas(lines) }
    val trans = remember(paras) { mutableStateMapOf<Int, String>() }
    var done by remember(paras) { mutableIntStateOf(0) }
    var err by remember(paras) { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()

    LaunchedEffect(paras) {
        trans.clear(); done = 0; err = null
        err = DocTranslate.translate(
            paras,
            onProgress = { d, _ -> done = d },
            onPara = { i, t -> trans[i] = t }
        )
    }

    Column(modifier.fillMaxSize()) {
        // 进度条：翻译中显示；完成即收起
        if (err == null && done < paras.size) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
                Text(
                    "翻译中 " + done + "/" + paras.size,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { if (paras.isEmpty()) 1f else done.toFloat() / paras.size },
                    modifier = Modifier.fillMaxWidth().height(3.dp)
                )
            }
        }
        err?.let {
            Text(
                "对照翻译不可用：" + it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
        }
        if (paras.isEmpty()) {
            Text(
                "这份文档没有可翻译的英文段落（可能已经是中文，或正文是图片）。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp)
            )
            return@Column
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(paras.size) { i ->
                val p = paras[i]
                Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
                    if (p.page.isNotBlank() && (i == 0 || paras[i - 1].page != p.page)) {
                        Text(
                            p.page, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(bottom = 4.dp)
                        )
                    }
                    Text(
                        p.text, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val t = trans[i]
                    if (!t.isNullOrBlank()) {
                        Row(Modifier.padding(top = 4.dp)) {
                            Box(
                                Modifier
                                    .padding(top = 2.dp)
                                    .width(2.dp)
                                    .height(16.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.5f))
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(t, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

