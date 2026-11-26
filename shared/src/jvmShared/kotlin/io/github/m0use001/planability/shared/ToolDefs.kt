package io.github.m0use001.planability.shared

import org.json.JSONArray
import org.json.JSONObject

/**
 * 工具契约单一真源（M4 从 app/ai/ptl/ToolDefs.kt 迁入，jvmShared：org.json 纯逻辑）。
 *
 * 这份注册表是**跨端契约的机器可读权威**：工具名、参数名/类型/描述、风险档、域/插件分组
 * 全部只在这里定义一份。app 侧经 typealias 桥透明使用；PC 端消费侧对齐见
 * ◆/planability-cross-end-contract.md（drift 守卫会红）。
 *
 * 原 Risk 枚举已在 M2 批3 迁入本包（PermPolicy 需要它），此处直接同包引用。
 */
data class ToolParam(
    val name: String,
    val type: String = "string",
    val description: String,
    val required: Boolean = false
)

/**
 * `exports/` 这个前缀该怎么向用户解释。
 *
 * ## 为什么需要专门写清楚
 *
 * v14.3 之前，工具**真的会拒绝**带路径的文件名，只接受 `报告.docx` 这种裸名字，
 * 所以 AI 学会了说"文件名不支持带路径，系统会自动写入导出目录"。
 * 那是当时的**事实**，但 v14.3 之后：
 *
 * - `exports/` 变成**虚拟前缀**，写 `exports/季度汇报.docx` 是完全支持的；
 * - 文件落点已经**跟着对话走**（见 `ExportStore.dirFor`），不再是单一的"导出目录"。
 *
 * 契约里三处 `path` 参数都写着 `exports/xxx` 却从不说它是什么，
 * AI 只能照着旧印象编，于是继续说"不支持带路径"——
 * 设备上真机对话里确实出现了这句话（2026-10-12）。
 *
 * 用户看到的错误信息比任何内部实现都权威，所以这句话得在契约里改掉，而不是等 AI 自己猜。
 *
 * 抽成常量而不是在三处各写一遍：三份文案迟早会漂，而漂了的代价是
 * AI 在三个工具上给出三种互相矛盾的说法。
 */
private const val EXPORTS_PATH_HINT =
    "`exports/` 是虚拟前缀，写不写都一样，系统会自己决定实际位置：" +
        "文件会存到**当前对话的目录**（未分类对话会单独建一个目录）。" +
        "可以直接写 `exports/季度汇报.docx`，不需要只给裸文件名。"

/** PTL 工具定义（prokop LoadedTool 同构，兼容 MCP tool spec） */
data class ToolDef(
    val name: String,
    val cnDescription: String,
    val whenToUse: String,
    val params: List<ToolParam> = emptyList(),
    val risk: Risk = Risk.AUTO
) {
    fun toJsonSchema(): JSONObject {
        val props = JSONObject()
        val req = JSONArray()
        params.forEach { p ->
            props.put(p.name, JSONObject().put("type", p.type).put("description", p.description))
            if (p.required) req.put(p.name)
        }
        return JSONObject()
            .put("type", "function")
            .put(
                "function", JSONObject()
                    .put("name", name)
                    .put("description", "$cnDescription。适用场景：$whenToUse")
                    .put("parameters", JSONObject().put("type", "object").put("properties", props).put("required", req))
            )
    }
}

/** PTL v1 注册表：23 个域工具（读 13 / 写 9 / 删 1） */
object ToolRegistry {

