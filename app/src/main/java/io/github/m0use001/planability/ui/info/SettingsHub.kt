package io.github.m0use001.planability.ui.info

/**
 * v50.5：本文件原有的两个合并入口已**迁走**——实现见 [AiSettingsHubs]（ModelSettingsHub
 * 扩到三 tab、新增 ToolsHub）。这里只留文件与这份迁移说明，避免老文档/日志里的
 * 「见 ui/info/SettingsHub.kt」指针全部失效。
 *
 * ## 为什么当初要合并（历史，仍然成立）
 *
 * 「模型设置」「智能体设置」原本是抽屉里的两行——同属模型域、都要在顶部切换查看，
 * 拆成两行只是多一次跳转。合并成一屏、顶部按钮切换。
 *
 * ## 为什么不把 topBar 抽出来重写
 *
 * [io.github.m0use001.planability.ui.profile.ModelManagerScreen] 与 [AssistantsScreen] 各自都有
 * 完整 Scaffold + 返回键 + 轮询加载。抽 topBar 等于改两个长页面，收益只是省一行；
 * 所以只在标题栏下方插一行切换器，两个页面**原样复用**。
 *
 * ## 那条踩了一整轮的真机坑（务必别重蹈）
 *
 * 切换器是**函数**而不是直接摆在容器里的 Composable：两个被嵌页面都包在
 * PredictiveBackOverlay 里，而那个 overlay 用的是 Dialog —— **独立窗口**。
 * 把切换器画在主窗口的 Column 里，布局实测完全正常（size/pos 都对），但屏幕上
 * **完全看不到** —— Dialog 永远盖在主窗口之上。必须把切换器交给被嵌页面，
 * 由它画在自己的 Scaffold topBar 里（Dialog 内部）。
 *
 * ## 为什么 Hub 只套一层 overlay
 *
 * 切 tab 是 `when(tab)` 换内容。若被嵌页面各自再套一层 overlay，一切换就是两个
 * Dialog 窗口**接连关掉再打开**，宿主窗口从背后透出一帧 —— 用户看到的"页面闪一下"。
 * 所以 Hub 在最外层只套一层，各页面收 insideOverlay=true 只画内容。
 *
 * ## 切换时保留各自状态吗
 *
 * 不保留。两个页面都只是一份编辑表单，没有进行中的长任务，重建代价可忽略；
 * 反过来常驻多个 Screen 会让各自的 LaunchedEffect 轮询在后台一直跑。
 * （Hub 自己选中的 tab 是记忆的，见 [AiSettingsHubs] 的 KEY_*_TAB。）
 */
internal object SettingsHubNotes
