package io.github.m0use001.planability.ui.notes

import android.media.MediaPlayer
import android.media.MediaRecorder
import android.widget.Toast
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Undo
import androidx.compose.material.icons.rounded.Add
// v53.8 列表行：类型方章与空态用到的图标
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoStories
import androidx.compose.material.icons.rounded.Brush
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.PictureAsPdf
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.TextFields
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.mutableStateOf
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import io.github.m0use001.planability.ui.common.adaptiveColumn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.m0use001.planability.app.LocalOverlayClose
import io.github.m0use001.planability.ai.rag.WikiLinks
import io.github.m0use001.planability.app.OverlayEnter
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.app.Router
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.HapticTicks
import io.github.m0use001.planability.core.hScrollZone
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.reachedEndHaptic
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.NoteAudio
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.NoteRow
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.NotebookEntity
import io.github.m0use001.planability.ui.common.EmptyHint
import io.github.m0use001.planability.ui.common.hScrollHint
import io.github.m0use001.planability.ui.common.pressScale
import io.github.m0use001.planability.ui.common.wobbleOn
import io.github.m0use001.planability.ui.common.filterChipColors
import io.github.m0use001.planability.ui.common.ConfirmDialog
import io.github.m0use001.planability.ui.common.LabelEditorRow
import io.github.m0use001.planability.ui.common.TagChip
import io.github.m0use001.planability.ui.common.parseLabels
import io.github.m0use001.planability.ui.theme.SeedColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import io.github.m0use001.planability.ui.common.Motion

// ==================== 笔记本封面 ====================

/**
 * 选择器里可选的固定款式编号。约定（v19.33 起）：
 *   [COVER_SOLID] -1 素色默认款（没有风格的默认态）→ 0 起为新风格（从 0 开始编号）→ 90 自定义文字平铺
 *
 * 加一款新封面：① 在 NotebookCover 的 `when` 里加对应编号的分支；
 * ② 把它追加进这个列表。三处（绘制/选择/存储）共用同一个编号，避免"画了但选不到"或"选得到但画不出"。
 *
 * 编号来源：《笔记本封面设计 · 18 种艺术风格图鉴》。用户从 18 款里挑了 15 款，
 * 删掉的 3 款是「等距轴测 / 数据可视化 / 像素 8-bit」。
 */
val COVER_PICKER_STYLES = listOf(
    COVER_SOLID,
    0,   // 包豪斯几何抽象
    1,   // 瑞士网格海报
    2,   // 孟菲斯设计
    3,   // 构成主义 / 至上主义
    4,   // 欧普艺术 Op Art
    5,   // 低多边形 Low Poly
    6,   // 蓝图 / 工程图
    7,   // 等高线地形图
    8,   // 剪纸层叠 Paper Cut
    9,   // Art Deco 装饰艺术
    10,  // 星座图 / 星图
    11,  // 莫兰迪色块
    12,  // 故障艺术 Glitch
    13,  // 中式几何装饰艺术
    14,  // 冷抽象（蒙德里安）
    // 以下三个特殊款带自己的名字，且各有**独立的编辑界面**（用户要求）：
    // 不是「一个款式带来源开关」，而是各自一个款式编号，配色/摆放/编辑入口自动分家。
    COVER_TEXT,  // 文字封面（自定义文字 + 排列方式）
    COVER_ICON,  // 图标封面（Material 图标）
    // ◆版封面：素材包（可选素材源集）注册了才可选；公开版没有这一款
) + if (io.github.m0use001.planability.core.CoverArtPack.available) listOf(COVER_MC) else emptyList()

/**
 * 归一化历史/异常编号：只认「选择器里的款式」和「90 文字款」，配色序号也收敛到合法范围。
 *
 * 为什么用白名单而不是写死某个区间：真机上就存在 coverStyle=13/18 这种仓库历史里查无此号的
 * 遗留值（早期 AI 工具或调试写进去的）。它们渲染上本来就落 else（素色），但会让选择器
 * 一格都不亮；白名单写法让以后再加新风格时，陌生编号自动收敛到素色，不用每次补区间。
 *
 * 编号里低位是款式、高位是配色（见 CoverProtocol.encodeCoverStyle），
 * 所以这里要先拆出款式再校验，最后把校验过的配色序号编回去。
 *
 * 文字款（90）不在选择器列表里，但它是合法款式——所以单独放行，
 * 否则用户挑了一个文字预设、一进编辑弹窗就被打回素色。
 */
fun normalizeCoverStyle(raw: Int): Int {
    val base = coverBaseStyle(raw)
    val pal = CoverPalettes.clampPalette(base, coverPaletteIndex(raw))
    if (base == COVER_TEXT) return encodeCoverStyle(COVER_TEXT, pal)
    if (base !in COVER_PICKER_STYLES) return COVER_SOLID
    return encodeCoverStyle(base, pal)
}
// v19.9：按用户要求**下线全部图案款封面**——原来 style 1..10（陆家嘴晨光 / 东京霓虹 / 曼哈顿日落 /
// 维港夜色 / 银河雪山 / 行星轨道 / 深空星云 / 海岸灯塔 / 沙漠绿洲 / 雨林瀑布）。
// 现在只剩两种：素色（else 分支，编号 0 与历史遗留的 1..10 都落这里）与文字款（style 90）。
// 新图案款等用户手把手指导后再加回来（加在 when 的 90 与 else 之间，编号接着往下排，别复用旧号）。
// 协议：全部绘制包进 clipPath(coverShape)（四角曲率一致）；设计空间 1000×1120，X()/Y()/R() 归一化换算。
// v19.8：按要求取消落影，只留装订线。

/**
 * 封面圆角：**全款式统一**，网格 / 编辑器预览 / 列表卡片共用。
 *
 * v19.47：这里曾经对蒙德里安（款式 14）单独放大过（14 → 30 → 20dp），理由是它「图案贴边、
 * 四角被削掉的那一弧特别显眼」。那条路最后走成了这样：
 *   · 两个值分叉 → 选择器里同屏的格子一个圆一个方，看着像两个体系；
 *   · 每次用户提观感（「太小」→「太大」）都要在"哪个数才对"上再赌一次，
 *     而**靠像素反推又量不准**（抗锯齿让阈值检测的起点偏离真实顶边，小圆角尤其被放大成
 *     "看起来小一大截"）。
 * 现在统一用一个常量：观感一致、代码里没有第二处需要同步的半径，
 * 也不用再为"蒙德里安该多圆"做取舍。
 * 真要再调，改这一个数，**全款式一起动**（这是有意的：分叉才是问题本身）。
 */
val COVER_RADIUS = 14.dp

@Composable
fun NotebookCover(
    style: Int,
    modifier: Modifier = Modifier,
    text: String = "",
    layout: Int = 0,
    /**
     * 图标下标（素材包图标池的条目下标，单面平铺用）。
     *
     * 约定：**大于等于素材包图标池数量表示等距（物品栏）立方体版**。
     * 所以这里一个 Int 同时表达了「用哪个图标」与「单面还是等距」，不用再加一列。
     * 只对 [COVER_MC] 有意义；别的款式忽略它。
     */
    icon: Int = 0,
    /**
     * 多图标排列里每个位置各自的图标（CSV）。空 = 没指定，陪衬位由 nearby() 自动挑。
     * 只对 [COVER_MC] / [COVER_ICON] 有意义。
     */
    icons: String = "",
    /** v19.7：封面底部压印的笔记本名（空 = 不压印） */
    title: String = ""
) {
    val textMeasurer = rememberTextMeasurer()
    // v19.8：按用户要求取消落影，只留装订线
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        // v19.47：圆角**不再按款式分叉**，全款式共用 [COVER_RADIUS]。
        // 蒙德里安曾经单独放大过（14 → 30 → 20dp），分叉带来的问题比它解决的问题多：
        // 同屏两个体系、每次调观感都要再赌一个数、而像素反推又量不准。
        // 现在这一行就是唯一的半径来源 —— 想改就改 COVER_RADIUS，全款式一起动。
        // v56.2 例外（用户：概览小图=封面缩小，圆角等比例缩放）：网格封面实测宽 ≈150dp，
        // 14dp/150 ≈ 0.093——迷你渲染按同一比例算半径（22dp 宽 → ≈2dp，与大封面同观感），
        // ≥40dp 的正常尺寸仍严格 COVER_RADIUS——常数约定只对正常尺寸生效，迷你档是缩略语义。
        // 注意 radiusPx 必须喂给 clipPath **和**下面各分支自画的 drawRoundRect：
        // 后者若是写死 14dp，交集仍按大圆角算，小图就会缩成胶囊（v56.2a 实测踩坑）。
        val radiusPx = if (size.minDimension >= 40.dp.toPx()) COVER_RADIUS.toPx()
            else size.minDimension * 0.093f
        val baseStyle = coverBaseStyle(style)
        val coverShape = Path().apply {
            addRoundRect(
                androidx.compose.ui.geometry.RoundRect(
                    0f, 0f, w, h,
                    androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
                )
            )
        }
        clipPath(coverShape) {
            // 配色：同一套画法可有多种配色，取当前下标那套。
            val pal = CoverPalettes.of(baseStyle)
                .getOrElse(CoverPalettes.clampPalette(baseStyle, coverPaletteIndex(style))) {
                    CoverPalettes.of(COVER_SOLID).first()
                }
            when (baseStyle) {
                0 -> drawCoverBauhaus(pal)
                1 -> drawCoverSwiss(pal)
                2 -> drawCoverMemphis(pal)
                3 -> drawCoverConstructivism(pal)
                4 -> drawCoverOpArt(pal)
                5 -> drawCoverLowPoly(pal)
                6 -> drawCoverBlueprint(pal)
                7 -> drawCoverContour(pal)
                8 -> drawCoverPaperCut(pal)
                9 -> drawCoverArtDeco(pal)
                10 -> drawCoverStarChart(pal)
                11 -> drawCoverMorandi(pal)
                12 -> drawCoverGlitch(pal)
                13 -> drawCoverChinese(pal)
                14 -> drawCoverMondrian(pal)
                COVER_MC -> drawCoverIcon(pal, icon, layout, icons)
                    // 素材包未注册时该款式不可选（normalizeCoverStyle 收敛成素色），
                    // 存量数据带 MC 下标进来也只会留下底色渐变（单图标槽位为 null）
                COVER_ICON -> drawCoverIcon(pal, icon, layout, icons)
                COVER_TEXT -> { // 自定义文字平铺（text + layout 由用户输入）
                    val base = pal["base"]
                    drawRoundRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                androidx.compose.ui.graphics.lerp(base, Color.White, 0.10f),
                                base,
                                androidx.compose.ui.graphics.lerp(base, Color.Black, 0.10f)
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
                    )
                    // 空文字时**不画字**（原来是补一个默认的「笔记本」平铺）。
                    // 用户反馈：输入框明明是空的，封面上却铺满「笔记本笔记本」，
                    // 远看像一块脏掉的纯色——空就该是干净的底色，打字了才开始平铺。
                    val content = text
                    val ts0 = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold)
                    val m0 = textMeasurer.measure(AnnotatedString(content), ts0)
                    if (content.isNotBlank()) if (layout == 3) {
                        // 居中大字（宽 74%）：v19.9 按要求去掉底衬与浮雕，只留单层文字
                        val s2 = (w * 0.74f) / m0.size.width.coerceAtLeast(1)
                        val tsBig = TextStyle(fontSize = (40 * s2).sp, fontWeight = FontWeight.Bold)
                        // 无宽度约束预排版 + 用 TextLayoutResult 绘制（drawText 简便重载会按画布宽重新排版导致意外换行）
                        val mbig = textMeasurer.measure(
                            AnnotatedString(content), tsBig,
                            constraints = androidx.compose.ui.unit.Constraints()
                        )
                        drawText(
                            mbig,
                            topLeft = Offset((w - mbig.size.width) / 2f, (h - mbig.size.height) / 2f),
                            color = Color.White.copy(alpha = 0.92f)
                        )
                    } else {
                        // 平铺：0 网格对齐 / 1 错行（砖墙）/ 2 斜向（-18° 旋转）
                        // 密度（用户要求提高）：字宽 42% -> 28%，水平步长 1.3 -> 1.15，
                        // 垂直步长 2.05 -> 1.45。原参数每行只放得下 3 个、行距接近一个字高，
                        // 稀疏得像占位符；现在每行 6 个左右，行距也收拢到能连成纹理。
                        val s1 = (w * 0.28f) / m0.size.width.coerceAtLeast(1)
                        val ts = TextStyle(fontSize = (40 * s1).sp, fontWeight = FontWeight.Bold)
                        val m = textMeasurer.measure(
                            AnnotatedString(content), ts,
                            constraints = androidx.compose.ui.unit.Constraints()
                        )
                        val tw = m.size.width.toFloat().coerceAtLeast(1f)
                        val th = m.size.height.toFloat()
                        // v19.9：去掉浮雕双层，只留单层文字
                        fun stamp(x: Float, y: Float) {
                            drawText(m, topLeft = Offset(x, y), color = Color.White.copy(alpha = 0.55f))
                        }
                        var y = -th
                        var row = 0
                        while (y < h + th) {
                            val off = if (layout == 1 && row % 2 == 1) tw * 0.55f else 0f
                            var x = -tw + off
                            while (x < w + tw) {
                                if (layout == 2) {
                                    rotate(-18f, pivot = Offset(w / 2f, h / 2f)) { stamp(x, y) }
                                } else {
                                    stamp(x, y)
                                }
                                x += tw * 1.15f
                            }
                            y += th * 1.45f
                            row++
                        }
                    }
                }
                else -> { // 素色（v19.8：只保留上下渐变，斜向柔光/压线边框已按用户要求取消）
                    val base = pal["base"]
                    drawRoundRect(
                        brush = androidx.compose.ui.graphics.Brush.verticalGradient(
                            listOf(
                                androidx.compose.ui.graphics.lerp(base, Color.White, 0.10f),
                                base,
                                androidx.compose.ui.graphics.lerp(base, Color.Black, 0.10f)
                            )
                        ),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(radiusPx, radiusPx)
                    )
                }
            }
            // —— v15.3 统一装订线（细折痕：窄影 + 细线，不再压大色块）——
            val creaseX = w * 0.12f
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    listOf(Color.Black.copy(alpha = 0.1f), Color.Transparent)
                ),
                topLeft = Offset(creaseX, 0f),
                size = androidx.compose.ui.geometry.Size(w * 0.028f, h)
            )
            drawLine(
                Color.Black.copy(alpha = 0.2f),
                start = Offset(creaseX, 0f), end = Offset(creaseX, h),
                strokeWidth = 1.5f
            )
            // v19.8：按用户要求——封面只保留装订线，其余拟物光影/暗角/颗粒/内缘高光/落影全部取消
        }
    }
}

// ==================== 笔记页主入口 ====================

@Composable
fun NotesScreen() {
    val haptics = LocalHaptics.current
    val scope = rememberCoroutineScope()
    var openNotebook by remember { mutableStateOf<NotebookEntity?>(null) }
    // v69d「三级滑动」（2026-11-21 用户拍板）：editingNote 上提到根——宽屏 BoardDeck 以
    // openNotebook / editingNote 推导层级（1 网格+最近 / 2 笔记列表+网格 / 3 编辑器+列表），
    // 窄屏内页覆盖层也共用这一份状态（单一来源，宽窄不漂移）。
    var editingNote by remember { mutableStateOf<Long?>(null) }
    // 后台冻结（2026-10-30 用户需求）：进程被杀后重进，回到上次打开的笔记本。
    // 存 id 不存对象（查询刷新会换实例）；笔记本已删就安静留在列表页。
    // 恢复完成前记录 effect 不写，否则首组合会先把 null 落盘覆盖旧值。
    var notesRestored by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        io.github.m0use001.planability.app.UiFreeze.awaitLoaded()
        if (io.github.m0use001.planability.app.UiFreeze.enabled) {
            // 2026-11-01：只在冻结页签确实是 notes 时才恢复笔记本。
            // 否则「上次停在别的 tab、但笔记本 id 还留在 KV」时，冷启动会把笔记列表
            // 弹在首页/计划页上面（用户看到的是点了小组件却进了笔记）。
            val tab = Graph.settings.kvGet("shell.freeze.tab")
            val id = if (tab == "notes" || tab == null) {
                Graph.settings.kvGet("shell.freeze.notebook")?.toLongOrNull() ?: 0L
            } else 0L
            if (id > 0L) openNotebook = Graph.db.notebookDao().byId(id)
        }
        notesRestored = true
    }
    LaunchedEffect(notesRestored, openNotebook?.id) {
        if (!notesRestored || !io.github.m0use001.planability.app.UiFreeze.enabled) return@LaunchedEffect
        Graph.settings.kvPut("shell.freeze.notebook", openNotebook?.id?.toString() ?: "")
    }
    var showNewBook by remember { mutableStateOf(false) }
    // v56 语义检索模型管理收拢进「模型设置」（原顶栏 语义检索 键已撤）
    // v54.0 全库搜索（跨笔记本）
    var showGlobalSearch by remember { mutableStateOf(false) }
    var editBook by remember { mutableStateOf<NotebookEntity?>(null) }

    // 分享接入（ACTION_SEND → 新笔记）与全局路由直达编辑器
    // 键用标志本身：各页常驻组合（beyondViewportPageCount = tabs.size），Unit 版早跑完、翻转不重跑
    var directEditNote by remember { mutableStateOf<Long?>(null) }
    val notesRootWide = io.github.m0use001.planability.core.rememberWindowSizeClass().isWide
    val notesRootScope = androidx.compose.runtime.rememberCoroutineScope()
    // v69d：统一的「打开一条笔记」入口——宽屏直接钻到**编辑器级**（openNotebook + editingNote
    // 同置，BoardDeck 从 level1 一路滑到 level3），窄屏维持整屏编辑器。
    // （DB 查询是 suspend，进协程；宽度在组合期取值。）
    val openNoteEntry: (Long) -> Unit = { id ->
        if (notesRootWide) {
            notesRootScope.launch {
                val nbId = Graph.notes.noteById(id)?.notebookId
                val nb = nbId?.let { nid -> Graph.notes.notebooks().firstOrNull { it.id == nid } }
                if (nb != null) {
                    openNotebook = nb
                    editingNote = id
                } else {
                    directEditNote = id
                }
            }
        } else {
            directEditNote = id
        }
    }

    // v68（2026-11-21「纸条导航」）：上面 2026-11-01 的「切走就收子页」watcher 与 v50.3 的
    // notebookMemory id 回读 hack 一起退役。原来收层是因为子页是独立 Dialog 窗口、会浮在
    // 新 tab 之上（锁不归零藏别人的底栏、BackHandler 替别人吃返回）；v68 起
    // PredictiveBackOverlay 页内化 + 锁按 tab 记账 + 返回手势按 LocalTabActive 门控，
    // 三个问题都在壳层根治——openNotebook / showNewBook / editBook / directEditNote
    // 全部随本页纸条原样保留（页面记忆：编辑器写一半切去专注，回来接着写）。
    LaunchedEffect(Router.pendingSharedText) {
        val shared = Router.pendingSharedText
        if (shared != null) {
            Router.pendingSharedText = null
            val nb = Graph.notes.notebooks().firstOrNull()
                ?: run {
                    Graph.notes.createNotebook("默认笔记本", COVER_SOLID, 210f)
                    Graph.notes.notebooks().firstOrNull()
                }
            if (nb != null) {
                val id = Graph.notes.createNote(nb.id, "", "md")
                Graph.notes.noteById(id)?.let {
                    Graph.notes.saveNote(it.copy(content = shared, title = firstLineTitle(shared)))
                }
                directEditNote = id
            }
        }
    }
    LaunchedEffect(Router.pendingNoteId) {
        val pending = Router.pendingNoteId
        if (pending != null) {
            Router.pendingNoteId = null
            if (Graph.notes.noteById(pending) != null) directEditNote = pending
        }
    }

    // 2026-10-24 用户指令：笔记页「取消下拉标题增大动画，但保留圆角标题栏」。
    // 与计划页同改：不接 collapseScrollConnection、不保留展开区（Extra 行程为 0），
    // 标题栏恒定在固定态（64dp 圆角浮层 + 小标题），标签筛选行照旧从圆角下滚过。

    Box(Modifier.fillMaxSize()) {
    if (notesRootWide) {
        // v69d「三级滑动」（2026-11-21 用户拍板「笔记三级全做成滑动」）：宽屏整页是一条
        // **水平板组**（BoardDeck），层级由 openNotebook / editingNote 推导——
        //   level1 [笔记本网格 | 最近笔记] → 点笔记本：集体右滑（最近笔记滑出右缘、
        //   网格滑进副框连续改排版、列表从左滑进主框）→ level2 [笔记列表 | 笔记本网格]
        //   → 点笔记 → level3 [编辑器 | 笔记列表]；返回集体左移（预测返回跟手拖回）。
        // 相框仍由壳层固定绘制（v69「框不动」），瓦片透明画在框上。
        val level = when {
            editingNote != null -> 3
            openNotebook != null -> 2
            else -> 1
        }
        // 顶栏动作区（搜索全部笔记）：网格板头与窄屏 M3ETitleBar 共用同一份
        val notesTopActions: @Composable () -> Unit = {
            // v54.0 全库搜索：跨笔记本找东西（原来只能在单个本子里过滤）
            androidx.compose.material3.IconButton(onClick = {
                haptics.perform(HapticEngine.Kind.Tap)
                showGlobalSearch = true
            }) {
                Icon(Icons.Rounded.Search, contentDescription = "搜索全部笔记")
            }
        }
        io.github.m0use001.planability.ui.common.BoardDeck(
            level = level,
            onBack = {
                // 逐级左移：先退编辑器，再退笔记本
                if (editingNote != null) editingNote = null
                else {
                    openNotebook = null
                    editingNote = null
                }
            },
            // v69 统一宽度比：副框 = BoardLayout.DetailWidth（380），与首页/计划/专注/我的同源
            detailWidth = io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth,
            panes = listOf(
                // 板 0：最近笔记（根层副框；钻入后滑出右缘）
                {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        NotesRecentPane(
                            onOpenNote = { id -> openNoteEntry(id) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                // 板 1：笔记本网格（根层主框；钻入后滑进副框当「选本器」，FAB 只在主框摆）
                {
                    NotesGridBoard(
                        notesWide = true,
                        topActions = notesTopActions,
                        showFab = level == 1,
                        onOpenNotebook = { openNotebook = it },
                        onEditBook = { editBook = it },
                        onNewBook = { showNewBook = true }
                    )
                },
                // 板 2：笔记列表（level2 主框 / level3 副框；返回箭头只在主框显示）
                {
                    NotesListBoard(
                        notebookOrNull = openNotebook,
                        inBoard = true,
                        isMain = level == 2,
                        onOpenNote = { editingNote = it },
                        onCreateNote = { editingNote = it },
                        onManage = { openNotebook?.let { editBook = it } },
                        onBack = {
                            openNotebook = null
                            editingNote = null
                        }
                    )
                },
                // 板 3：编辑器（level3 主框；freeform/pdf 仍全屏覆盖）
                {
                    NotesEditorBoard(
                        editingNote = editingNote,
                        embedded = true,
                        onDone = { editingNote = null },
                        onOpenNote = { target, _ -> editingNote = target }
                    )
                }
            )
        )
    } else {
        // 窄屏：网格整屏（SplitBoards 单板形态）+ 浮层标题栏 + FAB（v69d 起网格体收进 NotesGridBoard）
        Column(Modifier.fillMaxSize()) {
            androidx.compose.foundation.layout.BoxWithConstraints(Modifier.weight(1f)) {
            val notesWide = false
            val gridWidth = maxWidth
            // 顶栏动作区（搜索全部笔记）：网格板头与窄屏 M3ETitleBar 共用同一份
            val notesTopActions: @Composable () -> Unit = {
                androidx.compose.material3.IconButton(onClick = {
                    haptics.perform(HapticEngine.Kind.Tap)
                    showGlobalSearch = true
                }) {
                    Icon(Icons.Rounded.Search, contentDescription = "搜索全部笔记")
                }
            }
            io.github.m0use001.planability.ui.common.SplitBoards(
                twoPane = notesWide,
                // v69「框不动」：宽屏两板形态时板底由壳层固定框绘制（窄屏分支 transparent 无效）
                transparent = true,
                // v69 统一宽度比：右板 = BoardLayout.DetailWidth（380），与首页/计划/专注/我的同源
                detailWidth = io.github.m0use001.planability.ui.common.BoardLayout.DetailWidth,
                // 窄屏：壳不加 padding/inset（网格 contentPadding 与浮层标题栏自理，逐字不动）
                primary = {
                    NotesGridBoard(
                        notesWide = notesWide,
                        topActions = notesTopActions,
                        showFab = false,
                        onOpenNotebook = { openNotebook = it },
                        onEditBook = { editBook = it },
                        onNewBook = { showNewBook = true }
                    )
                }
            )
            // 标题栏：宽屏 BoardHeader 已长在左板板面上（上面 primary 里）；
            // 窄屏保持原样——浮层圆角标题栏压在网格上层（最后声明 = 最上层），筛选行从圆角下钻过
            if (!notesWide) io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = "笔记",
                // 固定态：没有展开区，副标题（笔记本数/筛选态）无处可放——用户 2026-10-24
                // 拍板「取消下拉标题增大动画，保留圆角标题栏」，统计信息一并让位。
                // 2026-10-25：标题栏 + 摘掉——新增笔记本改用计划页同款独立新增按钮（底部中间圆形）。
                collapsedFraction = 1f,
                expandable = false,
                modifier = Modifier.align(Alignment.TopCenter),
                // v51 知识库：语义检索开关（模型按需下载，未启用时检索走词法）
                actions = notesTopActions
            )
            // 新增笔记本 = 独立新增按钮（计划页同款 MediumFab，圆形、屏幕底部中间）
            io.github.m0use001.planability.ui.common.MediumFab(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); showNewBook = true },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding() + 10.dp)
            ) { Icon(Icons.Rounded.Add, contentDescription = "添加笔记本") }
            }
        }
    }

    }
    openNotebook?.let { book ->
        // v69d：宽屏内页已并进 BoardDeck（level2/3 随层级滑动），这里只剩**窄屏**整屏覆盖层。
        if (!notesRootWide) {
            // 笔记列表（选择笔记）也是全屏子页：套统一外壳，进场/预测返回/程序化关闭
            // 与编辑器同一套动效。Forward：网格→列表是层级导航（规范 "Forward and backward"
            // 的 inbox→thread 场景），进场走水平 1/4 滑+淡。
            PredictiveBackOverlay(
                onBack = {
                    openNotebook = null
                    editingNote = null
                },
                enter = OverlayEnter.Forward,
                // fullscreen=true：笔记本内是整屏列表 + 编辑器覆盖层，要整屏宽度
                fullscreen = true
            ) {
                Box(Modifier.fillMaxSize()) {
                    NotesListBoard(
                        notebookOrNull = book,
                        inBoard = false,
                        isMain = true,
                        onOpenNote = { editingNote = it },
                        onCreateNote = { editingNote = it },
                        onManage = { editBook = book },
                        // 列表板返回：走外壳的程序化关闭（180ms 收缩）再落状态
                        onBack = LocalOverlayClose.current
                    )
                    // 编辑器覆盖层（窄屏全屏，NoteEditorScreen 自包外壳与返回动效）
                    NotesEditorBoard(
                        editingNote = editingNote,
                        embedded = false,
                        onDone = { editingNote = null },
                        onOpenNote = { target, _ -> editingNote = target }
                    )
                }
            }
        }
    }

    if (showNewBook) {
        // v19.39：新建与管理统一走**独立全屏界面**，不再用居中弹窗。
        // 用户反馈「不是所有笔记本详细编辑界面都要做成独立界面吗」——
        // 同一张表单在两个入口给两种容器，新建时还挤在弹窗里、
        // 排列/图标/标签几排要在弹窗那点高度里滚，观感和操作都不一致。
        // 2026-10-24：两条入口都套 PredictiveBackOverlay —— 进场滑升+淡入、
        // 预测返回跟手、关闭/保存走 180ms 收缩（原来直接 if 硬切硬消失）。
        PredictiveBackOverlay(onBack = { showNewBook = false }) {
            NotebookDialog(
                title = "新建笔记本", book = null,
                // 关闭统一走 LocalOverlayClose（带动画）；真正的状态复位在外层 onBack
                onDismiss = LocalOverlayClose.current,
                asScreen = true
            ) { name, style, labels, ctext, clayout, cicon, cicons ->
                scope.launch {
                    Graph.notes.createNotebook(name, style, DEFAULT_COVER_HUE, labels, ctext, clayout, cicon, cicons)
                }
            }
        }
    }
    editBook?.let { book ->
        PredictiveBackOverlay(onBack = { editBook = null }) {
            NotebookDialog(
                title = "管理笔记本", book = book,
                onDismiss = LocalOverlayClose.current,
                // 用户要求管理界面是独立界面（不再是一层弹窗）
                asScreen = true,
                // v19.15：data 层早有 deleteNotebook，但 UI 一直没入口（长按只能改名）——
                // 笔记本建错/建多了没法删。笔记会随笔记本进回收站，本体删除不可恢复，故二次确认。
                onDelete = {
                    scope.launch { Graph.notes.deleteNotebook(book.id) }
                }
            ) { name, style, labels, ctext, clayout, cicon, cicons ->
                scope.launch {
                    Graph.notes.renameNotebook(book, name, style, DEFAULT_COVER_HUE, labels, ctext, clayout, cicon, cicons)
                }
            }
        }
    }

    // v53.9 `[[笔记#小节]]`：记下"打开哪篇的哪一节"，编辑器起来后自己定位一次就清掉
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var pendingAnchorFor by remember { mutableStateOf(0L) }

    directEditNote?.let { id ->
        NoteEditorScreen(
            noteId = id,
            onDone = { directEditNote = null },
            onOpenNote = { target, anchor ->
                if (anchor.isNotBlank()) { pendingAnchor = anchor; pendingAnchorFor = target }
                directEditNote = target
            },
            initialAnchor = pendingAnchor?.takeIf { pendingAnchorFor == id },
            onAnchorConsumed = { pendingAnchor = null }
        )
    }

    // v54.0 全库搜索：命中行点开 = 关搜索 + 直接进编辑器（与「资料」页同一条打开路径）；
    // v56 问笔记并入该屏第二模式（搜笔记/问笔记 chips），入口仍是这一个搜索键
    if (showGlobalSearch) {
        GlobalNoteSearchScreen(
            onDone = { showGlobalSearch = false },
            onOpenNote = { id ->
                showGlobalSearch = false
                openNoteEntry(id)
            }
        )
    }
}

