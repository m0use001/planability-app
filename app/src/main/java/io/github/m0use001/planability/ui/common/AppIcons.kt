package io.github.m0use001.planability.ui.common

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.FactCheck
import androidx.compose.material.icons.automirrored.rounded.ShowChart
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Article
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Autorenew
import androidx.compose.material.icons.rounded.BarChart
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.ChatBubble
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronLeft
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.CropFree
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.DriveFileRenameOutline
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Extension
import androidx.compose.material.icons.rounded.FilterAlt
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderCopy
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Hub
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.InsertDriveFile
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Memory
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.OpenInNew
import androidx.compose.material.icons.rounded.PhoneAndroid
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.MonitorHeart
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Slideshow
import androidx.compose.material.icons.rounded.SmartToy
import androidx.compose.material.icons.rounded.Storage
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material.icons.rounded.Upload
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.ui.graphics.vector.ImageVector
import io.github.m0use001.planability.data.WorkbenchIcons

/**
 * # 图标词汇表（唯一入口）
 *
 * 为什么要有这么一个 object：全仓库一度有 110 个不同图标 / 432 个调用点，
 * 同一个图标被拿来表示好几件事（`Info` 既是记忆库入口又是文件详情，
 * `BarChart` 既是使用统计又是图表默认，`FolderOff` 既是未分类又是旧导出目录），
 * 用户看到的是"同一个图形忽左忽右地换意思"。这里把**一个语义钉死成一个符号**，
 * 以后加界面先在下面找，找不到再加（并写清楚它代表什么）。
 *
 * ## 三条硬规则
 *
 * 1. **同语义必同图标**：会话永远是 [Kind.Chat]，记忆永远是 [Kind.Memory]，
 *    不允许 A 屏用齿轮、B 屏用芯片表示同一件事。
 * 2. **不同语义不共用图标**：`Folder` 家族按"是不是真的文件夹"分工（见 [Kind]），
 *    `CheckCircle` 只表示"已完成"，"待处理"另给 [State.Pending]。
 * 3. **同屏不重复**：一眼扫过去，同屏出现的两个图形必须能读出两种意思。
 *
 * ## 配色纪律（R11：装饰中性，主色只给"有话说"的图标）
 *
 * - **装饰性图标一律中性 `onSurfaceVariant`**：导航（返回/翻页/关闭/搜索/展开收起/更多）、
 *   行首类型标记（文件/项目/会话/助手/来源/模型）、卡头快捷动作、文件类型图标；
 * - **主色只给三类**：①当前选中/已勾选（✓ 当前使用、Checkbox、开关、选中 pill）；
 *   ②主操作（FAB、运行/确认/重新生成）；③状态高亮（正在运行、当前时刻、连接成功）；
 * - **危险操作保持 `error` 红**：[Act.Delete] 一类必须与"确认"的主色区分开，不为凑比例而改；
 * - **深色底上的图标（FAB、primaryContainer 容器）不写 tint**，交给 `LocalContentColor`；
 * - **禁用态**用同色透明度表达（`onSurfaceVariant.copy(alpha = 0.38f)`），不引入新灰阶；
 * - `tertiary` 只用于非交互状态容器（评分胶囊、组件徽标），图标上不要用；
 * - 分组/小节标题的文字仍是主色（原生 Gboard 证据），但**标题旁的图形不必跟着染色**。
 */
object AppIcons {

    // ==================== 导航：只回答"我在哪一层" ====================
    object Nav {
        /** 返回上一层（TopAppBar / 覆盖层的返回箭头） */
        val Back: ImageVector = Icons.AutoMirrored.Rounded.ArrowBack

        /** 关闭当前浮层/抽屉（≠ 返回：它是"这层不要了"） */
        val Close: ImageVector = Icons.Rounded.Close

        /** 翻页·翻期：上一页 / 上一月（**不是返回**，所以绝不用箭头） */
        val Prev: ImageVector = Icons.Rounded.ChevronLeft

        /** 翻页·翻期：下一页 / 下一月 */
        val Next: ImageVector = Icons.Rounded.ChevronRight

        /** 展开一个可折叠分组 */
        val Expand: ImageVector = Icons.Rounded.ExpandMore

        /** 收起一个可折叠分组 */
        val Collapse: ImageVector = Icons.Rounded.ExpandLess

        /** 向下（滚动到底 / 下移一项）：chevron 形态，比整支箭头轻 */
        val Down: ImageVector = Icons.Rounded.KeyboardArrowDown

        /** 向上（上移一项） */
        val Up: ImageVector = Icons.Rounded.KeyboardArrowUp
    }

    // ==================== 操作：用户对某个对象做的事 ====================
    object Act {
        /** 新建（唯一的 "+"，所有 FAB / 新建入口共用） */
        val Add: ImageVector = Icons.Rounded.Add

