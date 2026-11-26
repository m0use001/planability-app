package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-14：DeepSeek 应用能力云通道的解析与失败契约（方案
 * ◆/planability-ai-cloud-plan.md，拍板：Tavily 优先云兜底）。
 *
 * 与 WebSearchTavilyTest 同一套纪律：
 * - 解析吃**真实响应形状**（/guides/search + /guides/ocr 文档字段）；
 * - 失败契约：错误体/空 results/坏 JSON 一律空手，绝不抛；
 * - 源码级守卫钉「兜底语义不许变成主源」「URL 必须过 SafeUrl」「默认全关」。
 */
class DeepSeekCloudTest {

    private fun src(rel: String): String {
        val roots = listOf("app/src/main/java/io/github/m0use001/planability/", "src/main/java/io/github/m0use001/planability/")
        roots.forEach { r ->
            val f = java.io.File(r + rel)
            if (f.isFile) return f.readText()
        }
        return java.io.File("app/src/main/java/io/github/m0use001/planability/" + rel).readText()
    }

    /** /guides/search 文档的真实响应形状（裁剪） */
    private val realSearchShape = """
        {"results":[
          {"title":"高等数学 复习要点","url":"https://math.example.com/keynote.pdf",
           "snippet":"微分方程 第七章","summary":"第七章 微分方程复习要点与例题",
           "content":"很长很长的正文…","site_name":"示例数学站","date":"2025-10-15",
           "rerank_score":0.93,"authority":0.8},
          {"title":"坏条目","url":"ftp://example.com/x","snippet":"非法协议"}
        ]}
    """.trimIndent()

    @Test
    fun `解析真实形状搜索响应`() {
        val hits = DeepSeekCloud.parseSearch(realSearchShape, 5)
        assertEquals(1, hits.size)   // ftp:// 丢
        assertEquals("高等数学 复习要点", hits[0].title)
        assertEquals("https://math.example.com/keynote.pdf", hits[0].url)
        // 摘要优先 summary（服务端已抽好），压空白截 240
        assertTrue("摘要应取 summary：${hits[0].snippet}", hits[0].snippet.contains("复习要点"))
        assertFalse(hits[0].snippet.contains("\n"))
    }

    @Test
    fun `搜索错误体与空结果都空手`() {
        assertTrue(DeepSeekCloud.parseSearch("""{"error":{"message":"bad key"}}""", 5).isEmpty())
        assertTrue(DeepSeekCloud.parseSearch("""{"results":[]}""", 5).isEmpty())
        assertTrue(DeepSeekCloud.parseSearch("not json", 5).isEmpty())
        assertTrue(DeepSeekCloud.parseSearch("", 5).isEmpty())
    }

    /** /guides/ocr 文档的真实响应形状：pages[].text 拼接 */
    @Test
    fun `解析 OCR 逐页文本`() {
        val json = """
            {"id":"ocr_1","object":"ocr.result","page_count":2,
             "pages":[
               {"index":0,"text":"第一页文字","finish_reason":"stop"},
               {"index":1,"text":"第二页文字","finish_reason":"length"}
             ],
             "usage":{"total_tokens":100}}
        """.trimIndent()
        val text = DeepSeekCloud.parseOcr(json)!!
        assertTrue(text.contains("第一页文字") && text.contains("第二页文字"))
        // 空页/无 pages = null（端侧结果保留）
        assertNull(DeepSeekCloud.parseOcr("""{"pages":[]}"""))
        assertNull(DeepSeekCloud.parseOcr("bad json"))
    }

    // ---------------------------------------------------------------- 计费换算（唯一口径）

