package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.Recurrence
import io.github.m0use001.planability.data.Schedule
import io.github.m0use001.planability.data.ScreenTimeStats
import io.github.m0use001.planability.data.Todo
import org.json.JSONObject

/**
 * 计划域的**第二批**工具实现（2026-10-31 补齐「AI 写不了重复待办」缺口）：
 * todo_edit / todo_delete / schedule_delete / checkin_query / checkin_add /
 * screen_time_query / plan_category_list。
 *
 * 为什么单独一个文件：`ToolExecImpls.execute` 已两次撞 JVM 单方法 64KB 上限
 * （fs_* 拆成 FsShellTools、file_library_* 拆成 LibraryTools），这批再往里塞
 * 必然第三次，按同一先例直接开新文件。todo_create / schedule_create 的**参数**
 * 扩展仍在原分支里改（只加几行），新增工具全部分流到这里。
 *
 * 重复参数契约（跨端，PC 端同形实现见 agents_log [assign]）：
 *  - `repeat`：`none`/`daily`/`weekly:1,3`/`biweekly:1,3`/`monthly`/`monthlyW:2:3`/
 *    `yearly`/`interval:14`（与日程 repeatRule 头部同语法，1=周一…7=周日）；
 *    monthly 按锚点日号，monthlyW=每月第 N 个星期 X，yearly 按锚点月日。
 *  - `repeat_until`：yyyy-MM-dd，缺省=永久。
 *  解析直接复用 [Recurrence.parseScheduleRule]，不另写语法。
 */
internal object PlanTools {

    /** 处理本批工具；不认识的名字返回 null（调用方继续原分发）。 */
    suspend fun run(name: String, a: JSONObject, sessionId: Long): ToolExecResult? {
        return when (name) {
            // ---------- 2026-10-31 补齐计划域（新建也挪进来：execute 第三次撞 64KB 上限） ----------
            "todo_create" -> todoCreate(a, sessionId)
            "schedule_create" -> scheduleCreate(a, sessionId)
            "schedule_update" -> scheduleUpdate(a, sessionId)
            "schedule_delete_today" -> scheduleSkipDay(a, sessionId)
            // ---------- 第二批新工具 ----------
            "todo_edit" -> todoEdit(a, sessionId)
            "todo_delete" -> todoDelete(a, sessionId)
            "schedule_delete" -> scheduleDelete(a, sessionId)
            "checkin_query" -> checkinQuery(a)
            "checkin_add" -> checkinAdd(a, sessionId)
            "screen_time_query" -> screenTimeQuery(a)
            "plan_category_list" -> categoryList(a)
            "plan_category_create" -> categoryCreate(a)
            "timeline_query" -> timelineQuery(a)
            else -> null
        }
    }

