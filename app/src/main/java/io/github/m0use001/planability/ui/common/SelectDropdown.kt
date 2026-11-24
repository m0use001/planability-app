package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDropDown
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 单选下拉胶囊（2026-11-22 用户「可滑动项不要碰到框/圆角矩形边缘」）。
 *
 * 替换圆角板内的小固定集单选胶囊行（时长预设/重复第几周/每月几号/类型筛选…）：
 * 一行只占**一个**胶囊、永不横向滑到板缘（SplitBoards 板面 / PaneContentBlock 都会
 * clip 圆角，横滑胶囊会贴进圆角里）。胶囊本身就是当前值的摘要，点开是完整选项列表，
 * 选中项带 ✓。-[selectedIndex] = -1 表示当前值不在 [options] 里（[summary] 自行带值，
 * 例如「自定义 30 分」）；触感由调用方在 [onSelect] 里给（与原 FilterChip 同档 Select）。
 */
@Composable
fun SelectDropdownChip(
    summary: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Box(modifier) {
        var open by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(percent = 50))
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                .clickable(enabled = enabled) { open = true }
                .padding(start = 12.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Text(
                summary,
                style = MaterialTheme.typography.labelLarge,
                color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f)
            )
            Icon(
                Icons.Rounded.ArrowDropDown, "展开选项",
                Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEachIndexed { i, o ->
                DropdownMenuItem(
                    // 2026-11-15 用户报障同款规矩：菜单项高亮必须圆角裁切（守卫 MenuItemRippleShapeTest 钉）
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)),
                    text = { Text(o) },
                    trailingIcon = {
                        if (i == selectedIndex) {
                            Icon(Icons.Rounded.Check, null, Modifier.size(18.dp))
                        }
                    },
                    onClick = { open = false; onSelect(i) }
                )
            }
        }
    }
}
