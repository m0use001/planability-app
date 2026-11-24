package io.github.m0use001.planability.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * M3 Expressive 形状语义 token（4 档）——M3E 审查（2026-10-23 报告差距 7）的收敛基座。
 *
 * 此前全库数百处 `RoundedCornerShape(字面量)`，同一"卡片"语义在 12/14/16/18dp 之间漂移
 * （文件页同一列表四个容器实测 14.1/17.9/14.1/11.8dp）。这 4 档对应 M3E 规范：
 * - [card] 卡片/表面容器：12dp（https://m3.material.io/styles/shape/shape-scale-tokens）
 * - [listRow] 列表行：16dp（expressive list 未选中外角 16dp）
 * - [chip] chip：8dp（chips 规范 "slightly rounded with an 8dp corner"，高 32dp——勿改成胶囊）
 * - [pill] 胶囊：50%（随高度全圆；dp 写不了"随高度"，必须走 percent）
 *
 * 用法：`.clip(ShapeTokens.card)`、`background(color, ShapeTokens.pill)`、`shape = ShapeTokens.listRow`。
 * 非这 4 种语义的小圆角（分隔、描边细节等 2-10dp）维持原字面量，不强行归档。
 */
object ShapeTokens {
    /** 卡片 / 表面容器 */
    val card = RoundedCornerShape(12.dp)

    /** 列表行（外角 16dp） */
    val listRow = RoundedCornerShape(16.dp)

    /** chip（8dp，配 32dp 高——M3 chips 规范形态，不是全胶囊） */
    val chip = RoundedCornerShape(8.dp)

    /** 胶囊（50%，随高度全圆） */
    val pill = RoundedCornerShape(percent = 50)
}
