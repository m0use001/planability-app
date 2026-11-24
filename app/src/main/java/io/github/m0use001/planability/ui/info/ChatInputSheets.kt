package io.github.m0use001.planability.ui.info

import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import io.github.m0use001.planability.ui.common.motionEnter
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.ai.LinkedRef
import io.github.m0use001.planability.ai.ptl.ToolDef
import io.github.m0use001.planability.ai.ptl.ToolRegistry
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.AppIcons
import kotlinx.coroutines.launch
import io.github.m0use001.planability.ui.common.filterChipColors

/** v8 待发送附件（复制进 attachments/ 后的引用） */
data class PendingAttachment(val relPath: String, val name: String, val tool: String)

/** 按扩展名选读取工具：图片 OCR / office 解析 / 纯文本 */
fun toolForFile(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return when (ext) {
        "jpg", "jpeg", "png", "webp", "gif", "bmp" -> "file_read_image"
        "docx", "xlsx", "pptx" -> "file_read_document"
        else -> "file_read_text"
    }
}

/** v8 @勾连选择器：记忆/技能/日程/待办/笔记/智能体/插件/工具域 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LinkPickSheet(
    onDismiss: () -> Unit,
    onPick: (LinkedRef, String) -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var memories by remember { mutableStateOf<List<io.github.m0use001.planability.data.MemoryEntity>>(emptyList()) }
    var schedules by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var todos by remember { mutableStateOf<List<Pair<Long, String>>>(emptyList()) }
    var notes by remember { mutableStateOf<List<io.github.m0use001.planability.data.NoteEntity>>(emptyList()) }
    var assistants by remember { mutableStateOf<List<io.github.m0use001.planability.data.Assistant>>(emptyList()) }
    LaunchedEffect(Unit) {
        scope.launch {
            runCatching {
                val ms = Graph.memories.all()
                memories = ms
                val today = AppTime.today()
                schedules = Graph.plan.scheduleOccurrences(today, today.plusDays(7)).take(12).map {
                    it.schedule.id to (it.schedule.title + " " + AppTime.millisToTime(it.start))
                }
                todos = Graph.plan.activeTodos().take(12).map { it.id to it.title }
                notes = Graph.notes.allNotes().filter { !it.trashed }.take(12)
                assistants = Graph.assistants.all()
            }
        }
    }
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
        dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("勾连到本轮对话", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "选中内容将注入本轮上下文；插件/工具域勾连会在本轮强制启用对应能力",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 460.dp)) {
                // 工具域（每组专属图标）
                item { SectionLabel("工具域（强制启用）") }
                items(AT_MENU_DOMAINS) { d ->
                    PickRow(
                        when (d) {
                            "schedule" -> AppIcons.Link.Schedule
                            "todo" -> AppIcons.Link.Todo
                            "note" -> AppIcons.Link.Note
                            "file" -> AppIcons.Kind.File
                            "info" -> AppIcons.Link.Info
                            "memory" -> AppIcons.Link.Memory
                            "automation" -> AppIcons.Link.Automation
                            "extend" -> AppIcons.Link.Extend
                            else -> AppIcons.Link.DomainFallback
                        },
                        "@域:$d", "@域:$d"
                    ) { onPick(LinkedRef("domain", 0L, d), "@域:$d") }
                }
                item { SectionLabel("插件（强制启用）") }
                items(AT_MENU_PLUGINS) { p ->
                    PickRow(
                        when (p) {
                            "office" -> AppIcons.Link.Office
                            "web" -> AppIcons.Link.Web
                            "ics" -> AppIcons.Link.CalendarExport
                            "phone" -> AppIcons.Link.Phone
                            else -> AppIcons.Link.PluginFallback
                        },
                        "@插件:$p", "@插件:$p"
                    ) { onPick(LinkedRef("plugin", 0L, p), "@插件:$p") }
                }
                if (memories.any { it.type != "skill" }) {
                    item { SectionLabel("记忆") }
                    items(memories.filter { it.type != "skill" }.take(12), key = { "m" + it.id }) { m ->
                        PickRow(AppIcons.Kind.Memory, "@记忆:" + m.name, "记忆·" + m.name) { onPick(LinkedRef("memory", m.id, m.name), "@记忆:" + m.name) }
                    }
                }
                if (memories.any { it.type == "skill" }) {
                    item { SectionLabel("技能") }
                    items(memories.filter { it.type == "skill" }.take(12), key = { "s" + it.id }) { m ->
                        PickRow(AppIcons.Kind.Skill, "@技能:" + m.name, "技能·" + m.name) { onPick(LinkedRef("skill", m.id, m.name), "@技能:" + m.name) }
                    }
                }
                if (schedules.isNotEmpty()) {
                    item { SectionLabel("日程（近 7 天）") }
                    items(schedules, key = { "sc" + it.first }) { (id, label) ->
                        PickRow(AppIcons.Link.Schedule, "@日程:" + label, "日程·" + label) { onPick(LinkedRef("schedule", id, label), "@日程:" + label) }
                    }
                }
                if (todos.isNotEmpty()) {
                    item { SectionLabel("待办") }
                    items(todos, key = { "t" + it.first }) { (id, label) ->
                        PickRow(AppIcons.Link.Todo, "@待办:" + label, "待办·" + label) { onPick(LinkedRef("todo", id, label), "@待办:" + label) }
                    }
                }
                if (notes.isNotEmpty()) {
                    item { SectionLabel("笔记") }
                    items(notes, key = { "n" + it.id }) { n ->
                        PickRow(AppIcons.Kind.Note, "@笔记:" + n.title, "笔记·" + n.title) { onPick(LinkedRef("note", n.id, n.title), "@笔记:" + n.title) }
                    }
                }
                if (assistants.isNotEmpty()) {
                    item { SectionLabel("智能体") }
                    items(assistants, key = { "a" + it.id }) { a ->
                        PickRow(AppIcons.Kind.Assistant, "@助手:" + a.name, "助手·" + a.name) { onPick(LinkedRef("assistant", a.id, a.name), "@助手:" + a.name) }
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 10.dp, bottom = 2.dp)
    )
}

@Composable
private fun PickRow(icon: ImageVector?, token: String, sub: String, onClick: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 9.dp)
    ) {
        Icon(
            icon ?: AppIcons.Link.DomainFallback, null, Modifier.size(15.dp),
            // R11：链接来源图标是装饰 → 中性
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(8.dp))
        Text(token, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.width(8.dp))
        Text(
            sub, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

/** v8 / 指令定义 */
data class SlashCommand(val cmd: String, val label: String, val hint: String)