        /** 新建文件夹（与"新建文件"分清：一个是目录一个是内容） */
        val NewFolder: ImageVector = Icons.Rounded.CreateNewFolder

        /** 改内容（编辑正文、改参数） */
        val Edit: ImageVector = Icons.Rounded.Edit

        /** 只改名字（重命名）——和 [Edit] 分开：用户预期不同 */
        val Rename: ImageVector = Icons.Rounded.DriveFileRenameOutline

        /** 换位置（移动到…）：曾误用 FolderCopy，会被读成"一组文件夹"这种内容节点 */
        val Move: ImageVector = Icons.AutoMirrored.Rounded.DriveFileMove

        /** 归档 / 旧的存档位置（已归档分组、旧导出目录——同一个"封存"语义） */
        val Archive: ImageVector = Icons.Rounded.Archive

        /** 删除：**永远 error 红**，不参与提蓝 */
        val Delete: ImageVector = Icons.Rounded.Delete

        /** 行内溢出菜单（MoreVert） */
        val More: ImageVector = Icons.Rounded.MoreVert

        /** 搜索 */
        val Search: ImageVector = Icons.Rounded.Search

        /** 重新拉取 / 重新采集（睡眠、下拉刷新） */
        val Refresh: ImageVector = Icons.Rounded.Refresh

        /** 撤销上一次写入 —— 禁止再拿 [Refresh] 冒充撤销（语义是"回滚"不是"重来"） */
        val Undo: ImageVector = Icons.Rounded.Undo

        /** 用外部应用打开（文件长按菜单的"用其他应用打开"） */
        val Open: ImageVector = Icons.Rounded.OpenInNew

        /** 收藏/默认入口（文件库顶栏的星标：点亮=当前路径是默认打开路径） */
        val Pin: ImageVector = Icons.Rounded.Star

        /** 收藏的未点亮态 */
        val PinOutline: ImageVector = Icons.Rounded.StarBorder
    }

    // ==================== 内容：列表/树里"这是个什么东西" ====================
    object Kind {
        /** 一段会话（聊天） */
        val Chat: ImageVector = Icons.Rounded.ChatBubble

        /** 项目：用项目自己的图标，折叠后才退成文件夹形 */
        fun project(iconKey: String): ImageVector = WorkbenchIcons.project(iconKey)

        /** 文件夹（收起态） */
        val Folder: ImageVector = Icons.Rounded.Folder

        /** 文件夹（展开态）——与 [Folder] 是同一个东西的两种状态，不是两种语义 */
        val FolderOpen: ImageVector = Icons.Rounded.FolderOpen

        /** 「未分类」：没有项目归属的散落内容。**只**用于未分类，不表示"删除文件夹" */
        val FolderNone: ImageVector = Icons.Rounded.FolderOff

        /** 一组文件夹组成的**分组节点**（"目录的目录"通用形） */
        val FolderGroup: ImageVector = Icons.Rounded.FolderCopy

        /** 项目数据（项目记忆+项目文件+真实子文件夹的**数据**分组）——数据库形，与普通文件夹一眼区分（2026-10-28 用户） */
        val ProjectData: ImageVector = Icons.Rounded.Storage

        /** 信息提取（群→日期→产物分组）——漏斗 = 从消息流里**提纯**出来，与普通文件夹一眼区分（2026-10-28 用户） */
        val Extract: ImageVector = Icons.Rounded.FilterAlt

        /** 笔记 */
        val Note: ImageVector = Icons.Rounded.Description

        /** 文件（文件域、文件叶、文件库入口——同一个"文件"语义） */
        val File: ImageVector = Icons.Rounded.InsertDriveFile

        /** 幻灯片文件（有快照可翻页） */
        val Slides: ImageVector = Icons.Rounded.Slideshow

        /** 图片文件 */
        val Picture: ImageVector = Icons.Rounded.Image

        /** 记忆（记忆库入口 / 项目记忆叶 / @记忆 —— 一个意思一个图形） */
        val Memory: ImageVector = Icons.Rounded.Memory

        /** 技能（记忆的一种，但要能和普通记忆一眼分开） */
        val Skill: ImageVector = Icons.Rounded.AutoAwesome

        /** 智能体 / 助手（@助手、智能体列表）。**只**表助手：不要再拿它当"记忆库"入口 */
        val Assistant: ImageVector = Icons.Rounded.SmartToy

        /** 语音输入 */
        val Voice: ImageVector = Icons.Rounded.Mic

        /** 电脑端（PC 渲染 / 跨端） */
        val Computer: ImageVector = Icons.Rounded.Computer

        /** 剪贴板内容 */
        val Clipboard: ImageVector = Icons.Rounded.ContentPaste

        /** 倒数日 / 旗帜标记 */
        val Flag: ImageVector = Icons.Rounded.Flag
    }

    // ==================== 状态：这件事怎么样了 ====================
    object State {
        /** 已完成 / 成功 / 达标 —— 只有这一个意思才准用它 */
        val Done: ImageVector = Icons.Rounded.CheckCircle

