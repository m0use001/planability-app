package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 十三期：会话工具权限简化为 4 档（图2 式下拉菜单），替换 27 工具三态详表入口。
 *
 * 2026-10-29：档位语义与放行集合全部迁到 [io.github.m0use001.planability.ai.ptl.PermPolicy]
 * （审查发现「计划模式」黑名单漏拦、「自动执行」实际放行整机文件与设备操控）。
 * 这里只保留**图标**这层壳：策略归策略、皮肤归皮肤，改文案只需要动 PermPolicy。
 */
object PermModes {
    data class Mode(val key: String, val label: String, val desc: String, val icon: ImageVector)

    private fun iconOf(key: String): ImageVector = when (key) {
        "ro" -> Icons.Rounded.VisibilityOff
        "auto" -> Icons.Rounded.Edit
        "full" -> Icons.Rounded.Lock
        else -> Icons.Rounded.Visibility
    }

    val ALL: List<Mode> = io.github.m0use001.planability.shared.PermPolicy.TIERS.map {
        Mode(it.key, it.label, it.desc, iconOf(it.key))
    }

    /** 会改动数据的工具集合（登记表的真源在 PermPolicy，有单测守） */
    val WRITE_TOOLS: Set<String> get() = io.github.m0use001.planability.shared.PermPolicy.WRITE_TOOLS

    /** 档位 → 会话级 permJson（空串=不写，保持默认） */
    fun permJsonFor(key: String): String =
        io.github.m0use001.planability.shared.PermPolicy.permJsonFor(key)

    /** 会话 permJson → 档位（无法识别时归 confirm） */
    fun modeForPermJson(permJson: String): String =
        io.github.m0use001.planability.shared.PermPolicy.modeForPermJson(permJson)
}

/** 权限档位下拉菜单：挂在权限 chip 旁（锚点内调用），点选即回调 */
@Composable
fun PermModeMenu(
    expanded: Boolean,
    currentKey: String,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit
) {
    DropdownMenu(
        expanded = expanded,
        onDismissRequest = onDismiss,
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
    ) {
        PermModes.ALL.forEach { m ->
            DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            m.icon, null,
                            androidx.compose.ui.Modifier.size(18.dp),
                            // R11：菜单项行首图标中性；当前项由右侧 ✓（保留主色）表达
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(m.label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(
                                m.desc, style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (m.key == currentKey) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                onClick = { onPick(m.key) }
            )
        }
    }
}