    // 2026-11-26 开源根治：ALL 改为「公开内置集 + 设备包注册集」。私密工具（◆ /
    // ◆ / phone_*）的定义不在本文件——它们住在 app 的 可选设备源集，
    // 运行时经 [registerExtra] 注入（DeviceFeaturesInstaller）；开源版无该源集 →
    // ALL/DOMAINS/PLUGINS 天然不含任何私密工具（模型"看不见"，而非"调了失败"）。
    val BUILTIN: List<ToolDef> = listOf(
        // ---------- 日程域 ----------
        ToolDef(
            name = "schedule_query", cnDescription = "查询用户日程（按时间范围列主记录，不展开重复实例）",
            whenToUse = "用户问「我有什么安排/明天干嘛/周几有空」时；**要看某天/某周/课表的展开实例用 timeline_query（重复日程会展开成当天实例），本工具只列主记录**",
            params = listOf(
                ToolParam("start", "string", "起始日，格式 yyyy-MM-dd 或 今天/明天/后天，缺省=今天"),
                ToolParam("end", "string", "结束日（含），同上，缺省=起始日")
            )
        ),
        ToolDef(
            name = "schedule_create", cnDescription = "创建一条日程（先落助理中枢草稿，用户审核后进时间轴）",
            whenToUse = "用户明确要安排日程/会议/课程时；v54 统一审核——工具产出的是中枢草稿而非正式日程，返回文案会带草稿编号，要向用户说明",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("title", "string", "日程标题", required = true),
                ToolParam("start", "string", "开始时间，如 2026-09-28 14:00 / 明天 09:00 / 今天 14:00", required = true),
                ToolParam("end", "string", "结束时间，同上；缺省=开始+1小时"),
                ToolParam("place", "string", "地点（可选）"),
                ToolParam("note", "string", "备注（可选）"),
                ToolParam("priority", "string", "重要程度 high/normal/low（四象限「重要」轴），缺省 normal（影响时间轴颜色）；按实际重要程度设，别一律 high"),
                ToolParam("urgency", "string", "紧急程度 high/normal/low（四象限「紧急」轴），不传=未设置；有明确截止/临近的事设 high，可以迟后的事设 low"),
                ToolParam("lat", "number", "地点纬度（可选，配合 lng 存储地图选点）"),
                ToolParam("lng", "number", "地点经度（可选）"),
                ToolParam("repeat", "string", "重复规则：daily / weekly:1,3（1=周一…7=周日，可多个逗号隔开）/ biweekly:1,3（每两周）/ monthly（每月按日号）/ monthlyW:2:3（每月第2个周三）/ yearly / interval:14（每14天）。缺省不重复"),
                ToolParam("repeat_until", "string", "重复截止日 yyyy-MM-dd，缺省=一直重复"),
                ToolParam("tag", "string", "分类标签名（可选，须是已有标签，用 plan_category_list 查）"),
                ToolParam("labels", "string", "自由标签，逗号分隔，如 工作,重要（可选）")
            )
        ),
        ToolDef(
            name = "schedule_update", cnDescription = "修改日程（按 id 改标题/时间/地点/优先级/重复）",
            whenToUse = "用户要改时间、改地点、改重要程度或重复规则时；id 来自 schedule_query",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "日程 id", required = true),
                ToolParam("title", "string", "新标题（可选）"),
                ToolParam("start", "string", "新开始时间（可选）"),
                ToolParam("end", "string", "新结束时间（可选）"),
                ToolParam("place", "string", "新地点（可选）"),
                ToolParam("priority", "string", "新重要程度 high/normal/low（四象限「重要」轴，可选）"),
                ToolParam("urgency", "string", "新紧急程度 high/normal/low（四象限「紧急」轴，可选；不传=不改）"),
                ToolParam("lat", "number", "纬度（可选）"),
                ToolParam("lng", "number", "经度（可选）"),
                ToolParam("repeat", "string", "新重复规则，语法同 schedule_create；传 none 取消重复（可选）"),
                ToolParam("repeat_until", "string", "新重复截止日，缺省=一直重复（可选）"),
                ToolParam("tag", "string", "分类标签名（可选）"),
                ToolParam("labels", "string", "自由标签，逗号分隔（整体替换，可选）")
            )
        ),
        ToolDef(
            name = "schedule_delete", cnDescription = "删除一条日程（进回收站，可撤销）",
            whenToUse = "用户明确说这个日程不要了/删掉时；只取消某一天用 schedule_delete_today",
            risk = Risk.DANGER,
            params = listOf(ToolParam("id", "integer", "日程 id（来自 schedule_query）", required = true))
        ),
        ToolDef(
            name = "schedule_delete_today", cnDescription = "为日程添加某天例外（仅那天不出现，不删原日程）",
            whenToUse = "用户说「今天/某天这个先不去/取消这次」时",
            risk = Risk.DANGER,
            params = listOf(
                ToolParam("id", "integer", "日程 id", required = true),
                ToolParam("date", "string", "哪天例外，yyyy-MM-dd 或 今天/明天，缺省=今天")
            )
        ),
        ToolDef(
            name = "timeline_query", cnDescription = "查询某天（或某段）已排好的日程与带时刻的待办（时间轴视图，重复日程展开成当天实例）",
            whenToUse = "用户问到**具体某天/某周/课表/课程安排/当天有什么/忙不忙/冲突**时必用——这是查「哪天有什么」的精确工具；重复日程会展开成当天实例。可传 days 一次铺一周（如「我的课表」「这周安排」），上限 31 天。data_history 数的是主记录条数（每周重复的课只算 1 条），答不了这类问题，别拿它当课表查。",
            params = listOf(
                ToolParam("day", "string", "起始日 yyyy-MM-dd 或 今天/明天/后天，缺省=今天"),
                ToolParam("days", "integer", "查几天（1=单天、7=一周），上限 31，缺省=1")
            )
        ),
        // ---------- 待办域 ----------
        ToolDef(
            name = "todo_query", cnDescription = "查询待办明细（每条带编号、日期、重复规则；上限 30 条+总数）",
            whenToUse = "用户问「还有什么要做/待办列表」时；要给已有待办排时段/加时刻、或改任何字段之前，" +
                "先用它拿到真实编号（返回「编号 N」）；判断某任务几天没做也用它",
            params = listOf(
                ToolParam("status", "string", "active=未完成 / done=已完成 / all，缺省 active"),
                ToolParam("day", "string", "按日期过滤，yyyy-MM-dd 或 今天/明天，缺省不限")
            )
        ),
        ToolDef(
            name = "todo_create", cnDescription = "创建一条待办（先落助理中枢草稿，用户审核后进时间轴）",
            whenToUse = "用户说要加个待办/任务/提醒自己做某事时；「每天/每周X/每隔N天」这类重复任务必须传 repeat 参数。v54 统一审核——工具产出的是中枢草稿而非正式待办，返回文案会带草稿编号，要向用户说明",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("title", "string", "待办标题", required = true),
                ToolParam("date", "string", "归属日 yyyy-MM-dd 或 今天/明天/后天，缺省=今天"),
                // 2026-11-02 四象限口径：priority=重要轴、urgency=紧急轴，两轴都要按实际
                // 情况分别设置——此前紧急轴没有参数，AI 建的待办全部「紧急性未设置」
                ToolParam("priority", "string", "重要程度 high/normal/low（四象限「重要」轴），缺省 normal；按实际重要程度设，别一律 high"),
                ToolParam("urgency", "string", "紧急程度 high/normal/low（四象限「紧急」轴），不传=未设置；有明确截止/临近的事设 high，可以迟后的事设 low"),
                ToolParam("note", "string", "备注（可选）"),
                ToolParam("place", "string", "地点（可选；与已有地点同名时自动带上坐标）"),
                ToolParam("lat", "number", "地点纬度（可选）"),
                ToolParam("lng", "number", "地点经度（可选）"),
                ToolParam("repeat", "string", "重复规则：daily / weekly:1,3（1=周一…7=周日，可多个逗号隔开）/ biweekly:1,3（每两周）/ monthly（每月按日号）/ monthlyW:2:3（每月第2个周三）/ yearly / interval:14（每14天）。缺省不重复"),
                ToolParam("repeat_until", "string", "重复截止日 yyyy-MM-dd，缺省=一直重复"),
                ToolParam("due", "string", "截止时刻 HH:mm 或日期+时刻（可选；**不传=不设时间**，日期仍必填——无时间待办会汇总在首页底部「未设置时间计划」胶囊里，别再编一个假时间）"),
                ToolParam("percent", "integer", "完成度百分比 0-100（可选；不传=不启用）。做了一部分的活（如读到第 6 章）主动汇报 percent=60，会按比例计入当天完成率；100 = 做完了但没打卡（进度满载）。**它不改变打卡状态**——要标打卡完成用 todo_complete"),
                ToolParam("tag", "string", "分类标签名（可选，须是已有标签，用 plan_category_list 查）"),
                ToolParam("labels", "string", "自由标签，逗号分隔，如 工作,重要（可选）")
            )
        ),
        ToolDef(
            name = "todo_edit", cnDescription = "修改一条待办（标题/日期/重复/标签等，按 id 改）",
            // P0-1（2026-11-10 审计两次动态复现）：弱模型把「助理中枢草稿 #N」的编号当 todos 表 id
            // 传进来，把无关待办整条改掉（连重复规则一起保留），工具全程 ok。描述里必须把 id 来源钉死。
            whenToUse = "用户要改待办的日期、内容、重复规则或具体时刻（due，如「每天 20:00 做」「加个时间段」）时；「改成每周三」这类要求用 repeat 参数。" +
                "id 必须来自 todo_query 的返回；待处理草稿编号（如「草稿 #1」）不是 id，别拿它当 id。",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "待办 id（必须来自 todo_query 的「编号 N」；待处理草稿编号不是待办 id）", required = true),
                ToolParam("title", "string", "新标题（可选）"),
                ToolParam("date", "string", "新归属日（可选）"),
                ToolParam("due", "string", "新截止时刻（可选；15:00 或 明天 15:00；传 0/none 清空）"),
                ToolParam("priority", "string", "新重要程度 high/normal/low（四象限「重要」轴，可选）"),
                ToolParam("urgency", "string", "新紧急程度 high/normal/low（四象限「紧急」轴，可选；不传=不改）"),
                ToolParam("note", "string", "新备注（可选）"),
                ToolParam("place", "string", "新地点（可选）"),
                ToolParam("repeat", "string", "新重复规则，语法同 todo_create；传 none 取消重复（可选）"),
                ToolParam("repeat_until", "string", "新重复截止日，缺省=一直重复（可选）"),
                ToolParam("percent", "integer", "新完成度 0-100（可选；不传=不改）。用户说「做了一半了/还差一点」时更新；0-99 设进度、100 = 做完了但没打卡、-1 关掉百分比。**不改变打卡状态**——要打卡用 todo_complete"),
                ToolParam("tag", "string", "分类标签名（可选）"),
                ToolParam("labels", "string", "自由标签，逗号分隔（整体替换，可选）")
            )
        ),
        ToolDef(
            name = "todo_delete", cnDescription = "删除一条待办（进回收站，可撤销）",
            whenToUse = "用户明确说某个待办不要了/删掉时",
            risk = Risk.DANGER,
            params = listOf(ToolParam("id", "integer", "待办 id（来自 todo_query）", required = true))
        ),
        ToolDef(
            name = "checkin_query", cnDescription = "查询待办的打卡/数据记录（体重、喝水、习惯打卡等）",
            whenToUse = "用户问「我这周体重/喝水记录怎么样」「XX 打卡了多少次」时",
            params = listOf(
                ToolParam("todo_id", "integer", "待办 id（可选；缺省=列出所有开着数据记录的待办）")
            )
        ),
        ToolDef(
            name = "checkin_add", cnDescription = "为开着「数据记录」的待办补录一笔打卡",
            whenToUse = "用户说「帮我记一下今天体重 62.5」「补录昨天的喝水」时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("todo_id", "integer", "待办 id", required = true),
                ToolParam("date", "string", "哪一天，缺省=今天"),
                ToolParam("value", "number", "数值/计数型的值（number/count 必传）"),
                ToolParam("text", "string", "短文本型的内容"),
                ToolParam("tags", "string", "多选型的选项，逗号分隔")
            )
        ),
        ToolDef(
            name = "plan_category_list", cnDescription = "列出计划页的分类（标签/地点/文件夹），地点含是否已绑坐标",
            whenToUse = "要给日程/待办挂分类标签、或确认某个地点是否存在时；create 的 tag 参数只能用这里列出的名字",
            params = listOf(
                ToolParam("kind", "string", "tag/place/folder/all，缺省 all")
            )
        ),
        // 2026-11-14 用户「AI 说设置不了标签」：会话 287 实录 tag:「课程」5 连拒——
        // 标签不存在且 AI 没有建标签的工具，只能让用户手动去分类管理，流程断裂。
        ToolDef(
            name = "plan_category_create", cnDescription = "新建一个计划分类（标签/地点/文件夹），或把地点作为显示名挂到已有绑定地点下",
            whenToUse = "要用的标签还不存在时（todo/note 的 tag 参数只认已有标签）；先建标签，再在 create/edit 里用它。" +
                "**建地点时先 plan_category_list(kind=place) 看有没有已存在的绑定地点**：若新地名是某栋楼里的房间/子地点（如 ◆-312 属于 ◆），" +
                "传 parent=◆ 把它作为「显示地名」挂到那个绑定地点下，**不要新建成并列的顶层地点**；只有整栋楼都没有时才不带 parent 新建",
            params = listOf(
                ToolParam("name", "string", "分类名（≤20 字）", required = true),
                ToolParam("kind", "string", "tag/place/folder，缺省 tag"),
                ToolParam("hue", "number", "色相角度 0-360（可选，缺省随机）"),
                ToolParam("parent", "string", "（kind=place 时）父「绑定地名」，把 name 作为该楼下的显示地名挂进去；如 parent=◆、name=◆-312。整栋新楼才留空")
            )
        ),
        // ---------- 生活数据（2026-10-31 用户指名补齐：专注/周报/成就/草稿/回收站/笔记本） ----------
        ToolDef(
            name = "focus_query", cnDescription = "查询最近的专注记录（每日分钟数/次数/完成率，含累计）",
            whenToUse = "用户问「我最近专注了多少/专注记录怎么样」时",
            params = listOf(ToolParam("days", "integer", "查询最近几天，缺省 7"))
        ),
        ToolDef(
            name = "focus_add", cnDescription = "补记一次刚结束的专注（按现在往回推开始时刻）",
            whenToUse = "用户说「我刚专注了 25 分钟，记一下」时；不能替用户开始/结束专注",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("planned_min", "integer", "专注时长（分钟，1-240）", required = true),
                ToolParam("completed", "boolean", "是否完成，缺省 true")
            )
        ),
        ToolDef(
            name = "weekly_query", cnDescription = "查询已生成的周小结文本（每篇正文截 400 字，支持 offset 分页）",
            whenToUse = "用户问「上周总结/周报说了什么」时",
            params = listOf(
                ToolParam("offset", "integer", "跳过最新的 N 篇（缺省 0；要找更早的周报就翻页）")
            )
        ),
        ToolDef(
            name = "achievement_query", cnDescription = "查询经验等级与已解锁徽章",
            whenToUse = "用户问「我几级了/有哪些成就」时",
            params = listOf()
        ),
        ToolDef(
            name = "pending_add", cnDescription = "把一条日程/待办放进「待确认草稿」，用户批准后才真正创建",
            whenToUse = "用户说「先记着，回头我确认」、或信息不够确定时；比 todo_create/schedule_create 多一道用户闸门",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("title", "string", "草稿标题", required = true),
                ToolParam("kind", "string", "todo/schedule，缺省 todo"),
                ToolParam("timeText", "string", "原始时间描述，如「周三晚上」「明天 14:00」"),
                ToolParam("place", "string", "地点（可选）"),
                ToolParam("extra", "string", "备注（可选）"),
                ToolParam("confidence", "number", "你对该条信息可靠的把握 0~1（可选，缺省不标）")
            )
        ),
        ToolDef(
            name = "trash_query", cnDescription = "列出回收站里已删除的待办/日程/笔记（每类给总数，支持 offset 分页）",
            whenToUse = "用户说「东西删错了/找回删掉的」时",
            params = listOf(
                ToolParam("offset", "integer", "跳过每类最新的 N 条（缺省 0；回收站多时翻页用）")
            )
        ),
        ToolDef(
            name = "trash_restore", cnDescription = "从回收站恢复一条待办/日程/笔记",
            whenToUse = "用户确认要找回某条已删除的记录时（id 来自 trash_query）",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("kind", "string", "todo/schedule/note", required = true),
                ToolParam("id", "integer", "编号（来自 trash_query）", required = true)
            )
        ),
        ToolDef(
            name = "notebook_list", cnDescription = "列出笔记本（含各本笔记数）",
            whenToUse = "要选笔记本建笔记、或确认某本笔记是否存在时",
            params = listOf()
        ),
        ToolDef(
            name = "notebook_create", cnDescription = "新建一个笔记本",
            whenToUse = "用户要为某主题单独记笔记时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("name", "string", "笔记本名", required = true))
        ),
        ToolDef(
            name = "note_edit", cnDescription = "修改笔记（标题/正文/标签/移动到别的笔记本）",
            whenToUse = "用户要改已有笔记内容或归类时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "笔记 id（来自 note_search/note_read）", required = true),
                ToolParam("title", "string", "新标题（可选）"),
                ToolParam("content", "string", "新正文（整体替换，可选）"),
                ToolParam("notebook", "string", "目标笔记本名（移动，可选）"),
                ToolParam("tags", "string", "标签，逗号分隔（整体替换，可选）")
            )
        ),
        ToolDef(
            name = "note_link", cnDescription = "笔记链接：加/删 [[链接]]，或查反链与出链",
            whenToUse = "用户说「把这两篇笔记关联起来」「谁引用了这篇」时；加链接=在正文末尾写 [[目标标题]]",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("action", "string", "backlinks（反链）/outlinks（出链）/link（加）/unlink（删）", required = true),
                ToolParam("id", "integer", "笔记 id", required = true),
                ToolParam("target", "string", "目标笔记标题（link/unlink 必填，可不存在=悬空引用）")
            )
        ),
        ToolDef(
            name = "note_delete", cnDescription = "删除一条笔记（进回收站，可撤销）",
            whenToUse = "用户明确说某篇笔记不要了时",
            risk = Risk.DANGER,
            params = listOf(ToolParam("id", "integer", "笔记 id", required = true))
        ),
        ToolDef(
            name = "todo_complete", cnDescription = "勾选完成一条待办",
            whenToUse = "用户说某件事做完了/勾掉某待办时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("id", "integer", "待办 id（来自 todo_query）", required = true))
        ),
        // ---------- 笔记域 ----------
        ToolDef(
            name = "library_search", cnDescription = "全库检索笔记正文（RAG 分块 + BM25 相关性排序）",
            whenToUse = "用户问「我的笔记/资料里有没有关于 X」、想找以前记录的长内容时；比 note_search 更适合长文与模糊语义",
            params = listOf(ToolParam("query", "string", "检索词或短语", required = true))
        ),
        ToolDef(
            name = "note_search", cnDescription = "检索笔记（标题/正文双路 FTS + 相关性融合，可按标签/笔记本过滤）",
            whenToUse = "用户想找笔记/记过的东西时；整篇长文找证据用 kb_ask",
            params = listOf(
                ToolParam("query", "string", "关键词", required = true),
                ToolParam("tag", "string", "只看带这个标签的笔记（可选）"),
                ToolParam("notebook", "string", "只看这个笔记本（可选，笔记本名）")
            )
        ),
        ToolDef(
            name = "kb_ask", cnDescription = "知识库问答：多轮检索笔记/分块/链接，返回带编号引用的证据包（证据不足会明说没覆盖）",
            whenToUse = "用户问「我的笔记/资料里关于 X 怎么说的」「基于我的记录回答」时。回答必须用 [编号] 标出处；" +
                "证据包没覆盖的部分要明确说知识库里没有，不许编",
            params = listOf(
                ToolParam("query", "string", "问题或检索意图", required = true),
                ToolParam("tag", "string", "只检索带这个标签的笔记（可选）"),
                ToolParam("topK", "integer", "最多几条证据（默认 6）")
            )
        ),
        ToolDef(
            name = "note_read", cnDescription = "读取一条笔记全文，支持从指定位置分段读",
            whenToUse = "note_search 命中后要看全文时。长笔记按返回头的 offset 续读",
            params = listOf(
                ToolParam("id", "integer", "笔记 id", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长笔记按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        ToolDef(
            name = "note_create", cnDescription = "新建一篇 Markdown 笔记（可指定笔记本）",
            whenToUse = "用户说记一下/写个笔记/帮我记下来时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("title", "string", "笔记标题", required = true),
                ToolParam("content", "string", "Markdown 正文", required = true),
                ToolParam("notebook", "string", "笔记本名（可选，须已存在，用 notebook_list 查；缺省默认笔记本）")
            )
        ),
        ToolDef(
            name = "note_append", cnDescription = "向已有笔记末尾追加内容",
            whenToUse = "用户说往某条笔记里补充/追加时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "笔记 id", required = true),
                ToolParam("text", "string", "追加的文本", required = true)
            )
        ),
        // ---------- 文件域（应用内附件/导出目录） ----------
        ToolDef(
            name = "file_list", cnDescription = "列出应用内附件与 AI 生成的文件",
            whenToUse = "用户问有哪些文件/附件时",
            params = listOf(
                ToolParam("dir", "string", "attachments=附件 / exports=导出，缺省两者都列")
            )
        ),
        ToolDef(
            name = "file_export",
            cnDescription = "把纯文本内容导出为应用内文件（任意纯文本扩展名：.md/.markdown/.txt/.htm/.html/.json/.xml/.yaml/.csv/.tsv/.ics/.svg/.css/.js/.sql/代码文件等），写入 exports 目录，可在文件页查看或系统分享",
            whenToUse = "用户要把大纲/整理结果/网页/配置/代码/文本保存成文件带走时；办公格式（docx/xlsx/pptx）不要用本工具",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("filename", "string", "文件名（含扩展名，如 大纲.md、页面.htm、数据.csv、配置.json；不要路径分隔符）", required = true),
                ToolParam("content", "string", "要写入的完整文本（原样写入，不做转义）", required = true)
            )
        ),
        ToolDef(
            name = "file_read_text", cnDescription = "读取应用内文本文件内容（txt/md/csv/json），支持从指定位置分段读",
            whenToUse = "file_list 命中后需要看内容时。返回头写明「第 X–Y 字 / 共 N 字」；没读完就按头里的 offset 再调一次续读，长文稿能分多次读完",
            params = listOf(
                ToolParam("path", "string", "file_list 返回的相对路径", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始）。首次不传=0；长文件按返回头给的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000，上限 200000）")
            )
        ),
        ToolDef(
            name = "file_read_document", cnDescription = "解析应用内文档为结构化文本（docx/xlsx/pptx/ppt/pdf），支持分段读；扫描 PDF 会生成每页快照（exports/*.pages/），单页版式/公式用 file_read_image 看图",
            whenToUse = "附件是文档且用户要内容/总结时。长文档同样按返回头的 offset 续读",
            params = listOf(
                ToolParam("path", "string", "file_list 返回的附件相对路径", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长文档按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        ToolDef(
            name = "file_export_document",
            cnDescription = "把内容导出为办公文档：.docx（Word，支持轻量 Markdown 排版）/ .xlsx（表格，行内单元格用制表符分隔）/ .csv；其它纯文本扩展名（.md/.htm/.json 等）也照原样写出，写入 exports 目录",
            whenToUse = "用户要 Word/Excel/表格文件、要把整理结果做成文档时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("filename", "string", "文件名（.docx/.xlsx/.csv 结尾，不要路径分隔符）", required = true),
                ToolParam(
                    "content", "string",
                    "内容：docx 支持轻量 Markdown——`# 一级标题` / `## 二级` / `### 三级`、`**粗体**`、" +
                        "`- 项目符号`、`1. 编号`、`> 引用`、`| 表头 | 表头 |` 后跟 `| --- | --- |` 再写数据行、" +
                        "`---` 分隔线；不加标记的行就是正文段落。" +
                        "建议：开头一行用 `# 标题`，正文按小节分 `## `，数据用表格而不是空格对齐。" +
                        "xlsx/csv=每行一条记录、单元格用制表符分隔",
                    required = true
                )
            )
        ),
        ToolDef(
            name = "file_build_deck",
            cnDescription = "生成演示文稿（.pptx）：按结构化大纲生成带版式/配色/备注的 PPT，写进 exports 目录",
            whenToUse = "用户要做PPT/幻灯片/演示稿，或要把一份内容变成可放映的页面时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("filename", "string", "文件名，以 .pptx 结尾，不要带路径", required = true),
                ToolParam(
                    "slides", "string",
                    "JSON 数组字符串。每项：{\"layout\":\"title|section|bullets|two_column|image|table|quote\"," +
                        "\"title\":\"\",\"subtitle\":\"\",\"notes\":\"演讲者备注\"," +
                        "\"blocks\":[{\"type\":\"bullets\",\"items\":[\"要点1\",\"要点2\"]}," +
                        "{\"type\":\"para\",\"text\":\"段落\",\"bold\":false}," +
                        "{\"type\":\"image\",\"path\":\"本地路径或http(s)图片地址\",\"caption\":\"图注\"}," +
                        "{\"type\":\"table\",\"rows\":[[\"表头\"],[\"单元格\"]]}]}。" +
                        "**title/subtitle/要点必须写这次演示的真实内容**，不要写" +
                        "「演示文稿标题」「演示文稿副标题」之类占位话——那会原样出现在成品里。",
                    required = true
                ),
                ToolParam(
                    "title", "string", "演示文稿标题（用作文档属性与默认封面标题）", required = false
                ),
                ToolParam(
                    "theme", "string",
                    "可选，JSON：{\"name\":\"默认\",\"dark\":false,\"accent\":\"4F6BED\"}（accent 为 6 位十六进制）",
                    required = false
                )
            )
        ),
        ToolDef(
            name = "file_edit_deck",
            cnDescription = "改已有的 .pptx：只替换指定页的内容，其余页原样保留",
            whenToUse = "用户要修改已有 PPT 的某几页（改标题/要点/加备注），而不是重新生成一份时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam(
                    "path", "string",
                    "原 pptx 的路径，如 exports/季度汇报.pptx（用 file_list 或 file_read_document 确认）。" +
                        EXPORTS_PATH_HINT,
                    required = true
                ),
                ToolParam(
                    "slides", "string",
                    "要改的页，JSON 数组。每项必须有 pageNo（从 1 开始，与读取时看到的页号一致，" +
                        "改完请原样带回）以及要替换后的内容：" +
                        "{\"pageNo\":3,\"title\":\"新标题\",\"subtitle\":\"\",\"blocks\":" +
                        "[{\"type\":\"bullets\",\"items\":[\"要点一\",\"要点二\"]}," +
                        "{\"type\":\"para\",\"text\":\"一段话\",\"bold\":false}],\"notes\":\"演讲者备注\"}。" +
                        "**没列出的页不会被触碰**。",
                    required = true
                ),
                ToolParam(
                    "out", "string", "输出文件名（以 .pptx 结尾）。默认在原名后加 _改", required = false
                )
            )
        ),
        ToolDef(
            name = "file_edit_document",
            cnDescription = "改已有的 .docx：只替换指定段落的文字，其余内容原样保留",
            whenToUse = "用户要修改已有 Word 文档的某几段（改错字、改标题、删掉一段），而不是重新生成整份时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam(
                    "path", "string",
                    "原 docx 的路径，如 exports/周报.docx（用 file_list 或 file_read_document 确认）。" +
                        EXPORTS_PATH_HINT,
                    required = true
                ),
                ToolParam(
                    "edits", "string",
                    "要改的段落，JSON 数组。每项 {\"index\":2,\"text\":\"新文字\"}，" +
                        "index 从 1 开始，与读取时看到的 [P1] [P2] 编号一致；" +
                        "text 传空字符串表示清空该段。**没列出的段落不会被触碰**。",
                    required = true
                ),
                ToolParam(
                    "out", "string", "输出文件名（以 .docx 结尾）。默认在原名后加 _改", required = false
                )
            )
        ),
        ToolDef(
            name = "file_edit_sheet",
            cnDescription = "改已有的 .xlsx：改单元格、加行、删行，其余内容（样式/合并/其它工作表）原样保留（手机端直接改，不需要电脑）",
            whenToUse = "用户要修改已有 Excel 表格的某些单元格/行时。Word/PPT 的改稿用别的工具",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam(
                    "path", "string",
                    "原 xlsx 的路径，如 exports/明细.xlsx（用 file_list 确认）。" +
                        EXPORTS_PATH_HINT,
                    required = true
                ),
                ToolParam(
                    "ops", "string",
                    "要做的操作，JSON 数组。三种：" +
                        "{\"op\":\"set\",\"sheet\":\"明细\",\"cell\":\"B3\",\"value\":13000} 改单元格（" +
                        "cell 用 B3 这种记法，也可用 row/col 从 1 起数）；" +
                        "{\"op\":\"addRow\",\"values\":[\"私域\",800,\"52%\"]} 末尾加一行；" +
                        "{\"op\":\"delRow\",\"row\":3} 删第 3 行。**没提到的行不会被触碰**。",
                    required = true
                ),
                ToolParam(
                    "out", "string", "输出文件名（以 .xlsx 结尾）。默认在原名后加 _改", required = false
                )
            )
        ),
        ToolDef(
            name = "web_fetch", cnDescription = "抓取网页并提取正文文本（只读），支持分段读",
            whenToUse = "用户给出网址链接、要读网页内容/新闻/文档时。长文按返回头的 offset 续读",
            params = listOf(
                ToolParam("url", "string", "http/https 网址", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长文按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        ToolDef(
            name = "web_search", cnDescription = "联网搜索：返回搜索结果列表（标题/链接/摘要），国内直连无需配置",
            whenToUse = "需要联网查最新信息/资料/新闻/天气等模型知识覆盖不到的内容时；拿到结果后可用 web_fetch 读原文",
            params = listOf(
                ToolParam("query", "string", "搜索关键词", required = true),
                ToolParam("count", "integer", "返回条数 1-8，缺省 5")
            )
        ),
        ToolDef(
            name = "calendar_export_ics", cnDescription = "把指定日程导出为 .ics 日历文件（可导入系统日历或分享）",
            whenToUse = "用户要把某条日程导出/分享到其他日历应用时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("id", "integer", "日程 id（来自 schedule_query）", required = true))
        ),
        ToolDef(
            name = "file_read_image", cnDescription = "对应用内图片附件做端侧 OCR 提取文字（长图自动切条，支持分段读）",
            whenToUse = "附件是图片且用户要其中文字时。识别文本长（截图/长图）时按返回头的 offset 续读",
            params = listOf(
                ToolParam("path", "string", "file_list 返回的图片相对路径", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长文按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        // ---------- v43 真实文件面（用户定向：文件面放开）----------
        // 与 file_* 的区别：这里直接操作**手机真实存储**（/storage/emulated/0 以下），
        // 不是应用内附件/导出目录。路径闸门见 core/FsGuard：Android/data、Android/obb 恒拒。
        ToolDef(
            name = "fs_list", cnDescription = "列出手机真实目录下的文件与子目录（名称/类型/大小）",
            whenToUse = "用户问手机里某个文件夹有什么、或要在真实目录里找文件时；路径用绝对路径，如 /storage/emulated/0/Download",
            params = listOf(
                ToolParam("path", "string", "绝对路径（/storage/emulated/0 以下）", required = true),
                ToolParam("showHidden", "boolean", "是否列出以 . 开头的隐藏项（默认 false）")
            )
        ),
        ToolDef(
            name = "fs_read_text", cnDescription = "读取手机真实存储里的文本文件（txt/md/csv/json/代码等），支持从指定位置分段读",
            whenToUse = "fs_list/fs_search 命中后要看内容时；二进制文件会被拒绝并指路。长文件按返回头的 offset 续读",
            params = listOf(
                ToolParam("path", "string", "绝对路径", required = true),
                ToolParam("maxKB", "integer", "最多读取多少 KB（默认 200，上限 512）"),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长文件按返回头的 offset 续读")
            )
        ),
        ToolDef(
            name = "fs_search", cnDescription = "在手机存储里按文件名关键字搜索（限定深度与条数）",
            whenToUse = "用户只记得文件名片段、不知道在哪个目录时",
            params = listOf(
                ToolParam("query", "string", "文件名关键字（不区分大小写）", required = true),
                ToolParam("root", "string", "搜索根目录（默认 /storage/emulated/0）"),
                ToolParam("limit", "integer", "最多返回条数（默认 30，上限 100）")
            )
        ),
        ToolDef(
            name = "fs_write_text", cnDescription = "把文本写入手机真实存储（父目录不存在会自动创建；默认不覆盖已有文件）",
            whenToUse = "用户要把内容保存到手机某个真实目录、或修改已有的文本文件时（覆盖需显式 overwrite=true）",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("path", "string", "绝对路径（含文件名）", required = true),
                ToolParam("content", "string", "要写入的完整文本（原样写入，不做转义）", required = true),
                ToolParam("overwrite", "boolean", "目标已存在时是否覆盖（默认 false）")
            )
        ),
        ToolDef(
            name = "fs_mkdir", cnDescription = "在手机真实存储里创建目录（多级自动创建）",
            whenToUse = "用户要新建文件夹时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("path", "string", "绝对路径", required = true))
        ),
        ToolDef(
            name = "fs_move", cnDescription = "在手机真实存储里移动/重命名文件或目录",
            whenToUse = "用户要挪文件、改文件名时（目标已存在会拒绝，不覆盖）",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("from", "string", "源绝对路径", required = true),
                ToolParam("to", "string", "目标绝对路径（含新名字）", required = true)
            )
        ),
        ToolDef(
            name = "fs_delete",
            cnDescription = "删除手机真实存储里的文件或目录（**不可恢复**；目录必须显式 recursive=true）",
            whenToUse = "用户明确要求删除、且已知道删的是哪个路径时；拿不准时先 fs_list 确认",
            risk = Risk.DANGER,
            params = listOf(
                ToolParam("path", "string", "绝对路径", required = true),
                ToolParam("recursive", "boolean", "目录是否递归删除（默认 false，只删空目录）")
            )
        ),
        // ---------- v43 浏览器自动化（应用内 WebView + JS；用户定向的差异化能力） ----------
        // （◆ 已随 2026-11-26 开源根治移入 app 的 可选设备源集——实现依赖
        //   ◆/ShellPolicy，属永不公开的设备控制栈；运行时经 ToolRegistry.registerExtra 注入。）
        ToolDef(
            name = "browser_open",
            cnDescription = "在应用内浏览器窗口打开网页（用户可见；登录由用户手动完成一次，之后复用 Cookie）",
            whenToUse = "用户要在网页上查东西/填表单/看登录后的页面时；打开后返回标题、URL、可交互元素与正文摘录",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("url", "string", "完整网址（http/https）", required = true))
        ),
        ToolDef(
            name = "browser_click",
            cnDescription = "点击网页元素（优先 CSS 选择器，其次按文字精确匹配）",
            whenToUse = "要点按钮/链接/菜单时；文字取 browser_open 返回的「可交互元素」里的原文",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("selector", "string", "CSS 选择器（如 #login、.btn-primary）"),
                ToolParam("text", "string", "元素文字（与页面完全一致，如 登录、查询）")
            )
        ),
        ToolDef(
            name = "browser_fill",
            cnDescription = "向网页输入框填写文字（触发 input/change，兼容受控输入框架）",
            whenToUse = "登录、搜索框、表单填写时；先 browser_open 拿到页面元素确认选择器",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("selector", "string", "输入框 CSS 选择器", required = true),
                ToolParam("text", "string", "要填入的文字", required = true)
            )
        ),
        ToolDef(
            name = "browser_extract",
            cnDescription = "提取网页文字（可按 CSS 选择器取某个区域，否则取整页正文），支持分段读",
            whenToUse = "页面已经打开、要读内容/核对结果时。长页按返回头的 offset 续读",
            params = listOf(
                ToolParam("selector", "string", "可选的 CSS 选择器（如 .article、#result）"),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始），长页按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        ToolDef(
            name = "browser_back",
            cnDescription = "网页回退一页",
            whenToUse = "点进去后要回上一页时",
            params = listOf()
        ),
        ToolDef(
            name = "browser_close",
            cnDescription = "关闭应用内浏览器窗口",
            whenToUse = "读网页不该用它（读正文用 web_fetch，无头渲染不弹窗）；" +
                "只有你为了**让用户登录/填表**打开过可见窗口，用户办完后用它收尾，别把窗口挂在那里",
            params = listOf()
        ),
        // ---------- 信息域 ----------
        // （◆ 已随 2026-11-26 开源根治移入 app 的 可选设备源集——实现依赖
        //   读屏采集服务，属永不公开的采集栈；运行时经 ToolRegistry.registerExtra 注入。）
        ToolDef(
            name = "digest_query", cnDescription = "查询信息提纯记录（按日期，支持 offset 分页）",
            whenToUse = "用户问最近群聊提纯/要点时",
            params = listOf(
                ToolParam("day", "string", "yyyy-MM-dd 或 今天/昨天，缺省=今天"),
                ToolParam("offset", "integer", "跳过最新的 N 条（缺省 0；当天提纯多时翻页用）")
            )
        ),
        ToolDef(
            name = "pending_query", cnDescription = "查询待确认的日程/待办草稿（可按类型/来源/月份/有无时间/事件日期区间过滤，支持分页）",
            whenToUse = "用户问「有哪些待处理」时；助理中枢批量审核时也用它翻草稿（积压可上千条：用 offset 分页翻完、用 month/before_date 圈定某月或某日期段）",
            params = listOf(
                ToolParam("kind", "string", "todo/schedule，缺省全部"),
                ToolParam("source", "string", "session=会话产生/capture=信息提取/manual=手动，缺省全部"),
                ToolParam("month", "string", "只查事件日期在指定月的草稿，如 10 或 2026-10（匹配「10月24日」这类时间描述；无时间描述的条目不算命中）"),
                ToolParam("has_time", "boolean", "true=只查有时间描述的；false=只查无时间描述的（这类多为过时残留，常用来定位无时间条目）；缺省不限"),
                ToolParam("before_date", "string", "只查事件日期**早于**该日的草稿（如 2026-11-15 或 今天；专门用于清「上半月已过期」那批）。无时间/解析不到日期的条目不算命中"),
                ToolParam("after_date", "string", "只查事件日期**晚于**该日的草稿（配合 before_date 圈定区间）"),
                ToolParam("offset", "integer", "跳过最新的 N 条（翻旧草稿用，缺省 0）"),
                ToolParam("limit", "integer", "本页最多返回几条（默认 20，上限 50）")
            )
        ),
        ToolDef(
            name = "pending_commit", cnDescription = "把一条待处理草稿正式入库为日程/待办（进每日时间轴），可顺带修正字段",
            whenToUse = "用户认可某条草稿时（编号来自 pending_query 或【待处理清单】）；排的时间与已有安排冲突要先跟用户确认",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "待处理草稿编号（#[n] 的 n）", required = true),
                ToolParam("title", "string", "修正标题（可选，缺省用草稿原标题）"),
                ToolParam("date", "string", "待办归属日 yyyy-MM-dd 或 今天/明天（kind=todo 时）"),
                ToolParam("start", "string", "日程开始 yyyy-MM-dd HH:mm（kind=schedule 时必填）"),
                ToolParam("end", "string", "日程结束，缺省=开始+1小时"),
                ToolParam("due", "string", "待办截止时刻 HH:mm（kind=todo 时可选，不传=无时间待办）"),
                ToolParam("place", "string", "地点（可选）"),
                ToolParam("priority", "string", "重要程度 high/normal/low（可选）"),
                ToolParam("urgency", "string", "紧急程度 high/normal/low（可选）"),
                ToolParam("repeat", "string", "重复规则，语法同 todo_create（可选）"),
                ToolParam("note", "string", "备注（可选）")
            )
        ),
        ToolDef(
            name = "pending_ignore", cnDescription = "把一条待处理草稿标记为忽略（不入库）",
            whenToUse = "用户明确说某条不用管/不用记时",
            params = listOf(
                ToolParam("id", "integer", "待处理草稿编号", required = true)
            )
        ),
        ToolDef(
            name = "pending_suggest", cnDescription = "给一条待处理草稿写上建议时段（不入库，卡片上显示建议）",
            whenToUse = "你排好了时间但用户还没拍板时——先 suggest，用户认可后再 pending_commit",            params = listOf(
                ToolParam("id", "integer", "待处理草稿编号", required = true),
                ToolParam("start", "string", "建议开始 yyyy-MM-dd HH:mm", required = true),
                ToolParam("end", "string", "建议结束 yyyy-MM-dd HH:mm，缺省=开始+1小时")
            )
        ),
        ToolDef(
            name = "pending_update", cnDescription = "修改一条待处理草稿的内容（标题/时间/地点/类型；草稿入库前改它）",
            whenToUse = "用户对助理中枢里的草稿有意见时（「这条应该是下午三点」「标题改成…」「地点不对」「这条其实是日程」）。" +
                "id 来自 pending_query 或【待处理清单】的草稿编号；改完仍是草稿，要 pending_commit 才进时间轴",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "待处理草稿编号（不是待办 id）", required = true),
                ToolParam("title", "string", "新标题（可选）"),
                ToolParam("time", "string", "新的时间描述，如 17:10 / 周四 16:10 / 11月13日 15:00（可选；会同步成入库用的日期与时刻）"),
                ToolParam("place", "string", "新地点（可选）"),
                ToolParam("kind", "string", "todo 或 schedule（可选，纠正草稿类型）")
            )
        ),
        ToolDef(
            name = "pending_purge", cnDescription = "批量忽略待处理草稿（按月份/类型/来源/时间有无/编号清单，至少给一个条件）",
            whenToUse = "用户明确要求批量清理某批草稿时（如「把 10 月的过期草稿都清掉」）。执行前必须先用 pending_query 核对命中条数与样例，并把条数报给用户确认——这个动作走确认卡",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("month", "string", "只清事件日期在该月的草稿，如 10 或 2026-10（匹配「10月24日」这类时间描述）"),
                ToolParam("before_date", "string", "只清事件日期**早于**该日的草稿（如 2026-11-15 或 今天）——清「上半月已过期、下半月还有效」时用这个，比 month 精确到日"),
                ToolParam("after_date", "string", "只清事件日期**晚于**该日的草稿（配合 before_date 圈定区间）"),
                ToolParam("kind", "string", "todo/schedule，缺省全部"),
                ToolParam("source", "string", "session/capture/manual，缺省全部"),
                ToolParam("has_time", "boolean", "true=只清有时间描述的；false=只清无时间描述的（这类更可能是过时残留）；缺省不限"),
                ToolParam("ids", "string", "显式编号清单，逗号分隔（如 1780,1781,1782）")
            )
        ),
        // ---------- 记忆域 ----------
        ToolDef(
            name = "memory_search", cnDescription = "按关键词检索长期记忆（打分召回）",
            whenToUse = "上下文没给到、需要查用户偏好/背景/教训时",
            params = listOf(ToolParam("query", "string", "检索词", required = true))
        ),
        ToolDef(
            name = "memory_add", cnDescription = "新增一条长期记忆",
            whenToUse = "用户明确说「记住…/以后都…」时；用户传授可复用做法时 type 用 skill",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "唯一名（英文短词，如 prefer-quiet）", required = true),
                ToolParam("type", "string", "project/feedback/reference/skill，缺省 project"),
                ToolParam("body", "string", "记忆正文（15-80字，具体）", required = true)
            )
        ),
        ToolDef(
            name = "memory_forget", cnDescription = "按名或 id 删除一条长期记忆",
            // 2026-11-25（◆）：用户说「删掉/全删/清掉/抹掉」时词法打分接不住
            // 「删除」，工具曾被路由挤出选集 → 模型断言「没有删除记忆的工具」。这里把
            // 常见口语措辞写进场景描述兜打分（同时它已进 ToolRouter.FLOOR 地板双保险）。
            whenToUse = "用户明确要求删除/删掉/清掉/抹掉/移除/忘掉某条记忆时（「全删」「都删了」也算）",
            risk = Risk.DANGER,
            params = listOf(ToolParam("name", "string", "记忆名或 id", required = true))
        ),
        ToolDef(
            name = "memory_invalidate", cnDescription = "作废长期记忆条目（标记失效、不删行，保留可追溯）",
            whenToUse = "用户表示某设定/说法作废、更换、不再算数时：先用 memory_search 找到承载旧说法的记忆条目，把它们的内部名逐条传进来作废并写明原因。**只新增一条「作废声明」而不作废旧条目不算完成**——旧条目仍会被召回，作废内容会反复冒出来",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("names", "string", "要作废的记忆内部名或 id，逗号分隔（如 a,b,c）", required = true),
                ToolParam("reason", "string", "作废原因（一句话，进审计历史）", required = true),
                ToolParam("replaced_by", "string", "取代它的新记忆名或 id（有就填，会写成「被取代」）")
            )
        ),
        // ---------- 系统域 ----------
        ToolDef(
            name = "now", cnDescription = "获取当前日期时间（含星期）",
            whenToUse = "需要精确当前时间计算相对日期时"
        ),
        ToolDef(
            name = "deep_plan", cnDescription = "规划器-工人模式：把复杂任务分解为不超过 4 个子任务并行执行（执行者模型）后汇总成一份答案",
            whenToUse = "用户要求深入/全面规划复杂任务、多角度研究、需要并行拆解时；简单问题不要用",
            params = listOf(ToolParam("task", "string", "要规划完成的任务描述（含目标与约束）", required = true))
        ),
        ToolDef(
            name = "ask_expert", cnDescription = "把专项问题委托给内置智能体并取回其答案（planner=日程规划与时间安排，digest=信息提纯与要点，tutor=教学讲解，writer=写作润色），支持分段读",
            whenToUse = "需要专项视角产出（排期方案、要点提炼、讲解、润色）时；智能体对用户不可见，其回答由你整合后输出。回答超长时按返回头的 offset 续读，不要把半截答案当全文",
            params = listOf(
                ToolParam("agent", "string", "planner/digest/tutor/writer", required = true),
                ToolParam("question", "string", "要委托的问题（自包含，智能体看不到本对话）", required = true),
                ToolParam("offset", "integer", "从第几个字符开始读回答（0 开始），按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000）")
            )
        ),
        ToolDef(
            name = "undo_last_write", cnDescription = "撤销本会话最近一次写操作（删除刚建的日程/待办/笔记，还原修改）",
            whenToUse = "用户说「撤销/算了/把刚才建的删掉」时",
            risk = Risk.CONFIRM
        ),
        ToolDef(
            name = "usage_report", cnDescription = "汇报本会话 token 用量与工具调用次数",
            whenToUse = "用户问「用了多少 token/成本」时"
        ),
        // ---------- 全量历史与趋势（system 域，常开） ----------
        ToolDef(
            name = "data_history",
            cnDescription = "查某类数据的全部历史记录统计与变化趋势（从最早记录到今天）",
            whenToUse = "用户想看某类数据的「整体情况/历史变化趋势/一共多少/完成率走势」时调用，拿到趋势数据后建议用 ```chart 块画图可视化。注意：todo/schedule 按**主记录**计数——每周重复的课只算 1 条；「课表/某天有什么/是否每周重复」这类问题必须用 timeline_query，本工具答不了。",
            params = listOf(
                ToolParam(
                    "type", "string",
                    "数据类型：todo=待办 / schedule=日程 / note=笔记 / digest=信息提纯 / usage=AI用量 / sleep=睡眠 / checkin=打卡记录（待办打卡时记下的时间/数值/次数/文本）",
                    required = true
                ),
                ToolParam("days", "integer", "趋势统计的最近天数，如 30/90/365，缺省=全部历史")
            )
        ),
        ToolDef(
            name = "sleep_query", cnDescription = "查询最近的睡眠记录（时长/入睡/醒来时间，来自 OPPO 健康采集）",
            whenToUse = "用户问「昨晚睡得怎么样/睡了多久/最近睡眠情况」时",
            params = listOf(ToolParam("days", "integer", "查询最近几天，缺省 7"))
        ),
        ToolDef(
            name = "screen_time_query", cnDescription = "查询某天屏幕使用时间（总时长 + 分 App 排行）",
            whenToUse = "用户问「我今天手机用多久/哪个 App 用得最多」时",
            params = listOf(ToolParam("date", "string", "yyyy-MM-dd 或 今天/昨天，缺省今天"))
        ),
        // ---------- 自动化任务（v11，automation 域） ----------
        ToolDef(
            name = "automation_list", cnDescription = "列出自动化任务（定时让 AI 执行并通知）",
            whenToUse = "用户问自动化/定时任务有哪些时"
        ),
        ToolDef(
            name = "automation_create",
            cnDescription = "创建自动化任务或快捷指令：可按间隔/每日时刻自动执行一段 AI 指令并通知结果（后续每次执行的 AI 回合都追加在当前这个对话里，不新开会话），也可创建**不自动触发**的快捷指令",
            whenToUse = "用户要「每天 X 点自动做…/每隔 N 分钟帮我…」这类定时 AI 任务时；或要「随时能点一下就跑的快捷指令」时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "任务名称", required = true),
                ToolParam("prompt", "string", "每次触发执行的 AI 指令", required = true),
                ToolParam("assistantKey", "string", "智能体键 general/planner/digest/tutor/writer，缺省 general"),
                ToolParam("intervalMinutes", "integer", "间隔分钟（如 60/1440）；与每日时刻二选一"),
                ToolParam("dailyHour", "integer", "每日执行小时 0-23；与 interval 二选一"),
                ToolParam("dailyMinute", "integer", "每日执行分钟 0-59，缺省 0"),
                ToolParam(
                    "manual", "boolean",
                    "true = 不自动触发（存成快捷指令，只在应用里点「运行」执行）；" +
                        "此时不填 intervalMinutes/dailyHour（PC 端的同名工具**没有**这个参数，见跨端文档 §3.10）"
                ),
                ToolParam(
                    "steps", "string",
                    "**不经过 AI 的确定性流水线**，JSON 数组：" +
                        "[{\"type\":\"app_open\",\"params\":{\"app\":\"设置\"}}," +
                        "{\"type\":\"◆_text\",\"params\":{\"text\":\"确认\"}}]。" +
                        "给了 steps 就建流水线任务（prompt 可空）；不给才是 AI 型任务。" +
                        "type 只能取 automation_actions 列出来的那些，拼错会当场报回来"
                ),
                ToolParam(
                    "event", "string",
                    "事件触发：boot（开机/升级后）/ power:on（插上电）/ power:off（拔电）/ " +
                        "unlock（解锁后）/ app_open:包名（如 app_open:com.tencent.mm）。" +
                        "给了它就按事件触发，与间隔/每日时刻互斥" +
                        "（「打开应用」需要用户已开启无障碍；PC 端没有这个参数，见 §3.10）"
                )
            )
        ),
        ToolDef(
            name = "automation_actions",
            cnDescription = "列出流水线可用的动作及各自参数（**不调 AI 的那些**）",
            whenToUse = "要建/改一条不经过 AI 的快捷指令流水线之前——动作名和参数名只能从这里取，别猜",
            risk = Risk.AUTO,
            params = listOf(
                ToolParam("q", "string", "按关键词过滤动作名/说明（可选），如 滑动 / 无障碍 / 文件")
            )
        ),
        ToolDef(
            name = "automation_run",
            cnDescription = "立刻运行一条自动化或快捷指令（按名字），并回报它成没成",
            whenToUse = "用户说「现在跑一下那条…」时；也用于调试刚建的指令",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "指令名（完整名；只有一个片段时按包含匹配）", required = true)
            )
        ),
        ToolDef(
            name = "automation_pin",
            cnDescription = "把一条指令**钉到桌面**，成一个点一下就跑的图标",
            whenToUse = "用户说「放到桌面上」「我要一键跑那条」时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "指令名（完整名；只有一个片段时按包含匹配）", required = true)
            )
        ),
        ToolDef(
            name = "automation_edit", cnDescription = "修改自动化任务（名称/指令/触发/启停）",
            whenToUse = "用户要改或暂停/恢复某个自动化任务时；id 来自 automation_list",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "自动化任务 id", required = true),
                ToolParam("name", "string", "新名称（可选）"),
                ToolParam("prompt", "string", "新指令（可选）"),
                ToolParam("assistantKey", "string", "智能体键（可选）"),
                ToolParam("intervalMinutes", "integer", "新间隔分钟（可选）"),
                ToolParam("dailyHour", "integer", "新每日小时（可选）"),
                ToolParam("dailyMinute", "integer", "新每日分钟（可选）"),
                ToolParam(
                    "event", "string",
                    "改成事件触发：boot / power:on / power:off / unlock / app_open:包名；" +
                        "传 none = 去掉事件触发。给了它会清掉间隔与每日时刻（两者互斥）"
                ),
                ToolParam("enabled", "boolean", "启停（可选）")
            )
        ),
        ToolDef(
            name = "automation_delete", cnDescription = "删除自动化任务",
            whenToUse = "用户明确要删除某个自动化任务时",
            risk = Risk.DANGER,
            params = listOf(ToolParam("id", "integer", "自动化任务 id", required = true))
        ),
        // v35：执行日志是**只读**工具，所以不设 risk（默认 AUTO，ro 档也能查）。
        // 它补的是「到点跑没跑、跑成什么样」的回溯面——
        // 在这之前失败只进 logcat，模型和用户都查不到任何执行历史。
        ToolDef(
            name = "automation_log",
            cnDescription = "查自动化任务的执行记录：最近几次在什么时间跑的、成功还是失败、结果摘要与失败原因",
            whenToUse = "用户问「自动化有没有在跑/上次结果是什么/为什么没收到/是不是一直失败」时；id 来自 automation_list",
            params = listOf(
                ToolParam("id", "integer", "自动化任务 id；不传 = 查全部任务的最近记录"),
                ToolParam("limit", "integer", "最多返回多少条（1-50，缺省 20）")
            )
        ),
        // ---------- 扩展管理（v11，extend 域）：技能/插件/智能体/MCP ----------
        ToolDef(
            name = "skill_list", cnDescription = "列出技能（skill 类型记忆）",
            whenToUse = "用户问有哪些技能/可复用做法时"
        ),
        ToolDef(
            name = "plugin_toggle", cnDescription = "开/关插件能力包（office/web/ics）",
            whenToUse = "用户要启用或停用某个能力包时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("plugin", "string", "office / web / ics", required = true),
                ToolParam("enabled", "boolean", "true=启用 false=停用", required = true)
            )
        ),
        ToolDef(
            name = "assistant_list", cnDescription = "列出智能体（内置+自定义）",
            whenToUse = "用户问有哪些智能体/助手时"
        ),
        ToolDef(
            name = "assistant_create", cnDescription = "创建自定义智能体（人格）",
            whenToUse = "用户要新建一个有人格/专长的智能体时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "智能体名称", required = true),
                ToolParam("systemPrompt", "string", "人格 system prompt", required = true),
                ToolParam("emoji", "string", "图标 emoji，缺省 🤖"),
                ToolParam("slogan", "string", "一句话介绍（可选）")
            )
        ),
        ToolDef(
            name = "assistant_edit", cnDescription = "修改智能体（内置与自定义都可改，改人格/名称/介绍）",
            whenToUse = "用户要修改某个智能体的人设时；id 来自 assistant_list",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("id", "integer", "智能体 id", required = true),
                ToolParam("name", "string", "新名称（可选）"),
                ToolParam("systemPrompt", "string", "新人格（可选）"),
                ToolParam("emoji", "string", "新图标（可选）"),
                ToolParam("slogan", "string", "新介绍（可选）")
            )
        ),
        ToolDef(
            name = "assistant_delete", cnDescription = "删除自定义智能体（内置的不可删）",
            whenToUse = "用户明确要删除某个自定义智能体时",
            risk = Risk.DANGER,
            params = listOf(ToolParam("id", "integer", "智能体 id", required = true))
        ),
        ToolDef(
            name = "mcp_list", cnDescription = "列出已配置的 MCP 服务器",
            whenToUse = "用户问 MCP 服务器/外部工具接入情况时"
        ),
        ToolDef(
            name = "mcp_connect", cnDescription = "添加或更新 MCP 服务器（Streamable HTTP 端点），保存后其工具自动注入对话",
            whenToUse = "用户要接入一个 MCP 服务器（给出 URL）时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "服务器名称", required = true),
                ToolParam("url", "string", "MCP 端点 URL", required = true),
                ToolParam("headers", "string", "额外请求头 JSON，如 {\"Authorization\":\"Bearer x\"}（可选）")
            )
        ),
        ToolDef(
            name = "mcp_disconnect", cnDescription = "删除 MCP 服务器",
            whenToUse = "用户明确要移除某个 MCP 服务器时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("name", "string", "服务器名称", required = true))
        ),
        // ---------- v12：文件库（全局与项目文件，AI 与用户共用） ----------
        // 2026-10-29：文件库根 = 「项目数据」（当前会话/已归档会话/项目记忆/项目文件 四个子目录）。
        // 裸名写入默认进「项目文件」；手机存储里用户自己的文件不在这里（要用 fs_* 工具读，只读不改）。
        ToolDef(
            name = "file_library_list", cnDescription = "列出文件库树（根=各项目的\"项目数据\"：当前会话/已归档会话/项目记忆/项目文件；含子目录相对路径/字数；二进制文件标\"二进制\"）",
            whenToUse = "用户要查看自己的资料文件、或需要知道有哪些项目文件可引用时",
            params = listOf(ToolParam("project", "string", "项目名，缺省=全部（含全局）"))
        ),
        ToolDef(
            name = "file_library_read", cnDescription = "读取文件库中一份文件的全文，支持从指定位置分段读",
            whenToUse = "需要引用某份资料的具体内容时。返回头写明「第 X–Y 字 / 共 N 字」；长文稿（小说稿、笔记）没读完就按头里的 offset 续读",
            params = listOf(
                ToolParam("name", "string", "文件名或相对\"项目数据\"的路径（file_library_list 里看到的名称）", required = true),
                ToolParam("project", "string", "项目名，缺省=全局与各项目内自动匹配"),
                ToolParam("offset", "integer", "从第几个字符开始读（0 开始）。首次不传=0；长文件按返回头的 offset 续读"),
                ToolParam("limit", "integer", "本次最多读多少字符（默认 8000，上限 200000）")
            )
        ),
        ToolDef(
            // 2026-11-17 用户实报：「AI 每次让我生成/修改计划，都跑去写 .md 计划文件」。
            // 这里的示例路径原来全是「项目文件/计划/步骤.md」「计划/2026」——工具描述是
            // 模型最容易照抄的模板，等于天天在教它「计划 = 项目文件里的 md」。
            // 示例换成中性目录，并在 whenToUse 里明说「计划不落文件」。
            name = "file_library_write", cnDescription = "新建或更新文件库文件（同名覆盖；裸名写入默认落\"项目文件\"目录，带相对路径则相对\"项目数据\"，目录不存在会自动创建——写 项目文件/资料/会议纪要.md 等于先建好「资料」文件夹再写文件）",
            whenToUse = "用户要把资料存入文件库（全局或某项目的项目数据）、或要按目录分类归档文件时。**用户说的「计划」不要用本工具落盘**——计划走 ```plan 卡或 `- [ ]` 勾选清单（工作流面板里那份），只有用户明确要求「导出/归档成文档」才写文件",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "文件名（如 会议纪要.md，落\"项目文件\"）或相对\"项目数据\"的路径（如 项目文件/资料/会议纪要.md）", required = true),
                ToolParam("content", "string", "全文内容", required = true),
                ToolParam("project", "string", "项目名，缺省=全局")
            )
        ),
        // 2026-10-31 用户要求「告知 AI 可以在项目文件的文件夹中新建文件夹 / 管理项目文件树」：
        // 此前只有 write（能隐式建目录）与 delete（只能删文件），没有显式建目录，也没有改名/移动。
        ToolDef(
            name = "file_library_mkdir", cnDescription = "在文件库中新建文件夹（可多级：资料/2026 → 项目文件/资料/2026）。裸路径默认建在\"项目文件\"下；已存在视为成功",
            whenToUse = "用户要求新建文件夹整理资料，或要先把目录结构建好再往里写文件时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("path", "string", "文件夹路径（如 资料/2026，落\"项目文件/资料/2026\"；也可写完整的 项目文件/资料/2026）", required = true),
                ToolParam("project", "string", "项目名，缺省=全局")
            )
        ),
        ToolDef(
            name = "file_library_rename", cnDescription = "重命名文件库中的文件或文件夹（文件的新名可带目录前缀实现移动，如 归档/2026.md；文件夹只改本级名字；目标已存在则拒绝，不覆盖）",
            whenToUse = "用户要求给文件/文件夹改名，或把文件挪到别的文件夹时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("path", "string", "原文件或文件夹的相对\"项目数据\"路径", required = true),
                ToolParam("new_name", "string", "新名字（文件可带目录前缀 = 移动到该目录，目录需已存在）", required = true),
                ToolParam("project", "string", "项目名，缺省=全局")
            )
        ),
        ToolDef(
            name = "file_library_delete", cnDescription = "删除文件库中的一份文件或整个文件夹（软删除；name 可含子目录相对路径，命中文件夹时递归删除其内容）",
            whenToUse = "用户明确要求删除某份文件或某个文件夹时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("name", "string", "文件名或文件夹的相对路径（相对\"项目数据\"）", required = true),
                ToolParam("project", "string", "项目名，缺省=全局")
            )
        ),
        // ---------- v12：AI 全设置可操作 ----------
        ToolDef(
            name = "model_switch", cnDescription = "切换默认对话模型（等价模型设置页操作）",
            whenToUse = "用户要求换模型、用某个模型回答时",
            risk = Risk.CONFIRM,
            params = listOf(ToolParam("model", "string", "模型名（须已在模型管理中启用）", required = true))
        ),
        ToolDef(
            name = "capability_toggle", cnDescription = "开启或关闭基础能力域（schedule/todo/note/file/info/memory/automation/extend）",
            whenToUse = "用户要求打开或关闭某类 AI 能力时",
            risk = Risk.CONFIRM,
            params = listOf(
                ToolParam("domain", "string", "域 key", required = true),
                ToolParam("enabled", "boolean", "true=开启，false=关闭", required = true)
            )
        ),
        // ---------- 手机操控（phone use 插件：类 computer use，模型直接操作手机） ----------
        // （9 个 phone_* 工具已随 2026-11-26 开源根治移入 app 的 可选设备源集——实现
        //   依赖读屏采集与坐标闸门，属永不公开的设备控制栈；运行时经 ToolRegistry.registerExtra
        //   注入。）
    )

    fun byName(name: String): ToolDef? = ALL.firstOrNull { it.name == name }

    /** 工具域 → 工具名（能力开关用；system 域常开）。公开内置集，[DOMAINS] 在其上叠加注册集。 */
    private val BUILTIN_DOMAINS: Map<String, List<String>> = mapOf(
        "schedule" to listOf("schedule_query", "schedule_create", "schedule_update", "schedule_delete_today", "schedule_delete", "timeline_query"),
        // 2026-10-31 补齐计划域缺口（用户实锤「AI 连重复待办都建不了」）：todo_edit/delete、
        // 重复参数、打卡数据、分类总览。checkin_* 归 todo 域（数据记录是待办的一部分）。
        "todo" to listOf(
            "todo_query", "todo_create", "todo_complete", "todo_edit", "todo_delete",
            "checkin_query", "checkin_add", "plan_category_list", "plan_category_create"
        ),
        "note" to listOf(
            "library_search", "note_search", "kb_ask", "note_read", "note_create", "note_append",
            "note_edit", "note_delete", "note_link", "notebook_list", "notebook_create"
        ),
        // 2026-10-31：专注域（补记/查询专注会话；AI 不遥控实时计时）
        "focus" to listOf("focus_query", "focus_add"),
        // v41（2026-10-20 全量工具盘查）：file_read_image / sleep_query / work_plan_start /
        // automation_actions / automation_run / automation_pin 曾经**谁都没登记**——
        // 定义、实现、UI 文案都在，但 toToolsJson 按 DOMAINS+PLUGINS 过滤，模型永远看不到它们，
        // 提示词里却让模型去调（"automation_actions 查动作"、"睡眠调 sleep_query"），
        // 结果只能是"未知工具"。ToolDefsTest.everyToolIsReachableSomewhere 现在守着这条。
        "file" to listOf(
            "file_list", "file_read_text", "file_read_image", "file_export",
            "file_library_list", "file_library_read", "file_library_write",
            "file_library_mkdir", "file_library_rename", "file_library_delete",
            // v43：真实文件面（fs_*），与 file 域一起开关
            "fs_list", "fs_read_text", "fs_search", "fs_write_text", "fs_mkdir", "fs_move", "fs_delete"
        ),
        "info" to listOf("digest_query", "pending_query", "weekly_query", "pending_add", "pending_commit", "pending_ignore", "pending_suggest", "pending_update", "pending_purge"),
        // 2026-11-12 memory_invalidate：显式作废（◆ 实锤，见 ToolDefs 记忆域 def）
        "memory" to listOf("memory_search", "memory_add", "memory_forget", "memory_invalidate"),
        // 2026-10-31 用户拍板删除 work_plan_start（"把计划落成工作进程清单"那条线）：
        // 它会主动推销"要不要落地成工作进程清单"，而用户早在基线前就废掉了超长计划+阶段复盘机制，
        // 不需要任何形态的"AI 主动开工"。历史 .md 清单的可打勾渲染保留（文件预览照常能看）。
        "system" to listOf(
            "now", "deep_plan", "ask_expert", "undo_last_write", "usage_report", "data_history",
            "sleep_query", "screen_time_query",
            "achievement_query", "trash_query", "trash_restore"
        ),
        // v43：Shell 巡检——已已移入可选设备功能包（注册时注入 shell 域）
        // v43：浏览器自动化（应用内 WebView）
        "browser" to listOf("browser_open", "browser_click", "browser_fill", "browser_extract", "browser_back", "browser_close"),
        "automation" to listOf(
            "automation_list", "automation_create", "automation_edit", "automation_delete", "automation_log",
            "automation_actions", "automation_run", "automation_pin"
        ),
        "extend" to listOf(
            "skill_list", "plugin_toggle", "assistant_list", "assistant_create", "assistant_edit",
            "assistant_delete", "mcp_list", "mcp_connect", "mcp_disconnect",
            "model_switch", "capability_toggle"
        )
    )

    /** 插件能力包（可装卸的新增工具；office=办公三件套）。公开内置集，[PLUGINS] 在其上叠加注册集。 */
    private val BUILTIN_PLUGINS: Map<String, List<String>> = mapOf(
        // office 包 = 办公文件相关工具。v14.3 起 pptx 走独立的 file_build_deck：
        // 实测（真机）office 包不列它，模型就会拿 file_export_document 顶着做，
        // 传个 .pptx 文件名配 docx 内容，产出一个打不开的假 pptx。
        // 所以新工具必须登记在这里，否则"写了但模型看不见"。
        "office" to listOf(
            "file_read_document", "file_export_document",
            "file_build_deck", "file_edit_deck",
            "file_edit_document", "file_edit_sheet"
        ),
            "web" to listOf("web_fetch", "web_search"),
        "ics" to listOf("calendar_export_ics"),
        // phone 包（9 个 phone_* 工具）已已移入可选设备功能包（注册时注入）
    )

    /** 全量定义（公开内置 + 设备包注册）。 */
    val ALL: List<ToolDef> get() = BUILTIN + extraDefs

    /** 工具域 → 工具名（公开内置 + 设备包注册；能力开关与设置 UI 的清单来源）。 */
    val DOMAINS: Map<String, List<String>> get() = BUILTIN_DOMAINS + extraDomains

    /** 插件能力包（公开内置 + 设备包注册）。 */
    val PLUGINS: Map<String, List<String>> get() = BUILTIN_PLUGINS + extraPlugins

    // ===== 设备包注册集（2026-11-26 开源根治）=====
    @Volatile private var extraDefs: List<ToolDef> = emptyList()
    @Volatile private var extraDomains: Map<String, List<String>> = emptyMap()
    @Volatile private var extraPlugins: Map<String, List<String>> = emptyMap()
    @Volatile private var extraVersion: Int = 0

    /**
     * 设备功能包（app 的 可选设备源集）注入私密工具：定义 + 域/插件分组一次性注册。
     * 只许在应用启动早期（DeviceFeaturesInstaller）调用一次；开源版从不调用，
     * [ALL]/[DOMAINS]/[PLUGINS] 保持纯公开集。
     */
    fun registerExtra(
        defs: List<ToolDef>,
        domains: Map<String, List<String>> = emptyMap(),
        plugins: Map<String, List<String>> = emptyMap(),
    ) {
        extraDefs = extraDefs + defs
        extraDomains = extraDomains + domains
        extraPlugins = extraPlugins + plugins
        extraVersion++
    }

    /** 全量工具 schema（function calling） */
    fun toToolsJson(): JSONArray = toToolsJson(null, null)

    // v40（成本优化 1.2，ref-cost-study §7-1.2）：schema 序列化 memo。
    // 72 个工具的全量 schema 每轮重新序列化是数千 token 级的**重复** CPU 开销，且产物逐字节相同。
    // key = (enabledDomains, enabledPlugins)：设置变更自然产生新 key，无需失效回调。
    // 缓存的是**每工具 JSONObject 的只读列表**；返回时套一层新的 JSONArray ——
    // McpBridge.appendTools 会在轮 0 往返回数组里追加 MCP 工具，若共享外层数组，
    fun toToolsJson(enabledDomains: Set<String>?, enabledPlugins: Set<String>?): JSONArray {
        val version = extraVersion
        val memo = schemaMemo
        val cached = memo
            ?.takeIf { it.first == enabledDomains && it.second == enabledPlugins && it.third == version }
            ?.fourth
        val list = if (cached != null) cached else {
            val allowed = mutableSetOf<String>()
            DOMAINS.forEach { (d, tools) -> if (enabledDomains == null || d in enabledDomains) allowed += tools }
            PLUGINS.forEach { (p, tools) -> if (enabledPlugins == null || p in enabledPlugins) allowed += tools }
            ALL.filter { it.name in allowed }.map { it.toJsonSchema() }
        }
        if (cached == null) schemaMemo = Quadruple(enabledDomains, enabledPlugins, version, list)
        return JSONArray().apply { list.forEach { put(it) } }
    }

    // v40：schema 序列化 memo 存储（原 app 侧字段，随 toToolsJson 一起迁）。
    // 2026-11-26：key 增加注册版本号——registerExtra 注入私密工具后旧 memo 必须失效。
    private var schemaMemo: Quadruple<Set<String>?, Set<String>?, Int, List<JSONObject>>? = null

    /** 轻量四元组（避免为 memo 引入整型 boxing 的 pair 嵌套）。 */
    private data class Quadruple<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)

    /**
     * 启用的工具域**解析**（M4：从原 suspend enabledDomains 抽出的纯半边——IO 在 app 侧
     * 扩展函数里读 Graph.db 后调用这里，解析规则本身是契约的一部分，必须单源）。
     * KV "tool.domains"，缺省=全部（null）；空串=全部。
     */
    fun parseDomains(raw: String?): Set<String>? {
        val set = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: return null
        if (set.isEmpty()) return null
        // v11：后加入的新域默认并入老用户的固化白名单（一次性行为，之后以 KV 为准）
        return set + setOf("automation", "extend")
    }

    /**
     * 启用的插件能力包**解析**（纯半边，同上）。KV "plugin.enabled"，缺省=全部（null）。
     */
    fun parsePlugins(raw: String?): Set<String>? {
        val set = raw?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }?.toSet() ?: return null
        return set.ifEmpty { null }
    }
}
