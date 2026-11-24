package io.github.m0use001.planability.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * 2026-10-29：项目记忆的**落盘镜像**（`<项目数据>/项目记忆/`），2026-11-08 起**双向**。
 *
 * ## 为什么做、为什么是单向
 *
 * 项目记忆原先只活在 Room 的 `memories` 表里——用户翻开手机的「项目数据/项目记忆」
 * （Syncthing 同步到电脑也一样）什么都看不见，记忆对用户是黑盒。
 * 现在每条记忆导出成 `<slug>.md`（带 frontmatter）+ `MEMORY.md` 索引，人能直接读。
 *
 * **DB 是唯一真源**：这个目录是导出物，用户在电脑端改了 `slug.md`，下次写入时会被覆盖。
 * 要长期保存的资料应该放「项目文件」——这条规则要写进 MEMORY.md 头部，让翻文件的人第一眼看到。
 *
 * ## 2026-11-08 为什么变成双向（真实报障）
 *
 * 用户让 AI「把新记忆写到项目记忆文件夹」，AI 用写文件工具落了 `xxx.md`：
 * **文件管理器里看得到，App 记忆库一条都没有**——因为这条链路是单向的，而且下一次
 * 导出会把这个"不认识的文件"当垃圾删掉（keep 集之外的都清）。等于 AI 以为记住了、
 * 用户以为记住了，实际是丢的。
 *
 * 现在导出前先**收养孤儿文件**（[adoptStray]）：目录里不是 App 生成的 `.md`，
 * 解析 frontmatter 后写成真正的记忆条目（然后按标准格式重导一遍）。
 * 三条防误伤：
 * - frontmatter 的 `name` 与库里任何记忆同名 → 视为"改名残留"，不收养（让清理循环删掉）；
 * - 文件名 slug 命中现有记忆 → 就是导出物，跳过；
 * - 文件没有可读正文（空/只有 frontmatter）→ 不收养，直接跳过。
 *
 * ## 防抖
 *
 * `upsert` 在一轮对话里可能连发好几条（自动沉淀一次加 2-3 条），每次全量导出既浪费 IO
 * 又会让 Syncthing 连续抓中间态。按项目各挂一个 Job，800ms 内的多次写入合并成一次导出。
 */
object MemoryFiles {

    private const val DEBOUNCE_MS = 800L

    /** App 自己维护的文件，永不收养 */
    private val GENERATED = setOf("MEMORY.md", "场景.md", "画像.md")

    private val jobs = ConcurrentHashMap<Long, Job>()

    /**
     * 启动补跑：把每个"有记忆"的项目都导出一遍（幂等，覆盖即最新）。
     *
     * 2026-11-05 补 **记忆清零的项目**：只遍历 `all()`（isDeleted=0）会让「最后一条
     * 记忆被删掉的项目」从集合里消失，其镜像目录从此没人清理——真机实锤：
     * `_global/项目记忆/` 的 MEMORY.md/handoff-current.md 停在 10-31（全局记忆
     * 删空后冻结 5 天），用户在文件管理器看到"删了记忆文件还在"。
     * 用 allIncludingDeleted() 把"曾有记忆"的项目也拉进来——export() 的清理循环
     * 会删掉库里已不存在的导出物（软删的也不在 keep 集，同样清掉）。
     */
    suspend fun exportAll() = withContext(Dispatchers.IO) {
        val pids = (Graph.db.memoryDao().all().map { it.projectId } +
            Graph.db.memoryDao().allIncludingDeleted().map { it.projectId }).distinct()
        pids.forEach { pid ->
            runCatching { export(pid) }
        }
    }

    /** 记忆增删改后喊一声；按项目合并导出 */
    fun schedule(projectId: Long) {        val pid = projectId
        jobs[pid]?.cancel()
        jobs[pid] = Graph.appScope.launch {
            delay(DEBOUNCE_MS)
            runCatching { export(pid) }
            jobs.remove(pid)
        }
    }

