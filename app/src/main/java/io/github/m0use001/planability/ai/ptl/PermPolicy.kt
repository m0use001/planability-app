package io.github.m0use001.planability.ai.ptl

import org.json.JSONArray
import org.json.JSONObject

/**
 * 2026-10-29：**会话权限档位的唯一定义**（原先散在 `ui/info/ChatPermMenu.kt`）。
 *
 * ## 为什么搬到 ai/ptl 并重写
 *
 * 审查（2026-10-29）发现两处名实严重不符：
 * ①「计划模式」只按 `WRITE_TOOLS` 黑名单拦，漏了 `automation_pin` /
 *    `automation_run` 三个会写数据的工具 —— 名字写着"不改动任何数据"，实际能建文件、改库、跑自动化；
 * ②「自动执行」的描述只说"自动改日程/待办/笔记"，实际静默放行 38 个工具：
 *    整机文件读写（`fs_write_text/move/mkdir`）、改系统配置（`mcp_connect`/`plugin_toggle`/`capability_toggle`/`model_switch`）、
 *    建设备级自动化（`automation_create`，等于把控制权写进定时任务）。
 *
 * 现在把档位策略收在这里，执行器（[ToolExecutor]）与界面（ChatPermMenu）共用同一份：
 * - **计划模式 = 白名单**：只放行 `Risk.AUTO`（纯查询/读取）的工具，任何非 AUTO 一律拒绝。
 *   以后新增写工具不必记得登记黑名单，忘登记也不会漏拦。
 * - **自动执行 = 业务数据放行**：只自动放行 [AUTO_TOOLS]（日程/待办/笔记/记忆/导出文件），
 *   文件面、设备操控、系统配置仍然逐个确认。
 * - **完全访问**：除 DANGER（删除类）外全免确认，描述按实际写。
 * - [WRITE_TOOLS] 仍是"会改数据"的登记表，由单测保证 `risk != AUTO` 的工具一个不漏。
 */
object PermPolicy {

    data class Tier(val key: String, val label: String, val desc: String)

    val TIERS = listOf(
        Tier(
            "ro", "计划模式",
            "只读：查询/读取可以跑；任何写入、设备操控、改系统设置一律拒绝"
        ),
        Tier(
            "confirm", "变更前确认",
            "每次改数据前都先问我（默认）"
        ),
        Tier(
            "auto", "自动执行",
            "自动改日程/待办/笔记/记忆、导出文件；改手机文件、操控其它应用、改系统设置仍要确认"
        ),
        Tier(
            "full", "完全访问",
            "除删除类操作（日程/文件/记忆/自动化/智能体）外，其余写入都免确认"
        )
    )

    val TIER_KEYS: Set<String> = TIERS.map { it.key }.toSet()

    /**
     * 「会改数据」的工具登记表。
     *
     * 单测 [io.github.m0use001.planability.ai.ptl.PermCoverageTest] 断言：
     * `ToolRegistry.ALL` 里 `risk != AUTO` 的工具必须都在这里 —— 漏登记会让计划模式漏拦。
     */
    val WRITE_TOOLS: Set<String> = setOf(
        "schedule_create", "schedule_update", "schedule_delete_today", "schedule_delete",
        "todo_create", "todo_complete", "todo_edit", "todo_delete",
        "checkin_add",
        "focus_add", "pending_add", "trash_restore",
        // v53 助理中枢：commit 入库进时间轴（写）；suggest 只改草稿建议时段、ignore 改草稿状态，同为写
        "pending_commit", "pending_ignore", "pending_suggest",
        // 2026-11-12 某次会话：草稿修改（标题/时间/地点/类型 + payload 同步）——改的是入库参数
        "pending_update",
        // v55 中枢批量清理：一次改多条草稿状态，按写类最严算
        "pending_purge",
        "notebook_create", "note_edit", "note_delete",
        "note_create", "note_append",
        // v49 知识库：link/unlink 改正文（backlinks/outlinks 也走这个工具名，权限按最严的写类算）
        "note_link",
        "file_export", "file_export_document", "file_build_deck", "file_edit_deck",
        "file_edit_document", "file_edit_sheet", "calendar_export_ics",
        "memory_add", "memory_forget", "memory_invalidate", "undo_last_write",
        "automation_create", "automation_edit", "automation_delete",
        "automation_run", "automation_pin",
        "plugin_toggle", "assistant_create", "assistant_edit", "assistant_delete",
        "mcp_connect", "mcp_disconnect",
        "file_library_write", "file_library_delete", "file_library_mkdir", "file_library_rename",
        // 真实文件面写操作
        "fs_write_text", "fs_mkdir", "fs_move", "fs_delete",
        // 浏览器动作（extract/back 是只读，不入此表）
        "browser_open", "browser_click", "browser_fill",
        "model_switch", "capability_toggle"
    )

