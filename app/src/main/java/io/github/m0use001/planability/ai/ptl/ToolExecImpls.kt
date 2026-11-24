package io.github.m0use001.planability.ai.ptl

import io.github.m0use001.planability.ai.UsageLedger
import io.github.m0use001.planability.core.AppTime
import io.github.m0use001.planability.core.toLocalDate
import io.github.m0use001.planability.data.CheckinFormat
import io.github.m0use001.planability.data.COVER_SOLID
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.data.MemoryEntity
import io.github.m0use001.planability.data.NoteEntity
import io.github.m0use001.planability.data.Schedule
import io.github.m0use001.planability.data.Todo
import io.github.m0use001.planability.ui.files.parseDocumentForTool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters

/** M4 域工具实现 + 容错时间解析（被 ToolExecutor 分发调用） */
object ToolExecImpls {

    // ---------- 容错时间解析（guIDE 归一化风格） ----------

    fun parseDateTime(raw: String): Long? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.all { ch -> ch.isDigit() } && t.length >= 12) return t.toLongOrNull()
        val today = AppTime.today()
        fun resolveDay(word: String): LocalDate = when (word) {
            "明天" -> today.plusDays(1)
            "后天" -> today.plusDays(2)
            "大后天" -> today.plusDays(3)
            "昨天" -> today.minusDays(1)
            else -> today
        }
        val rel = Regex("^(今天|明天|后天|大后天|昨天)").find(t)?.groupValues?.get(1)
        val rest = t.removePrefix(rel ?: "").trim()
        if (rel != null) {
            val hm = Regex("(\\d{1,2}):(\\d{2})").find(rest)
            val day = resolveDay(rel)
            return if (hm != null) AppTime.millisOf(day, hm.groupValues[1].toInt(), hm.groupValues[2].toInt())
            else AppTime.dayKey(day)
        }
        val full = Regex("(\\d{4})[-/.](\\d{1,2})[-/.](\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2}))?").find(t)
        if (full != null) {
            val d = LocalDate.of(full.groupValues[1].toInt(), full.groupValues[2].toInt(), full.groupValues[3].toInt())
            // groupValues 对未参与匹配的组返回空串而非 null，必须用 isNotEmpty 判断
            return if (full.groupValues[4].isNotEmpty() && full.groupValues[5].isNotEmpty())
                AppTime.millisOf(d, full.groupValues[4].toInt(), full.groupValues[5].toInt())
            else AppTime.dayKey(d)
        }
        val short = Regex("(\\d{1,2})[-/.](\\d{1,2})(?:[ T](\\d{1,2}):(\\d{2}))?$").find(t)
        if (short != null) {
            val d = LocalDate.of(today.year, short.groupValues[1].toInt(), short.groupValues[2].toInt())
            return if (short.groupValues[3].isNotEmpty() && short.groupValues[4].isNotEmpty())
                AppTime.millisOf(d, short.groupValues[3].toInt(), short.groupValues[4].toInt())
            else AppTime.dayKey(d)
        }
        // 2026-11-14 真机会话实录：「11月19日 16:00」被拒，而错误提示自己拿
        // 「11月13日 15:00」当合法例子——「X月X日/号 [HH:MM]」这一档根本没有，
        // 提示在撒谎。补上；已过去超过 7 天的按最近未来（明年）理解，
        // 一周内的过去日期保留（用户可能就指前几天）。
        val cjkDate = Regex("(\\d{1,2})月(\\d{1,2})[日号](?:\\s*(\\d{1,2}):(\\d{2}))?").find(t)
        if (cjkDate != null) {
            val d = runCatching {
                LocalDate.of(today.year, cjkDate.groupValues[1].toInt(), cjkDate.groupValues[2].toInt())
            }.getOrNull() ?: return null
            val eff = if (d.isBefore(today.minusDays(7))) d.plusYears(1) else d
            return if (cjkDate.groupValues[3].isNotEmpty() && cjkDate.groupValues[4].isNotEmpty())
                AppTime.millisOf(eff, cjkDate.groupValues[3].toInt(), cjkDate.groupValues[4].toInt())
            else AppTime.dayKey(eff)
        }
        val hmOnly = Regex("^(\\d{1,2}):(\\d{2})$").find(t)
        if (hmOnly != null) {
            var d = today
            if (AppTime.millisOf(d, hmOnly.groupValues[1].toInt(), hmOnly.groupValues[2].toInt()) < AppTime.nowMillis()) d = today.plusDays(1)
            return AppTime.millisOf(d, hmOnly.groupValues[1].toInt(), hmOnly.groupValues[2].toInt())
        }
        val wd = Regex("周([一二三四五六日天])").find(t)
        if (wd != null) {
            val target = when (wd.groupValues[1]) {
                "一" -> DayOfWeek.MONDAY
                "二" -> DayOfWeek.TUESDAY
                "三" -> DayOfWeek.WEDNESDAY
                "四" -> DayOfWeek.THURSDAY
                "五" -> DayOfWeek.FRIDAY
                "六" -> DayOfWeek.SATURDAY
                else -> DayOfWeek.SUNDAY
            }
            val d = today.with(TemporalAdjusters.nextOrSame(target))
            val hm = Regex("(\\d{1,2}):(\\d{2})").find(t)
            return if (hm != null) AppTime.millisOf(d, hm.groupValues[1].toInt(), hm.groupValues[2].toInt())
            else AppTime.dayKey(d)
        }
        return null
    }

    internal fun fmtDay(millis: Long): String {
        val d = millis.toLocalDate()
        return d.monthValue.toString() + "/" + d.dayOfMonth
    }

    /** v11：priority 参数解析（high/normal/low 或 0/1/2）；schedule_update 用可空区分"未传" */
    internal fun priorityOf(a: JSONObject): Int = when (a.optString("priority", "normal").lowercase()) {
        "high", "0" -> 0
        "low", "2" -> 2
        else -> 1
    }

    internal fun priorityOfOrNull(a: JSONObject): Int? {
        if (!a.has("priority")) return null
        return when (a.optString("priority").lowercase()) {
            "high", "0" -> 0
            "low", "2" -> 2
            "normal", "1" -> 1
            else -> null
        }
    }

    /** 2026-11-02 用户「AI 把重要性拉满，紧急性直接不设置」：四象限的**紧急轴**此前在工具
     *  参数里根本不存在（todo/schedule 四个工具都只有 priority），AI 建的条目 urgency
     *  永远 -1（未设置）。三档参数映射五档存库：high→4 / normal→2 / low→0，未传=null
     *  （保持 -1 未设置，与编辑器口径一致）。 */
    internal fun urgencyOfOrNull(a: JSONObject): Int? {
        if (!a.has("urgency")) return null
        return when (a.optString("urgency").lowercase()) {
            "high", "4" -> 4
            "normal", "2" -> 2
            "low", "0" -> 0
            else -> null
        }
    }

    /** v11：坐标参数解析（缺失/JSON null/NaN → null；org.json 的 optDouble 缺省返回 NaN 不能直接用） */
    internal fun optCoord(a: JSONObject, key: String): Double? {
        if (!a.has(key) || a.isNull(key)) return null
        return a.optDouble(key).takeUnless { it.isNaN() }
    }

    /**
     * 读路径收敛（2026-11-09 夜）：所有"读文件"工具共用这一套解析，
     * 消灭「同一个文件，用 file_read_document 读得到、用 file_library_read 读不到」这类分叉
     * （今天英语课附件/绑定项目 docx 两次报障的根因都是路径解析分叉）。
     *
     * 顺序：① attachments / exports ② ~/ 展开 ③ 绝对路径（自有目录直通、其余 FsGuard）
     * ④ 项目文件树（会话所属项目优先，裸名唯一匹配） ⑤ 文件库全树精确匹配（任意项目 +
     * 全局，含「项目记忆/」这类只读镜像——read_document 此前读不到它）。
     *
     * 来源差异只影响报错文案，不影响"能不能读到"。
     */
    internal suspend fun resolveUnified(raw: String, sessionId: Long): Pair<File?, String> {
        val (f1, e1) = resolveReadableFile(raw, sessionId)
        if (f1 != null) return f1 to ""
        val name = raw.trim().removePrefix("./")
        if (name.isNotEmpty() && !name.startsWith("/") && !name.contains("..")) {
            val hits = (io.github.m0use001.planability.data.ProjectFileRepo.tree(0L) +
                Graph.projects.all().flatMap { io.github.m0use001.planability.data.ProjectFileRepo.tree(it.id) })
                .filterIsInstance<io.github.m0use001.planability.data.ProjectFileRepo.Node.File>()
                .filter {
                    it.f.name == name ||
                        (if (it.f.sub.isBlank()) it.f.name else it.f.sub + "/" + it.f.name) == name
                }
            val hit = hits.singleOrNull()
            if (hit != null) {
                val dir = io.github.m0use001.planability.data.ProjectFileRepo.resolveDir(hit.f.projectId, hit.f.sub)
                val real = dir?.let { File(it, hit.f.name) }
                if (real != null && real.exists() && real.isFile) return real to ""
            }
            if (hits.size > 1) return null to "文件库里有 " + hits.size + " 份「" + name + "」（" +
                hits.take(3).joinToString("、") {
                    if (it.f.sub.isBlank()) it.f.name else it.f.sub + "/" + it.f.name
                } + (if (hits.size > 3) "…" else "") + "），请带子目录路径"
        }
        return null to e1
    }

    /**
     * 只允许应用私有目录内的 attachments/exports 相对路径（防穿越）。
     *
     * `exports/` 是**虚拟前缀**：它解析成"当前对话的文件目录"，
     * 见 [ExportStore.dirFor]。AI 侧的工具契约不用变（仍写 `exports/xxx`），
     * 变的是落点跟着对话走。
     */
    /**
     * 2026-11-12 真机会话实锤（用户「我不是早说了之前那个意大利人设作废了吗？？」）：
     * 之前唯一的写通道是 memory_add——模型只能堆「作废声明」，承载旧说法的条目
     * invalidAt 一直是 0、照常被召回/常驻注入/混进摘要，作废设定反复复提。
     * memory_invalidate 把 v45 就有的失效机制（invalidAt/supersededBy，recall 与工作层
     * 查询均已过滤）暴露给模型：标失效不删行，审计历史与镜像（status: invalid）都保留。
     * 单独成函数还有一层原因：execute 是巨型 when，方法体已顶到 JVM 64KB 上限
     * （MethodTooLargeException），新分支必须外提。
     */
    /**
     * v57（2026-11-21 串味审计）：记忆按名/按 id 取的**项目作用域读**。
     *
     * 可见范围 = 本会话项目 + 全局(0) + 常驻索引(inIndex=1)，与 recall 的跨项目规则一致；
     * 别的项目的普通条目一律按「不存在」处理——旧版按名/按 id 都是全局裸查，小说会话
     * 能翻到、能作废、能删线代项目的记忆。可见性判断放 [MemoryDao.byNameVisible]（名字路）
     * 与这里（id 路也要复核，防止模型拿别的会话泄漏来的 id 直捣）。
     */
    private suspend fun visibleMemory(key: String, sessionId: Long): MemoryEntity? {
        val pid = Graph.chat.session(sessionId)?.projectId ?: 0L
        val m = key.toLongOrNull()?.let { Graph.memories.byId(it) }
            ?: runCatching { Graph.db.memoryDao().byNameVisible(key, pid) }.getOrNull()
        if (m == null) return null
        return if (m.projectId == pid || m.projectId == 0L || m.inIndex) m else null
    }

    private suspend fun runMemoryInvalidate(sessionId: Long, a: JSONObject): ToolExecResult {
        val names = a.optString("names").trim()
        if (names.isEmpty()) return ToolExecResult.fail(
            "names 为空：先用 memory_search 找到承载旧说法的记忆条目，把它们的内部名或 id 传进来（逗号分隔）"
        )
        val reason = a.optString("reason").trim().ifBlank { "未写明" }
        val replacedRaw = a.optString("replaced_by").trim()
        val now = AppTime.nowMillis()
        val replaced = if (replacedRaw.isEmpty()) null else visibleMemory(replacedRaw, sessionId)
        if (replacedRaw.isNotEmpty() && (replaced == null || replaced.isDeleted))
            return ToolExecResult.fail("replaced_by「$replacedRaw」不存在，先用 memory_add 写好新条目再作废旧条目")
        val done = mutableListOf<String>()
        val skipped = mutableListOf<String>()
        val missing = mutableListOf<String>()
        val undoneRows = mutableListOf<MemoryEntity>()
        for (raw in names.split(',', '，', '、')) {
            val key = raw.trim()
            if (key.isEmpty()) continue
            val m = visibleMemory(key, sessionId)
            if (m == null || m.isDeleted) { missing.add(key); continue }
            if (m.id == replaced?.id) return ToolExecResult.fail("「" + m.name + "」不能自己取代自己")
            if (m.invalidAt != 0L && m.invalidAt <= now) { skipped.add(m.name); continue }
            undoneRows.add(m)
            if (replaced != null) Graph.memories.markSuperseded(m.id, replaced.id, now)
            else Graph.memories.markInvalid(m.id, now, reason)
            done.add(m.name)
        }
        if (undoneRows.isNotEmpty()) {
            ToolExecutor.registerUndo(
                UndoEntry(sessionId, "恢复被作废的记忆（" + undoneRows.size + " 条）") {
                    undoneRows.forEach { Graph.memories.upsert(it) }
                }
            )
            // 持久化撤销复用 memory_update 还原分支（按 JSON 重建行，invalidAt 回 0）
            undoneRows.forEach { m ->
                ToolExecutor.registerUndoPersisted(
                    sessionId, "恢复记忆「" + m.name + "」", "memory_update",
                    org.json.JSONObject()
                        .put("id", m.id).put("name", m.name).put("type", m.type)
                        .put("description", m.description).put("body", m.body.take(2000))
                        .put("projectId", m.projectId).put("importance", m.importance)
                        .toString()
                )
            }
        }
        if (done.isEmpty()) return ToolExecResult.fail(
            "没有作废任何条目；未找到或已删：" + missing.joinToString("、").ifEmpty { "—" } +
                "；本就已失效：" + skipped.joinToString("、").ifEmpty { "—" }
        )
        return ToolExecResult.ok(
            "已作废 " + done.size + " 条（" + done.joinToString("、") + "），原因：" + reason +
                (if (replaced != null) "；由「" + replaced.name + "」取代" else "") +
                (if (skipped.isNotEmpty()) "；跳过本已失效：" + skipped.joinToString("、") else "") +
                (if (missing.isNotEmpty()) "；未找到：" + missing.joinToString("、") else "") +
                "。确认新说法已记录（memory_add 或写入设定卡）。",
            "已作废 " + done.size + " 条记忆"
        )
    }

    private suspend fun resolveInnerFile(rel: String, sessionId: Long): File? {
        val t = rel.trim().removePrefix("/").replace("..", "")
        if (!(t.startsWith("attachments/") || t.startsWith("exports/"))) return null
        val f = if (t.startsWith("exports/"))
            File(io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId), t.removePrefix("exports/"))
        else File(Graph.appContext.filesDir, t)
        return if (f.exists() && f.isFile) f else null
    }

    /**
     * 文档/图片读取的**统一路径解析**（2026-11-03 用户「英语课项目里 AI 读不到文件」）。
     *
     * 原来只认 `attachments/` 与 `exports/`，而用户资料在**项目文件**里——模型把能想到的
     * 五种写法（绝对路径 / `~/…` / `项目名/项目文件/…` / `项目文件/…` / 裸文件名）全试了，
     * 一个都判「路径不合法」：
     *  · 1104/1105 `file_library_read` 读 docx → content 是空串（二进制不进库），
     *    模型合理地改用文档解析器，然后卡在路径上；
     *  · 1114 连 `fs_search` 自己搜出来的真实绝对路径都不收。
     *
     * 现在按顺序：
     * ①`attachments/`、`exports/`（聊天附件与应用导出，原语义不变）
     * ②`~/` → 外部存储根
     * ③绝对路径 → FsGuard（外部存储根内、拒 Android/data·obb、防 canonical 逃逸）
     * ④相对路径 → 项目文件树：`<项目名>/项目文件/<子>`、`<项目名>/项目数据/项目文件/<子>`、
     *    `项目数据/项目文件/<子>`、`项目文件/<子>`、`<项目文件子目录>/<文件名>`、裸文件名
     *    （后四种以**会话所属项目**为根；裸名在项目文件里按文件名唯一匹配）
     *
     * 返回 (文件, 错误文案)：文件为 null 时错误文案直接回给模型（说清试过哪些根）。
     */
    internal suspend fun resolveReadableFile(raw: String, sessionId: Long): Pair<File?, String> {
        val p = raw.trim()
        if (p.isEmpty()) return null to "路径不能为空"
        // ① 应用内附件/导出
        resolveInnerFile(p, sessionId)?.let { return it to "" }
        // ② ~/ 展开
        val home = runCatching { android.os.Environment.getExternalStorageDirectory().absolutePath }.getOrNull()
            ?: "/storage/emulated/0"
        val expanded = if (p == "~" || p.startsWith("~/")) home + p.removePrefix("~") else p
        // ③ 绝对路径：**先看是不是应用自己的目录**——未绑定项目的项目文件与导出都在
        //    自己的 externalFilesDir/filesDir 下，读它们本来就不需要「所有文件访问」权限；
        //    一律交给 FsGuard 会在未授权时被整体拒掉（模拟器实测）。自有目录直通，其余走 FsGuard。
        if (expanded.startsWith("/")) {
            val canon = runCatching { File(expanded).canonicalFile }.getOrNull()
            val ownRoots = listOfNotNull(
                Graph.appContext.filesDir,
                Graph.appContext.cacheDir,
                Graph.appContext.noBackupFilesDir,
                Graph.appContext.getExternalFilesDir(null)
            ).mapNotNull { runCatching { it.canonicalPath }.getOrNull() }
            val canonPath = canon?.path
            val ownHit = canonPath != null && ownRoots.any {
                canonPath == it || canonPath.startsWith(it + java.io.File.separator)
            }
            if (ownHit && canon != null && canon.isFile) return canon to ""
            val g = io.github.m0use001.planability.core.FsGuard.resolve(expanded)
            val f = g.file
            if (f != null && f.exists() && f.isFile) return f to ""
            return null to (g.error ?: ("文件不存在：" + expanded))
        }
        // ④ 项目文件相对路径
        val segs = p.replace('\\', '/').trim('/').split('/').filter { it.isNotBlank() }
        if (segs.isEmpty()) return null to "路径不能为空"
        // 剥「项目名 / 项目数据」前缀
        var rest = segs
        var pid: Long? = null
        if (rest.first() == io.github.m0use001.planability.data.ProjectDirs.DATA) rest = rest.drop(1)
        if (rest.firstOrNull() == io.github.m0use001.planability.data.ProjectDirs.FILES) rest = rest.drop(1)
        if (pid == null) {
            // 首段若是项目名，以它为根
            val maybe = Graph.projects.all().firstOrNull { it.name == rest.firstOrNull() }
            if (maybe != null) {
                pid = maybe.id
                rest = rest.drop(1)
                if (rest.firstOrNull() == io.github.m0use001.planability.data.ProjectDirs.DATA) rest = rest.drop(1)
                if (rest.firstOrNull() == io.github.m0use001.planability.data.ProjectDirs.FILES) rest = rest.drop(1)
            }
        }
        if (pid == null) {
            pid = Graph.chat.session(sessionId)?.projectId?.takeIf { it > 0L } ?: 0L
        }
        val filesRoot = File(io.github.m0use001.planability.data.ProjectFileRepo.dirOf(pid), io.github.m0use001.planability.data.ProjectDirs.FILES)
        // 精确相对路径
        if (rest.isNotEmpty()) {
            val direct = File(filesRoot, rest.joinToString("/"))
            if (direct.exists() && direct.isFile) return direct to ""
        }
        // 裸文件名/子目录名匹配：在项目文件树里按 name 找（唯一命中才算）
        val wanted = rest.lastOrNull() ?: p
        val hits = runCatching {
            io.github.m0use001.planability.data.ProjectFileRepo.tree(pid)
                .filterIsInstance<io.github.m0use001.planability.data.ProjectFileRepo.Node.File>()
                .filter { it.f.name == wanted }
        }.getOrDefault(emptyList())
        val hit = hits.singleOrNull()
        if (hit != null) {
            val real = File(filesRoot, if (hit.sub.isBlank()) hit.f.name else hit.sub + "/" + hit.f.name)
            if (real.exists() && real.isFile) return real to ""
        }
        if (hits.size > 1) return null to "项目文件里有多份「" + wanted + "」（" +
            hits.joinToString("、") { if (it.sub.isBlank()) it.f.name else it.sub + "/" + it.f.name } +
            "），请带子目录路径"
        return null to "文件不存在。可用写法：绝对路径、~/…、或「项目文件/子目录/文件名」" +
            "（当前项目：" + (Graph.projects.all().firstOrNull { it.id == pid }?.name ?: "全局") + "）"
    }

    /** 可以直接当纯文本读的扩展名（不能把二进制文件也按文本读——那是乱码不是内容） */
    private val TEXT_KINDS = setOf(
        "txt", "md", "markdown", "json", "csv", "tsv", "html", "htm", "xml",
        "log", "yaml", "yml", "kt", "java", "py", "js", "ts", "css"
    )

    /** 文档类扩展名（file_read_document 支持解析的格式；ppt = 老版二进制课件，2026-11-05 补；
     *  doc = 老版 Word 二进制，2026-11-09 随 POI 一起补） */
    internal val DOC_EXTS = setOf("docx", "doc", "xlsx", "pptx", "ppt", "pdf")

    /**
     * 解析一份文档为结构化文本（file_read_document 与 file_library_read 共用）。
     *
     * **失败契约（2026-11-05 用户「一次踩一个坑」整改）**：这里**永远不返回空串**——
     * 抽取失败返回"[抽取失败：原因]"，暂不支持返回"[PDF 暂不抽文字：…+替代办法]"，
     * 抽出来字数与文件体积明显不匹配返回"质量异常"前缀。理由：工具层的 `text.isBlank()`
     * 只会让模型看到一句"解析失败"，它既不知道是格式不支持还是文件坏了，也就给不出
     * 有用的替代方案（换 PDF/截图/电脑另存），用户只能干瞪眼。
     */
    internal fun parseDocText(f: File, sessionId: Long = 0L): String {
        val ext = f.extension.lowercase()
        val text = runCatching {
            // v56.4（2026-11-14 用户：「AI 读文件改成优先走云端」，针对在用的 DeepSeek 系列模型）：
            // pdf/doc/docx/ppt/pptx 先交 DeepSeek 文件解析（支持这五种格式、按页出文本，
            // 1.5 元/千页；扫描件/手写/公式排版比端侧小模型强）。不可用/失败/超预算/非 DeepSeek
            // 模型 → 返回 null，直接落到下面的端侧链，行为与上云前完全一致。
            cloudDocFirst(f, sessionId) ?: when (ext) {
                "pptx" -> io.github.m0use001.planability.ai.office.OfficeText.fromPptx(f, withIndex = true)
                "ppt" -> io.github.m0use001.planability.ai.office.OfficeText.fromPpt(f, withIndex = true) +
                    pptSlideNotes(f, sessionId)
                // 2026-11-09 主路换成 Apache POI（成熟库，按 run 拼、不 trim，不会吞空格）；
                // 自研 OfficeText 正则降级为兜底（POI 抛异常或读空时再试）。
                "docx" -> {
                    val t = io.github.m0use001.planability.ai.office.PoiDocumentReader.readWord(f)
                        ?: io.github.m0use001.planability.ai.office.OfficeText.fromDocx(f, withIndex = true)
                    // 2026-11-09：整页截图贴进 Word 的文档（讲义/范文常见）文字极少，
                    // 补一层内嵌图片 OCR；否则模型看到的是一份"几乎空白"的文档。
                    if (io.github.m0use001.planability.ai.office.PoiDocumentReader.wordLooksLikeImages(t, f)) {
                        val imgs = kotlinx.coroutines.runBlocking {
                            io.github.m0use001.planability.ai.office.PoiDocumentReader.readWordPictures(
                                Graph.appContext, f
                            ) { ctx, uri -> io.github.m0use001.planability.ai.ptl.DeepSeekCloud.recognizeEnhanced(ctx, uri, sessionId) }
                        }
                        if (!imgs.isNullOrBlank())
                            t + "\n\n[以下为文档内图片的 OCR 结果，原文均为截图]\n" + imgs
                        else t
                    } else t
                }
                // 老 Word（OLE2 二进制）：此前完全不支持，现在走 POI HWPF；
                // 字太少时补一层内嵌图片 OCR（老课件常把截图贴进 doc）
                "doc" -> {
                    val t = io.github.m0use001.planability.ai.office.PoiDocumentReader.readWord(f)
                        ?: "[.doc 解析失败：文件可能损坏或被加密]"
                    if (io.github.m0use001.planability.ai.office.PoiDocumentReader.wordLooksLikeImages(t, f)) {
                        val imgs = kotlinx.coroutines.runBlocking {
                            io.github.m0use001.planability.ai.office.PoiDocumentReader.readDocPictures(
                                Graph.appContext, f
                            ) { ctx, uri -> io.github.m0use001.planability.ai.ptl.DeepSeekCloud.recognizeEnhanced(ctx, uri, sessionId) }
                        }
                        if (!imgs.isNullOrBlank())
                            t + "\n\n[以下为文档内图片的 OCR 结果，原文均为截图]\n" + imgs
                        else t
                    } else t
                }
                "xlsx" -> io.github.m0use001.planability.ai.office.PoiDocumentReader.readSheet(f)
                    ?: parseDocumentForTool(f)
                // PDF 文字层：PDFBox 抽不到（扫描件）就逐页渲染走 OCR——OCR 是 suspend，
                // 工具执行本身在 IO 线程上，这里用 runBlocking 等它（单文件，几秒级）。
                "pdf" -> {
                    // v56.1：扫描 PDF 逐页快照持久化到导出目录（前 24 页）——OCR 是文本兜底，
                    // 快照才是版式/公式的视觉兜底（AiStream tool 分支按路径直通视觉模型）
                    val exportRoot = kotlinx.coroutines.runBlocking {
                        io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                    }
                    val snapDir = java.io.File(exportRoot, f.nameWithoutExtension + ".pages")
                    val t = io.github.m0use001.planability.ai.office.PoiDocumentReader.readPdfText(f)
                        ?: kotlinx.coroutines.runBlocking {
                            io.github.m0use001.planability.ai.office.PoiDocumentReader.readPdfByOcr(
                                Graph.appContext, f, snapDir
                            ) { ctx, uri -> io.github.m0use001.planability.ai.ptl.DeepSeekCloud.recognizeEnhanced(ctx, uri, sessionId) }
                        }
                    if (t == null)
                        "[PDF 未抽取文字：没有文字层且 OCR 也没识别出内容]\n" +
                            "替代：① 让用户把要用的那几页截图发来（file_read_image 会 OCR）；" +
                            "② 电脑上若有同一份内容的 docx/pptx/txt，优先读那份；" +
                            "③ 也可以在电脑上把 PDF 另存为文本后同步过来"
                    else {
                        val pages = runCatching {
                            snapDir.listFiles()?.filter { it.extension == "png" }?.sortedBy { it.name }.orEmpty()
                        }.getOrDefault(emptyList())
                        if (pages.isEmpty()) t
                        else "[每页快照已生成（" + pages.size + " 页）：导出到 exports/" + f.nameWithoutExtension +
                            ".pages/" + pages.first().name + " … " + pages.last().name +
                            "；要看第 N 页的版式/公式，用 file_read_image 读对应路径]\n" + t
                    }
                }
                // 纯文本类直接读；其余走文档解析器。
                // 注意不能对所有格式都 readText 兜底：二进制文件读出来是乱码，
                // 模型会把它当正文（bogus xlsx 实测会吐出读者看不懂的碎片）。
                in TEXT_KINDS -> runCatching { f.readText() }.getOrDefault("")
                else -> parseDocumentForTool(f)
            }
        }.getOrElse { "[抽取失败：" + (it.message ?: "未知原因") + "]" }
        // 兜底：**任何**岗位都不许把空串交出去（工具层只会把它翻译成无信息的"解析失败"）
        val safe = if (text.isBlank())
            "[抽取失败：没能从 ." + ext + " 里读到内容（文件 " + (f.length() / 1024) +
                " KB）——可能格式不受支持或文件损坏]"
        else text
        return qualityWarning(f, safe) + safe
    }

    /**
     * 云端优先读文件（2026-11-14 用户：「AI 读文件改成优先走云端」）。
     *
     * 只在「云 OCR = 优先 + 当前会话模型是 DeepSeek 系列 + 格式受支持」时返回文本；
     * 其余情况（开关关、兜底档、非 DeepSeek 模型、无 key、超预算、网络/解析失败）返回 null，
     * 调用方原样走端侧解析。**云端失败绝不能变成「文件读不到」**——降级链是硬要求。
     *
     * PDF 走云端后仍补一套逐页快照（本地渲染，不花钱）：版式/公式图的视觉兜底不能因为
     * 换了文字来源就消失（v56.1 的 file_read_image 直通靠这些路径）。
     */
    private fun cloudDocFirst(f: File, sessionId: Long): String? = runCatching {
        val ext = f.extension.lowercase()
        if (ext !in DeepSeekCloud.CLOUD_DOC_EXTS) return null
        val parsed = kotlinx.coroutines.runBlocking {
            DeepSeekCloud.parseDocIfEnabled(Graph.appContext, f, sessionId)
        } ?: return null
        if (ext != "pdf") return parsed.text
        // PDF：补快照目录（与端侧 OCR 路径同一套命名，模型看到的提示也一致）
        val exportRoot = kotlinx.coroutines.runBlocking {
            io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
        }
        val snapDir = java.io.File(exportRoot, f.nameWithoutExtension + ".pages")
        val pages = kotlinx.coroutines.runBlocking {
            io.github.m0use001.planability.ai.office.PoiDocumentReader
                .snapshotPdfPages(Graph.appContext, f, snapDir)
        }
        if (pages.isEmpty()) parsed.text
        else parsed.text + "\n\n[每页快照已生成（" + pages.size + " 页）：导出到 exports/" +
            f.nameWithoutExtension + ".pages/" + pages.first().name + " … " + pages.last().name +
            "；要看第 N 页的版式/公式，用 file_read_image 读对应路径]"
    }.getOrNull()

    /**
     * 抽取质量闸：文件不小、读出来的字却屈指可数 → 明确告诉模型"可能只读到一部分"。
     * 典型场景：整页图片的 PPT（公式图）、扫描件、列结构异常的表。模型据此会提示用户
     * 换方式（截图/OCR），而不是把两行字当成全文继续推理。
     */
    private fun qualityWarning(f: File, text: String): String {
        val visible = text.count { it.isLetterOrDigit() }
        val kb = f.length() / 1024
        return if (kb >= 40 && visible < 80) {
            "[抽取质量异常：文件 " + kb + " KB，只读到 " + visible +
                " 个字符——可能大部分是图片/公式图，或格式解析不全；涉及具体内容时请先向用户确认]\n"
        } else ""
    }

    /** 文件 → 可被视觉直通引用的相对路径（attachments/… 或 exports/…）；不属于两者返回 ""。 */
    private suspend fun imageRelPath(img: File, sessionId: Long): String = runCatching {
        val fd = Graph.appContext.filesDir.absolutePath + "/"
        if (img.absolutePath.startsWith(fd)) {
            img.absolutePath.removePrefix(fd).takeIf { it.startsWith("attachments/") } ?: ""
        } else {
            val expRoot = io.github.m0use001.planability.ai.office.ExportStore
                .dirFor(Graph.appContext, sessionId).absolutePath + "/"
            if (img.absolutePath.startsWith(expRoot))
                "exports/" + img.absolutePath.removePrefix(expRoot) else ""
        }
    }.getOrDefault("")

    /** file_read_document 主逻辑（execute 已逼近 64KB 上限，分支体拆出来）。 */
    private suspend fun readDocumentTool(sessionId: Long, a: JSONObject): ToolExecResult {
        // 2026-11-03：路径面从「attachments/exports」扩到项目文件/绝对路径（见 resolveUnified）
        val (f, err) = resolveUnified(a.optString("path"), sessionId)
        if (f == null) return ToolExecResult.fail(err)
        val ext = f.extension.lowercase()
        if (ext !in DOC_EXTS) return ToolExecResult.fail(
            if (ext in listOf("jpg", "jpeg", "png", "webp"))
            // 2026-11-11 撞测：模型对 .jpg 快照连错 11 次都没得到自救指引——报错里点名替代工具
                "仅支持 docx/doc/xlsx/pptx/ppt/pdf；当前 .$ext 是图片，请改用 file_read_image"
            else "仅支持 docx/doc/xlsx/pptx/ppt/pdf，当前 .$ext"
        )
        // docx/pptx 额外带编号（[P1] / [第 N 页]）：改稿要按段/按页定位，
        // 模型得能明确指认"第 3 段"，只给纯文本它只能猜位置，一改就错位
        val text = parseDocText(f, sessionId)
        if (text.isBlank()) return ToolExecResult.fail("解析 " + f.name + " 失败")
        // 2026-11-05 分页：长文档（PDF/长篇 docx）同样只能看到前 1.2 万字，没有续读入口
        val page = TextWindows.page(text, a.optInt("offset", 0),
            a.optInt("limit", TextWindows.DEFAULT_LIMIT).coerceAtLeast(12000))
        // 审计标注来源（2026-11-14：读文件可走云端解析，用户要能在工具行上看出来）
        val viaCloud = text.contains("[云端解析（DeepSeek）")
        return ToolExecResult.ok(
            page.render(f.name),
            "解析 " + f.name + (if (viaCloud) "（云端）" else "") + (if (page.hasMore) "（未完）" else "")
        )
    }

    /** file_read_image 主逻辑：OCR + 结果带相对路径（视觉直通引用用）。 */
    private suspend fun readImageTool(sessionId: Long, a: JSONObject): ToolExecResult {
        val (img, imgErr) = resolveUnified(a.optString("path"), sessionId)
        if (img == null) return ToolExecResult.fail(imgErr)
        if (img.extension.lowercase() !in listOf("jpg", "jpeg", "png", "webp"))
            return ToolExecResult.fail("不是图片文件")
        // 2026-11-14：带上 sessionId —— DeepSeek 系列模型走「优先云端」（见 recognizeEnhanced）
        val text = DeepSeekCloud.recognizeEnhanced(Graph.appContext, android.net.Uri.fromFile(img), sessionId)
        val head = imageRelPath(img, sessionId).let { if (it.isEmpty()) "" else "[$it]\n" }
        if (text.isBlank()) {
            // v56.1：OCR 空 ≠ 没价值——快照/公式页恰是 OCR 最弱的地方，这里 fail 会让结果
            // 文本没有路径 → 视觉直通（AiStream tool 分支按结果文本里的路径附图）不触发，
            // 最需要「看图」的场景反而看不到图。改回 ok 带路径头，直通把图喂给视觉模型。
            return ToolExecResult.ok(
                head + "[OCR 未识别到文字——图片已随本结果直通视觉模型，请直接看图描述内容与版式；" +
                    "确认是纯装饰图就如实告知用户]",
                "OCR " + img.name + " 0 字（图已直通）"
            )
        }
        // 2026-11-05 分页：长图切条识别能出几万字，take(6000) 会把识别成果砍掉大半
        val page = TextWindows.page(text, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
        val viaCloud = text.contains("由云端 OCR 识别")
        return ToolExecResult.ok(head + page.render(img.name),
            "OCR " + img.name + (if (viaCloud) "（云端）" else "") + (if (page.hasMore) "（未完）" else ""))
    }

    /**
     * 老版 .ppt 的**每页整体快照**（2026-11-11「AI 文件能力」夜）：渲染每页 JPEG 到
     * 并在结果里列出快照路径——模型随后可对单页调 file_read_image（视觉直通会把该图
     * 直接喂给视觉模型，见 [AiStream] 的 tool 分支）。
     *
     * 失败一律吞掉返回 ""：快照是增强不是必需品，绝不能让 file_read_document 整体失败。
     */
    private fun pptSlideNotes(f: File, sessionId: Long): String = runCatching {
        val snaps = io.github.m0use001.planability.ai.office.PptSlides.renderSnapshots(f)
        if (snaps.isEmpty()) return ""
        val dir = kotlinx.coroutines.runBlocking {
            runCatching {
                io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
            }.getOrNull()
        } ?: return ""
        val sub = java.io.File(dir, f.nameWithoutExtension + ".slides")
        sub.mkdirs()
        val rels = ArrayList<String>(snaps.size)
        val ocr = StringBuilder()
        snaps.forEachIndexed { i, jpg ->
            val file = java.io.File(sub, "%02d.jpg".format(i + 1))
            java.io.FileOutputStream(file).use { jpg.inputStream().copyTo(it) }
            val rel = "exports/" + f.nameWithoutExtension + ".slides/" + file.name
            rels.add(rel)
            if (ocr.length < 6000) {
                val t = kotlinx.coroutines.runBlocking {
                    runCatching {
                        io.github.m0use001.planability.core.OcrHelper.recognize(
                            Graph.appContext, android.net.Uri.fromFile(file)
                        )
                    }.getOrNull()
                }.orEmpty().trim()
                if (t.isNotEmpty()) ocr.append("[第 ").append(i + 1).append(" 页快照 OCR]\n")
                    .append(t.take(900)).append("\n\n")
            }
        }
        "\n\n[每页整体快照已生成（" + snaps.size + " 页）：导出到 " + rels.first() +
            " … " + rels.last() + "；要看第 N 页的版式/公式，用 file_read_image 读对应路径]\n" + ocr
    }.getOrDefault("")

    /**
     * 顺手把 [file] 转成同名 PDF 预览，返回拼进结果文案的短语（转不出来返回 `""`）。
     *
     * ## 为什么生成和改稿都要调它
     *
     * 预览页（ExportBrowserScreen）的规则是"有同名 .pdf 就翻 PDF，并标注
     * **与文件实际版式一致**"。所以：
     * - 生成时不出 PDF，用户看到的就只是自绘快照（近似版式）；
     * - 改稿后**留着旧 PDF 更糟** —— 过期版式顶着一句笃定的承诺给用户看，
     *   比干脆没有 PDF 还要误导。
     *
     * 所以转失败/没装 LibreOffice 时**把同名旧 PDF 删掉**，宁可退回自绘快照。
     * 任何失败都吞掉返回 `""`：PDF 是"更好的预览"，不是必需品，
     * 不值得为它让生成/改稿整个流程失败。
     */
    private suspend fun pdfPreviewNote(file: File, ext: String): String {
        val pdfFile = File(file.parentFile, file.nameWithoutExtension + ".pdf")
        val engine = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                if (!io.github.m0use001.planability.ai.office.PcBridge.usableFor(ext)) null
                else io.github.m0use001.planability.ai.office.PcBridge.toPdf(file, pdfFile, ext)
            }.getOrNull()
        }
        val ok0 = engine != null && pdfFile.exists() && pdfFile.length() > 0
        // 2026-11-09 夜：电脑不在线时不再"没有 PDF"，用 PDFBox + 系统字体出一份
        // 朴素版式的离线 PDF（文字齐全、版式近似）。生成失败维持现状（无 PDF）。
        var approx = false
        val ok = if (ok0) true else when (ext) {
            "docx" -> {
                val text = runCatching { io.github.m0use001.planability.ai.office.PoiDocumentReader.readWord(file) }.getOrNull()
                if (text.isNullOrBlank()) false
                else {
                    approx = io.github.m0use001.planability.ai.office.SimplePdfWriter.writeLines(
                        text.lines().take(2400), pdfFile
                    )
                    approx
                }
            }
            "xlsx" -> {
                val text = runCatching { io.github.m0use001.planability.ai.office.PoiDocumentReader.readSheet(file) }.getOrNull()
                if (text.isNullOrBlank()) false
                else {
                    approx = io.github.m0use001.planability.ai.office.SimplePdfWriter.writeLines(
                        text.lines().take(2400), pdfFile
                    )
                    approx
                }
            }
            else -> false
        }
        if (!ok) pdfFile.delete()
        return if (ok) "，并转出 PDF 预览（${pdfFile.length() / 1024} KB" +
            (if (approx) "，本机近似版式" else "，电脑端排版") + "）" else ""
    }

    /**
     * 电脑端交回来的文件**开包看一眼**：里面得有一页真实内容。
     *
     * ## 为什么值得多做这一步
     *
     * 2026-10-11 实测：桥把 pptx 入参读错了一层（手机发 `payload = {"deck": {...}}`，
     * 桥按 `payload.slides` 找），于是返回一份 **0 页的空 pptx**——而上层只要
     * "文件存在且非 0 字节"就 `renameTo(out)` 顶掉手机端兜底，结果还如实回报
     * "由电脑端引擎生成（高保真版式）"。**空演示文稿 + 一句成功播报**，是最坏的那类假成功：
     * 手机端本来能出 3 页，被一个更差的输出取代了。
     *
     * 校验按格式认一个"必须存在的部件"（zip 条目），打不开/没有就当空壳，
     * 调用方据此回退手机端并在结果里改口。**只校验电脑端的输出**，
     * 手机端自己的 writer 不走这里——自己的代码不该被自己再审一遍。
     *
     * @return true = 坏文件/空壳（丢弃它）
     */
    internal fun pcOutputLooksHollow(file: File, kind: String): Boolean {
        if (!file.isFile || file.length() == 0L) return true
        val need = when (kind) {
            "pptx" -> Regex("""ppt/slides/slide\d+\.xml""")
            "docx" -> Regex("""word/document\.xml""")
            else -> Regex("""xl/worksheets/[^/]+\.xml""")
        }
        return runCatching {
            java.util.zip.ZipFile(file).use { z ->
                z.entries().asSequence().none { e -> need.matches(e.name) }
            }
        }.getOrDefault(true) // 打不开的 zip 也是坏文件
    }

    // ---------- 分发 ----------

    suspend fun execute(sessionId: Long, name: String, a: JSONObject): ToolExecResult {
        val plan = Graph.plan
        val notes = Graph.notes
        return when (name) {
            "now" -> {
                val n = AppTime.now()
                val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")[n.dayOfWeek.value - 1]
                ToolExecResult.ok(
                    n.year.toString() + "-" + n.monthValue + "-" + n.dayOfMonth + " " + week,
                    "now"
                )
            }
            "schedule_query" -> {
                val start = parseDateTime(a.optString("start").ifBlank { "今天" }) ?: AppTime.dayKey(AppTime.today())
                val end = if (a.has("end")) (parseDateTime(a.optString("end")) ?: AppTime.dayKey(AppTime.today().plusDays(7)))
                else maxOf(start, AppTime.dayKey(AppTime.today().plusDays(7)))
                val occ = plan.scheduleOccurrences(start.toLocalDate(), end.toLocalDate())
                if (occ.isEmpty()) ToolExecResult.ok("该范围内没有日程", "无日程")
                else {
                    val lines = occ.take(30).map { o ->
                        "「" + o.schedule.title + "」 " + fmtDay(o.start) + " " + AppTime.millisToTime(o.start) + "-" +
                            AppTime.millisToTime(o.end) +
                            (if (o.schedule.place.isNotBlank()) " 地点:" + o.schedule.place else "") +
                            // 2026-10-31：重复规则说出来，否则模型看不出「每周一、三」（改规则/回答频率都要用）
                            (if (o.schedule.repeatRule.isNotBlank())
                                " [" + io.github.m0use001.planability.data.Recurrence
                                    .parseScheduleRule(o.schedule.repeatRule)
                                    .describeCompact(o.start.toLocalDate()) + "]"
                            else "") +
                            "（编号 " + o.schedule.id + "）"
                    }.toMutableList()
                    // 截断必须说出来：只给 30 行、detail 又写"共 N 条"，模型会以为这就是全部，
                    // 于是把"周内有 40 节课"答成 30 节。用户 2026-10-20 反复对账课次，这里不能糊。
                    if (occ.size > 30) {
                        lines += "…还有 " + (occ.size - 30) + " 条未列出（缩小 start/end 范围再查，或按需分次查）"
                    }
                    ToolExecResult.ok(lines.joinToString("\n"), occ.size.toString() + " 条日程")
                }
            }
            // schedule_create / schedule_update / schedule_delete_today / todo_create 已挪到
            // PlanTools（2026-10-31 接入重复/标签参数后 execute 第三次撞 JVM 64KB 单方法上限）
            "todo_query" -> {
                val status = a.optString("status", "active")
                if (status == "done") {
                    val logs = plan.doneLogs(AppTime.today().minusDays(7), AppTime.today())
                    if (logs.isEmpty()) ToolExecResult.ok("最近 7 天没有完成记录", "无完成记录")
                    else ToolExecResult.ok(
                        logs.take(30).joinToString("\n") { l -> "「" + l.title + "」（编号 " + l.todoId + "）" },
                        logs.size.toString() + " 条已完成"
                    )
                } else {
                    val dayFilter = a.optString("day").takeIf { it.isNotBlank() }?.let { parseDateTime(it)?.let { m -> AppTime.dayKeyOf(m) } }
                    // 2026-11-03：点名某天时改走 todosOfDay（与首页同口径）——
                    // 原来按 date 精确匹配，过期未勾的重复待办（欠账）模型看不到，
                    // 会答出「今天没有待办」（与「漏勾即消失」同一批洞）。
                    val todos = if (dayFilter != null) plan.todosOfDay(dayFilter)
                        else plan.activeTodos().sortedBy { it.date }
                    if (todos.isEmpty()) ToolExecResult.ok("没有未完成待办", "无待办")
                    else ToolExecResult.ok(
                        todos.take(30).joinToString("\n") { t ->
                            "「" + t.title + "」 " + (if (t.date > 0) fmtDay(t.date) else "无日期") +
                                (if (t.priority == 0) " [高]" else "") +
                                // 2026-10-31：重复/监督/标签说出来，否则模型改不了重复、看不见监督待办
                                PlanTools.todoRepeatText(t) +
                                (if (t.supervisionEnabled) " [监督" + (if (t.dataRecordEnabled) "+数据记录" else "") + "]" else "") +
                                (if (t.labels.isNotBlank()) " [标签:" + t.labels + "]" else "") +
                                "（编号 " + t.id + "）"
                        },
                        todos.size.toString() + " 条待办"
                    )
                }
            }
            "todo_complete" -> {
                val id = a.optLong("id", -1)
                val t = plan.todoById(id) ?: return ToolExecResult.fail("待办 id=" + id + " 不存在")
                plan.completeTodo(t, AppTime.today())
                val undoId = id
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "取消完成「" + t.title + "」") {
                        plan.todoById(undoId)?.let { cur -> plan.completeTodo(cur, AppTime.today()) }
                    }
                )
                ToolExecutor.registerUndoPersisted(sessionId, "取消完成「" + t.title + "」", "todo_complete", id.toString())
                ToolExecResult.ok("已完成待办「" + t.title + "」", "完成「" + t.title + "」")
            }
            "note_search" -> {
                val q = a.optString("query").trim()
                if (q.isEmpty()) return ToolExecResult.fail("关键词为空")
                val tag = a.optString("tag").trim()
                var hits = io.github.m0use001.planability.ai.rag.NoteSearch.search(q, 10, tag)
                val notebookName = a.optString("notebook").trim()
                if (notebookName.isNotBlank()) {
                    val nb = Graph.notes.notebooks().firstOrNull { it.name == notebookName }
                        ?: return ToolExecResult.fail("没有叫「" + notebookName + "」的笔记本（用 notebook_list 查）")
                    hits = hits.filter { it.note.notebookId == nb.id }
                }
                if (hits.isEmpty()) ToolExecResult.ok(
                    "没有匹配的笔记（title/正文全文检索均未命中；换更短的关键词或去掉标签条件再试）", "无命中"
                )
                else ToolExecResult.ok(
                    buildString {
                        append("命中 ").append(hits.size).append(" 条（编号可用于 note_read / note_link）：\n")
                        hits.forEach { h ->
                            append("《").append(h.note.title).append("》（编号 ").append(h.note.id)
                            append("；命中 ").append(h.matchedBy)
                            if (h.note.tags.isNotBlank()) append("；标签 ").append(h.note.tags)
                            append("）：").append(h.snippet).append("\n")
                        }
                    },
                    hits.size.toString() + " 条笔记命中"
                )
            }
            "note_read" -> {
                val n = notes.noteById(a.optLong("id", -1)) ?: return ToolExecResult.fail("笔记不存在")
                // 2026-11-05 分页：笔记是用户长文的主要容器（小说稿就在笔记里），take(6000) 无续读
                val page = TextWindows.page(n.content, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                ToolExecResult.ok("《" + n.title + "》\n" + page.render(n.title), "读取《" + n.title + "》" + (if (page.hasMore) "（未完）" else ""))
            }
            "note_create" -> {
                val title = a.optString("title").trim().ifBlank { "新笔记" }
                val content = a.optString("content")
                // 2026-10-31：可指定笔记本（按名）；缺省仍是默认笔记本
                val notebookId = a.optString("notebook").trim().takeIf { it.isNotBlank() }?.let { name ->
                    Graph.notes.notebooks().firstOrNull { it.name == name }?.id
                        ?: return ToolExecResult.fail("没有叫「" + name + "」的笔记本（用 notebook_list 查，或 notebook_create 建一个）")
                } ?: Graph.db.notebookDao().all().firstOrNull()?.id
                    ?: notes.createNotebook("默认笔记本", COVER_SOLID, 0f)  // 素色默认款：编号 -1，不是新风格 0
                val nid = notes.createNote(notebookId, title, "md")
                notes.saveNote(
                    NoteEntity(
                        id = nid, notebookId = notebookId, title = title, content = content,
                        createdAt = AppTime.nowMillis(), updatedAt = AppTime.nowMillis()
                    )
                )
                val undoId = nid
                val undoTitle = title
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "删除笔记《" + title + "》") { notes.trashNote(undoId) }
                )
                ToolExecutor.registerUndoPersisted(sessionId, "删除笔记《" + title + "》", "note_create", nid.toString())
                ToolExecResult.ok("已创建笔记 id=" + nid + "《" + undoTitle + "》", "已记笔记《" + title + "》")
            }
            "note_append" -> {
                val id = a.optLong("id", -1)
                val n = notes.noteById(id) ?: return ToolExecResult.fail("笔记不存在")
                val text = a.optString("text")
                notes.saveNote(n.copy(content = n.content + "\n\n" + text, updatedAt = AppTime.nowMillis()))
                val undoOrig = n
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "还原笔记《" + n.title + "》") { notes.saveNote(undoOrig) }
                )
                ToolExecResult.ok("已向《" + n.title + "》追加 " + text.length + " 字", "已补充《" + n.title + "》")
            }
            "file_list" -> {
                val dir = a.optString("dir", "both")
                suspend fun listDir(rel: String): List<String> {
                    // exports 已搬到外部目录（见 ExportStore），这里跟着走，否则 AI 列出来是空目录。
                    // 2026-10-28 用户查「新对话__282 哪来的」：原来这里用 dirFor(create=true)——
                    // AI 只是想**列**一下文件，却给会话在项目真实文件夹里建了空目录，
                    // 会话删掉后就成了孤儿（Python编程/新对话__282 就是这么来的）。
                    // 改 existingDirFor（create=false）：没有目录就返回空，绝不落地。
                    val d = if (rel == "exports") io.github.m0use001.planability.ai.office.ExportStore.existingDirFor(Graph.appContext, sessionId)
                    else File(Graph.appContext.filesDir, rel)
                    if (d == null || !d.exists()) return emptyList()
                    return d.listFiles()?.sortedByDescending { it.lastModified() }
                        ?.take(20)?.map { f -> rel + "/" + f.name + " | " + f.length() / 1024 + "KB" }
                        ?: emptyList()
                }
                val out = mutableListOf<String>()
                if (dir == "both" || dir == "attachments") out += listDir("attachments")
                if (dir == "both" || dir == "exports") out += listDir("exports")
                if (out.isEmpty()) ToolExecResult.ok("应用内暂无附件与 AI 生成的文件", "无文件")
                else ToolExecResult.ok(out.joinToString("\n"), out.size.toString() + " 个文件")
            }
            "file_read_text" -> {
                // 2026-11-09 收敛：原来只认 attachments/exports，项目文件/文件库里的 .md 读不到——
                // 模型只能改道 file_library_read，一条路读得到另一条读不到的分叉就是在这埋的。
                val (f, rerr) = resolveUnified(a.optString("path"), sessionId)
                if (f == null) return ToolExecResult.fail(rerr)
                val kinds = io.github.m0use001.planability.core.FileKinds
                // 二进制文档不能当文本读：读出来是 ZIP/PDF 原始字节，既烧上下文又答不了问题。
                // 用户 2026-10-20 的现场（docx 预览成一屏 PK 乱码）在模型侧是同一个入口问题：
                // 这里必须把模型推回 file_read_document，而不是塞给它 8000 字乱码。
                if (kinds.isBinary(f.name)) {
                    val ext = kinds.extOf(f.name)
                    if (ext in setOf("docx", "xlsx", "pptx")) {
                        return ToolExecResult.fail(".$ext 是二进制办公文档，请用 file_read_document 读取（file_read_text 只读纯文本）")
                    }
                    return ToolExecResult.fail(".$ext 是二进制文件，不能按文本读取")
                }
                val text = runCatching { f.readText() }
                    .getOrElse { return ToolExecResult.fail("读取失败：" + (it.message ?: "未知错误")) }
                if (text.isBlank()) ToolExecResult.ok("（文件是空的）", "读取 " + f.name)
                else {
                    // 2026-11-05 分页（真机会话现场：58 万字文件只能看到前 8000 字，没有续读入口）
                    val page = TextWindows.page(text, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                    ToolExecResult.ok(page.render(f.name), "读取 " + f.name + (if (page.hasMore) "（未完）" else ""))
                }
            }
            "file_export" -> {
                val rawName = a.optString("filename").trim()
                val content = a.optString("content")
                if (rawName.isEmpty() || content.isEmpty()) return ToolExecResult.fail("文件名或内容为空")
                if (rawName.contains('/') || rawName.contains('\\') || rawName.contains("..")) {
                    return ToolExecResult.fail("文件名不能包含路径分隔符")
                }
                // 纯文本工具不能冒充办公文档/二进制：写个 .docx 扩展名 + 文本内容，
                // 产出的文件 Word 打不开，用户看到的就是"AI 给我的文档坏了"。
                // 其余扩展名一律按纯文本写（.md/.htm/.json/.csv/.yaml/… 都支持）。
                if (io.github.m0use001.planability.core.FileKinds.isBinary(rawName)) {
                    val ext = io.github.m0use001.planability.core.FileKinds.extOf(rawName)
                    return ToolExecResult.fail(
                        "file_export 只写纯文本，不能生成 .$ext。请改用 " +
                            (if (ext == "pptx") "file_build_deck" else "file_export_document")
                    )
                }
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val f = File(dir, rawName)
                f.writeText(content)
                ToolExecutor.registerUndoPersisted(
                    sessionId, "删除文件 " + rawName, "file_export", "exports/" + rawName
                )
                ToolExecResult.ok(
                    "已导出 exports/" + rawName + "（" + content.length + " 字），可在文件页查看或分享",
                    "已导出 " + rawName
                )
            }
            "file_read_document" -> readDocumentTool(sessionId, a)
            "file_read_image" -> readImageTool(sessionId, a)
            // ---------- v43：真实文件面（fs_*） ----------
            // 实现拆在 FsShellTools：execute 的 when 已逼近 JVM 单方法 64KB 上限
            //（加完这 8 个工具后编译报 Method too large，必须分文件）
            "fs_list", "fs_read_text", "fs_search", "fs_write_text", "fs_mkdir", "fs_move",
            "fs_delete" -> {
                FsShellTools.run(name, a) ?: ToolExecResult.fail("未知工具：" + name)
            }
            // ---------- v43：浏览器自动化（应用内 WebView） ----------
            "browser_open", "browser_click", "browser_fill", "browser_extract", "browser_back", "browser_close" -> {
                BrowserTools.run(name, a) ?: ToolExecResult.fail("未知工具：" + name)
            }
            "digest_query" -> {
                val day = a.optString("day").takeIf { it.isNotBlank() }?.let { parseDateTime(it) } ?: AppTime.dayKey(AppTime.today())
                val list = Graph.info.digestsOf(AppTime.dayKeyOf(day))
                if (list.isEmpty()) ToolExecResult.ok("该日没有提纯记录", "无提纯记录")
                else {
                    // v55：总数 + offset 分页——信息提取一天可产生很多条，原来静默 take(6)
                    val offset = a.optInt("offset", 0).coerceIn(0, list.size)
                    val page = list.drop(offset).take(4)
                    ToolExecResult.ok(
                        "共 " + list.size + " 条提纯，本页第 " + (offset + 1) + "-" + (offset + page.size) + " 条：\n" +
                            page.joinToString("\n\n") { d -> "【" + d.sourceGroup + "】\n" + d.digest.take(300) } +
                            (if (offset + page.size < list.size) "\n看其余提纯：offset=" + (offset + page.size) else ""),
                        list.size.toString() + " 条提纯"
                    )
                }
            }
            "pending_query" -> {
                // v54：可过滤；v55：加 offset 分页 + month 月过滤——真机中枢积压 1360 条，
                // 旧实现只能看最新 50 条，9/10 月那批永远翻不到（AI 直接回「我翻不到」=锁死）。
                // 排序显式化（createdAt DESC, id DESC）：DB 查询虽已 ORDER BY，但内存过滤后
                // 必须保证分页切片稳定，否则翻页会重复/漏条。
                val kind = a.optString("kind", "").trim().lowercase()
                val source = a.optString("source", "").trim().lowercase()
                val monthRaw = a.optString("month", "").trim()
                val offset = a.optInt("offset", 0).coerceIn(0, 100000)
                val limit = a.optInt("limit", 20).coerceIn(1, 50)
                val month = if (monthRaw.isEmpty()) null else PendingFilter.normMonth(monthRaw)
                if (monthRaw.isNotEmpty() && month == null) {
                    return ToolExecResult.fail("month 看不懂：" + monthRaw + "（用 10 或 2026-10）")
                }
                // 2026-11-22：has_time 三态 + 事件日期区间（before_date/after_date）——用户
                // 「has_time=false 传了还被无视、返回全部」「pending_purge 只有 month 太粗」。
                val hasTime = PendingFilter.normTriState(a.optString("has_time", ""))
                val today = AppTime.today()
                val beforeRaw = a.optString("before_date", "").trim()
                val beforeDate = if (beforeRaw.isEmpty()) null else PendingFilter.parseDay(beforeRaw, today)
                if (beforeRaw.isNotEmpty() && beforeDate == null) {
                    return ToolExecResult.fail("before_date 看不懂：$beforeRaw（用 2026-11-15 或 今天）")
                }
                val afterRaw = a.optString("after_date", "").trim()
                val afterDate = if (afterRaw.isEmpty()) null else PendingFilter.parseDay(afterRaw, today)
                if (afterRaw.isNotEmpty() && afterDate == null) {
                    return ToolExecResult.fail("after_date 看不懂：$afterRaw（用 2026-11-15 或 今天）")
                }
                var list = Graph.info.pendingItems()
                    .sortedWith(
                        compareByDescending<io.github.m0use001.planability.data.PendingItem> { it.createdAt }
                            .thenByDescending { it.id }
                    )
                if (kind in listOf("todo", "schedule")) list = list.filter { it.kind == kind }
                if (source in listOf("session", "capture", "manual", "automation")) list = list.filter { it.source == source }
                if (month != null) list = list.filter { PendingFilter.timeTextInMonth(it.timeText, month) }
                if (hasTime == true) list = list.filter { it.timeText.isNotBlank() }
                if (hasTime == false) list = list.filter { it.timeText.isBlank() }
                if (beforeDate != null || afterDate != null) {
                    list = list.filter { p ->
                        val yr = runCatching {
                            java.time.Instant.ofEpochMilli(p.createdAt)
                                .atZone(java.time.ZoneId.systemDefault()).year
                        }.getOrDefault(today.year)
                        val d = PendingFilter.firstDateInText(p.timeText, yr) ?: return@filter false
                        (beforeDate == null || d.isBefore(beforeDate)) &&
                            (afterDate == null || d.isAfter(afterDate))
                    }
                }
                val cond = kind.isNotBlank() || source.isNotBlank() || month != null ||
                    hasTime != null || beforeDate != null || afterDate != null
                if (list.isEmpty()) ToolExecResult.ok("没有待确认条目" + (if (cond) "（按当前过滤条件）" else ""), "无待处理")
                else {
                    val page = list.drop(offset).take(limit)
                    if (page.isEmpty()) {
                        return ToolExecResult.ok(
                            "共 " + list.size + " 条待确认，offset=" + offset + " 已越过末尾（本页为空）。" +
                                "翻页请让 offset < 总数。",
                            "翻页越过末尾"
                        )
                    }
                    val head = "共 " + list.size + " 条待确认" +
                        (if (cond) "（已按条件过滤" + (if (month != null) "，month=" + month + "月" else "") + "）" else "") +
                        "，本页第 " + (offset + 1) + "-" + (offset + page.size) + " 条："
                    ToolExecResult.ok(
                        head + "\n" + page.joinToString("\n") { p ->
                            val srcCn = when (p.source) { "session" -> "会话"; "manual" -> "手动"; "automation" -> "自动化"; else -> "提取" }
                            "#" + p.id + " " + (if (p.kind == "schedule") "[日程·" else "[待办·") + srcCn + "]「" +
                                p.title + "」 " + p.timeText +
                                (if (p.suggestedStart > 0) " 建议" + AppTime.millisToTime(p.suggestedStart) else "")
                        } +
                            (if (offset + page.size < list.size) "\n继续翻页：offset=" + (offset + page.size) else ""),
                        list.size.toString() + " 条待确认"
                    )
                }
            }
            "memory_search" -> {
                // 两段式：
                // ① 名字精确命中 → 直接给完整正文（模型想"翻出那条记忆"时，语义检索只会
                //    把它排进候选，正文仍然拿不到；这是"AI 到底记不记得我"最直接的卡点）；
                // ② 否则走语义召回 top5，每条给「名字 + 类型 + 描述 + 正文节选」，
                //    而不是原来只回一句 description —— 那样模型知道"有这么回事"却看不见内容。
                // v57（2026-11-21 串味审计）：两条路都按**会话项目**限定可见范围
                // （本项目 + 全局(0) + 常驻索引；别的项目的条目翻不到也搜不出来——
                // 旧版 recall(q,5) 不传 scope，小说会话能搜出线代的「设定卡迭代」）。
                val q = a.optString("query").trim()
                val sessionProject = Graph.chat.session(sessionId)?.projectId ?: 0L
                val exact = if (q.isBlank()) null else runCatching {
                    Graph.db.memoryDao().byNameVisible(q, sessionProject)
                }.getOrNull()
                if (exact != null && !exact.isDeleted) {
                    // 2026-11-12：精确命中也要核对有效性——recall 语义路已过滤墓碑，但按名
                    // 直查这条口子原来漏着，会把已作废条目的正文原样端给模型（无标注地当现行事实）
                    val voided = exact.supersededBy != 0L ||
                        (exact.invalidAt != 0L && exact.invalidAt <= AppTime.nowMillis())
                    val head = if (voided) "【已作废】此条已失效（可能已被新设定取代），不要当作现行事实：\n" else ""
                    ToolExecResult.ok(
                        head + "【" + exact.name + "】(" + exact.type + ")\n" +
                            exact.description.ifBlank { "" } + "\n" + exact.body.take(1200),
                        (if (voided) "按名称命中（已作废）：" else "按名称命中：") + exact.name
                    )
                } else {
                    val hits = Graph.memories.recall(q, 5, sessionProject)
                    if (hits.isEmpty()) ToolExecResult.ok("没有相关记忆", "无命中")
                    else ToolExecResult.ok(
                        hits.joinToString("\n\n") { m ->
                            "【" + m.name + "】(" + m.type + ")\n" +
                                m.description.ifBlank { m.body.take(60) } + "\n" + m.body.take(400)
                        },
                        hits.size.toString() + " 条记忆命中（已带正文节选）"
                    )
                }
            }
            "memory_add" -> {
                val body = a.optString("body").trim()
                val memName = a.optString("name").trim().ifBlank { "memory-" + (System.currentTimeMillis() % 100000) }
                if (body.isEmpty()) return ToolExecResult.fail("记忆正文为空")
                val memType = a.optString("type", "project").takeIf { it in listOf("project", "feedback", "reference", "skill") } ?: "project"
                // 项目会话里加的记忆归该项目（0=全局）；originSession 不记就查不到是哪次会话写的
                val sessionProject = Graph.chat.session(sessionId)?.projectId ?: 0L
                // P2（2026-11-10 审计）：同名重放原来会插两行（name 无唯一约束），undo 还只删
                // firstOrNull 留下一条。改成 upsert-by-name：已有同名记忆就原地更新（复用 id），
                // undo 还原旧正文（原来没有才软删）。
                // v57（2026-11-21）：同名判定收窄为**同项目**——别的项目（含全局）的同名行
                // 不复用 id、不覆盖，另起一行（唯一索引已放宽为 (name, projectId)）。
                val existing = runCatching {
                    Graph.db.memoryDao().byNameInProject(memName.take(40), sessionProject)
                }.getOrNull()
                val memId = Graph.memories.upsert(
                    MemoryEntity(
                        id = existing?.id ?: 0,
                        name = memName.take(40), type = memType,
                        description = body.take(60), body = body, inIndex = false, importance = 2,
                        originSession = sessionId, projectId = sessionProject
                    )
                )
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, if (existing != null) "还原记忆「" + memName + "」" else "删除记忆「" + memName + "」") {
                        if (existing != null) Graph.memories.upsert(existing)
                        else Graph.memories.softDelete(memId)
                    }
                )
                if (existing != null) {
                    ToolExecutor.registerUndoPersisted(
                        sessionId, "还原记忆「" + memName + "」", "memory_update",
                        org.json.JSONObject()
                            .put("id", memId).put("name", existing.name).put("type", existing.type)
                            .put("description", existing.description).put("body", existing.body.take(2000))
                            .put("projectId", existing.projectId).put("importance", existing.importance)
                            .toString()
                    )
                } else {
                    ToolExecutor.registerUndoPersisted(sessionId, "删除记忆「" + memName + "」", "memory_add", memId.toString())
                }
                ToolExecResult.ok(
                    (if (existing != null) "已更新同名记忆：" else "已记住：") + body.take(80) + "（内部名称 " + memName + "）",
                    if (existing != null) "已更新一段记忆" else "已记住一段记忆"
                )
            }
            "memory_forget" -> {
                val key = a.optString("name").trim()
                val m = visibleMemory(key, sessionId)
                    ?: return ToolExecResult.fail("记忆不存在（或不在本会话可见范围：别的项目的条目删不了）")
                Graph.memories.softDelete(m.id)
                val undoMem = m
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "恢复记忆「" + m.name + "」") { Graph.memories.upsert(undoMem) }
                )
                ToolExecutor.registerUndoPersisted(sessionId, "恢复记忆「" + m.name + "」", "memory_forget", m.id.toString())
                ToolExecResult.ok("已忘记一条记忆（内部名称 " + m.name + "，内容：" + m.description.ifBlank { m.body.take(40) } + "）", "已忘记一段记忆")
            }
            "memory_invalidate" -> runMemoryInvalidate(sessionId, a)
            "undo_last_write" -> {
                val undone = ToolExecutor.undoLast(sessionId)
                if (undone == null) ToolExecResult.fail("没有可撤销的写操作")
                else ToolExecResult.ok("已撤销：" + undone, "已撤销：" + undone.take(30))
            }
            "deep_plan" -> {
                val task = a.optString("task").trim()
                if (task.isEmpty()) return ToolExecResult.fail("任务描述为空")
                val result = io.github.m0use001.planability.ai.ptl.DeepPlanner.run(sessionId, task)
                ToolExecResult.ok(result.take(6000), "深度规划完成（" + result.length + " 字）")
            }
            "ask_expert" -> {
                val key = a.optString("agent").trim()
                val q = a.optString("question").trim()
                if (q.isEmpty()) return ToolExecResult.fail("问题为空")
                val ag = Graph.assistants.byKey(key)
                    ?: return ToolExecResult.fail(
                        "没有名为 $key 的智能体（可用：" +
                            Graph.assistants.all().joinToString("/") { it.key } + "）"
                    )
                // v19.9：注册运行态（取人格 → 调用模型 → 回收答案），运行面板可看到跑到第几步
                val runId = io.github.m0use001.planability.ai.ptl.SubAgentRuns.start(
                    sessionId, "ask_expert", "智能体 · " + ag.name + " · " + q.take(20),
                    listOf("读取人格提示词", "调用模型生成", "回收并整理解答")
                )
                io.github.m0use001.planability.ai.ptl.SubAgentRuns.step(runId, 0, "done")
                val answer = runCatching {
                    io.github.m0use001.planability.ai.AiClient.chatWith(
                        io.github.m0use001.planability.ai.AiClient.config,
                        ag.systemPrompt,
                        q,
                        ag.temperature
                    )
                }.getOrElse {
                    io.github.m0use001.planability.ai.ptl.SubAgentRuns.finish(runId, "failed")
                    return ToolExecResult.fail("智能体调用失败：" + (it.message ?: "网络错误"))
                }
                io.github.m0use001.planability.ai.ptl.SubAgentRuns.step(runId, 1, "done")
                io.github.m0use001.planability.ai.ptl.SubAgentRuns.step(runId, 2, "done")
                io.github.m0use001.planability.ai.ptl.SubAgentRuns.finish(runId, "done")
                // 2026-11-05 分页：take(2500) 是**静默截断**——detail 不报总长，模型会把半截
                // 智能体回答当全文整合（委托 writer 写长文时必踩）。改 TextWindows 带元信息。
                val page = TextWindows.page(answer, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                ToolExecResult.ok(
                    "【智能体 " + ag.name + " 的回答】\n" + page.render(ag.name + " 回答"),
                    "委托 " + ag.name + (if (page.hasMore) "（未完）" else "")
                )
            }
            "file_export_document" -> {
                val filename = a.optString("filename").trim()
                val content = a.optString("content")
                if (filename.isEmpty() || content.isEmpty()) return ToolExecResult.fail("文件名或内容为空")
                if (filename.contains('/') || filename.contains('\\') || filename.contains(".."))
                    return ToolExecResult.fail("文件名不合法")
                val ext = filename.substringAfterLast('.', "").lowercase()
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, filename)
                // P3：docx/xlsx 电脑可用时走电脑端（真样式、合并单元格、列宽自适应），
                // 否则手机端出朴素版。结果里**说明用的哪条路**——不说明的话用户没法判断。
                var pcEngine: String? = null
                if (ext == "docx" || ext == "xlsx") {
                    val pcOut = File(dir, ".pc_$filename")
                    val ok = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        if (!io.github.m0use001.planability.ai.office.PcBridge.usableFor(ext)) return@withContext null
                        if (ext == "docx") {
                            io.github.m0use001.planability.ai.office.PcBridge.renderDocx(
                                content.lines(), filename.substringBeforeLast('.'), pcOut
                            )
                        } else {
                            val rows = content.lines()
                                .filter { it.isNotBlank() }
                                .map { it.split('\t').map { c -> c.trim() } }
                            io.github.m0use001.planability.ai.office.PcBridge.renderXlsx(
                                rows, filename.substringBeforeLast('.'), pcOut
                            )
                        }
                    }
                    if (ok != null && pcOut.exists() && pcOut.length() > 0 &&
                        !pcOutputLooksHollow(pcOut, ext)
                    ) {
                        pcOut.renameTo(out)
                        pcEngine = ok
                    } else {
                        // 空壳（0 页/0 段）不能顶掉手机端兜底，也不能报"电脑端引擎"
                        pcOut.delete()
                    }
                }
                if (!out.exists()) {
                    when (ext) {
                        "csv" -> out.writeText(content)
                        "docx" -> OfficeWriter.writeDocx(out, content)
                        "xlsx" -> OfficeWriter.writeXlsx(out, content)
                        // pptx 走独立工具。这里必须明确拦下来：模型很爱拿本工具"顶着"做 pptx，
                        // 而本工具的内容格式是"每行一段"，用它写出的 .pptx 是个打不开的假货——
                        // 用户拿到文件才发现不对，比当场报错糟糕得多
                        "pptx" -> return ToolExecResult.fail(
                            "本工具只生成 .docx / .xlsx / .csv 与纯文本，不做幻灯片。" +
                                "要生成 PPT 请改用 file_build_deck：filename 以 .pptx 结尾，" +
                                "slides 传结构化 JSON 数组（layout/title/blocks）。"
                        )
                        // 2026-10-20（用户要求「其他文件也要支持生成，包括但不限于 .md、.htm」）：
                        // 纯文本扩展名一律照写，不再报"不支持的格式"——AI 选错工具不该让用户拿不到文件。
                        else -> {
                            if (io.github.m0use001.planability.core.FileKinds.isText(filename)) {
                                out.writeText(content)
                            } else {
                                return ToolExecResult.fail(
                                    "不支持的格式：.$ext。" +
                                        "可生成 .docx / .xlsx / .csv，以及任意纯文本扩展名" +
                                        "（.md / .txt / .htm / .html / .json / .xml / .yaml / .ics / .svg …）"
                                )
                            }
                        }
                    }
                }
                if (!out.exists() || out.length() == 0L) return ToolExecResult.fail("导出失败")
                // 2026-11-09 写后回读闸：文件非空 ≠ 内容对。生成后用同一个解析器读回来，
                // 读不到字/内容大量丢失 = 假成功（写器 bug、电脑桥空壳、R8 裁类都踩过）。
                // 宁可当场报失败并删掉空壳，也不让用户拿走一个打不开的文件。
                if (ext == "docx" || ext == "xlsx") {
                    val why = io.github.m0use001.planability.ai.office.PoiDocumentReader.verifyWritten(
                        out, content.count { it.isLetterOrDigit() }
                    )
                    if (why != null) {
                        out.delete()
                        return ToolExecResult.fail(
                            "生成失败：$filename 写入后回读校验没通过（$why）。已删除空壳文件，" +
                                "请重试；若反复失败改用纯文本导出（.md/.txt）"
                        )
                    }
                }
                ToolExecutor.registerUndoPersisted(
                    sessionId, "删除文件 " + filename, "file_export", "exports/" + filename
                )
                // 顺手存一份 PDF 预览：导出文件页看到同名 .pdf 就翻 PDF（真·所见即所得），
                // 没有就退回自绘快照。为什么、以及失败怎么办，都写在 pdfPreviewNote 的注释里。
                // csv 刻意不转：它没有版式可言，转出来只是把文本再包一层，预览页也不认。
                val pdfNote = if (ext == "docx" || ext == "xlsx") pdfPreviewNote(out, ext) else ""
                val via = when {
                    pcEngine != null -> "，由电脑端引擎生成（$pcEngine）"
                    ext == "docx" || ext == "xlsx" -> "，由手机端生成（朴素版式）"
                    else -> ""
                }
                ToolExecResult.ok(
                    "已导出 exports/$filename（${out.length() / 1024} KB$via），" +
                        "可在「文件库」里查看或分享$pdfNote",
                    "已导出 $filename"
                )
            }
            "file_build_deck" -> {
                val filename = a.optString("filename").trim()
                if (filename.isEmpty()) return ToolExecResult.fail("文件名不能为空")
                if (filename.contains('/') || filename.contains('\\') || filename.contains(".."))
                    return ToolExecResult.fail("文件名不能带路径")
                if (!filename.endsWith(".pptx", true))
                    return ToolExecResult.fail("演示文稿文件必须以 .pptx 结尾")
                val raw = a.optString("slides")
                if (raw.isBlank()) return ToolExecResult.fail("slides 不能为空")
                val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
                    ?: return ToolExecResult.fail(
                        "slides 必须是 JSON 数组字符串。" +
                            "正确形如[{\"title\":\"封面\",\"layout\":\"title\"}," +
                            "{\"title\":\"第一部分\",\"blocks\":[{\"type\":\"bullets\",\"items\":[\"要点\"]}]}]"
                    )
                val deck = io.github.m0use001.planability.ai.office.DocModel.deckFromJson(
                    a.optString("title").ifBlank { filename.removeSuffix(".pptx") },
                    io.github.m0use001.planability.ai.office.DocModel.themeFromJson(
                        a.optJSONObject("theme") ?: runCatching { org.json.JSONObject(a.optString("theme")) }.getOrNull()
                    ),
                    arr
                )
                if (deck.slides.isEmpty())
                    return ToolExecResult.fail("slides 解析后没有任何页面，请检查 JSON 结构")
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, filename)

                // P3 混合路线：电脑开着且支持 pptx 就交给它出高保真版，否则手机端出简版。
                // 结果里**必须说明用的是哪条路**——不说明的话用户没法判断该不该去电脑上改。
                val pcOut = File(dir, ".pc_$filename")
                var pcEngine: String? = if (io.github.m0use001.planability.ai.office.PcBridge.usableFor("pptx")) {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.m0use001.planability.ai.office.PcBridge.render("pptx", deck, pcOut)
                    }
                } else null
                // 电脑端交回的可能是**空壳**（0 页），见 pcOutputLooksHollow：
                // 那种文件不能顶掉下面的手机端 PptxWriter 兜底，回报里也不能再说"电脑端高保真"。
                if (pcOut.exists() && pcOut.length() > 0 && !pcOutputLooksHollow(pcOut, "pptx")) {
                    pcOut.renameTo(out)
                } else {
                    pcOut.delete()
                    pcEngine = null
                }

                var report = PptxWriter.Report(0, 0, emptyList())
                if (!out.exists()) {
                    report = runCatching {
                        PptxWriter.write(out, deck)
                    }.getOrElse {
                        return ToolExecResult.fail("生成失败：" + (it.message ?: it.javaClass.simpleName))
                    }
                }
                if (!out.exists() || out.length() == 0L) return ToolExecResult.fail("写入失败")
                ToolExecutor.registerUndoPersisted(
                    sessionId, "删除生成的文件 " + filename, "file_export", "exports/" + filename
                )

                // 同步渲染每页快照（P1）：手机上没有能真渲染 pptx 的引擎，但排版信息我们自己
                // 就有，画一遍 PNG 就够翻页看了。放 IO 线程，别在调用方的调度器上卡着。
                // 快照失败**不影响 pptx 本身**——它是附属品，不能因为画不出来就让整个生成失败
                val shots = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        val sd = File(
                            dir,
                            io.github.m0use001.planability.ai.office.SlideRenderer.snapshotDirName(filename)
                        )
                        io.github.m0use001.planability.ai.office.SlideRenderer.renderAllToDir(deck, sd)
                    }
                }.getOrDefault(0)

                val warn = if (report.picturesSkipped.isEmpty()) "" else
                    "\n注意：" + report.picturesSkipped.size + " 张图没读到已跳过（" +
                        report.picturesSkipped.joinToString("、") { it.take(40) } + "）"
                val shotNote = if (shots > 0) "，并渲染了 $shots 页预览图" else ""
                val via = if (pcEngine != null)
                    "由电脑端引擎生成（$pcEngine，高保真版式）"
                else
                    "由手机端生成（简版式：标题/要点/表格/图片，母版与图表不支持）"

                // 顺手存一份 PDF 预览：导出文件页看到同名 .pdf 就翻 PDF（真·所见即所得），
                // 否则退回自绘快照。为什么、失败怎么办，见 pdfPreviewNote 的注释。
                val pdfNote = pdfPreviewNote(out, "pptx")
                ToolExecResult.ok(
                    "已生成 exports/$filename（${deck.slides.size} 页、${out.length() / 1024} KB" +
                        (if (report.pictures > 0) "、内嵌 ${report.pictures} 张图" else "") + "），$via。" +
                        "在「信息 → 文件库」里可以翻页预览$shotNote$pdfNote$warn",
                    "生成 $filename"
                )
            }
            "file_edit_deck" -> {
                val rel = a.optString("path").trim()
                val src = resolveInnerFile(rel, sessionId) ?: return ToolExecResult.fail(
                    "路径不合法或文件不存在：$rel（导出目录下的文件请写 exports/xxx.pptx）"
                )
                if (!src.name.endsWith(".pptx", true))
                    return ToolExecResult.fail("本工具只改 .pptx。改 Word/Excel 请用别的方式重做内容")
                val raw = a.optString("slides")
                if (raw.isBlank()) return ToolExecResult.fail("slides 不能为空")
                val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
                    ?: return ToolExecResult.fail("slides 必须是 JSON 数组，每项形如 {\"pageNo\":1,\"title\":\"...\"}")

                val theme = io.github.m0use001.planability.ai.office.DocModel.Theme()
                val deck = runCatching {
                    io.github.m0use001.planability.ai.office.PptxEditor.read(src)
                }.getOrElse {
                    // 读不懂就不改：整份重建不是编辑，那会抹掉用户的母版、动画、图表，
                    // 而工具还报"改好了"——这是最坏的一类假成功
                    return ToolExecResult.fail(
                        "读不懂这个 pptx 的结构，已放弃修改（没有动原文件）。" +
                            "原因：" + (it.message ?: it.javaClass.simpleName) + "。" +
                            "可以请用户先用 PowerPoint/WPS 另存为「空白演示文稿」再导进来重做内容。"
                    )
                }
                val byPage = HashMap<Int, org.json.JSONObject>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val p = o.optInt("pageNo", 0)
                    if (p <= 0) return ToolExecResult.fail("每项都必须带 pageNo（从 1 开始），第 $i 项没有")
                    byPage[p] = o
                }
                if (byPage.isEmpty()) return ToolExecResult.fail("没有解析出任何要改的页")

                val patches = byPage.map { (pageNo, o) ->
                    val s = io.github.m0use001.planability.ai.office.DocModel.slideFromJson(o)
                    s.copy(pageNo = pageNo)
                }
                val outName = a.optString("out").trim()
                    .ifEmpty { src.nameWithoutExtension + "_改.pptx" }
                if (!outName.endsWith(".pptx", true))
                    return ToolExecResult.fail("输出文件名必须以 .pptx 结尾")
                if (outName.contains('/') || outName.contains('\\') || outName.contains(".."))
                    return ToolExecResult.fail("输出文件名不能带路径")
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, outName)

                val res = runCatching {
                    io.github.m0use001.planability.ai.office.PptxEditor.apply(src, patches, out, deck)
                }.getOrElse {
                    return ToolExecResult.fail("改写失败：" + (it.message ?: it.javaClass.simpleName))
                }
                if (!out.exists() || out.length() == 0L) return ToolExecResult.fail("写入失败")

                // 快照跟着重画：改了内容却留着旧预览图，预览就成了误导
                val shots = runCatching {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        io.github.m0use001.planability.ai.office.SlideRenderer.renderAllToDir(
                            deck.copy(slides = patches.associate { it.pageNo to it }.let { m ->
                                deck.slides.map { m[it.pageNo] ?: it }
                            }),
                            File(dir, io.github.m0use001.planability.ai.office.SlideRenderer.snapshotDirName(outName))
                        )
                    }
                }.getOrDefault(0)

                ToolExecutor.registerUndoPersisted(
                    sessionId, "删除改稿结果 " + outName, "file_export", "exports/" + outName
                )
                // 改稿结果也要有真·预览：出不了 PDF 时顺手删掉同名旧 PDF，
                // 否则预览页会拿过期版式顶着"与文件实际版式一致"给用户看（见 pdfPreviewNote）
                val pdfNote = pdfPreviewNote(out, "pptx")
                val warn = buildString {
                    if (res.failed.isNotEmpty()) {
                        append("\n有 " + res.failed.size + " 页没改成：" + res.failed.joinToString("；"))
                    }
                    if (res.droppedAnimations > 0) {
                        append("\n注意：被改的页里有 " + res.droppedAnimations +
                            " 页带动画/切换效果，重写会丢失这部分（其余页不受影响）")
                    }
                }
                ToolExecResult.ok(
                    "已改 exports/$outName：替换了第 " + byPage.keys.sorted().joinToString("、") +
                        " 页，其余 " + (deck.slides.size - res.changed) + " 页原样保留$warn$pdfNote",
                    "改了 $outName 的 ${res.changed} 页"
                )
            }
            "file_edit_document" -> {
                val rel = a.optString("path").trim()
                val src = resolveInnerFile(rel, sessionId) ?: return ToolExecResult.fail(
                    "路径不合法或文件不存在：$rel（导出目录下的文件请写 exports/xxx.docx）"
                )
                if (!src.name.endsWith(".docx", true))
                    return ToolExecResult.fail("本工具只改 .docx。要改幻灯片用 file_edit_deck，生成新文档用 file_export_document")
                val raw = a.optString("edits")
                if (raw.isBlank()) return ToolExecResult.fail("edits 不能为空")
                val arr = runCatching { org.json.JSONArray(raw) }.getOrNull()
                    ?: return ToolExecResult.fail(
                        "edits 必须是 JSON 数组，形如 [{\"index\":2,\"text\":\"新文字\"}]"
                    )
                val edits = ArrayList<io.github.m0use001.planability.ai.office.DocxEditor.Edit>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    // 对外说"第 N 段"（1 基），内部用 0 基，别让人算错一位
                    val n = o.optInt("index", 0)
                    if (n <= 0) return ToolExecResult.fail("index 从 1 开始，第 $i 项的 index 无效")
                    edits.add(
                        io.github.m0use001.planability.ai.office.DocxEditor.Edit(
                            index = n - 1,
                            text = o.optString("text")
                        )
                    )
                }
                if (edits.isEmpty()) return ToolExecResult.fail("没有解析出任何要改的段落")

                val total = runCatching {
                    io.github.m0use001.planability.ai.office.DocxEditor.read(src).size
                }.getOrElse {
                    return ToolExecResult.fail(
                        "读不懂这个 docx：${it.message ?: it.javaClass.simpleName}。原文件未改动"
                    )
                }
                val outName = a.optString("out").trim().ifEmpty { src.nameWithoutExtension + "_改.docx" }
                if (!outName.endsWith(".docx", true)) return ToolExecResult.fail("输出文件名必须以 .docx 结尾")
                if (outName.contains('/') || outName.contains('\\') || outName.contains(".."))
                    return ToolExecResult.fail("输出文件名不能带路径")
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, outName)
                val res = runCatching {
                    io.github.m0use001.planability.ai.office.DocxEditor.apply(src, edits, out)
                }.getOrElse {
                    return ToolExecResult.fail("改写失败：" + (it.message ?: it.javaClass.simpleName))
                }
                if (!out.exists() || out.length() == 0L) return ToolExecResult.fail("写入失败")
                ToolExecutor.registerUndoPersisted(
                    sessionId, "删除改稿结果 " + outName, "file_export", "exports/" + outName
                )
                // 改稿结果也要真·预览（出不了 PDF 就删同名旧 PDF，别让过期版式冒充真预览）
                val pdfNote = pdfPreviewNote(out, "docx")
                val warn = if (res.failed.isEmpty()) "" else
                    "\n有 " + res.failed.size + " 段没改成：" + res.failed.joinToString("；")
                ToolExecResult.ok(
                    "已改 exports/$outName：改了 " + res.changed + " 段（共 $total 段），" +
                        "其余段落与排版原样保留$warn$pdfNote",
                    "改了 $outName 的 ${res.changed} 段"
                )
            }
            "file_edit_sheet" -> {
                val rel = a.optString("path").trim()
                val src = resolveInnerFile(rel, sessionId) ?: return ToolExecResult.fail(
                    "路径不合法或文件不存在：$rel（导出目录下的文件请写 exports/xxx.xlsx）"
                )
                if (!src.name.endsWith(".xlsx", true))
                    return ToolExecResult.fail(
                        "本工具只改 .xlsx。改 Word 用 file_edit_document，改 PPT 用 file_edit_deck"
                    )
                val opsRaw = a.optString("ops")
                if (opsRaw.isBlank()) return ToolExecResult.fail("ops 不能为空")
                val ops = runCatching { org.json.JSONArray(opsRaw) }.getOrNull()
                    ?: return ToolExecResult.fail(
                        "ops 必须是 JSON 数组，形如 [{\"op\":\"set\",\"cell\":\"B3\",\"value\":13000}]"
                    )
                if (ops.length() == 0) return ToolExecResult.fail("没有解析出任何操作")
                val outName = a.optString("out").trim().ifEmpty { src.nameWithoutExtension + "_改.xlsx" }
                if (!outName.endsWith(".xlsx", true)) return ToolExecResult.fail("输出文件名必须以 .xlsx 结尾")
                if (outName.contains('/') || outName.contains('\\') || outName.contains(".."))
                    return ToolExecResult.fail("输出文件名不能带路径")
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, outName)

                // 手机端优先（2026-10-20 用户要求）：set/addRow/delRow 都只是改 XML，
                // 不需要电脑。电脑端只在手机端解析失败时兜底（异常格式/加密表等）。
                val local = runCatching {
                    io.github.m0use001.planability.ai.office.XlsxEditor
                        .apply(src, org.json.JSONArray(opsRaw), out)
                }.getOrNull()
                if (local != null && out.exists() && out.length() > 0) {
                    ToolExecutor.registerUndoPersisted(
                        sessionId, "删除改稿结果 " + outName, "file_export", "exports/" + outName
                    )
                    val pdfNote = pdfPreviewNote(out, "xlsx")
                    val warn = if (local.failed.isEmpty()) ""
                    else "\n有 " + local.failed.size + " 项没做成：" + local.failed.joinToString("；")
                    return ToolExecResult.ok(
                        "已改 exports/$outName（手机端直接改「${local.sheet}」：${local.applied.size} 项）。" +
                            "其余单元格、样式与合并原样保留。实际做了：" + local.applied.joinToString("；") +
                            warn + pdfNote,
                        "改了 $outName 的 ${local.applied.size} 项"
                    )
                }

                val res = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    io.github.m0use001.planability.ai.office.PcBridge.patchXlsx(src, opsRaw, out)
                }
                when (res) {
                    is io.github.m0use001.planability.ai.office.PcBridge.Patch.Ok -> {
                        ToolExecutor.registerUndoPersisted(
                            sessionId, "删除改稿结果 " + outName, "file_export", "exports/" + outName
                        )
                        val did = if (res.applied.isBlank()) "${ops.length()} 项"
                        else res.applied.split(';').size.toString() + " 项"
                        // 改稿结果也要真·预览（出不了 PDF 就删同名旧 PDF，别让过期版式冒充真预览）
                        val pdfNote = pdfPreviewNote(out, "xlsx")
                        ToolExecResult.ok(
                            "已改 exports/$outName（由电脑端 ${res.engine} 执行：$did）。" +
                                "其余单元格与格式原样保留。实际做了：" + res.applied + pdfNote,
                            "改了 $outName"
                        )
                    }
                    is io.github.m0use001.planability.ai.office.PcBridge.Patch.Unsupported ->
                        ToolExecResult.fail("电脑端不支持这个：${res.reason}")
                    is io.github.m0use001.planability.ai.office.PcBridge.Patch.Failed ->
                        ToolExecResult.fail(
                            "改表格失败：${res.reason}。" +
                                "xlsx 改稿需要电脑端在运行（desktop 里 npm run office），" +
                                "并在「信息 → 文件库」页把「电脑端渲染」打开。原文件未改动。"
                        )
                }
            }
            "web_fetch" -> {
                val url = a.optString("url").trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    return ToolExecResult.fail("[FETCH_INVALID_URL] 只支持 http/https 链接；请换用 web_search 找到可访问的地址")
                }
                // 2026-11-08：外链安全前置——模型给的 URL 不可信，禁止本机/私网/保留地址
                if (!io.github.m0use001.planability.core.SafeUrl.isAllowed(url)) {
                    return ToolExecResult.fail(
                        "[FETCH_BLOCKED] 该地址指向本机或私有网络（localhost/127.x/10.x/192.168.x/169.254 等），" +
                            "不允许抓取。要读公开资料请给公网 http/https 地址。"
                    )
                }
                var text = OfficeWriter.fetchText(url)
                var viaRender = false
                var viaCloud = false
                // 2026-11-08：静态 HTML 拿不到正文时**先走无头渲染**，不再直接弹可见浏览器。
                // wiki / 知乎 / 示例社区这类前端渲染站，静态抓取只有空壳；以前只能 browser_open，
                // 于是每抓一页都弹一个窗口（用户实报）。无头渲染在本进程内离屏跑完就销毁，
                // 用户看不到任何东西；Cookie 与可见浏览器共用，所以"登录过"的站也不需要再弹。
                if (text.isNullOrBlank()) {
                    val rendered = io.github.m0use001.planability.browser.HeadlessRender.fetchText(url)
                    if (!rendered.isNullOrBlank()) {
                        text = rendered
                        viaRender = true
                    }
                }
                if (text == null || text.isBlank()) {
                    // 2026-11-14 云抓取兜底（DeepSeek Fetch API，开关 cloudFetchEnabled）：
                    // 静态抓+无头渲染都空手时才走，失败回下方原始失败路径。
                    val cloud = runCatching { DeepSeekCloud.fetchIfEnabled(url) }.getOrNull()
                    if (cloud != null) {
                        text = cloud.second
                        viaRender = false
                        viaCloud = true
                    }
                }
                if (text == null) {
                    // v19.10：失败原因结构化——区分网络不可达 / 被拒（403/404）/ 站点不支持，
                    // 并明确给出替代路径，避免模型只看到一句「抓取失败」就重复重试同一个地址
                    return ToolExecResult.fail(
                        "[FETCH_FAILED] 无法抓取 " + url + "（网络不可达、被风控拦截或站点不支持抓取）。" +
                            "换用策略：① 改用 web_search 找该主题的其它可访问来源；② 换该站点的公开 API；" +
                            "③ 若用户给了原文，可直接让我基于原文工作。不要重复抓取同一地址。"
                    )
                }
                if (text.isBlank()) {
                    return ToolExecResult.fail(
                        "[FETCH_EMPTY] 静态抓取与无头渲染都没取到正文（多半是登录墙/验证码，或站点拒绝应用内浏览器）。" +
                            "换用策略：① 先试其它公开来源（多数内容站有转载）；② 确实只有这一页且需要登录时，" +
                            "用 browser_open 打开它并**明确请用户在这个窗口里登录**，登录后 browser_extract 取正文，" +
                            "取完 browser_close 关窗。"
                    )
                }
                // 2026-11-05 分页：网页正文动辄几万字，take(6000) 无续读
                val page = TextWindows.page(text, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                ToolExecResult.ok(
                    page.render(url),
                    "抓取 " + text.length + " 字" +
                        (if (viaCloud) "（云端）" else if (viaRender) "（无头渲染）" else "") +
                        (if (page.hasMore) "（未完）" else "")
                )
            }
            "web_search" -> {
                val q = a.optString("query").trim()
                if (q.isEmpty()) return ToolExecResult.fail("搜索词为空")
                val n = a.optInt("count", 5).coerceIn(1, 8)
                val outcome = try {
                    WebSearch.searchWithSource(q, n)
                } catch (e: Exception) {
                    return ToolExecResult.fail("搜索失败：" + (e.message ?: "网络错误"))
                }
                val hits = outcome.hits
                if (hits.isEmpty()) return ToolExecResult.fail("搜索无结果（网络受限或被风控，可稍后重试）")
                // 质量持续门（2026-11-05 用户：别拿垃圾结果硬答）：兜底链走完仍与查询
                // 零相关/退化 → 结果前挂显式话术，模型必须告知用户"搜索源今天不行"，
                // 而不是把曼恩卡车当背单词攻略继续推理。
                val degraded = !WebSearch.isGoodEnough(hits, q)
                val banner = if (degraded)
                    "[搜索质量警告：结果与查询相关性差——搜索源今天可能不稳（已自动换源重试仍未改善）。" +
                        "以下内容仅供参考；涉及事实的结论请明确告知用户「搜索结果不可靠」，不要据此下确定性结论]\n\n"
                else ""
                ToolExecResult.ok(
                    banner + hits.mapIndexed { i, h ->
                        (i + 1).toString() + ". " + h.title + "\n" + h.url + "\n" + h.snippet
                    }.joinToString("\n\n"),
                    "搜索 " + hits.size + " 条" +
                        (when (outcome.source) {
                            "tavily" -> "（Tavily）"
                            "deepseek" -> "（云）"
                            else -> "（端侧）"
                        }) +
                        (if (degraded) "（质量差）" else "")
                )
            }
            "calendar_export_ics" -> {
                val id = a.optLong("id", 0L)
                if (id <= 0) return ToolExecResult.fail("缺少日程 id")
                val s = Graph.db.scheduleDao().byId(id) ?: return ToolExecResult.fail("日程不存在（id=$id）")
                val dir = io.github.m0use001.planability.ai.office.ExportStore.dirFor(Graph.appContext, sessionId)
                val out = File(dir, "schedule_" + s.id + ".ics")
                out.writeText(OfficeWriter.buildIcs(s.title, s.start, s.end, s.place, s.note))
                ToolExecResult.ok(
                    "已导出 exports/schedule_" + s.id + ".ics（" + s.title + "），可在文件页分享给日历应用",
                    "已导出 ics"
                )
            }
            "library_search" -> {
                val q = a.optString("query").trim()
                if (q.isEmpty()) return ToolExecResult.fail("检索词为空")
                val hits = io.github.m0use001.planability.ai.rag.RagIndex.search(q, 5)
                if (hits.isEmpty()) ToolExecResult.ok(
                    "全库没有相关内容（分块检索 BM25 零命中；可换更短关键词，或用 note_search 查标题）", "无命中"
                )
                else ToolExecResult.ok(
                    buildString {
                        append("命中 ").append(hits.size).append(" 段；noteId 可用于 note_read 读全文：\n\n")
                        hits.forEach { h ->
                            // v49：出处带上「片段 N/M」——同一篇笔记多块命中时模型能判断是不是同一处，
                            // 也方便回答里标引用（用户要求可溯源）。
                            val total = runCatching {
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                    Graph.db.ragDao().chunkCount(h.noteId)
                                }
                            }.getOrDefault(0)
                            append("noteId=").append(h.noteId).append(" 《").append(h.title).append("》")
                            append("[片段").append(h.chunkIndex + 1)
                            if (total > 0) append("/").append(total)
                            append("]\n").append(h.snippet).append("\n\n")
                        }
                    },
                    hits.size.toString() + " 条全库命中"
                )
            }
            "usage_report" -> {
                val msgs = Graph.chat.messagesOf(sessionId)
                val tin = msgs.sumOf { m -> m.tokensIn }
                val tout = msgs.sumOf { m -> m.tokensOut }
                val audits = Graph.db.toolAuditDao().ofSession(sessionId)
                ToolExecResult.ok(
                    "本会话：输入 " + tin + " / 输出 " + tout + " tokens，工具调用 " + audits.size + " 次（" +
                        audits.count { r -> r.outcome == "ok" } + " 成功）",
                    "tokens " + tin + "/" + tout
                )
            }
            "data_history" -> dataHistory(a, sessionId)
            "sleep_query" -> {
                val days = a.optInt("days", 7).coerceIn(1, 90)
                val records = Graph.sleep.last(days)
                if (records.isEmpty()) return ToolExecResult.fail("还没有睡眠记录（首页/信息获取可采集）")
                val avg = records.sumOf { it.durationMin } / records.size
                val out = StringBuilder()
                out.appendLine("最近 " + records.size + " 天睡眠：平均 " + avg / 60 + " 小时 " + avg % 60 + " 分钟")
                records.forEach { r ->
                    out.appendLine(
                        "  " + fmtDayKey(AppTime.dayKey(java.time.LocalDate.ofEpochDay(r.dateEpochDay))) +
                            "：" + r.durationText +
                            "，入睡 " + fmtHM(r.fallMin) + "，醒来 " + fmtHM(r.wakeMin) +
                            if (r.durationMin < 420) "（不足7小时）" else ""
                    )
                }
                ToolExecResult.ok(out.toString().trim(), "睡眠 " + records.size + " 天")
            }
            "automation_list" -> {
                val all = io.github.m0use001.planability.data.AutomationRepo.all()
                if (all.isEmpty()) ToolExecResult.ok("还没有自动化任务", "无自动化")
                else ToolExecResult.ok(
                    all.joinToString("\n") { a ->
                        // v19.42：事件触发要排在最前面判。原来 interval/daily 都不匹配的事件型任务
                        // 会显示成「未配置触发」——而它其实是**配好了**的，模型据此会建议用户去补触发，
                        // 于是用户配的事件被当成没配。这比显示难看严重得多：它会让 AI 给出错误建议。
                        val trigger = when {
                            a.hasEventTrigger -> io.github.m0use001.planability.notify.AutomationSignal
                                .describe(a.triggerKind, a.triggerArg)
                            a.intervalMinutes > 0 -> "每 " + a.intervalMinutes + " 分钟"
                            a.dailyHour >= 0 -> "每日 %02d:%02d".format(a.dailyHour, a.dailyMinute)
                            a.hasGeoTrigger -> "到/离某地"
                            else -> "手动（快捷指令）"
                        }
                        "id=" + a.id + " " + a.name + " [" + trigger + " " + (if (a.enabled) "启用" else "停用") +
                            "] " + (if (a.kind == "flow") "流水线 " + a.prompt.take(30) else "指令:" + a.prompt.take(40))
                    },
                    all.size.toString() + " 个自动化"
                )
            }
            "automation_create" -> {
                val name = a.optString("name").trim()
                val prompt = a.optString("prompt").trim()
                // v19.41：给了 steps 就是**不经过 AI 的流水线**。这是「有些任务不做成自动指挥 AI」
                // 的落地口——AI 在这里只负责把用户的意图**编排成步骤**，跑的时候一步模型都不用。
                val rawSteps = a.optString("steps").trim()
                val isFlow = rawSteps.isNotEmpty() && rawSteps != "[]"
                if (name.isEmpty()) return ToolExecResult.fail("名称不能为空")
                if (!isFlow && prompt.isEmpty()) {
                    return ToolExecResult.fail(
                        "指令不能为空；想造一条**不经过 AI** 的快捷指令就传 steps（动作名先用 automation_actions 查）"
                    )
                }
                var stepsJson = ""
                var stepCount = 0
                // v19.42：事件触发（充电/解锁/开机/打开应用）。看不懂当场报错并列出可用值——
                // 事件字面量总共五个，猜错的代价是「一条永远不会触发的空壳」，而它**不会报运行错误**。
                val eventRaw = a.optString("event").trim()
                val event = if (eventRaw.isEmpty()) null else
                    (parseEventTrigger(eventRaw) ?: return ToolExecResult.fail(eventErr(eventRaw)))
                if (isFlow) {
                    val parsed = io.github.m0use001.planability.core.AutomationStep.decodeList(rawSteps)
                    // decodeList 把解析失败退化成空列表——这里必须当成错误回，
                    // 否则会存下一条 stepsJson=[] 的空壳，还显示成「已创建」。
                    if (parsed.isEmpty()) {
                        return ToolExecResult.fail(
                            "steps 解析不出来，要的是 JSON 数组：" +
                                "[{\"type\":\"app_open\",\"params\":{\"app\":\"设置\"}}]"
                        )
                    }
                    // 动作名拼错在这里就被挡住：存进去的坏流水线要等到真跑的时候才炸，
                    // 而那时可能已经过去一天、用户早忘了当初建过什么。
                    val unknown = parsed.map { it.type }.distinct()
                        .filter { io.github.m0use001.planability.core.ActionRegistry.byKey(it) == null }
                    if (unknown.isNotEmpty()) {
                        return ToolExecResult.fail(
                            "没有这些动作：$unknown。用 automation_actions 看现有哪些"
                        )
                    }
                    stepsJson = io.github.m0use001.planability.core.AutomationStep.encodeList(parsed)
                    stepCount = parsed.size
                }
                val interval = if (a.has("intervalMinutes") && !a.isNull("intervalMinutes")) a.optInt("intervalMinutes", 0) else 0
                val dHour = if (a.has("dailyHour") && !a.isNull("dailyHour")) a.optInt("dailyHour", -1) else -1
                val dMinute = if (dHour in 0..23) (if (a.has("dailyMinute") && !a.isNull("dailyMinute")) a.optInt("dailyMinute", 0) else 0) else -1
                // P1-5（2026-11-10 审计静态实锤）：原来 dailyHour 直收 25/24 → hasTrigger 判定
                // dailyHour>=0 通过 → 存下一条**永不触发的空壳**且无任何报错。落库前必须挡。
                if (a.has("dailyHour") && !a.isNull("dailyHour") && dHour != -1 && dHour !in 0..23) {
                    return ToolExecResult.fail(
                        "dailyHour 要在 0-23（收到 $dHour）。用户说「24:00」通常指午夜 0 点，用 dailyHour=0；" +
                            "「每天 24 点」这种写法会存成永不触发的任务，不能落库。"
                    )
                }
                if (a.has("dailyMinute") && !a.isNull("dailyMinute") && dMinute != -1 && dMinute !in 0..59) {
                    return ToolExecResult.fail("dailyMinute 要在 0-59（收到 " + a.optInt("dailyMinute", -1) + "）")
                }
                val draft = io.github.m0use001.planability.data.Automation(
                    name = name,
                    // 流水线的 prompt 是占位：flow 分支根本不读它。仍然写一句可读的话，
                    // 因为列表卡片与 automation_list 都会显示它，空着会让人以为建坏了。
                    prompt = if (isFlow) "[流水线] $stepCount 步，不经过 AI" else prompt,
                    assistantKey = a.optString("assistantKey", "general").ifBlank { "general" },
                    intervalMinutes = if (event != null) 0 else interval,
                    dailyHour = if (event != null) -1 else dHour,
                    dailyMinute = if (event != null) -1 else dMinute,
                    kind = if (isFlow) "flow" else "ai",
                    stepsJson = stepsJson,
                    triggerKind = event?.first ?: "",
                    triggerArg = event?.second ?: ""
                )
                // v19.40：manual = 快捷指令（无触发）。原来一律拒绝无触发的草稿，
                // 那在"任务必须自己跑"的前提下是对的；现在有一类东西**本来就该手动跑**，
                // 于是判据变成"要么有触发、要么明确说了是 manual"——
                // 兜底仍是拒绝，避免又一次出现"存下来却永远不执行"的静默空壳。
                val manual = a.optBoolean("manual", false)
                if (!draft.hasTrigger && !manual) {
                    return ToolExecResult.fail(
                        "需要配置触发：intervalMinutes 或 dailyHour/dailyMinute；" +
                            "若是想要「随时手动点一下就跑」的快捷指令，传 manual=true"
                    )
                }
                val id = io.github.m0use001.planability.data.AutomationRepo.save(draft)
                // v18.1：AI 创建的自动化锚定到当前对话——此后每次执行的 AI 轮次都追加在这个会话里
                runCatching { Graph.settings.kvPut("auto.session:$id", sessionId.toString()) }
                val undoId = id
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "删除自动化「" + name + "」") {
                        io.github.m0use001.planability.data.AutomationRepo.delete(undoId)
                        runCatching { Graph.settings.kvDelete("auto.session:$undoId") }
                    }
                )
                ToolExecResult.ok("已创建自动化「" + name + "」（编号 " + id + "）", "已建自动化「" + name + "」")
            }
            "automation_edit" -> {
                val id = a.optLong("id", -1)
                val old = io.github.m0use001.planability.data.AutomationRepo.all().firstOrNull { it.id == id }
                    ?: return ToolExecResult.fail("自动化 id=" + id + " 不存在")
                var ns = old
                a.optString("name").takeIf { it.isNotBlank() }?.let { ns = ns.copy(name = it) }
                a.optString("prompt").takeIf { it.isNotBlank() }?.let { ns = ns.copy(prompt = it) }
                a.optString("assistantKey").takeIf { it.isNotBlank() }?.let { ns = ns.copy(assistantKey = it) }
                if (a.has("intervalMinutes") && !a.isNull("intervalMinutes")) ns = ns.copy(intervalMinutes = a.optInt("intervalMinutes", 0))
                if (a.has("dailyHour") && !a.isNull("dailyHour")) ns = ns.copy(dailyHour = a.optInt("dailyHour", -1))
                if (a.has("dailyMinute") && !a.isNull("dailyMinute")) ns = ns.copy(dailyMinute = a.optInt("dailyMinute", 0))
                // P1-5（2026-11-10 审计）：编辑路径同样能造「永不触发的空壳」，落库前统一校验。
                if (a.has("intervalMinutes") && !a.isNull("intervalMinutes") && ns.intervalMinutes < 0) {
                    return ToolExecResult.fail("intervalMinutes 不能是负数（收到 " + ns.intervalMinutes + "）")
                }
                if (a.has("dailyHour") && !a.isNull("dailyHour") && ns.dailyHour != -1 && ns.dailyHour !in 0..23) {
                    return ToolExecResult.fail(
                        "dailyHour 要在 0-23（收到 " + ns.dailyHour + "）。「24:00」请写 dailyHour=0。"
                    )
                }
                if (a.has("dailyMinute") && !a.isNull("dailyMinute") && ns.dailyMinute != -1 && ns.dailyMinute !in 0..59) {
                    return ToolExecResult.fail("dailyMinute 要在 0-59（收到 " + ns.dailyMinute + "）")
                }
                if (a.has("enabled") && !a.isNull("enabled")) ns = ns.copy(enabled = a.optBoolean("enabled", true))
                // v19.42：事件触发。给 event 就改成按事件触发并**清掉定时字段**——
                // 两者同时留着的话，「插上电」和「每 60 分钟」会一起响，
                // 而用户以为自己只是改了触发方式。
                val eventRaw = a.optString("event").trim()
                if (eventRaw.isNotEmpty()) {
                    if (eventRaw == "none") {
                        ns = ns.copy(triggerKind = "", triggerArg = "")
                    } else {
                        val e = parseEventTrigger(eventRaw) ?: return ToolExecResult.fail(eventErr(eventRaw))
                        ns = ns.copy(
                            triggerKind = e.first, triggerArg = e.second,
                            intervalMinutes = 0, dailyHour = -1, dailyMinute = -1
                        )
                    }
                }
                io.github.m0use001.planability.data.AutomationRepo.save(ns)
                ToolExecResult.ok("已修改自动化「" + ns.name + "」（编号 " + id + "）", "已改自动化「" + ns.name + "」")
            }
            "automation_delete" -> {
                val id = a.optLong("id", -1)
                val old = io.github.m0use001.planability.data.AutomationRepo.all().firstOrNull { it.id == id }
                    ?: return ToolExecResult.fail("自动化 id=" + id + " 不存在")
                io.github.m0use001.planability.data.AutomationRepo.delete(id)
                runCatching { Graph.settings.kvDelete("auto.session:$id") }
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "恢复自动化「" + old.name + "」") { io.github.m0use001.planability.data.AutomationRepo.save(old) }
                )
                ToolExecResult.ok("已删除自动化「" + old.name + "」", "已删自动化「" + old.name + "」")
            }
            // v35：执行日志（只读）。PC 端早有 automation_log，手机端一直缺——
            // 失败只进 logcat，模型和用户都问不出"到点跑了没、跑成什么样"。
            // v19.41：让模型**看得见**能编排的动作。这一条是「AI 指挥做一条快捷指令」的地基——
            // 动作名和参数名只能从这里取；不告诉模型，它就会照着记忆编出 file_mov、move_file 这种。
            "automation_pin" -> {
                val name = a.optString("name").trim()
                if (name.isEmpty()) return ToolExecResult.fail("缺少指令名")
                val all = io.github.m0use001.planability.data.AutomationRepo.all()
                val target = all.firstOrNull { it.name.trim() == name }
                    ?: all.firstOrNull { it.name.contains(name) }
                    ?: return ToolExecResult.fail(
                        if (all.isEmpty()) "还没有任何指令" else "没有叫「$name」的指令，现有：" +
                            all.take(8).joinToString("、") { it.name }
                    )
                val ok = io.github.m0use001.planability.app.AutomationShortcuts.pin(Graph.appContext, target)
                if (!ok) {
                    // 失败分两种：启动器不支持 / 用户关了「允许添加快捷方式」。都不是故障，
                    // 所以给一句「还能怎么做」而不是干巴巴的失败
                    ToolExecResult.ok(
                        "这台手机/启动器没让钉桌面（用户可能关了「允许添加快捷方式」）。" +
                            "仍然可以用：长按 App 图标能直接跑它，桌面小组件里也有一列",
                        "未能钉到桌面"
                    )
                } else {
                    ToolExecResult.ok("已把「${target.name}」钉到桌面", "已钉到桌面")
                }
            }

            "automation_actions" -> {
                val q = a.optString("q").trim()
                val defs = io.github.m0use001.planability.core.ActionRegistry.defs.filter {
                    q.isEmpty() || it.key.contains(q, true) ||
                        it.label.contains(q, true) || it.desc.contains(q, true)
                }
                if (defs.isEmpty()) {
                    return ToolExecResult.fail(
                        if (q.isEmpty()) "动作表是空的（ActionRegistry.defs 没东西）"
                        else "没有匹配「$q」的动作（去掉 q 看全部）"
                    )
                }
                val sb = StringBuilder()
                defs.forEachIndexed { i, d ->
                    if (i > 0) sb.append('\n')
                    // 标出依赖无障碍的：模型据此提醒用户先开权限，
                    // 否则用户建完直接跑，第一步就失败，结论会是「这功能坏了」。
                    if (d.key.startsWith("a11y_")) sb.append("[需无障碍已开启] ")
                    sb.append(d.key).append(' ').append(d.label).append(" —— ").append(d.desc)
                    d.params.forEach { p ->
                        sb.append("\n   · ").append(p.key).append('(').append(p.label).append(')')
                        if (p.hint.isNotBlank()) sb.append("：").append(p.hint)
                    }
                }
                ToolExecResult.ok(
                    defs.size.toString() + " 个动作（全部**不经过 AI**，确定性的）：\n" + sb,
                    "动作清单（" + defs.size + "）"
                )
            }

            "automation_run" -> {
                val name = a.optString("name").trim()
                if (name.isEmpty()) return ToolExecResult.fail("缺少指令名")
                val all = io.github.m0use001.planability.data.AutomationRepo.all()
                // 精确优先、包含兜底——顺序反了的话「备份」会抢走「备份到云端」
                val target = all.firstOrNull { it.name.trim() == name }
                    ?: all.firstOrNull { it.name.contains(name) }
                    ?: return ToolExecResult.fail(
                        if (all.isEmpty()) "还没有任何自动化或快捷指令（可以先用 automation_create 建一条）"
                        else "没有叫「$name」的指令，现有：" + all.take(8).joinToString("、") { it.name }
                    )
                io.github.m0use001.planability.notify.AutomationSweep.execute(Graph.appContext, target)
                // execute 是 suspend，跑完才返回；读刚落的那条记录才是真结果。
                // 直接回「已运行」的话，失败也会被报成成功——这正是这个工具唯一要回答的问题。
                val rec = io.github.m0use001.planability.data.AutomationRepo.runsOf(target.id, 1).firstOrNull()
                if (rec == null) return ToolExecResult.ok("已触发「${target.name}」", "已触发")
                if (!rec.ok) return ToolExecResult.fail("「${target.name}」跑失败：" + rec.summary)
                ToolExecResult.ok("「${target.name}」跑完：" + rec.summary, "运行成功")
            }

            "automation_log" -> {
                val id = if (a.has("id") && !a.isNull("id")) a.optLong("id", -1) else -1L
                val limit = a.optInt("limit", 20).coerceIn(1, 50)
                val rows = if (id > 0L) {
                    io.github.m0use001.planability.data.AutomationRepo.runsOf(id, limit)
                } else {
                    io.github.m0use001.planability.data.AutomationRepo.recentRuns(limit)
                }
                if (rows.isEmpty()) {
                    ToolExecResult.ok(
                        if (id > 0L) "自动化 id=$id 还没有执行记录（要等它到点跑过一次）"
                        else "还没有任何自动化执行记录（任务跑过一次就会出现）",
                        "无执行记录"
                    )
                } else {
                    val fmt = java.time.format.DateTimeFormatter
                        .ofPattern("MM-dd HH:mm")
                        .withZone(io.github.m0use001.planability.core.AppTime.zone)
                    ToolExecResult.ok(
                        rows.joinToString("\n") { r ->
                            val head = (if (r.ok) "✓ " else "✗ ") +
                                fmt.format(java.time.Instant.ofEpochMilli(r.at)) +
                                " [" + r.kind + "] " + r.name +
                                " 用时" + r.durationMs + "ms"
                            // 失败时 summary 是异常串、detail 是堆栈；成功时 detail 是每步明细
                            val tail = r.summary.take(300).replace('\n', ' ')
                            val d = r.detail.take(300).replace('\n', ' ')
                            if (d.isEmpty()) head + " — " + tail
                            else head + " — " + tail + " ｜明细: " + d
                        },
                        rows.size.toString() + " 条执行记录"
                    )
                }
            }
            "skill_list" -> {
                val skills = Graph.memories.all().filter { it.type == "skill" }
                if (skills.isEmpty()) ToolExecResult.ok("还没有技能（可在对话里说「把…存为技能」创建）", "无技能")
                else ToolExecResult.ok(
                    skills.joinToString("\n") { m -> "「" + m.description.ifBlank { m.body.take(60) } + "」（名称 " + m.name + "）" },
                    skills.size.toString() + " 个技能"
                )
            }
            "plugin_toggle" -> {
                val plugin = a.optString("plugin").trim().lowercase()
                if (plugin !in listOf("office", "web", "ics")) return ToolExecResult.fail("未知插件：" + plugin + "（可选 office/web/ics）")
                val enabled = a.optBoolean("enabled", true)
                val all = listOf("office", "web", "ics")
                val current = ToolRegistry.enabledPlugins() ?: all.toSet()
                val next = if (enabled) current + plugin else current - plugin
                // v12 修复：始终写全量清单（之前全开时写空串，与"null=全开"语义打架导致插件被过滤）
                Graph.db.settingsDao().put(io.github.m0use001.planability.data.SettingKV("plugin.enabled", next.joinToString(",")))
                ToolExecResult.ok(
                    "插件 " + plugin + " 已" + (if (enabled) "启用" else "停用") + "（当前启用：" + next.joinToString("/") + "）",
                    "插件 " + plugin + " " + (if (enabled) "开" else "关")
                )
            }
            "assistant_list" -> {
                val all = Graph.assistants.all()
                ToolExecResult.ok(
                    all.joinToString("\n") { s ->
                        s.emoji + "「" + s.name + "」" + (if (s.builtin) "（内置）" else "（自定义）") +
                            "（编号 " + s.id + "）"
                    },
                    all.size.toString() + " 个智能体"
                )
            }
            "assistant_create" -> {
                val name = a.optString("name").trim()
                val sys = a.optString("systemPrompt").trim()
                if (name.isEmpty() || sys.isEmpty()) return ToolExecResult.fail("名称与人格不能为空")
                val key = "custom_" + System.currentTimeMillis() / 1000
                val id = Graph.db.assistantDao().upsert(
                    io.github.m0use001.planability.data.Assistant(
                        key = key, name = name, emoji = a.optString("emoji", "🤖").ifBlank { "🤖" },
                        slogan = a.optString("slogan"), systemPrompt = sys,
                        createdAt = AppTime.nowMillis()
                    )
                )
                val undoId = id
                ToolExecutor.registerUndo(
                    UndoEntry(sessionId, "删除智能体「" + name + "」") { Graph.db.assistantDao().deleteCustom(undoId) }
                )
                ToolExecResult.ok("已创建智能体「" + name + "」（编号 " + id + "）", "已建智能体「" + name + "」")
            }
            "assistant_edit" -> {
                val id = a.optLong("id", -1)
                val old = Graph.assistants.byId(id) ?: return ToolExecResult.fail("智能体 id=" + id + " 不存在")
                var ns = old
                a.optString("name").takeIf { it.isNotBlank() }?.let { ns = ns.copy(name = it) }
                a.optString("systemPrompt").takeIf { it.isNotBlank() }?.let { ns = ns.copy(systemPrompt = it) }
                a.optString("emoji").takeIf { it.isNotBlank() }?.let { ns = ns.copy(emoji = it) }
                a.optString("slogan").takeIf { it.isNotBlank() }?.let { ns = ns.copy(slogan = it) }
                Graph.db.assistantDao().upsert(ns)
                ToolExecResult.ok("已修改智能体「" + ns.name + "」（编号 " + id + "）", "已改智能体「" + ns.name + "」")
            }
            "assistant_delete" -> {
                val id = a.optLong("id", -1)
                val old = Graph.assistants.byId(id) ?: return ToolExecResult.fail("智能体 id=" + id + " 不存在")
                if (old.builtin) return ToolExecResult.fail("内置智能体不可删除")
                Graph.db.assistantDao().deleteCustom(id)
                ToolExecResult.ok("已删除智能体「" + old.name + "」", "已删智能体「" + old.name + "」")
            }
            "mcp_list" -> {
                val all = io.github.m0use001.planability.ai.mcp.McpRepo.all()
                if (all.isEmpty()) ToolExecResult.ok("还没有配置 MCP 服务器（信息页-自动化-MCP 里添加，或让我用 mcp_connect 接入）", "无 MCP")
                else ToolExecResult.ok(
                    all.joinToString("\n") { s -> "「" + s.name + "」 " + s.url + " · " + (if (s.enabled) "启用" else "停用") },
                    all.size.toString() + " 个 MCP 服务器"
                )
            }
            "mcp_connect" -> {
                val name = a.optString("name").trim()
                val url = a.optString("url").trim()
                if (name.isEmpty() || url.isEmpty()) return ToolExecResult.fail("名称与 URL 不能为空")
                val old = io.github.m0use001.planability.ai.mcp.McpRepo.all().firstOrNull { it.name == name }
                val server = io.github.m0use001.planability.ai.mcp.McpServer(
                    id = old?.id ?: 0, name = name, url = url, headersJson = a.optString("headers"), enabled = true
                )
                io.github.m0use001.planability.ai.mcp.McpRepo.save(server)
                val count = runCatching { io.github.m0use001.planability.ai.mcp.McpClient.listTools(server).size }.getOrDefault(-1)
                ToolExecResult.ok(
                    "已保存 MCP 服务器「" + name + "」" + (if (count >= 0) "，连接成功，" + count + " 个工具已注入对话" else "（连接测试未通过，请检查 URL 或稍后重试）"),
                    "MCP「" + name + "」已接入"
                )
            }
            "mcp_disconnect" -> {
                val name = a.optString("name").trim()
                val old = io.github.m0use001.planability.ai.mcp.McpRepo.all().firstOrNull { it.name == name }
                    ?: return ToolExecResult.fail("MCP 服务器「" + name + "」不存在")
                io.github.m0use001.planability.ai.mcp.McpRepo.delete(old.id)
                ToolExecResult.ok("已移除 MCP 服务器「" + name + "」", "MCP「" + name + "」已移除")
            }
            // ---------- v12：文件库 ----------
            "file_library_list" -> {
                val projName = a.optString("project").trim()
                val projects = Graph.projects.all()
                val pid: Long? = when {
                    projName.isEmpty() -> null
                    projName == "全局" || projName.equals("global", true) -> 0L
                    else -> {
                        val p = projects.firstOrNull { it.name == projName }
                        if (p == null) return ToolExecResult.fail(
                            "项目「" + projName + "」不存在（可用项目：" + projects.joinToString("/") { it.name } + "）"
                        )
                        p.id
                    }
                }
                // 2026-10-28 用户「AI 能读到的文件树也有问题」：原来只列各项目**顶层**文件，
                // 子目录整个不可见（绑定了真实资料树的项目几十个文件会显示成"空"）。
                // 改用 ProjectFileRepo.tree 递归列出；目录/文件都标相对路径，
                // 二进制文件标"二进制"而不是误导性的"0 字"。read/write/delete 同步支持子路径。
                val lines = StringBuilder()
                suspend fun sizeLabel(f: io.github.m0use001.planability.data.ProjectFile): String =
                    if (f.kind == "binary") "二进制" else f.size.toString() + " 字"
                suspend fun rel(f: io.github.m0use001.planability.data.ProjectFile): String =
                    if (f.sub.isBlank()) f.name else f.sub + "/" + f.name
                suspend fun emit(pid: Long, label: String) {
                    val nodes = io.github.m0use001.planability.data.ProjectFileRepo.tree(pid)
                    lines.appendLine("【" + label + "】" + (if (nodes.isEmpty()) "无" else ""))
                    nodes.forEach { n ->
                        val indent = "  ".repeat(n.depth + 1)
                        when (n) {
                            is io.github.m0use001.planability.data.ProjectFileRepo.Node.Dir ->
                                lines.appendLine(indent + n.name + "/ （" + n.childCount + " 项）")
                            is io.github.m0use001.planability.data.ProjectFileRepo.Node.File ->
                                lines.appendLine(indent + rel(n.f) + " · " + sizeLabel(n.f))
                        }
                    }
                }
                if (pid == null || pid == 0L) emit(0L, "全局")
                if (pid == null) {
                    projects.forEach { p -> emit(p.id, p.name) }
                } else if (pid != 0L) {
                    val p = projects.first { it.id == pid }
                    emit(p.id, p.name)
                }
                ToolExecResult.ok(lines.toString().trim(), "文件库清单")
            }
            "file_library_read" -> {
                // 2026-10-28：name 支持子目录相对路径（"2026.10.10/debug_1.py"）；
                // 纯文件名仍可用（唯一命中即读），与 list 输出的相对路径呼应
                val name = a.optString("name").trim()
                val projName = a.optString("project").trim()
                fun matches(f: io.github.m0use001.planability.data.ProjectFile): Boolean =
                    f.name == name || (if (f.sub.isBlank()) f.name else f.sub + "/" + f.name) == name
                val projects = Graph.projects.all()
                val candidates: List<io.github.m0use001.planability.data.ProjectFile> = if (projName.isEmpty()) {
                    (io.github.m0use001.planability.data.ProjectFileRepo.tree(0L) +
                        projects.flatMap { io.github.m0use001.planability.data.ProjectFileRepo.tree(it.id) })
                        .filterIsInstance<io.github.m0use001.planability.data.ProjectFileRepo.Node.File>()
                        .map { it.f }
                        .filter { matches(it) }
                } else {
                    val pid = if (projName == "全局" || projName.equals("global", true)) 0L
                    else projects.firstOrNull { it.name == projName }?.id
                        ?: return ToolExecResult.fail("项目「" + projName + "」不存在")
                    io.github.m0use001.planability.data.ProjectFileRepo.tree(pid)
                        .filterIsInstance<io.github.m0use001.planability.data.ProjectFileRepo.Node.File>()
                        .map { it.f }
                        .filter { matches(it) }
                }
                val f = candidates.firstOrNull() ?: run {
                    // 2026-11-09 夜·收敛：兜底从"只认 attachments/"换成 resolveUnified——
                    // 附件、导出、绝对路径、其它项目的库文件统一一套解析；选错工具也能读到。
                    val (u, uerr) = resolveUnified(name, sessionId)
                    if (u != null && u.exists() && u.isFile) {
                        val e = u.extension.lowercase()
                        if (e in DOC_EXTS) {
                            val docText = runCatching { parseDocText(u) }.getOrDefault("")
                            if (docText.isNotBlank()) {
                                val page = TextWindows.page(docText, a.optInt("offset", 0),
                                    a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                                return ToolExecResult.ok(page.render(u.name),
                                    "读取 " + u.name + (if (page.hasMore) "（未完）" else ""))
                            }
                            return ToolExecResult.fail("「" + u.name + "」解析后无文本（." + e + "），可能损坏或全部是图片页")
                        }
                        if (e in listOf("jpg", "jpeg", "png", "webp"))
                            return ToolExecResult.fail("「" + u.name + "」是图片，请改用 file_read_image(path=" + name + ") 做 OCR")
                        val txt = runCatching { u.readText() }.getOrDefault("")
                        return if (txt.isNotBlank()) {
                            val page = TextWindows.page(txt, a.optInt("offset", 0),
                                a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                            ToolExecResult.ok(page.render(u.name), "读取 " + u.name)
                        } else ToolExecResult.fail(
                            "「" + u.name + "」是空的或二进制，请改用 file_read_document(path=" + name + ")"
                        )
                    }
                    return ToolExecResult.fail(
                        "文件「" + name + "」不在文件库；" + (uerr ?: "") +
                            " 附件可改用 file_read_document(path=attachments/<文件名>) 读取"
                    )
                }
                // 2026-11-03：二进制的办公文档在库里没有正文（ProjectFile.content 只存文本），
                // 原来这里返回「[文件名 · 项目N]」+ 空正文——模型以为读成功但什么都没拿到，
                // 只能猜着换工具（英语课那次就是这里埋的坑）。现在直接解析真实文件。
                val ext = f.name.substringAfterLast('.', "").lowercase()
                val body = if (f.content.isBlank() && ext in DOC_EXTS) {
                    // sub 相对 dirOf（tree() 就是从 dirOf 走的），之前在这里又叠一层「项目文件」，
                    // 结果绑定项目的文件真实路径拼成 项目数据/项目文件/项目文件/x.docx——
                    // 永远不存在 → 空正文 → 误报「是 .docx 二进制文档」（2026-11-09 英语课实录，
                    // 同一文件 file_read_document 却能读到，AI 无所适从）
                    val dir = io.github.m0use001.planability.data.ProjectFileRepo.resolveDir(f.projectId, f.sub)
                    val real = dir?.let { File(it, f.name) }
                    if (real != null && real.exists() && real.isFile) runCatching { parseDocText(real) }.getOrDefault("")
                    else ""
                } else f.content
                if (body.isBlank() && ext in DOC_EXTS) {
                    return ToolExecResult.fail("「" + f.name + "」是 ." + ext + " 二进制文档，库里没有正文文本；请用 file_read_document 解析")
                }
                // 2026-11-05 分页（真机会话现场）：58 万字的《林默手记.md》原来只返回前 1.2 万字，
                // 模型只能告诉用户「工具每次只能从文件开头取」。现在带 offset，头部写明位置与续读指令。
                val page = TextWindows.page(body, a.optInt("offset", 0), a.optInt("limit", TextWindows.DEFAULT_LIMIT))
                ToolExecResult.ok(
                    "[" + f.name + " · " + (if (f.projectId == 0L) "全局" else "项目" + f.projectId) + "]\n" + page.render(f.name),
                    "读取 " + f.name + (if (page.hasMore) "（未完）" else "")
                )
            }
            "file_library_write" -> {
                // 2026-10-29：根 = 「项目数据」（见 ProjectDirs）。**裸名默认写进「项目文件」**——
                // AI 主动维护的长期文件归 项目文件/，不带路径时不再直接落在数据容器根上；
                // 带 `/` 的相对路径相对 项目数据（可写 当前会话/ 已归档会话/ 项目记忆/ 项目文件/）。
                // 目录不存在自动建；路径穿越由 ProjectFileRepo.safeSegments 拦
                val raw = a.optString("name").trim()
                if (raw.isEmpty()) return ToolExecResult.fail("文件名不能为空")
                val rel = raw.replace('\\', '/').trim('/')
                val cut = rel.lastIndexOf('/')
                val name = if (cut < 0) rel else rel.substring(cut + 1)
                val sub = if (cut < 0) io.github.m0use001.planability.data.ProjectDirs.FILES else rel.substring(0, cut)
                // 2026-11-08 用户实报「让 AI 把新记忆写进项目记忆文件夹，文件管理器看得到、
                // App 记忆库不显示」：那个目录是记忆库的单向导出镜像（文件头就写着"请勿手改"），
                // 写进去等于写进一个会被下次导出清理掉的临时区。**这里直接拒**，并把正确通道
                // （memory_add）写进报错——模型下一轮就会改道，用户也不用再猜为什么没生效。
                if (sub == io.github.m0use001.planability.data.ProjectDirs.MEMORY ||
                    sub.startsWith(io.github.m0use001.planability.data.ProjectDirs.MEMORY + "/")
                ) return ToolExecResult.fail(
                    "[MIRROR_READONLY] 「" + io.github.m0use001.planability.data.ProjectDirs.MEMORY +
                        "/」是记忆库自动导出的镜像目录，写进来的文件不会进记忆库、还会被下次导出清掉。" +
                        "要记东西请改用 memory_add（name/description/body，带 project 参数）；" +
                        "要存长文档请放「项目文件」。"
                )
                val content = a.optString("content")
                val projName = a.optString("project").trim()
                val pid = if (projName.isEmpty() || projName == "全局" || projName.equals("global", true)) 0L
                else Graph.projects.all().firstOrNull { it.name == projName }?.id
                    ?: return ToolExecResult.fail("项目「" + projName + "」不存在")
                val saved = io.github.m0use001.planability.data.ProjectFileRepo.save(
                    io.github.m0use001.planability.data.ProjectFile(projectId = pid, name = name, content = content, sub = sub)
                )
                ToolExecutor.registerUndoPersisted(sessionId, "删除文件 " + name, "file_library_write", name)
                ToolExecResult.ok(
                    "已写入文件库「" + (if (sub.isBlank()) name else "$sub/$name") + "」(" + content.length + " 字，" + (if (pid == 0L) "全局" else "项目") + "）",
                    "写入 " + name
                )
            }
            // 2026-10-31：目录树管理（新建文件夹 / 改名移动 / 删文件夹）实现拆在 LibraryTools——
            // execute 的 when 再次触碰 JVM 单方法 64KB 上限（同 FsShellTools 的先例）
            "file_library_mkdir", "file_library_rename", "file_library_delete" -> {
                LibraryTools.run(name, a, sessionId) ?: ToolExecResult.fail("未知工具：" + name)
            }
            // 2026-10-31：计划域（建/改/删/例外日/打卡/屏幕时间/分类）全部拆在 PlanTools——
            // 与 file_library_* / FsShellTools 同理，execute 已三次撞 JVM 单方法 64KB 上限
            "todo_create", "todo_edit", "todo_delete",
            "schedule_create", "schedule_update", "schedule_delete_today", "schedule_delete",
            "checkin_query", "checkin_add", "screen_time_query", "plan_category_list", "plan_category_create" -> {
                PlanTools.run(name, a, sessionId) ?: ToolExecResult.fail("未知工具：" + name)
            }
            // 生活数据第二批（专注/周报/成就/草稿/回收站/笔记本），同上拆在 LifeTools
            // 2026-11-07 真机验收抓到接线断点：LifeTools 内部 when 里挂着处理器的
            // kb_ask/note_link/pending_commit/pending_ignore/pending_suggest 五个工具
            // 从没被路由到这里——落到 else → PlanTools.run 返回 null → 「工具 X 未实现」。
            // kb_ask 一死，检索触发策略（v55 ContextEngine 硬规则）整条失效；
            // pending_commit 一死，中枢的 AI 入库通道只剩 UI 卡片一条路。
            // 守卫：ToolDispatchWiringTest 钉「每个登记工具必须在执行器源码出现」。
            "focus_query", "focus_add", "weekly_query", "achievement_query",
            "pending_add", "trash_query", "trash_restore",
            "notebook_list", "notebook_create", "note_edit", "note_delete",
            "kb_ask", "note_link",
            "pending_commit", "pending_ignore", "pending_suggest", "pending_update", "pending_purge" -> {
                LifeTools.run(name, a, sessionId) ?: ToolExecResult.fail("未知工具：" + name)
            }
            // ---------- v12：AI 全设置 ----------
            "model_switch" -> {
                val modelName = a.optString("model").trim()
                val model = Graph.db.aiModelDao().all().firstOrNull { it.name == modelName && it.enabled }
                    ?: return ToolExecResult.fail("模型「" + modelName + "」不存在或未启用（模型管理里添加后可用）")
                Graph.aiProviders.setActiveModel(model.id)
                ToolExecResult.ok("默认模型已切换为 " + modelName + "（新会话生效）", "切模型 " + modelName)
            }
            "capability_toggle" -> {
                val domain = a.optString("domain").trim().lowercase()
                if (domain !in ToolRegistry.DOMAINS.keys || domain == "system") {
                    return ToolExecResult.fail("未知域或常开域：" + domain + "（可切换：" + ToolRegistry.DOMAINS.keys.filter { it != "system" }.joinToString("/") + "）")
                }
                val enabled = a.optBoolean("enabled", true)
                val all = ToolRegistry.DOMAINS.keys
                val current = ToolRegistry.enabledDomains() ?: all.toSet()
                val next = if (enabled) current + domain else current - domain
                Graph.db.settingsDao().put(io.github.m0use001.planability.data.SettingKV("tool.domains", next.joinToString(",")))
                ToolExecResult.ok(
                    "能力域 " + domain + " 已" + (if (enabled) "开启" else "关闭") + "（当前：" + next.joinToString("/") + "）",
                    "域 " + domain + " " + (if (enabled) "开" else "关")
                )
            }
            else -> PlanTools.run(name, a, sessionId) ?: ToolExecResult.fail("工具 " + name + " 未实现")
        }
    }

    /**
     * data_history：全史统计 + 样本（2026-11-12 从 execute 抽出——execute 第三次撞 JVM 64KB
     * 单方法上限；首次是重复/标签参数接入时挪去 PlanTools，见该处注释）。
     * 样本只有 8 条且无编号：todo/schedule 分支必须指路 todo_query/schedule_query，
     * 否则模型会把样本当全部、断言「没有查明细的工具」（真机会话实锤）。
     */
    private suspend fun dataHistory(a: JSONObject, sessionId: Long): ToolExecResult {
            val type = a.optString("type", "todo").trim().lowercase()
            val days = a.optInt("days", 0).coerceAtLeast(0)
            val today = AppTime.today()
            val out = StringBuilder()
            return when (type) {
                "todo", "待办" -> {
                    val all = Graph.db.todoDao().all()
                    val active = all.filter { !it.done && !it.trashed }
                    val done = all.filter { it.done && !it.trashed }
                    val trashed = all.filter { it.trashed }
                    val logs = Graph.db.doneLogDao().between(0L, AppTime.dayKey(today))
                    out.appendLine("待办总记录 " + all.size + " 条：未完成 " + active.size + " / 已完成 " + done.size + " / 回收站 " + trashed.size)
                    val total = active.size + done.size
                    if (total > 0) out.appendLine("历史完成率 " + (done.size * 100 / total) + "%")
                    out.appendLine("【创建趋势】" + trendLines(all.filter { it.date > 0 }.groupingBy { it.date }.eachCount(), days, today).joinToString("；"))
                    out.appendLine("【完成趋势】" + trendLines(logs.groupingBy { it.date }.eachCount(), days, today).joinToString("；"))
                        if (active.isNotEmpty()) {
                            // 2026-11-12 某次会话第二次复发：即便 todo_query 已在召回地板（已用
                            // 同轮原句验证路由结果含它），deepseek-flash 仍只肯调 data_history，然后
                            // 断言「todo_query 不在我能调用的工具列表里」。模型回忆不可靠时，
                            // 唯一稳的办法是让它**已经信任的这份数据自带编号**：全量未完成（≤30 条）
                            // 每条带「编号 N」+重复规则，编号可直接喂 todo_edit/schedule_update。
                            out.appendLine("【当前未完成（带编号，可直接用于 todo_edit 改日期/时刻）】")
                            active.take(30).forEach { t ->
                                val day = if (t.date > 0) "（" + t.date.toLocalDate().monthValue + "/" + t.date.toLocalDate().dayOfMonth + "）" else ""
                                val perm = if (t.permanent) "（长期）" else ""
                                out.appendLine("  「" + t.title + "」（编号 " + t.id + "）" + day + perm + PlanTools.todoRepeatText(t))
                            }
                            if (active.size > 30) {
                                out.appendLine("（共 " + active.size + " 条，只列前 30；更全用 todo_query 分页查）")
                            }
                        }
                    ToolExecResult.ok(out.toString().trim(), "待办全史 " + all.size + " 条")
                }
                // v22：待办打卡时记下的载荷（早餐时间/体重/次数/短文本…）
                "checkin", "打卡" -> {
                    val pairs = Graph.plan.checkinPairs(limitPerTodo = 400)
                    if (pairs.isEmpty()) {
                        out.appendLine("目前没有任何开启「数据记录」的待办打卡记录")
                    } else {
                        out.appendLine("共 " + pairs.size + " 个待办有打卡记录：")
                        pairs.forEach { (t, rs) ->
                            out.appendLine(CheckinFormat.summary(t, rs, AppTime.zone))
                        }
                    }
                    ToolExecResult.ok(out.toString().trim(), "打卡记录 " + pairs.size + " 项")
                }
                "schedule", "日程" -> {
                    val all = Graph.db.scheduleDao().all()
                    val active = all.filter { !it.trashed }
                    out.appendLine("日程总记录 " + all.size + " 条：有效 " + active.size + " / 回收站 " + (all.size - active.size))
                    val keys = active.map { s -> AppTime.dayKey(java.time.Instant.ofEpochMilli(s.start).atZone(AppTime.zone).toLocalDate()) }
                    out.appendLine("【开始时间趋势】" + trendLines(keys.groupingBy { it }.eachCount(), days, today).joinToString("；"))
                        if (active.isNotEmpty()) {
                            out.appendLine("【最近日程（带编号，可直接用于 schedule_update）】")
                            active.sortedByDescending { it.start }.take(8).forEach { s ->
                                out.appendLine("  「" + s.title + "」（编号 " + s.id + "） " + fmtDay(s.start) + " " + AppTime.millisToTime(s.start))
                            }
                            out.appendLine("（只列最近 8 条；按范围查完整日程用 schedule_query）")
                        }
                    ToolExecResult.ok(out.toString().trim(), "日程全史 " + all.size + " 条")
                }
                "note", "笔记" -> {
                    val all = Graph.db.noteDao().allIncludingTrashed()
                    val active = all.filter { !it.trashed }
                    out.appendLine("笔记总记录 " + all.size + " 条：有效 " + active.size + " / 回收站 " + (all.size - active.size))
                    val keys = active.map { n -> AppTime.dayKey(java.time.Instant.ofEpochMilli(n.createdAt).atZone(AppTime.zone).toLocalDate()) }
                    out.appendLine("【创建趋势】" + trendLines(keys.groupingBy { it }.eachCount(), days, today).joinToString("；"))
                    if (active.isNotEmpty()) {
                        out.appendLine("【最近更新样本】")
                        active.sortedByDescending { it.updatedAt }.take(8).forEach { n ->
                            out.appendLine("  《" + n.title + "》")
                        }
                    }
                    ToolExecResult.ok(out.toString().trim(), "笔记全史 " + all.size + " 条")
                }
                "digest", "提纯", "信息" -> {
                    val all = Graph.info.digests()
                    val valid = all.filter { it.digest.isNotBlank() }
                    out.appendLine("提纯总记录 " + all.size + " 条：有效提纯 " + valid.size + " 条")
                    out.appendLine("【提纯趋势】" + trendLines(all.groupingBy { it.date }.eachCount(), days, today).joinToString("；"))
                    val groups = valid.groupingBy { it.sourceGroup }.eachCount().toList().sortedByDescending { it.second }
                    if (groups.isNotEmpty()) {
                        out.appendLine("【来源分布】" + groups.take(10).joinToString("；") { it.first + ":" + it.second })
                    }
                    if (valid.isNotEmpty()) {
                        out.appendLine("【最近样本】")
                        valid.take(5).forEach { d ->
                            out.appendLine("  " + fmtDayKey(d.date) + " " + d.sourceGroup + "：" + d.digest.take(50))
                        }
                    }
                    ToolExecResult.ok(out.toString().trim(), "提纯全史 " + all.size + " 条")
                }
                "usage", "用量", "token" -> {
                    val f = File(Graph.appContext.filesDir, "ai_usage/usage.jsonl")
                    if (!f.exists()) return ToolExecResult.fail("还没有用量记录")
                    var gens = 0; var tin = 0L; var tout = 0L
                    val perDay = mutableMapOf<Long, Int>()
                    val perModel = mutableMapOf<String, Int>()
                    f.readLines().forEach { l ->
                        runCatching {
                            val o = JSONObject(l)
                            gens++
                            val i = o.optInt("tokensIn")
                            val o2 = o.optInt("tokensOut")
                            tin += i; tout += o2
                            val d = java.time.Instant.ofEpochMilli(o.optLong("ts")).atZone(AppTime.zone).toLocalDate()
                            perDay.merge(AppTime.dayKey(d), i + o2) { a1, b1 -> a1 + b1 }
                            val m = o.optString("model").ifBlank { "?" }
                            perModel.merge(m, 1) { a1, b1 -> a1 + b1 }
                        }
                    }
                    out.appendLine("累计 " + gens + " 轮生成：输入 " + tin + " / 输出 " + tout + " tokens")
                    out.appendLine("【模型分布】" + perModel.toList().sortedByDescending { it.second }.take(5).joinToString("；") { it.first + ":" + it.second + "轮" })
                    out.appendLine("【token 趋势】" + trendLines(perDay, days, today).joinToString("；"))
                    // v40（成本优化 3.x / 阶段 4）：本会话的账 + 预算状态 + 输出速度，
                    // 给模型一个自查自停的入口（学 PURE check_token_usage，并入既有 usage_report）。
                    val sessionRows = UsageLedger.loadEntries().filter { it.session == sessionId }
                    val sIn = sessionRows.sumOf { (it.tokensIn ?: 0).toLong() }
                    val sOut = sessionRows.sumOf { (it.tokensOut ?: 0).toLong() }
                    val sCache = sessionRows.sumOf { (it.cachedTokens ?: 0).toLong() }
                    val sMs = sessionRows.sumOf { it.durationMs ?: 0L }
                    val speed = if (sMs > 0 && sOut > 0) "，平均 " + (sOut * 1000 / sMs) + " tok/s" else ""
                    out.appendLine("【本会话】输入 " + sIn + " / 输出 " + sOut + " tokens" + speed +
                        if (sCache > 0) "（其中缓存命中 " + sCache + "，命中的输入按折扣计费）" else "")
                    when (val b = UsageLedger.checkBudget(sessionId)) {
                        is UsageLedger.BudgetCheck.NoBudget -> out.appendLine("【预算】未设置（可用 " + UsageLedger.KEY_SOFT + "/" + UsageLedger.KEY_HARD + " 配置）")
                        is UsageLedger.BudgetCheck.Ok -> out.appendLine("【预算】正常：已用 " + b.spent + "（软 " + (b.soft ?: "未设") + " / 硬 " + (b.hard ?: "未设") + "）")
                        is UsageLedger.BudgetCheck.Warn -> out.appendLine("【预算】已过软上限：已用 " + b.spent + " / 软 " + b.soft + "（硬 " + (b.hard ?: "未设") + "）。请尽快收尾，并把余量情况告诉用户")
                        is UsageLedger.BudgetCheck.OverHard -> out.appendLine("【预算】已达硬上限：已用 " + b.spent + " / 硬 " + b.hard + "。新发送会被拦截，请先告知用户")
                    }
                    ToolExecResult.ok(out.toString().trim(), gens.toString() + " 轮 " + (tin + tout) + " tokens")
                }
                "sleep", "睡眠" -> {
                    val all = Graph.sleep.last(if (days > 0) days else 365)
                    if (all.isEmpty()) return ToolExecResult.fail("还没有睡眠记录（首页/信息获取可采集）")
                    val avg = all.sumOf { it.durationMin } / all.size
                    out.appendLine("睡眠记录 " + all.size + " 天：平均 " + avg / 60 + " 小时 " + avg % 60 + " 分钟，达标(≥7h) " + all.count { it.atGoal } + " 天")
                    out.appendLine("【时长趋势（半小时档）】" + trendLines(all.associate { it.dateEpochDay to it.durationMin / 30 }, days, today).joinToString("；"))
                    out.appendLine("【最近样本】")
                    all.take(5).forEach { r ->
                        out.appendLine("  " + fmtDayKey(r.dateEpochDay) + "：" + r.durationText + "，入睡 " + fmtHM(r.fallMin) + "，醒来 " + fmtHM(r.wakeMin))
                    }
                    ToolExecResult.ok(out.toString().trim(), "睡眠 " + all.size + " 天")
                }
                else -> return ToolExecResult.fail("未知数据类型：" + type + "（可选 todo/schedule/note/digest/usage/sleep）")
            }
    }

    /** 趋势分桶：跨度 ≤31 天按日，≤190 天按周，否则按月；返回 "标签: 数值" 行 */
    private fun trendLines(dayCounts: Map<Long, Int>, daysLimit: Int, today: LocalDate): List<String> {
        if (dayCounts.isEmpty()) return listOf("无记录")
        val rangeStart = if (daysLimit > 0) today.minusDays((daysLimit - 1).toLong())
        else dayCounts.keys.min().toLocalDate().let { if (it.isAfter(today)) today else it }
        val start = rangeStart
        val span = java.time.temporal.ChronoUnit.DAYS.between(start, today) + 1
        val lines = mutableListOf<String>()
        if (span <= 31) {
            var d = start
            while (!d.isAfter(today)) {
                val k = AppTime.dayKey(d)
                lines.add(d.monthValue.toString() + "/" + d.dayOfMonth + ":" + (dayCounts[k] ?: 0))
                d = d.plusDays(1)
            }
        } else if (span <= 190) {
            var w = 0
            while (true) {
                val ws = start.plusDays(w * 7L)
                if (ws.isAfter(today)) break
                val we = minOf(ws.plusDays(6), today)
                var sum = 0
                var d = ws
                while (!d.isAfter(we)) { sum += dayCounts[AppTime.dayKey(d)] ?: 0; d = d.plusDays(1) }
                lines.add(ws.monthValue.toString() + "/" + ws.dayOfMonth + "当周:" + sum)
                w++
            }
        } else {
            var m = java.time.YearMonth.from(start)
            val endM = java.time.YearMonth.from(today)
            while (!m.isAfter(endM)) {
                var sum = 0
                var d = m.atDay(1)
                while (!d.isAfter(m.atEndOfMonth())) { sum += dayCounts[AppTime.dayKey(d)] ?: 0; d = d.plusDays(1) }
                lines.add(m.year.toString() + "-" + m.monthValue + "月:" + sum)
                m = m.plusMonths(1)
            }
        }
        return lines
    }

    private fun fmtDayKey(dayKey: Long): String {
        val d = dayKey.toLocalDate()
        return d.monthValue.toString() + "/" + d.dayOfMonth
    }

    private fun fmtHM(min: Int): String =
        "%02d:%02d".format((min / 60) % 24, min % 60)

    /**
     * v19.42：事件触发字面量 → (kind, arg)。
     *
     * 认 `boot` / `power:on` / `power:off` / `unlock` / `app_open:包名`。
     * 用 `:` 分隔而不是两个独立参数：模型少传一个参数的出错率远高于把两个值写错格式，
     * 而这里两种错都会当场被拒——一个拼错的触发器建出来是**永不触发的空壳**，
     * 而且不会有任何运行错误提示（没匹配上就是没匹配上）。
     */
    private fun parseEventTrigger(raw: String): Pair<String, String>? {
        val t = raw.trim()
        val kinds = io.github.m0use001.planability.notify.AutomationSignal.KINDS
        val head = t.substringBefore(':').trim()
        val arg = t.substringAfter(':', "").trim()
        if (head !in kinds) return null
        // app_open 没有包名就等于没有目标，必须拒
        if (head == io.github.m0use001.planability.notify.AutomationSignal.KIND_APP_OPEN && arg.isBlank()) return null
        // power 只认 on/off：写成「充电」会安静地匹配不到任何广播
        if (head == io.github.m0use001.planability.notify.AutomationSignal.KIND_POWER && arg !in listOf("on", "off")) {
            return null
        }
        return head to arg
    }

    /** 事件字面量写错时的统一报错：把可用值一次列全，模型不用来回试 */
    private fun eventErr(raw: String): String =
        "看不懂事件触发「$raw」。可用值：boot / power:on / power:off / unlock / app_open:包名" +
            "（包名如 com.tencent.mm，在应用的「跳转」页能看到）"
}