// TopAppBar 是实验性 API（material3 一直没转正），全屏编辑页用到它，这里显式 opt-in
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun NotebookDialog(
    title: String,
    book: NotebookEntity?,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    /**
     * true = 全屏独立编辑页（管理笔记本用），false = 居中弹窗（新建笔记本用）。
     * 两种容器喂同一份内容，表单只维护一处。
     *
     * 必须放在 [onConfirm] **之前**：调用处用的是 trailing lambda，
     * 它会绑到参数表的最后一个位置，放最后会把 Boolean 参数当成 lambda 来传。
     */
    asScreen: Boolean = false,
    onConfirm: (name: String, style: Int, labels: String, text: String, layout: Int, icon: Int, icons: String) -> Unit
) {
    var name by remember { mutableStateOf(book?.name ?: "") }
    // v19.9：新风格从 0 起编号，素色让位 COVER_SOLID(-1)。开框就把陌生编号（已下线的 1..10
    // 图案款、遗留的 13/18 之类）归一成素色，否则选择器一格都不亮，用户不点直接「确定」
    // 又把旧编号原样写回库。
    val rawStyle = book?.coverStyle ?: COVER_SOLID
    var style by remember { mutableIntStateOf(normalizeCoverStyle(rawStyle)) }
    // v19.34：coverHue 不再参与渲染（配色一律走 CoverPal），
    // 写库时统一填 [DEFAULT_COVER_HUE]，老行读出来是什么就还是什么，不做无意义改写。
    // v18：自定义封面（文字 + 排列方式，快照存本笔记本）
    var customText by remember { mutableStateOf(book?.coverText ?: "") }
    var customLayout by remember { mutableIntStateOf(book?.coverLayout ?: 0) }
    // v19.35：封面图标下标（>= 素材包图标池数量是等距版，>= MaterialIconSet.offset 是 Material 图标）。
    // v19.36 起落库：`NotebookEntity.coverIcon` 列已随 MIGRATION_30_31 建好，
    // `renameNotebook`/`createNotebook` 都接了 icon 参数，这里从已有笔记本读回选中值。
    var customIcon by remember {
        // v19.44：打开时就把下标迁到当前款式的池子里。
        // 存量库里有「ICON 款 + 下标却是 MC 的 0」这种行（新建时按 0 存下的），
        // 不迁的话这颗 chip 会显示 MC 的条目名、计数回到 MC 池，而选择器锁在 Material
        // —— 用户看到「封面是 Material 图标、名字却是方块名」，改也改不动。
        mutableIntStateOf(io.github.m0use001.planability.core.CoverArtPack.coerceIcon(book?.coverIcon ?: 0, coverBaseStyle(rawStyle)))
    }
    // v19.37：多图标排列里每个位置各自的图标（CSV，-1 占位表示该位置没指定）。
    // 空串 = 一个都没指定，陪衬位由 nearby() 自动挑，行为与加这列之前一致。
    var customIcons by remember { mutableStateOf(book?.coverIcons ?: "") }
    // 正在改第几个位置；-1 = 在改主图标（「选择图标」按钮走这条）
    var editingSlot by remember { mutableIntStateOf(-1) }
    // 样式选择器里**同一时刻只能框选一个**：
    // 选到具体内容（预设或本机快照）时，「文字封面」款那格就不再重复框选，
    // 否则同屏两个选中框，看着像能同时选两个。
    var pickedText by remember { mutableStateOf(book?.coverText?.isNotBlank() == true) }
    var showIconPicker by remember { mutableStateOf(false) }
    // v15：自定义封面预设库（新建多个 / 长按编辑 / 删除）
    val dlgScope = rememberCoroutineScope()
    // 标签清单与颜色都来自全局仓库：新建/管理、以及别的页面看到的都是同一套
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    // 弹窗里的排列方式 chip = 选择（HIG selection → 最轻的 Select）
    val haptics = LocalHaptics.current
    var covers by remember { mutableStateOf(listOf<io.github.m0use001.planability.data.CustomCover>()) }
    var customDialog by remember { mutableStateOf<String?>(null) } // "create" | "editPreset" | "editNotebook"
    var editingPreset by remember { mutableStateOf<io.github.m0use001.planability.data.CustomCover?>(null) }
    var draftText by remember { mutableStateOf("") }
    var draftLayout by remember { mutableIntStateOf(0) }
    fun reloadCovers() {
        dlgScope.launch { covers = Graph.settings.customCovers() }
    }
    // v17：标签（与全软共享一套体系）
    var labels by remember { mutableStateOf(book?.labels ?: "") }
    var showPicker by remember { mutableStateOf(false) }
    // v19.15：删除二次确认
    var confirmDelete by remember { mutableStateOf(false) }
    if (showPicker) reloadCovers()
    fun openCustomEditor(mode: String, preset: io.github.m0use001.planability.data.CustomCover? = null) {
        customDialog = mode
        editingPreset = preset
        draftText = preset?.text ?: customText
        draftLayout = preset?.layout ?: customLayout
    }
    // 独立全屏页要独占一级页外壳：底栏是**一级界面专属**的，它在 NavShell 里画在
    // 页面之上（HorizontalPager 之后声明），不锁的话会一直压在编辑页上面，
    // 看起来像「明明是独立界面，怎么还挂着底部菜单栏」。
    // 用项目现成机制：lockCount 驱动 NavShell 的底栏显隐（左缘抽屉手势也一并让位）。
    if (asScreen) io.github.m0use001.planability.core.LockShellChrome()
    // 退出统一走动画：全屏页由外层 PredictiveBackOverlay 提供 close（180ms 收缩后
    // 才经 onBack 复位状态，所以保存/删除后这里也要主动 dismiss，不能只靠外层状态）；
    // 弹窗模式（无 overlay）回退到 onDismiss。
    val dismissAnimated: () -> Unit = if (asScreen) LocalOverlayClose.current else onDismiss

    // ---------- 表单内容：弹窗与全屏独立编辑页共用一份 ----------
    // 用户要求「管理界面改成独立界面」，但新建仍是弹窗——两种容器喂同一份内容，
    // 避免同一套表单在两处各写一遍（改一处漏一处）。
    val content: @Composable (Modifier) -> Unit = { m ->
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    // 必须用 m：它带着调用方给的 padding/尺寸（全屏页的 16dp 边距与
                    // Scaffold 的 contentPadding 都在这里）。写成 Modifier 等于把
                    // 调用方的约束整份丢掉——真机表现是内容顶到左边缘、并与底栏重叠。
                    modifier = m.verticalScroll(rememberScrollState())
                ) {
                    NotebookCover(style, Modifier.width(120.dp).height(150.dp), text = customText, layout = customLayout, icon = customIcon, icons = customIcons, title = name.ifBlank { "笔记本" })
                    val curBase = coverBaseStyle(style)
                    val palCount = CoverPalettes.countOf(curBase)
                    // v14.1：封面选择二级菜单（可用款随 style 递增）。
                    // 占满整行而不是只有文字那么宽：TextButton 的点击区只有文字大，
                    // 在这个可滚动的弹窗里几乎点不中（自动化连点几次都进不去，用户也容易点空）。
                    TextButton(onClick = { showPicker = true }, modifier = Modifier.fillMaxWidth()) {
                        Text("选择封面")
                    }
                    // v19.38：文字封面的详细编辑**直接放在这里**（用户要求合并进管理界面）。
                    // 它原先藏在自定义封面编辑弹窗的 "editNotebook" 分支里，要多点一层才改得了
                    // 字和排列；现在选了文字款就能就地改，预览也跟着实时变。
                    // v19.45：分节细线。原来所有子项一律 10dp 间距，预览、输入框、
                    // 两排 chip、标签挤成一片，看不出哪里是下一件事。用与抽屉同一套细线分块：
                    // [封面预览 + 选择封面] | [名称 + 封面细节] | [标签 + 删除]。
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("名称") }, singleLine = true)
                    if (curBase == COVER_TEXT) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "封面文字",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = customText,
                                onValueChange = { customText = it; pickedText = true },
                                placeholder = { Text("笔记本名或任意文字") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                "文字排列",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                // v19.45：4dp -> 6dp。去掉对勾后每颗更矮，行与行贴成一片，
                                // 与同屏其它区块的 6dp 也不一致。
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                listOf("网格", "错行", "斜向", "居中").forEachIndexed { i, n ->
                                    FilterChip(
                                        colors = filterChipColors(),
                                        selected = customLayout == i,
                                        onClick = {
                                            // 只改排列，不动 pickedText：切网格/错行/斜向/居中
                                            // 属于「在当前款里调参数」，不是「挑了某个具体封面」。
                                            // 之前这里置 true，用户随手切个排列，样式选择器里
                                            // 「文字封面」款的框就没了，改由本机快照格亮着。
                                            customLayout = i
                                            haptics.perform(HapticEngine.Kind.Select)
                                        },
                                        label = { Text(n, maxLines = 1, softWrap = false) }
                                        // v19.45：同上，去掉对勾防换行（选中态有 primaryContainer 实底）
                                    )
                                }
                            }
                        }
                    }

                    // v19.34：配色方案切换。素色款和文字款也接进来了，所以这一排**任何款式都有意义**
                    // （单配色款式除外，它们只有一套，画出来是多余的一排按钮）。
                    // 「换色」按钮已删除：它每次 hue+=47° 去撞一条色环，既撞不到想要的颜色，
                    // 又和下面这排是同一个功能——现在选色一律在这里，和款式同一个界面。
                    if (palCount > 1) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "配色方案",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // 用 FlowRow 而不是 Row：配色名有长有短（「原版」2 字 vs「夜光孟菲斯」5 字），
                            // Row 遇到放不下的就把 chip 里的**文字**折行（「夜光孟/菲斯」），
                            // 看起来像坏掉了。FlowRow 是把整个 chip 换到下一行。
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until palCount) {
                                    val on = i == coverPaletteIndex(style)
                                    FilterChip(
                                        // 配色芯片必须走 filterChipColors()，否则会用 Material3
                                        // 默认的 secondaryContainer，和同页其它芯片差一整个色相
                                        colors = filterChipColors(),
                                        selected = on,
                                        onClick = {
                                            style = encodeCoverStyle(curBase, i)
                                            haptics.perform(HapticEngine.Kind.Select)
                                        },
                                        label = {
                                            Text(
                                                CoverPalettes.labelOf(curBase, i),
                                                // 再兜一层：单行 + 不软换行，任何宽度下都不折行
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        // v19.45：同上，去掉对勾防换行（选中态有 primaryContainer 实底）
                                    )
                                }
                            }
                        }
                    }
                    // v19.35：◆ 款专有的「摆放方式」。
                    // 复用**已有的 coverLayout 列**，不为此再往数据库加一列——
                    // 它的语义本就是「当前款式内部的布局选项」：文字款用它存文字平铺，
                    // MC 款用它存图标摆放，两者互不相干（切款式时各自解释自己那一套）。
                    if (curBase == COVER_MC || curBase == COVER_ICON) {
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                "摆放方式",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                // 只遍历 VISIBLE：底部一排/对角平铺/环绕已按用户要求下架，
                                // 但常量与绘制分支保留（库里可能还存着那三个序号）。
                                for (l in McLayouts.VISIBLE) {
                                    val on = l == customLayout
                                    FilterChip(
                                        colors = filterChipColors(),
                                        selected = on,
                                        onClick = {
                                            customLayout = l
                                            haptics.perform(HapticEngine.Kind.Select)
                                        },
                                        label = {
                                            Text(
                                                McLayouts.LABELS.getOrElse(l) { "$l" },
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                        // v19.45：**去掉选中时的对勾**（用户 10-16 报「点一下按钮伸长、
                                        // 整排突然换行」的根因）。FilterChip 的选中态本来就有
                                        // `selectedContainerColor = primaryContainer` 的实底 +
                                        // onPrimaryContainer 字色，再加一枚 leadingIcon 会让这颗
                                        // **变宽**，把后面的 chip 挤到下一行 —— 点一下整排跳一次。
                                        // 「永远留 16dp 占位」也能防跳，但那让所有 chip 一直更宽、
                                        // 排数更多；选择态既然已经看得见，就不要这个对勾。
                                    )
                                }
                            }
                        }
                    }
                    // v19.44：图标入口**单元素与多元素共用同一处**。
                    // 原来是两条路：单元素（居中/满铺/四个角部档）走中间那颗
                    // 「选择图标（N / M）」文字按钮，多元素走下面一排「左上：X」chip。
                    // 同一个动作摆成两种形状，用户得先弄清自己是哪一档才知道点哪儿；
                    // 而且那颗文字按钮里塞了一串「N / M」计数，读起来像进度条。
                    // 现在统一成一排 chip：单元素就是这一排只有一颗（方位名是「主体」）。
                    if (curBase == COVER_MC || curBase == COVER_ICON) {
                        val slotN = McLayouts.slotCount(customLayout)
                        val single = slotN <= 1
                        Column(
                            Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                if (single) "封面图标（点一下更换）" else "各位置图标（点一下更换）",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                for (i in 0 until slotN) {
                                    // 未指定的位置显示 nearby() 会选到的那张，
                                    // 让用户看到「这个位置现在实际画的是什么」再决定改不改。
                                    val v = if (single) customIcon else readSlot(customIcons, i)
                                    val shown = if (single) customIcon
                                    else if (v >= 0) v else nearby(customIcon, i)
                                    FilterChip(
                                        colors = filterChipColors(),
                                        // 单元素这颗是**动作按钮**（只有一条路可走），不是"选项"，
                                        // 所以不参与选中态（用户 10-16 明确要求：不要选中特效）。
                                        // 多元素那排的选中态仍在表达「这个位置被单独指定过」，那是有效信息，
                                        // 但也正是它让同一排 chip 一半亮一半灭——单元素不需要这个概念。
                                        selected = !single && v >= 0,
                                        onClick = {
                                            // 单元素时 editingSlot = -1 表示「改主图标 coverIcon」，
                                            // 与多元素写进 coverIcons CSV 是两条不同的存储路径，保持原样。
                                            editingSlot = if (single) -1 else i
                                            showIconPicker = true
                                        },
                                        label = {
                                            // 方位名而不是「位置 N」——四角档要一眼看出是哪个角，
                                            // 名单与绘制顺序一致（McLayouts.slotNames）。单元素档叫「主体」。
                                            val names = McLayouts.slotNames(customLayout)
                                            Text(
                                                "${names.getOrElse(i) { "位置 ${i + 1}" }}：${iconLabel(shown, curBase)}",
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                    // v19.45：分节细线。原来所有子项一律 10dp 间距，预览、输入框、
                    // 两排 chip、标签挤成一片，看不出哪里是下一件事。用与抽屉同一套细线分块：
                    // [封面预览 + 选择封面] | [名称 + 封面细节] | [标签 + 删除]。
                    Box(
                        Modifier.fillMaxWidth().height(1.dp)
                            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    )
                    // v17：标签编辑（预置与日程/待办同一套）
                    Column(Modifier.fillMaxWidth()) {
                        Text(
                            "标签",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        // 以前只传了前两个参数，hues/onColor 走默认空实现——
                        // 长按弹层能开、颜色却存不住（改完一关就没）。待办/日程那边是好的，
                        // 很容易误以为"全局都能改色"，实际只有计划侧生效。
                        LabelEditorRow(
                            labels,
                            globalLabels.names,
                            hues = globalLabels.hues,
                            tints = globalLabels.tints,
                            onColor = { name, tint ->
                                dlgScope.launch {
                                    // 先存浓淡、再存色相：后者会 bump dataVersion 触发全局重读
                                    io.github.m0use001.planability.ui.common.saveLabelTint(name, tint)
                                    Graph.plan.setLabelColor(name, tint?.hue)
                                }
                            },
                            onRename = { old, new ->
                                dlgScope.launch {
                                    io.github.m0use001.planability.ui.common.renameLabelEverywhere(old, new)
                                }
                            }
                        ) { labels = it }
                    }
                    // v19.15：删除笔记本入口（仅编辑态有）。笔记随本进回收站，本体不可恢复 → 二次确认
                    if (onDelete != null) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(onClick = { confirmDelete = true }) {
                                Text("删除笔记本", color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
    }
    val confirm: @Composable () -> Unit = {
                TextButton(onClick = {
                    if (name.isNotBlank()) {
                        // 保存类动作 → Success（HIG notify·success：上扬两段）。
                        // 只在真的会保存时才振：名字为空时这点按下去什么都不发生，
                        // 再给一声"成功"就是撒谎。
                        haptics.perform(HapticEngine.Kind.Success)
                        onConfirm(name.trim(), style, labels, customText.trim(), customLayout, customIcon, customIcons)
                        // 保存后退出也带动画（原来由调用点直接置 false → 硬消失）
                        dismissAnimated()
                    }
                }) { Text("确定") }
    }

    if (asScreen) {
        // 全屏编辑页：顶栏标题 + 返回，底栏取消/确定。
        // 相比弹窗，它不再受屏幕高度挤压——提示文字、槽位 chips 都放得下。
        Scaffold(
            // 必须占满可用高度：这一支渲染在笔记列表的容器里，不填满的话
            // Scaffold 会按内容高度收缩，底栏「取消/确定」就悬在内容末尾而不是屏幕底部。
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = { Text(title) },
                    navigationIcon = {
                        // 项目里统一的「带震动的返回」：返回/关闭 = 中性轻触 → Tap
                        // （HIG 映射表：返回/关闭=Tap，选择=Select，破坏性=ImpactHeavy）
                        io.github.m0use001.planability.ui.common.HapticBackButton(dismissAnimated)
                    }
                )
            },
            bottomBar = {
                Row(
                    Modifier
                        .fillMaxWidth()
                        // 只避让系统手势条。原来这里用的是 navAvoidPadding()（底栏高度+106.dp），
                        // 但本页面已经锁住外壳、底栏根本不会显示，留那 106.dp 纯浪费——
                        // 真机上表现为「取消/确定」悬在半空，下面一大片黑。
                        .navigationBarsPadding()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)
                ) {
                    TextButton(onClick = {
                        // 取消与顶栏返回是同一语义（都是"不保存退出"）→ 同一档 Tap
                        haptics.perform(HapticEngine.Kind.Tap)
                        dismissAnimated()
                    }) { Text("取消") }
                    confirm()
                }
            }
        ) { pad ->
            // 水平 16dp：AlertDialog 自带内容边距，Scaffold 没有，不补的话
            // 「摆放方式」「各位置图标」这类标题会顶到屏幕左边缘被裁（真机上就是这样）。
            content(Modifier.padding(pad).padding(horizontal = 16.dp).fillMaxWidth())
        }
    } else {
        AlertDialog(
            onDismissRequest = dismissAnimated,
            modifier = Modifier.motionEnter(),
            title = { Text(title) },
            text = { content(Modifier) },
            confirmButton = { confirm() },
            dismissButton = { TextButton(onClick = dismissAnimated) { Text("取消") } }
        )
    }
    if (confirmDelete && onDelete != null) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            modifier = Modifier.motionEnter(),
            title = { Text("删除笔记本？") },
            text = {
                Text("「${book?.name ?: ""}」会被删除，无法恢复；其中的笔记会移入回收站，可从回收站找回。")
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                    // 删除完同样走收缩退出（状态复位在 overlay 的 onBack 里）
                    dismissAnimated()
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("取消") } }
        )
    }
    if (showIconPicker && io.github.m0use001.planability.core.CoverArtPack.picker != null) {
        Dialog(onDismissRequest = { showIconPicker = false }) {
            // 2026-11-26：选择器在封面素材包（可选素材包），经槽位调（公开版不可达：
            // showIconPicker 只在 COVER_MC/COVER_ICON 编辑入口置位，后者走 Material 池仍可用）
            io.github.m0use001.planability.core.CoverArtPack.picker!!.invoke(
                if (editingSlot >= 0) {
                    val v = readSlot(customIcons, editingSlot)
                    if (v >= 0) v else customIcon
                } else customIcon,
                // 两个款式各有独立编辑界面：选了哪款就只给那套素材。
                // curBase 在这个作用域里取不到（它定义在 AlertDialog 的 text lambda 内），
                // 所以这里按样式重算一次。
                if (coverBaseStyle(style) == COVER_ICON) 1 else 0,
                { picked ->
                    if (editingSlot < 0) {
                        customIcon = picked
                    } else {
                        customIcons = writeSlot(customIcons, editingSlot, picked)
                    }
                },
                { showIconPicker = false }
            )
        }
    }
    if (showPicker) {
        AlertDialog(
            onDismissRequest = { showPicker = false },
            modifier = Modifier.motionEnter(),
            title = { Text("选择封面样式") },
            text = {
                androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                    columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(2),
                    // 430 + 上下 contentPadding：400 那版最后一行的「图标封面 / ◆版封面」
                    // 会贴着弹窗底边被裁掉半截（用户反馈提示文字显示不完整）。
                    // contentPadding 让滚到头时还有留白，行与边框不再挤在一起。
                    modifier = Modifier
                        .height(430.dp)
                        .padding(horizontal = 4.dp),
                    contentPadding = PaddingValues(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // v19.9：可用款式列表化——加一款新封面只要往 COVER_PICKER_STYLES 里追加编号，
                    // 绘制（NotebookCover 的 when）、选择器、排序全都自动跟上，不会两处漏改。
                    items(COVER_PICKER_STYLES) { st ->
                        // 选中判定比的是「款式」，配色不算；否则换了配色那一格就不亮了
                        // 选了具体内容（预设/快照）时这格让位，避免同屏两个框
                        val selected = st == coverBaseStyle(style) &&
                            !(st == COVER_TEXT && pickedText)
                        val curPal = CoverPalettes.clampPalette(st, coverPaletteIndex(style))
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)
                                    ) else Modifier
                                )
                                // 切款式时保留配色序号（换款不该顺手把配色重置掉）
                                .clickable {
                                    val wasBase = coverBaseStyle(style)
                                    style = encodeCoverStyle(st, curPal)
                                    if (st == COVER_TEXT) pickedText = false
                                    // v19.44：换款式时把图标下标迁到对应的素材池（ICON ↔ MC）。
                                    // 不迁的话，MC→ICON 会变成「选择器锁在 Material、值却是 MC 的」，
                                    // 选完存下去被绘制回退 —— 用户看到的就是「选了半天没反应」。
                                    if (wasBase != st) {
                                        customIcon = io.github.m0use001.planability.core.CoverArtPack.coerceIcon(customIcon, st)
                                        // 逐个位置的自定义图标同样属于旧池，一并迁过去。
                                        // 直接清空会让用户白挑一遍，逐个迁只是"回到该池第一张"。
                                        val slots = customIcons.split(',')
                                            .mapNotNull { it.trim().toIntOrNull() }
                                        if (slots.isNotEmpty()) {
                                            customIcons = slots.joinToString(",") { v ->
                                                if (v >= 0) io.github.m0use001.planability.core.CoverArtPack.coerceIcon(v, st).toString() else "-1"
                                            }
                                        }
                                    }
                                    showPicker = false
                                }
                        ) {
                            Column {
                                NotebookCover(
                                    encodeCoverStyle(st, curPal),
                                    Modifier.fillMaxWidth().aspectRatio(0.85f)
                                )
                                // 三个特殊款加文字说明：它们不再是「看着像某个风格」的图案格，
                                // 而是各自打开一套独立编辑界面，光看图猜不出在做什么。
                                // 其他款保持纯图（它们的含义由图案本身表达）。
                                if (st == COVER_TEXT || st == COVER_ICON || st == COVER_MC) {
                                    Text(
                                        when (st) {
                                            COVER_TEXT -> "文字封面"
                                            COVER_ICON -> "图标封面"
                                            else -> "◆版封面"
                                        },
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        // 上下都留白：文字贴着格子底边时会被裁掉半截
                                        modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
                                    )
                                }
                            }
                        }
                    }
                    // v15：自定义封面预设库（点选应用，长按编辑/删除）
                    val ownSnapshot = coverBaseStyle(style) == COVER_TEXT && covers.none { it.text == customText && it.layout == customLayout }
                    items(covers.size, key = { covers[it].id }) { idx ->
                        val p = covers[idx]
                        val selected = pickedText && coverBaseStyle(style) == COVER_TEXT &&
                            customText == p.text && customLayout == p.layout
                        Box(
                            Modifier
                                // 新增一个自定义封面预设时要有落位动画：没有 animateItem 它就是"凭空冒出来"
                                .animateItem()
                                .clip(RoundedCornerShape(10.dp))
                                .then(
                                    if (selected) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)
                                    ) else Modifier
                                )
                                .combinedClickable(
                                    onClick = {
                                        customText = p.text; customLayout = p.layout; style = COVER_TEXT; pickedText = true
                                        showPicker = false
                                    },
                                    onLongClick = {
                                        haptics.perform(HapticEngine.Kind.ImpactLight)
                                        openCustomEditor("editPreset", p)
                                    }
                                )
                        ) {
                            NotebookCover(COVER_TEXT, Modifier.fillMaxWidth().aspectRatio(0.85f), text = p.text, layout = p.layout)
                        }
                    }
                    // 本笔记本自有快照（不在预设库中时显示，长按编辑）
                    if (ownSnapshot) item {
                        Box(
                            Modifier
                                .clip(RoundedCornerShape(10.dp))
                                // 边框跟着选中态走：用户刚点的是「文字封面」款时不该亮，
                                // 否则同屏两个框，看起来像能同时选两个封面。
                                .then(
                                    if (pickedText) Modifier.border(
                                        2.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(10.dp)
                                    ) else Modifier
                                )
                                .combinedClickable(
                                    onClick = { pickedText = true; showPicker = false },
                                    onLongClick = {
                                        haptics.perform(HapticEngine.Kind.ImpactLight)
                                        openCustomEditor("editNotebook")
                                    }
                                )
                        ) {
                            NotebookCover(COVER_TEXT, Modifier.fillMaxWidth().aspectRatio(0.85f), text = customText, layout = customLayout)
                        }
                    }
                    // 「＋新建自定义」按钮已按用户要求取消——新建入口移到「文字封面」款
                    // 自己的编辑界面里，不再占一个样式格（它跟样式列表不是同一类东西）。
                }
            },
            confirmButton = { TextButton(onClick = { showPicker = false }) { Text("关闭") } }
        )
    }
    if (customDialog != null) {
        val isPresetEdit = customDialog == "editPreset"
        AlertDialog(
            onDismissRequest = { customDialog = null },
            modifier = Modifier.motionEnter(),
            title = {
                Text(
                    when (customDialog) {
                        "create" -> "新建自定义封面"
                        "editPreset" -> "编辑自定义封面"
                        else -> "编辑封面文字"
                    }
                )
            },
            text = {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    NotebookCover(COVER_TEXT, Modifier.width(120.dp).height(150.dp), text = draftText, layout = draftLayout)
                    OutlinedTextField(
                        value = draftText,
                        onValueChange = { draftText = it },
                        label = { Text("封面文字") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("排列方式", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        listOf("网格", "错行", "斜向", "居中").forEachIndexed { i, n ->
                            androidx.compose.material3.FilterChip(
                                colors = filterChipColors(),
                                selected = draftLayout == i,
                                onClick = { draftLayout = i; haptics.perform(HapticEngine.Kind.Select) },
                                label = { Text(n) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val nt = draftText.trim().ifBlank { "笔记本" }
                    when (customDialog) {
                        "create" -> {
                            val preset = io.github.m0use001.planability.data.CustomCover(System.currentTimeMillis(), nt, draftLayout)
                            dlgScope.launch {
                                Graph.settings.setCustomCovers(covers + preset)
                                covers = covers + preset
                            }
                            customText = nt; customLayout = draftLayout; style = COVER_TEXT; pickedText = true
                        }
                        "editPreset" -> {
                            val old = editingPreset
                            if (old != null) {
                                val updated = covers.map {
                                    if (it.id == old.id) it.copy(text = nt, layout = draftLayout) else it
                                }
                                dlgScope.launch {
                                    Graph.settings.setCustomCovers(updated)
                                    Graph.notes.applyCustomCoverEdit(old.text, old.layout, nt, draftLayout)
                                }
                                covers = updated
                                if (coverBaseStyle(style) == COVER_TEXT && customText == old.text && customLayout == old.layout) {
                                    customText = nt; customLayout = draftLayout
                                }
                            }
                        }
                        else -> {
                            customText = nt; customLayout = draftLayout; style = COVER_TEXT; pickedText = true
                        }
                    }
                    customDialog = null
                }) { Text(if (isPresetEdit) "保存" else "使用") }
            },
            dismissButton = {
                Row {
                    val delTarget = editingPreset
                    if (isPresetEdit && delTarget != null) {
                        TextButton(onClick = {
                            dlgScope.launch {
                                Graph.settings.setCustomCovers(covers.filter { it.id != delTarget.id })
                            }
                            covers = covers.filter { it.id != delTarget.id }
                            customDialog = null
                        }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                    }
                    TextButton(onClick = { customDialog = null }) { Text("取消") }
                }
            }
        )
    }
}

// ==================== 笔记列表板（v12 批E：无边框+悬浮胶囊岛+批量编辑+右滑操作+置顶移动） ====================

/**
 * v69d（2026-11-21「笔记三级全做成滑动」）：原 NoteListPage 的**列表板**——
 * 宽屏作 BoardDeck 的板 2（level2 主框 / level3 副框），窄屏作整屏覆盖层的主体；
 * 编辑器分派拆去 [NotesEditorBoard]，`editingNote`/`openNotebook` 上提到 NotesScreen 根。
 *
 * 滑出保留：[notebook] 置 null（BoardDeck 退级动画中）时仍用**最后一次非空值**渲染，
 * 板滑出的路上内容不蒸发（动画走完自然停在屏外）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NotesListBoard(
    notebookOrNull: NotebookEntity?,
    /** 板形态（宽屏瓦片 / 窄屏整屏覆盖层）：透明底交给板、板头平铺；窄屏整屏自带底与圆角栏 */
    inBoard: Boolean,
    /** 是否当前主框（决定返回箭头：副框里的列表不给返回键，返回归主框管） */
    isMain: Boolean,
    onOpenNote: (Long) -> Unit,
    /** 新建（md/画布/PDF 导入）后回调——根层把它接去 editingNote（= 钻到编辑器级） */
    onCreateNote: (Long) -> Unit,
    onManage: () -> Unit,
    /** 返回（宽屏=退一级；窄屏=外壳程序化关闭） */
    onBack: () -> Unit
) {
    // 滑出保留：notebookOrNull 置 null 后仍用最后一次非空值渲染（正文对 notebook 零改动）
    var lastBook by remember { mutableStateOf<NotebookEntity?>(null) }
    if (notebookOrNull != null) lastBook = notebookOrNull
    val notebook = lastBook ?: return
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    // 2026-11-07 P0-2：列表行投影 NoteRow（不含 content/inkJson，预览/字数查询时算好）
    var notes by remember { mutableStateOf<List<NoteRow>>(emptyList()) }
    var trashed by remember { mutableStateOf<List<NoteRow>>(emptyList()) }
    var inTrash by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    // v56.2 搜索框改按钮（用户拍板「免得占位置」）：默认不占行，点栏上搜索键才展开输入行
    var searchOpen by remember { mutableStateOf(false) }
    val searchFocus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(searchOpen) {
        if (searchOpen) {
            kotlinx.coroutines.delay(120)   // 等输入行进入组合再拉焦点
            runCatching { searchFocus.requestFocus() }
        }
    }
    var newMenu by remember { mutableStateOf(false) }
    val version by Graph.dataVersion.flow.collectAsState()
    val navAvoid = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()

    // v12 批E：批量编辑 / 右滑展开 / 移动与批量删除
    // 非一级界面：隐藏底部导航栏（外壳由调用处的 PredictiveBackOverlay 统一锁）。
    var selectionMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf(setOf<Long>()) }
    var moveIds by remember { mutableStateOf<List<Long>?>(null) }
    var confirmBatchDelete by remember { mutableStateOf(false) }
    // 全面屏手势：批量模式里先退批量，再由外壳的预测返回退列表页。
    // 只在批量模式启用——启用时本回调比外壳的 PredictiveBackHandler 后注册、优先吃到；
    // 平时保持禁用，预测返回手势才能跟手缩放整页。
    // v68：按 LocalTabActive 门控（本页常驻组合，别的 tab 上不得吃系统返回）。
    androidx.activity.compose.BackHandler(enabled = selectionMode && io.github.m0use001.planability.core.LocalTabActive.current) {
        selectionMode = false
        selectedIds = emptySet()
    }
    // 胶囊岛水平拖动
    var islandX by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    // PDF 导入选择器（composable 期注册，岛内「PDF 批注」触发）
    val pdfPicker = rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                runCatching {
                    val context = Graph.appContext
                    val dir = File(context.filesDir, "pdfs").apply { mkdirs() }
                    val target = File(dir, "note_${System.currentTimeMillis()}.pdf")
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    }
                    val id = Graph.notes.createNote(notebook.id, "", "pdf")
                    Graph.db.noteDao().update(
                        Graph.db.noteDao().byId(id)!!.copy(content = target.absolutePath)
                    )
                    withContext(Dispatchers.Main) { onCreateNote(id) }
                }.onFailure { }
            }
        }
    }

    // 2026-11-07 P0-2：列表改投影行（不含正文，打开笔记再取全文）
    LaunchedEffect(version, inTrash) {
        if (inTrash) {
            trashed = Graph.notes.trashedRows()
        } else {
            notes = Graph.notes.noteRowsOf(notebook.id)
        }
    }

    // 2026-11-07 P0-1：搜索改「防抖 300ms + 笔记本内 FTS」（原 listFor 每键对全部正文做
    // 主线程 contains 扫描，2000 篇实测 p90=89ms）。searchRows 非 null = 搜索态；
    // 回收站无搜索框，顺手修掉「列表态输入的 query 残留过滤回收站」的隐性 bug。
    var searchRows by remember { mutableStateOf<List<NoteRow>?>(null) }
    LaunchedEffect(query, version, inTrash) {
        if (inTrash || query.isBlank()) {
            searchRows = null
            return@LaunchedEffect
        }
        kotlinx.coroutines.delay(300)
        searchRows = io.github.m0use001.planability.ai.rag.NoteSearch.searchInNotebook(query, notebook.id)
    }

    // 2026-11-07 P0-1：非搜索态沿用本地列表（不再 contains 全文）；搜索态用 FTS 结果
    fun listFor(trash: Boolean): List<NoteRow> {
        if (!trash && searchRows != null) return searchRows!!
        return (if (trash) trashed else notes)
            .sortedWith(compareByDescending<NoteRow> { it.pinned }.thenByDescending { it.updatedAt })
    }

    val shown = listFor(inTrash)

    // v69d：列表体仍是 [inBoard] 参数驱动的一份实现——宽屏作 BoardDeck 瓦片（透明底、
    // 平铺板头），窄屏作整屏覆盖层主体（自带底、圆角栏）；两处共用，视觉不漂移。
    val listBody: @Composable (Boolean) -> Unit = { inBoard ->
    Box(
        Modifier
            .fillMaxSize()
            .then(if (inBoard) Modifier else Modifier.background(MaterialTheme.colorScheme.surface))
    ) {
        // 列表 ⇄ 回收站：**硬切**（2026-10-25 用户：所有横切界面切换时重叠）。
        // 横向过渡的中间帧两套「标题/搜索框/列表」同屏，看起来就是叠字；
        // 分类管理页同类问题已用硬切解决，这里统一成硬切：任何一帧只有一套内容。
        val trashShown = listFor(inTrash)
        Column(Modifier.fillMaxSize()) {
            // v56 用户拍板：笔记列表页顶部也用圆角标题栏（与笔记本网格页同一套 M3E 语言，
            // 原来是「无边框轻标题」平顶——笔记域里最后一块没跟上圆角栏的界面）。
            // 栏体自含状态栏 inset，所以外层 Column 的 statusBarsPadding 一并撤掉。
            io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = when {
                    selectionMode -> "已选 ${selectedIds.size} 项"
                    inTrash -> notebook.name + " · 回收站"
                    else -> notebook.name
                },
                collapsedFraction = 1f,
                expandable = false,
                // v64：在板上时用平铺形态（无底圆角/无投影），与另一块板的板头同一语言
                flat = inBoard,
                // v69d：返回箭头只在**主框**里显示——它现在有明确目的地（退一级：宽屏由
                // BoardDeck 负责集体左移，窄屏由外壳收缩关闭）。副框里的列表不给返回键，
                // 返回归主框/系统手势管（两个返回键会互相抢）。
                navigationIcon = if (isMain) {
                    {
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            onBack()
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回")
                        }
                    }
                } else null,
                // v56.2 笔记本名称右侧的封面概览小图（用户：就是封面缩小——纵向 0.78 与网格
                // 同一画法同一比例，圆角按迷你尺寸比例收，见 NotebookCover radiusPx 注释）
                titleTrailing = {
                    NotebookCover(
                        notebook.coverStyle,
                        modifier = Modifier.size(width = 22.dp, height = 28.dp),
                        text = notebook.coverText, layout = notebook.coverLayout,
                        icon = notebook.coverIcon, icons = notebook.coverIcons,
                        title = ""
                    )
                },
                actions = {
                    // v56.2 搜索框改按钮：点开才出输入行，默认不占一行
                    if (!inTrash && !selectionMode) {
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            if (searchOpen) { query = ""; searchOpen = false } else searchOpen = true
                        }) {
                            Icon(
                                if (searchOpen) Icons.Rounded.Close else Icons.Rounded.Search,
                                contentDescription = if (searchOpen) "关闭搜索" else "搜索笔记"
                            )
                        }
                    }
                    // 编辑笔记本：用户 2026-10-24 要求从底部胶囊岛的铅笔移到右上角。
                    // 批量模式下标题变为「已选 N 项」，这个入口先收起（避免误触丢选择态）。
                    if (!selectionMode) {
                        IconButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Tap)
                            onManage()
                        }) {
                            Icon(
                                Icons.Rounded.Edit,
                                contentDescription = "编辑笔记本",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            )
            // 回收站 ⇄ 正常列表：搜索框也跟着硬切（2026-10-25）。原来用收放动画，
            // 退出期间搜索框会悬在回收站内容上方，和列表/空提示同屏 → 就是用户说的"切换时重叠"。
            // v56.2 默认隐藏：搜索框改成标题栏按钮，点开才出这一行（免得常驻占位置）
            if (searchOpen && !inTrash) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    placeholder = { Text("搜索笔记") },
                    leadingIcon = { Icon(Icons.Rounded.Search, null) },
                    singleLine = true, shape = RoundedCornerShape(14.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 2.dp)
                        .focusRequester(searchFocus)
                )
            }
            val noteListState = androidx.compose.foundation.lazy.rememberLazyListState()
            // key(inTrash)：切换列表/回收站时**换一棵 LazyColumn**。只换数据不够——
            // items 带 key + Modifier.animateItem()，旧行会做退场淡化，和新内容同屏叠一瞬
            // （实际截图抓到过：三条旧笔记压在"回收站是空的"上面）。
            androidx.compose.runtime.key(inTrash) {
            LazyColumn(Modifier.fillMaxSize().padding(horizontal = 12.dp).reachedEndHaptic(noteListState), state = noteListState) {
                // 2026-11-07 P0-3：搜索无结果不再显示「还没有笔记」（用户会以为笔记丢了）
                val searching = !inTrash && query.isNotBlank()
                if (trashShown.isEmpty() && searching) {
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp)
                        ) {
                            Icon(
                                Icons.Rounded.Search, null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                            Text(
                                "没有匹配「" + query.trim() + "」的笔记",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp)
                            )
                            Text(
                                "换个更短的关键词，或用「全库搜索」跨笔记本找",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                } else if (trashShown.isEmpty()) {
                    // v53.8 空态从一行灰字改成「图标 + 一句它是什么 + 一句它能干什么 + 入口」，
                    // 否则新用户进到笔记本里只看到「还没有笔记」，不知道这里和文件库有什么区别。
                    item {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.fillMaxWidth().padding(top = 72.dp, bottom = 24.dp)
                        ) {
                            Icon(
                                if (inTrash) Icons.Rounded.DeleteOutline else Icons.Rounded.AutoStories,
                                contentDescription = null,
                                modifier = Modifier.size(46.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                            )
                            Text(
                                if (inTrash) "回收站是空的" else "还没有笔记",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(top = 14.dp)
                            )
                            Text(
                                if (inTrash) "删掉的笔记会先放这里，随时能恢复"
                                else "写下的内容会自动进知识库：AI 能检索、能被 [[链接]] 串起来",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                modifier = Modifier.padding(top = 6.dp, start = 32.dp, end = 32.dp)
                            )
                            if (!inTrash) {
                                androidx.compose.material3.TextButton(
                                    onClick = { newMenu = true },
                                    modifier = Modifier.padding(top = 6.dp)
                                ) { Text("写第一篇") }
                            }
                        }
                    }
                }
                items(trashShown, key = { it.id }) { note ->
                    Box(Modifier.animateItem()) {
                    NoteRowItem(
                        note = note,
                        // v69e：卡片右上角叠放缩略卡用所属笔记本的封面（用户参考图那块）
                        bookStyle = notebook.coverStyle,
                        inTrash = inTrash,
                        selectionMode = selectionMode,
                        selected = note.id in selectedIds,
                        onClick = {
                            // 打开=Tap；批量模式下这一下是勾选/取消（Checkbox 同一路径），按表格用 Select
                            if (selectionMode) haptics.perform(HapticEngine.Kind.Select)
                            else haptics.perform(HapticEngine.Kind.Tap)
                            when {
                                inTrash -> scope.launch { Graph.notes.restoreNote(note.id) }
                                selectionMode -> selectedIds =
                                    if (note.id in selectedIds) selectedIds - note.id else selectedIds + note.id
                                else -> onOpenNote(note.id)
                            }
                        },
                        // 2026-11-01：批量多选的入口从「长按行」改到长按菜单的「多选」项——
                        // 长按手势让给操作菜单；触感已由菜单打开时（行内 Rigid）给出，这里不再重复
                        onEnterSelection = {
                            if (!inTrash && !selectionMode) {
                                selectionMode = true
                                selectedIds = setOf(note.id)
                            }
                        },
                        onPin = {
                            scope.launch { Graph.notes.setNotePinned(note.id, !note.pinned) }
                        },
                        onMove = { moveIds = listOf(note.id) },
                        onDelete = {
                            scope.launch { Graph.notes.trashNote(note.id) }
                        }
                    )
                    }
                }
                item { Spacer(Modifier.height(40.dp)) }
            }
            }
        }

        // ---------- 悬浮胶囊岛（可拖动；批量模式下切换为批量操作组） ----------
        Box(
            contentAlignment = Alignment.BottomCenter,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                // 只避让系统手势条，**不要**再加底栏那 106dp。
                // 本页是笔记本内部页，外壳底栏根本不显示；用 navAvoidPadding() 会把小岛
                // 顶到离屏幕底还差一大截的半空（真机现象就是「底部菜单栏没有固定」）。
                // 同文件 line 1003 的「取消/确定」已经踩过一次并改掉了，这里是同一个坑。
                .navigationBarsPadding()
                .padding(bottom = 10.dp)
        ) {
            Surface(
                shape = RoundedCornerShape(percent = 50),
                shadowElevation = 8.dp,
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                // 原来这里可以横向拖动（islandX + detectDragGestures），
                // 实际用起来就是「底部菜单栏左右乱动」——手指滑动列表时偶尔蹭到它、
                // 一旦拖走它还不会回到中间。底部工具栏应该是固定的，已去掉拖动。
            ) {
                // 常规 ⇄ 批量 两组按钮的切换：方向性轻移 + 快淡入淡出，胶囊宽度随内容
                // 用同一根弹簧收放（sizeTransform），不再硬切。
                AnimatedContent(
                    targetState = selectionMode,
                    contentAlignment = Alignment.Center,
                    transitionSpec = {
                        val dir = if (targetState) 1 else -1
                        ((slideInHorizontally(Motion.standardSpatial()) { it * dir / 3 } + fadeIn(Motion.quick())) togetherWith
                            (slideOutHorizontally(Motion.standardSpatial()) { -it * dir / 3 } + fadeOut(Motion.quick())))
                            .using(SizeTransform(clip = false) { _, _ -> Motion.sizeSpring() })
                    },
                    label = "note-list-island-mode",
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                ) { sel ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (sel) {
                            IslandButton(Icons.Rounded.Check, "全选/取消") {
                                selectedIds = if (selectedIds.size == shown.size) emptySet() else shown.map { it.id }.toSet()
                            }
                            IslandButton(Icons.Rounded.Delete, "删除") {
                                if (selectedIds.isNotEmpty()) confirmBatchDelete = true
                            }
                            IslandButton(Icons.Rounded.FolderOpen, "移动") {
                                if (selectedIds.isNotEmpty()) moveIds = selectedIds.toList()
                            }
                            IslandButton(Icons.Rounded.Close, "退出多选") {
                                selectionMode = false; selectedIds = emptySet()
                            }
                        } else {
                            // v56.2 返回已移到标题栏 navigationIcon，岛上只留 新建/回收站。
                            Box {
                                IslandButton(Icons.Rounded.Add, "新建") { newMenu = true }
                                NewNoteMenu(
                                    expanded = newMenu,
                                    onDismiss = { newMenu = false },
                                    onCreateMd = {
                                        newMenu = false
                                        scope.launch { onCreateNote(Graph.notes.createNote(notebook.id, "", "md")) }
                                    },
                                    onCreateFreeform = {
                                        newMenu = false
                                        scope.launch { onCreateNote(Graph.notes.createNote(notebook.id, "", "freeform")) }
                                    },
                                    onImportPdf = {
                                        newMenu = false
                                        pdfPicker.launch(arrayOf("application/pdf"))
                                    }
                                )
                            }
                            // 回收站⇄列表的按钮换向：图标/文案快淡交叉（同一颗键的内容变化）
                            AnimatedContent(
                                targetState = inTrash,
                                transitionSpec = { fadeIn(Motion.quick()) togetherWith fadeOut(Motion.quick()) },
                                label = "island-trash-btn"
                            ) { trash ->
                                IslandButton(
                                    if (trash) Icons.Rounded.Refresh else Icons.Rounded.Delete,
                                    if (trash) "返回列表" else "回收站"
                                ) { inTrash = !inTrash }
                            }
                            // 铅笔（管理笔记本）已按用户要求移到列表页右上角，岛上只留返回/新建/回收站。
                        }
                    }
                }
            }
        }
    }

    // 批量/单条移动到笔记本
    moveIds?.let { ids ->
        MoveNotesDialog(notebookId = notebook.id, onDismiss = { moveIds = null }) { target ->
            scope.launch {
                ids.forEach { Graph.notes.moveNote(it, target) }
                selectionMode = false
                selectedIds = emptySet()
            }
            moveIds = null
        }
    }

    if (confirmBatchDelete) {
        ConfirmDialog(
            title = "批量删除",
            text = "把选中的 ${selectedIds.size} 条笔记移入回收站？",
            onConfirm = {
                scope.launch { selectedIds.forEach { Graph.notes.trashNote(it) } }
                selectionMode = false
                selectedIds = emptySet()
                confirmBatchDelete = false
            },
            onDismiss = { confirmBatchDelete = false }
        )
    }
    }   // listBody 关闭（v69d：宽屏作 BoardDeck 瓦片，窄屏作整屏覆盖层主体，两处共用）

    // v69d：列表板只渲染列表体——编辑器/概览分派已拆去 NotesEditorBoard（BoardDeck 板 3）。
    listBody(inBoard)
}