        /** 单个勾（行内小勾，比 [Done] 轻一档） */
        val Check: ImageVector = Icons.Rounded.Check

        /** 待核对 / 待处理（AI 提出来还没确认的东西）——**不是**"完成" */
        val Pending: ImageVector = Icons.AutoMirrored.Rounded.FactCheck

        /** 未达标 / 警示（error 红） */
        val Warn: ImageVector = Icons.Rounded.Warning

        /** 纯"详情 / 说明"：只在真的表示"看看详情"时用，**不再当入口图标** */
        val Info: ImageVector = Icons.Rounded.Info
    }

    // ==================== 设置入口 ====================
    // ⚠️ 这一组与 ui/profile/ProfileScreens.kt 的 SettingRow 一一对应（两个入口同一屏功能）。
    // 改任何一个都必须两边一起改，否则同一个功能在「我的」和「工作台抽屉」读起来像两件事。
    object Setting {
        /** 模型与智能体（SettingsHub 一屏两 tab） */
        val Model: ImageVector = Icons.Rounded.Settings

        /** AI 能力开关（插件/能力共用的开关表） */
        val AiCapability: ImageVector = Icons.Rounded.Extension

        /** 图表默认设置（**BarChart 专属**：使用统计已让给 [Usage]） */
        val ChartDefault: ImageVector = Icons.Rounded.BarChart

        /** 手机操控区域（phone use 可视/可操作范围） */
        val PhoneControl: ImageVector = Icons.Rounded.CropFree

        /** MCP 服务器设置 */
        val Mcp: ImageVector = Icons.Rounded.Hub

        /** 命令（/ 指令）设置 */
        val Command: ImageVector = Icons.Rounded.Terminal

        /** 导入（技能 / MCP JSON / 插件包） */
        val Import: ImageVector = Icons.Rounded.Upload

        /** 自动化任务（与「自动化与扩展」中心同一个语义，同一个图形） */
        val Automation: ImageVector = Icons.Rounded.Autorenew

        /** 使用统计（token / 用量）——用折线，把柱状图 [ChartDefault] 让出去 */
        val Usage: ImageVector = Icons.AutoMirrored.Rounded.ShowChart

        /** 信息获取·提纯：**Sensors 只表"信息域"**，不再兼职自动化/技能 */
        val Capture: ImageVector = Icons.Rounded.Sensors

        /** AI 能力体检（2026-11-05）：听诊器语义——"查这个系统哪里不对" */
        val Health: ImageVector = Icons.Rounded.MonitorHeart

        /** 文件库入口（与底部「文件」tab、文件叶同形：都是"文件"） */
        val FileLibrary: ImageVector = Icons.Rounded.InsertDriveFile

        /** 记忆库入口 —— 曾经是 Info，与「信息」底栏 tab / 文件详情撞车，故改用 [Kind.Memory] */
        val MemoryBank: ImageVector = Icons.Rounded.Memory

        /**
         * v53.3「资料」总汇（记忆库 / 文件库 / 归档项目）。
         * 用 Inventory2（叠放的资料箱）：表达"存放物的集合"，不与 [FileLibrary] 的"单个文件"
         * 或 [MemoryBank] 的"记忆芯片"抢同一个图形。
         */
        val DataHub: ImageVector = Icons.Rounded.Inventory2
    }

    // ==================== 勾连（@ 菜单）的域与插件 ====================
    object Link {
        val Schedule: ImageVector = Icons.Rounded.CalendarMonth   // 日程域 / 日程条目
        val Todo: ImageVector = State.Done                        // 待办域 / 待办条目（"要打勾的事"）
        val Note: ImageVector = Kind.Note                         // 笔记域 / 笔记条目
        val Info: ImageVector = Setting.Capture                   // 信息域（Sensors = 信息获取）
        val Memory: ImageVector = Kind.Memory                     // 记忆域 / 记忆条目
        val Automation: ImageVector = Setting.Automation          // 自动化域（原 Tune，与"会话参数"撞车）
        val Extend: ImageVector = Setting.AiCapability            // extend 域（能力/插件/助手/MCP）
        val File: ImageVector = Kind.File                         // file 域（原来落到 Folder 兜底，读成"文件夹"）

        // 插件包
        val Office: ImageVector = Icons.Rounded.Article
        val Web: ImageVector = Icons.Rounded.Public
        val CalendarExport: ImageVector = Icons.Rounded.Event
        val Phone: ImageVector = Icons.Rounded.PhoneAndroid
        /** 插件兜底：认不出的插件一律按"能力包"画 */
        val PluginFallback: ImageVector = Setting.AiCapability
        /** 域兜底：认不出的域按"信息/工具"画，**不要**再退回 Folder */
        val DomainFallback: ImageVector = Setting.Capture
    }
}
