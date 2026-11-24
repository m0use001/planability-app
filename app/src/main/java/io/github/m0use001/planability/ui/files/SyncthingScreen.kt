@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package io.github.m0use001.planability.ui.files

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.ui.theme.ShapeTokens
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.syncthing.SyncthingEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import io.github.m0use001.planability.ui.common.filterChipColors

/** 同步中心：Syncthing 原生内核控制 + 设备配对 + 文件夹双向同步 */
@Composable
fun SyncthingScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val engine by SyncthingEngine.state.collectAsState()
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboardManager.current

    var folders by remember { mutableStateOf<List<FolderRow>>(emptyList()) }
    var devices by remember { mutableStateOf<List<DeviceRow>>(emptyList()) }
    var showAddFolder by remember { mutableStateOf(false) }
    var showAddDevice by remember { mutableStateOf(false) }
    /** v25：点文件夹行打开设置（共享给哪些设备 / 同步方式 / 暂停 / 忽略模式） */
    var editFolder by remember { mutableStateOf<FolderRow?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    // 提示展示 6 秒后自动清除。原来清空挂在 refresh() 成功路径里，4 秒轮询会把刚设置的
    // 成功/失败提示立刻擦掉——错误信息永远看不见（添加文件夹失败就这样被吞了一整晚）
    LaunchedEffect(message) {
        if (message != null) {
            delay(6000)
            message = null
        }
    }
    // v19.15：手动触发 dump 导出（平时只有启动 1.5s 后自动写一次，改完数据想立刻推给电脑用这个）
    var dumping by remember { mutableStateOf(false) }
    // v19.60：完整模式 = Syncthing 原版网页界面（内嵌 WebView，API Key 自动带上）
    var showFullMode by remember { mutableStateOf(false) }
    // v19.61：内核随应用启动的开关（pc-a 10-18 assign）。走 SettingsRepo 旁路 KV、缺键=开，
    // 不进 SettingsSnapshot 正式字段（同 label_colors 饱和度那类小配置的先例）
    var autoStart by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        autoStart = Graph.settings.raw("sync.autostart") != "0"
    }

    fun exportDumpNow() {
        if (dumping) return
        dumping = true
        scope.launch {
            val f = io.github.m0use001.planability.data.PhoneDump.write(context)
            dumping = false
            message = if (f != null) "已导出到 ${f.parent ?: "?"}" else "导出失败（目录不可写或数据为空）"
        }
    }

    fun refresh() {
        scope.launch {
            if (!SyncthingEngine.isRunning) return@launch
            runCatching {
                val fArr = JSONArray(SyncthingEngine.configFolders(context))
                val dArr = JSONArray(SyncthingEngine.configDevices(context))
                val conns = JSONObject(SyncthingEngine.connections(context))
                    .optJSONObject("connections")
                val fList = (0 until fArr.length()).map { i ->
                    val o = fArr.getJSONObject(i)
                    val st = runCatching { JSONObject(SyncthingEngine.dbStatus(context, o.getString("id"))) }.getOrNull()
                    val g = st?.optLong("globalBytes") ?: 0L
                    val s = st?.optLong("inSyncBytes") ?: 0L
                    val paused = o.optBoolean("paused")
                    val devs = o.optJSONArray("devices")
                    FolderRow(
                        id = o.getString("id"),
                        label = o.optString("label").ifBlank { o.getString("id") },
                        path = o.optString("path"),
                        // paused 优先于 state：否则暂停了还显示"空闲"，用户以为在同步
                        stateText = if (paused) "已暂停" else st?.optString("state") ?: "-",
                        progress = if (g > 0) (s.toFloat() / g).coerceIn(0f, 1f) else 1f,
                        paused = paused,
                        type = o.optString("type").ifBlank { "sendreceive" },
                        deviceIds = if (devs == null) emptyList() else (0 until devs.length()).map {
                            devs.getJSONObject(it).optString("deviceID")
                        }
                    )
                }
                val dList = (0 until dArr.length()).map { i ->
                    val o = dArr.getJSONObject(i)
                    val id = o.getString("deviceID")
                    DeviceRow(
                        id = id,
                        name = o.optString("name"),
                        connected = conns?.optJSONObject(id)?.optBoolean("connected") == true
                    )
                }.filter { it.id != SyncthingEngine.state.value.myId }
                // /rest/config/devices 永远包含本机自己，而本机不可能和自己 connected——
                // 不过滤的话「远程设备」里恒有一行「未连接」，用户会以为同步坏了（pc-a 10-18 assign）。
                // 本机信息在上方「此设备」卡里有专属位置；文件夹共享不受影响（见 FolderDetailDialog：
                // 不在勾选列表里的本机/残留条目保存时原样保留）。
                folders = fList
                devices = dList
            }.onFailure { if (message == null) message = it.message }
        }
    }

    LaunchedEffect(engine.running, engine.myId) {
        if (engine.running) {
            while (true) {
                refresh()
                delay(4000)
            }
        }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
                // 返回 = 中性轻触（Tap），见 core/Haptics.kt 的 HIG 映射表
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                Text("同步中心 · Syncthing", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }

            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // ---------- 此设备（参考 Syncthing 官方排版：分区大标题 + 卡片内左标签右值） ----------
                SyncSectionTitle("此设备")
                Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(Modifier.padding(start = 14.dp, end = 36.dp, top = 4.dp, bottom = 4.dp)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        ) {
                            Box(
                                Modifier.size(10.dp).background(
                                    if (engine.running) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.outline, CircleShape
                                )
                            )
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text("内核", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    if (engine.running) "运行中 · Syncthing v2 (arm64)" else "未启动",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                haptics.perform(io.github.m0use001.planability.core.HapticEngine.Kind.ImpactLight)
                                if (engine.running) SyncthingEngine.stop() else SyncthingEngine.start(context)
                            }) {
                                Text(
                                    if (engine.running) "停止" else "启动",
                                    color = if (engine.running) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // v19.61：随应用启动。关掉不影响当前正在运行的内核（用上面的「停止」），
                        // 只影响下次打开应用；打开时若内核还没跑，顺手拉起来。
                        SyncRowDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("随应用启动", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "每次打开应用自动拉起同步内核，不用再进本页手动点「启动」；关闭只影响下次启动",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = autoStart,
                                onCheckedChange = { on ->
                                    // 开/关都是选择，与「暂停同步」Switch 同档（Select）
                                    haptics.perform(HapticEngine.Kind.Select)
                                    autoStart = on
                                    scope.launch {
                                        runCatching { Graph.settings.putRawValue("sync.autostart", if (on) "1" else "0") }
                                        if (on && !SyncthingEngine.isRunning) SyncthingEngine.start(context)
                                    }
                                }
                            )
                        }
                        if (engine.running && engine.myId.isNotBlank()) {
                            SyncRowDivider()
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text("本机设备 ID", style = MaterialTheme.typography.bodyLarge)
                                    Text(
                                        "在电脑端 Syncthing 里添加它",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        engine.myId,
                                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                IconButton(onClick = {
                                    clipboard.setText(AnnotatedString(engine.myId))
                                    message = "设备 ID 已复制"
                                }) { Icon(Icons.Rounded.ContentCopy, "复制设备 ID", Modifier.size(18.dp)) }
                            }
                        }
                        // v19.60：完整模式入口。放在「此设备」卡片里 —— 它和内核是一件事：
                        // GUI 就是内核自己 serve 的网页，没内核时打开只会白屏，所以点击时顺手把内核拉起来。
                        SyncRowDivider()
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("完整模式", style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    "Syncthing 原版界面：配对、文件夹高级项、版本与日志全在里面",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            TextButton(onClick = {
                                // 普通点击 = 中性 → Tap
                                haptics.perform(HapticEngine.Kind.Tap)
                                if (!SyncthingEngine.isRunning) {
                                    SyncthingEngine.start(context)
                                    message = "内核启动中，稍等一两秒…"
                                }
                                showFullMode = true
                            }) { Text("打开") }
                        }
                        if (engine.running && engine.myId.isBlank()) {
                            Text(
                                "内核启动中，等待设备 ID…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                        engine.error?.let {
                            Text(
                                it, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                // ---------- 数据同步（dump）----------
                // v19.15：刻意放在 engine.running 判断**之外**——导出 dump 不依赖内核
                // （PhoneDump.defaultDir 会退到 getExternalFilesDir/synced），
                // 内核没起/没配对时用户同样需要这个入口，否则「改完数据立刻推给电脑」还是做不到
                SyncSectionTitle("数据同步（dump）")
                Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column(
                        Modifier.padding(start = 14.dp, end = 36.dp, top = 12.dp, bottom = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            "把日程/待办/笔记等数据导出成 phone-dump.json 放进同步文件夹，电脑端会自动合并。应用启动时会自动导一次；刚改完数据想立刻推给电脑，点下面这个。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                            OutlinedButton(onClick = { exportDumpNow() }, enabled = !dumping) {
                                Text(if (dumping) "正在导出…" else "立即导出 dump 给电脑")
                            }
                        }
                    }
                }

                if (engine.running && engine.myId.isNotBlank()) {
                    // ---------- 远程设备 ----------
                    SyncSectionTitle("远程设备（${devices.size}）")
                    Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(start = 14.dp, end = 36.dp, top = 4.dp, bottom = 4.dp)) {
                            if (devices.isEmpty()) {
                                // 修掉本机误列后的空态：一行「未连接」都没有了，得告诉用户去哪配对
                                Text(
                                    "还没有远程设备。点「＋ 添加设备」把电脑端 Syncthing 的设备 ID 加进来。",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 10.dp)
                                )
                            }
                            devices.forEachIndexed { i, d ->
                                if (i > 0) SyncRowDivider()
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)
                                ) {
                                    Box(Modifier.size(10.dp).background(
                                        if (d.connected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.outline, CircleShape))
                                    Spacer(Modifier.width(10.dp))
                                    Text(
                                        d.name.ifBlank { "未命名设备" },
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1, overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        if (d.connected) "已连接" else "未连接",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (d.connected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            SyncRowDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showAddDevice = true }) { Text("＋ 添加设备") }
                            }
                        }
                    }

                    // ---------- 同步文件夹 ----------
                    SyncSectionTitle("同步文件夹（${folders.size}）")
                    Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(Modifier.padding(start = 14.dp, end = 36.dp, top = 4.dp, bottom = 4.dp)) {
                            if (folders.isEmpty()) {
                                Text(
                                    "暂无同步文件夹",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(vertical = 12.dp)
                                )
                            }
                            folders.forEachIndexed { i, f ->
                                if (i > 0) SyncRowDivider()
                                // 整行可点 → 打开该文件夹的设置（共享设备/方式/暂停/忽略模式）
                                Column(
                                    Modifier.fillMaxWidth()
                                        .clip(RoundedCornerShape(10.dp))
                                        .clickable { editFolder = f }
                                        .padding(vertical = 10.dp)
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(
                                                f.label,
                                                style = MaterialTheme.typography.bodyLarge,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                            Text(
                                                // 同步方式紧跟路径：用户要一眼看出这个目录是"只发"还是"双向"
                                                f.path + " · " + folderTypeLabel(f.type),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                maxLines = 1, overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                        Text(
                                            f.stateText,
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = if (f.paused) MaterialTheme.colorScheme.outline
                                            else MaterialTheme.colorScheme.primary
                                        )
                                        // 暂停/恢复（不进对话框，一步到位）
                                        // 暂停/恢复 = 开关型选择，和「暂停同步」Switch 同一档（Select）
                                        IconButton(onClick = {
                                            haptics.perform(HapticEngine.Kind.Select)
                                            scope.launch {
                                                runCatching {
                                                    SyncthingEngine.setFolderPaused(context, f.id, !f.paused)
                                                }.onFailure { message = it.message }
                                                refresh()
                                            }
                                        }) {
                                            Icon(
                                                if (f.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                                                if (f.paused) "恢复同步" else "暂停同步",
                                                Modifier.size(18.dp),
                                                tint = if (f.paused) MaterialTheme.colorScheme.primary
                                                else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        IconButton(onClick = {
                                            // 把文件夹移出同步（配置被改）= 破坏性，和删除确认同档（ImpactHeavy）
                                            haptics.perform(HapticEngine.Kind.ImpactHeavy)
                                            scope.launch {
                                                runCatching {
                                                    val cfg = JSONArray(SyncthingEngine.configFolders(context))
                                                    val kept = JSONArray()
                                                    for (k in 0 until cfg.length()) {
                                                        if (cfg.getJSONObject(k).getString("id") != f.id) kept.put(cfg.getJSONObject(k))
                                                    }
                                                    val all = JSONObject(SyncthingEngine.rest(context, "GET", "/rest/config"))
                                                    all.put("folders", kept)
                                                    SyncthingEngine.rest(context, "POST", "/rest/system/config?set=1", all.toString())
                                                    refresh()
                                                }.onFailure { message = it.message }
                                            }
                                        }) {
                                            Icon(
                                                Icons.Rounded.Delete, "移除",
                                                Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    // v45 M3E：手写双 Box 进度条 → 确定进度用 LinearWavyProgressIndicator。
                                    // 为什么换而不是删：这里显示的是"同步到百分之多少"，删掉就丢了信息；
                                    // 波浪形是 M3E 对**较长过程**（同步往往几十秒）的推荐表达，静止的细直条看着像卡住。
                                    // 也不再自己 clip 圆角/限高 —— 组件自带描边与波浪振幅，裁掉会把波形切平。
                                    androidx.compose.material3.LinearWavyProgressIndicator(
                                        progress = { f.progress },
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                }
                            }
                            SyncRowDivider()
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { showAddFolder = true }) { Text("＋ 添加文件夹") }
                            }
                        }
                    }

                    SyncSectionTitle("怎么用")
                    Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Column(
                            Modifier.padding(start = 14.dp, end = 36.dp, top = 10.dp, bottom = 12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            listOf(
                                "电脑端安装 Syncthing，复制它的设备 ID",
                                "点「添加设备」把 ID 粘贴进来",
                                "点「添加文件夹」浏览选目录，勾选要共享给哪些设备",
                                "电脑端点接受，之后两边自动双向同步；点文件夹行可随时改共享对象/方式/暂停"
                            ).forEachIndexed { i, step ->
                                Row(verticalAlignment = Alignment.Top) {
                                    Text(
                                        "${i + 1}",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.primary,
                                        modifier = Modifier.width(18.dp)
                                    )
                                    Text(
                                        step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                } else {
                    SyncSectionTitle("说明")
                    Card(shape = ShapeTokens.card, colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                        Text(
                            "内核默认随应用自动启动（上方开关可关）。启动后可：查看本机设备 ID、与电脑端配对、添加双向同步文件夹并查看实时进度；配置与同步索引持久保存。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 14.dp, end = 36.dp, top = 12.dp, bottom = 12.dp)
                        )
                    }
                }

                message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                Spacer(Modifier.height(30.dp))
            }
        }
    }

    // v19.60：完整模式（Syncthing 原版网页界面）
    if (showFullMode) {
        SyncthingFullModeScreen(onDone = { showFullMode = false })
    }

    if (showAddDevice) {
        var devId by remember { mutableStateOf("") }
        var devName by remember { mutableStateOf("") }
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showAddDevice = false },
            title = { Text("添加设备") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = devId, onValueChange = { devId = it }, label = { Text("设备 ID（7 组字母-数字）") })
                    OutlinedTextField(value = devName, onValueChange = { devName = it }, label = { Text("备注名（如 我的电脑）") })
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    scope.launch {
                        runCatching { SyncthingEngine.addDevice(context, devId, devName) }
                            .onSuccess { showAddDevice = false; message = "设备已添加，等待对方接受"; refresh() }
                            .onFailure { message = it.message }
                    }
                }, enabled = devId.trim().length >= 7) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { showAddDevice = false }) { Text("取消") } }
        )
    }

    if (showAddFolder) {
        var label by remember { mutableStateOf("") }
        // 路径改由目录选择器产出，不再是手打绝对路径——手打输错一个字符要等 Syncthing 报错才发现
        var path by remember { mutableStateOf<String?>(null) }
        var picking by remember { mutableStateOf(false) }
        // 默认全选 = 旧行为（共享给所有已配对设备）；用户按需取消勾选即"只向某个设备同步"
        var checked by remember { mutableStateOf(devices.map { it.id }.toSet()) }
        // 失败原因显示在对话框内部：message 在对话框后面、还会被轮询清掉，等于看不见
        var addErr by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showAddFolder = false },
            title = { Text("添加同步文件夹") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("名称") }, singleLine = true)
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.weight(1f)) {
                            Text("目录", style = MaterialTheme.typography.labelMedium)
                            Text(
                                path ?: "尚未选择",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (path == null) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2, overflow = TextOverflow.Ellipsis
                            )
                        }
                        TextButton(onClick = { picking = true }) { Text(if (path == null) "选择…" else "更改") }
                    }
                    Text("共享给（可多选，只勾选的设备会同步）", style = MaterialTheme.typography.labelMedium)
                    if (devices.isEmpty()) {
                        Text(
                            "还没有远程设备。可先添加，稍后点文件夹行再共享。",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    devices.forEach { d ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth()
                                .clickable {
                                    // 勾/取消勾 = 选择（与开关、chip 同档最轻的 Select）
                                    haptics.perform(HapticEngine.Kind.Select)
                                    checked = if (d.id in checked) checked - d.id else checked + d.id
                                }
                        ) {
                            Checkbox(
                                checked = d.id in checked,
                                onCheckedChange = { on ->
                                    // 直接点方块时走这里（子节点消费了点击，行 clickable 不触发）
                                    haptics.perform(HapticEngine.Kind.Select)
                                    checked = if (on) checked + d.id else checked - d.id
                                }
                            )
                            Text(d.name.ifBlank { d.id.take(12) }, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Text(
                        "「仅发送」= 只往对方推、不接收对方改动；「仅接收」相反。建好后点文件夹行随时改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    addErr?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showAddFolder = false }, modifier = Modifier.weight(1f)) {
                        Text("取消", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = {
                            val sel = path ?: return@TextButton
                            addErr = null
                            scope.launch {
                                runCatching {
                                    // 文件夹 id 是两端共享契约：选中的是应用默认 synced 目录时
                                    // 必须用固定 id planability-data（= 电脑端 DATA_FOLDER_ID），
                                    // 否则「手机先建文件夹」这条路结构上不可能和电脑汇成同一个
                                    // （pc-a 10-18 assign；此前写死时间戳 id 就是这个坑）。
                                    val defaultSynced = File(
                                        context.getExternalFilesDir(null) ?: context.filesDir, "synced"
                                    ).canonicalPath
                                    val isDefaultSynced = runCatching {
                                        File(sel).canonicalPath == defaultSynced
                                    }.getOrDefault(false)
                                    SyncthingEngine.addFolder(
                                        context,
                                        id = if (isDefaultSynced) SyncthingEngine.DATA_FOLDER_ID
                                        else "plan-" + (System.currentTimeMillis() / 1000),
                                        label = label.trim().ifBlank { File(sel).name },
                                        path = sel,
                                        // devices 列表已过滤掉本机（见 refresh），这里把本机补回去：
                                        // 文件夹的 devices 里得有自己，与旧行为一致
                                        deviceIds = (
                                            devices.map { it.id }.filter { it in checked } +
                                                SyncthingEngine.state.value.myId
                                            ).filter { it.isNotBlank() }.distinct()
                                    )
                                }.onSuccess {
                                    showAddFolder = false
                                    message = if (checked.isEmpty()) "文件夹已添加（仅本机，未共享）" else "文件夹已添加"
                                    refresh()
                                }.onFailure { addErr = it.message ?: "添加失败" }
                            }
                        },
                        enabled = !path.isNullOrBlank() && label.isNotBlank(),
                        modifier = Modifier.weight(1f)
                    ) { Text("添加", maxLines = 1, softWrap = false) }
                }
            }
        )
        if (picking) {
            DirPickerDialog(
                context = context,
                existingPaths = folders.map { it.path },
                onPick = { f ->
                    path = f.absolutePath
                    if (label.isBlank()) label = f.name
                    picking = false
                },
                onDismiss = { picking = false }
            )
        }
    }

    editFolder?.let { row ->
        FolderDetailDialog(
            row = row,
            devices = devices,
            onDismiss = { editFolder = null },
            onDone = { err ->
                editFolder = null
                if (err == null) { message = "已保存"; refresh() } else message = err
            }
        )
    }
}

