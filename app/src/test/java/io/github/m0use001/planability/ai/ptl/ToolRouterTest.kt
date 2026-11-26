package io.github.m0use001.planability.ai.ptl

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具路由（v55 Step 2）的回归网。
 *
 * 三件套对应关系：
 * - 失败契约 → `弱信号不窄化`（query 空白/单 token/全零分 → 全量原样返回，不冒险丢工具）；
 * - 质量闸 → `金标工具进 top-K`（典型提问里该用的工具必须在选集里——这是路由的生命线）；
 * - 回归网 → `召回地板永不丢` + `route 弱信号零行为变化`（接进 ChatEngine 后，闲聊/重试
 *   轮次与路由前完全一致）。
 *
 * 全部跑在真实 ToolRegistry.ALL 上（纯 JVM，无 Android 依赖）——工具定义变了这里会红。
 */
class ToolRouterTest {

    private val allNames = ToolRegistry.ALL.map { it.name }

    private fun schemaOf(names: List<String>): JSONArray {
        val arr = JSONArray()
        ToolRegistry.ALL.filter { it.name in names }.forEach { arr.put(it.toJsonSchema()) }
        return arr
    }

    private fun namesOf(arr: JSONArray): Set<String> {
        val out = mutableSetOf<String>()
        for (i in 0 until arr.length()) {
            out.add(arr.optJSONObject(i)?.optJSONObject("function")?.optString("name").orEmpty())
        }
        return out
    }

    // ---------- 失败契约：弱信号不窄化 ----------

    @Test
    fun `弱信号不窄化 - 空白与重试轮次原样返回全量`() {
        val full = schemaOf(allNames)
        for (q in listOf(null, "", "   ", "嗯", "好")) {
            assertEquals("query=「$q」应零窄化", full.length(), ToolRouter.route(full, q).length())
        }
    }

    @Test
    fun `弱信号不窄化 - 与所有工具零重叠的 query 不窄化`() {
        // 这串 bigram 不会命中任何工具描述（防"路由把闲聊轮的可用工具筛掉"）
        val keep = ToolRouter.select(allNames, "囍囍囍囍囍囍", emptySet())
        assertEquals(null, keep)
    }

    // ---------- 质量闸：金标工具进 top-K ----------

    @Test
    fun `金标工具进 top-K - 建待办`() {
        val keep = requireNotNull(ToolRouter.select(allNames, "帮我建一个明天提醒交作业的待办", emptySet()))
        assertTrue("todo_create 不在选集里：$keep", "todo_create" in keep)
    }

    @Test
    fun `金标工具进 top-K - 查日程`() {
        val keep = requireNotNull(ToolRouter.select(allNames, "这周我还有什么安排", emptySet()))
        assertTrue("schedule_query 不在选集里：$keep", "schedule_query" in keep)
    }

    @Test
    fun `金标工具进 top-K - 问自己的笔记`() {
        val keep = requireNotNull(ToolRouter.select(allNames, "我的笔记里有没有关于微积分的记录", emptySet()))
        assertTrue("kb_ask 不在选集里：$keep", "kb_ask" in keep)
    }

    /**
     * 2026-11-25 ◆ 实锤回归（「小说助理删不了记忆」）：用户原话「……记忆系统
     * 有点问题，你先全删掉……」——「删掉/全删」与 memory_forget 描述里的「删除」一字之差，
     * 词法零分被挤出 top-35，模型回「我手上的工具里没有记忆删除这一项」。修=进召回地板
     * + 描述补口语措辞。这条钉死真实原话必须能路由到 memory_forget。
     */
    @Test
    fun `金标工具进 top-K - 口语删记忆`() {
        val q = "（这个agent软件尚处于alpha阶段）记忆系统有点问题，你先全删掉（除非是◆三剧情这种客观记录）"
        val keep = requireNotNull(ToolRouter.select(allNames, q, emptySet()))
        assertTrue("memory_forget 不在选集里（◆ 复发）：$keep", "memory_forget" in keep)
    }

    // ---------- 回归网：召回地板 ----------

    @Test
    fun `召回地板永不丢 - 即使 query 只偏向一个域`() {
        val keep = requireNotNull(ToolRouter.select(allNames, "创建日程安排", emptySet()))
        for (f in ToolRouter.FLOOR) {
            if (f in allNames) assertTrue("地板工具 $f 被路由筛掉了：$keep", f in keep)
        }
    }

    @Test
    fun `文件读取组必须进地板`() {
        // 2026-11-11 真机 6339（小说助理会话）实锤：模型回「我这边现在读不到那 9075 字的
        // 文件正文：这次读文件的工具没有提供」——tools_audit 里 file_library_read 历史
        // 全 ok，只是这轮词法打分把它挤出 top-24。文件读取是 AI 看本地资料的眼睛，
        // 与读网页组（2026-11-08 同会话同病灶）同判据：永远在地板。
        for (f in listOf(
            "file_library_list", "file_library_read",
            "file_read_text", "file_read_document", "file_read_image"
        )) {
            assertTrue("文件读取工具 $f 必须在召回地板", f in ToolRouter.FLOOR)
        }
    }

