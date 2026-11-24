package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Done
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.office.AiHealthCheck
import io.github.m0use001.planability.ai.ptl.WebSearch
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * **AI 能力体检**（2026-11-05，用户「一次踩一个坑」整改的收口件）。
 *
 * 一键跑三组检查，红绿显示：
 * ① 文档格式（内置样本走真实抽取管线，逐格式）；
 * ② 真实语料（扫「项目文件」里的文档，列读不出/只读到一点的）；
 * ③ 联网搜索活体探测（固定测试 query，验证结果相关性而非"有没有返回"）。
 *
 * 为什么面板不做成"后台自动跑"：体检会发网络请求、扫用户文件，应当由用户
 * **在想确认的时候**主动触发（出门学习前点一下），也便于对照"上次是什么样"。
 */
@Composable
internal fun AiHealthScreen(onDone: () -> Unit) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<AiHealthCheck.Report?>(null) }
    var running by remember { mutableStateOf(false) }
    var progressText by remember { mutableStateOf("") }
    // promptfoo 式评估用例表（只读、无副作用）
    val evalRows = remember { androidx.compose.runtime.mutableStateListOf<Pair<io.github.m0use001.planability.ai.EvalSuite.Case, io.github.m0use001.planability.ai.EvalSuite.Result>>() }
    var evalSummary by remember { mutableStateOf<String?>(null) }

    fun run() {
        if (running) return
        running = true
        report = null
        evalRows.clear()
        evalSummary = null
        scope.launch {
            val items = mutableListOf<AiHealthCheck.Item>()
            val parse: (File) -> String = { io.github.m0use001.planability.ai.ptl.ToolExecImpls.parseDocText(it) }
            runCatching {
                withContext(Dispatchers.IO) {
                    val dir = File(io.github.m0use001.planability.data.Graph.appContext.cacheDir, "ai-health")
                    progressText = "① 检查文档格式…"
                    items += AiHealthCheck.checkFormats(dir, parse)
                    // 清理体检样本（只删自己生成的）
                    dir.listFiles()?.filter { it.name.startsWith("health-sample") }?.forEach { it.delete() }

                    progressText = "② 扫描真实语料…"
                    val files = runCatching {
                        val projects = io.github.m0use001.planability.data.Graph.projects.all()
                        projects.flatMap { p ->
                            val base = File(
                                io.github.m0use001.planability.data.ProjectFileRepo.dirOf(p.id),
                                io.github.m0use001.planability.data.ProjectDirs.FILES
                            )
                            base.walkTopDown().filter { it.isFile }.toList()
                        }
                    }.getOrDefault(emptyList())
                    items += AiHealthCheck.checkCorpus(files, parse)

                    progressText = "③ 探测联网搜索…"
                    // 三组客观探测（官网 / 多词内容页 / 菜谱正文）——覆盖"会坏的那一类输入"，
                    // 不再只探单词品牌 query（那条永远不会红，见 AiHealthCheck.SEARCH_PROBES 注释）
                    val probeResults = AiHealthCheck.SEARCH_PROBES.map { p ->
                        p to runCatching {
                            WebSearch.search(p.query, 5).map { it.title to it.url }
                        }.getOrDefault(emptyList())
                    }
                    items += AiHealthCheck.judgeSearchProbes(probeResults)
                    // 降级留痕：最近 7 天 web_search 被判"质量差"的次数（tools_audit）
                    val degraded = runCatching {
                        io.github.m0use001.planability.data.Graph.db.toolAuditDao()
                            .degradedSearches(System.currentTimeMillis() - 7L * 24 * 3600 * 1000)
                    }.getOrDefault(0)
                    items += AiHealthCheck.judgeDegradedHistory(degraded, 7)

                    // ⑤ 附件链路（2026-11-09 夜）：最近 8 份附件走真实解析——
                    // 样本绿 ≠ 用户文件绿，附件才是材料主入口
                    progressText = "⑤ 检查附件链路…"
                    val attFiles = runCatching {
                        File(io.github.m0use001.planability.data.Graph.appContext.filesDir, "attachments")
                            .listFiles()?.sortedByDescending { it.lastModified() }?.toList()
                    }.getOrNull().orEmpty()
                    // 体检用"快速探测"：PDF 只读文字层，扫描件标出来（不在体检里跑全量 OCR，
                    // 否则几页扫描件就能把"30 秒体检"拖成几分钟）
                    items += AiHealthCheck.checkAttachments(attFiles) { f ->
                        if (f.extension.equals("pdf", true))
                            io.github.m0use001.planability.ai.office.PoiDocumentReader.readPdfText(f)
                                ?: "[扫描件：无文字层，会话里读时会走 OCR]"
                        else parse(f)
                    }

                    // ⑥ 网页抓取活体：example.com + 内容校验（断网/劫持都能红）
                    progressText = "⑥ 探测网页抓取…"
                    items += AiHealthCheck.checkWebFetch { url ->
                        runCatching {
                            okhttp3.OkHttpClient.Builder()
                                .callTimeout(8, java.util.concurrent.TimeUnit.SECONDS)
                                .build()
                                .newCall(
                                    okhttp3.Request.Builder().url(url)
                                        .header("User-Agent", "Mozilla/5.0 (Android) Planability-Health")
                                        .build()
                                )
                                .execute().use { resp ->
                                    if (resp.isSuccessful) resp.body?.string()?.take(4000) else null
                                }
                        }.getOrNull()
                    }

                    // ⑦ 云搜索兜底活体（2026-11-14）：开关关 = INFO 跳过；开 = 真调
                    // DeepSeek Search API（判据在期望关键词，非自证）。走真实入口
                    // searchIfEnabled（护栏/key 检查同产品路径）。
                    progressText = "⑦ 探测云搜索兜底…"
                    run {
                        val enabled = runCatching {
                            io.github.m0use001.planability.data.Graph.settings.snapshot.value.cloudSearchEnabled
                        }.getOrDefault(false)
                        val cloudHits = if (enabled) runCatching {
                            io.github.m0use001.planability.ai.ptl.DeepSeekCloud.searchIfEnabled("Example Domain site:example.com", 3)
                        }.getOrDefault(emptyList()) else emptyList()
                        items += AiHealthCheck.judgeCloudSearch(
                            cloudHits.map { it.title to it.url }, enabled
                        )
                    }

                    // ④ 评估用例（promptfoo 式）：行为对不对，而不是通道通不通
                    progressText = "④ 运行评估用例…"
                    val snap = io.github.m0use001.planability.ai.EvalSuite.run(
                        io.github.m0use001.planability.ai.EvalSuite.builtin(dir)
                    ) { c, r ->
                        evalRows += c to r
                    }
                    evalSummary = snap.summary()
                }
            }.onFailure {
                items += AiHealthCheck.Item(
                    "体检本身出错", AiHealthCheck.Level.FAIL,
                    it.message ?: "未知错误", "请把这条信息发给开发者"
                )
            }
            progressText = ""
            report = AiHealthCheck.Report(items, System.currentTimeMillis())
            running = false
        }
    }

    // 进入即自动跑一次——用户点进来就是为了看结果，不该再点第二下
    LaunchedEffect(Unit) { run() }

    io.github.m0use001.planability.app.PredictiveBackOverlay(onBack = {
        haptics.perform(HapticEngine.Kind.Tap)
        onDone()
    }) {
        Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 顶栏（与会话式圆角栏一致）
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .statusBarsPadding()
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 10.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        onDone()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    Text(
                        "AI 能力体检", style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 52.dp, top = 2.dp)
                ) {
                    val r = report
                    val tint = when (r?.overall) {
                        AiHealthCheck.Level.OK -> MaterialTheme.colorScheme.primary
                        AiHealthCheck.Level.FAIL -> MaterialTheme.colorScheme.error
                        AiHealthCheck.Level.WARN -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Text(
                        if (running) (progressText.ifBlank { "检查中…" }) else (r?.summary() ?: "未检查"),
                        style = MaterialTheme.typography.labelMedium,
                        color = tint
                    )
                }
            }

            if (running) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(3.dp))
            }

            Column(
                Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "出门学习前点一次，30 秒知道哪个能力是坏的——而不是学到一半才发现。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                report?.items?.forEach { item -> HealthRow(item) }

                // ── 评估用例表（promptfoo 式：用例 → 断言 → 成败 + 证据 + 耗时）──
                if (evalSummary != null || evalRows.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "评估用例", style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                        )
                        Text(
                            evalSummary ?: ("运行中 " + evalRows.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Text(
                        "只读用例：搜索该不该命中、文档失败契约、记忆镜像与库是否一致。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    evalRows.forEach { (c, r) -> EvalRow(c, r) }
                }
                if (report == null && !running) {
                    Text(
                        "点下方按钮开始检查",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 一键取证（2026-11-09 夜）：体检报告 + 最近 100 条工具审计打包落
                // Download/Planability/evidence-<时间>.txt——报障时不用再描述现象，
                // 把这一个文件发给开发即可（现象、证据、时间线都在里面）。
                if (report != null) {
                    val ectx = LocalContext.current
                    var exported by remember { mutableStateOf<String?>(null) }
                    androidx.compose.material3.OutlinedButton(
                        onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            scope.launch {
                                exported = runCatching {
                                    val audits = io.github.m0use001.planability.data.Graph.db.toolAuditDao().recent()
                                    exportEvidence(ectx, report, audits)
                                }.getOrElse { "导出失败：" + (it.message ?: "未知错误") }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            if (exported == null) "导出取证包（报告 + 最近工具审计）"
                            else "已导出：$exported",
                            style = MaterialTheme.typography.labelMedium
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Button(
                    onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        run()
                    },
                    enabled = !running,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (running) "检查中…" else "重新检查")
                }
            }
        }
    }
}

/** 一条评估用例：状态点 + 分组 + 用例名 + 断言描述 +（失败时）实测证据 + 耗时 */
@Composable
private fun EvalRow(c: io.github.m0use001.planability.ai.EvalSuite.Case, r: io.github.m0use001.planability.ai.EvalSuite.Result) {
    val cs = MaterialTheme.colorScheme
    val tint = if (r.passed) cs.primary else cs.error
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (r.passed) cs.surfaceContainer else cs.errorContainer.copy(alpha = 0.35f))
            .padding(horizontal = 12.dp, vertical = 9.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tint)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                c.group, style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant
            )
            Spacer(Modifier.width(6.dp))
            Text(
                c.name, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f)
            )
            Text(
                (if (r.passed) "PASS" else "FAIL") + " · " + r.ms + "ms",
                style = MaterialTheme.typography.labelSmall, color = tint
            )
        }
        Text(
            c.assertion, style = MaterialTheme.typography.labelSmall,
            color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp, start = 16.dp)
        )
        if (!r.passed) {
            Text(
                "实测：" + r.evidence, style = MaterialTheme.typography.labelSmall,
                color = tint, modifier = Modifier.padding(top = 2.dp, start = 16.dp)
            )
        }
        if (c.network && !r.passed) {
            Text(
                "（这条需要联网，离线时会失败）", style = MaterialTheme.typography.labelSmall,
                color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp)
            )
        }
    }
}

