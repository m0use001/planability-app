package io.github.m0use001.planability.ui.notes

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics

/**
 * 图标选择器：从全部 Material filled 图标里挑一个封面图标。
 *
 * ## 为什么是搜索框 + 网格，而不是分类浏览
 * 图标名是 PascalCase 英文（`ShoppingCart`、`LocalCafe`），有三千多个，
 * 按字母区段分组反而比直接搜索难找——用户记得的多半是语义（cart、coffee），
 * 而语义就写在名字里。所以只给一个搜索框，输入即筛。
 *
 * ## 格子必须是封面同款 1000:1120 比例
 * [coverIconPreview] 内部用 `X()=v*w/1000` 与 `Y()=v*h/1120` 两个不同分母换算，
 * 只有格子宽高比恰好是 1000/1120 时两者才等价、图标才画成正方形。
 * 格子上画成正方形、里面塞一个按比例绘制的图标，出来的就是压扁的形状。
 */
@Composable
fun IconPickerDialog(
    current: Int,
    onPick: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    val haptics = LocalHaptics.current
    var query by remember { mutableStateOf("") }
    val q = query.trim()

    val list: List<Int> = remember(q) {
        MaterialIconSet.NAMES.indices.filter { e ->
            q.isEmpty() || MaterialIconSet.NAMES[e].contains(q, ignoreCase = true)
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                "选择图标",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )

            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("搜索（英文名，如 cart、coffee）") },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
            )

            Text(
                if (q.isEmpty()) "${MaterialIconSet.COUNT} 个图标"
                else "筛选出 ${list.size} 个",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp, bottom = 2.dp)
            )

            if (list.isEmpty()) {
                Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = androidx.compose.ui.Alignment.Center) {
                    Text(
                        "搜不到「$q」",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(300.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(list) { icon ->
                        IconCell(
                            icon = icon,
                            selected = icon == current,
                            onClick = {
                                haptics.perform(HapticEngine.Kind.Tap)
                                onPick(icon)
                                onDismiss()
                            }
                        )
                    }
                }
            }

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

/**
 * 一个图标格子。
 *
 * 图标是单色矢量，用主题的 onSurfaceVariant 上色——格底是主题色，
 * 不上色就画成默认黑，深色主题下直接看不见。
 * **着色必须在 composable 上下文取**：Canvas 的 lambda 不是 composable 上下文，
 * MaterialTheme 在里面调用会编译不过。
 */
@Composable
private fun IconCell(icon: Int, selected: Boolean, onClick: () -> Unit) {
    // 格子必须是**封面同款 1000:1120 比例**，不是正方形：见 IconPickerDialog 的说明。
    //
    // 宽度**必须交给网格分**，不能写死 dp：6 列 × 54dp + 间距会超出对话框宽度，
    // cell 溢出列宽后第一排被上方切掉、最右列被屏幕切掉、选中描边也跟着溢出去（看着是歪的）。
    val tintColor = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1000f / 1120f)
            .then(
                if (selected) {
                    Modifier.border(
                        width = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                        shape = RoundedCornerShape(4.dp)
                    )
                } else Modifier
            )
            // 选中描边本来就是 4dp 圆角，但水波挂在描边外 → 按压是直角方块。裁剪与描边同曲率。
            .clip(RoundedCornerShape(4.dp))
            .clickable(onClick = onClick)
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pad = size.width * 0.16f
            coverIconPreview(
                icon,
                cell = size.width - pad * 2,
                cx = size.width / 2f,
                cy = size.height / 2f,
                tint = tintColor
            )
        }
    }
}