    /** v53 助理中枢：某天的时间轴（已排日程 + 带时刻待办），排期/查冲突用 */
    private suspend fun timelineQuery(a: JSONObject): ToolExecResult {
        val day = a.optString("day").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: return ToolExecResult.fail("日期看不懂：" + it) }
            ?.toLocalDate() ?: AppTime.today()
        // 2026-11-12（真机会话实录）：用户问「我的课表/这周安排」，旧实现一次只能查一天，
        // 模型连打 6 轮工具逐日铺——而且它先是拿 data_history（数主记录条数）答课表，
        // 得出「没有每周重复」的错误结论。加 days 参数一次展开一段；上限 31 天防刷屏。
        val days = (a.optInt("days", 1).takeIf { it > 0 } ?: 1).coerceAtMost(31)
        val all = mutableListOf<String>()
        for (i in 0 until days) {
            val d = day.plusDays(i.toLong())
            val dayKey = AppTime.dayKey(d)
            val lines = mutableListOf<String>()
            runCatching {
                Graph.plan.scheduleOccurrences(d, d).sortedBy { it.start }.forEach { o ->
                    lines.add(
                        AppTime.millisToTime(o.start) + "-" + AppTime.millisToTime(o.end) +
                            " 日程 " + o.schedule.title +
                            (if (o.schedule.place.isNotBlank()) " @" + o.schedule.place else "")
                    )
                }
            }
            runCatching {
                Graph.db.todoDao().byDate(dayKey)
                    .filter { !it.done && it.due > 0 }
                    .sortedBy { it.due }
                    .forEach { t ->
                        val endAt = if (t.endAt > 0) t.endAt else t.due + 30 * 60_000L
                        lines.add(
                            AppTime.millisToTime(t.due) + "-" + AppTime.millisToTime(endAt) +
                                " 待办 " + t.title + (if (t.place.isNotBlank()) " @" + t.place else "")
                        )
                    }
            }
            if (lines.isEmpty()) {
                all.add(ToolExecImpls.fmtDay(dayKey) + " 没有已排的日程或带时刻的待办")
            } else {
                all.add(ToolExecImpls.fmtDay(dayKey) + " 共 " + lines.size + " 项：\n" + lines.joinToString("\n"))
            }
        }
        val total = all.sumOf { blk -> blk.lineSequence().count() - 1 }   // 每块首行是日期头
        if (total <= 0) {
            return ToolExecResult.ok(ToolExecImpls.fmtDay(AppTime.dayKey(day)) + " 起 " + days + " 天内没有已排的日程或带时刻的待办", "区间空闲")
        }
        return ToolExecResult.ok(all.joinToString("\n"), "时间轴 " + days + " 天")
    }

    // ---------- 执行层存在性校验（P0-1，2026-11-10） ----------

    /**
     * id 查不到时的失败文案。弱模型最常犯的错是拿「助理中枢草稿编号」当表 id（2026-11-09
     * 审计两次动态复现）——所以这里多查一步 pending_items：命中了就把「这是草稿、先
     * pending_commit / todo_query 拿真实 id」直接写进报错，模型下一轮就能改道。
     */
    private suspend fun missingIdMsg(kind: String, id: Long): String {
        val draft = runCatching { Graph.info.pendingItems().firstOrNull { it.id == id } }.getOrNull()
        return if (draft != null) {
            "编号 $id 是助理中枢的草稿「" + draft.title + "」，不是" + kind +
                " id。草稿要先 pending_commit 入库，再用 todo_query/schedule_query 查真实编号。"
        } else {
            kind + " id=" + id + " 不存在（先用 todo_query/schedule_query 查真实编号）"
        }
    }

    // ---------- 重复参数 → Recurrence（create/edit 共用） ----------

    /**
     * 从参数里取重复规则。返回 (规则, 错误文案)：参数没提 → (null, null)；
     * 写了但看不懂 → (null, 错误)。`none`/空串 = 明确取消重复（返回 [Recurrence.NONE]）。
     */
    fun repeatOf(a: JSONObject): Pair<Recurrence?, String?> {
        if (!a.has("repeat")) return null to null
        val raw = a.optString("repeat").trim().lowercase()
        if (raw.isEmpty() || raw == "none" || raw == "不重复") return Recurrence.NONE to null
        val rec = Recurrence.parseScheduleRule(raw)
        if (!rec.repeats) return null to "看不懂的重复规则「" + raw + "」：" + REPEAT_SYNTAX
        val untilRaw = a.optString("repeat_until").trim()
        if (untilRaw.isEmpty()) return rec to null
        val until = ToolExecImpls.parseDateTime(untilRaw)
            ?: return null to "截止日期看不懂：「" + untilRaw + "」（要 yyyy-MM-dd）"
        return rec.copy(untilDayKey = AppTime.dayKeyOf(until)) to null
    }

    const val REPEAT_SYNTAX =
        "可用：daily / weekly:1,3（1=周一…7=周日）/ biweekly:1,3（每两周）/ monthly（每月按日号）/ " +
            "monthlyW:2:3（每月第2个周三）/ yearly / interval:14（每14天）"

    /** 把规则落到待办的四列 + permanent 上（parseTodoRule 的精确逆变换） */
    fun todoWithRepeat(t: Todo, rec: Recurrence): Todo {
        val (type, days, interval) = Recurrence.toTodoParts(rec)
        return t.copy(
            repeatType = type, repeatDays = days, repeatInterval = interval,
            repeatEnd = rec.untilDayKey, permanent = rec.untilDayKey == 0L
        )
    }

    /** 待办/日程行里给模型看的重复短语（describeCompact 紧凑口径，列表同款） */
    fun todoRepeatText(t: Todo): String =
        if (t.repeatType == "none") ""
        else " [重复:" + Recurrence.parseTodoRule(t).describeCompact(t.date.toLocalDate()) + "]"

    // ---------- todo_create / schedule_create / schedule_update / schedule_delete_today ----------
    //（2026-10-31 从 ToolExecImpls 挪入：参数接入重复/标签后 execute 第三次撞 64KB 上限）

    private suspend fun todoCreate(a: JSONObject, sessionId: Long): ToolExecResult {
        val title = a.optString("title").trim()
        if (title.isEmpty()) return ToolExecResult.fail("标题为空")
        val parsed = a.optString("date").takeIf { it.isNotBlank() }?.let { ToolExecImpls.parseDateTime(it) }
            ?: AppTime.dayKey(AppTime.today())
        val dayKey = AppTime.dayKeyOf(parsed)
        // 2026-11-02 用户：「AI 建的待办也不设置紧急-重要」——原来缺省 priority=normal
        // 会把 importance 写成 2（中），模型不传也成了「设置过」。改 OrNull 语义：
        // 不传 → importance=-1（未设置），priority 落派生值 1（与 PriorityMap.priorityOf(-1) 一致）。
        // 2026-10-31：重复 / 分类标签 / 自由标签 / 截止时刻 / 地点坐标联动
        //（此前一样都没有——用户实锤「AI 连重复待办都建不了」）
        val (rec, recErr) = repeatOf(a)
        if (recErr != null) return ToolExecResult.fail(recErr)
        // 标签名对不对**现在就校验**（错误早返回，模型能马上改参数重试）；
        // 真正的 tagId 解析留给 PendingImporter（入库时才碰分类表）
        val (_, tagErr) = tagIdOf(a)
        if (tagErr != null) return ToolExecResult.fail(tagErr)
        val place = a.optString("place")
        val due = a.optString("due").takeIf { it.isNotBlank() }
            ?.let { raw -> ToolExecImpls.parseDateTime(raw) ?: return ToolExecResult.fail("截止时刻看不懂：" + raw) }
            ?: 0L
        // v54 统一审核：不再直接 saveTodo，改成助理中枢草稿（payload=原始参数，
        // 入库时 PendingImporter 按 payload 忠实重建重复/优先级/标签）。直接进时间轴的唯一入口是 pending_commit。
        val createdAt = AppTime.nowMillis()
        val item = io.github.m0use001.planability.data.PendingItem(
            title = title, kind = "todo",
            timeText = ToolExecImpls.fmtDay(dayKey) + (if (due > 0) " " + AppTime.millisToTime(due) else "") +
                (rec?.takeIf { it.repeats }?.let { " [重复:" + it.describeCompact(dayKey.toLocalDate()) + "]" } ?: ""),
            place = place,
            extra = listOf(a.optString("note"), a.optString("labels")).filter { it.isNotBlank() }.joinToString(" / "),
            status = "pending", createdAt = createdAt,
            source = "session",
            originProjectId = sessionProjectId(sessionId),
            originSessionId = sessionId,
            suggestedStart = due.takeIf { it > 0 } ?: 0L,
            suggestedEnd = 0L,
            payload = a.toString()
        )
        // 2026-11-11 判重入库（用户「待处理审核中一堆重复的」）：重复命中不新插、返回空
        val draftId = Graph.info.addPendingItems(listOf(item)).firstOrNull() ?: -1L
        if (draftId > 0L) {
            ToolExecutor.registerUndo(
                UndoEntry(sessionId, "撤掉草稿「" + title + "」") { Graph.info.deletePending(draftId) }
            )
            ToolExecutor.registerUndoPersisted(sessionId, "删除草稿「" + title + "」", "pending_add", draftId.toString())
        } else {
            val exist = Graph.info.pendingItems().firstOrNull {
                io.github.m0use001.planability.data.PendingDedup.isDuplicate(it, item)
            }
            return ToolExecResult.ok(
                "已有同名草稿" + (exist?.let { "（草稿 #" + it.id + "）" } ?: "") + "：待办「" + title + "」 " +
                    ToolExecImpls.fmtDay(dayKey) + "，未重复添加（在助理中枢待审里确认即可）",
                "已有同名草稿"
            )
        }
        val repeatText = rec?.takeIf { it.repeats }
            ?.let { " [重复:" + it.describeCompact(dayKey.toLocalDate()) + "]" } ?: ""
        return ToolExecResult.ok(
            "已放进助理中枢待审（草稿 #" + draftId + "）：待办「" + title + "」 " +
                ToolExecImpls.fmtDay(dayKey) + (if (due > 0) " " + AppTime.millisToTime(due) else "") + repeatText +
                "。要向用户说明：还没进时间轴，用户在助理中枢确认后才生效。",
            "存草稿「" + title + "」"
        )
    }

    private suspend fun scheduleCreate(a: JSONObject, sessionId: Long): ToolExecResult {
        val title = a.optString("title").trim()
        val start = ToolExecImpls.parseDateTime(a.optString("start"))
            ?: return ToolExecResult.fail("开始时间无法解析：" + a.optString("start"))
        val end = a.optString("end").takeIf { it.isNotBlank() }?.let { ToolExecImpls.parseDateTime(it) } ?: (start + 3600_000L)
        if (title.isEmpty()) return ToolExecResult.fail("标题为空")
        val realEnd = maxOf(end, start + 5 * 60_000L)
        val (rec, recErr) = repeatOf(a)
        if (recErr != null) return ToolExecResult.fail(recErr)
        val (_, tagErr) = tagIdOf(a)
        if (tagErr != null) return ToolExecResult.fail(tagErr)
        // v54 统一审核：不再直接 saveSchedule，改成助理中枢草稿（payload=原始参数，
        // 入库时 PendingImporter 忠实重建并做时间冲突检查）。直接进时间轴的唯一入口是 pending_commit。
        val createdAt = AppTime.nowMillis()
        val item = io.github.m0use001.planability.data.PendingItem(
            title = title, kind = "schedule",
            timeText = ToolExecImpls.fmtDay(start) + " " + AppTime.millisToTime(start) + "-" +
                AppTime.millisToTime(realEnd) +
                (rec?.takeIf { it.repeats }?.let { " [" + it.describeCompact(start.toLocalDate()) + "]" } ?: ""),
            place = a.optString("place"),
            extra = a.optString("note"),
            status = "pending", createdAt = createdAt,
            source = "session",
            originProjectId = sessionProjectId(sessionId),
            originSessionId = sessionId,
            suggestedStart = start,
            suggestedEnd = realEnd,
            payload = a.toString()
        )
        // 2026-11-11 判重入库（用户「待处理审核中一堆重复的」）：重复命中不新插、返回空
        val draftId = Graph.info.addPendingItems(listOf(item)).firstOrNull() ?: -1L
        if (draftId > 0L) {
            ToolExecutor.registerUndo(
                UndoEntry(sessionId, "撤掉草稿「" + title + "」") { Graph.info.deletePending(draftId) }
            )
            ToolExecutor.registerUndoPersisted(sessionId, "删除草稿「" + title + "」", "pending_add", draftId.toString())
        } else {
            val exist = Graph.info.pendingItems().firstOrNull {
                io.github.m0use001.planability.data.PendingDedup.isDuplicate(it, item)
            }
            return ToolExecResult.ok(
                "已有同名草稿" + (exist?.let { "（草稿 #" + it.id + "）" } ?: "") + "：日程「" + title + "」：" +
                    ToolExecImpls.fmtDay(start) + " " + AppTime.millisToTime(start) + "-" + AppTime.millisToTime(realEnd) +
                    "，未重复添加（在助理中枢待审里确认即可）",
                "已有同名草稿"
            )
        }
        val repeatText = rec?.takeIf { it.repeats }
            ?.let { " [" + it.describeCompact(start.toLocalDate()) + "]" } ?: ""
        return ToolExecResult.ok(
            "已放进助理中枢待审（草稿 #" + draftId + "）：日程「" + title + "」：" +
                ToolExecImpls.fmtDay(start) + " " + AppTime.millisToTime(start) + "-" + AppTime.millisToTime(realEnd) + repeatText +
                "。要向用户说明：还没进时间轴，用户在助理中枢确认后才生效。",
            "存草稿「" + title + "」"
        )
    }

    /** 草稿来源项目：会话挂在哪个项目下（查不到算全局 0） */
    private suspend fun sessionProjectId(sessionId: Long): Long =
        if (sessionId <= 0L) 0L
        else runCatching { Graph.chat.session(sessionId)?.projectId ?: 0L }.getOrDefault(0L)

    private suspend fun scheduleUpdate(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val old = Graph.db.scheduleDao().byId(id) ?: return ToolExecResult.fail(missingIdMsg("日程", id))
        var ns = old
        a.optString("title").takeIf { it.isNotBlank() }?.let { ns = ns.copy(title = it) }
        a.optString("place").takeIf { it.isNotBlank() }?.let { ns = ns.copy(place = it) }
        ToolExecImpls.optCoord(a, "lat")?.let { ns = ns.copy(lat = it) }
        ToolExecImpls.optCoord(a, "lng")?.let { ns = ns.copy(lng = it) }
        ToolExecImpls.priorityOfOrNull(a)?.let {
            // 两列一起改：priority 是给 PC/工具看的，importance 是本端真正存的那档
            ns = ns.copy(priority = it, importance = io.github.m0use001.planability.data.PriorityMap.importanceOf(it))
        }
        ToolExecImpls.urgencyOfOrNull(a)?.let { ns = ns.copy(urgency = it) }
        // P1-6（2026-11-10 审计静态实锤）：原来解析失败被 ?.let 静默吞掉、仍回「已修改日程」——
        // 与 todo_edit 对 date 的口径不一致（那边是显式 fail）。改/没改必须说真话。
        a.optString("start").takeIf { it.isNotBlank() }?.let { raw ->
            val m = ToolExecImpls.parseDateTime(raw)
                ?: return ToolExecResult.fail("开始时间看不懂：「" + raw + "」（写 明天/周五/2026-11-12 15:00 这类）")
            ns = ns.copy(start = m)
        }
        a.optString("end").takeIf { it.isNotBlank() }?.let { raw ->
            val m = ToolExecImpls.parseDateTime(raw)
                ?: return ToolExecResult.fail("结束时间看不懂：「" + raw + "」（写 明天 16:00 / 2026-11-12 16:00 这类）")
            ns = ns.copy(end = m)
        }
        // 2026-10-31：重复规则与标签也能改（原先只能建时指定，改就得删了重建）
        val (rec, recErr) = repeatOf(a)
        if (recErr != null) return ToolExecResult.fail(recErr)
        if (rec != null) {
            ns = ns.copy(repeatRule = io.github.m0use001.planability.data.Recurrence.toScheduleRule(rec))
        }
        val (tagId, tagErr) = tagIdOf(a)
        if (tagErr != null) return ToolExecResult.fail(tagErr)
        if (a.has("tag")) ns = ns.copy(tagId = tagId)
        a.optString("labels").takeIf { it.isNotBlank() }?.let { ns = ns.copy(labels = it.trim()) }
        if (ns.end <= ns.start) ns = ns.copy(end = ns.start + 3600_000L)
        Graph.plan.saveSchedule(ns)
        val undoOld = old
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "还原日程「" + old.title + "」") { Graph.plan.saveSchedule(undoOld) }
        )
        // P0-2 同款：日程改名也给「旧 → 新」，模型不能凭对话印象报对象
        return ToolExecResult.ok(
            "已修改日程（编号 " + id + "）：" + old.title + " → " + ns.title +
                (if (ns.repeatRule.isNotBlank()) " [重复:" + io.github.m0use001.planability.data.Recurrence
                    .parseScheduleRule(ns.repeatRule).describeCompact(ns.start.toLocalDate()) + "]" else ""),
            "已改日程「" + ns.title + "」"
        )
    }

    private suspend fun scheduleSkipDay(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val s = Graph.db.scheduleDao().byId(id) ?: return ToolExecResult.fail(missingIdMsg("日程", id))
        // 2026-10-31：例外日从"只能今天"放开到任意一天（UI 一直支持任意日）
        val day = a.optString("date").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: return ToolExecResult.fail("日期看不懂：" + it) }
            ?.toLocalDate() ?: AppTime.today()
        Graph.plan.excludeScheduleForToday(s, day)
        val undoOrig = s
        val dayText = ToolExecImpls.fmtDay(AppTime.dayKey(day))
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "恢复日程「" + s.title + "」" + dayText + " 出现") {
                Graph.db.scheduleDao().upsert(undoOrig)
            }
        )
        return ToolExecResult.ok(
            "已为日程「" + s.title + "」添加 " + dayText + " 例外（原日程保留）",
            dayText + "跳过「" + s.title + "」"
        )
    }

    // ---------- todo_edit ----------

    private suspend fun todoEdit(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val old = Graph.plan.todoById(id) ?: return ToolExecResult.fail(missingIdMsg("待办", id))
        var nt = old
        a.optString("title").takeIf { it.isNotBlank() }?.let { nt = nt.copy(title = it) }
        a.optString("note").takeIf { it.isNotBlank() }?.let { nt = nt.copy(note = it) }
        a.optString("place").takeIf { it.isNotBlank() }?.let { nt = nt.copy(place = it) }
        ToolExecImpls.optCoord(a, "lat")?.let { nt = nt.copy(lat = it) }
        ToolExecImpls.optCoord(a, "lng")?.let { nt = nt.copy(lng = it) }
        if (a.has("priority")) {
            val p = ToolExecImpls.priorityOfOrNull(a)
                ?: return ToolExecResult.fail("priority 要 high/normal/low")
            nt = nt.copy(priority = p, importance = io.github.m0use001.planability.data.PriorityMap.importanceOf(p))
        }
        ToolExecImpls.urgencyOfOrNull(a)?.let { nt = nt.copy(urgency = it) }
        a.optString("date").takeIf { it.isNotBlank() }?.let { raw ->
            val m = ToolExecImpls.parseDateTime(raw) ?: return ToolExecResult.fail("日期看不懂：" + raw)
            nt = nt.copy(date = AppTime.dayKeyOf(m))
        }
        // P2（2026-11-10 审计）：todo_edit 原来没有 due 参数——「改成明天下午三点」只能改日期，
        // 旧时刻静默保留、工具回 ok。补上 due，只有 HH:mm 时按待办归属日对齐（不然会落成"今天 15:00"）。
        // P0（2026-11-12 中枢会话实报）：时段必须**成对写**——原来只 copy(due=…)、endAt 从不跟，
        // 挪晚后时间轴显示「23:30–17:20」负时长（AI 按分钟排期 11 条排坏 3 条，只能弃条重建）。
        // 语义：①改时刻=平移——原本有结束时段（endAt≥due）就保持时长跟着挪；
        // ②due:"0" 清时刻=成对清（due/endAt 同时归零，条目干净退出时间轴，不残留幽灵 end）；
        // ③原本无 end（endAt==0）不凭空造（展示层按 due+30min 默认，见 timeline 渲染）。
        a.optString("due").takeIf { it.isNotBlank() }?.let { raw ->
            if (raw.trim() in setOf("0", "none", "无")) {
                nt = nt.copy(due = 0L, endAt = 0L)
            } else {
                val parsedDue = ToolExecImpls.parseDateTime(raw)
                    ?: return ToolExecResult.fail("截止时刻看不懂：「" + raw + "」")
                val hm = Regex("^(\\d{1,2}):(\\d{2})$").find(raw.trim())
                val newDue = if (hm != null)
                    AppTime.millisOf(nt.date.toLocalDate(), hm.groupValues[1].toInt(), hm.groupValues[2].toInt())
                else parsedDue
                nt = if (old.due > 0 && old.endAt >= old.due) {
                    nt.copy(due = newDue, endAt = newDue + (old.endAt - old.due))
                } else {
                    nt.copy(due = newDue)
                }
            }
        }
        a.optString("labels").takeIf { it.isNotBlank() }?.let { nt = nt.copy(labels = it.trim()) }
        if (a.has("tag")) {
            val (tid, terr) = tagIdOf(a)
            if (terr != null) return ToolExecResult.fail(terr)
            nt = nt.copy(tagId = tid)
        }
        // 重复：先解析（失败早退，别把半套改动写下去）
        val (rec, err) = repeatOf(a)
        if (err != null) return ToolExecResult.fail(err)
        if (rec != null) nt = todoWithRepeat(nt, rec)
            // v49：百分比**只改进度、永不碰勾选**（用户终态拍板，见 TodoProgress）。
            // 此前这里曾把 100 翻译成 done=true——那就是被废除的「第二条完成通道」：
            // 直接置 done 会绕过 doneLog/滚期/监督打卡，时间轴与打卡账本立刻分裂
            // （「打卡后锁死 100%」「撤销打卡失效」的远亲）。标完成只走 todo_complete。
            // percentDone = 100 的语义 = 「做完了但没打卡」（胶囊满+勾，done 仍 false）。
            if (a.has("percent")) {
                val p = a.optInt("percent", -1).coerceIn(-1, 100)
                nt = nt.copy(percentDone = p)
            }
        // 地点绑坐标：没给坐标时按地点名查「分类管理 → 地点」（kind=1）回填
        if (nt.lat == 0.0 && nt.lng == 0.0 && nt.place.isNotBlank()) {
            placeCoords(nt.place)?.let { (lat, lng) -> nt = nt.copy(lat = lat, lng = lng) }
        }
        if (nt == old) return ToolExecResult.fail("没有任何要改的字段（至少传一个）")
        Graph.plan.saveTodo(nt)
        val undoOld = old
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "还原待办「" + old.title + "」") { Graph.plan.saveTodo(undoOld) }
        )
        // P0-2（2026-11-10 审计动态实录）：模型正文声称改了 A、实际改了 B（真标题只在工具行
        // display 里）。payload 是模型逐字看到的那份——必须写「旧标题 → 新标题」，
        // 模型想撒谎就得先无视眼前的目标名，同时用户核对时一眼能看出改错了对象。
        val repeatNote = if (old.repeatType != "none" && rec == null) {
            // 场景②的教训：把「新建」误做成「编辑」时，原待办的重复规则会被静默继承
            " ⚠ 该待办原本带重复规则（" + todoRepeatText(old).trim().removePrefix("[").removeSuffix("]") +
                "），本次未改；若不该再重复请传 repeat:none。"
        } else ""
        return ToolExecResult.ok(
            "已修改待办（编号 " + id + "）：" + old.title + " → " + nt.title + todoRepeatText(nt) + repeatNote,
            "已改待办「" + nt.title + "」"
        )
    }

    // ---------- todo_delete / schedule_delete（软删进回收站） ----------

    private suspend fun todoDelete(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val t = Graph.plan.todoById(id) ?: return ToolExecResult.fail(missingIdMsg("待办", id))
        Graph.plan.deleteTodo(id)
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "从回收站恢复「" + t.title + "」") { Graph.plan.restoreTodo(id) }
        )
        // 持久化撤销：applyUndo 的 "todo_delete" 分支做同一件事（跨进程仍可撤）
        ToolExecutor.registerUndoPersisted(sessionId, "恢复待办「" + t.title + "」", "todo_delete", id.toString())
        return ToolExecResult.ok("已删除待办「" + t.title + "」（进了回收站，可撤销）", "已删待办「" + t.title + "」")
    }

    private suspend fun scheduleDelete(a: JSONObject, sessionId: Long): ToolExecResult {
        val id = a.optLong("id", -1)
        val s = Graph.plan.scheduleById(id) ?: return ToolExecResult.fail(missingIdMsg("日程", id))
        Graph.plan.deleteSchedule(id)
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "从回收站恢复日程「" + s.title + "」") { Graph.plan.restoreSchedule(id) }
        )
        ToolExecutor.registerUndoPersisted(sessionId, "恢复日程「" + s.title + "」", "schedule_delete", id.toString())
        return ToolExecResult.ok("已删除日程「" + s.title + "」（进了回收站，可撤销）", "已删日程「" + s.title + "」")
    }

    // ---------- checkin_query / checkin_add（数据记录打卡） ----------

    private suspend fun checkinQuery(a: JSONObject): ToolExecResult {
        val zone = AppTime.zone
        // v48：百分比流水（每次设置/每个打卡日一条），与打卡记录一起给 AI 看
        suspend fun percentLine(t: Todo): String {
            val logs = runCatching { Graph.plan.percentsOf(t.id) }.getOrDefault(emptyList())
            return if (logs.isEmpty()) ""
            else "\n百分比进度（" + logs.size + " 条）：" +
                io.github.m0use001.planability.data.PercentLogFormat.recent(logs, zone)
        }
        val tid = a.optLong("todo_id", -1)
        if (tid > 0) {
            val t = Graph.plan.todoById(tid) ?: return ToolExecResult.fail(missingIdMsg("待办", tid))
            val rs = CheckinFormat.sortForDisplay(Graph.plan.checkinsOf(tid))
            val pct = percentLine(t)
            if (rs.isEmpty() && pct.isBlank()) return ToolExecResult.ok("「" + t.title + "」还没有打卡记录", "无打卡")
            return ToolExecResult.ok(
                "「" + t.title + "」最近记录：\n" +
                    (if (rs.isEmpty()) "（无打卡数据）" else rs.take(30).joinToString("\n") { CheckinFormat.line(it, zone) } +
                        "\n" + CheckinFormat.summary(t, rs, zone)) + pct,
                "查打卡「" + t.title + "」"
            )
        }
        // 不指名待办：列出所有开着数据记录/百分比进度的待办及其摘要
        val rows = Graph.plan.activeTodos().filter {
            it.dataRecordEnabled || io.github.m0use001.planability.data.TodoProgress.isEnabled(it.percentDone)
        }
        if (rows.isEmpty()) return ToolExecResult.ok("没有开启「数据记录」或百分比进度的待办", "无数据记录待办")
        val lines = rows.map { t ->
            val rs = Graph.plan.checkinsOf(t.id)
            "「" + t.title + "」（" + CheckinKind_label(t.dataRecordKind) + "，编号 " + t.id + "）" +
                (if (rs.isEmpty()) "：暂无打卡数据" else "：" + CheckinFormat.summary(t, rs, zone)) +
                percentLine(t)
        }
        return ToolExecResult.ok(lines.joinToString("\n"), rows.size.toString() + " 条数据记录")
    }

    private fun CheckinKind_label(kind: String): String = when (kind) {
        "time" -> "时间"; "number" -> "数值"; "count" -> "计数"; "text" -> "短文本"; "tags" -> "多选"; else -> "打卡"
    }

    private suspend fun checkinAdd(a: JSONObject, sessionId: Long): ToolExecResult {
        val tid = a.optLong("todo_id", -1)
        val t = Graph.plan.todoById(tid) ?: return ToolExecResult.fail(missingIdMsg("待办", tid))
        if (!t.dataRecordEnabled) {
            return ToolExecResult.fail("「" + t.title + "」没有开启数据记录，打不了卡（在待办编辑器里开启）")
        }
        val dayKey = a.optString("date").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: return ToolExecResult.fail("日期看不懂") }
            ?.let { AppTime.dayKeyOf(it) } ?: AppTime.dayKey(AppTime.today())
        val kind = t.dataRecordKind.ifBlank { "time" }
        val num = if (a.has("value") && !a.isNull("value")) a.optDouble("value").takeUnless { it.isNaN() } else null
        val text = a.optString("text").trim()
        if (kind == "number" || kind == "count") {
            if (num == null) return ToolExecResult.fail("这个待办记录" + CheckinKind_label(kind) + "，要传 value（数值）")
        } else if (kind == "text" && text.isEmpty() && num == null) {
            return ToolExecResult.fail("这个待办记录短文本，要传 text")
        }
        val record = io.github.m0use001.planability.data.CheckinRecord(
            todoId = tid, date = dayKey, at = AppTime.nowMillis(), kind = kind,
            numValue = num ?: 0.0, unit = t.dataRecordUnit, textValue = text,
            tags = a.optString("tags").trim()
        )
        // P2（2026-11-10 审计）：撤销原来直接删整天——addCheckin 是覆盖语义，undo 必须
        // 还原被覆盖的那条而不是删光当天。
        val prevCheckin = runCatching { Graph.plan.checkinOn(tid, dayKey.toLocalDate()) }.getOrNull()
        Graph.plan.addCheckin(record)
        val undoKey = dayKey
        ToolExecutor.registerUndo(
            UndoEntry(sessionId, "撤掉「" + t.title + "」的这笔打卡") {
                Graph.plan.removeCheckin(tid, undoKey)
                prevCheckin?.let { Graph.plan.addCheckin(it) }
            }
        )
        return ToolExecResult.ok(
            "已记一笔：" + CheckinFormat.line(record, AppTime.zone) + "（「" + t.title + "」）",
            "打卡「" + t.title + "」"
        )
    }

    // ---------- screen_time_query ----------

    private suspend fun screenTimeQuery(a: JSONObject): ToolExecResult {
        val date = a.optString("date").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: return ToolExecResult.fail("日期看不懂") }
            ?.toLocalDate() ?: AppTime.today()
        val t = ScreenTimeStats.today(date)
            ?: return ToolExecResult.fail(
                "拿不到屏幕使用数据：系统没有授予「使用情况访问」权限（设置 → 应用 → 特殊应用权限），或当天还没有数据"
            )
        val h = t.totalMinutes / 60
        val m = t.totalMinutes % 60
        val top = t.apps.take(10).joinToString("\n") { it.label + " " + it.minutes + " 分钟" }
        return ToolExecResult.ok(
            (date.monthValue.toString() + "/" + date.dayOfMonth + " 屏幕使用 " + h + " 小时 " + m + " 分") +
                (if (top.isEmpty()) "" else "\n" + top),
            "屏幕 " + h + "h" + m + "m"
        )
    }

    // ---------- plan_category_list ----------

    private suspend fun categoryList(a: JSONObject): ToolExecResult {
        val kindArg = a.optString("kind", "all").lowercase()
        val kinds = when (kindArg) {
            "tag", "标签" -> listOf(0)
            "place", "地点" -> listOf(1)
            "folder", "文件夹" -> listOf(2)
            "all" -> listOf(0, 1, 2)
            else -> return ToolExecResult.fail("kind 要 tag/place/folder/all")
        }
        val names = mapOf(0 to "标签", 1 to "地点", 2 to "文件夹")
        val out = kinds.mapNotNull { k ->
            val list = Graph.plan.categories(k)
            if (list.isEmpty() && k != 0) return@mapNotNull null
            names[k] + "：" + list.joinToString("、") { c ->
                c.name + when (k) {
                    1 -> if (c.lat != 0.0 || c.lng != 0.0) "（已绑坐标）" else ""
                    else -> ""
                }
            }
        }
        if (out.isEmpty()) return ToolExecResult.ok("还没有任何分类（标签/地点/文件夹在计划页「分类管理」里建）", "无分类")
        return ToolExecResult.ok(out.joinToString("\n"), "查分类")
    }

    // ---------- plan_category_create（2026-11-14 用户「AI 说设置不了标签」） ----------
    // 真机会话实录：tag:「课程」5 连拒——标签不存在且 AI 没有建标签的工具，
    // 流程只能断给用户（错误提示让用户去分类管理手动建）。

    private suspend fun categoryCreate(a: JSONObject): ToolExecResult {
        val name = a.optString("name").trim()
        if (name.isEmpty()) return ToolExecResult.fail("name 不能为空")
        if (name.length > 20) return ToolExecResult.fail("分类名太长（≤20 字）")
        val kind = when (a.optString("kind", "tag").lowercase()) {
            "tag", "标签" -> 0
            "place", "地点" -> 1
            "folder", "文件夹" -> 2
            else -> return ToolExecResult.fail("kind 要 tag/place/folder")
        }
        val existing = Graph.plan.categories(kind).firstOrNull { it.name == name }
        if (existing != null) {
            return ToolExecResult.ok(
                "分类「" + name + "」已经存在（id=" + existing.id + "），直接在 create/edit 里用",
                "分类已存在"
            )
        }
        val hue = (if (a.has("hue")) a.optDouble("hue").toFloat() else kotlin.random.Random.nextFloat() * 360f)
            .coerceIn(0f, 360f)
        Graph.plan.saveCategory(io.github.m0use001.planability.data.CategoryEntity(name = name, kind = kind, hue = hue))
        val kindName = mapOf(0 to "标签", 1 to "地点", 2 to "文件夹")[kind]
        val id = Graph.plan.categories(kind).firstOrNull { it.name == name }?.id ?: -1L
        return ToolExecResult.ok(
            "已建" + kindName + "「" + name + "」（id=" + id + "），现在可以在 create/edit 的 tag 参数里用",
            "建" + kindName + " " + name
        )
    }

    // ---------- tag（分类维度）→ tagId（create/edit 共用） ----------

    /**
     * `tag` 参数按名匹配「分类管理 → 标签」（kind=0）。
     * 返回 (tagId, 错误)：没传 → (0, null)；传了找不到 → (0, 错误，附现有标签名)。
     */
    suspend fun tagIdOf(a: JSONObject): Pair<Long, String?> {
        if (!a.has("tag")) return 0L to null
        val name = a.optString("tag").trim()
        if (name.isEmpty() || name == "无" || name == "未分类") return 0L to null
        val hit = Graph.plan.categories(0).firstOrNull { it.name == name }
            ?: return 0L to "没有叫「" + name + "」的标签（先在计划页分类管理里建，或用 plan_category_list 查现有标签）"
        return hit.id to null
    }

    // ---------- 地点 → 坐标（create/edit 共用） ----------

    /** 按显示名查「分类管理 → 地点」里绑了坐标的条目；查不到返回 null（不报错，地名本身照样存） */
    suspend fun placeCoords(place: String): Pair<Double, Double>? =
        Graph.plan.categories(1)
            .firstOrNull { it.name == place.trim() && (it.lat != 0.0 || it.lng != 0.0) }
            ?.let { it.lat to it.lng }
}
