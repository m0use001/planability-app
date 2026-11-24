package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.PendingItem
import io.github.m0use001.planability.data.PriorityMap
import io.github.m0use001.planability.data.Recurrence
import org.json.JSONObject

/**
 * v54：待处理草稿 → 正式日程/待办的**唯一入库通道**。
 *
 * 两个调用方共用同一逻辑，保证行为一致：
 * - 助理对话里的 `pending_commit` 工具（带修改参数）
 * - 中枢卡片右侧 ✓（不带参数，按 payload 忠实导入）
 *
 * v54 统一审核后，普通会话的 todo_create/schedule_create 也产出草稿（payload=原始工具参数），
 * 入库时按 payload 忠实重建——重复规则/优先级/紧急度/标签全保留，而不是只靠 timeText 猜。
 */
internal object PendingImporter {

    /**
     * 把草稿入库为日程/待办，并标记 imported。
     * [overrides] 的键优先于 payload（AI 在 commit 时修正时间等就放这里）。
     * 返回 (新条目 id, 给用户看的一句话, 冲突警告或 null)；草稿状态已被置为 imported。
     */
    suspend fun commit(
        item: PendingItem,
        overrides: JSONObject = JSONObject(),
        sessionId: Long = 0L,
    ): Triple<Long, String, String?> {
        // 参数合成：payload 垫底、overrides 覆盖——之后全部按合成后的 args 解析
        val a = JSONObject()
        if (item.payload.isNotBlank()) {
            runCatching {
                val p = JSONObject(item.payload)
                p.keys().forEach { k -> a.put(k, p.opt(k)) }
            }
        }
        overrides.keys().forEach { k -> a.put(k, overrides.opt(k)) }
        // 草稿字段兜底（手动创建的草稿没有 payload）
        if (a.optString("title").isBlank()) a.put("title", item.title)
        if (a.optString("place").isBlank() && item.place.isNotBlank()) a.put("place", item.place)
        if (a.optString("note").isBlank() && item.extra.isNotBlank()) a.put("note", item.extra)

        val title = a.optString("title").trim()
        val place = a.optString("place")
        val note = a.optString("note")
        val (tagId, tagErr) = PlanTools.tagIdOf(a)
        if (tagErr != null) throw IllegalArgumentException(tagErr)
        val p = ToolExecImpls.priorityOfOrNull(a)
        var lat = 0.0; var lng = 0.0
        if (place.isNotBlank()) PlanTools.placeCoords(place)?.let { (la, ln) -> lat = la; lng = ln }

        if (item.kind == "schedule") {
            val start = a.optString("start").takeIf { it.isNotBlank() }
                ?.let { ToolExecImpls.parseDateTime(it) ?: throw IllegalArgumentException("开始时间看不懂：" + a.optString("start")) }
                ?: item.suggestedStart.takeIf { it > 0 }
                ?: throw IllegalArgumentException("日程入库需要开始时间：传 start（如「明天 15:00」），或先用 pending_suggest 建议时段")
            val end = a.optString("end").takeIf { it.isNotBlank() }
                ?.let { ToolExecImpls.parseDateTime(it) ?: throw IllegalArgumentException("结束时间看不懂：" + a.optString("end")) }
                ?: (if (item.suggestedEnd > start) item.suggestedEnd else start + 3600_000L)
            val realEnd = maxOf(end, start + 5 * 60_000L)
            val (rec, recErr) = PlanTools.repeatOf(a)
            if (recErr != null) throw IllegalArgumentException(recErr)
            val sid = Graph.plan.saveSchedule(
                io.github.m0use001.planability.data.Schedule(
                    title = title, start = start, end = realEnd, place = place, note = note,
                    priority = p ?: 1,
                    importance = p?.let { PriorityMap.importanceOf(it) } ?: -1,
                    urgency = ToolExecImpls.urgencyOfOrNull(a) ?: -1,
                    lat = lat, lng = lng,
                    repeatRule = rec?.let { Recurrence.toScheduleRule(it) } ?: "",
                    tagId = tagId
                )
            )
            registerUndo(sessionId, "撤销入库「$title」（删日程并还原草稿）") {
                Graph.plan.deleteSchedule(sid)
                runCatching { Graph.info.updatePending(item.copy(status = "pending")) }
            }
            Graph.info.markPending(item.id, "imported")
            val clash = clashWarning(start, realEnd, excludeTitle = title)
            return Triple(
                sid,
                "已入库日程「$title」：" + ToolExecImpls.fmtDay(start) + " " +
                    AppTime.millisToTime(start) + "-" + AppTime.millisToTime(realEnd) + "，进入每日时间轴",
                clash
            )
        }

        // kind = todo
        val parsedDay = a.optString("date").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: throw IllegalArgumentException("日期看不懂：" + a.optString("date")) }
            ?: item.suggestedStart.takeIf { it > 0 }
            ?: (ToolExecImpls.parseDateTime("今天") ?: 0L)
        val dayKey = AppTime.dayKeyOf(parsedDay)
        val due = a.optString("due").takeIf { it.isNotBlank() }
            ?.let { ToolExecImpls.parseDateTime(it) ?: throw IllegalArgumentException("截止时刻看不懂：" + a.optString("due")) }
            ?: item.suggestedStart.takeIf { it > 0 }
            ?: 0L
        val (rec, recErr) = PlanTools.repeatOf(a)
        if (recErr != null) throw IllegalArgumentException(recErr)
        var todo = io.github.m0use001.planability.data.Todo(
            title = title, date = dayKey,
            priority = p ?: 1,
            importance = p?.let { PriorityMap.importanceOf(it) } ?: -1,
            urgency = ToolExecImpls.urgencyOfOrNull(a) ?: -1,
            note = note, place = place, lat = lat, lng = lng,
            tagId = tagId, due = due
        )
        if (rec != null) todo = PlanTools.todoWithRepeat(todo, rec)
        val tid = Graph.plan.saveTodo(todo)
        registerUndo(sessionId, "撤销入库「$title」（删待办并还原草稿）") {
            Graph.plan.deleteTodo(tid)
            runCatching { Graph.info.updatePending(item.copy(status = "pending")) }
        }
        Graph.info.markPending(item.id, "imported")
        val dueText = if (due > 0) " " + AppTime.millisToTime(due) else ""
        return Triple(tid, "已入库待办「$title」 " + ToolExecImpls.fmtDay(dayKey) + dueText + "，进入每日时间轴", null)
    }

    private fun registerUndo(sessionId: Long, label: String, action: suspend () -> Unit) {
        if (sessionId > 0L) ToolExecutor.registerUndo(UndoEntry(sessionId, label, action))
    }

    /**
     * 入库前查当天既有安排，与新时段重叠就给一句警告（不阻断——commit 本身有用户确认卡）。
     * 排错自己的名字（AI 常把同一草稿先 suggest 又 commit，别把自己算进冲突）。
     */
    private suspend fun clashWarning(start: Long, end: Long, excludeTitle: String): String? {
        val day = start.toLocalDate()
        val clashes = runCatching {
            Graph.plan.scheduleOccurrences(day, day)
                .filter { it.schedule.title != excludeTitle && it.start < end && start < it.end }
        }.getOrDefault(emptyList())
        if (clashes.isEmpty()) return null
        return "⚠ 与已有日程时间重叠：" + clashes.take(3).joinToString("、") {
            "「" + it.schedule.title + "」" + AppTime.millisToTime(it.start) + "-" + AppTime.millisToTime(it.end)
        } + "（已按你指定的时间入库，需要调整就说一声）"
    }
}
