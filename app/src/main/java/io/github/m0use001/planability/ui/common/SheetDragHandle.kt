package io.github.m0use001.planability.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/**
 * 统一底部面板把手（2026-11-15 用户定案）。
 *
 * 用户原话：「软件内所有可拉起二级菜单的把手的选中特效都是这个突兀的长方形」
 * → 追问确认：指的是**包着那根小横杠的整条浅色底**。
 * 所以要的形态 = **只剩一根小横杠**（32×4dp 圆角条），底板透明——
 * 面板顶不再出现一块比面板本身更亮的"长方形"。
 *
 * 用法：`ModalBottomSheet(dragHandle = { SheetDragHandleOnly() }, ...)`。
 * 高度 22dp（=M3 的悬挂区内边距两倍），保证拖拽热区与原来一致。
 */
@Composable
fun SheetDragHandleOnly() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .width(32.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f))
        )
    }
}