    /**
     * 收养目录里的孤儿 `.md`（不是 App 生成的）→ 写成真正的记忆条目。
     * 返回收养条数；只读盘 + 写库，不删文件（清理交给导出末尾的 keep 循环）。
     */
    internal suspend fun adoptStray(projectId: Long, dir: File): Int {
        val files = dir.listFiles()?.filter { it.isFile && it.name.endsWith(".md", ignoreCase = true) }
            ?: return 0
        if (files.isEmpty()) return 0
        val existing = Graph.db.memoryDao().all().filter { it.projectId == projectId }
        val slugs = existing.map { safeSlug(it.name) + ".md" }.toSet()
        val names = existing.map { it.name }.toSet()
        var adopted = 0
        files.forEach { f ->
            if (f.name in GENERATED || f.name in slugs) return@forEach
            val text = runCatching { f.readText(Charsets.UTF_8) }.getOrNull() ?: return@forEach
            val m = parseStray(f.name, text) ?: return@forEach
            if (m.name in names) return@forEach          // 改名残留，别重复收养
            runCatching {
                Graph.db.memoryDao().upsert(m.copy(projectId = projectId))
                adopted++
            }
        }
        return adopted
    }

    /**
     * 解析一个孤儿文件 → 记忆条目（**纯函数**，可单测）。
     * 支持带 frontmatter（App 自己那套 `key: value`）与纯 markdown 两种写法；
     * 没有正文也没有 description 的空壳返回 null（不收养）。
     */
    internal fun parseStray(fileName: String, text: String): MemoryEntity? {
        val raw = text.replace("\r\n", "\n")
        var name = fileName.removeSuffix(".md").removeSuffix(".MD").trim()
        var description = ""
        var type = "project"
        var importance = 1
        var body = raw
        if (raw.startsWith("---")) {
            val end = raw.indexOf("\n---", startIndex = 3)
            if (end > 0) {
                val head = raw.substring(3, end)
                body = raw.substring(end + 4).trim()
                head.lineSequence().forEach { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) return@forEach
                    val k = line.substring(0, i).trim().lowercase()
                    val v = line.substring(i + 1).trim()
                    when (k) {
                        "name" -> if (v.isNotBlank()) name = v
                        "description" -> description = v
                        "type" -> if (v.isNotBlank()) type = v
                        "importance" -> v.toIntOrNull()?.let { importance = it.coerceIn(1, 3) }
                    }
                }
            }
        }
        body = body.trim()
        if (body.isBlank()) {
            if (description.isBlank()) return null
            body = description
        }
        if (description.isBlank()) {
            description = body.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(60).orEmpty()
        }
        val now = io.github.m0use001.planability.core.AppTime.nowMillis()
        return MemoryEntity(
            name = name.take(80),
            type = type,
            description = description,
            body = body,
            importance = importance,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 立刻导出一个项目（启动时补跑 / 防抖到期） */
    suspend fun export(projectId: Long) = withContext(Dispatchers.IO) {        val dir = ProjectDirs.memoryDir(projectId)
        if (!dir.isDirectory && !dir.mkdirs()) return@withContext

        // 2026-11-08：导出前先收养孤儿文件（AI 直接写进来的 .md）——不收养的话下面
        // 的清理循环会把它删掉，用户看到的是"文件管理器有、记忆库没有、过一会儿文件也没了"
        runCatching { adoptStray(projectId, dir) }

        val mems = Graph.db.memoryDao().all().filter { it.projectId == projectId }
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)

        // 索引：**0 条时不写、并让清理循环删掉旧的**——2026-11-05 用户实报
        // 「记忆库删光了，文件管理器里 MEMORY.md/场景.md 还在，一眼就是没删干净」。
        // 目录空着（0 项）是用户唯一不会误读的信号；有记忆时下次导出会重建索引。
        val index = buildString {
            appendLine("# 项目记忆（自动生成，请勿手改）")
            appendLine()
            appendLine("> 本目录由 App 从记忆库单向导出：**在这里改会被下次写入覆盖**，")
            appendLine("> 要长期保存的资料请放到「项目文件」。共 ${mems.size} 条。")
            appendLine()
            mems.forEach { m ->
                val desc = m.description.ifBlank { m.body.take(60) }
                val mark = when {
                    m.supersededBy != 0L -> "（已被取代）"
                    m.invalidAt != 0L -> "（已失效）"
                    else -> ""
                }
                appendLine("- [${m.name}](${safeSlug(m.name)}.md) — $desc$mark")
            }
            if (mems.isNotEmpty()) {
                appendLine()
                appendLine("相关：[场景.md](场景.md) · [画像.md](画像.md)")
            }
        }
        val keep = mutableSetOf<String>()
        if (mems.isNotEmpty()) {
            keep += "MEMORY.md"
            File(dir, "MEMORY.md").writeText(index, Charsets.UTF_8)
        } else {
            File(dir, "MEMORY.md").delete()
        }

        // 每条一份
        mems.forEach { m ->
            val name = safeSlug(m.name) + ".md"
            keep += name
            val status = when {
                m.supersededBy != 0L -> "superseded"
                m.invalidAt != 0L -> "invalid"
                else -> "active"
            }
            val body = buildString {
                appendLine("---")
                appendLine("name: ${m.name}")
                appendLine("description: ${m.description}")
                appendLine("type: ${m.type}")
                appendLine("importance: ${m.importance}")
                appendLine("priority: ${m.priority}")
                if (m.scene.isNotBlank()) appendLine("scene: ${m.scene}")
                appendLine("status: $status")
                if (m.supersededBy != 0L) appendLine("supersededBy: ${m.supersededBy}")
                appendLine("updatedAt: ${fmt.format(Date(m.updatedAt))}")
                appendLine("---")
                appendLine()
                append(m.body)
                appendLine()
            }
            File(dir, name).writeText(body, Charsets.UTF_8)
        }

        // v45 P5：L2 场景与 L3 画像也进镜像（资产环——离开 App 也能看到分层记忆的全貌）
        val scenes = Graph.db.memorySceneDao()
            .activeForProject(projectId, 15, System.currentTimeMillis())
        if (scenes.isNotEmpty() && mems.isNotEmpty()) {
            keep += "场景.md"
            File(dir, "场景.md").writeText(buildString {
                appendLine("# 项目场景（自动生成，请勿手改）")
                appendLine()
                scenes.forEach { sc ->
                    appendLine("## ${sc.name}（heat ${sc.heat}）")
                    if (sc.summary.isNotBlank()) appendLine(sc.summary)
                    appendLine()
                }
            }, Charsets.UTF_8)
        }
        val persona = runCatching { Graph.db.memoryPersonaDao().get() }.getOrNull()
        if (persona != null && persona.body.isNotBlank() && mems.isNotEmpty()) {
            keep += "画像.md"
            File(dir, "画像.md").writeText(buildString {
                appendLine("---")
                appendLine("version: ${persona.version}")
                appendLine("updatedAt: ${fmt.format(Date(persona.updatedAt))}")
                appendLine("---")
                appendLine()
                append(persona.body)
                appendLine()
            }, Charsets.UTF_8)
        }

        // 清掉已经不存在于库里的导出物（目录归 app 管，只清 .md，别动用户放的其它文件）
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }?.forEach { f ->
            if (f.name !in keep) runCatching { f.delete() }
        }
    }

    /** slug → 安全文件名（记忆 name 理论上是 slug，但用户/模型什么都可能写） */
    private fun safeSlug(raw: String): String =
        raw.trim()
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\s+"), "-")
            .take(80)
            .ifBlank { "memory" }
}
