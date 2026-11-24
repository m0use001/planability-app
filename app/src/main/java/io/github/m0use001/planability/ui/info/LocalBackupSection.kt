package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.data.AutoBackup
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * v44：本机快照（Download/Planability）的状态、加密开关与"解密出一份可恢复明文"。
 *
 * ## 为什么放在「导入」页
 * 这里是数据进出本机的地方（导入对话/技能/MCP）；整库快照的恢复也是同一件事。
 *
 * ## 为什么必须让用户看见"密钥随卸载销毁"
 * 加密是默认开的——保护的是"手机被人翻"。但它有一个反直觉的代价：重装后旧 `.enc`
 * 解不开。不写清楚，用户会以为"有备份就安全"，等真出事才发现拿不到。
 * 所以这一屏三句话讲明白：加密防偷看 / 明文能重装读 / 真正的重装恢复靠电脑上的守护快照。
 */
@Composable
internal fun LocalBackupSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var st by remember { mutableStateOf<AutoBackup.Status?>(null) }
    var msg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    LaunchedEffect(Unit) { st = AutoBackup.status() }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text("本机快照与恢复", style = MaterialTheme.typography.titleSmall)
        Text(
            "切后台时把整库复制到 Download/Planability，5 分钟最多一份。\n" +
                "默认 AES-256-GCM 加密（*.db.enc）——文件拷到电脑上打不开，" +
                "但密钥随卸载销毁，重装后旧密文解不开。重装的恢复主路径是电脑上的 device-guard 快照。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        st?.let { s ->
            Text(
                "加密 ${s.encrypted} 份 · 明文 ${s.plain} 份 · 恢复副本 ${s.restoreCopies} 份\n" +
                    "最近一份：" + (if (s.lastAt > 0) fmt.format(Date(s.lastAt)) else "还没写过") +
                    " · 密钥：" + (if (s.sealedOk) "可用" else "不可用（会退化为明文）"),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 6.dp)
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("保留一份「急救明文」副本", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "重装后也能直接读（代价：拿到手机的人也能读这份最新的库）。只留最近 1 份。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = s.keepPlain,
                    onCheckedChange = { on ->
                        haptics.perform(HapticEngine.Kind.Select)
                        scope.launch {
                            AutoBackup.setKeepPlain(on)
                            st = AutoBackup.status()
                        }
                    }
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = !busy, onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        AutoBackup.snapshot(force = true)
                        st = AutoBackup.status()
                        busy = false
                        haptics.perform(HapticEngine.Kind.Success)
                        msg = "已写一份快照（若加密可用，只有密文）"
                    }
                }) { Text(if (busy) "处理中…" else "立即快照") }

                TextButton(enabled = !busy, onClick = {
                    busy = true; msg = ""
                    scope.launch {
                        val name = AutoBackup.decryptNewestToRestoreCopy()
                        // 解出来的副本数变了，状态行要跟着刷（否则刚生成仍显示 0 份）
                        st = AutoBackup.status()
                        busy = false
                        if (name != null) {
                            haptics.perform(HapticEngine.Kind.Success)
                            msg = "已解密为 Download/Planability/$name\n" +
                                "恢复：python tools/device-guard/device_guard.py restore <拉下来的文件>"
                        } else {
                            haptics.perform(HapticEngine.Kind.Error)
                            msg = "没有可解密的快照，或解不开（重装后密钥已销毁）"
                        }
                    }
                }) { Text("解密最新一份") }
            }
        }

        if (msg.isNotBlank()) {
            Text(
                msg,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
        }
    }
}