/**
 * v69d（2026-11-21「笔记三级全做成滑动」）：笔记**编辑器板**——原 NoteListPage 宽屏
 * primary 的 `when(kind)` 分派独立出来，作 BoardDeck 的板 3（level3 主框）与窄屏覆盖层。
 *
 * 滑出保留：editingNote 置 null（退级动画中）时仍用**最后一次非空值**渲染，编辑器板
 * 滑出的路上内容不蒸发；freeform/pdf 画布类仍各自全屏打开（不落板）。
 */
@Composable
private fun NotesEditorBoard(
    editingNote: Long?,
    embedded: Boolean,
    onDone: () -> Unit,
    onOpenNote: (Long, String) -> Unit
) {
    var lastNote by remember { mutableStateOf<Long?>(null) }
    if (editingNote != null) lastNote = editingNote
    // 滑出保留只属于**板形态**（embedded，BoardDeck 瓦片）；窄屏整屏覆盖层必须严格跟随
    // editingNote——onDone 置 null 就要真退（否则 lastNote 顶着编辑器永远关不掉）。
    val id = if (embedded) (editingNote ?: lastNote ?: return) else editingNote ?: return
    // v53.9 `[[笔记#小节]]` 跳转：打开哪篇的哪一节
    var pendingAnchor by remember { mutableStateOf<String?>(null) }
    var pendingAnchorFor by remember { mutableStateOf(0L) }
    var kind by remember(id) { mutableStateOf<String?>(null) }
    LaunchedEffect(id) { kind = Graph.notes.noteById(id)?.kind }
    when (kind) {
        "freeform" -> FreeformScreen(noteId = id, onDone = onDone)
        "pdf" -> PdfNoteScreen(noteId = id, onDone = onDone)
        "md", null -> if (kind != null) NoteEditorScreen(
            noteId = id,
            embedded = embedded,
            onDone = onDone,
            onOpenNote = { target, anchor ->
                if (anchor.isNotBlank()) { pendingAnchor = anchor; pendingAnchorFor = target }
                onOpenNote(target, anchor)
            },
            initialAnchor = pendingAnchor?.takeIf { pendingAnchorFor == id },
            onAnchorConsumed = { pendingAnchor = null }
        )
    }
}

/**
 * v69d：笔记页的**笔记本网格板**——原根层 SplitBoards.primary 的网格体独立出来，
 * 宽屏作 BoardDeck 的板 1（根层主框，钻入后滑进副框当「选本器」），窄屏作整屏网格。
 * 数据自持（notebooks / noteStats / labelFilter / 网格滚动位），与列表板互不依赖。
 *
 * @param showFab 「新建笔记本」浮动按钮是否显示（只在它是主框时摆；滑进副框后收起，
 *                免得副框里挂一个大 FAB）
 */
