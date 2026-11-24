package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.JsonRepair
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.ToolAudit
import org.json.JSONObject

data class ToolCallReq(val id: String, val name: String, val argsJson: String)

data class ToolExecResult(val ok: Boolean, val payload: String, val display: String) {
    companion object {
        fun fail(msg: String) = ToolExecResult(false, "[FAIL] $msg", "失败：" + msg.take(60))
        fun ok(payload: String, display: String) = ToolExecResult(true, "[OK] " + payload, display)
    }
}

/** 审批桥：confirm/danger 级由会话 UI 实现（挂起等确认卡） */
interface PtlApprovalBridge {
    /** 返回 true=放行；false=拒绝。"总是允许"的持久化由实现方处理 */
    suspend fun requestApproval(sessionId: Long, tool: ToolDef, argsJson: String): Boolean
}

/** 撤销快照条目（会话内存级，闭包精确还原） */
class UndoEntry(val sessionId: Long, val describe: String, val action: suspend () -> Unit)

/**
 * M4 统一工具层执行器：
 * 风险门（auto 直行 / confirm 可"总是允许" / danger 每次确认）→
 * 容错入参解析 → execute 分发（ToolExecImpls）→ 50KB 截断 → tools_audit 留痕。
 */
object ToolExecutor {

    var approvalBridge: PtlApprovalBridge? = null

    private const val MAX_PAYLOAD = 50_000

    // ---------- 撤销栈（内存 + 持久化兜底，跨进程存活） ----------

    private val undoStack = mutableListOf<UndoEntry>()

    fun registerUndo(entry: UndoEntry) {
        undoStack.add(entry)
        if (undoStack.size > 64) undoStack.removeAt(0)
    }

    /** 写类工具同时登记持久化撤销指令（kind/payload 见 applyUndo），进程重启后仍可撤 */
    suspend fun registerUndoPersisted(sessionId: Long, describe: String, kind: String, payload: String) {
        val list = Graph.settings.ptlUndo().toMutableList()
        list.add(
            org.json.JSONObject()
                .put("sessionId", sessionId).put("describe", describe)
                .put("kind", kind).put("payload", payload).toString()
        )
        while (list.size > 32) list.removeAt(0)
        Graph.settings.setPtlUndo(list)
    }

    fun peekUndo(sessionId: Long): String? =
        undoStack.lastOrNull { e -> e.sessionId == sessionId }?.describe

    suspend fun undoLast(sessionId: Long): String? {
        // 优先内存栈（本轮会话内精确快照）
        val mem = undoStack.lastOrNull { e -> e.sessionId == sessionId }
        if (mem != null) {
            undoStack.remove(mem)
            mem.action()
            return mem.describe
        }
        // 持久化兜底（跨进程）
        val list = Graph.settings.ptlUndo().toMutableList()
        for (i in list.indices.reversed()) {
            val obj = runCatching { org.json.JSONObject(list[i]) }.getOrNull() ?: continue
            if (obj.optLong("sessionId") != sessionId) continue
            list.removeAt(i)
            Graph.settings.setPtlUndo(list)
            val ok = applyUndo(obj.optLong("sessionId"), obj.optString("kind"), obj.optString("payload"))
            return if (ok) obj.optString("describe") + "（持久化撤销）" else null
        }
        return null
    }

