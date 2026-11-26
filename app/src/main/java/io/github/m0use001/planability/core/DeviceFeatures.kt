package io.github.m0use001.planability.core

import androidx.compose.runtime.Composable

/**
 * 可选设备功能包的**唯一接缝**。
 *
 * 背景：采集系统（消息采集/提纯、群聊摘要）与设备控制栈（◆、shell、phone_*
 * 手机操控、读屏通道）是私密功能，永不进开源版；但它们的代码过去散落在主模块各处，
 * 每次开源发布都要"删文件+手工摘引用"（拆弹）。根治后全部私密代码集中在
 * 一个可选源集（与 main 同一编译单元，可直接用 Graph/ChatEngine 等公共件），
 * 主模块**只通过本对象消费**它们，且全部槽位有 NoOp 兜底：
 *
 * - 私仓：`DeviceFeaturesInstaller`（ContentProvider，App.onCreate 前自动运行）调
 *   [install] 注册真实实现 → [installed]=true，功能全量可用（行为与重构前一致）；
 * - 精简版：导出脚本剔除该可选源集与相应清单声明
 *   → install 从不发生 → 全槽位 NoOp，相关入口按 [installed]=false 隐藏（显式降级，
 *   不假装工作）。
 *
 * 铁律：
 * 1. 主模块**禁止 import 任何可选功能符号**——边界守卫测试会红；
 * 2. 新增可选功能 = 实现放可选源集 + 在这里加一个槽位（接口先落在主模块）；
 * 3. 槽位只许 `private set`，唯一写入点是 [install]（由可选包的安装器调）。
 */
object DeviceFeatures {

    /** 私密模块已安装（真实实现已注册）。公开版恒 false。 */
    var installed: Boolean = false
        private set

    /**
     * 读屏/无障碍通道网关（B2 接线）：前台检测、屏幕文本抓取、截屏等原来直接调
     * 读屏采集服务实例的公开功能（AutoReturn/AutomationSignal/
     * ActionRegistry/A11yWatch/FloatChatActivity）一律改走这里。NoOp = 能力不可用，
     * 调用方按返回值显式降级（不假装工作）。
     */
    var a11y: A11yGateway = A11yGateway.NoOp
        private set

    /**
     * AI 工具贡献（B1 接线）：私密工具（◆ / ◆ / phone_*）的
     * schema 定义、执行器、◆ 就绪门控、中文名映射都从这里注入。
     * null（公开版）= 工具清单里根本没有这些工具——模型"看不见"，而非"调了失败"。
     */
    var tools: ToolContribution? = null
        private set

    /**
     * 私密页面槽位（B3 接线）：群聊摘要、手机操控、信息采集三个页面的 @Composable。
     * NoOp = 槽位为 null，入口处按 [installed] 隐藏。
     */
    var screens: ScreenSlots = ScreenSlots()
        private set

    /**
     * 一次性安装（可选包的安装器调用）。幂等：重复安装以最后者为准，
     * 但 [installed] 只会翻真。
     */
    fun install(
        a11y: A11yGateway,
        tools: ToolContribution?,
        screens: ScreenSlots,
        debugHooks: DebugHooks = DebugHooks(),
    ) {
        this.a11y = a11y
        this.tools = tools
        this.screens = screens
        this.debugHooks = debugHooks
        installed = true
    }

    /** 调试钩子（仅 debug 的 am start 动作；公开版恒空实例 = 钩子静默）。 */
    class DebugHooks {
        /** DEBUG_PURIFY：跑一遍群提纯。 */
        var purifyOnce: (suspend () -> String)? = null

        /** DEBUG_RESET_CAPTURE：完整重置信息提取。 */
        var resetCapture: (suspend () -> String)? = null
    }

    var debugHooks: DebugHooks = DebugHooks()
        private set

    /**
     * 读屏/无障碍能力网关（真实实现 = 读屏服务适配层）。
     *
     * 公开功能（自动化动作 a11y_*、AutoReturn 前台回退、AutomationSignal 应用观察、
     * A11yWatch 判活、悬浮聊天截屏）需要「读屏服务」这个**通用通道**，但服务类本身属
     * 私密采集栈。网关就是这一层的接口——主模块只认它，公开版全 NoOp（能力不可用，
     * 调用方按 null/false 显式降级）。
     */
    interface A11yGateway {
        /** 读屏服务当前是否已绑定（设置里已开启且连接成功）。 */
        val isBound: Boolean

        /** 读屏服务组件类名（A11yWatch 判活用）；NoOp 返回空串。 */
        val serviceClassName: String

        /** 抓当前屏幕整帧位图；不可用返回 null。 */
        fun takeScreenBitmap(): android.graphics.Bitmap?

        /** 抓当前屏幕可读文本；不可用返回空串。 */
        fun captureScreenText(): String

        /** 当前前台应用包名；不可用返回 null。 */
        fun currentForegroundPkg(): String?

        /** 前台应用切换事件流（AutoReturn/AutomationSignal 观察用）；NoOp 返回空流。 */
        val windowEvents: kotlinx.coroutines.flow.Flow<String>

        /** 屏幕元素快照（文本 + 坐标）。 */
        fun captureNodes(): List<A11yNode>

