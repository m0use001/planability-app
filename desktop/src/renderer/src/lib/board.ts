/**
 * 宽屏板材质常量 —— 直译手机 `ui/common/SplitBoard.kt` 的 `BoardLayout`
 * + `ui/common/AdaptivePane.kt` 的宽屏列宽常量。
 *
 * 为什么单独一个文件：全 app 的板圆角 / 缝 / 详情宽 / 断点只从这里取（守卫钉字面量不散落）。
 * 数值与断点逐字相同（PC 桌面 1dp≈1px），差异只在宿主语言。改这里 = 全 app 板材质跟着变。
 *
 * 对应手机件：`BoardLayout`（Shape/Gap/Padding/DetailWidth/ContentDetailWidth/MinListWidth/RowDividerInset）
 * + `WideSecondaryWidth / WideWorkbenchMaxWidth / WideTimelineMaxWidth / WideCalendarMaxWidth`。
 * 新增 `contentMaxWidth` / `assistantBodyWidth` 对应会话页限宽口径（手机 ChatWideLayout）。
 */

/** 板几何（对应 `BoardLayout`）。单位 px（=dp）。 */
export const BOARD = {
	/** 两块板的圆角。 */
	shape: 24,
	/** 栏间白缝：分隔靠留白与底色，不靠线。 */
	gap: 12,
	/** 板内统一内缩（板头、分节内容）。 */
	padding: 16,
	/** 表单/列表型详情板宽度：与首页/计划页侧栏同宽，全 app 只有一种「详情宽」心智。 */
	detailWidth: 380,
	/** 内容型详情板（文件预览、笔记编辑器）的最大宽度——留给正文的读长。 */
	contentDetailWidth: 480,
	/** 左板最小可用宽度：比一部手机的内容宽度略宽，分组列表才读得开。 */
	minListWidth: 380,
	/** 宽屏「扁平行」之间的 1dp 细线两端内缩量。 */
	rowDividerInset: 14,
} as const;

/** 宽屏各列宽（对应 `AdaptivePane.kt` 常量）。 */
export const WIDE = {
	/** 宽屏第二栏（今日侧栏）的固定宽度。 */
	secondaryWidth: 380,
	/** 宽屏主列（工作台首页左栏）的最大宽度。 */
	workbenchMaxWidth: 960,
	/** 单日时间轴列的最大宽度。 */
	timelineMaxWidth: 680,
	/** 日历月视图的最大宽度。 */
	calendarMaxWidth: 620,
	/** 聊天消息列表限宽（会话页内容列）。 */
	contentMaxWidth: 880,
	/** 助手正文最大宽度（浮窗保持 760，会话页可传 null 撑满列）。 */
	assistantBodyWidth: 760,
	/** 计划页分组列表限宽（spec §5 计划行）。 */
	planListMaxWidth: 760,
	/** 我的页双板门槛 = 内容宽 ≥820（spec §5：页面级双板门槛，壳层 Rail 才是 840）。 */
	profileTwoPaneMinWidth: 820,
	/** 宽屏二级页保留模态形态时的居中板卡宽（spec §1：PredictiveBackOverlay v58 口径）。 */
	modalCardWidth: 1000,
} as const;