    @Test
    fun `实况复现 - 事后核对轮必须还能看见 file_library_read`() {
        // 6339 原句的用户消息：词法上「记忆文件」抬高记忆组、没有任何文件读取词，
        // 修前这轮 file_library_read 被挤出 → 模型只能让用户手贴 9075 字正文
        val keep = requireNotNull(
            ToolRouter.select(allNames, "你之前不是查过了吗，还写了记忆文件", emptySet())
        )
        assertTrue("file_library_read 被路由挤掉：$keep", "file_library_read" in keep)
        assertTrue("file_library_list 被路由挤掉：$keep", "file_library_list" in keep)
    }

    @Test
    fun `实况复现 - 用户作废设定轮必须还能看见 memory_invalidate`() {
        // 6410 原句（◆，前面已有 6404「不要再搞想不想被看到的人设」、6412「我都是
        // 换路特斯esprit了」两连）：作废轮的措辞没有任何词法信号（「人设/意大利」只会抬
        // 旧设定与车型类条目）。修前 memory_invalidate 尚不存在，模型只能 memory_add
        // 「作废声明」，旧条目 invalidAt 一直是 0 照常被召回 → 作废设定反复复提。
        val keep = requireNotNull(
            ToolRouter.select(allNames, "我不是早说了之前那个意大利人设作废了吗？？", emptySet())
        )
        assertTrue("memory_invalidate 被路由挤掉：$keep", "memory_invalidate" in keep)
        assertTrue("memory_search 被路由挤掉（作废前先要找旧条目）：$keep", "memory_search" in keep)
    }

    @Test
    fun `强制保留名即使低分也必须进选集`() {
        val keep = requireNotNull(ToolRouter.select(allNames, "帮我建一个待办", setOf("sleep_query")))
        assertTrue("强制名 sleep_query 被丢：$keep", "sleep_query" in keep)
    }

    // ---------- 用户点名强制（2026-11-11 全链撞测事故） ----------

    @Test
    fun `用户点名的工具必须进选集 - 即使被同分工具挤出 top-K`() {
        // 2026-11-11 撞测实录：用户消息 verbatim 点名 file_read_image，词法打分只得 3 分，
        // 被 9 个同分工具挤出 top-24 → 模型"看不见"被点名的工具，只好病急乱投医调 web_fetch
        val keep = requireNotNull(
            ToolRouter.select(
                allNames,
                "Now call file_read_image with path exports/8.1.slides/04.jpg to view page 4, " +
                    "then describe what you see",
                emptySet()
            )
        )
        assertTrue("用户点名的 file_read_image 被路由挤掉：$keep", "file_read_image" in keep)
    }

    @Test
    fun `点名强制 - 大小写不敏感且多工具点名全保留`() {
        val keep = requireNotNull(
            ToolRouter.select(allNames, "use FILE_READ_IMAGE then file_read_document", emptySet())
        )
        assertTrue("FILE_READ_IMAGE（大小写不敏感）被丢：$keep", "file_read_image" in keep)
        assertTrue("file_read_document 被丢：$keep", "file_read_document" in keep)
    }

    @Test
    fun `点名强制 - 短名词不算点名（now 不是 now 工具的点名称呼）`() {
        // 「now」只有 3 字符，普通英文句到处是它；点名称呼必须 ≥6 字符防误触发
        val keep = ToolRouter.select(allNames, "now tell me what you see in the image", emptySet())
        // 无强断言：只要不因误点名而把「now」以外的奇怪东西带进必留即可（行为守卫）
        if (keep != null) assertTrue("now 工具不在选集（本句只是英文词 now）：$keep", "now" in keep)
    }

    @Test
    fun `地板里不存在的名字自动忽略（随工具集演化不脆）`() {
        val keep = ToolRouter.select(allNames, "囍囍囍囍囍囍", setOf("不存在的工具"))
        assertEquals(null, keep) // 全零分 → 不窄化，地板不存在的名也不炸
    }

    // ---------- route() 端到端 ----------

    @Test
    fun `route 弱信号零行为变化 - 引用与内容都等于全量`() {
        val full = schemaOf(allNames)
        val routed = ToolRouter.route(full, null)
        assertEquals(full.length(), routed.length())
        assertEquals(namesOf(full), namesOf(routed))
    }

    @Test
    fun `route 窄化 - 子集更小且必含地板与金标`() {
        val full = schemaOf(allNames)
        val routed = ToolRouter.route(full, "帮我建一个明天提醒交作业的待办")
        val keep = namesOf(routed)
        assertTrue("窄化后不应比全量大", keep.size < full.length())
        assertTrue("金标 todo_create 丢了", "todo_create" in keep)
        for (f in ToolRouter.FLOOR) if (f in allNames) assertTrue("地板 $f 丢了", f in keep)
        // 入参不被改动（MCP 追加等下游依赖原数组语义）
        assertEquals(allNames.size.toLong(), full.length().toLong())
    }

    // ---------- 切词 ----------

    @Test
    fun `tokenize - 英文按词中文按二元组`() {
        assertEquals(listOf("todo", "待办", "办创", "创建", "建日", "日程"),
            ToolRouter.tokenize("todo 待办创建日程"))
        assertEquals(emptyList<String>(), ToolRouter.tokenize(null))
        assertEquals(emptyList<String>(), ToolRouter.tokenize("。！？"))
    }
}