val SLASH_COMMANDS = listOf(
    SlashCommand("/memory", "写入记忆", "把上一轮问答沉淀为一条记忆"),
    SlashCommand("/skill", "新建技能", "把可复用做法存为技能记忆"),
    SlashCommand("/compact", "压缩上下文", "立即把较早对话压缩为摘要"),
    SlashCommand("/clear", "清除此前上下文", "游标推到最后一条，下一轮只带增量"),
    SlashCommand("/model", "切换模型", "打开模型选择器"),
    // ---- v19.15 扩充（每条都对得上已有工具或已有界面，无需新后端）----
    SlashCommand("/phone", "手机操控", "勾连手机插件（读屏/点按/滑动/开应用/填字）并预填指令"),
    SlashCommand("/perm", "会话权限档位", "打开权限档位下拉（计划/确认/自动/完全访问；改动对本会话后续消息一直生效，不是只这一轮）"),
    SlashCommand("/plugins", "插件与工具域", "让 AI 列出当前启用的插件/工具域并说明如何切换"),
    SlashCommand("/undo", "撤销上次改动", "回滚上一次由 AI 执行的写入操作"),
    SlashCommand("/usage", "token 用量", "让 AI 汇总本轮的 token 用量"),
    SlashCommand("/new", "新建会话", "清空当前会话，回到未发送状态"),
    SlashCommand("/today", "今日速览", "预填「汇总今天的日程与待办」"),
    SlashCommand("/automation", "自动化管理", "打开自动化任务管理页"),
    SlashCommand("/recall", "记忆检索", "预填「在记忆库里检索：」"),
    SlashCommand("/export", "导出给电脑", "立刻导出 phone-dump.json 到同步文件夹")
)

/**
 * v19.15：@ 菜单的「工具域 / 插件」清单**从注册表派生**。
 * 旧写法在 UI 里把注册表抄成 `listOf("schedule",...)` / `listOf("office","web","ics")`，
 * 结果新增插件（phone 手机操控包）静默不出现、还漏了 automation/extend/system 三个域——
 * 改成派生后，以后加插件不会再漏。
 * 排除 `system` 域：它是常开域（见 ToolRegistry.DOMAINS 注释），勾选没有意义。
 */
val AT_MENU_DOMAINS: List<String> = ToolRegistry.DOMAINS.keys.filter { it != "system" }
val AT_MENU_PLUGINS: List<String> = ToolRegistry.PLUGINS.keys.toList()