@Composable
private fun NotesGridBoard(
    notesWide: Boolean,
    topActions: @Composable () -> Unit,
    showFab: Boolean,
    onOpenNotebook: (NotebookEntity) -> Unit,
    onEditBook: (NotebookEntity) -> Unit,
    onNewBook: () -> Unit
) {
    val haptics = LocalHaptics.current
    var notebooks by remember { mutableStateOf<List<NotebookEntity>>(emptyList()) }
    // v53.8 每个笔记本的「篇数 / 最近更新」，封面下方显示
    var noteStats by remember { mutableStateOf<Map<Long, Pair<Int, Long>>>(emptyMap()) }
    // v17：笔记本按标签划分（与全软日程/待办共享一套标签）
    var labelFilter by remember { mutableStateOf<String?>(null) }
    // 全局统一标签：笔记本/待办/日程三处来源 + 预置词，统一算法见 ui/common/LabelStore.kt。
    val globalLabels = io.github.m0use001.planability.ui.common.rememberGlobalLabels()
    val version by Graph.dataVersion.flow.collectAsState()
    LaunchedEffect(version) {
        notebooks = Graph.notes.notebooks()
        // v53.8 封面下方补「N 篇 · 最近更新」：原来只写名字和标签，几个空笔记本和一本书
        // 长什么样看不出区别。
        // 2026-11-07 P0-2：改聚合查询——原来 allNotes() 把全部正文拉进内存只为数篇数。
        noteStats = Graph.notes.notebookStats().associate { it.notebookId to (it.cnt to it.latest) }
    }
    val bookState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    // 再点一次「笔记」图标 → 笔记本网格回到顶部
    io.github.m0use001.planability.app.ScrollToTopOnReselect("notes", bookState)

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            if (notesWide) {
                io.github.m0use001.planability.ui.common.BoardHeader(
                    title = "笔记",
                    actions = topActions
                )
            }
            LazyVerticalGrid(
                state = bookState,
                // v57 平板：Fixed(2) 是手机口径——平板 1248dp 宽下一格巨大（实测封面占半屏）。
                // 改 Adaptive(156dp)：手机内容宽 328dp → 仍然恰好 2 列（328/156=2.1），
                // 平板自然增多列数，封面（固定 120dp 宽）在格内不变形。
                columns = GridCells.Adaptive(minSize = 156.dp),
                modifier = Modifier
                    .then(
                        if (notesWide) Modifier.fillMaxWidth().weight(1f)
                        else Modifier.fillMaxSize()
                    )
                    .reachedEndHaptic(bookState),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    16.dp,
                    top = if (notesWide) 4.dp
                    else WindowInsets.statusBars.asPaddingValues().calculateTopPadding() +
                        io.github.m0use001.planability.ui.common.M3ETitleBarPinnedH + 12.dp,
                    16.dp,
                    bottom = 16.dp + io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding()
                ),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // v17：标签筛选（按标签划分笔记本）——2026-11-22 用户「可滑动项不要碰到
                // 框/圆角矩形边缘」：板内横滑胶囊行改单个下拉胶囊（当前标签即摘要）。
                item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                    // 标签筛选 = 选择（HIG selection → 最轻的 Select），和计划页筛选同一档
                    io.github.m0use001.planability.ui.common.SelectDropdownChip(
                        summary = labelFilter ?: "全部",
                        options = listOf("全部") + globalLabels.names,
                        selectedIndex = if (labelFilter == null) 0 else globalLabels.names.indexOf(labelFilter) + 1,
                        onSelect = { idx ->
                            labelFilter = if (idx == 0) null else globalLabels.names[idx - 1]
                            haptics.perform(HapticEngine.Kind.Select)
                        }
                    )
                }
                if (notebooks.isEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        EmptyHint("还没有笔记本\n点右上角 + 新建")
                    }
                }
                val shown = if (labelFilter == null) notebooks else notebooks.filter { parseLabels(it.labels).contains(labelFilter) }
                if (shown.isEmpty() && notebooks.isNotEmpty()) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        EmptyHint("该标签下还没有笔记本")
                    }
                }
                items(shown, key = { it.id }) { book ->
                    // v14：长按笔记本 = 换封面/改名/标签（管理）
                    // 2026-11-20：格子必须先 clip 再挂 combinedClickable。原来链上没有 clip，
                    // 按压/悬停水波是整格直角矩形——封面不透明把上半截盖住，只剩封面下方
                    // 文字区露出一块灰方块（用户平板截图报的「突兀的正方形」就是这里）。
                    // 圆角取封面同一个 [COVER_RADIUS]，水波与封面同曲率。
                    Column(
                        Modifier.animateItem()
                            // pressScale 必须在 clip 之前（Motion.kt 摆放铁律）
                            .pressScale()
                            .clip(RoundedCornerShape(COVER_RADIUS))
                            .combinedClickable(
                                onClick = {
                                    haptics.perform(HapticEngine.Kind.Tap)
                                    onOpenNotebook(book)
                                },
                                onLongClick = {
                                    // 长按 = 管理入口，按 Haptics.kt 五档表用 ImpactLight（原来误用 Tap）
                                    haptics.perform(HapticEngine.Kind.ImpactLight)
                                    onEditBook(book)
                                }
                            )
                    ) {
                        NotebookCover(
                            book.coverStyle,
                            modifier = Modifier.fillMaxWidth().aspectRatio(0.78f),
                            text = book.coverText, layout = book.coverLayout,
                            // icon / icons 都要传：漏掉 icon 时封面永远画默认那张
                            // （City 卡片上长期显示 ABC 就是这么来的），
                            // 漏掉 icons 则多图标排列永远退回 nearby() 的默认挑法。
                            icon = book.coverIcon, icons = book.coverIcons,
                            title = book.name
                        )
                        Text(
                            book.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp, start = 4.dp)
                        )
                        // v53.8：篇数 + 最近更新。空笔记本写「还没有笔记」，比留白更有指引性。
                        val stat = noteStats[book.id]
                        Text(
                            when {
                                stat == null || stat.first == 0 -> "还没有笔记"
                                else -> "${stat.first} 篇 · ${NotePreview.relativeTime(stat.second)}"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            modifier = Modifier.padding(top = 1.dp, start = 4.dp)
                        )
                        val bookLabels = parseLabels(book.labels)
                        if (bookLabels.isNotEmpty()) {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp, start = 4.dp)
                            ) {
                                // 颜色跟全局标签色走。原来写死 212f（蓝），
                                // 于是笔记本上的标签永远一片蓝，而计划页显示的是
                                // 各人设过的颜色——同一个标签在两处颜色不一样。
                                bookLabels.take(3).forEach { lb ->
                                    TagChip(lb, globalLabels.tintOf(lb, 212f))
                                }
                            }
                        }
                    }
                }
            }
        }
        // 新增笔记本 = 独立新增按钮（计划页同款 MediumFab，圆形、屏幕底部中间）
        if (showFab) {
            io.github.m0use001.planability.ui.common.MediumFab(
                onClick = { haptics.perform(HapticEngine.Kind.Tap); onNewBook() },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = io.github.m0use001.planability.app.navAvoidPadding().calculateBottomPadding() + 10.dp)
            ) { Icon(Icons.Rounded.Add, contentDescription = "添加笔记本") }
        }
    }
}

/** 悬浮胶囊岛上的单个按钮 */
@Composable
private fun IslandButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    desc: String,
    tint: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
    onClick: () -> Unit
) {
    val haptics = LocalHaptics.current
    IconButton(onClick = { haptics.perform(HapticEngine.Kind.Select); onClick() }) {
        Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(21.dp))
    }
}

/** 新建菜单（原 FAB 三类入口） */
@Composable
private fun NewNoteMenu(
    expanded: Boolean,
    onDismiss: () -> Unit,
    onCreateMd: () -> Unit,
    onCreateFreeform: () -> Unit,
    onImportPdf: () -> Unit
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化
            text = { Text("Markdown 笔记") }, onClick = onCreateMd
        )
        DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            text = { Text("无边记（无限画布）") }, onClick = onCreateFreeform
        )
        DropdownMenuItem(
            modifier = Modifier.clip(RoundedCornerShape(10.dp)),
            text = { Text("PDF 批注（导入 PDF）") }, onClick = onImportPdf
        )
    }
}

/**
 * v69e（2026-11-21 用户参考卡片）：笔记卡右上角**叠放的两枚缩略卡**。
 *
 * v70（2026-11-21 用户拍板「只改缩略卡：无图也显示内容预览」）：主卡改画**笔记内容**缩略
 * （md=文字页 / freeform=手写涂鸦页 / pdf=文档页），不再一律用笔记本封面——
 * 纯文字笔记也有预览可看；笔记本身份由后面那张副卡（所属笔记本封面）保留。
 * 副卡在后（左下、斜 -8°、带白描边），主卡在前（右、直放）。
 */