/** 一条检查结果：色点 + 名称 + 说明 +（失败时）建议 */
@Composable
private fun HealthRow(item: AiHealthCheck.Item) {
    val cs = MaterialTheme.colorScheme
    val (tint, label) = when (item.level) {
        AiHealthCheck.Level.OK -> cs.primary to "正常"
        AiHealthCheck.Level.WARN -> cs.tertiary to "注意"
        AiHealthCheck.Level.FAIL -> cs.error to "不可用"
        AiHealthCheck.Level.SKIP -> cs.onSurfaceVariant to "跳过"
    }
    Surface(
        color = if (item.level == AiHealthCheck.Level.FAIL) cs.errorContainer.copy(alpha = 0.40f)
        else cs.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier
                    .padding(top = 4.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(50))
                    .background(tint)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.name, style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                    )
                    Text(label, style = MaterialTheme.typography.labelSmall, color = tint)
                }
                Text(
                    item.detail, style = MaterialTheme.typography.bodySmall,
                    color = cs.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp)
                )
                if (item.advice.isNotBlank()) {
                    Text(
                        "建议：" + item.advice, style = MaterialTheme.typography.labelSmall,
                        color = tint, modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 一键取证包（2026-11-09 夜）：体检报告 + 最近工具审计落 `Download/Planability/`。
 * 报障时把这一个文件交出来即可——现象、证据、时间线都在里面，不用再口头描述。
 */
private suspend fun exportEvidence(
    context: android.content.Context,
    report: AiHealthCheck.Report?,
    audits: List<io.github.m0use001.planability.data.ToolAudit>
): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
    val ts = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
        .format(java.time.LocalDateTime.now())
    val name = "evidence-$ts.txt"
    val sb = StringBuilder()
    sb.appendLine("Planability 取证包 $ts")
    sb.appendLine("device=${android.os.Build.MODEL}  android=${android.os.Build.VERSION.RELEASE}")
    sb.appendLine()
    sb.appendLine("== 体检报告 ==")
    report?.items?.forEach {
        sb.appendLine("[${it.level}] ${it.name}: ${it.detail}" +
            (if (it.advice.isNotBlank()) " ｜ 建议: ${it.advice}" else ""))
    }
    sb.appendLine()
    sb.appendLine("== 最近 ${audits.size} 条工具审计 ==")
    val fmt = java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA)
    audits.forEach { a ->
        sb.appendLine(
            ("#${a.id} ${fmt.format(java.util.Date(a.at))} [s${a.sessionId}] ${a.tool} " +
                "${a.outcome} ${a.ms}ms  ${a.detail}").take(300)
        )
    }
    // MediaStore 写 Download/Planability（与 AutoBackup 同一目录，用户看得到）
    val values = android.content.ContentValues().apply {
        put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
        put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
        put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
            android.os.Environment.DIRECTORY_DOWNLOADS + "/Planability")
    }
    val resolver = context.contentResolver
    val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: throw IllegalStateException("MediaStore 写入被拒")
    resolver.openOutputStream(uri)?.use { it.write(sb.toString().toByteArray(Charsets.UTF_8)) }
    name
}
