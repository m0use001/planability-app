package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import io.github.m0use001.planability.core.AppJump
import io.github.m0use001.planability.core.AppEntry
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.JumpTarget
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.ui.common.AppIcons
import io.github.m0use001.planability.ui.common.pressScale
import kotlinx.coroutines.launch

/**
 * v19.40：**看别的软件有哪些页面，并直接过去**——而不是"打开它然后自己翻"。
 *
 * 两级：应用列表 → 该应用的可跳目标（主页 / 各 activity / 各深链）。
 * 对标 Apple 快捷指令时这一屏同时扮演两个角色：
 *  - 独立的跳转浏览器（用户点进来就想过去）
 *  - `app_open` 这个流水线动作的"选参数"来源（看清楚了再往步骤里填）
 *
 * ## 界面上必须说清的两件事
 * ① **未导出的页面要点不进去**：`exported=false` 系统直接拒绝。这里先标灰并写明原因，
 *    而不是让用户点了收到一个裸 SecurityException 再莫名其妙。
 * ② **降级是设计的一部分**：组件跳不动 → 试深链 → 最后回主页。所以列表里每一项都能点，
 *    失败时给的是一句中文原因（`AppJump.jump` 返回），不是"操作失败"。
 */
@Composable
fun AppJumpPane() {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()

    var query by remember { mutableStateOf("") }
    var apps by remember { mutableStateOf<List<AppEntry>?>(null) }
    var selected by remember { mutableStateOf<AppEntry?>(null) }
    var targets by remember { mutableStateOf<List<JumpTarget>?>(null) }
    /** 最近一次跳转的失败原因（成功不提示——界面会自己切过去） */
    var lastErr by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { apps = AppJump.apps() }

    if (selected == null) {
        val list = apps
        Column(Modifier.fillMaxSize()) {
            if (lastErr != null) {
                Text(
                    lastErr!!,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = { Text("搜应用（名称或包名）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)
            )
            when {
                list == null -> Text(
                    "正在读取已安装应用…",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                list.isEmpty() -> Text(
                    "一个应用都没读到。这是包可见性问题——本应由 AndroidManifest 的 QUERY_ALL_PACKAGES 解决",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
                else -> {
                    val q = query.trim()
                    val shown = if (q.isEmpty()) list
                    else list.filter {
                        it.label.contains(q, true) || it.pkg.contains(q, true)
                    }
                    Text(
                        "${shown.size} 个应用" + if (shown.size != list.size) "（共 ${list.size}）" else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
                    )
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(shown, key = { it.pkg }) { app ->
                            AppRow(app) {
                                haptics.perform(HapticEngine.Kind.Tap)
                                selected = app
                                targets = null
                                lastErr = null
                            }
                        }
                    }
                }
            }
        }
        return
    }

    // ---------- 第二级：这个应用能跳到哪儿 ----------
    val app = selected!!
    LaunchedEffect(app.pkg) { targets = AppJump.targetsOf(app.pkg) }
    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { haptics.perform(HapticEngine.Kind.Tap); selected = null }
                    .padding(6.dp)
            ) {
                Icon(AppIcons.Nav.Back)
            }
            Column(Modifier.weight(1f).padding(start = 6.dp)) {
                Text(
                    app.label, style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    app.pkg, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        lastErr?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 4.dp)
            )
        }
        val list = targets
        when {
            list == null -> Text(
                "正在读取页面…", style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            list.isEmpty() -> Text(
                "这个应用没有可枚举的页面",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            else -> LazyColumn(Modifier.fillMaxSize()) {
                items(list.size) { i ->
                    val t = list[i]
                    TargetRow(t) {
                        haptics.perform(
                            if (t.exported || t.uri != null) HapticEngine.Kind.Tap
                            else HapticEngine.Kind.Error
                        )
                        scope.launch {
                            val err = AppJump.jump(app.pkg, t)
                            lastErr = err
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AppRow(app: AppEntry, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        AppIcon(app)
        Column(Modifier.weight(1f).padding(start = 10.dp)) {
            Text(
                app.label, style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                buildString {
                    append(app.pkg)
                    if (app.activityCount > 0) append("  ·  ").append(app.activityCount).append(" 个页面")
                    if (app.deepLinkCount > 0) append("  ·  ").append(app.deepLinkCount).append(" 条深链")
                    if (!app.launchable) append("  ·  无启动入口")
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
        Icon(AppIcons.Nav.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TargetRow(t: JumpTarget, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .pressScale()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 8.dp)
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.label,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    // 未导出的行文字降灰：让人一眼看出"这项点进去没用"，
                    // 而不是点了才被告知（那时候已经浪费了一次点击）
                    color = if (t.exported || t.uri != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                    }
                )
                if (!t.exported && t.uri == null) {
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "未导出",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.error.copy(alpha = 0.12f))
                            .padding(horizontal = 4.dp, vertical = 1.dp)
                    )
                }
            }
            if (t.cls != null) {
                Text(
                    t.cls, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
        Icon(AppIcons.Nav.Next, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 应用图标：能拿到就用真图标（core-ktx 的 Drawable.toBitmap），拿不到退回首字方块 */
@Composable
private fun AppIcon(app: AppEntry, size: Int = 36) {
    val ctx = LocalContext.current
    val bmp = remember(app.pkg) {
        runCatching {
            ctx.packageManager.getApplicationIcon(app.pkg).toBitmap(size * 3, size * 3)
        }.getOrNull()
    }
    if (bmp != null) {
        Image(
            bmp.asImageBitmap(),
            contentDescription = null,
            modifier = Modifier.size(size.dp).clip(RoundedCornerShape(9.dp))
        )
    } else {
        Box(
            Modifier
                .size(size.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(MaterialTheme.colorScheme.primaryContainer),
            contentAlignment = Alignment.Center
        ) {
            Text(
                app.label.take(1).uppercase(),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun Icon(image: androidx.compose.ui.graphics.vector.ImageVector, tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    androidx.compose.material3.Icon(image, null, Modifier.size(20.dp), tint = tint)
}