    /**
     * 记账单位换算钉死（2026-11-14 真机首版写错过一次：搜索记 50 tenths=0.05 元，
     * 实际 0.005 元/次——超收 10 倍；显示又除错 1000 倍）。官方定价：
     * 搜索 5 元/千次、网页抓取 5 元/千次、OCR 1.5 元/千页；tenths = 0.001 元。
     */
    @Test
    fun `计费换算唯一口径`() {
        assertEquals("一次搜索应是 0.005 元 = 5 tenths", 5, DeepSeekCloud.costTenthsSearch())
        assertEquals("一次抓取应是 0.005 元 = 5 tenths", 5, DeepSeekCloud.costTenthsFetch())
        assertEquals("一页 OCR 0.0015 元 → 整数粒度记 2 tenths", 2, DeepSeekCloud.costTenthsOcrPages(1))
        assertEquals("10 页 OCR = 15 tenths", 15, DeepSeekCloud.costTenthsOcrPages(10))
        assertEquals(0.005, DeepSeekCloud.tenthsToYuan(5), 1e-9)
        assertEquals(5.0, DeepSeekCloud.tenthsToYuan(5000), 1e-9)
        // 调用点不许再出现字面量（防再写错单位）
        val cloud = src("ai/ptl/DeepSeekCloud.kt")
        assertFalse("搜索记账不许写字面量", cloud.contains("budgetOkAndCharge(5)"))
        assertFalse("抓取记账不许写字面量", cloud.contains("budgetOkAndCharge(50)"))
        assertTrue("搜索记账应走纯函数", cloud.contains("costTenthsSearch()"))
        assertTrue("抓取记账应走纯函数", cloud.contains("costTenthsFetch()"))
        assertTrue("OCR 记账应走纯函数", cloud.contains("costTenthsOcrPages(pageCountOf(raw))"))
        // 分类记账：三类能力各自累计（设置卡分能力展示的底账）
        assertTrue("搜索没按分类记账", cloud.contains("charge(\"search\", costTenthsSearch())"))
        assertTrue("抓取没按分类记账", cloud.contains("charge(\"fetch\", costTenthsFetch())"))
        assertTrue("OCR 没按分类记账", cloud.contains("charge(\"ocr\", costTenthsOcrPages(pageCountOf(raw))"))
    }

    // ---------------------------------------------------------------- 源码级守卫

    /** 搜索/抓取默认关（兜底语义）；读文件默认「优先」（2026-11-14 用户拍板） */
    @Test
    fun `云开关默认：搜索抓取关、读文件优先`() {
        val s = src("data/SettingsRepo.kt")
        assertTrue("cloudSearchEnabled 必须默认 false", s.contains("val cloudSearchEnabled: Boolean = false"))
        assertTrue("cloudFetchEnabled 必须默认 false", s.contains("val cloudFetchEnabled: Boolean = false"))
        assertTrue(
            "读文件默认必须「优先」（用户 2026-11-14 拍板：AI 读文件优先走云端）",
            s.contains("val cloudOcrMode: String = CLOUD_OCR_PREFER")
        )
        // 旧布尔键兼容：老库里 cloud.ocr 的 "1"/"0" 要能映射成兜底/关闭，不能读丢
        assertTrue("旧键兼容映射丢了", s.contains("CLOUD_OCR_FALLBACK") && s.contains("all[\"cloud.ocr\"]"))
    }

    /**
     * 兜底语义钉死：searchWithSource 里云兜底必须排在 Tavily 循环与端侧链**之后**；
     * 工具层透传只许出现在 DeepSeek 端点（echoEmptyReasoning 复用同一条 baseUrl 判断）。
     */
    @Test
    fun `云兜底只许排在 Tavily 与端侧之后`() {
        val w = src("ai/ptl/WebSearch.kt")
        val body = w.substringAfter("fun searchWithSource")
        val tavilyLoop = body.indexOf("for (k in keys)")
        // 主路径的端侧链锚定在 Tavily 循环之后（keys.isEmpty 分支里的 legacySearch 在循环前出现是合法的）
        val legacyCall = body.indexOf("legacySearch(query, count)", tavilyLoop)
        val cloudCall = body.indexOf("DeepSeekCloud.searchIfEnabled(query, count)", tavilyLoop)
        assertTrue("Tavily 循环丢了", tavilyLoop >= 0)
        assertTrue("端侧链丢了", legacyCall >= 0)
        assertTrue("云兜底调用丢了", cloudCall >= 0)
        assertTrue(
            "云兜底不许排在 Tavily 之前（拍板：Tavily 优先，云兜底）",
            cloudCall > tavilyLoop && cloudCall > legacyCall
        )
        assertTrue("云来源标注丢了", w.contains("\"deepseek\""))
        val stream = src("ai/AiStream.kt")
        // 透传条件复用 DeepSeek 端点判断（echoEmptyReasoning 即 baseUrl 含 deepseek）
        assertTrue("透传没有挂在 DeepSeek 端点判断上", Regex("if \\(echoEmptyReasoning\\) \\{\\s*val serverTools").containsMatchIn(stream))
        val cloud = src("ai/ptl/DeepSeekCloud.kt")
        assertTrue("serverToolTypes 没按开关过滤", cloud.contains("if (searchEnabled()) add(\"deepseek_web_search\")"))
    }

