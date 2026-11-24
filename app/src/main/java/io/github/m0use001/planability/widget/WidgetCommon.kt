package io.github.m0use001.planability.widget

import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.text.Text

private const val PKG = "io.github.m0use001.planability"
private const val MAIN = "io.github.m0use001.planability.MainActivity"

/**
 * 打开主界面，并可带上「要落到哪个 Tab / 哪个子页」。
 *
 * 为什么不直接写死一个 `OPEN_INTENT` 常量：六个组件对用户来说指向的东西不一样——
 * 点「今日总览」想看的是待办，点「今日日程」想看的是日程表，点「快捷指令」想看的是自动化。
 * 全都只打开首页等于让用户每次都自己再点两下，那不叫深链。
 *
 * 实现上复用 App 里已有的 `Router` 通道：自定义 action + 两个 extra，
 * `MainActivity.handleShortcut` 读到之后设 `Router.targetTab` / `pendingPlanSubTab`。
 * **不带 tab 时（tab == null）行为和旧版完全一致**（ACTION_MAIN + LAUNCHER），
 * 所以钉到桌面的快捷方式、通知跳转都不受影响。
 */
private fun openIntent(tab: String?, planSub: String?, open: String?): Intent {
    val i = Intent(Intent.ACTION_MAIN)
        .addCategory(Intent.CATEGORY_LAUNCHER)
        .setClassName(PKG, MAIN)
    if (tab != null) {
        i.action = io.github.m0use001.planability.app.Router.ACTION_WIDGET_TAB
        i.putExtra(io.github.m0use001.planability.app.Router.EXTRA_TAB, tab)
        if (planSub != null) {
            i.putExtra(io.github.m0use001.planability.app.Router.EXTRA_PLAN_SUB, planSub)
        }
        // 2026-11-01：页内覆盖层直达（倒数日管理 / 月历）。tab 只到一级页，覆盖层要靠它。
        if (open != null) {
            i.putExtra(io.github.m0use001.planability.app.Router.EXTRA_WIDGET_OPEN, open)
        }
    }
    return i
}

/**
 * 点击小组件打开主界面。
 *
 * @param tab     落到哪个一级页（null = 只打开应用，同旧行为）
 * @param planSub 计划页内的分段（"todo" / "schedule"）
 * @param open    页内覆盖层直达："countdown"（倒数日管理）/ "calendar"（月历）
 */
fun GlanceModifier.clickOpenApp(
    tab: String? = null,
    planSub: String? = null,
    open: String? = null
): GlanceModifier = this.clickable(actionStartActivity(openIntent(tab, planSub, open)))

/**
 * 小组件骨架（Material 3 Expressive）。
 *
 * ## 与旧版的三点不同
 * 1. **没有左侧 4dp 竖色条了**。那是「装饰性强调」的老思路，M3 Expressive 用
 *    **层次色块**（`surfaceContainer*` 的 tonal block）和**胶囊 chip**来表达层级。
 * 2. **整版不再铺不透明底色**（旧版铺 `#17171C`）。启动器自己已经给小组件画了
 *    一层带圆角的容器，铺满等于把圆角盖成直角；现在只铺一层半透明 surface
 *    （见 `WidgetTheme.shell`），圆角透出来，压在壁纸上也仍然可读。
 * 3. **色板从外面传进来**而不是全局单例。旧版 `WidgetColors` 是写死的常量对象，
 *    用户换主题色时应用内变了、桌面纹丝不动；现在 `WidgetTheme.palette()` 运行时
 *    从 `theme.seed` 推导，每个组件拿到的都是当下这一份。
 *
 * `content` 拿到的 `WidgetPalette` 就是本组件该用的那套颜色与字阶——
 * 传参而不是用 CompositionLocal，是为了避开 Glance 组合里自定义 local 的坑，
 * 也让人一眼看出「这个组件用的是哪份色板」。
 */
@Composable
fun WidgetShell(
    title: String,
    subtitle: String = "",
    /** 点组件的空白处落到哪个 tab（null = 只打开主界面，同旧行为） */
    openTab: String? = null,
    /** 落点是计划页时，切到哪个子页："todo" / "schedule" */
    planSub: String? = null,
    content: @Composable (WidgetPalette) -> Unit
) {
    // 字号跟着真实尺寸走：同一个 3×2 上合适，拉到 4×3 就显小、缩到 2×2 会溢出。
    // Glance 没有自适应字号，所以由 WidgetTheme 按高度换算缩放因子（0.88–1.32）。
    val size = LocalSize.current
    val p = WidgetTheme.palette(LocalContext.current, size)

    // 「透明外框 + 悬浮卡」在 Glance 1.1.1 上**做不到**，两条路都真机试过并失败：
    //  ① `.padding(10.dp).widgetCard()`——Glance 把所有 modifier 施加在**同一个 View** 上，
    //     padding 只缩内容区、背景仍铺满整个 View；
    //  ② 外面套一层 padding 的 Box——子节点是 fillMaxSize，卡片位置毫无变化；
    //  ③ 按 `LocalSize` 显式算尺寸（-20dp）再居中——**`LocalSize` 报的比真实格子小**，
    //     卡片会缩成一小块，三格统计直接被挤成省略号（真机截图对照过）。
    // 结论：老老实实 fillMaxSize 铺满格子。Android 12+ 启动器本来就会把 RemoteViews
    // 裁成圆角矩形，所以「异形」只能体现在**格子内部的形状**（圆角块、胶囊、正圆盘），
    // 而不是格子本身的轮廓。真正的圆角由 `WidgetTheme` 里的 `cornerRadius` 负责。
    // 外壳 = 主色描边 + 半透明 surface 卡（拼色规则 R10：原生小组件用亮色描边包住整块，
    // 而不是把整块涂成主色）。Glance 1.1.1 没有 border modifier（javap 实查只有
    // background/cornerRadius），所以描边用「外层铺主色 + 内层 padding 1.5dp 铺卡片色」
    // 叠出来；内层也 fillMaxSize，padding 由外层缩进。
    Box(
        GlanceModifier
            .fillMaxSize()
            .background(p.accent)
            .cornerRadius(28.dp)
            .clickOpenApp(openTab, planSub)
    ) {
        Box(
            GlanceModifier
                .fillMaxSize()
                .padding(1.5.dp)
                .widgetCard(p)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column(GlanceModifier.fillMaxSize()) {
                Row(
                    GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically
                ) {
                    Text(
                        title,
                        style = p.title,
                        maxLines = 1,
                        modifier = GlanceModifier.defaultWeight()
                    )
                    // 副标题收成右上角的胶囊 chip，而不是和标题挤在一行的灰色小字
                    if (subtitle.isNotEmpty()) {
                        Box(
                            GlanceModifier
                                .widgetPill(p)
                                .padding(horizontal = 8.dp, vertical = 3.dp)
                        ) {
                            Text(subtitle, style = p.labelOnChip, maxLines = 1)
                        }
                    }
                }
                Spacer(GlanceModifier.height(8.dp))
                content(p)
            }
        }
    }
}