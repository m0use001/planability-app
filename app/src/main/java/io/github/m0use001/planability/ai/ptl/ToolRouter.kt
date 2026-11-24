package io.github.m0use001.planability.ai.ptl

import org.json.JSONArray

/**
 * 工具路由（2026-11-07，Step 2）：按本轮用户提问的相关性，从全量工具 schema（~109 个，
 * 序列化后 8-12k token）里选 top-K 子集喂给模型。
 *
 * 动机（设计文档 §3）：工具 schema 每轮全量重发
 * 是"每轮重复支付的最大 token 项"之一（v40 只 memo 了序列化，没 memo token）；本地 2B 档
 * 模型扛不住 100+ 工具单趟选择，云端模型也是纯浪费。
 *
 * ## 设计取舍（失败契约三件套的"契约"部分）
 *
 * - **词法打分起步，零新依赖**：query 切词（英文词 + 中文 2-gram）对工具
 *   `name/cnDescription/whenToUse` 做大小写不敏感子串命中计分。同义词命中不到是已知边界——
 *   靠 `whenToUse` 里的中文场景描述兜（每个 ToolDef 都有"适用场景"一句），不够再上向量路
 *   （复用 ai/embed 基建，同端侧重排 Step 3 的路）。
 * - **召回地板**：无论打分如何，`FLOOR` 里的工具永远保留——它们是模型"兜底自救"的口子
 *   （查时间/查记忆/委托智能体/切模型 + kb 检索三件套，检索策略硬规则依赖它们）。
 *   地板里不存在的名字自动忽略（随工具集演化不脆）。
 * - **弱信号不窄化**（防回归的关键）：query 空白 / 可打分 token < 2 / 打分全零 →
 *   **原样返回全量**。闲聊、重试、看不清意图的轮次宁可全量，也不冒险把模型唯一需要的
 *   工具筛掉——丢工具的代价是模型"看不见"（不调），不是崩溃（执行仍按名走
 *   `ToolExecutor`→`ALL`，路由只改"模型看得见谁"）。
 * - **纯函数可单测**：`select` 不碰 JSON/DB。
 */
object ToolRouter {

    /**
     * 召回地板：永远保留的工具名（与 DOMAINS 演化解耦，不在 ALL 里的自动忽略）。
     *
     * 2026-11-08 加网页组（事故原型：小说会话，用户要「完整列出星轨3 剧情」，
     * 模型搜到 wiki 章节页却说**「我没有抓取网页的能力」**——`web_fetch`/`browser_*`
     * 实现齐全、历史上也成功用过 4 次，只是被路由筛掉了。**丢工具的代价是模型"看不见"，
     * 然后一本正经地断言自己没有这个能力**，比崩溃更难发现，也更伤信任。
     * 判据：读网页是"兜底自救"的口子（和 kb 检索三件套同级），任何任务都可能突然需要
     * 去读一页原文，词法打分算不出这种需求）。
     *
     * 2026-11-11 加文件读取组（**同一会话、同一病灶的复发**：用户「你之前不是查过了吗，
     * 还写了记忆文件」，模型回「我这边现在读不到那 9075 字的文件正文：**这次读文件的工具
     * 没有提供**」——tools_audit 里 file_library_read 历史调用全 ok、实现带完整分页，
     * 只是这轮词法打分（「记忆文件」把记忆组抬得很高）把它挤出了 top-24。文件读取是
     * AI 看本地资料的**眼睛**，和读网页同判据：任何轮次都可能突然要读一份文件正文，
     * 不能赌词法。列出+读全文+三格式+图片 OCR+附件文本五件全保）。
     *
     * 2026-11-12 加 memory_invalidate（真机会话三轮追问）：
     * 作废轮的措辞完全不可预测（口语化的作废表述里都没有「作废」二字），
     * 词法算不出这轮要作废旧条目；而漏掉的代价正是本工具要根治的事——模型看不见它，
     * 就退回 memory_add 堆「作废声明」，旧条目继续被召回。
     */
    internal val FLOOR = setOf(
        "now", "memory_search", "memory_add", "memory_invalidate", "ask_expert", "model_switch",
        "kb_ask", "note_search", "library_search",
        "web_fetch", "web_search",
        "browser_open", "browser_click", "browser_fill", "browser_extract", "browser_back",
        "file_library_list", "file_library_read", "file_read_text", "file_read_document", "file_read_image",
        // 2026-11-12 加查询组（真机会话实锤，与网页组/文件组同一病灶）：用户「你给四级备考
        // 那几个待办安排每天具体实际时间段」→ 词法打分把 todo_query 挤出 top-30，模型只拿到
        // data_history 的 8 条样本（无编号），于是两轮断言「我没有查待办明细的工具」「没法给
        // 已有待办加时刻」——全是幻觉（todo_query/todo_edit(due) 实现齐全）。
        // 查询类工具是"拿到编号再改"的前置口子，任何轮次都可能突然需要：永远保在地板。
        "todo_query", "schedule_query",
        // 2026-11-12 某次会话（同日晚）：模型两次调 pending_update →「未知工具」→ 对用户说
        // 「我没有能修改草稿时刻的工具」。草稿修改与查询同属「拿编号再改」的生命周期口子，
        // 同样常驻（名字是模型自然会用到的叫法，别再让它发现"这个工具不存在"）。
        "pending_update",
    )