/** v8 会话级工具权限管理（三态：跟随全局 / 允许 / 禁止） */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionPermSheet(sessionId: Long, onDismiss: () -> Unit) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var allow by remember { mutableStateOf(setOf<String>()) }
    var deny by remember { mutableStateOf(setOf<String>()) }
    LaunchedEffect(sessionId) {
        runCatching {
            val s = Graph.chat.session(sessionId) ?: return@LaunchedEffect
            val obj = org.json.JSONObject(s.permJson.ifBlank { "{}" })
            val a = obj.optJSONArray("allow")
            allow = a?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() } ?: emptySet()
            val d = obj.optJSONArray("deny")
            deny = d?.let { arr -> (0 until arr.length()).map { arr.optString(it) }.toSet() } ?: emptySet()
        }
    }
    fun persist(a: Set<String>, d: Set<String>) {
        scope.launch {
            val json = org.json.JSONObject()
                .put("allow", org.json.JSONArray(a.toList()))
                .put("deny", org.json.JSONArray(d.toList()))
                .toString()
            Graph.chat.setSessionPerm(sessionId, json)
        }
    }
    ModalBottomSheet(contentWindowInsets = { WindowInsets.navigationBars }, 
        dragHandle = { io.github.m0use001.planability.ui.common.SheetDragHandleOnly() },
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ) {
        Column(Modifier.padding(horizontal = 18.dp)) {
            Text("会话工具权限", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                "禁止=本轮直接拒绝；允许=跳过确认卡（危险工具仍必确认）；跟随全局=按风险门与全局设置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(ToolRegistry.ALL, key = { it.name }) { def ->
                    val state = when {
                        def.name in deny -> 2
                        def.name in allow -> 1
                        else -> 0
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .padding(horizontal = 4.dp, vertical = 5.dp)
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(def.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                            Text(
                                def.risk.name + " · " + def.cnDescription,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1, overflow = TextOverflow.Ellipsis
                            )
                        }
                        FilterChip(
                            colors = filterChipColors(),
                            selected = state != 0,
                            onClick = {
                                when (state) {
                                    0 -> { allow = allow + def.name; persist(allow, deny) }
                                    1 -> { allow = allow - def.name; deny = deny + def.name; persist(allow, deny) }
                                    else -> { deny = deny - def.name; persist(allow, deny) }
                                }
                            },
                            label = {
                                Text(
                                    when (state) {
                                        1 -> "允许"
                                        2 -> "禁止"
                                        else -> "跟随全局"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (state == 2) MaterialTheme.colorScheme.error
                                    else if (state == 1) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        )
                    }
                }
                item { Spacer(Modifier.height(30.dp)) }
            }
        }
    }
}

/** v8 /memory、/skill：把上一轮内容快速沉淀为记忆 */
@Composable
fun MemoryQuickDialog(
    initialType: String,
    suggestedBody: String,
    onDismiss: () -> Unit
) {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var type by remember { mutableStateOf(initialType) }
    var name by remember { mutableStateOf(suggestSlug(suggestedBody)) }
    var desc by remember { mutableStateOf(suggestedBody.lineSequence().firstOrNull()?.take(60) ?: "") }
    var body by remember { mutableStateOf(suggestedBody.take(1200)) }
    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (type == "skill") "新建技能" else "写入记忆", style = MaterialTheme.typography.titleSmall) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(colors = filterChipColors(), selected = type == "feedback", onClick = { type = "feedback" }, label = { Text("反馈") })
                    FilterChip(colors = filterChipColors(), selected = type == "reference", onClick = { type = "reference" }, label = { Text("参考") })
                    FilterChip(colors = filterChipColors(), selected = type == "skill", onClick = { type = "skill" }, label = { Text("技能") })
                    FilterChip(colors = filterChipColors(), selected = type == "project", onClick = { type = "project" }, label = { Text("项目") })
                }
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("唯一名（英文短词）") }, singleLine = true)
                OutlinedTextField(value = desc, onValueChange = { desc = it }, label = { Text("一句话描述") }, singleLine = true)
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("正文") }, minLines = 3)
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && body.isNotBlank(),
                onClick = {
                    scope.launch {
                        Graph.memories.upsert(
                            io.github.m0use001.planability.data.MemoryEntity(
                                name = name.trim().take(40),
                                type = type,
                                description = desc.trim().take(120),
                                body = body.trim().take(2000),
                                inIndex = false,
                                importance = 2,
                                originSession = 0
                            )
                        )
                    }
                    onDismiss()
                }
            ) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } }
    )
}

internal fun suggestSlug(text: String): String {
    val ascii = Regex("[A-Za-z0-9_-]{3,}").findAll(text).firstOrNull()?.value?.lowercase()
    if (ascii != null) return ascii.take(30)
    val cjk = Regex("[\\u4e00-\\u9fff]+").findAll(text).firstOrNull()?.value ?: "note"
    return "note-" + cjk.take(6) + "-" + (System.currentTimeMillis() / 1000 % 10000)
}

/** v8 / 指令菜单：输入以 / 开头时显示在输入框上方，点击即执行 */
@Composable
fun SlashMenu(typed: String, onExecute: (String) -> Unit) {
    val prefix = typed.trimStart()
    val matches = if (prefix == "/") SLASH_COMMANDS else SLASH_COMMANDS.filter { it.cmd.startsWith(prefix) }
    if (matches.isEmpty()) return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(vertical = 4.dp)
    ) {
        matches.forEach { c ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onExecute(c.cmd) }
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Text(
                    c.cmd, style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(c.label, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.weight(1f))
                Text(
                    c.hint, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