    /** 把不可信 URL 转发给云端抓取前必须先过 SafeUrl（本机/私网/保留地址拦截） */
    @Test
    fun `云抓取必须过 SafeUrl`() {
        val cloud = src("ai/ptl/DeepSeekCloud.kt")
        val fetchFn = cloud.substringAfter("suspend fun fetchIfEnabled")
        assertTrue("fetchIfEnabled 里没有 SafeUrl 校验", fetchFn.contains("SafeUrl.isAllowed(url)"))
    }

    /**
     * 2026-11-14 用户改口径：DeepSeek 系列模型「读文件优先走云端」。
     * 两档语义都必须在源码里成立：
     * - 优先（prefer）：云端在端侧之前（不设长度门槛），云端空手才回端侧；
     * - 兜底/关闭：端侧第一手，云端只在端侧 <8 字时补（旧行为不变）。
     */
    @Test
    fun `读文件两档：优先云在先、兜底端侧在先`() {
        val cloud = src("ai/ptl/DeepSeekCloud.kt")
        val fn = cloud.substringAfter("suspend fun recognizeEnhanced")
        val localIdx = fn.indexOf("OcrHelper.recognize")
        val preferBranch = fn.indexOf("if (preferForSession(sessionId))")
        val forcedCloud = fn.indexOf("ocrIfEnabled(context, uri, \"\", force = true)")
        val fallbackCloud = fn.indexOf("ocrIfEnabled(context, uri, local)")
        assertTrue("端侧 ML Kit 调用丢了", localIdx >= 0)
        assertTrue("没有「优先」分支", preferBranch > localIdx)
        assertTrue("优先分支里没有先上云（force=true）", forcedCloud > preferBranch)
        assertTrue("兜底分支的云调用丢了", fallbackCloud > forcedCloud)
        // 兜底的长度门槛只能在 force=false 时生效（优先档不设门槛）
        val ocr = cloud.substringAfter("fun ocrIfEnabled(")
        assertTrue("过短阈值丢了", ocr.contains("localText.length >= 8"))
        assertTrue("阈值没有受 force 约束", ocr.contains("if (!force && localText.length >= 8)"))
        // 发请求前必须有预算预检（原来只在响应后记账，超预算仍会把文件传上云）
        assertTrue("云 OCR 缺预算预检", ocr.contains("budgetAllows(costTenthsOcrPages(1))"))
        assertTrue("文件解析缺预算预检", cloud.substringAfter("suspend fun parseDocIfEnabled")
            .contains("budgetAllows(costTenthsOcrPages(1))"))
    }

    /** 「优先」只对 DeepSeek 系列模型 + 云端支持的格式生效（用户给的范围限定） */
    @Test
    fun `优先云读的范围：DeepSeek 系列与支持的格式`() {
        assertTrue(DeepSeekCloud.isDeepSeekSeries("deepseek-chat"))
        assertTrue(DeepSeekCloud.isDeepSeekSeries("DeepSeek-V3.2"))
        assertTrue(DeepSeekCloud.isDeepSeekSeries("deepseek-ai/DeepSeek-R1"))
        assertFalse("别的模型不该被判成 DeepSeek", DeepSeekCloud.isDeepSeekSeries("qwen3.5-4b"))
        assertFalse(DeepSeekCloud.isDeepSeekSeries(""))
        // 云端文件解析支持 PDF/Word/PPT；xlsx 不支持（必须留端侧 POI，别传上去白花钱）
        assertTrue(DeepSeekCloud.CLOUD_DOC_EXTS.containsAll(listOf("pdf", "doc", "docx", "ppt", "pptx")))
        assertFalse("xlsx 云端不支持，不能进优先通道", DeepSeekCloud.CLOUD_DOC_EXTS.contains("xlsx"))
    }