    private suspend fun applyUndo(sessionId: Long, kind: String, payload: String): Boolean = try {
        when (kind) {
            "schedule_create" -> { Graph.plan.deleteSchedule(payload.toLong()); true }
            "todo_create" -> { Graph.plan.deleteTodo(payload.toLong()); true }
            "todo_delete" -> { Graph.plan.restoreTodo(payload.toLong()); true }
            "schedule_delete" -> { Graph.plan.restoreSchedule(payload.toLong()); true }
            "todo_complete" -> {
                Graph.plan.todoById(payload.toLong())?.let { Graph.plan.completeTodo(it, AppTime.today()) }
                true
            }
            "note_create" -> { Graph.notes.trashNote(payload.toLong()); true }
            // P1-7（2026-11-10 审计）：下面三类 kind 早就 registerUndoPersisted 登记了，
            // 但 applyUndo 一直没有分支——跨进程撤销恒失败（回「没有可撤销的写操作」）。
            "note_delete" -> { Graph.notes.restoreNote(payload.toLong()); true }
            "pending_add" -> { Graph.info.deletePending(payload.toLong()); true }
            "file_library_write" -> {
                // payload 是裸文件名（登记处只带了 name），按最新同名文件软删——宁可撤错
                // 一张纸也要兑现「可撤销」的承诺；有 id 的话优先用 id。
                val asId = payload.toLongOrNull()
                val f = if (asId != null) io.github.m0use001.planability.data.ProjectFileRepo.byId(asId)
                else io.github.m0use001.planability.data.ProjectFileRepo.all()
                    .filter { it.name == payload && !it.trashed }
                    .maxByOrNull { it.createdAt }
                if (f != null) {
                    io.github.m0use001.planability.data.ProjectFileRepo.softDelete(f.id)
                    true
                } else false
            }
            "memory_add" -> {
                // payload 现在是 id（同名更新会走 memory_update）；兼容旧档按名字找
                val byId = payload.toLongOrNull()?.let { Graph.memories.byId(it) }
                val m = byId ?: Graph.memories.all().firstOrNull { it.name == payload }
                m?.let { Graph.memories.softDelete(it.id) }
                m != null
            }
            "memory_update" -> {
                // P2（2026-11-10）：同名记忆被原地更新时，持久化撤销要还原旧正文
                val o = runCatching { org.json.JSONObject(payload) }.getOrNull()
                val id = o?.optLong("id", -1) ?: -1
                if (o != null && id > 0) {
                    Graph.memories.upsert(
                        io.github.m0use001.planability.data.MemoryEntity(
                            id = id, name = o.optString("name"), type = o.optString("type"),
                            description = o.optString("description"), body = o.optString("body"),
                            importance = o.optInt("importance", 2), projectId = o.optLong("projectId", 0)
                        )
                    )
                    true
                } else false
            }
            "memory_forget" -> {
                Graph.memories.byId(payload.toLong())?.let { Graph.memories.upsert(it) }
                true
            }
            "file_export" -> {
                // 2026-10-20 盘查修：导出文件不在 filesDir/exports，而是在 ExportStore.dirFor()
                // （外部 files 目录 / 项目 / 会话子目录）。旧实现删的是 filesDir 下的同名路径，
                // 永远删不到，却无条件 return true —— 界面显示"已撤销"，文件还在。
                val t = payload.trim().removePrefix("/").replace("..", "")
                val f = when {
                    t.startsWith("exports/") -> {
                        val dir = io.github.m0use001.planability.ai.office.ExportStore
                            .dirFor(Graph.appContext, sessionId)
                        java.io.File(dir, t.removePrefix("exports/"))
                    }
                    t.startsWith("attachments/") -> java.io.File(Graph.appContext.filesDir, t)
                    else -> null
                }
                f != null && f.exists() && f.delete()
            }
            else -> false
        }
    } catch (e: Exception) {
        false
    }

    // ---------- "总是允许"（由审批桥在用户选择"总是"时持久化） ----------

    /**
     * 2026-10-29：改成**按会话**存。原先是一条全局 KV（`ptl.always`），
     * 在任一对话里点过"总是允许"，之后所有会话（包括切到「计划模式」的会话）
     * 都不再弹确认 —— 计划模式因此可以被历史放行完全绕过。
     */
    suspend fun alwaysAllowed(sessionId: Long): Set<String> = Graph.settings.ptlAlways(sessionId)

    // ---------- 审计 ----------

    /**
     * 确认卡对象现状（P0-1）：改/删类工具弹卡前，把被改对象此刻的样子查出来，
     * 以 `_当前对象` 键附进 argsJson —— 用户能一眼看出「模型要改的不是我说的那条」。
     * 查不到（id 不存在）返回原 args，由执行层的存在性校验显式失败。
     */
    private suspend fun withCurrentObjectNote(name: String, args: org.json.JSONObject): org.json.JSONObject {
        val id = args.optLong("id", -1)
        if (id <= 0) return args
        val note = when (name) {
            "todo_edit", "todo_delete", "todo_complete", "checkin_add" -> Graph.plan.todoById(id)?.let { t ->
                "待办「" + t.title + "」 " + AppTime.millisToFull(t.date) +
                    (if (t.repeatType != "none") "，重复:" + t.repeatType + "/" + t.repeatDays +
                        (if (t.permanent) "（永久）" else "") else "") +
                    (if (t.done) "，已完成" else "")
            }
            "schedule_update", "schedule_delete", "schedule_delete_today" -> Graph.plan.scheduleById(id)?.let { s ->
                "日程「" + s.title + "」 " + AppTime.millisToFull(s.start) +
                    (if (s.repeatRule.isNotBlank()) "，重复:" + s.repeatRule else "")
            }
            else -> null
        } ?: return args
        return args.put("_当前对象", note)
    }