    /**
     * 无信息量的续接词（2-gram 粒度）：用户说「重试一下」「继续」「好的」时，
     * 这些词会命中工具描述里的"失败重试""继续读"等字样，把路由误触发成窄化。
     * 2026-11-08 实测：「重试一下」被窄化到 **13 个工具**（全量 109）——等于每次说"重试"
     * 就丢掉一大半能力。规则：**去掉这些词后不足 2 个词元 = 弱信号 = 不窄化**。
     */
    private val FILLERS = setOf(
        "重试", "试一", "一下", "再试", "试试", "再来", "继续", "接着", "然后",
        "好的", "可以", "开始", "快点", "对的", "行吧", "嗯嗯", "这个", "那个",
    )

    /**
     * 窄化后保留的工具数上限（含地板/强制名）。云端无感、本地 2B 可用的量级。
     * 2026-11-11 24→30：地板从 14 扩到 19（文件读取组进地板，见 FLOOR 注释），
     * 原值会让排名组只剩 5 个位置——路由省的那点 schema token 抵不过"该用的工具
     * 挤不进选集"这一类故障的复发成本。
     * 2026-11-12 30→34：查询组（todo_query/schedule_query）进地板后地板=23，
     * 30 只剩 7 个打分名额（某次会话的 todo_query 正是在这个量级被挤掉）。
     * 规则：**TOP_K 至少给打分组留 11 个位置**，地板扩一个、TOP_K 跟着扩一个。
     * 2026-11-12 34→35：pending_update 进地板（同会话同日第三次），地板=24。
     */
    internal const val TOP_K = 35

    /** 强制保留的下限：地板 + 强制名都凑不齐时，窄化没有意义 */
    private const val MIN_GAIN = 4

    /**
     * 从全量 schema 数组里选本轮子集。
     *
     * @param full `ToolRegistry.toToolsJson` 的结果（MCP 工具应在路由**之后**再 append，
     *   它们是动态的、不在打分池里）
     * @param query 本轮用户提问文本；null/弱信号 → 原样返回 full（不窄化）
     * @param forcedNames 强制保留的工具名（如 @勾连解析出的工具；域级强制已在
     *   toToolsJson 的 enabledDomains 里处理，这里补单工具粒度）
     * @return 路由后的 JSONArray（新建外层数组；不窄化时原样返回 full 引用）
     */
    fun route(full: JSONArray, query: String?, forcedNames: Set<String> = emptySet()): JSONArray {
        if (full.length() == 0) return full
        val names = ArrayList<String>(full.length())
        for (i in 0 until full.length()) {
            val n = full.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
            if (n.isNotEmpty()) names.add(n)
        }
        val keep = select(names, query, forcedNames) ?: return full
        val out = JSONArray()
        for (i in 0 until full.length()) {
            val n = full.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty()
            if (n in keep) out.put(full.optJSONObject(i))
        }
        return out
    }

    /**
     * 选名。返回 null = 弱信号不窄化；否则 = 保留名单（必含地板+强制名，且 ⊆ allNames）。
     */
    internal fun select(allNames: List<String>, query: String?, forcedNames: Set<String>): Set<String>? {
        val allowed = allNames.toSet()
        // 用户点名（2026-11-11 全链撞测事故）：query 里 verbatim 出现的工具名强制保留。
        // 实录：用户消息点名 file_read_image，词法打分只得 3 分，被 9 个同分工具挤出
        // top-24 → 模型"看不见"被点名的工具，第一轮 11 次错调 file_read_document 读 .jpg、
        // 第二轮病急乱投医调 web_fetch。点名是比打分强得多的信号（与 @勾连 forcedNames 同级）。
        // ≥6 字符门槛：普通英文短词（now/see…）不算点名，防误触发。
        val mentioned = if (query.isNullOrEmpty()) emptySet() else
            allNames.filterTo(HashSet()) { it.length >= 6 && query.contains(it, ignoreCase = true) }
        val mustKeep = (FLOOR + forcedNames + mentioned) intersect allowed
        // 去掉纯续接词（「重试一下」「继续」）：它们不是任务信号，只会在工具描述里撞字
        val tokens = tokenize(query).filter { it !in FILLERS }
        if (tokens.size < 2) return null
        val scores = HashMap<String, Int>()
        for (name in allNames) {
            val def = ToolRegistry.byName(name) ?: continue
            val hay = (def.cnDescription + " " + def.whenToUse).lowercase()
            var s = 0
            for (t in tokens) {
                if (def.name.contains(t, ignoreCase = true)) s += 3
                if (hay.contains(t)) s += 2
            }
            if (s > 0) scores[name] = s
        }
        if (scores.isEmpty()) return null
        val ranked = scores.entries.sortedByDescending { it.value }.map { it.key }
        // 名次并列的兜底排序：按 ALL 里的注册序（稳定，便于单测）
        val keep = LinkedHashSet<String>(mustKeep)
        for (n in ranked) {
            if (keep.size >= TOP_K) break
            keep.add(n)
        }
        if (allowed.size - keep.size < MIN_GAIN) return null
        return keep
    }

    /**
     * 切词：英文/数字按词（≥2 字符），中文按 2-gram（单字命中满篇、3-gram 召回不足）。
     * 标点/空白丢弃。
     */
    internal fun tokenize(q: String?): List<String> {
        if (q.isNullOrBlank()) return emptyList()
        val out = ArrayList<String>()
        Regex("[A-Za-z][A-Za-z0-9_]+").findAll(q).forEach { out.add(it.value.lowercase()) }
        val cjk = q.filter { it.code in 0x4E00..0x9FFF }
        for (i in 0 until cjk.length - 1) out.add(cjk.substring(i, i + 2))
        return out
    }
}