    /** 云端逐页文本：多页带页标（改稿要能按页定位），单页不加噪音 */
    @Test
    fun `逐页文本带页标`() {
        val json = """
            {"object":"ocr.result","page_count":3,
             "pages":[
               {"index":0,"text":"第一页"},
               {"index":1,"text":"  "},
               {"index":2,"text":"第三页"}
             ]}
        """.trimIndent()
        val pages = DeepSeekCloud.parseOcrPages(json)!!
        assertEquals(2, pages.size)   // 空白页丢
        val text = DeepSeekCloud.numberPages(pages)
        assertTrue("缺第 1 页页标：$text", text.contains("[第1页]\n第一页"))
        assertTrue("缺第 2 页页标：$text", text.contains("[第2页]\n第三页"))
        assertEquals("单页不该加页标", "只有一页", DeepSeekCloud.numberPages(listOf("只有一页")))
    }

    /** 云端优先读文件的接线：parseDocText 必须先试云端，且 file_read_image 要带 sessionId */
    @Test
    fun `工具层云优先接线`() {
        val t = src("ai/ptl/ToolExecImpls.kt")
        val body = t.substringAfter("internal fun parseDocText")
        val cloudIdx = body.indexOf("cloudDocFirst(f, sessionId)")
        val localBranch = body.indexOf("when (ext)")
        assertTrue("parseDocText 没有先试云端", cloudIdx >= 0)
        assertTrue("云端尝试必须在端侧分支之前", cloudIdx < localBranch)
        assertTrue("file_read_image 没把 sessionId 传下去", t.contains("recognizeEnhanced(Graph.appContext, android.net.Uri.fromFile(img), sessionId)"))
        // PDF 走云端后仍要补逐页快照（视觉兜底不能因换文字来源而丢）
        assertTrue("云端 PDF 分支丢了快照", t.contains("snapshotPdfPages(Graph.appContext, f, snapDir)"))
    }

    /**
     * 2026-11-15 用户「为什么余额烧得这么快」：云端读文件按页计费，而 AI 一轮任务常把同一份
     * 文件读好几遍（重读/分段/24 轮工具循环）——**发请求（计费）之前必须先查缓存**，
     * 命中直接返回、不再上云。纯函数（键/序列化）断言见 DocParseCacheTest。
     */
    @Test
    fun `云端读文件先查缓存再上云计费`() {
        val cloud = src("ai/ptl/DeepSeekCloud.kt")
        val fn = cloud.substringAfter("suspend fun parseDocIfEnabled").substringBefore("private fun postOcr")
        val read = fn.indexOf("docCacheRead(context, cacheKey)")
        val post = fn.indexOf("postOcr(f, key)")
        assertTrue("parseDocIfEnabled 里没有查缓存", read > 0)
        assertTrue(
            "没把结果写回缓存（下次重读又会计费）",
            fn.contains("docCacheWrite(context, cacheKey, parsed)")
        )
        assertTrue("缓存必须在真正发请求（计费）之前", read in 1 until post)
        assertTrue("记账必须在真正上云那一支里", fn.indexOf("charge(\"ocr\"") > post)
        assertTrue("缓存没有上限（PDF 全文会一直堆）", cloud.contains("DOC_CACHE_MAX = 120"))
        assertTrue("上限没生效", cloud.contains("all.drop(DOC_CACHE_MAX).forEach { it.delete() }"))
    }

    /** 工具审计的来源标注必须含云（「（云）」），质量差 banner 不受来源影响 */
    @Test
    fun `工具审计标注云来源`() {
        val t = src("ai/ptl/ToolExecImpls.kt")
        assertTrue("web_search 审计缺「（云）」标注", t.contains("\"deepseek\" -> \"（云）\""))
        assertTrue("web_fetch 审计缺「（云端）」标注", t.contains("（云端）"))
        // 读文件：云端解析/云端 OCR 都要在工具行上标出来（用户要能看出这一份走了云）
        assertTrue("file_read_document 缺云端标注", t.contains("if (viaCloud) \"（云端）\""))
        assertTrue("file_read_image 缺云端标注", t.contains("if (viaCloud) \"（云端）\" else \"\")"))
    }
}