/**
 * v25：文件夹设置 —— 「只向哪个设备同步」的落点。
 *
 * 固定套路 **GET 整份 → 内存里改 → PUT 整份**：Syncthing 对 PUT 里缺失的字段一律回落默认值，
 * 本地只拼要改的几个字段会把 devices/ignorePatterns 静默清空（真发生过的话用户是同步悄悄断掉才发现）。
 *
 * devices 增删规则刻意做成"在原数组上增删"：原数组里可能有**本机设备**和已从设备列表消失的
 * 残留 id，它们不在勾选框里出现，绝不能因为"没勾"就被抹掉。
 */
@Composable
private fun FolderDetailDialog(
    row: FolderRow,
    devices: List<DeviceRow>,
    onDismiss: () -> Unit,
    onDone: (String?) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var loaded by remember { mutableStateOf(false) }
    var label by remember { mutableStateOf(row.label) }
    var type by remember { mutableStateOf(row.type) }
    var paused by remember { mutableStateOf(row.paused) }
    var checked by remember { mutableStateOf(row.deviceIds.toSet()) }
    var ignoreText by remember { mutableStateOf("") }
    var loadErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(row.id) {
        runCatching {
            val o = SyncthingEngine.getFolder(context, row.id)
            label = o.optString("label").ifBlank { row.label }
            type = o.optString("type").ifBlank { "sendreceive" }
            paused = o.optBoolean("paused")
            val devs = o.optJSONArray("devices")
            checked = if (devs == null) emptySet() else (0 until devs.length()).map {
                devs.getJSONObject(it).optString("deviceID")
            }.toSet()
            val ign = o.optJSONArray("ignorePatterns")
            ignoreText = if (ign == null) "" else (0 until ign.length()).map { ign.getString(it) }
                .joinToString("\n")
            loaded = true
        }.onFailure { loadErr = it.message }
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("文件夹设置") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.verticalScroll(rememberScrollState())
            ) {
                when {
                    loadErr != null -> Text(
                        "读取配置失败：$loadErr", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    !loaded -> Text("读取配置中…", style = MaterialTheme.typography.bodySmall)
                    else -> {
                        Text(
                            row.path, style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2, overflow = TextOverflow.Ellipsis
                        )
                        OutlinedTextField(value = label, onValueChange = { label = it }, label = { Text("名称") }, singleLine = true)
                        Text("同步方式", style = MaterialTheme.typography.labelMedium)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "sendreceive" to "双向", "sendonly" to "仅发送", "receiveonly" to "仅接收"
                            ).forEach { (k, l) ->
                                // 同步方式三选一 = 选择（HIG selection → 最轻的 Select）
                                FilterChip(colors = filterChipColors(), selected = type == k, onClick = {
                                    type = k
                                    haptics.perform(HapticEngine.Kind.Select)
                                }, label = { Text(l) })
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                            Text("暂停同步", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            // 开/关都是选择，同一档手感
                            Switch(checked = paused, onCheckedChange = {
                                haptics.perform(HapticEngine.Kind.Select)
                                paused = it
                            })
                        }
                        Text("共享给设备", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "只勾选的设备会同步；不勾任何 = 仅本机",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (devices.isEmpty()) {
                            Text("还没有远程设备（返回后点「＋添加设备」）", style = MaterialTheme.typography.bodySmall)
                        }
                        devices.forEach { d ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.fillMaxWidth()
                                    .clickable {
                                        haptics.perform(HapticEngine.Kind.Select)
                                        checked = if (d.id in checked) checked - d.id else checked + d.id
                                    }
                            ) {
                                Checkbox(
                                    checked = d.id in checked,
                                    onCheckedChange = { on ->
                                        haptics.perform(HapticEngine.Kind.Select)
                                        checked = if (on) checked + d.id else checked - d.id
                                    }
                                )
                                Text(d.name.ifBlank { d.id.take(12) }, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                        OutlinedTextField(
                            value = ignoreText,
                            onValueChange = { ignoreText = it },
                            label = { Text("忽略模式（一行一条，如 *.tmp）") },
                            minLines = 2
                        )
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                    Text("取消", maxLines = 1, softWrap = false)
                }
                TextButton(
                    onClick = {
                        scope.launch {
                            runCatching {
                                // 重新 GET 一份最新配置再改：对话框开着的几秒里后台同步可能已经动过配置
                                val fresh = SyncthingEngine.getFolder(context, row.id)
                                fresh.put("label", label.trim().ifBlank { row.label })
                                fresh.put("type", type)
                                fresh.put("paused", paused)
                                // —— devices：原数组上增删（保留本机/未知 id，见类注释）——
                                val arr = fresh.optJSONArray("devices") ?: JSONArray()
                                val existing = LinkedHashMap<String, JSONObject>()
                                for (i in 0 until arr.length()) {
                                    val d = arr.getJSONObject(i)
                                    existing[d.optString("deviceID")] = d
                                }
                                val shown = devices.map { it.id }
                                val next = JSONArray()
                                existing.forEach { (id, obj) ->
                                    if (id !in shown || id in checked) next.put(obj)
                                }
                                shown.forEach { id ->
                                    if (id in checked && id !in existing) next.put(JSONObject().put("deviceID", id))
                                }
                                fresh.put("devices", next)
                                fresh.put(
                                    "ignorePatterns",
                                    JSONArray(ignoreText.lines().mapNotNull { l -> l.trim().takeIf { t -> t.isNotEmpty() } })
                                )
                                SyncthingEngine.putFolder(context, fresh)
                            }.onSuccess { onDone(null) }.onFailure { onDone(it.message) }
                        }
                    },
                    enabled = loaded,
                    modifier = Modifier.weight(1f)
                ) { Text("保存", maxLines = 1, softWrap = false) }
            }
        }
    )
}

/**
 * v25：目录选择器（添加同步文件夹用）。
 *
 * 起点 = 应用专属目录（无需任何权限，`synced/` 就在里面）；拿到「所有文件访问」时
 * 可跳到内部存储根选 DCIM/Download。已配置过的目录标「已添加」，防止重复共享同一个路径。
 * 「新建文件夹」顺带满足"在任意位置开新目录再共享"的用法。
 */
@Composable
private fun DirPickerDialog(
    context: android.content.Context,
    existingPaths: List<String>,
    onPick: (File) -> Unit,
    onDismiss: () -> Unit
) {
    val start = remember { context.getExternalFilesDir(null) ?: context.filesDir }
    val fullAccess = remember { Environment.isExternalStorageManager() }
    val storageRoot = remember { runCatching { Environment.getExternalStorageDirectory() }.getOrNull() }
    val tops = remember(fullAccess) {
        buildSet {
            add(start)
            if (fullAccess && storageRoot != null) add(storageRoot)
        }
    }
    var cur by remember { mutableStateOf(start) }
    var tick by remember { mutableStateOf(0) }
    var showNew by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    val dirs = remember(cur, tick) {
        cur.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text("选择目录") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    cur.absolutePath, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (fullAccess && storageRoot != null && cur != storageRoot) {
                    TextButton(onClick = { cur = storageRoot }) { Text("跳到内部存储根") }
                }
                Column(
                    Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())
                ) {
                    if (cur !in tops && cur.parentFile != null) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { cur = cur.parentFile!! }
                        ) {
                            Text("… 上一级", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    if (dirs.isEmpty()) {
                        Text(
                            "（此文件夹下没有子文件夹）", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    dirs.forEach { d ->
                        val added = d.absolutePath in existingPaths
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().clickable { cur = d }
                        ) {
                            Text(
                                d.name, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                            if (added) {
                                Text(
                                    "已添加", style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(Modifier.fillMaxWidth()) {
                TextButton(onClick = { showNew = true }, modifier = Modifier.weight(1f)) {
                    Text("新建文件夹", maxLines = 1, softWrap = false)
                }
                TextButton(onClick = { onPick(cur) }, modifier = Modifier.weight(1f)) {
                    Text("选这个目录", maxLines = 1, softWrap = false)
                }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )

    if (showNew) {
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { showNew = false },
            title = { Text("新建文件夹") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it },
                    label = { Text("文件夹名") }, singleLine = true
                )
            },
            confirmButton = {
                Row(Modifier.fillMaxWidth()) {
                    TextButton(onClick = { showNew = false }, modifier = Modifier.weight(1f)) {
                        Text("取消", maxLines = 1, softWrap = false)
                    }
                    TextButton(
                        onClick = {
                            val n = newName.trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                            if (n.isNotEmpty()) {
                                runCatching { File(cur, n).mkdirs() }
                                tick++
                                newName = ""
                                showNew = false
                            }
                        },
                        enabled = newName.trim().isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) { Text("创建", maxLines = 1, softWrap = false) }
                }
            }
        )
    }
}

data class FolderRow(
    val id: String, val label: String, val path: String,
    val stateText: String, val progress: Float,
    /** 暂停态单列：paused 时 db/status 仍回 idle，混在 stateText 里会显示"空闲"但其实没在同步 */
    val paused: Boolean = false,
    /** sendreceive 双向 / sendonly 仅发送 / receiveonly 仅接收 */
    val type: String = "sendreceive",
    /** 共享给的设备 id 快照（勾选编辑用） */
    val deviceIds: List<String> = emptyList()
)

fun folderTypeLabel(t: String) = when (t) {
    "sendonly" -> "仅发送"
    "receiveonly" -> "仅接收"
    else -> "双向"
}

data class DeviceRow(val id: String, val name: String, val connected: Boolean)

/** v19.12：分区大标题（参考 Syncthing 官方排版：大号粗体、卡外、上下留白） */
@Composable
private fun SyncSectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(start = 4.dp, top = 16.dp, bottom = 6.dp)
    )
}

/** v19.12：卡内行分隔线 */
@Composable
private fun SyncRowDivider() {
    androidx.compose.material3.HorizontalDivider(
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)
    )
}

/**
 * v19.60 完整模式：**Syncthing 原版网页界面**，内嵌 WebView 打开。
 *
 * ## 为什么不是扔给系统浏览器
 * 内核的 GUI 绑在 `127.0.0.1:8384` 且带 API Key 认证，系统浏览器会弹一个
 * 要求手输 40 位 Key 的 Basic Auth 对话框——没人背得下那串。
 * 内嵌 WebView 可以把 Key 自动补上（见 onReceivedHttpAuthRequest），
 * 用户点一下「打开」就直接进原版界面。
 *
 * ## 几个踩过的点，都写在这里免得后面再改
 * - **明文流量**：`http://127.0.0.1` 早就在 res/xml/network_security_config.xml 的
 *   domain-config 里放行了（SyncthingEngine 的 REST 调用一直走明文），所以 WebView 也能加载；
 *   换机器如果这里没配，表现是白屏 + 连接错误。
 * - **认证方式**：Syncthing GUI 走 HTTP Basic，**用户名任意、密码 = API Key**。
 *   认证成功后服务端种 session cookie，后续请求 WebView 自动带上，不用我们管。
 * - **内核预热**：点「打开」时若内核刚被拉起，GUI 要 1~2 秒才 listen，首屏会连接失败。
 *   这里做有上限的自动重试（每次 1.2s、最多 5 次），期间提示"内核启动中"，
 *   而不是把失败直接摔给用户。
 * - **不做 WebView 内回退**：Syncthing 的 GUI 基本是单页应用，历史记录没什么可退；
 *   系统返回交给 PredictiveBackOverlay 收整页，行为和其他子页一致。
 */
@Composable
private fun SyncthingFullModeScreen(onDone: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val haptics = LocalHaptics.current
    val clipboard = LocalClipboardManager.current
    val apiKey = remember { SyncthingEngine.ensureApiKey(context) }
    val guiUrl = "http://${SyncthingEngine.GUI_HOST}:${SyncthingEngine.GUI_PORT}/"

    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    /** 每次 +1 触发一次重新加载（手动重试 / 内核预热自动重试都走它） */
    var tick by remember { mutableStateOf(0) }
    var autoRetry by remember { mutableStateOf(0) }

    // 内核预热自动重试：连接失败且内核确实在跑 → 等 1.2s 再试，最多 5 次。
    // 判定条件带上 isRunning：内核没在跑就是真失败，别空转打扰用户。
    LaunchedEffect(error, tick) {
        if (error != null && SyncthingEngine.isRunning && autoRetry < 5) {
            delay(1200)
            autoRetry++
            tick++
        }
    }

    PredictiveBackOverlay(onBack = onDone) {
        Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp)
                ) {
                    // 返回 = 中性轻触（Tap）；走 LocalOverlayClose 才有统一的收缩动画。
                    // 注意：CompositionLocal 的 .current 只能在**组合上下文**读，
                    // 直接写进 onClick lambda 会编译不过（@Composable invocations…），
                    // 所以先提到外面的局部变量再调用（19:00 实测）。
                    val overlayClose = io.github.m0use001.planability.app.LocalOverlayClose.current
                    IconButton(onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        overlayClose()
                    }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回") }
                    Text(
                        "完整模式 · Syncthing",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    TextButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); tick++ }) {
                        Text(if (loading) "刷新中…" else "刷新")
                    }
                }

                Box(Modifier.fillMaxSize()) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            android.webkit.WebView(ctx).apply {
                                settings.javaScriptEnabled = true
                                // Syncthing GUI 用 localStorage 记界面偏好，不给这个很多开关不记忆
                                settings.domStorageEnabled = true
                                settings.useWideViewPort = true
                                settings.loadWithOverviewMode = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                // 只是加载本机 GUI，别给它翻本地文件的权限
                                settings.allowFileAccess = false
                                settings.allowContentAccess = false
                                webViewClient = object : android.webkit.WebViewClient() {
                                    // 自动补 API Key：Syncthing 的 Basic 认证用户名可随便填，密码必须是 apikey
                                    override fun onReceivedHttpAuthRequest(
                                        view: android.webkit.WebView,
                                        handler: android.webkit.HttpAuthHandler,
                                        host: String, realm: String
                                    ) {
                                        handler.proceed("syncthing", apiKey)
                                    }

                                    override fun onPageStarted(
                                        view: android.webkit.WebView, url: String, favicon: android.graphics.Bitmap?
                                    ) {
                                        loading = true
                                        error = null
                                    }

                                    override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                        loading = false
                                    }

                                    override fun onReceivedError(
                                        view: android.webkit.WebView,
                                        request: android.webkit.WebResourceRequest,
                                        err: android.webkit.WebResourceError?
                                    ) {
                                        // 只认主框架：子资源失败（某个 js/图标 404）不该把整页判死
                                        if (!request.isForMainFrame) return
                                        loading = false
                                        val raw = err?.description?.toString().orEmpty()
                                        error = when {
                                            SyncthingEngine.isRunning ->
                                                "打不开 $guiUrl（$raw）。内核在运行，多半还在启动中——点下方重试。"
                                            else ->
                                                "内核没在运行，GUI 没人serve。返回同步中心点「启动」，等出现设备 ID 再来。"
                                        }
                                    }
                                }
                            }
                        },
                        // tick 变化 = 重新加载；首帧也靠它触发（AndroidView 的 update 在组合后必跑一次）
                        update = { tick; it.loadUrl(guiUrl) },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (loading && error == null) {
                        Box(
                            Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                // v45 M3E：≤5s 的等待用 LoadingIndicator（七形状 morph 循环），
                                // 旧 CircularProgressIndicator 在 M3E 里已不是推荐表达。
                                androidx.compose.material3.LoadingIndicator()
                                Spacer(Modifier.height(12.dp))
                                Text(
                                    "正在打开 Syncthing 原版界面…",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }

            // 失败态：给重试 / 复制网址 / 转系统浏览器三条路，别让用户卡死在这一屏
            error?.let { msg ->
                Box(
                    Modifier.fillMaxSize()
                        .background(MaterialTheme.colorScheme.background.copy(alpha = 0.92f)),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        shape = ShapeTokens.card,
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainer
                        ),
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Column(
                            Modifier.padding(18.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                "打不开完整模式",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                msg,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                guiUrl,
                                style = MaterialTheme.typography.labelSmall.copy(fontFamily = FontFamily.Monospace),
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    clipboard.setText(AnnotatedString(guiUrl))
                                }) { Text("复制网址") }
                                OutlinedButton(onClick = {
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(guiUrl)
                                            )
                                        )
                                    }
                                }) { Text("用浏览器打开") }
                                Button(onClick = { autoRetry = 0; tick++ }) { Text("重试") }
                            }
                        }
                    }
                }
            }
        }
    }
}
