package io.github.m0use001.planability.ui.voice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.VoskManager
import io.github.m0use001.planability.data.Graph

/**
 * 语音输入弹层：Vosk 实时离线识别，确认后回调文本（空文本 = 取消）。
 */
@Composable
fun VoiceInputSheet(
    title: String = "语音输入",
    onDone: (String) -> Unit
) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    val state by VoskManager.state.collectAsState()
    val partial by VoskManager.partial.collectAsState()

    var permissionNeeded by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionNeeded = !granted
        if (granted) VoskManager.start(context)
    }

    val listening = state == VoskManager.State.Listening
    val modelReady = VoskManager.isReady(context)
    val busy = state is VoskManager.State.Downloading || state is VoskManager.State.Unpacking
    val errMsg = (state as? VoskManager.State.Error)?.message

    // 2026-10-31 UI 修：以前这里只是个**内联 Box**——遮罩只覆盖自己那一块，
    // 页面其余部分既不压暗也不可点（弹层出来了还能点到后面的页面），系统返回也不关它。
    // 改成真 Dialog（独立窗口）：全屏压暗、点外部/返回都能关；取消语义 = onDone("")。
    val cancel: () -> Unit = {
        if (listening) VoskManager.stop()
        onDone("")
    }
    androidx.compose.ui.window.Dialog(
        onDismissRequest = cancel,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false,
            dismissOnClickOutside = true,
            dismissOnBackPress = true
        )
    ) {
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null
            ) { cancel() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth().padding(16.dp)
        ) {
            Column(
                Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                when {
                    permissionNeeded -> {
                        Text("需要麦克风权限用于离线识别", style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }) {
                            Text("授予麦克风权限")
                        }
                    }
                    !modelReady -> {
                        Text(
                            "语音模型未就绪，请到「我的 → 模型管理」下载 Vosk 中文模型",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Text("下载完成后回到这里即可离线识别", style = MaterialTheme.typography.bodySmall)
                    }
                    busy -> {
                        val p = when (val s = state) {
                            is VoskManager.State.Downloading -> s.progress
                            is VoskManager.State.Unpacking -> s.progress
                            else -> 0
                        }
                        Text("模型准备中 $p%", style = MaterialTheme.typography.bodySmall)
                    }
                    else -> {
                        Box(
                            contentAlignment = Alignment.Center,
                            modifier = Modifier
                                .size(84.dp)
                                .clip(CircleShape)
                                .background(
                                    if (listening) MaterialTheme.colorScheme.errorContainer
                                    else MaterialTheme.colorScheme.primaryContainer
                                )
                                .clickable {
                                    haptics.perform(HapticEngine.Kind.ImpactLight)
                                    if (listening) VoskManager.stop()
                                    else {
                                        VoskManager.partial  // 保留旧文本供「使用文本」
                                        VoskManager.start(context)
                                    }
                                }
                        ) {
                            Icon(
                                if (listening) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                contentDescription = if (listening) "停止" else "开始",
                                tint = if (listening) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(40.dp)
                            )
                        }
                        Text(
                            when {
                                errMsg != null -> errMsg
                                listening -> "聆听中…（说完点一下停止）"
                                partial.isNotBlank() -> "识别完成"
                                else -> "点按麦克风开始说话"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (partial.isNotBlank()) {
                            Text(
                                partial,
                                style = MaterialTheme.typography.bodyLarge,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    if (listening) {
                        TextButton(onClick = { VoskManager.stop() }) { Text("停止") }
                    }
                    if (partial.isNotBlank()) {
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Success)
                            onDone(partial)
                        }) { Text("使用文本") }
                    }
                    TextButton(onClick = {
                        if (listening) VoskManager.stop()
                        onDone("")
                    }) { Text(if (partial.isNotBlank()) "丢弃" else "取消") }
                }
            }
        }
    }
    }
}
