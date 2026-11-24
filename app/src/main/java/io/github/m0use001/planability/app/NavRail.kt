package io.github.m0use001.planability.app

import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * v57 宽屏（平板 / 折叠屏内屏 / 大屏窗口）的一级导航：**左侧 NavigationRail**。
 *
 * 为什么是 Rail 而不是把药丸底栏原样搬上去：
 * - 药丸底栏是 6 格横向排布，在 1000dp+ 宽屏上会横跨整屏、两侧留白、点选目标太远；
 * - Rail 是 Material 3 为「宽窗口」准备的竖排导航，图标在上、标签在下，纵向排布不占横向空间；
 * - 审计结论同源（`设计文档`）：手机底栏不得原样上平板。
 *
 * 与药丸底栏的差异（刻意保留）：
 * - 图标/标签同源（都来自 [tabSpecs]），选中态语义一致；
 * - **没有拖动吸附**（`onDragProgress`）：那是底栏专属的横向拖动手感，竖排 Rail 没有可拖的横向空间；
 * - 切页语义（再点当前页回顶、来源 tab 作废、`ShellNav.currentTab`）由调用方在 [onSelect] 里处理，
 *   与底栏共用同一个 `selectTab`（见 `NavShell.AppShell`）——两套导航不会走偏。
 */
@Composable
fun NavRailPane(
    tabs: List<TabSpec>,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    NavigationRail(
        modifier = modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
    ) {
        tabs.forEachIndexed { idx, spec ->
            NavigationRailItem(
                selected = idx == current,
                onClick = { onSelect(idx) },
                icon = { Icon(spec.icon, contentDescription = spec.label) },
                label = { Text(spec.label) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    selectedTextColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                ),
            )
        }
    }
}
