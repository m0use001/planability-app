@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package io.github.m0use001.planability.ui.plan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.categoryColor
import io.github.m0use001.planability.ui.common.motionEnter
import io.github.m0use001.planability.ui.edit.MapPickerSheet
import kotlinx.coroutines.launch

/**
 * 地点选择（2026-10-25 用户定的两级模型，独立全屏子页）。
 *
 * 结构照搬「分类管理 → 地点」：**绑定地名（哪栋楼）→ 展开后是显示地名列表（哪间房）**。
 * 用户先选绑定地名，展开后点显示地名即完成选点；每行带编辑/删除按钮（2026-10-28 起
 * 显示地名的增删改集中在这里，分类管理只留只读展开）；没有合适的显示名就点底部
 * 「＋ 新增显示地名」；整栋楼都没有就「新建绑定地点」走地图选点。
 */
@Composable
fun PlacePickerScreen(
    currentBound: String,
    currentDisplay: String,
    onDismiss: () -> Unit,
    onPick: (bound: String, lat: Double, lng: Double, display: String) -> Unit
) {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    val version by Graph.dataVersion.flow.collectAsState()
    var places by remember { mutableStateOf<List<CategoryEntity>>(emptyList()) }
    // 当前展开的绑定地名（进页面先展开已选中的那栋）
    var expanded by remember { mutableStateOf(currentBound) }
    var showMap by remember { mutableStateOf(false) }
    // 正在给哪个绑定地点加显示名
    var addFor by remember { mutableStateOf<CategoryEntity?>(null) }
    var newName by remember { mutableStateOf("") }
    // 正在重命名哪个显示地名：（所属绑定地点, 原名）
    var renameFor by remember { mutableStateOf<Pair<CategoryEntity, String>?>(null) }
    var renameValue by remember { mutableStateOf("") }

    LaunchedEffect(version) { places = Graph.plan.categories(1) }

    PredictiveBackOverlay(onBack = onDismiss) {
        val overlayClose = LocalOverlayClose.current
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("选择地点") },
                    navigationIcon = {
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    }
                )
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad)) {
                LazyColumn(
                    Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    item {
                        Text(
                            "先选绑定地名（哪栋楼），展开后点显示地名（哪间房）",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                    if (places.isEmpty()) {
                        item {
                            Text(
                                "还没有绑定地点\n点下方「新建绑定地点」去地图上选一个",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 24.dp)
                            )
                        }
                    }
                    items(places, key = { it.id }) { p ->
                        BoundPlaceRow(
                            place = p,
                            expanded = expanded == p.name,
                            currentBound = currentBound,
                            currentDisplay = currentDisplay,
                            onToggle = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                expanded = if (expanded == p.name) "" else p.name
                            },
                            onPickDisplay = { d -> onPick(p.name, p.lat, p.lng, d) },
                            onEditDisplay = { old ->
                                renameFor = p to old
                                renameValue = old
                            },
                            onDeleteDisplay = { d ->
                                scope.launch { Graph.plan.removeDisplayName(p.id, d) }
                            },
                            onAddDisplay = {
                                addFor = p
                                newName = ""
                            }
                        )
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                OutlinedButton(
                    onClick = { haptics.perform(HapticEngine.Kind.Tap); showMap = true },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)
                ) { Text("新建绑定地点（地图选点）") }
            }
        }
    }

    if (showMap) {
        MapPickerSheet(
            initial = null,
            initialName = "",
            onDismiss = { showMap = false },
            onPick = { point, display ->
                showMap = false
                val bound = point.label.ifBlank { display }
                scope.launch {
                    Graph.plan.ensureDisplayName(bound, display, point.lat, point.lng)
                    onPick(bound, point.lat, point.lng, display)
                }
            }
        )
    }

    // 「是否填入绑定地名？」问句行（2026-10-29 与选点面板弹窗对齐：[填入] 一键填绑定名，
    // 再补楼层房号；两个弹窗共用）
    @Composable
    fun FillBoundNameRow(bound: String, onFill: () -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "是否填入绑定地名？",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onFill) { Text("填入") }
        }
    }

    addFor?.let { p ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { addFor = null },
            title = { Text("新增显示地名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("显示地名（可写楼层房间号）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FillBoundNameRow(bound = p.name, onFill = { newName = p.name })
                    Text(
                        "挂在「${p.name}」下；点「填入」用绑定名，或改成 楼E-302 这类房间号。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = newName.isNotBlank(),
                    onClick = {
                        val d = newName.trim()
                        addFor = null
                        haptics.perform(HapticEngine.Kind.Success)
                        scope.launch {
                            Graph.plan.ensureDisplayName(p.name, d, p.lat, p.lng)
                            onPick(p.name, p.lat, p.lng, d)
                        }
                    }
                ) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { addFor = null }) { Text("取消") } }
        )
    }

    renameFor?.let { (p, old) ->
        AlertDialog(
            modifier = Modifier.motionEnter(),
            onDismissRequest = { renameFor = null },
            title = { Text("重命名显示地名") },
            text = {
                Column {
                    OutlinedTextField(
                        value = renameValue,
                        onValueChange = { renameValue = it },
                        label = { Text("显示地名（可写楼层房间号）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    FillBoundNameRow(bound = p.name, onFill = { renameValue = p.name })
                    Text(
                        "挂在「${p.name}」下；已建立的日程/待办里存的旧名字不会自动改。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = renameValue.isNotBlank(),
                    onClick = {
                        val d = renameValue.trim()
                        renameFor = null
                        haptics.perform(HapticEngine.Kind.Success)
                        if (d.isNotBlank()) scope.launch {
                            Graph.plan.renameDisplayName(p.id, old, d)
                        }
                    }
                ) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameFor = null }) { Text("取消") } }
        )
    }
}

@Composable
private fun BoundPlaceRow(
    place: CategoryEntity,
    expanded: Boolean,
    currentBound: String,
    currentDisplay: String,
    onToggle: () -> Unit,
    onPickDisplay: (String) -> Unit,
    onEditDisplay: (String) -> Unit,
    onDeleteDisplay: (String) -> Unit,
    onAddDisplay: () -> Unit
) {
    val haptics = LocalHaptics.current
    val names = Graph.plan.displayNamesOf(place)
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable(onClick = onToggle)
                .padding(vertical = 10.dp, horizontal = 6.dp)
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(categoryColor(place.hue))
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                // 绑定地名不截断：自动换行显示全名（2026-10-28 用户要求）
                Text(
                    place.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
                )
                Text(
                    if (place.lat != 0.0 || place.lng != 0.0) "已绑坐标 · ${names.size} 个显示地名"
                    else "未绑坐标 · ${names.size} 个显示地名",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Icon(
                if (expanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                contentDescription = if (expanded) "收起" else "展开",
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (expanded) {
            // 展开区 = 显示地名**列表**（不是胶囊流）：每行显示名可换行显示全名，
            // 点名字 = 完成选点，右侧编辑/删除按钮做管理（2026-10-28 用户要求）
            Column(Modifier.fillMaxWidth().padding(start = 22.dp, bottom = 8.dp)) {
                names.forEach { n ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            n,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = if (place.name == currentBound && n == currentDisplay)
                                androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                            color = if (place.name == currentBound && n == currentDisplay)
                                MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.Select)
                                    onPickDisplay(n)
                                }
                                .padding(vertical = 8.dp)
                        )
                        IconButton(
                            onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                onEditDisplay(n)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Edit, "编辑显示地名",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        IconButton(
                            onClick = {
                                haptics.perform(HapticEngine.Kind.ImpactHeavy)
                                onDeleteDisplay(n)
                            },
                            modifier = Modifier.size(36.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Delete, "删除显示地名",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
                // 底部加号：新增显示地名（默认填绑定名，用户自己改成房间号）
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            haptics.perform(HapticEngine.Kind.Tap)
                            onAddDisplay()
                        }
                        .padding(vertical = 6.dp, horizontal = 2.dp)
                ) {
                    Box(
                        Modifier
                            .size(26.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Rounded.Add, null,
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "新增显示地名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}
