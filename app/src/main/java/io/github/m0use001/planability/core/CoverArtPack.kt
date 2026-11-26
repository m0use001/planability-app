package io.github.m0use001.planability.core

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.github.m0use001.planability.ui.notes.CoverPal

/**
 * 封面素材包（可选源集）的**唯一接缝**（2026-11-26，phone-b）。
 *
 * ◆ 贴图封面（原版+模组方块贴图 5302 张 + 图标池 + 等距绘制 + 选择器）属
 * **不含在公开版**的素材体系。它的源码与图片全部集中在 一个可选
 * 源集，与设备功能包源集同构），主模块只经本对象消费：
 *
 * - 私仓：素材包安装器注册真实实现 → [available]=true，COVER_MC 款式全功能可用；
 * - 公开版：导出脚本整个剔除素材源集目录 + 清单标注块 → 从不注册 → [available]=false，
 *   COVER_MC 款式不出现在样式列表里，图标下标只按 Material 池解释（退化为 COVER_ICON 路线）。
 *
 * 铁律同 [DeviceFeatures]：主模块禁 import 素材源集符号；新增素材能力 = 实现放素材源集
 * + 在这里加槽位。
 */
object CoverArtPack {

    /** 素材包已注册（真实实现可用）。公开版恒 false。 */
    var available: Boolean = false
        private set

    /** 图标池条目数（单面图标个数；等距版下标从 COUNT 起算）。未注册恒 0。 */
    var iconCount: Int = 0
        private set

    /** 某下标是否为等距（物品栏立方体）版。未注册恒 false。 */
    var isIso: (Int) -> Boolean = { false }
        private set

    /** 图标池条目名（选择器/芯片展示用）；未注册返回空串。 */
    var iconNameOf: (Int) -> String = { "" }
        private set

    /** MC 池内的陪衬图下标挑选（nearby 的 MC 语义）；未注册返回 null（调用方原样返回）。 */
    var nearbyMc: ((base: Int, delta: Int) -> Int)? = null
        private set

    /**
     * 绘制一个 MC 贴图图标（ COVER_MC 款的单图标；Material 下标不会走到这里）。
     * 布局几何在主模块的 drawCoverIcon，素材绘制在这里。
     */
    var drawMcIcon: ((DrawScope, index: Int, cell: Float, cx: Float, cy: Float,
                      base: androidx.compose.ui.graphics.Color,
                      shadow: androidx.compose.ui.graphics.Color,
                      shadowDx: Float, shadowDy: Float, alpha: Float) -> Unit)? = null
        private set

    /** 图标选择器 Composable（lockSeries: 0=MC 池 1=Material 池）。未注册为 null。 */
    var picker: (@Composable (
        current: Int,
        lockedSeries: Int,
        onPick: (Int) -> Unit,
        onDismiss: () -> Unit,
    ) -> Unit)? = null
        private set

    /** MC 款的配色方案表（CoverProtocol 的 byStyle 用）；未注册返回空表。 */
    var palettes: List<CoverPal> = emptyList()
        private set

    /**
     * 把图标下标归一到合法池（换款式时调）。未注册 = 不做 MC 语义，原样返回。
     */
    var coerceIcon: (icon: Int, style: Int) -> Int = { icon, _ -> icon }
        private set

    fun install(
        iconCount: Int,
        isIso: (Int) -> Boolean,
        iconNameOf: (Int) -> String,
        nearbyMc: ((Int, Int) -> Int)?,
        drawMcIcon: (DrawScope, Int, Float, Float, Float, androidx.compose.ui.graphics.Color, androidx.compose.ui.graphics.Color, Float, Float, Float) -> Unit,
        picker: @Composable (Int, Int, (Int) -> Unit, () -> Unit) -> Unit,
        palettes: List<CoverPal>,
        coerceIcon: (Int, Int) -> Int,
    ) {
        this.iconCount = iconCount
        this.isIso = isIso
        this.iconNameOf = iconNameOf
        this.nearbyMc = nearbyMc
        this.drawMcIcon = drawMcIcon
        this.picker = picker
        this.palettes = palettes
        this.coerceIcon = coerceIcon
        available = true
    }
}