@Composable
private fun NoteThumbStack(
    style: Int,
    kind: String,
    modifier: Modifier = Modifier
) {
    // 主卡 46dp 宽（0.78 与网格同比例高）；副卡略小、错位露出左上角
    val mainW = 46.dp
    val subW = 40.dp
    Box(modifier) {
        // 副卡：左后、倾斜、白描边（参考图里那层白框）——笔记本身份锚
        Box(
            Modifier
                .padding(top = 6.dp, start = 0.dp)
                .size(width = subW, height = subW / 0.78f)
                .graphicsLayer { rotationZ = -8f }
                .clip(RoundedCornerShape(6.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(1.5.dp)
        ) {
            NotebookCover(style, modifier = Modifier.fillMaxSize(), text = "", layout = 0, title = "")
        }
        // 主卡：右前、直放、白描边 —— 内容预览页
        Box(
            Modifier
                .padding(start = subW * 0.42f)
                .size(width = mainW, height = mainW / 0.78f)
                .clip(RoundedCornerShape(7.dp))
                .background(MaterialTheme.colorScheme.surface)
                .padding(1.5.dp)
        ) {
            NoteContentThumb(kind)
        }
        // 画布/PDF 类型角标：小圆点压在主卡右下，暗示「这不是普通 md」
        if (kind == "pdf" || kind == "freeform") {
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .size(16.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(2.dp)
                    .clip(RoundedCornerShape(50))
                    .background(MaterialTheme.colorScheme.primary)
            )
        }
    }
}

/**
 * v70：主卡里的**内容预览页**（46dp 级别的小画布，全矢量、零 IO）。
 *  · md/文本（默认）：文字页——深色标题条 + 淡正文行（行宽错落，像一页笔记）；
 *  · freeform（手写/画布）：白页 + 两道涂鸦笔迹；
 *  · pdf：文档页——右上折角 + 正文行。
 */
@Composable
internal fun NoteContentThumb(kind: String, compact: Boolean = false) {
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    val titleColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.75f)
    Box(Modifier.fillMaxSize()) {
        when (kind) {
            "freeform" -> {
                val ink = MaterialTheme.colorScheme.primary
                val ink2 = MaterialTheme.colorScheme.tertiary
                Canvas(Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = androidx.compose.ui.graphics.drawscope.Stroke(2.dp.toPx(), cap = StrokeCap.Round)
                    drawPath(
                        Path().apply {
                            moveTo(0f, size.height * 0.68f)
                            quadraticBezierTo(size.width * 0.28f, size.height * 0.15f, size.width * 0.52f, size.height * 0.45f)
                            quadraticBezierTo(size.width * 0.78f, size.height * 0.75f, size.width, size.height * 0.22f)
                        },
                        ink, style = stroke
                    )
                    drawPath(
                        Path().apply {
                            moveTo(size.width * 0.1f, size.height * 0.9f)
                            quadraticBezierTo(size.width * 0.45f, size.height * 0.72f, size.width * 0.9f, size.height * 0.88f)
                        },
                        ink2, style = stroke
                    )
                }
            }
            "pdf" -> {
                // 右上折角：三角折页 + 阴影色
                Canvas(Modifier.fillMaxSize()) {
                    val fold = size.width * 0.3f
                    drawPath(
                        Path().apply {
                            moveTo(size.width - fold, 0f)
                            lineTo(size.width, fold)
                            lineTo(size.width - fold, fold)
                            close()
                        },
                        lineColor.copy(alpha = 0.5f)
                    )
                }
                ThumbTextLines(titleColor, lineColor, compact)
            }
            else -> ThumbTextLines(titleColor, lineColor, compact)
        }
    }
}

/** v70：缩略页里的「标题条 + 正文行」（md 与 pdf 共用；compact=最近笔记 30dp 档）。 */
@Composable
internal fun ThumbTextLines(
    titleColor: androidx.compose.ui.graphics.Color,
    lineColor: androidx.compose.ui.graphics.Color,
    compact: Boolean = false
) {
    val hPad = if (compact) 4.dp else 7.dp
    val vPad = if (compact) 3.dp else 6.dp
    val gap = if (compact) 2.dp else 3.dp
    val barH = if (compact) 1.5.dp else 2.dp
    Column(
        Modifier.fillMaxSize().padding(horizontal = hPad, vertical = vPad),
        verticalArrangement = Arrangement.spacedBy(gap)
    ) {
        Box(Modifier.fillMaxWidth(0.55f).height(if (compact) 2.dp else 3.dp).clip(RoundedCornerShape(2.dp)).background(titleColor))
        Box(Modifier.fillMaxWidth().height(barH).clip(RoundedCornerShape(2.dp)).background(lineColor))
        Box(Modifier.fillMaxWidth(0.9f).height(barH).clip(RoundedCornerShape(2.dp)).background(lineColor))
        Box(Modifier.fillMaxWidth(0.72f).height(barH).clip(RoundedCornerShape(2.dp)).background(lineColor))
        Box(Modifier.fillMaxWidth(0.85f).height(barH).clip(RoundedCornerShape(2.dp)).background(lineColor))
    }
}

/** 笔记行：右滑露出 置顶/移动/删除；批量模式显示勾选框；置顶项排序置前带 📌 */
@Composable
@Suppress("ParameterName")
private fun NoteRowItem(
    // 2026-11-07 P0-2：列表行改 NoteRow 投影（不含正文；预览/字数由查询算好）。参数名保持 note，
    // 行内除 preview/size 外的引用零改动。
    note: NoteRow,
    /**
     * v69e（2026-11-21 用户参考卡片）：所属笔记本的封面风格——卡片右上角那叠缩略图用它
     * 真渲染（笔记本身没有内嵌图片，用「这本笔记本长什么样」当视觉锚，和网格/最近笔记同一语言）。
     */
    bookStyle: Int,
    inTrash: Boolean,
    selectionMode: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    // 2026-11-01 顶部预算（meta-ai 方案 §六）：右滑露出的 置顶/移动/删除 三图标改为
    // 长按菜单（与消息长按一致），行常驻只剩内容；批量多选入口并进菜单末项。
    onEnterSelection: () -> Unit,
    onPin: () -> Unit,
    onMove: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = LocalHaptics.current
    // 每行自持菜单态：长按行即开（回收站/批量模式不响应，与旧长按守卫一致）
    var menuOpen by remember(note.id) { mutableStateOf(false) }

    Box(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                // v57 批 1（B 类首个调用点）：置顶/取消置顶 = 状态翻转，整行抖一下——
                // 长按菜单选「置顶」后 pinned 翻转触发；挂在 clip 之前（摆放铁律，缩得到底块）。
                // 首次组合/回收站里的行不抖（trigger 初值不是翻转，seen 门闩兜住）。
                .wobbleOn(trigger = note.pinned)
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .let { m ->
                    // 2026-11-01：右滑露出已删（操作进长按菜单），但**横向拖拽消费必须留着**——
                    // 没有它，老用户在行上横滑（原右滑肌肉记忆）超过 500ms 会被 combinedClickable
                    // 判成长按、凭空弹菜单（plan36b 实测两连发）。这里只消费、不响应。
                    if (!inTrash && !selectionMode) m.pointerInput(note.id) {
                        detectHorizontalDragGestures { change, _ -> change.consume() }
                    } else m
                }
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = if (!inTrash && !selectionMode) {
                        {
                            haptics.perform(HapticEngine.Kind.Rigid)
                            menuOpen = true
                        }
                    } else null
                )
                .padding(14.dp)
        ) {
            // 行首引导位：批量模式=勾选框，平时=类型图标；进出用水平展开/收起 + 快淡入淡出，
            // 整行文字位置随宽度动画平滑让位（原来 if/else 硬切会把标题顶跳一下）
            AnimatedVisibility(
                visible = selectionMode,
                enter = expandHorizontally(Motion.standardSpatial()) + fadeIn(Motion.quick()),
                exit = shrinkHorizontally(Motion.standardSpatial()) + fadeOut(Motion.quick()),
                label = "note-row-check"
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Checkbox(checked = selected, onCheckedChange = { onClick() })
                    Spacer(Modifier.width(6.dp))
                }
            }
            // v56.2 类型方章撤除（参考 ColorOS 笔记卡：卡片正文即全部，类型进元信息行文字），
            // 批量模式的勾选框引导位保留
            Column(Modifier.weight(1f)) {
                // v69e（2026-11-21 用户参考卡片）：标题/预览在左、右上角**叠放两枚缩略卡**
                // （下方那枚略斜、带白描边），日期沉到卡片底左、⋮ 在底右 —— 与参考图同一版式。
                Row {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (note.pinned) {
                                Icon(
                                    Icons.Rounded.PushPin, "已置顶",
                                    modifier = Modifier.size(13.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                                Spacer(Modifier.width(4.dp))
                            }
                            Text(
                                // v53.8：标题也可能带标记（标题取首行，首行本来就可能写 `#`/`[[ ]]`），
                                // 列表显示洗一遍；库里仍是原文（链接按原标题解析，不受显示影响）
                                NotePreview.stripLine(note.title).ifBlank { "无标题" },
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                        }
                        // v53.8 正文预览：跳掉与标题重复的首行、洗掉 #/[[ ]]/| 这些标记
                        // 2026-11-07 P0-2：预览吃查询算好的 240 字投影
                        val preview = remember(note.preview, note.title) { NotePreview.flatten(note.preview, note.title, 80) }
                        if (note.kind == "pdf") {
                            Text(
                                "PDF 文档",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        } else if (preview.isNotBlank()) {
                            Text(
                                preview,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp)
                            )
                        }
                    }
                    // 右上角叠放缩略卡：副卡先画（左/下、略斜），主卡压在上面
                    NoteThumbStack(
                        style = bookStyle,
                        kind = note.kind,
                        modifier = Modifier.padding(start = 12.dp, top = 2.dp)
                    )
                }
                // 底部：日期（第三档明暗，参考图里日期比预览更暗）+ ⋮
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .padding(top = 14.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        (when (note.kind) {
                            "pdf" -> "PDF · "
                            "freeform" -> "手写 · "
                            else -> ""
                        }) + if (inTrash) "已删除" else
                            java.text.SimpleDateFormat("yyyy/M/d HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(note.updatedAt)) + " 更新",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                }
            }
        }
        // v69e：⋮ 钉在**卡片右下角**（参考图那一颗）——放在内容列里会被右上角缩略卡挤窄，
        // 不贴角；挪到卡片 Box 上用 BottomEnd 叠放，与日期同一行高。
        if (!inTrash && !selectionMode) {
            IconButton(
                onClick = {
                    haptics.perform(HapticEngine.Kind.Rigid)
                    menuOpen = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = 5.dp, end = 5.dp)
                    .size(28.dp)
            ) {
                Icon(
                    Icons.Rounded.MoreVert,
                    contentDescription = "更多操作",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
        // 长按菜单：替代原「右滑露出三图标」。锚在行 Box 上，跟随行的位置弹出。
        androidx.compose.material3.DropdownMenu(
            expanded = menuOpen,
            onDismissRequest = { menuOpen = false }
        ) {
            androidx.compose.material3.DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                text = { Text(if (note.pinned) "取消置顶" else "置顶") },
                leadingIcon = {
                    Icon(
                        Icons.Rounded.PushPin, null, Modifier.size(17.dp),
                        tint = if (note.pinned) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                },
                onClick = { menuOpen = false; onPin() }
            )
            androidx.compose.material3.DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                text = { Text("移动到笔记本") },
                leadingIcon = { Icon(Icons.Rounded.FolderOpen, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { menuOpen = false; onMove() }
            )
            androidx.compose.material3.DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                text = { Text("删除") },
                // 2026-10-25：中性色，红色只留给「确认删除」那一步
                leadingIcon = { Icon(Icons.Rounded.Delete, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { menuOpen = false; onDelete() }
            )
            androidx.compose.material3.DropdownMenuItem(
                modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                text = { Text("多选") },
                leadingIcon = { Icon(Icons.Rounded.Check, null, Modifier.size(17.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                onClick = { menuOpen = false; onEnterSelection() }
            )
        }
    }
}

/** 移动笔记到笔记本（单条/批量共用） */
@Composable
private fun MoveNotesDialog(
    notebookId: Long,
    onDismiss: () -> Unit,
    onMove: (Long) -> Unit
) {
    var books by remember { mutableStateOf<List<NotebookEntity>>(emptyList()) }
    LaunchedEffect(Unit) { books = Graph.notes.notebooks() }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.motionEnter(),
        title = { Text("移动到笔记本") },
        text = {
            Column {
                books.forEach { b ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onMove(b.id) }
                            .padding(horizontal = 8.dp, vertical = 10.dp)
                    ) {
                        Text("📕 ", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            b.name + if (b.id == notebookId) "（当前）" else "",
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

// ==================== Markdown 编辑器 ====================

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: Long,
    onDone: () -> Unit,
    onOpenNote: (Long, String) -> Unit = { _, _ -> },
    /** v53.9 `[[笔记#小节]]` 带来的锚点：打开后把光标停在该小节标题行 */
    initialAnchor: String? = null,
    onAnchorConsumed: () -> Unit = {},
    /**
     * v57 平板双栏：true = 由父页（NoteListPage 宽屏分支）的右栏**直接承载**——
     * 父页自备返回语义，本页不再自包全屏 Dialog。手机路径恒 false，行为零变化。
     */
    embedded: Boolean = false
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var note by remember { mutableStateOf<NoteEntity?>(null) }
    var md by remember { mutableStateOf(TextFieldValue("")) }
    // v49 知识库：标签编辑（AI 侧已有 tags 参数，这里补人的入口）
    var showTagDialog by remember { mutableStateOf(false) }
    var tagDraft by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf(false) }
    var showVoice by remember { mutableStateOf(false) }
    var audios by remember { mutableStateOf<List<NoteAudio>>(emptyList()) }
    var recording by remember { mutableStateOf(false) }
    var recordHint by remember { mutableStateOf<String?>(null) }
    // v54.3 导出 .md 的结果提示
    var exportMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(exportMsg) {
        if (exportMsg != null) {
            kotlinx.coroutines.delay(3500)
            exportMsg = null
        }
    }
    var recorder by remember { mutableStateOf<MediaRecorder?>(null) }
    var recordStartAt by remember { mutableStateOf(0L) }
    var recordPath by remember { mutableStateOf<String?>(null) }
    // 岛上二级菜单
    var showFormatMenu by remember { mutableStateOf(false) }
    var showVoiceMenu by remember { mutableStateOf(false) }
    // 2026-11-04 用户笔记本三条建议：撤回 / 常驻 Markdown 提示 / 自动书签（大纲）
    val undoStack = remember(noteId) { androidx.compose.runtime.mutableStateListOf<TextFieldValue>() }
    val redoStack = remember(noteId) { androidx.compose.runtime.mutableStateListOf<TextFieldValue>() }
    var lastEditAt by remember(noteId) { androidx.compose.runtime.mutableLongStateOf(0L) }
    var hintOpen by remember { mutableStateOf(true) }
    var showOutline by remember { mutableStateOf(false) }
    // v49：保存后自增，触发反链重查（链接图在 saveNote 里重建）
    var backlinksRefresh by remember(noteId) { mutableStateOf(0) }
    val focusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // 大纲：按 #/##/###/#### 行解析。空格可有可无——「#标题」是国人最常写的写法，
    // 严格 Markdown 要求 # 后带空格，但大纲是给自己导航用的，宁松勿严。
    val headings = remember(md.text) {
        Regex("(?m)^(#{1,4})\\s*(\\S.*)$").findAll(md.text).map { m ->
            Triple(m.range.first, m.groupValues[1].length, m.groupValues[2].trim())
        }.toList()
    }
    // v49 知识库：`[[` 笔记链接补全（光标在未闭合的 [[ 里才弹）
    val linkQuery = remember(md.text, md.selection.start) { linkQueryAt(md.text, md.selection.start) }
    // 2026-11-05 用户「笔记一次输入太多，内容被菜单胶囊挡住」：胶囊是悬浮的，
    // 正文铺到底就会钻到它下面。这里量出胶囊实际高度（提示条展开/收起、预览态各不同），
    // 用它给编辑区底部留出空档——写死一个 dp 值迟早又对不上。
    var islandH by remember { mutableStateOf(0) }
    var linkCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(linkQuery?.query) {
        val q = linkQuery
        if (q == null) {
            linkCandidates = emptyList()
            return@LaunchedEffect
        }
        // 2026-11-07 P0-1：补全改 SQL 查询（原来每键 allNotes() 全量读正文再内存过滤；
        // 标题子串口径不变，LIKE 对 ASCII 不分大小写与原 ignoreCase 等价）
        linkCandidates = Graph.notes.linkTitleCandidates(noteId, q.query)
    }
    // v49：反链（谁 [[链接]] 到本篇）
    var backlinks by remember(noteId) { mutableStateOf<List<NoteEntity>>(emptyList()) }
    // v53.6：出链（本篇到谁）+ 悬空目标 + 提及但未链接，随保存一起刷新（链接图在 saveNote 里重建）
    var outNotes by remember(noteId) { mutableStateOf<List<NoteEntity>>(emptyList()) }
    var outDangling by remember(noteId) { mutableStateOf<List<String>>(emptyList()) }
    var mentions by remember(noteId) { mutableStateOf<List<NoteEntity>>(emptyList()) }
    LaunchedEffect(noteId, backlinksRefresh) {
        withContext(Dispatchers.IO) {
            backlinks = Graph.notes.backlinks(noteId)
            val (o, d) = Graph.notes.outLinks(noteId)
            outNotes = o
            outDangling = d
            mentions = Graph.notes.unlinkedMentions(noteId)
        }
    }
    // v53.6 链接可走：正文 [[X]] 的目标解析（标题 → 笔记 id）。只在**预览态**解析——
    // 编辑态那是原文，不该出现胶囊；预览每开一次按当前草稿重解，刚打的链接也能立刻走。
    // 查不到的目标留在 map 外 = 悬空，点击走「创建」。
    var wikiMap by remember(noteId) { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var createLinkedTitle by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(preview, md.text) {
        if (!preview) return@LaunchedEffect
        val refs = WikiLinks.parse(md.text)
        wikiMap = if (refs.isEmpty()) emptyMap() else withContext(Dispatchers.IO) {
            val m = HashMap<String, Long>()
            refs.forEach { r ->
                Graph.db.noteKbDao().byTitle(r.title)?.let { if (it.id != noteId) m[r.title.lowercase()] = it.id }
            }
            m
        }
    }

    LaunchedEffect(noteId) {
        note = Graph.notes.noteById(noteId)
        md = TextFieldValue(note?.content ?: "")
        audios = Graph.db.noteAudioDao().ofNote(noteId)
    }

    // v53.9：`[[笔记#小节]]` 点进来 → 光标停在该小节标题行（编辑器的滚动跟随光标，天然到位）。
    // 等 note 真加载完再跳：过早设置 selection 会被上面的 `md = TextFieldValue(...)` 覆盖掉。
    LaunchedEffect(noteId, initialAnchor, note?.id) {
        val a = initialAnchor?.takeIf { it.isNotBlank() } ?: return@LaunchedEffect
        if (note == null) return@LaunchedEffect
        val off = NoteAnchor.findOffset(md.text, a) ?: run { onAnchorConsumed(); return@LaunchedEffect }
        preview = false
        md = md.copy(selection = androidx.compose.ui.text.TextRange(off))
        onAnchorConsumed()
        focusRequester.requestFocus()
    }

    fun reloadAudios() {
        scope.launch(Dispatchers.IO) {
            val list = Graph.db.noteAudioDao().ofNote(noteId)
            withContext(Dispatchers.Main) { audios = list }
        }
    }

    fun startRecording() {
        if (note == null) {
            recordHint = "笔记尚未就绪，稍后再试"; return
        }
        val dir = File(context.filesDir, "note_audio").apply { mkdirs() }
        val out = File(dir, "note_${noteId}_${System.currentTimeMillis()}.m4a")
        val rec = if (android.os.Build.VERSION.SDK_INT >= 31) MediaRecorder(context)
        else @Suppress("DEPRECATION") MediaRecorder()
        runCatching {
            rec.setAudioSource(MediaRecorder.AudioSource.MIC)
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            rec.setOutputFile(out.absolutePath)
            rec.prepare()
            rec.start()
        }.onSuccess {
            recorder = rec
            recording = true
            recordStartAt = System.currentTimeMillis()
            recordPath = out.absolutePath
            recordHint = "录音中…点「停止」保存"
        }.onFailure {
            rec.release()
            recordHint = "无法开始录音：${it.message}"
        }
    }

    fun stopRecording() {
        val rec = recorder ?: return
        recorder = null
        recording = false
        val path = recordPath
        runCatching { rec.stop() }
        rec.release()
        if (path != null) {
            val dur = (System.currentTimeMillis() - recordStartAt).toInt()
            scope.launch(Dispatchers.IO) {
                runCatching {
                    Graph.db.noteAudioDao().insert(
                        NoteAudio(noteId = noteId, path = path, durMs = dur, createdAt = AppTime.nowMillis())
                    )
                }
                reloadAudios()
            }
        }
        recordHint = null
    }

    // 2026-11-04 用户「新建笔记输入文字后退出，列表还显示无内容」：
    // 原来保存挂在 rememberCoroutineScope 上、launch 完立刻 onDone()——编辑器退出组合时
    // scope 被取消，保存可能整条丢失，而退出又是**唯一**保存点。
    // 现在两件事分开：①改输入即防抖自动保存（进程被杀也只丢最后 800ms）；
    // ②退出保存走 Graph.appScope（应用级，组合销毁取消不到）。
    fun saveDraft() {
        val n = note ?: return
        val text = md.text
        val title = firstLineTitle(text)
        if (text == n.content && title == n.title) return
        val snapshot = n.copy(content = text, title = title)
        note = snapshot // 本地基准同步：退出与自动保存不会重复写同一份
        Graph.appScope.launch { runCatching { Graph.notes.saveNote(snapshot) } }
        // saveNote 内会重建链接图：把反链拉新的
        backlinksRefresh++
    }

    fun exitEditor() {
        if (recording) stopRecording()
        saveDraft()
        onDone()
    }

    /**
     * 文本编辑入口：带撤回栈（2026-11-04 用户建议「笔记编辑界面增加撤回按钮」）。
     * 600ms 内的连续输入合并成一步——一个字一个字撤回没人受得了；
     * 栈上限 200 步，防长笔记把内存吃满。
     */
    fun editText(nv: TextFieldValue) {
        val now = System.currentTimeMillis()
        if (undoStack.isEmpty() || now - lastEditAt > 600) {
            undoStack.add(md)
            if (undoStack.size > 200) undoStack.removeAt(0)
        }
        lastEditAt = now
        redoStack.clear()
        md = nv
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(md)
        md = undoStack.removeAt(undoStack.lastIndex)
        lastEditAt = 0
    }

    LaunchedEffect(noteId, md.text) {
        val n = note ?: return@LaunchedEffect
        if (md.text == n.content) return@LaunchedEffect
        kotlinx.coroutines.delay(800)
        saveDraft()
    }

    // 非一级界面：隐藏底部导航栏。v69b：embedded（平板两板的主框承载）时**不锁**——
    // 编辑器是壳层固定框里的内容（与「外观钻入」同形态），Rail 保持可见、任何深度可切页。
    if (!embedded) io.github.m0use001.planability.core.LockShellChrome()

    // Forward：列表→编辑器是层级导航，进场走水平 1/4 滑+淡（与 网格→列表 同一方向语义）
    // v57 平板双栏：本体抽成 editorBody——手机自包 PredictiveBackOverlay（原路径零变化），
    // 平板由父页右栏直接承载（embedded=true，父页决定返回语义）。
    val editorBody: @Composable () -> Unit = {
        // 岛上「返回并保存」走外壳的程序化关闭（180ms 收缩动画后再 onBack），与手势关闭同手感；
        // 必须在 overlay 内容里读，外壳外读到的还是空实现
        val overlayClose = LocalOverlayClose.current
        val editorHaptics = LocalHaptics.current
        // v56.2 编辑器标题栏：返回（并保存）从底部胶囊岛移上来；标题随正文首行实时洗一遍
        //（首行往往就是标题，写 `# 标题` 也由 stripLine 洗掉标记）
        val editorTitle = remember(md.text) {
            NotePreview.stripLine(md.text.lineSequence().firstOrNull { it.isNotBlank() } ?: "").ifBlank { "无标题" }
        }
        // v69c「框不完整」根修：embedded（两板主框承载）时**不铺不透明 surface 底**——
        // surface(244,251,246) 与板底 surfaceContainerLow(230,240,232) 是两种颜色，
        // 编辑器整块盖上去后主板只剩顶部一窄条板色，用户看到的就是「框不完整」。
        // 去底后板底由壳层固定框透出，圆角/整板与副框同材质。
        Box(Modifier.fillMaxSize().then(if (embedded) Modifier else Modifier.background(MaterialTheme.colorScheme.surface))) {
            Column(Modifier.fillMaxSize()) {
            io.github.m0use001.planability.ui.common.M3ETitleBar(
                title = editorTitle,
                collapsedFraction = 1f,
                expandable = false,
                // v64：内嵌在右板里时用平铺形态（无底圆角/无投影），否则板内又浮起一张圆角栏
                flat = embedded,
                navigationIcon = {
                    IconButton(onClick = {
                        editorHaptics.perform(HapticEngine.Kind.Tap)
                        // v69c「返回不了」根修：embedded（平板两板主框承载）时没有外层
                        // PredictiveBackOverlay，LocalOverlayClose 是空实现（点了没反应）。
                        // embedded 走 exitEditor（保存草稿 + onDone → 回概览），窄屏照旧走外壳关闭。
                        if (embedded) exitEditor() else overlayClose()
                    }) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "返回并保存")
                    }
                }
            )
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            // 编辑 ⇄ 预览：**硬切**（2026-10-25 用户：横切类界面切换时重叠）。
            // 整屏两套内容做位移交接时中间帧文字会叠在一起；编辑/预览没有共享元素，硬切最干净。
            if (preview) {
                Column(
                    Modifier
                        .fillMaxHeight()
                        .adaptiveColumn() // v57 平板：预览正文收进 840dp 居中，手机天然 no-op
                        // v56.2 状态栏 inset 由标题栏自含，这里不再叠一层
                        .verticalScroll(rememberScrollState())
                        .padding(top = 16.dp, start = 20.dp, end = 20.dp, bottom = 40.dp)
                ) {
                    CompositionLocalProvider(
                        LocalWikiLinks provides WikiLinkCtx(
                            byTitle = wikiMap,
                            // 命中：先落盘当前草稿再切（与反链行同一口径，退出唯一保存点的老坑）；
                            // v53.9 带 `#锚点` 时一并交出去，由外层记下"打开哪篇的哪一节"
                            onOpen = { id, anchor ->
                                saveDraft()
                                onOpenNote(id, anchor)
                            },
                            onCreate = { t -> createLinkedTitle = t }
                        )
                    ) { MarkdownView(md.text) }
                    // v49 知识库：标签（有才显示；编辑入口在岛上「格式菜单 → 🏷 标签」）
                    val tags = note?.tags.orEmpty()
                    if (tags.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            tags.split(',').filter { it.isNotBlank() }.forEach { t ->
                                androidx.compose.material3.SuggestionChip(
                                    onClick = {},
                                    label = { Text(t, style = MaterialTheme.typography.labelMedium) }
                                )
                            }
                        }
                    }
                    // v49 知识库：反链（谁 [[链接]] 到本篇）。预览里显示；编辑区不放，
                    // 免得和键盘上的补全条抢位置。点一行直接跳过去（先落盘再切）。
                    if (backlinks.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            "链接到本篇（" + backlinks.size + "）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                        )
                        backlinks.forEach { b ->
                            Text(
                                "《" + b.title + "》",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        saveDraft()
                                        onOpenNote(b.id, "")
                                    }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                    // v53.6 第 1 期 c：出链（本篇 [[链接]] 到谁）+ 悬空目标。
                    // 悬空那几行是**可操作**的入口：点一下就走「创建」（与正文胶囊同一落点），
                    // 否则用户只能回正文里一个个找、一个个点。
                    if (outNotes.isNotEmpty() || outDangling.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            "本篇链接到（" + (outNotes.size + outDangling.size) + "）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 6.dp)
                        )
                        outNotes.forEach { o ->
                            Text(
                                "《" + o.title + "》",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { saveDraft(); onOpenNote(o.id, "") }
                                    .padding(vertical = 6.dp)
                            )
                        }
                        outDangling.distinct().forEach { t ->
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { createLinkedTitle = t }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    "《" + t + "》",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "  · 未创建，点此新建",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                    // v53.6 第 1 期 b：提及但未链接（写了名字没连线）——只读导航；
                    // 加链建议是第 2 期「审阅队列」的事，这里不擅自改别人的笔记。
                    if (mentions.isNotEmpty()) {
                        Spacer(Modifier.height(18.dp))
                        androidx.compose.material3.HorizontalDivider(
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                        Text(
                            "提及了本篇（" + mentions.size + "）",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 12.dp, bottom = 2.dp)
                        )
                        Text(
                            "这些笔记写了本篇标题，但没加 [[链接]]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                        mentions.forEach { m ->
                            Text(
                                "《" + m.title + "》",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { saveDraft(); onOpenNote(m.id, "") }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                    if (audios.isNotEmpty()) {
                        Spacer(Modifier.height(14.dp))
                        Text(
                            "笔记录音",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold
                        )
                        audios.forEach { a ->
                            NoteAudioRow(a, onDelete = {
                                scope.launch(Dispatchers.IO) {
                                    Graph.db.noteAudioDao().delete(a.id)
                                    File(a.path).delete()
                                    reloadAudios()
                                }
                            })
                        }
                    }
                }
            } else {
                // 无边框编辑区：文字流直达屏幕底部，胶囊岛悬浮在文字之上（真悬浮）
                // v54.2：标记弱化色要先在 composable 上下文取到，再进 remember（不能在里面读主题）
                val markerColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
                val markerTransform = remember(markerColor) { MarkdownMarkerTransform(markerColor) }
                androidx.compose.material3.OutlinedTextField(
                    value = md,
                    onValueChange = { editText(it) },
                    modifier = Modifier
                        .fillMaxSize()
                        // v56.2 状态栏 inset 由标题栏自含（原来这里 statusBarsPadding）
                        // 2026-11-02 键盘筛查：编辑大框原无 ime 避让，键盘盖住光标/末尾
                        // 文本且无法滚回（fillMaxSize 铺到底、无滚动救回手段）
                        .imePadding()
                        // 大纲跳转：把光标移到标题行首，TextField 会自己滚过去
                        .focusRequester(focusRequester)
                        // 底部按胶囊实测高度留空——否则长文最后几行被胶囊盖住（用户 2026-11-05）
                        .padding(
                            start = 14.dp, end = 14.dp, top = 8.dp,
                            bottom = androidx.compose.ui.platform.LocalDensity.current.run { islandH.toDp() } + 16.dp
                        ),
                    // v54.2：等宽 14sp 看着像代码编辑器；正文该用正文字体——中文等宽尤其难读。
                    // 15sp/24 行高（比预览略小，一屏能多看几行）。
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Default,
                        fontSize = 15.sp,
                        lineHeight = 24.sp
                    ),
                    // v54.2：只给标记弱化着色（#、-、>、**、`、[[ ]），不改字、不动光标
                    visualTransformation = markerTransform,
                    placeholder = { Text("# 标题\n\n正文支持 Markdown：**加粗**、*斜体*、- 列表、> 引用、``` 代码块") },
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = MaterialTheme.colorScheme.primary
                    )
                )
            }

            // v49 知识库：`[[` 补全条——贴着键盘上沿，点选一次把 [[前缀 补成 [[标题]]
            val lq = linkQuery
            if (!preview && lq != null && linkCandidates.isNotEmpty()) {
                LinkSuggestBar(
                    items = linkCandidates,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .imePadding()
                        // 补全条压在胶囊上方，不跟胶囊抢位置
                        .padding(bottom = androidx.compose.ui.platform.LocalDensity.current.run { islandH.toDp() })
                ) { title ->
                    val start = lq.start
                    val end = md.selection.start.coerceAtMost(md.text.length)
                    val newText = md.text.substring(0, start) + "[[" + title + "]]" + md.text.substring(end)
                    editText(
                        TextFieldValue(newText, TextRange((start + title.length + 4).coerceAtMost(newText.length)))
                    )
                }
            }

            // v49 知识库：标签编辑（标签进笔记库，kb_ask / note_search 的 tag 参数按它过滤）
            if (showTagDialog) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { showTagDialog = false },
                    modifier = Modifier.motionEnter(),
                    title = { Text("笔记标签") },
                    text = {
                        Column {
                            androidx.compose.material3.OutlinedTextField(
                                value = tagDraft,
                                onValueChange = { tagDraft = it },
                                singleLine = true,
                                label = { Text("逗号分隔，如：微积分,期末") }
                            )
                            Text(
                                "标签用于知识库检索过滤（AI 的 tag 参数）",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                        }
                    },
                    confirmButton = {
                        TextButton(onClick = {
                            val n = note
                            if (n != null) {
                                val snapshot = n.copy(
                                    tags = io.github.m0use001.planability.data.NotesRepo.normalizeTags(tagDraft)
                                )
                                note = snapshot
                                Graph.appScope.launch { runCatching { Graph.notes.saveNote(snapshot) } }
                            }
                            showTagDialog = false
                        }) { Text("保存") }
                    },
                    dismissButton = { TextButton(onClick = { showTagDialog = false }) { Text("取消") } }
                )
            }

            // v53.6 链接可走：点了悬空链接 → 确认创建同名笔记（Logseq `<open-page-ref` 的做法）
            createLinkedTitle?.let { target ->
                ConfirmDialog(
                    title = "创建笔记",
                    text = "《$target》还不存在。现在创建并打开它？",
                    confirmText = "创建",
                    danger = false,
                    onConfirm = {
                        createLinkedTitle = null
                        val bookId = note?.notebookId ?: return@ConfirmDialog
                        scope.launch(Dispatchers.IO) {
                            // 内容种上 `# 标题`：saveDraft 按首行算标题，空内容存一次会把标题清成 ""，
                            // 那样刚建的链接目标立刻又变悬空。
                            val id = Graph.notes.createNote(bookId, target, "md")
                            Graph.notes.noteById(id)?.let { Graph.notes.saveNote(it.copy(content = "# $target\n\n")) }
                            withContext(Dispatchers.Main) {
                                saveDraft()
                                onOpenNote(id, "")
                            }
                        }
                    },
                    onDismiss = { createLinkedTitle = null }
                )
            }

            // 录音状态浮条：从顶部轻滑入 + 快淡入（原来凭空出现）
            androidx.compose.animation.AnimatedVisibility(
                visible = recordHint != null,
                enter = slideInVertically(Motion.standardSpatial()) { -it } + fadeIn(Motion.quick()),
                exit = slideOutVertically(Motion.standardSpatial()) { -it } + fadeOut(Motion.quick()),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp),
                label = "record-hint"
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        recordHint ?: "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            // v54.3 导出结果提示：3.5 秒自动消失（成功给路径，失败给原因）
            androidx.compose.animation.AnimatedVisibility(
                visible = exportMsg != null,
                enter = slideInVertically(Motion.standardSpatial()) { -it } + fadeIn(Motion.quick()),
                exit = slideOutVertically(Motion.standardSpatial()) { -it } + fadeOut(Motion.quick()),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 6.dp),
                label = "export-hint"
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(
                        exportMsg ?: "", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                    )
                }
            }

            }
            }

            // ---------- 悬浮胶囊岛（固定底部；编辑时随键盘上浮充当格式栏） ----------
            // 2026-11-04：+撤回键；上方多一条常驻 Markdown 速查（可收）。
            // v56.2 返回（并保存）已移到标题栏，岛上是 撤回 / 格式▾ / 语音▾ / 预览
            Box(
                contentAlignment = Alignment.BottomCenter,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .imePadding()
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    // 量高度给正文底部留空（见 islandH）
                    .onGloballyPositioned { islandH = it.size.height }
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                // v57 批 1（A 类）：速查条显隐从 if 硬切改 Motion.expandIn/collapseOut——
                // 硬切时下方的岛被瞬间顶开/落下；弹簧让高度逐帧过渡，岛是被「推」开的。
                androidx.compose.animation.AnimatedVisibility(
                    visible = !preview && hintOpen,
                    enter = Motion.expandIn,
                    exit = Motion.collapseOut
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MarkdownHintBar { hintOpen = false }
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Surface(
                    shape = RoundedCornerShape(percent = 50),
                    shadowElevation = 8.dp,
                    color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.96f),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        // v56.2 返回（并保存）已移到标题栏 navigationIcon
                        // 撤回：600ms 内连续输入合并为一步；没得撤时压暗（点了不响应）
                        IslandButton(
                            Icons.AutoMirrored.Rounded.Undo, "撤回",
                            tint = if (undoStack.isEmpty()) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                            else MaterialTheme.colorScheme.onSurface
                        ) { if (undoStack.isNotEmpty()) undo() }
                        // 预览态收起 格式/语音 两键：水平收展 + 快淡入淡出，胶囊宽度随之流动
                        AnimatedVisibility(
                            visible = !preview,
                            enter = expandHorizontally(Motion.standardSpatial()) + fadeIn(Motion.quick()),
                            exit = shrinkHorizontally(Motion.standardSpatial()) + fadeOut(Motion.quick()),
                            label = "editor-island-tools"
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                // 格式▾：二级菜单列出全部 Markdown 格式动作
                                Box {
                                IslandButton(Icons.Rounded.TextFields, "格式菜单") { showFormatMenu = true }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showFormatMenu,
                                    onDismissRequest = { showFormatMenu = false },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                ) {
                                    // 格式动作也走 editText：撤回栈要能撤回一次加粗/前缀
                                    (mdActions(md) { editText(it) } +
                                        listOf(
                                            "📑 大纲 · 自动书签" to { showOutline = true },
                                            "🏷 标签" to {
                                                tagDraft = note?.tags.orEmpty()
                                                showTagDialog = true
                                            },
                                            // v54.3：导出成 .md 落到「下载/Planability/notes」——
                                            // 拔掉数据线就能用别的编辑器打开、也能带走
                                            "📤 导出 .md" to {
                                                note?.let { n ->
                                                    // 先落盘再导出，否则导出的是旧内容
                                                    saveDraft()
                                                    scope.launch(Dispatchers.IO) {
                                                        val snapshot = Graph.notes.noteById(n.id) ?: n
                                                        val path = NoteExport.exportToDownloads(context, snapshot)
                                                        withContext(Dispatchers.Main) {
                                                            exportMsg = if (path != null) "已导出到 $path"
                                                            else "导出失败（存储权限或空间不足）"
                                                        }
                                                    }
                                                }
                                            }
                                        ))
                                        .forEach { (label, action) ->
                                        androidx.compose.material3.DropdownMenuItem(
                                            modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                            text = { Text(label, style = MaterialTheme.typography.bodyMedium) },
                                            onClick = {
                                                action()
                                                showFormatMenu = false
                                            }
                                        )
                                    }
                                }
                            }
                            // 语音▾：录音 + 语音转文字合并
                            Box {
                                IslandButton(
                                    if (recording) Icons.Rounded.Stop else Icons.Rounded.Mic,
                                    if (recording) "停止录音" else "语音菜单",
                                    tint = if (recording) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface
                                ) { showVoiceMenu = true }
                                androidx.compose.material3.DropdownMenu(
                                    expanded = showVoiceMenu,
                                    onDismissRequest = { showVoiceMenu = false },
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainer)
                                ) {
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = {
                                            Text(
                                                if (recording) "⏹ 停止录音" else "🎙 开始录音",
                                                style = MaterialTheme.typography.bodyMedium
                                            )
                                        },
                                        onClick = {
                                            showVoiceMenu = false
                                            if (recording) stopRecording() else startRecording()
                                        }
                                    )
                                    androidx.compose.material3.DropdownMenuItem(
                                        modifier = Modifier.clip(RoundedCornerShape(10.dp)), // 2026-11-15 点击高亮圆角化（原来是整行直角方块）
                                        text = { Text("〰 语音转文字", style = MaterialTheme.typography.bodyMedium) },
                                        onClick = {
                                            showVoiceMenu = false
                                            showVoice = true
                                        }
                                    )
                                }
                            }
                            }
                        }
                        // R11：浮动岛里的切换按钮与相邻的麦克风同为中性（默认 onSurface）
                        IslandButton(
                            if (preview) Icons.Rounded.Edit else Icons.Rounded.Visibility,
                            if (preview) "回到编辑" else "预览"
                        ) { preview = !preview }
                    }
                }
                }
            }
        }
    }

    if (showVoice) {
        io.github.m0use001.planability.ui.voice.VoiceInputSheet(title = "语音转文字") { text ->
            showVoice = false
            if (text.isNotBlank()) {
                val append = (if (md.text.isBlank()) "" else "\n\n") + text
                md = TextFieldValue(md.text + append, TextRange(md.text.length + append.length))
            }
        }
    }

    // 大纲 · 自动书签（2026-11-04 用户笔记本建议）：点标题把光标挪到该行，
    // TextField 会自己滚到光标位置——不用自己算行高。
    if (showOutline) {
        androidx.compose.material3.ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() }, onDismissRequest = { showOutline = false }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
            ) {
                Text(
                    "大纲 · 自动书签",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    if (headings.isEmpty()) "还没有标题——用 # / ## / ### 开一行就有大纲了"
                    else "点标题跳到那一节（共 ${headings.size} 节）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier.fillMaxWidth().heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    headings.forEach { (offset, level, title) ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .clickable {
                                    md = md.copy(selection = TextRange(offset))
                                    showOutline = false
                                    focusRequester.requestFocus()
                                }
                                .padding(vertical = 10.dp, horizontal = ((level - 1) * 14).dp)
                        ) {
                            Text(
                                title,
                                style = if (level == 1) MaterialTheme.typography.bodyLarge
                                else MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
    if (embedded) {
        editorBody()
    } else {
        PredictiveBackOverlay(onBack = { exitEditor() }, enter = OverlayEnter.Forward) { editorBody() }
    }
}

/**
 * 常驻 Markdown 速查条（2026-11-04 用户建议）：placeholder 一打字就没了，
 * 常用语法要有个不消失的地方看；right 的 ✕ 收起（本次编辑内有效）。
 */
@Composable
private fun MarkdownHintBar(onClose: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(percent = 50),
        color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = 0.9f)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp, end = 2.dp, top = 1.dp, bottom = 1.dp)
        ) {
            Text(
                "# 标题 · **粗** · *斜* · - 列表 · > 引用 · `代码` · \$\$公式\$\$",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
            IconButton(onClick = onClose, modifier = Modifier.size(22.dp)) {
                Icon(
                    Icons.Rounded.Close, "收起提示",
                    Modifier.size(13.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** Markdown 格式动作集（v12：供岛上「格式」二级菜单使用；包裹类作用于选区，前缀类作用于行首） */
private fun mdActions(md: TextFieldValue, onChange: (TextFieldValue) -> Unit): List<Pair<String, () -> Unit>> {
    fun wrap(token: String) {
        val sel = md.selection
        val newText: String
        val newSel: TextRange
        if (!sel.collapsed) {
            val inner = md.text.substring(sel.min, sel.max)
            newText = md.text.substring(0, sel.min) + token + inner + token + md.text.substring(sel.max)
            newSel = TextRange(sel.min + token.length, sel.max + token.length)
        } else {
            newText = md.text.substring(0, sel.min) + token + token + md.text.substring(sel.min)
            newSel = TextRange(sel.min + token.length)
        }
        onChange(TextFieldValue(newText, newSel))
    }
    fun linePrefix(prefix: String) {
        val pos = md.selection.min.coerceIn(0, md.text.length)
        val lineStart = if (pos == 0) 0 else md.text.lastIndexOf('\n', pos - 1) + 1
        val newText = md.text.substring(0, lineStart) + prefix + md.text.substring(lineStart)
        onChange(TextFieldValue(newText, TextRange(pos + prefix.length)))
    }
    fun insert(text: String) {
        val pos = md.selection.min.coerceIn(0, md.text.length)
        val newText = md.text.substring(0, pos) + text + md.text.substring(pos)
        onChange(TextFieldValue(newText, TextRange(pos + text.length)))
    }
    return listOf(
        "一级标题  H1" to { linePrefix("# ") },
        "二级标题  H2" to { linePrefix("## ") },
        "加粗  B" to { wrap("**") },
        "斜体  I" to { wrap("*") },
        "删除线  S" to { wrap("~~") },
        "引用  ❝" to { linePrefix("> ") },
        "行内代码  ‹›" to { wrap("`") },
        "无序列表  •" to { linePrefix("- ") },
        "待办勾选  ☑" to { linePrefix("- [ ] ") },
        "分隔线  —" to { insert("\n---\n") },
        "图表  图" to { insert("\n```chart\nbar\n图表标题\n类别一:10,类别二:30,类别三:20\n```\n") }
    )
}

/** 笔记录音行：MediaPlayer 播放 */
@Composable
private fun NoteAudioRow(item: NoteAudio, onDelete: () -> Unit) {
    var playing by remember { mutableStateOf(false) }
    val player = remember { MediaPlayer() }
    DisposableEffect(Unit) {
        onDispose {
            runCatching { if (player.isPlaying) player.stop() }
            player.release()
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp)
    ) {
        IconButton(onClick = {
            runCatching {
                if (playing) {
                    player.stop()
                    player.reset()
                    playing = false
                } else {
                    player.reset()
                    player.setDataSource(item.path)
                    player.prepare()
                    player.setOnCompletionListener { playing = false }
                    player.start()
                    playing = true
                }
            }
        }) {
            Icon(
                if (playing) Icons.Rounded.Stop else Icons.Rounded.PlayArrow,
                contentDescription = "播放"
            )
        }
        Text(
            "录音 ${item.durMs / 1000} 秒",
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete) {
            Icon(Icons.Rounded.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun firstLineTitle(content: String): String =
    content.lineSequence().firstOrNull { it.isNotBlank() }?.removePrefix("#")?.removePrefix(" ")?.take(30) ?: ""

/** 自研轻量 Markdown 渲染：标题/列表/引用/代码块/表格/粗斜体/分隔线
 *  chatCodeBar=true 时代码块带 复制/导出 工具栏（对话内嵌组件用，笔记页保持原样） */
@Composable
fun MarkdownView(
    md: String,
    chatCodeBar: Boolean = false,
    onExportCode: ((String, String) -> Unit)? = null,
    /**
     * 2026-11-08 聊天页：长按正文就地选词（主题色选区 + 系统复制条）。
     * **关键**：本函数和 MdTextLines 都是「平铺发射多个子组件」的（自己没有布局容器）。
     * 直接包 SelectionContainer 会把所有子组件都摆在 (0,0) 叠着画——真机看到的
     * 「多段文字层层叠印」就是这个。所以容器里必须垫一个 Column。
     * 笔记等其它调用点不传（默认 false，行为与原来完全一致）。
     */
    selectable: Boolean = false
) {
    // 十三期：先按代码围栏分段（代码段不做 LaTeX 解析），文本段再切 $…$ / $$…$$ 公式
    val fenceSegs = remember(md) { splitFenceSegments(md) }
    // 2026-11-14 用户「上下滑动还是卡」组合打点：重型消息 item 一次进视口整包组合，
    // 帧统计 p99 尖刺与之对应。首组合（md 首次出现）时把耗时与特征打出来，
    // 定位大头在文本排版/公式/表格哪一段（SideEffect=组合完成后，含全部子组合）。
    // 2026-11-21 探针修正：每次组合重新取点，量**本次**组合（remember 版会把重组间隔/异步
    // 等待算进来，虚高）。
    val tCompose0 = android.os.SystemClock.elapsedRealtime()
    val mdStats = remember(md) {
        Triple(
            md.lines().count { it.startsWith("|") },
            md.split("$$").size / 2,
            md.length,
        )
    }
    // 2026-11-21 探针改成就地计时（见函数末尾）：SideEffect 要等整帧组合结束才跑，
    // 会把之后所有兄弟节点的耗时一起算进来，数字虚高。这里只保留 mdStats 定义。
    val content: @Composable () -> Unit = content@{
    fenceSegs.forEach { seg ->
        if (seg.code) {
            MdCodeBlock(seg.text, chatCodeBar, onExportCode)
            Spacer(Modifier.height(10.dp))
        } else {
            // 2026-10-27 用户：行内公式（$…$ / \(…\)）原来也被 LatexBlock 独立一行居中，
            // 句中"拖动滑块实时调整 $y=ax^2+bx+c$ 系数"会被拆成三截。只有 display 级
            // （$$…$$ / \[…\]）才独立居中；行内的把定界符拼回文本流，交给
            // MdTextLines→MdInlineText 按 InlineTextContent 嵌进行内（粗体/表格行照常走原链路）
            val buf = StringBuilder()
            // 2026-11-11 掉帧修：公式切分记忆化——重组合（流式每帧、翻动回场）不再重切
            val mathSegs = remember(seg.text) { splitMathSegments(seg.text) }
            mathSegs.forEach { s ->
                when (s) {
                    is MathSeg.Math ->
                        if (s.display) {
                            if (buf.isNotBlank()) { MdTextLines(buf.toString()); buf.clear() }
                            LatexBlock(s.latex)
                            // 2026-11-02：块级公式里 `\text{解}` 的中文被剥成标签，挂公式下方。
                            // 2026-11-10 用户报障：模型把说明写进公式（`\text{不是未定式，}`），
                            // 暗色小字看起来像"普通文字被压成注释"。改成正文色 + bodyMedium，
                            // 仍居中挂在公式下（jlatexmath 画不了 CJK，只能这样并在）。
                            if (s.labels.isNotBlank()) {
                                Text(
                                    s.labels,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                    // 2026-11-17 用户「文字被挤出」：标签行是 \text{} 里剥出来的
                                    // 中文+数字，长度不定。显式 softWrap=true 钉死「按容器宽换行」，
                                    // 不许它在无界宽里按固有宽铺开、两端溢出屏幕被裁。
                                    softWrap = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        } else {
                            buf.append('$').append(s.latex).append('$')
                        }
                    is MathSeg.Txt -> buf.append(s.text)
                }
            }
            if (buf.isNotBlank()) MdTextLines(buf.toString())
        }
    }
    }
    if (selectable) {
        SelectionContainer(Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth()) { content() }
        }
    } else {
        content()
    }
    // 2026-11-21 探针：组合内就地取差值（不含 apply/measure，也不含后续兄弟节点）。
    // 每段都上报（>2ms 由 ScrollTrace 过滤）——单段都不大但同一帧里堆了好几条才是真凶，
    // 只有按帧求和才能看出来（最差帧 99ms 而单段 <10ms 就是这个形态）。
    val dtCompose = android.os.SystemClock.elapsedRealtime() - tCompose0
    io.github.m0use001.planability.core.ScrollTrace.noteSlowCompose(
        dtCompose.toInt(), mdStats.third, mdStats.first, mdStats.second
    )
    if (dtCompose > 25) {
        android.util.Log.w(
            "MdPerf",
            "compose=${dtCompose}ms len=${mdStats.third} tableRows=${mdStats.first} displayMath=${mdStats.second}"
        )
    }
}

/** 数一行里 ``` 出现几次（用来判「单行内联围栏」）。 */
private fun fenceTickCount(line: String): Int {
    var n = 0
    var i = 0
    while (true) {
        val p = line.indexOf("```", i)
        if (p < 0) break
        n++
        i = p + 3
    }
    return n
}
/**
 * 围栏分段：代码段含首尾围栏行（首行带 lang），文本段为其余内容。
 *
 * ## 单行内联围栏（2026-10-12 真机踩过，必须单独认）
 *
 * 模型经常把整个块写成**一行**：```` ```qa {"questions":[...]}``` ````。
 * 旧逻辑只看 `line.startsWith("```")`，于是把这种行当成围栏**开启**，
 * 后面每一行都被 append 进代码段——而这一行后面若还有正文，
 * 那整篇正文都会被渲染成等宽代码框，连正文里的 `**粗体**` 星号都原样露出来。
 *
 * 真机实际样本（chat_messages.id=1158）：模型输出的是
 * ` ```qa {...}``` ` + 空行 + 「**当前状态**：明天日程为空…**先睡觉…**」，
 * 而那份 JSON **本身是坏的**（末尾 `"}]}`，正确应为 `"]}]}` —— options 数组少一个 `]`），
 * 于是 [looseJsonObject] 解析失败、卡片出不来、整段回落进 MarkdownView。
 * 于是在旧的单行内联缺口下，一段**普通的正文**被整段吞成了等宽灰框。
 *
 * 所以这里按「一行里 ``` 出现 >= 2 次」判定单行内联围栏：整行自成一段代码，
 * **不改变 inCode**，后面的行立刻回到正常文本模式。
 * 这样即使 JSON 坏了出不了卡片，最坏也只是那一张卡显示成代码块，
 * 而不是把后面所有正文一起拖下水。
 */
/** 2026-11-14 快速滚动卡顿二段修：跨组合模块级缓存（与 MarkdownMath.splitMathSegments 同策略，
 *  LazyColumn item 滑出即销毁，往复滑动每次回场不再全量重扫） */
private val fenceSegCache = androidx.collection.LruCache<String, List<FenceSeg>>(256)

private fun splitFenceSegments(md: String): List<FenceSeg> {
    fenceSegCache.get(md)?.let { return it }
    val parsed = splitFenceSegmentsUncached(md)
    fenceSegCache.put(md, parsed)
    return parsed
}

private fun splitFenceSegmentsUncached(md: String): List<FenceSeg> {
    val segs = mutableListOf<FenceSeg>()
    val cur = StringBuilder()
    var inCode = false

    fun flushText() {
        segs += FenceSeg(false, cur.toString().trimEnd('\n'))
        cur.clear()
    }

    md.lines().forEach { line ->
        val ticks = fenceTickCount(line)
        when {
            // 单行内联围栏：```` ```lang code``` ```` —— 自成一段，且不影响 inCode
            ticks >= 2 -> {
                flushText()
                segs += FenceSeg(true, line.trim())
            }
            line.startsWith("```") -> {
                if (inCode) {
                    cur.appendLine(line) // 闭合围栏归属代码段
                    segs += FenceSeg(true, cur.toString().trimEnd('\n'))
                    cur.clear()
                } else {
                    flushText()
                    cur.appendLine(line) // 开始围栏归属代码段
                }
                inCode = !inCode
            }
            else -> cur.appendLine(line)
        }
    }
    segs += FenceSeg(inCode, cur.toString().trimEnd('\n'))
    return segs.filter { it.text.isNotBlank() }
}

private data class FenceSeg(val code: Boolean, val text: String)

/** chart 数据行解析："类别:数值"（逗号/中文逗号分隔） */
private fun chartEntries(buf: List<String>): List<Pair<String, Float>> =
    buf.flatMap { it.split(",", "，") }.mapNotNull { seg ->
        val kv = seg.split(":", "：")
        val v = kv.getOrNull(1)?.trim()?.toFloatOrNull()
        if (kv.getOrNull(0)?.isNotBlank() == true && v != null) kv[0].trim() to v else null
    }

/** 代码块（含 chart 图表块 / mindmap 思维导图 / fishbone 鱼骨图协议与 chat 复制/导出工具栏） */
@Composable
private fun MdCodeBlock(raw: String, chatCodeBar: Boolean, onExportCode: ((String, String) -> Unit)?) {
    val lines = raw.trim().lines()
    val head = lines.firstOrNull().orEmpty()
    // 行尾若是收尾围栏（```lang code```）先剥掉，否则 code 会带上三个反引号。
    val headClean = head.trimEnd().let {
        if (it.length > 3 && it.endsWith("```")) it.dropLast(3) else it
    }
    // 语言槽可能是 "chart" 也可以是 "chart line"（类型/标题挤在同一行，模型常这么写）
    val langRaw = headClean.removePrefix("```").trim()
    val lang = langRaw.substringBefore(' ').trim().lowercase()
    val langRest = langRaw.substringAfter(' ', "").trim()
    val rest = lines.drop(1).takeWhile { !it.startsWith("```") }
    // 单行内联写法 ```lang code``` —— 代码就在这一行，不在第二行起。
    // 真机踩过：模型经常把整个块写成一行，旧逻辑从第 2 行取代码 → 代码块渲染成**空的**，
    // 而真正的内容被当成普通文本重复显示一遍。
    val buf = if (langRest.isNotBlank() && rest.isEmpty()) listOf(langRest) else rest
    when (lang) {
        "chart" -> {
            // 两种写法：
            //   A 多行：```chart / line / 标题 / 类别:数值 …
            //     buf = [类型, 标题, 数据…]           ← buf 是 lines.drop(1)，buf[0] 就是类型
            //   B 单行：```chart line 标题 / 类别:数值 …
            //     langRest 非空，buf = [标题, 数据…]
            //
            // 这里原来整体错位一格：类型取 buf[1]（其实是标题）、标题取 buf[2]（其实是第一行数据）、
            // 数据从 drop(3) 开始（吃掉第一个数据点）。真机后果是三件事同时错：
            //   ① line/pie 认不出类型 → 全部退化成柱状图
            //   ② 标题显示成"周一:12"这种数据行
            //   ③ **第一个数据点消失**（7 天只剩 6 根柱子、饼图少了"工作"那一块）
            val chartType = langRest.substringBefore(' ').trim().lowercase()
                .ifBlank { buf.getOrNull(0)?.trim()?.lowercase() ?: "bar" }
            val chartTitle = if (langRest.isBlank()) buf.getOrNull(1)?.trim() ?: "" else langRest.substringAfter(' ').trim()
            val dataStart = if (langRest.isBlank()) 2 else 1
            val dataLines = buf.drop(dataStart)
            // 树状图/旭日图/气泡图的数据是**缩进树或三元组**，不是「类别:数值」，
            // 所以不走 chartEntries，直接把原始行交给各自的解析器。
            val hierType = chartType in setOf("treemap", "树状图", "sunburst", "旭日图")
            if (hierType) {
                // 首行是标题，树形数据要从标题行开始（标题本身当根节点）
                val hierBody = (listOf(chartTitle) + dataLines).joinToString("\n")
                if (chartType.startsWith("sun") || chartType.startsWith("旭")) SunburstBlock(hierBody)
                else TreemapBlock(hierBody)
                return
            }
            if (chartType in setOf("bubble", "气泡图")) {
                BubbleBlock((listOf(chartTitle) + dataLines).joinToString("\n")); return
            }
            val entries = chartEntries(dataLines)
            if (entries.isNotEmpty()) {
                ChartBlock(chartType, chartTitle, entries, LocalChartDefaults.current)
            } else {
                Text(
                    "图表数据为空（格式：类别:数值）",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
            return
        }
        "line", "折线图" -> { ChartBlock("line", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "bar", "柱状图" -> { ChartBlock("bar", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "pie", "饼图" -> { ChartBlock("pie", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "area", "面积图" -> { ChartBlock("area", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "hbar", "条形图" -> { ChartBlock("hbar", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "donut", "圆环图" -> { ChartBlock("donut", langRest, chartEntries(buf), LocalChartDefaults.current); return }
        "bubble", "气泡图" -> { BubbleBlock(buf.joinToString("\n")); return }
        "treemap", "树状图" -> { TreemapBlock(buf.joinToString("\n")); return }
        "sunburst", "旭日图" -> { SunburstBlock(buf.joinToString("\n")); return }
        "mindmap", "思维导图" -> { MindmapBlock(buf.joinToString("\n")); return }
        "fishbone", "鱼骨图" -> { FishboneBlock(buf.joinToString("\n")); return }
        "flow", "flowchart", "流程图" -> { FlowBlock(buf.joinToString("\n")); return }
        "tree", "树形图" -> { TreeBlock(buf.joinToString("\n")); return }
        "scatter", "散点图" -> { ScatterBlock(buf.joinToString("\n")); return }
        "radar", "雷达图" -> { RadarBlock(buf.joinToString("\n")); return }
        "heatmap", "热力图" -> { HeatmapBlock(buf.joinToString("\n")); return }
        "mermaid" -> { MermaidBlock(buf.joinToString("\n")); return }
    }
    val code = buf.joinToString("\n")
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        if (chatCodeBar) {
            val clipboard = LocalClipboardManager.current
            val barCtx = LocalContext.current
            Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                modifier = Modifier.padding(start = 12.dp, end = 4.dp, top = 4.dp)
            ) {
                Text(
                    if (lang.isBlank()) "代码" else lang,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(barCtx, "已复制", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Rounded.ContentCopy, "复制",
                        Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (onExportCode != null) {
                    IconButton(onClick = { onExportCode(lang, code) }, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Rounded.Save, "导出到文件页",
                            Modifier.size(14.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
        // 2026-10-30 用户「加上代码自动涂色」：代码块按语言自动上色（注释/字符串/数字/关键字/注解）。
        // 词法器在 ui/common/CodeHighlight.kt，配色全部取自当前主题的 scheme role，
        // 明暗主题与换配色时自动跟随；未知语言只涂注释/字符串/数字，不猜关键字。
        // remember 的键带上 scheme：切主题时要重涂，否则颜色停在上一个主题的色板上。
        val hlScheme = MaterialTheme.colorScheme
        // 配色方案（外观页可切）：vscode=完全复刻 VS Code；theme=按 VS Code 色相关系从主题主色生成
        val hlStyleKey = io.github.m0use001.planability.data.Graph.settings.snapshot
            .collectAsState().value.theme.codeHlStyle
        val hlStyle = io.github.m0use001.planability.ui.common.CodeHighlight.Style.of(hlStyleKey)
        // 2026-11-21 滚动掉帧根治①续：高亮结果跨组合缓存（键带配色方案身份与样式档，
        // 切主题/换配色自动失效重涂）。
        val hlKey = "$hlStyleKey|${System.identityHashCode(hlScheme)}|$lang|$code"
        val highlighted = codeHlCache.get(hlKey)
            ?: io.github.m0use001.planability.ui.common.CodeHighlight.highlight(code, lang, hlScheme, hlStyle)
                .also { codeHlCache.put(hlKey, it) }
        // 2026-11-06 用户「长文字段（例如代码）自动加左右滑动条，避免意外换行」：
        // softWrap=false 关掉按容器宽的自动换行 + horizontalScroll 左右滑——
        // 比屏窄的代码照常显示不产生滚动，超宽的长行保持原貌可滑（导出/复制的还是原文）。
        // 2026-11-09 用户「组件滑动条与右滑菜单冲突 / 快滑时点到组件卡住+震动」：
        // 代码块没登记 hScrollZone，聊天页根部的右滑抽屉手势会把横向滑代码（以及快滑
        // 指尖带右向漂移的落指）抢走——锁定时 ImpactLight 震动 + consume 全部事件，
        // 看起来就是「点到组件立刻卡住+震动」。登记后抽屉手势在代码块上让位。
        // 2026-11-13 用户「所有长内容的横向滑动条要可见，不然不知道能滑」：加指示条。
        val codeHs = rememberScrollState()
        Text(
            highlighted,
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            softWrap = false,
            modifier = Modifier
                .hScrollZone()
                .hScrollHint(codeHs)
                .horizontalScroll(codeHs)
                .padding(horizontal = 12.dp, vertical = if (chatCodeBar) 8.dp else 12.dp)
        )
    }
}

/** v54.1 排版：任意级标题（`#` 后可无空格——国人不写 `# 标题` 而写 `#标题`） */
private val HEADING_ANY = Regex("""^(#{1,6})\s*\S""")

/** 有序列表行（1. / 1)）：从 MdTextLines 逐行 matches 提出来的常量（掉帧修，2026-11-11） */
private val ORDERED_LIST_LINE = Regex("""\d+[.)]\s.+""")

/**
 * v54.1 预览态标题。
 *
 * H1 = 文档标题（底部一条细分隔线收口，避免和下面的正文糊成一片）；
 * H2 = 小节（左侧主色竖条，给"这一节从哪开始"一个视觉锚点）；
 * H3 及以下只靠字号/字重，层次多了反而吵。
 * 留白遵守「标题前 > 标题后」——这是排版里最便宜、也最容易被忽略的一条。
 */
@Composable
private fun MdHeading(level: Int, text: String) {
    val style = when (level) {
        1 -> MaterialTheme.typography.headlineSmall
        2 -> MaterialTheme.typography.titleLarge
        3 -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    Spacer(Modifier.height(when (level) { 1 -> 20; 2 -> 18; 3 -> 14; else -> 10 }.dp))
    if (level == 2) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(3.dp)
                    .height(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.primary)
            )
            Spacer(Modifier.width(8.dp))
            MdInlineText(text, style = style, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }
    } else {
        MdInlineText(text, style = style, fontWeight = FontWeight.Bold)
    }
    if (level == 1) {
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f)))
    }
    Spacer(Modifier.height(6.dp))
}

/** 文本行渲染：标题/引用/列表（含嵌套缩进）/表格/分隔线/段落（行内公式与富文本由 MdInlineText 处理） */
/**
 * 表格行整理（2026-11-11 用户截图）：模型把一行**硬折行**时，续行被当成新行解析 →
 * 列数错位、单元格里的公式被腰斩（`\sq` / `rt3 $`）。
 * 判据：某行列数少于上一行 = 续行，并回上一行最后一格（空格相连，公式重新配对）。
 */
internal fun mergeTableRows(grid: List<List<String>>): List<List<String>> {
    val merged = ArrayList<List<String>>(grid.size)
    grid.forEach { row ->
        val prev = merged.lastOrNull()
        if (prev != null && row.size < prev.size) {
            val fix = prev.toMutableList()
            fix[fix.size - 1] = (fix.last() + row.joinToString(" ")).trim()
            merged[merged.size - 1] = fix
        } else merged.add(row)
    }
    return merged
}

// 2026-11-17 滚动流畅性 P1：这些 Regex 原来在函数体内/每格 new，每次重组都重新编译。
// 提为模块顶层常量，编译一次全局复用（prejoinSplitMathLines / bareLatexLine / MdTextLines 表格分支）。
private val BARE_LATEX_CMD_REGEX = Regex("""\\[A-Za-z]+""")
private val BARE_LATEX_KNOWN_REGEX =
    Regex("""\\(frac|sqrt|quad|Longrightarrow|Longleftrightarrow|Rightarrow|left|right|sum|int|lim|cdot|times|le|ge|neq)""")
private val BROKEN_TAIL_CMD_REGEX = Regex("""\\([A-Za-z]+)$""")
private val ORDERED_LIST_START_REGEX = Regex("""\d+[.)]\s""")
private val TABLE_SEP_REGEX = Regex("^:?-{2,}:?$")

/**
 * 2026-11-11 用户截图：模型把一条行内公式**折行**写断（`…\Longrightar` 换行 `row\quadA=1`），
 * `$…$` 跨行后两侧都配不上对 → 整段 LaTeX 漏原文。
 *
 * 修：某行 `$` 计数为奇数（未闭合）且含 LaTeX 命令时，直接与下一行**无空格拼接**
 * （折行发生在命令字中间，补空格反而把 `\Longrightar` 和 `row` 拆成两个词）；
 * 下一行是块开始（标题/列表/表格/围栏/引用/空行）则不动，宁可漏原文也不吞结构。
 */
/** prejoinSplitMathLines 结果缓存（2026-11-21 滚动掉帧根治①续）：纯函数但每次（重）组合
 *  都对整段逐行扫——LazyColumn 回场即重算。按原文跨组合缓存。 */
private val prejoinCache = androidx.collection.LruCache<String, List<String>>(512)

internal fun prejoinSplitMathLines(raw: List<String>): List<String> {
    val cacheKey = raw.joinToString("\n")
    prejoinCache.get(cacheKey)?.let { return it }
    val out = ArrayList<String>(raw.size)
    var i = 0
    fun dollarBalanced(s: String): Boolean {
        var n = 0
        var k = 0
        while (k < s.length) {
            if (s[k] == '$' && (k == 0 || s[k - 1] != '\\')) n++
            k++
        }
        return n % 2 == 0
    }
    // 行尾是「被截断的长命令前缀」（真机实录：`…\Longrightar` 换行 `row\quadA=1`）
    val longCommands = listOf(
        "Longrightarrow", "Longleftrightarrow", "longrightarrow", "longleftarrow",
        "Leftrightarrow", "Rightarrow", "Leftarrow", "rightarrow", "leftarrow",
        "leftrightarrow", "Longleftarrow"
    )
    fun brokenTail(s: String): Boolean {
        val t = s.trimEnd()
        val m = BROKEN_TAIL_CMD_REGEX.find(t) ?: return false
        val word = m.groupValues[1]
        if (word.length < 4) return false
        return longCommands.any { it != word && it.startsWith(word) }
    }
    fun isBlockStart(line: String): Boolean {
        val t = line.trimStart()
        return t.isBlank() || t.startsWith("#") || t.startsWith("|") ||
            t.startsWith("- ") || t.startsWith("* ") || t.startsWith("> ") ||
            t.startsWith("```") || t.startsWith("~~~") ||
            ORDERED_LIST_START_REGEX.containsMatchIn(t)
    }
    while (i < raw.size) {
        var line = raw[i]
        var guard = 0
        while ((!dollarBalanced(line) && line.contains('\\') || brokenTail(line)) &&
            i + 1 < raw.size && !isBlockStart(raw[i + 1]) && guard < 4
        ) {
            line += raw[i + 1].trimStart()   // 无空格：折行可能断在命令字中间
            i++
            guard++
        }
        out.add(line)
        i++
    }
    prejoinCache.put(cacheKey, out)
    return out
}

/** 裸 LaTeX（没有 `$` 定界符）的行：`\frac/\sqrt/\quad/\Longrightarrow` 这类命令出现 ≥2 个
 * 且不含中文与代码引号 → 视为公式行，渲染时套 `$$…$$`。2026-11-11 用户截图：
 * 模型整段写 `a_2=\frac{4+3}{4}=1.75<2\quad\Longrightarrow A=1`，没有 `$` 就整段漏原文。 */
internal fun bareLatexLine(line: String): String? {
    val t = line.trim()
    if (t.isEmpty() || t.contains('$') || t.contains('`')) return null
    if (t.any { it.code in 0x4E00..0x9FFF }) return null
    val cmdCount = BARE_LATEX_CMD_REGEX.findAll(t).count()
    if (cmdCount < 2) return null
    if (!BARE_LATEX_KNOWN_REGEX.containsMatchIn(t)) return null
    return t
}

/** MdTextLines 表格分支的解析结果缓存（2026-11-17 滚动流畅性 P1）：
 *  切格/分隔行过滤/续行合并是纯函数，跨组合复用——往复滑回同一表格不再重跑
 *  splitTableRow + mergeTableRows（原来每次重组都算，且 isSep 每格 new Regex）。 */
private val mdTableCache = androidx.collection.LruCache<String, List<List<String>>>(64)

/** 代码高亮结果缓存（2026-11-21 滚动掉帧根治①续）：词法涂色是正则密集分析，remember 只活到
 *  item 滑出，往复滚动每次回场都全量重涂。按（配色方案身份|样式档|lang|code）跨组合缓存。 */
private val codeHlCache = androidx.collection.LruCache<String, androidx.compose.ui.text.AnnotatedString>(96)

/** 表格两遍测量结论缓存（2026-11-21 单帧瞬移根治②）：翻支有两个触发器——格内数学内容
 *  被挤压（onClamped→squeeze）与「估算总宽 vs 容器宽」判定，而容器宽 `containerPx` 是
 *  remember 状态、每轮回场从 0 重新量 → **每次进场**（不只回场）都先走 fit 分支、
 *  第二帧才翻横滑分支，整表高度一帧内大变（追踪器实锤：fling 中途抽搐、到底后弹 350~1015px）。
 *  这里把 [实测容器宽, squeeze结论] 按表格内容跨组合缓存，回场/复见首帧即用正确分支。
 *  键不含容器宽：不同宽度屏幕上会沿用旧宽，代价只是列宽估错而非渲染错误。 */
private val tblTwoPassCache = androidx.collection.LruCache<String, IntArray>(128)

private fun parseMdTable(tbl: List<String>): List<List<String>> {
    val key = tbl.joinToString("\n")
    mdTableCache.get(key)?.let { return it }
    val cellsOf = { row: String -> io.github.m0use001.planability.core.splitTableRow(row) }
    val isSep = { row: String -> cellsOf(row).isNotEmpty() && cellsOf(row).all { TABLE_SEP_REGEX.matches(it) } }
    val grid = tbl.filter { !isSep(it) }.map { cellsOf(it) }.filter { it.any { c -> c.isNotBlank() } }
    val merged = mergeTableRows(grid)
    mdTableCache.put(key, merged)
    return merged
}

@Composable
private fun MdTextLines(text: String) {
    // 2026-11-21 分解探针：整段文字行的组合耗时（父子顺序决定它会包含其内表格/公式的耗时）
    // 2026-11-21 分解探针：每次组合重新取点（remember 会把跨重组/异步等待算进来，虚高）
    val tLines0 = android.os.SystemClock.elapsedRealtime()
    // 2026-11-11 掉帧修：分段/折行拼接是纯函数，记忆化后重组合（流式每帧、翻动回场）不再重算
    val lines = remember(text) { prejoinSplitMathLines(text.lines()) }
    var i = 0
    while (i < lines.size) {
        val line = lines[i]
        val t = line.trimStart()
        // 嵌套列表缩进（每 2-3 个空格算一级，封顶 24）
        val indent = ((line.length - t.length) * 5).coerceAtMost(120)
        val listPad = Modifier.padding(start = indent.dp)
        when {
            // 十三期：任务清单 - [ ] / - [x]（支持缩进嵌套）
            t.startsWith("- [ ] ") || t.startsWith("- [x] ") ||
                t.startsWith("* [ ] ") || t.startsWith("* [x] ") -> {
                val done = t.substring(2, 5) == "[x]"
                Row(listPad.padding(vertical = 1.dp)) {
                    Text(
                        if (done) "☑  " else "☐  ",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    MdInlineText(t.drop(6))
                }
                Spacer(Modifier.height(2.dp))
            }
            // 2026-11-02：标题一律走 MdInlineText——用户会话里「## 二、第一组：直接关于
            // $A\mathbf x=\mathbf b$」整个标题把 LaTeX 源码裸显（Text 不走公式切分）
            //
            // v54.1 排版：六级标题原来只是"字号大一点的粗体"，堆在一起读不出层次。
            // 现在 H1 当文档标题（底部细分隔线收口）、H2 带左侧主色竖条（小节锚点感）、
            // H3 起只靠字重与字号，且**标题前留白 > 标题后**——这是排版里最便宜也最见效的一条。
            HEADING_ANY.containsMatchIn(t) -> {
                // 切分逻辑在 NotePreview.splitHeading（有单测钉住）：只吃掉 `#` 与空白，
                // 不能用 `#{1,6}\s*\S` 的匹配长度去截——那会把标题首字切掉（真机 bug："amma"）。
                val h = NotePreview.splitHeading(t)
                if (h != null) MdHeading(h.first, h.second) else {
                    MdInlineText(line, style = MaterialTheme.typography.bodyLarge); Spacer(Modifier.height(6.dp))
                }
            }
            // 十三期：多行/嵌套引用合并为一个块
            // v54.1：原来是一整块 primaryContainer 底色，面积大、和卡片抢注意力。
            // 改成「左侧竖线 + 弱化文字」的经典引用样式：只用一根线表达"这是引来的话"。
            t.startsWith(">") -> {
                val quoteLines = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                    quoteLines += lines[i].trimStart().removePrefix(">").removePrefix(" ")
                    i++
                }
                i--
                // 2026-11-10 真机 crash-loop 根修（微积分会话 5562）：原来用
                // Row.height(IntrinsicSize.Min) + 子级 Box.fillMaxHeight 画左侧竖线。
                // IntrinsicSize.Min 会对**整棵子树**做 intrinsic 测量，而 MdInlineText 含
                // 公式时是 BoxWithConstraints（SubcomposeLayout，明确不支持 intrinsic）→
                // 「引用块 + 行内公式」一渲染就 IllegalStateException（App 自动恢复该会话
                // 即崩循环，19:58 起 6 次 FATAL）。竖线改 drawBehind 直接画：高度取本行
                // 实测尺寸，零 intrinsic 测量，视觉等价（3dp 圆角条 + 正文缩进 13dp 不变）。
                val quoteBar = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .drawBehind {
                            val w = 3.dp.toPx()
                            drawRoundRect(
                                color = quoteBar,
                                size = androidx.compose.ui.geometry.Size(w, size.height),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(w / 2f, w / 2f)
                            )
                        }
                        .padding(start = 13.dp)   // 3dp 竖线 + 10dp 间距
                ) {
                    Column(Modifier.weight(1f)) {
                        quoteLines.forEach { q ->
                            if (q.isBlank()) Spacer(Modifier.height(4.dp))
                            else MdInlineText(q, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
            }
            t.startsWith("- ") || t.startsWith("* ") -> {
                Row(listPad) {
                    Text("•  ", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    MdInlineText(t.drop(2))
                }
                Spacer(Modifier.height(2.dp))
            }
            // 十三期：有序列表 1. / 1)（Regex 提为顶层常量：原来逐行 new，翻动一屏几十次编译）
            t.matches(ORDERED_LIST_LINE) -> {
                val numEnd = t.indexOfFirst { it == '.' || it == ')' }
                Row(listPad) {
                    Text(
                        t.take(numEnd + 1) + "  ",
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold
                    )
                    MdInlineText(t.drop(numEnd + 1).trimStart())
                }
                Spacer(Modifier.height(2.dp))
            }
            line.trimStart().startsWith("|") -> {
                val tbl = mutableListOf<String>()
                while (i < lines.size && lines[i].trimStart().startsWith("|")) {
                    tbl += lines[i].trim(); i++
                }
                i--
                // 2026-11-12：切格必须避开 `$…$` 里的竖线（`|b_n-1|` 曾是列分隔符 →
                // 表格错列/公式裸奔），见 core/MarkdownTables.splitTableRow。
                // 2026-11-17 P1：解析走 parseMdTable（模块级 LruCache，往复滑回不重算）。
                val merged = parseMdTable(tbl)
                if (merged.size >= 2) {
                    android.os.Trace.beginSection("Md.Table")
                    // 2026-11-21 探针：调用点就地计时（表格组件内部用 SideEffect 会算到帧末）
                    val tt0 = android.os.SystemClock.elapsedRealtime()
                    MdTable(merged.first(), merged.drop(1))
                    io.github.m0use001.planability.core.ScrollTrace.noteComposePart(
                        "表", (android.os.SystemClock.elapsedRealtime() - tt0).toInt()
                    )
                    android.os.Trace.endSection()
                    Spacer(Modifier.height(10.dp))
                } else {
                    merged.forEach { r ->
                        Text(
                            r.joinToString("  ·  "),
                            fontFamily = FontFamily.Monospace, fontSize = 12.sp
                        )
                    }
                    Spacer(Modifier.height(6.dp))
                }
            }
            // 十三期：分隔线变体 --- / *** / ___
            line.trim() == "---" || line.trim() == "***" || line.trim() == "___" -> {
                Spacer(Modifier.height(6.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Spacer(Modifier.height(6.dp))
            }
            line.isNotBlank() -> {
                // 2026-11-11：裸 LaTeX 行（无 $ 定界符）整行按公式渲，别漏原文
                val bare = bareLatexLine(line)
                if (bare != null) {
                    MdInlineText("$$" + bare + "$$", style = MaterialTheme.typography.bodyLarge)
                    Spacer(Modifier.height(6.dp))
                } else {
                    // v54.1：正文行高 23sp——默认行距在中文长段落里挤，稍微放宽更好读
                    MdInlineText(
                        line,
                        style = MaterialTheme.typography.bodyLarge.copy(lineHeight = 23.sp)
                    )
                    Spacer(Modifier.height(6.dp))
                }
            }
        }
        i++
    }
    // 2026-11-21 探针：组合内就地取差值（SideEffect 是全帧末，会吞掉后续兄弟节点的耗时）
    io.github.m0use001.planability.core.ScrollTrace.noteComposePart(
        "文", (android.os.SystemClock.elapsedRealtime() - tLines0).toInt()
    )
}

/** 轻量 Markdown 表格：第一行为表头，列宽按内容比例（最多 6 列，超出并入末列） */
@Composable
private fun MdTable(header: List<String>, rows: List<List<String>>) {
    // 2026-11-14 MdPerf 二段打点：表内耗时单独归因（rows×cells 全在一帧组合）
    // 2026-11-21 探针修正：每次组合重新取点（remember 会把跨重组等待算进来）。
    val tTbl0 = android.os.SystemClock.elapsedRealtime()
    val mathCells = remember(header, rows) {
        (listOf(header) + rows).sumOf { r -> r.count { it.contains('$') } }
    }
    androidx.compose.runtime.SideEffect {
        val dt = android.os.SystemClock.elapsedRealtime() - tTbl0
        if (dt > 25) android.util.Log.w("MdPerf", "table=${dt}ms rows=${rows.size} mathCells=$mathCells")
    }
    val width = maxOf(header.size, rows.maxOfOrNull { it.size } ?: 0).coerceAtMost(6)
    // 2026-11-18 真机（◆ 消息 7721，用户「又乱码」）：这里原来把单元格也
    // `take(120)`，长格从**公式中间**截断（`…得 $\dfrac{4x^4-9x^2+8x}{(4x^2-3)^`），
    // `$` 配对失败 → 从截断点起整段回退裸 LaTeX。截断只该用于列宽估算，渲染必须全文。
    fun cellEst(row: List<String>, c: Int): String = cellText(row, c, width).take(120)
    // 2026-11-12 换 estLen：公式源码折算成近似渲染宽（\command 记 1 个字符、括号/上下标
    // 符号不计）——`$\frac{x^{r+1}}{r+1}+C$` 源码 22 字符、渲染出来约 8 个字符宽，
    // 按源码长算列权会虚胖一倍多（fill 模式公式列白白挤占邻列、滑动模式误开档）。
    val weights = (0 until width).map { c ->
        val maxLen = (listOf(header.getOrElse(c) { "" }) + rows.map { cellEst(it, c) })
            .maxOfOrNull { estLen(it) } ?: 0
        maxLen.toFloat().coerceIn(12f, 30f)   // 太短的列保底、超长的列封顶，防一列吃光全宽
    }
    // 2026-11-12 用户「表格加自适应左右滑动，适应长公式」：单元格里的公式原先一律被
    // maxWem 硬夹进列宽（长公式缩到不可读）。现按列内容估算自然宽做开档判断：
    // 总宽装得下容器 → 维持 weight 铺满（窄表格行为不变）；装不下 → 整表横向滑动，
    // 单元格取估算固定宽（各行列对齐），公式在宽格子里按自然尺寸渲。
    // 手势登记 hScrollZone 与代码块同口径（聊天页右滑抽屉在表格上让位）。
    // 2026-11-13 用户两连校准（「长表格没有滑动条」+「刚好占满的不太长公式被塞了条」）：
    // 源码长度估算两头不准 → 加**实测挤压**信号：MdInlineText 上报单元格数学内容的
    // 自然宽超过格宽（=真被挤压）也开滑，且**sticky**（进滑动后不回退，防来回抖）；
    // 滑动模式列宽再按上报自然宽适配，公式按原尺寸渲染。
    val colDp = (0 until width).map { c ->
        ((listOf(header.getOrElse(c) { "" }) + rows.map { cellEst(it, c) })
            .maxOfOrNull { estLen(it) } ?: 0) * 7 + 18
    }.map { it.dp.coerceIn(64.dp, 280.dp) }
    val totalDp = colDp.map { it.value }.sum().dp
    val density = androidx.compose.ui.platform.LocalDensity.current
    // 2026-11-21 瞬移根治②（表格）：实测容器宽与 squeeze 结论跨组合缓存，
    // 回场/复见首帧即用正确分支，两遍测量不再翻转。
    val tblContentKey = (listOf(header) + rows).joinToString("\u0001")
    val tblCached = tblTwoPassCache.get(tblContentKey)
    var containerPx by remember { mutableIntStateOf(tblCached?.get(0) ?: 0) }
    val clampedEm = remember(header, rows) { androidx.compose.runtime.mutableStateMapOf<Int, Float>() }
    var squeezed by remember(header, rows) { mutableStateOf(tblCached?.get(1) == 1) }
    fun tblRememberState() {
        tblTwoPassCache.put(tblContentKey, intArrayOf(containerPx, if (squeezed) 1 else 0))
    }
    fun reportClamped(c: Int, em: Float) {
        if (em > (clampedEm[c] ?: 0f)) clampedEm[c] = em   // 单向棘轮：同名内容重复上报不抖
        if (!squeezed) {
            squeezed = true
            tblRememberState()
        }
    }
    val fontPxCell = with(density) { 12.sp.toPx() }   // bodySmall/labelMedium 同量级
    val colDpFit = (0 until width).map { c ->
        val need = clampedEm[c]?.let { (it * fontPxCell).dp + 20.dp } ?: 0.dp
        maxOf(colDp[c], need)
    }
    val totalDpFit = colDpFit.map { it.value }.sum().dp
    // 2026-11-21 瞬移根治②：首帧 containerPx 还没量到时用屏宽兜底判定——原来首帧必走
    // fit 分支、量到容器宽的第二帧才翻横滑，每次进场都跳一次。
    val effContainerPx = if (containerPx > 0) containerPx.toFloat()
    else with(density) {
        androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp.toPx()
    }
    // 2026-11-21 瞬移根治②b：含数学格、且估算宽度贴近容器的表格**首帧**直接进横滑分支。
    // 挤压信号（onClamped）只有数学格会发（MdInlineText 的 clampedEm 只在公式分支累计），
    // 而它要等实测才知道——冷进场先 fit 再翻，锚点条目高度骤变，LazyColumn 重锚定滚动，
    // 就是 fling 中途「抽搐一下再继续惯性」的实锤机制（追踪器：突跳 1618px/11ms[手0程0]）。
    // 文字格只会折行（高度首测定死）→ 文字表维持 fit；窄数学表（估算 ≤85% 容器宽）也不动。
    val estRatio = with(density) { totalDp.toPx() } / effContainerPx
    val hasMathCell = header.any { it.contains('$') } || rows.any { r -> r.any { it.contains('$') } }
    val overflow = effContainerPx > 0 && (
        estRatio > 1f || squeezed || (hasMathCell && estRatio > 0.85f)
    )

    val surface = MaterialTheme.colorScheme.surfaceContainerLow
    val headerSurface = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f)
    val lineColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    // 2026-11-21「压缩毛巾」：单元格可用宽（px，扣掉格内 8dp×2 内边距）——**首帧**就告诉
    // MdInlineText，拆行/占位一次判对，不再等布局回写宽度后第二帧翻转。
    val sumWeights = weights.sum().takeIf { it > 0f } ?: 1f
    val cellHint: (Int) -> Float = { c ->
        with(density) {
            val w = if (overflow) (colDpFit[c] - 16.dp).toPx()
            else effContainerPx * (weights[c] / sumWeights) - 16.dp.toPx()
            w.coerceAtLeast(24f)
        }
    }
    // 2026-11-13 用户「滑动条要可见」：滚动分支挂 hScrollHint 指示条（装得下时不画）
    val tblHs = rememberScrollState()
    if (overflow) {
        Row(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { containerPx = it.width; tblRememberState() }
                .hScrollZone()
                .hScrollHint(tblHs)
                .horizontalScroll(tblHs)
        ) {
            Column(
                Modifier
                    .width(totalDpFit)
                    .clip(RoundedCornerShape(8.dp))
                    .background(surface)
            ) {
                Row(Modifier.background(headerSurface)) {
                    (0 until width).forEach { c ->
                        MdInlineText(
                            header.getOrElse(c) { "" },
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.width(colDpFit[c]).padding(horizontal = 8.dp, vertical = 6.dp),
                            fontWeight = FontWeight.SemiBold,
                            onClamped = { em -> reportClamped(c, em) },
                            widthHintPx = cellHint(c),
                            compactMath = true,
                        )
                    }
                }
                // 2026-11-14 用户「上下滑动还是卡」终段修：表格组合开销=每格一层 Box 包装
                // + 每行一层分隔 Box——A/B 实测纯结构 ~0.8ms/格（69 行消息 193ms，剥公式
                // 只省 7%）。改：分隔线 drawBehind modifier 化（不占组合节点）、格文字直接
                // 挂 weight/width modifier（砍掉每格一层 Box），>12 行的表格再加行虚拟化
                // 卡片（表头常驻，行进内部 LazyColumn 只组合可见行）。
                if (rows.size > 12) {
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.height(420.dp)) {
                        itemsIndexed(rows) { _, r ->
                            tableRow(
                                r, width, rowWidth = totalDpFit,
                                colDp = { colDpFit[it] }, lineColor = lineColor,
                                hintPx = cellHint,
                            )
                        }
                    }
                } else {
                    rows.forEach { r ->
                        tableRow(
                                r, width, rowWidth = totalDpFit,
                                colDp = { colDpFit[it] }, lineColor = lineColor,
                                hintPx = cellHint,
                            )
                    }
                }
            }
        }
    } else {
        Column(
            Modifier
                .fillMaxWidth()
                .onSizeChanged { containerPx = it.width; tblRememberState() }
                .clip(RoundedCornerShape(8.dp))
                .background(surface)
        ) {
            Row(Modifier.fillMaxWidth().background(headerSurface)) {
                (0 until width).forEach { c ->
                    // 2026-10-27：单元格换 MdInlineText——模型爱在表格里写行内公式
                    // （$\Delta=b^2-4ac$）与粗体，原 Text(inlineMd) 渲染不出公式只露原文
                    MdInlineText(
                        header.getOrElse(c) { "" },
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(weights[c]).padding(horizontal = 8.dp, vertical = 6.dp),
                        fontWeight = FontWeight.SemiBold,
                        onClamped = { em -> reportClamped(c, em) },
                        widthHintPx = cellHint(c),
                        compactMath = true,
                    )
                }
            }
            if (rows.size > 12) {
                androidx.compose.foundation.lazy.LazyColumn(Modifier.height(420.dp)) {
                    itemsIndexed(rows) { _, r ->
                        tableRowWeighted(
                            r, width, weights = { weights[it] }, lineColor = lineColor,
                            hintPx = cellHint,
                        )
                    }
                }
            } else {
                rows.forEach { r ->
                    tableRowWeighted(
                            r, width, weights = { weights[it] }, lineColor = lineColor,
                            hintPx = cellHint,
                        )
                }
            }
        }
    }
}

/** 行分隔线：drawBehind modifier（不占组合节点；DrawScope 自带 density，1.dp.toPx 可用） */
private fun Modifier.tableRowLine(color: Color): Modifier = drawBehind {
    drawLine(color, Offset(0f, 0f), Offset(size.width, 0f), strokeWidth = 1.dp.toPx())
}

/** 固定列宽行（横滑模式）：每格一层 Text（width+padding 直接挂），砍掉 Box 包装。
 *  [rowWidth] 必须是各列宽之和——Row 宽度小于列宽和会把固定宽格子挤扁（列重叠） */
@Composable
private fun tableRow(
    r: List<String>,
    width: Int,
    rowWidth: androidx.compose.ui.unit.Dp,
    colDp: (Int) -> androidx.compose.ui.unit.Dp,
    lineColor: Color,
    hintPx: (Int) -> Float = { 0f },
) {
    Row(Modifier.width(rowWidth).tableRowLine(lineColor)) {
        (0 until width).forEach { c ->
            val text = cellText(r, c, width)
            val mod = Modifier.width(colDp(c)).padding(horizontal = 8.dp, vertical = 5.dp)
            if (!text.contains('$') && !text.contains("[[")) {
                Text(inlineMd(text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodySmall, modifier = mod)
            } else {
                MdInlineText(
                    text, style = MaterialTheme.typography.bodySmall, modifier = mod,
                    widthHintPx = hintPx(c), compactMath = true,
                )
            }
        }
    }
}

/** 权重列宽行（铺满模式）：同上，砍掉 Box 包装 */
@Composable
private fun tableRowWeighted(
    r: List<String>,
    width: Int,
    weights: (Int) -> Float,
    lineColor: Color,
    hintPx: (Int) -> Float = { 0f },
) {
    Row(Modifier.fillMaxWidth().tableRowLine(lineColor)) {
        (0 until width).forEach { c ->
            val text = cellText(r, c, width)
            val mod = Modifier.weight(weights(c)).padding(horizontal = 8.dp, vertical = 5.dp)
            if (!text.contains('$') && !text.contains("[[")) {
                Text(inlineMd(text, MaterialTheme.colorScheme.primary), style = MaterialTheme.typography.bodySmall, modifier = mod)
            } else {
                MdInlineText(
                    text, style = MaterialTheme.typography.bodySmall, modifier = mod,
                    widthHintPx = hintPx(c), compactMath = true,
                )
            }
        }
    }
}

/**
 * 单元格取文（原 MdTable.cell 闭包提出来共用）——**渲染用全文，绝不截断**。
 * 2026-11-18 真机（◆ 消息 7721）：`.take(120)` 把长格从公式中间截断
 * （`…得 $\dfrac{4x^4-9x^2+8x}{(4x^2-3)^`），`$` 配对失败 → 剩下半截连同 `$`
 * 当正文裸显，用户看到的就是「乱码」。列宽估算另有 MdTable 内的截断版。
 */
internal fun cellText(row: List<String>, c: Int, width: Int): String =
    if (c == width - 1 && row.size > width) row.drop(width - 1).joinToString(" / ")
    else row.getOrElse(c) { "" }

/**
 * 2026-11-12 表格列宽估算：公式源码折算成近似渲染宽——`\command`（一串字母）按 1 个
 * 字符计（`\frac` 渲出来就一个分号位）、花括号/上下标/对齐符号不计，CJK 记 2。
 */
private val LATEX_SOURCE_CMD = Regex("\\\\[A-Za-z]+")
internal fun estLen(s: String): Int {
    // 空格不剥：正文格的空格真实占宽（公式源码里的空格 LaTeX 会忽略，多计一点无妨）
    val deflated = s.replace(LATEX_SOURCE_CMD, "x")
        .replace("$", "").replace("{", "").replace("}", "")
        .replace("^", "").replace("_", "").replace("&", "").replace("\\", "")
    return deflated.count { it.code >= 0x2E80 } * 2 + deflated.count { it.code < 0x2E80 }
}

// ==================== 笔记图表块 ====================

/** ```chart 图表块：line / bar / pie（数据 格式 "类别:数值"，逗号分隔） */
@Composable
private fun ChartBlock(
    type: String,
    title: String,
    entries: List<Pair<String, Float>>,
    opts: ChartOptions = ChartOptions()
) {
    // 右上角三点 → 详细编辑（标注数值/趋势线/图例/网格/配色/高度/切换类型/存为全局默认）。
    // 菜单里的开关只影响这张图；「存为全局默认」才写到设置里。
    var cur by remember(type, title) { mutableStateOf(type) }
    ChartFrame(
        chartType = cur,
        title = title,
        switchableTypes = SWITCHABLE_FOR[cur] ?: emptyList(),
        onTypeChange = { cur = it },
        defaultLegend = cur == "pie" || cur == "donut"
    ) { o ->
        ChartBlockBody(cur, title, entries, o)
    }
}

/** 每种图表可切换到的类型（只列同一数据形态能表达的） */
private val SWITCHABLE_FOR: Map<String, List<String>> = mapOf(
    "bar" to listOf("bar", "hbar", "line", "area", "pie", "donut"),
    "hbar" to listOf("hbar", "bar", "line", "area", "pie", "donut"),
    "line" to listOf("line", "area", "bar", "hbar", "pie", "donut"),
    "area" to listOf("area", "line", "bar", "hbar", "pie", "donut"),
    "pie" to listOf("pie", "donut", "bar", "line"),
    "donut" to listOf("donut", "pie", "bar", "line")
)

@Composable
private fun ChartBlockBody(
    type: String,
    title: String,
    entries: List<Pair<String, Float>>,
    opts: ChartOptions
) {
    val textMeasurer = rememberTextMeasurer()
    val basePalette = SeedColors.chartPalette(isSystemInDarkTheme())
    // 调色板序号：循环取用不同起点，同一张图和下一张图就能区分开
    val palette = if (opts.paletteIdx > 0) {
        val off = opts.paletteIdx % basePalette.size
        basePalette.drop(off) + basePalette.take(off)
    } else basePalette
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            if (title.isNotBlank()) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
            }
            val maxV = entries.maxOf { it.second }.coerceAtLeast(0.001f)
            when (type) {
                // 面积图：折线图 + 曲线到基线的填充，强调"随时间变化的幅度/累积量"
                "area", "面积图" -> {
                    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                        val w = size.width
                        val top = 8.dp.toPx()
                        val bottom = size.height - 8.dp.toPx()
                        if (opts.showGrid) {
                            repeat(4) { g ->
                                val gy = top + (bottom - top) * g / 3
                                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                            }
                        }
                        val n = entries.size
                        val pts = entries.mapIndexed { idx, (_, v) ->
                            Offset(
                                if (n <= 1) w / 2 else idx * w / (n - 1),
                                bottom - (v / maxV) * (bottom - top)
                            )
                        }
                        if (pts.isNotEmpty()) {
                            val fill = Path()
                            fill.moveTo(pts.first().x, bottom)
                            pts.forEach { fill.lineTo(it.x, it.y) }
                            fill.lineTo(pts.last().x, bottom)
                            fill.close()
                            drawPath(
                                fill,
                                androidx.compose.ui.graphics.Brush.verticalGradient(
                                    listOf(lineColor.copy(alpha = 0.45f), lineColor.copy(alpha = 0.04f)),
                                    startY = top, endY = bottom
                                )
                            )
                            val line = Path()
                            pts.forEachIndexed { idx, p ->
                                if (idx == 0) line.moveTo(p.x, p.y)
                                else {
                                    val prev = pts[idx - 1]
                                    val midX = (prev.x + p.x) / 2
                                    line.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                                }
                            }
                            drawPath(line, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                            pts.forEach { drawCircle(lineColor, radius = 3.dp.toPx(), center = it) }
                        }
                    }
                }
                // 条形图（横置）：类目名长时唯一可读的形态 —— 竖排会把长标签压成竖排单字
                "hbar", "条形图" -> {
                    val rowDp = 26.dp
                    Column(Modifier.fillMaxWidth()) {
                        entries.forEachIndexed { idx, (k, v) ->
                            Row(
                                Modifier.fillMaxWidth().height(rowDp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    k, style = MaterialTheme.typography.labelSmall,
                                    color = labelColor, maxLines = 1,
                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                                    modifier = Modifier.width(64.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(14.dp)
                                        .clip(RoundedCornerShape(3.dp))
                                        .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth((v / maxV).coerceIn(0f, 1f))
                                            .fillMaxHeight()
                                            .clip(RoundedCornerShape(3.dp))
                                            .background(palette[idx % palette.size])
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = labelColor,
                                    modifier = Modifier.width(34.dp)
                                )
                            }
                        }
                    }
                }
                "line" -> {
                    val measurer = rememberTextMeasurer()
                    val vStyle = TextStyle(fontSize = 9.sp, color = labelColor)
                    // Canvas 的 DrawScope 里不能读 MaterialTheme（不在 @Composable 上下文），必须提前取
                    val trendColor = MaterialTheme.colorScheme.tertiary
                    Canvas(Modifier.fillMaxWidth().height(opts.heightOr(140).dp)) {
                        val w = size.width
                        val top = 14.dp.toPx()
                        val bottom = size.height - 8.dp.toPx()
                        if (opts.showGrid) {
                            repeat(4) { g ->
                                val gy = top + (bottom - top) * g / 3
                                drawLine(gridColor, Offset(0f, gy), Offset(w, gy), strokeWidth = 1.dp.toPx())
                            }
                        }
                        val n = entries.size
                        val pts = entries.mapIndexed { idx, (_, v) ->
                            Offset(
                                if (n <= 1) w / 2 else idx * w / (n - 1),
                                bottom - (v / maxV) * (bottom - top)
                            )
                        }
                        val path = Path()
                        pts.forEachIndexed { idx, p ->
                            if (idx == 0) path.moveTo(p.x, p.y)
                            else {
                                val prev = pts[idx - 1]
                                val midX = (prev.x + p.x) / 2
                                path.cubicTo(midX, prev.y, midX, p.y, p.x, p.y)
                            }
                        }
                        drawPath(path, lineColor, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
                        pts.forEach { drawCircle(lineColor, radius = 3.dp.toPx(), center = it) }
                        // 趋势线：最小二乘直线，斜率不为 0 才画（否则一条水平线没信息量）
                        if (opts.showTrendLine && n >= 3) {
                            val xs = entries.indices.map { it.toDouble() }
                            val ys = entries.map { it.second.toDouble() }
                            val mx = xs.average()
                            val my = ys.average()
                            val num = xs.zip(ys).sumOf { p -> (p.first - mx) * (p.second - my) }
                            val den = xs.sumOf { x -> (x - mx) * (x - mx) }
                            if (den != 0.0 && num != 0.0) {
                                val slope = num / den
                                val intercept = my - slope * mx
                                val y0 = intercept
                                val y1 = intercept + slope * (n - 1)
                                // 全部在 Double 域算完再转 Float —— maxV 是 Float，
                                // 混着写会一路 Double/Float 报错
                                fun yPix(d: Double): Float =
                                    (bottom - (d / maxV).toFloat() * (bottom - top)).coerceIn(top, bottom)
                                val tp = Path()
                                tp.moveTo(pts.first().x, yPix(y0))
                                tp.lineTo(pts.last().x, yPix(y1))
                                drawPath(
                                    tp, trendColor,
                                    style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
                                )
                            }
                        }
                        // 数值标注
                        if (opts.showValues) {
                            pts.forEachIndexed { i, p ->
                                val v = entries[i].second
                                val txt = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
                                val m = measurer.measure(AnnotatedString(txt), vStyle)
                                drawText(
                                    measurer, AnnotatedString(txt),
                                    topLeft = Offset(
                                        (p.x - m.size.width / 2).coerceIn(0f, w - m.size.width),
                                        (p.y - m.size.height - 4.dp.toPx()).coerceAtLeast(0f)
                                    ),
                                    style = vStyle
                                )
                            }
                        }
                    }
                }
                // pie 与 donut 同一段代码：donut 只是 useCenter=false + 留出 innerRadius。
                // 圆环比饼图多一圈半径，中心可以放总量，读数比纯饼图舒服。
                "pie", "donut" -> {
                    val total = entries.sumOf { it.second.toDouble() }.toFloat().coerceAtLeast(0.001f)
                    val donut = type == "donut"
                    val donutLabelColor = MaterialTheme.colorScheme.onSurface
                    Canvas(Modifier.fillMaxWidth().height(opts.heightOr(200).dp)) {
                        val r = minOf(size.width, size.height) / 2f - 8.dp.toPx()
                        val cx = size.width / 2f
                        val cy = size.height / 2f
                        var startAngle = -90f
                        entries.forEachIndexed { idx, (_, v) ->
                            val sweep = 360f * v / total
                            if (donut) {
                                val ringW = (r * 0.42f).coerceAtLeast(6f)
                                drawArc(
                                    color = palette[idx % palette.size],
                                    startAngle = startAngle, sweepAngle = sweep, useCenter = false,
                                    topLeft = Offset(cx - r, cy - r),
                                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2),
                                    style = androidx.compose.ui.graphics.drawscope.Stroke(width = ringW)
                                )
                            } else {
                                drawArc(
                                    color = palette[idx % palette.size],
                                    startAngle = startAngle, sweepAngle = sweep, useCenter = true,
                                    topLeft = Offset(cx - r, cy - r),
                                    size = androidx.compose.ui.geometry.Size(r * 2, r * 2)
                                )
                            }
                            startAngle += sweep
                        }
                        // 圆环中心写总量
                        if (donut) {
                            val st = TextStyle(fontSize = 12.sp, color = donutLabelColor)
                            val t = if (total == total.toInt().toFloat()) total.toInt().toString() else "%.1f".format(total)
                            drawText(
                                textMeasurer, AnnotatedString(t),
                                topLeft = Offset(cx - textMeasurer.measure(AnnotatedString(t), st).size.width / 2f, cy - 8.dp.toPx()),
                                style = st
                            )
                        }
                    }
                }
                else -> {
                    // bar（默认）
                    // 柱体和数值/趋势线分两层：柱子是 Row（权重均分），数值与趋势线
                    // 必须跨整个区域画，所以套一个 Box 里叠一层 Canvas。
                    // 覆盖同样的槽宽计算，这样标注会正对柱顶。
                    Box(Modifier.fillMaxWidth().height(opts.heightOr(140).dp)) {
                        Row(
                            Modifier.fillMaxWidth().height(140.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.Bottom
                        ) {
                            entries.forEachIndexed { idx, (_, v) ->
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .height(((120f * v / maxV).coerceAtLeast(4f)).dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(palette[idx % palette.size])
                                )
                            }
                        }
                        if (opts.showValues || opts.showTrendLine) {
                            val measurer = rememberTextMeasurer()
                            val vStyle = TextStyle(fontSize = 9.sp, color = labelColor)
                            val trendColor = MaterialTheme.colorScheme.tertiary
                            Canvas(Modifier.fillMaxWidth().height(140.dp)) {
                                val n = entries.size
                                if (n > 0) {
                                    val gap = 6.dp.toPx()
                                    val slotW = (size.width - gap * (n - 1)) / n
                                    // 柱底对齐容器底，柱高 = 120dp * v/maxV，因此柱顶 y 可直接反推
                                    fun topOf(i: Int): Float {
                                        val h = 120.dp.toPx() * (entries[i].second / maxV).coerceAtLeast(0f)
                                        return size.height - h
                                    }
                                    fun cx(i: Int) = slotW * i + gap * i + slotW / 2f

                                    if (opts.showTrendLine && n >= 3) {
                                        val xs = entries.indices.map { it.toDouble() }
                                        val ys = entries.map { it.second.toDouble() }
                                        val mx = xs.average()
                                        val my = ys.average()
                                        val num = xs.zip(ys).sumOf { p -> (p.first - mx) * (p.second - my) }
                                        val den = xs.sumOf { x -> (x - mx) * (x - mx) }
                                        if (den != 0.0) {
                                            val slope = num / den
                                            val intercept = my - slope * mx
                                            fun yPix(d: Double): Float {
                                                val h = (120.dp.toPx() * (d / maxV).toDouble()).coerceAtLeast(0.0)
                                                return (size.height - h).toFloat().coerceIn(0f, size.height)
                                            }
                                            val tp = Path()
                                            tp.moveTo(cx(0), yPix(intercept))
                                            tp.lineTo(cx(n - 1), yPix(intercept + slope * (n - 1)))
                                            drawPath(
                                                tp, trendColor,
                                                style = Stroke(
                                                    1.5.dp.toPx(),
                                                    pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f))
                                                )
                                            )
                                        }
                                    }
                                    if (opts.showValues) {
                                        entries.indices.forEach { i ->
                                            val v = entries[i].second
                                            val txt = if (v == v.toInt().toFloat()) v.toInt().toString() else "%.1f".format(v)
                                            val m = measurer.measure(AnnotatedString(txt), vStyle)
                                            drawText(
                                                measurer, AnnotatedString(txt),
                                                topLeft = Offset(
                                                    (cx(i) - m.size.width / 2f).coerceAtLeast(0f),
                                                    (topOf(i) - m.size.height - 4.dp.toPx()).coerceAtLeast(0f)
                                                ),
                                                style = vStyle
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            // 饼图单独给图例行：饼图没有 x 轴，类别名挤在柱子下面那一行毫无意义
            // （真机实测"工作:45"下面四个标签全是直接/社交/邮件/广告，和饼对不上）。
            // 带色块 + 全名 + 百分比，才看得出谁占多少。
            if (type == "pie" || type == "donut") {
                val totalPie = entries.sumOf { it.second.toDouble() }.coerceAtLeast(0.001)
                entries.forEachIndexed { idx, (k, v) ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            Modifier
                                .size(9.dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(palette[idx % palette.size])
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            k, style = MaterialTheme.typography.labelSmall,
                            color = labelColor, maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            "${(v / totalPie * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall, color = labelColor
                        )
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth()) {
                    entries.forEach { (k, v) ->
                        // 标签不再 take(4)：长类别名被砍成前 4 个字就认不出了。
                        // 允许换两行 + 省略号，宁可截断也不要错字。
                        Text(
                            k, style = MaterialTheme.typography.labelSmall,
                            color = labelColor, maxLines = 2, softWrap = true,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

/** HTML 实体解码（&nbsp; &lt; &gt; &amp; &quot; &#39;） */
private val HTML_ENTITIES = mapOf(
    "&nbsp;" to " ", "&lt;" to "<", "&gt;" to ">", "&amp;" to "&",
    "&quot;" to "\"", "&#39;" to "'", "&apos;" to "'", "&hellip;" to "…", "&mdash;" to "—"
)

private val HTML_TAG_RE = Regex("""<(/?)(b|strong|i|em|u|sub|sup|code|br)\b[^>]*>""", RegexOption.IGNORE_CASE)

/** 链接色的兜底（未传参的调用点维持旧蓝紫；composable 入口应传 scheme.primary） */
internal val InlineMdLinkFallback = Color(0xFF4A9EDA)

private val INLINE_LINK_RE = Regex("""\[([^\]]+)\]\(([^)\s]+)\)""")

/** inlineMd 结果缓存（2026-11-21 滚动掉帧根治①）：每行每次（重）组合都全量跑 HTML 实体
 *  替换 + 多轮标记递归 + 现场编译 linkRe——LazyColumn item 滑出即销毁，往复滚动每行重算。
 *  结果是不可变 AnnotatedString，按「链接色|文本」跨组合缓存（512 条 ≈ 一轮会话可见行）。 */
private val inlineMdCache = androidx.collection.LruCache<String, androidx.compose.ui.text.AnnotatedString>(512)

internal fun inlineMd(input: String, linkColor: Color = InlineMdLinkFallback): AnnotatedString {
    val key = "${linkColor.value}|$input"
    inlineMdCache.get(key)?.let { return it }
    val built = inlineMdUncached(input, linkColor)
    inlineMdCache.put(key, built)
    return built
}

/** 处理 HTML 标签、`行内码`、~~删除线~~、[链接](url)、**粗体** 与 *斜体* */
private fun inlineMdUncached(input: String, linkColor: Color): AnnotatedString = buildAnnotatedString {
    // v50 全局换色审查：本函数是**非 composable** 递归解析，取不到 MaterialTheme——
    // [链接](url) 的着色原来写死 #4A9EDA，换主题后笔记里的链接恒为旧蓝紫。
    // 现在 linkColor 由 composable 入口传 scheme.primary 传透（MarkdownMath 两处），
    // 递归分支全部透传；默认兜底维持旧行为。
    var text = input
    HTML_ENTITIES.forEach { (k, v) -> text = text.replace(k, v) }
    var rest = text
    while (true) {
        // HTML 标签：<br> 换行；<b>/<strong>/<i>/<em>/<u>/<sub>/<sup>/<code> 配对套样式
        val tag = HTML_TAG_RE.find(rest)
        if (tag != null) {
            val name = tag.groupValues[2].lowercase()
            // 2026-10-27：前缀段递归解析——否则夹在两个高优先级标记之间的低优先级
            // 富文本（如两个行内码之间的 **粗体**）会被原样吞掉（真机实拍复现）
            append(inlineMd(rest.substring(0, tag.range.first), linkColor))
            if (name == "br") {
                append("\n")
                rest = rest.substring(tag.range.last + 1)
                continue
            }
            val style = when (name) {
                "b", "strong" -> SpanStyle(fontWeight = FontWeight.Bold)
                "i", "em" -> SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic)
                "u" -> SpanStyle(textDecoration = TextDecoration.Underline)
                "sub", "sup" -> SpanStyle(fontSize = 11.sp, baselineShift = androidx.compose.ui.text.style.BaselineShift(if (name == "sup") 0.4f else -0.25f))
                "code" -> SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1F888888))
                else -> null
            }
            val closeIdx = rest.indexOf("</" + tag.groupValues[1] + name, tag.range.last + 1)
            if (style != null && closeIdx > 0) {
                val closeEnd = rest.indexOf('>', closeIdx)
                if (closeEnd > 0) {
                    pushStyle(style)
                    append(rest.substring(tag.range.last + 1, closeIdx))
                    pop()
                    rest = rest.substring(closeEnd + 1)
                    continue
                }
            }
            // 未配对或未知：丢弃标签本身
            rest = rest.substring(tag.range.last + 1)
            continue
        }
        // `code`（优先于其他标记，代码内不解析富文本）
        val code = rest.indexOf('`')
        if (code >= 0) {
            val end = rest.indexOf('`', code + 1)
            if (end > code + 1) {
                append(inlineMd(rest.substring(0, code), linkColor))
                pushStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0x1F888888)))
                append(rest.substring(code + 1, end))
                pop()
                rest = rest.substring(end + 1)
                continue
            }
        }
        // ~~删除线~~
        val strike = rest.indexOf("~~")
        if (strike >= 0) {
            val end = rest.indexOf("~~", strike + 2)
            if (end > strike + 2) {
                append(inlineMd(rest.substring(0, strike), linkColor))
                pushStyle(SpanStyle(textDecoration = TextDecoration.LineThrough))
                append(rest.substring(strike + 2, end))
                pop()
                rest = rest.substring(end + 2)
                continue
            }
        }
        // [文本](url)（仅样式着色）
        val link = INLINE_LINK_RE.find(rest)
        if (link != null) {
            append(inlineMd(rest.substring(0, link.range.first), linkColor))
            pushStyle(SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline))
            append(link.groupValues[1])
            pop()
            rest = rest.substring(link.range.last + 1)
            continue
        }
        val bold = rest.indexOf("**")
        if (bold >= 0) {
            val end = rest.indexOf("**", bold + 2)
            if (end > bold) {
                append(inlineMd(rest.substring(0, bold), linkColor))
                pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
                append(rest.substring(bold + 2, end))
                pop()
                rest = rest.substring(end + 2)
                continue
            }
        }
        val ital = rest.indexOf('*')
        if (ital >= 0) {
            val end = rest.indexOf('*', ital + 1)
            if (end > ital + 1) {
                append(inlineMd(rest.substring(0, ital), linkColor))
                pushStyle(SpanStyle(fontStyle = androidx.compose.ui.text.font.FontStyle.Italic))
                append(rest.substring(ital + 1, end))
                pop()
                rest = rest.substring(end + 1)
                continue
            }
        }
        // 2026-11-02：跨公式的粗体（`**…$公式$…**`）切段后每段只剩半个 **——
        // 旧代码跌进单 * 斜体分支把星号裸显。未配对的 ** 视为「粗体延续」：
        // 去掉符号、整段加粗（相邻公式两侧的段落因此视觉上连成一段粗体）
        val danglingBold = rest.indexOf("**")
        if (danglingBold >= 0) {
            append(inlineMd(rest.substring(0, danglingBold), linkColor))
            pushStyle(SpanStyle(fontWeight = FontWeight.Bold))
            append(rest.substring(danglingBold + 2).replace("**", ""))
            pop()
            break
        }
        append(rest)
        break
    }
}

// ==================== 无边记：无限画布 ====================
// InkStroke 与 JSON 序列化见 InkJson.kt（与 PDF 批注共用）

@Composable
fun FreeformScreen(noteId: Long, onDone: () -> Unit) {
    // 非一级界面：隐藏底部导航栏
    io.github.m0use001.planability.core.LockShellChrome()
    val scope = rememberCoroutineScope()
    val haptics = LocalHaptics.current
    var note by remember { mutableStateOf<NoteEntity?>(null) }
    var strokes by remember { mutableStateOf<List<InkStroke>>(emptyList()) }
    var liveStroke by remember { mutableStateOf<InkStroke?>(null) }
    var colorIdx by remember { mutableIntStateOf(0) }
    var strokeWidth by remember { mutableStateOf(5f) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var showClear by remember { mutableStateOf(false) }
    var dirty by remember { mutableStateOf(false) }
    // 对象层：文本 / 便利贴（存 content JSON）
    var objects by remember { mutableStateOf<List<FreeformObj>>(emptyList()) }
    var editObj by remember { mutableStateOf<FreeformObj?>(null) }
    var addType by remember { mutableStateOf<String?>(null) }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }

    LaunchedEffect(noteId) {
        note = Graph.notes.noteById(noteId)
        note?.let {
            strokes = parseInk(it.inkJson)
            objects = objectsFromJson(it.content)
        }
    }

    fun save() {
        val n = note ?: return
        scope.launch {
            Graph.notes.saveNote(
                n.copy(
                    inkJson = inkToJson(strokes),
                    content = objectsToJson(objects),
                    title = n.title.ifBlank { "手绘" }
                )
            )
        }
        dirty = false
    }

        // fullscreen=true：无限画布要整屏
        PredictiveBackOverlay(
            onBack = { if (dirty) save(); onDone() },
            enter = OverlayEnter.Forward,
            fullscreen = true
        ) {
        Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
            // 顶栏
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp)
            ) {
                // overlayClose 必须在组合期读一次存下来：放进 onClick 里读是 @Composable getter，编译不过
                val overlayClose = LocalOverlayClose.current
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); overlayClose() }) {
                    Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                }
                Text("无边记", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(
                    onClick = { addType = "text" },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("文本") }
                TextButton(
                    onClick = { addType = "sticky" },
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) { Text("便利贴") }
                IconButton(onClick = {
                    if (strokes.isNotEmpty()) {
                        strokes = strokes.dropLast(1)
                        dirty = true
                        haptics.perform(HapticEngine.Kind.Tap)
                    }
                }) { Icon(Icons.Rounded.Refresh, "撤销") }
                IconButton(onClick = { showClear = true }) {
                    Icon(Icons.Rounded.Delete, "清空")
                }
            }
            // 工具条
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            ) {
                SeedColors.inkPalette.forEachIndexed { idx, c ->
                    Box(
                        Modifier
                            .size(if (colorIdx == idx) 26.dp else 22.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(c)
                            .clickable { colorIdx = idx; haptics.perform(HapticEngine.Kind.Select) }
                    )
                }
                Spacer(Modifier.width(8.dp))
                Text("粗细", style = MaterialTheme.typography.labelSmall)
                // 笔粗滑块：按"跨过一档"振一下（Select），不是每次 onValueChange 都振——
                // 一次拖动能产生上百个回调，每次都振只会连成一片麻木（见 core/Haptics.kt 的 HapticTicks）
                val strokeTicks = remember(haptics) { HapticTicks(haptics, 1f) }
                Slider(
                    value = strokeWidth,
                    onValueChange = { strokeWidth = it; strokeTicks.onValue(it) },
                    valueRange = 2f..18f, modifier = Modifier.weight(1f)
                )
            }
            // 画布（笔迹 Canvas + 对象层共享画布坐标系）
            Box(
                Modifier
                    .fillMaxSize()
                    .weight(1f)
                    .background(MaterialTheme.colorScheme.surfaceContainerLowest)
                    .onSizeChanged { viewSize = it }
            ) {
                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(Unit) {
                            awaitEachGesture {
                                val first = awaitFirstDown(requireUnconsumed = false)
                                var current: FloatArray? = null
                                var transformed = false
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val pressed = event.changes.filter { it.pressed }
                                    if (pressed.size >= 2) {
                                        transformed = true
                                        liveStroke = null
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        val newScale = (scale * zoom).coerceIn(0.25f, 6f)
                                        // 缩放围绕手势中心
                                        val focus = event.calculateCentroid()
                                        val focusInCanvas = (focus - offset) / scale
                                        scale = newScale
                                        offset = focus - focusInCanvas * scale + pan
                                        event.changes.forEach { it.consume() }
                                    } else if (pressed.size == 1 && !transformed) {
                                        val p = (pressed[0].position - offset) / scale
                                        if (current == null) {
                                            current = floatArrayOf(p.x, p.y)
                                        } else {
                                            current = (current ?: FloatArray(0)) + floatArrayOf(p.x, p.y)
                                        }
                                        liveStroke = InkStroke(colorIdx, strokeWidth, current ?: FloatArray(0))
                                        pressed[0].consume()
                                    }
                                    if (pressed.isEmpty()) break
                                }
                                val c = current
                                if (!transformed && c != null && c.size >= 4) {
                                    strokes = strokes + InkStroke(colorIdx, strokeWidth, c.copyOf())
                                    dirty = true
                                    haptics.perform(HapticEngine.Kind.Tap)
                                }
                                liveStroke = null
                            }
                        }
                ) {
                    drawInk(strokes, liveStroke, scale, offset)
                }
                // 对象层：与笔迹同坐标系（拖拽以 scale 折算回画布坐标）
                Box(
                    Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationX = offset.x
                            translationY = offset.y
                            scaleX = scale
                            scaleY = scale
                        }
                ) {
                    objects.forEach { o ->
                        val stickyColor = SeedColors.inkPalette.getOrNull(o.hue % SeedColors.inkPalette.size)
                            ?: Color(0xFFFFF59D)
                        Box(
                            Modifier
                                .absoluteOffset { IntOffset(o.x.roundToInt(), o.y.roundToInt()) }
                                .widthIn(min = 48.dp, max = 220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(
                                    if (o.type == "sticky") stickyColor.copy(alpha = 0.92f)
                                    else Color.Transparent
                                )
                                .then(
                                    if (o.type == "sticky") Modifier
                                    else Modifier.border(
                                        1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(10.dp)
                                    )
                                )
                                .pointerInput(o.id) {
                                    detectDragGestures(
                                        onDragStart = { haptics.perform(HapticEngine.Kind.Tap) },
                                        onDrag = { change, dragAmount ->
                                            objects = objects.map {
                                                if (it.id == o.id) {
                                                    it.copy(
                                                        x = it.x + dragAmount.x / scale,
                                                        y = it.y + dragAmount.y / scale
                                                    )
                                                } else it
                                            }
                                            dirty = true
                                            change.consume()
                                        }
                                    )
                                }
                                .clickable { editObj = o }
                                .padding(8.dp)
                        ) {
                            Text(
                                o.text,
                                fontSize = 14.sp,
                                color = if (o.type == "sticky") Color(0xFF333333)
                                else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }

    // 添加对象：置于当前视口中心
    addType?.let { type ->
        var text by remember(type) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { addType = null },
            modifier = Modifier.motionEnter(),
            title = { Text(if (type == "text") "添加文本" else "添加便利贴") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text("内容") }) },
            confirmButton = {
                TextButton(onClick = {
                    if (text.isNotBlank()) {
                        val cx = (viewSize.width / 2f - offset.x) / scale
                        val cy = (viewSize.height / 2f - offset.y) / scale
                        objects = objects + FreeformObj(
                            id = System.currentTimeMillis(), type = type,
                            x = cx, y = cy, text = text.trim(), hue = colorIdx
                        )
                        dirty = true
                    }
                    addType = null
                }) { Text("添加") }
            },
            dismissButton = { TextButton(onClick = { addType = null }) { Text("取消") } }
        )
    }

    // 编辑/删除对象
    editObj?.let { o ->
        var text by remember(o.id) { mutableStateOf(o.text) }
        AlertDialog(
            onDismissRequest = { editObj = null },
            modifier = Modifier.motionEnter(),
            title = { Text(if (o.type == "text") "编辑文本" else "编辑便利贴") },
            text = { OutlinedTextField(value = text, onValueChange = { text = it }) },
            confirmButton = {
                TextButton(onClick = {
                    objects = objects.map { if (it.id == o.id) it.copy(text = text) else it }
                    dirty = true
                    editObj = null
                }) { Text("保存") }
            },
            dismissButton = {
                TextButton(onClick = {
                    objects = objects.filter { it.id != o.id }
                    dirty = true
                    editObj = null
                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
            }
        )
    }

    if (showClear) {
        ConfirmDialog(
            title = "清空画布",
            text = "将删除画布上的全部笔迹，不可恢复。",
            onConfirm = { strokes = emptyList(); dirty = true },
            onDismiss = { showClear = false }
        )
    }
}

/** 无边记对象：文本 / 便利贴（坐标为画布坐标，存笔记 content JSON） */
data class FreeformObj(
    val id: Long,
    val type: String,
    val x: Float,
    val y: Float,
    val text: String,
    val hue: Int = 0
)

private fun objectsToJson(list: List<FreeformObj>): String {
    val arr = org.json.JSONArray()
    list.forEach { o ->
        arr.put(
            org.json.JSONObject()
                .put("id", o.id).put("type", o.type)
                .put("x", o.x.toDouble()).put("y", o.y.toDouble())
                .put("text", o.text).put("hue", o.hue)
        )
    }
    return arr.toString()
}

private fun objectsFromJson(json: String): List<FreeformObj> = runCatching {
    val arr = org.json.JSONArray(json)
    (0 until arr.length()).map { i ->
        val o = arr.getJSONObject(i)
        FreeformObj(
            id = o.getLong("id"), type = o.optString("type", "text"),
            x = o.optDouble("x", 0.0).toFloat(), y = o.optDouble("y", 0.0).toFloat(),
            text = o.optString("text"), hue = o.optInt("hue", 0)
        )
    }
}.getOrDefault(emptyList())

private fun androidx.compose.ui.input.pointer.PointerEvent.calculateCentroid(): Offset {
    val changes = changes.filter { it.pressed }
    if (changes.isEmpty()) return Offset.Zero
    var x = 0f; var y = 0f
    changes.forEach { x += it.position.x; y += it.position.y }
    return Offset(x / changes.size, y / changes.size)
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawInk(
    strokes: List<InkStroke>,
    live: InkStroke?,
    sc: Float,
    offset: Offset
) {
    translate(offset.x, offset.y) {
        scale(sc, pivot = Offset.Zero) {
            (strokes + listOfNotNull(live)).forEach { s ->
                if (s.points.size >= 4) {
                    val path = Path()
                    path.moveTo(s.points[0], s.points[1])
                    var i = 2
                    while (i + 3 < s.points.size) {
                        val midX = (s.points[i] + s.points[i + 2]) / 2
                        val midY = (s.points[i + 1] + s.points[i + 3]) / 2
                        path.quadraticBezierTo(s.points[i], s.points[i + 1], midX, midY)
                        i += 2
                    }
                    path.lineTo(s.points[s.points.size - 2], s.points[s.points.size - 1])
                    drawPath(
                        path,
                        color = SeedColors.inkPalette[s.colorIdx % SeedColors.inkPalette.size],
                        style = Stroke(width = s.width, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
                    )
                }
            }
        }
    }
}