    /**
     * 缺省编造标注（P1-4，2026-11-10）：v53 把「不传 priority → importance=-1」修好了，
     * 但模型会**显式传** priority:"normal"/"high"（用户原话根本没提），工具分不清
     * 「用户说的」与「模型补的」。这里拿会话里最后一条 user 消息比对：没提过重要/紧急，
     * 就在确认卡上标 `_提示`，让用户自己决定要不要这个等级。
     */
    private suspend fun withFabricationNote(
        name: String, sessionId: Long, args: org.json.JSONObject
    ): org.json.JSONObject {
        if (name !in setOf("todo_create", "todo_edit", "schedule_create", "schedule_update")) return args
        val flags = listOfNotNull(
            args.optString("priority").takeIf { it.isNotBlank() }?.let { "重要程度=" + it },
            args.optString("urgency").takeIf { it.isNotBlank() }?.let { "紧急程度=" + it },
        )
        if (flags.isEmpty()) return args
        val lastUser = runCatching {
            Graph.chat.messagesOf(sessionId).lastOrNull { it.role == "user" }?.content ?: ""
        }.getOrDefault("")
        if (lastUser.isBlank()) return args
        val mentioned = FABRICATION_WORDS.any { lastUser.contains(it, ignoreCase = true) }
        if (mentioned) return args
        return args.put(
            "_提示",
            "用户原话没有提到" + flags.joinToString("、") + "——这可能是模型自己补的缺省值，" +
                "接受前确认要不要这个等级（不要就拒绝，让模型按未设置重来）"
        )
    }

    /** 用户原话里出现这些词才算「提过优先级」，否则确认卡标「用户原话没有提到」 */
    private val FABRICATION_WORDS = listOf(
        "重要", "紧急", "优先级", "四象限", "重要程度", "紧急程度",
        "important", "urgent", "priority", "high", "low", "normal"
    )

    private suspend fun logUse(sessionId: Long, req: ToolCallReq, risk: String, outcome: String, ms: Long, detail: String) {
        runCatching {
            Graph.db.toolAuditDao().insert(
                ToolAudit(
                    // v44：参数里的用户内容/凭据（browser_fill 的 text、fs_write_text 的 content）
                    // 隐去，只留长度；路径与命令原样留，危险操作仍可还原
                    sessionId = sessionId, tool = req.name,
                    args = io.github.m0use001.planability.core.AuditRedactor.redact(req.argsJson),
                    outcome = outcome, ms = ms, risk = risk, detail = detail.take(200), at = AppTime.nowMillis()
                )
            )
        }
    }

    // ---------- 主入口 ----------