        /** 按谓词找可访问性节点（自动化 ◆_text 用）；不可用返回空表。 */
        fun findNodesByText(text: String): List<A11yNodeHandle>

        /** 点按节点（优先控件自带的 ACTION_CLICK）；参数不适用时返回 false。 */
        fun tapNode(handle: A11yNodeHandle): Boolean

        /** 按坐标点按。 */
        fun tapPoint(x: Float, y: Float): Boolean

        /** 原始滑动手势（自动化 a11y_scroll 用）。 */
        fun swipeRaw(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean

        /** 全局动作（GLOBAL_ACTION_HOME/BACK 等；AutoReturn 回桌面用）。 */
        fun pressGlobal(action: Int): Boolean

        /** 系统返回键。 */
        fun goBack(): Boolean

        companion object {
            val NoOp: A11yGateway = object : A11yGateway {
                override val isBound: Boolean = false
                override val serviceClassName: String = ""
                override fun takeScreenBitmap(): android.graphics.Bitmap? = null
                override fun captureScreenText(): String = ""
                override fun currentForegroundPkg(): String? = null
                override val windowEvents: kotlinx.coroutines.flow.Flow<String> =
                    kotlinx.coroutines.flow.emptyFlow()
                override fun captureNodes(): List<A11yNode> = emptyList()
                override fun findNodesByText(text: String): List<A11yNodeHandle> = emptyList()
                override fun tapNode(handle: A11yNodeHandle): Boolean = false
                override fun tapPoint(x: Float, y: Float): Boolean = false
                override fun swipeRaw(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean = false
                override fun pressGlobal(action: Int): Boolean = false
                override fun goBack(): Boolean = false
            }
        }
    }

    /** 屏幕元素快照（网关口径，与采集服务的 ANode 解耦）。 */
    data class A11yNode(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    /** 可访问性节点句柄（不透明包装；只有设备包的 tapNode 认识它）。 */
    interface A11yNodeHandle {
        /** 展示用文本（text 或 contentDescription，截短前的原文）。 */
        val label: String
    }

    /**
     * AI 工具贡献包：私密工具的 schema/执行/门控/展示四合一。
     * 真实实现在可选包；公开版为 null（工具不出现在模型工具清单）。
     */
    interface ToolContribution {
        /**
         * 私密工具的 JSON schema 数组（org.json；构造自 ToolDef 定义——定义本体
         * 也住在可选源集，shared 的 ToolRegistry 只留公开工具）。
         */
        fun extraToolSchemas(): org.json.JSONArray

        /** 私密工具域/插件名集合（进 ToolRegistry 的 DOMAINS/PLUGINS 联动与设置 UI）。 */
        fun extraDomains(): List<String>
        fun extraPlugins(): List<String>

        /**
         * 执行入口：返回 null = 本包不认识这个工具名（主执行器继续走原 when）。
         * 认识则执行并返回结果。分支体多为挂起 IO（读屏/截屏/手势），签名 suspend；
         * 结果类型用主分发链同款 ToolExecResult（同模块可见）。
         */
        suspend fun execute(name: String, a: org.json.JSONObject): io.github.m0use001.planability.ai.ptl.ToolExecResult?

        /** ◆ 桥是否就绪（ChatEngine 注入 ◆ 前的门控）；公开版恒 false。 */
        fun shellBridgeReady(): Boolean

        /** 工具名 → 中文短标签（工具行 UI）；未收录返回 null 走通用渲染。 */
        fun toolLabel(name: String): String?
    }

    /** 私密页面槽位：每个槽位 null = 该页面不存在，入口隐藏。回调由宿主页面传入。 */
    class ScreenSlots {
        /** 群聊摘要页（GroupDigestScreen）。 */
        var groupDigest: (@Composable (
            onDone: () -> Unit,
            onOpenManual: () -> Unit,
            onOpenSession: (String) -> Unit,
        ) -> Unit)? = null

        /** 手机操控区域页。 */
        var phoneUseRegion: (@Composable (onDone: () -> Unit) -> Unit)? = null

        /** 信息采集设置页（InfoCaptureScreen）。 */
        var infoCapture: (@Composable (onDone: () -> Unit) -> Unit)? = null

        /** 悬浮采集开关（FloatCapture.show/hide 的语义包装）；null = 功能不存在。 */
        var floatCaptureToggle: ((show: Boolean) -> Unit)? = null

        /**
         * 抓取今日睡眠（HomeScreen 睡眠卡的"重抓"）：成功返回快照，失败返回 null
         * 并把原因写进 [sleepFailureText]。公开版 null = 卡片隐藏。
         */
        var captureSleep: (suspend () -> SleepSnapshot?)? = null

        /** 睡眠采集最近一次失败原因；null = 无。 */
        var sleepFailureText: (() -> String?)? = null
    }

    /** 今日睡眠卡片数据（HomeScreen 睡眠卡消费；无数据 = 卡片隐藏）。 */
    data class SleepSnapshot(
        val durationMin: Int,
        val durationText: String,
        val fallAsleep: String,
        val wakeUp: String,
        val dateText: String,
    )
}