    /**
     * 「自动执行」档自动放行的集合：**只限 app 自己的业务数据与导出物**。
     *
     * 刻意不含：`fs_*`（用户真实文件）、`automation_*` / `mcp_*` /
     * `plugin_toggle` / `capability_toggle` / `model_switch`（系统配置）。
     * 这些在自动档仍逐个弹确认 —— 这就是"自动执行"与"完全访问"的本质差别。
     */
    val AUTO_TOOLS: Set<String> = setOf(
        "schedule_create", "schedule_update",
        "todo_create", "todo_complete", "todo_edit",
        "checkin_add",
        "focus_add", "pending_add", "notebook_create", "note_edit",
        "note_create", "note_append",
        "memory_add",
        "file_export", "file_export_document", "file_build_deck", "file_edit_deck",
        "file_edit_document", "file_edit_sheet", "calendar_export_ics",
        "file_library_write",
        "undo_last_write"
    )

    /**
     * 某个档位下"怎么放行"：
     * - ro：不看 allow，执行器按 [readOnlyDenied] 直接拒所有非 AUTO；
     * - confirm：allow 为空（全部走确认卡）；
     * - auto：只看 [AUTO_TOOLS]；
     * - full：看 [WRITE_TOOLS]。
     */
    fun allowedSet(mode: String): Set<String> = when (mode) {
        "auto" -> AUTO_TOOLS
        "full" -> WRITE_TOOLS
        else -> emptySet()
    }

    /** 计划模式（只读）要不要拒这个风险级别：AUTO = 纯查询，放行；其余一律拒 */
    fun readOnlyDenied(risk: Risk): Boolean = risk != Risk.AUTO

    /**
     * 档位 → 会话级 permJson（带 mode 字段；空串=不写，保持默认）。
     *
     * 「计划模式」刻意**不写 deny 名单**：拦截完全交给执行器的白名单闸
     * （[readOnlyDenied]）。两者都写的话 deny 先命中，用户看到的理由是"本会话已禁用该工具"，
     * 而真实原因是"当前是只读档"—— 名字和理由又对不上，且 deny 名单本身会随工具增加而过期。
     */
    fun permJsonFor(key: String): String = when (key) {
        "ro" -> json("ro", emptyList(), emptyList())
        "confirm" -> json("confirm", emptyList(), emptyList())
        "auto" -> json("auto", AUTO_TOOLS, emptyList())
        "full" -> json("full", WRITE_TOOLS, emptyList())
        else -> ""
    }

    /**
     * 会话 permJson → 档位。
     *
     * 新数据认 `mode` 字段；旧会话（没有 mode）按老口径粗判，判出来的档位仍然决定
     * 实际放行集合（见 [allowedSet]）——即老「自动」会话会被收窄成新的自动档语义，
     * 老「完全访问」不受影响。这样历史数据不会把旧的过宽语义继续带下去。
     */
    fun modeForPermJson(permJson: String): String {
        if (permJson.isBlank()) return "confirm"
        val obj = runCatching { JSONObject(permJson) }.getOrNull() ?: return "confirm"
        val explicit = obj.optString("mode")
        if (explicit in TIER_KEYS) return explicit
        val allow = strings(obj.optJSONArray("allow"))
        val deny = strings(obj.optJSONArray("deny"))
        return when {
            deny.containsAll(WRITE_TOOLS) && deny.isNotEmpty() -> "ro"
            allow.containsAll(WRITE_TOOLS) && allow.isNotEmpty() -> "full"
            allow.isNotEmpty() -> "auto"
            else -> "confirm"
        }
    }

    private fun strings(a: JSONArray?): Set<String> =
        if (a == null) emptySet() else (0 until a.length()).map { a.optString(it) }.toSet()

    private fun json(mode: String, allow: Collection<String>, deny: Collection<String>): String =
        JSONObject()
            .put("mode", mode)
            .put("allow", JSONArray(allow.toList()))
            .put("deny", JSONArray(deny.toList()))
            .toString()
}