    suspend fun run(
        sessionId: Long,
        call: ToolCallReq,
        /** v8 会话级权限：allow=跳过 confirm 确认卡（DANGER 仍必确认）；deny=直接拒绝 */
        sessionAllow: Set<String> = emptySet(),
        sessionDeny: Set<String> = emptySet(),
        /**
         * 档位（[PermPolicy.TIER_KEYS]）。`ro` = 只读白名单：非 [Risk.AUTO] 一律拒；
         * 空串 = 只按 allow/deny 走（兼容不带档位的历史调用）。
         */
        permMode: String = ""
    ): ToolExecResult {
        val started = System.currentTimeMillis()
        val readOnly = permMode == "ro"
        // v11：MCP 动态工具路由（mcp<serverId>__<tool>），统一 CONFIRM 审批
        val mcpRoute = if (call.name.startsWith("mcp_")) null else io.github.m0use001.planability.ai.mcp.McpBridge.parse(call.name)
        if (mcpRoute != null) {
            val args0 = parseArgs(call.argsJson)
            if (call.name in sessionDeny) {
                logUse(sessionId, call, "confirm", "denied", 0, "本会话已禁用该工具")
                return ToolExecResult(false, "[DENIED] MCP 工具 " + call.name + " 在当前会话被用户禁用。", "已禁止 " + call.name)
            }
            // 计划模式（只读）：MCP 是外部工具、能力不可枚举，一律当写操作拒。
            // 从前这里没有任何闸，而 deny 名单装不下运行期才知道的名字 —— 只读档能被 MCP 绕过。
            if (readOnly) {
                logUse(sessionId, call, "confirm", "denied", 0, "计划模式：拒绝外部 MCP 工具")
                return ToolExecResult(
                    false,
                    "[DENIED] 当前是「计划模式」（只读）：外部 MCP 工具 " + call.name +
                        " 无法判定只读，本会话一律拒绝。要执行请让用户切换权限档位。",
                    "只读档拒绝 " + call.name
                )
            }
            val needAsk = call.name !in alwaysAllowed(sessionId) && call.name !in sessionAllow
            if (needAsk) {
                val mcpDef = ToolDef(call.name, "MCP 外部工具", "", risk = Risk.CONFIRM)
                val granted = approvalBridge?.requestApproval(sessionId, mcpDef, args0.toString()) ?: false
                if (!granted) {
                    logUse(sessionId, call, "confirm", "denied", 0, "用户拒绝")
                    return ToolExecResult(false, "[DENIED] 你拒绝了「" + mcpDef.cnDescription + "」（" + call.name + "）的调用，本次操作没有执行。", "已拒绝 " + call.name)
                }
            }
            val result = try {
                ToolExecResult.ok(
                    io.github.m0use001.planability.ai.mcp.McpBridge.call(mcpRoute.first, mcpRoute.second, call.argsJson),
                    "MCP " + mcpRoute.second
                )
            } catch (e: Exception) {
                ToolExecResult.fail("MCP 调用失败：" + (e.message ?: "未知错误"))
            }
            logUse(sessionId, call, "confirm", if (result.ok) "ok" else "fail", System.currentTimeMillis() - started, result.display)
            return result
        }
        val def = ToolRegistry.byName(call.name)
        if (def == null) {
            logUse(sessionId, call, "auto", "fail", 0, "未知工具")
            return ToolExecResult.fail("未知工具 " + call.name + "，可用工具见系统提示")
        }
        val args = parseArgs(call.argsJson)
        // 会话级禁止优先于一切
        if (call.name in sessionDeny) {
            logUse(sessionId, call, def.risk.name.lowercase(), "denied", 0, "本会话已禁用该工具")
            return ToolExecResult(false, "[DENIED] 「" + def.cnDescription + "」（" + def.name + "）在当前会话被用户禁用，请改用其他方式或询问用户。", "已禁止 " + def.name)
        }
        // 2026-10-29：计划模式（只读）= **白名单**，只放行 Risk.AUTO（纯查询/读取）。
        // 从前这里是 deny 黑名单（WRITE_TOOLS），漏登记的工具（work_plan_start / automation_pin /
        // automation_run）能在"不改动任何数据"的档位里写文件、改库、跑自动化。
        if (readOnly && PermPolicy.readOnlyDenied(def.risk)) {
            logUse(sessionId, call, def.risk.name.lowercase(), "denied", 0, "计划模式：只读档拒绝写操作")
            return ToolExecResult(
                false,
                "[DENIED] 当前是「计划模式」（只读）：「" + def.cnDescription + "」（" + def.name +
                    "）属于写操作，本会话已拒绝。要执行请让用户先把权限档位切成「变更前确认」或更高。",
                "只读档拒绝 " + def.name
            )
        }
        // 风险门：confirm 可"总是允许"，danger 每次确认；"总是允许"的持久化在审批桥实现内。
        // 会话 allow 可跳过 confirm；DANGER 永远不跳过（安全底线）
        if (def.risk != Risk.AUTO) {
            val needAsk = def.risk == Risk.DANGER ||
                (call.name !in alwaysAllowed(sessionId) && call.name !in sessionAllow)
            if (needAsk) {
                // P0-1（2026-11-10 审计两次动态复现）：模型把「草稿 #N」编号当 todos 表 id 调
                // todo_edit/schedule_edit，把无关待办/日程整条改掉、全程 ok——用户在确认卡上
                // 只看得到模型传的参数，看不出**被改的是哪条现状**。这里把目标对象的当前
                // 状态（标题/日期/重复规则）作为 `_当前对象` 键注入给确认卡展示；execute 仍用
                // 原始 args，注入的键不会进执行路径。
                val argsForApproval = withFabricationNote(
                    call.name, sessionId, withCurrentObjectNote(call.name, args)
                )
                val granted = approvalBridge?.requestApproval(sessionId, def, argsForApproval.toString()) ?: false
                if (!granted) {
                    logUse(sessionId, call, def.risk.name.lowercase(), "denied", 0, "用户拒绝")
                    return ToolExecResult(false, "[DENIED] 你拒绝了「" + def.cnDescription + "」（" + def.name + "）的调用，本次操作没有执行。", "已拒绝 " + def.name)
                }
            }
        }
        val result = try {
            ToolExecImpls.execute(sessionId, call.name, args)
        } catch (e: Exception) {
            ToolExecResult.fail("执行失败：" + (e.message ?: "未知错误"))
        }
        logUse(sessionId, call, def.risk.name.lowercase(), if (result.ok) "ok" else "fail", System.currentTimeMillis() - started, result.display)
        if (result.payload.length > MAX_PAYLOAD) {
            return ToolExecResult(result.ok, result.payload.take(MAX_PAYLOAD) + "\n…（结果过长已截断）", result.display)
        }
        return result
    }

    /** 容错入参解析：剥围栏、截取 JSON 对象边界 */
    private fun parseArgs(raw: String): JSONObject {
        val t = raw.trim().replace("```json", "").replace("```", "").trim()
        if (t.isEmpty()) return JSONObject()
        // 2026-11-05 根治：坏 JSON 曾在这里静默落空对象 → 工具拿到空参数执行
        // （报「缺少必填参数」或更糟：按缺省值真改了用户数据）。改走 JsonRepair。
        return JsonRepair.parseObject(t) ?: JSONObject()
    }
}
