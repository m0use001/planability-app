package io.github.m0use001.planability.ai.office

import java.io.File

/**
 * **AI 能力体检**（2026-11-05 用户「一次踩一个坑」整改的第二块）。
 *
 * ## 为什么要有它
 *
 * 用户已经开始用工作台辅助学习，而历史事故是「用一次踩一个坑」：
 * `.ppt` 读不了、PDF 抽不出字、网页搜索返回不相关结果。这些都属于
 * **某条能力静默坏掉**，而且只有真正用到那一刻才会暴露——学到一半才发现，
 * 代价是整个学习节奏被打乱。
 *
 * 本体检把"坏没坏"变成**出门前 30 秒能点的按钮**：
 * - 文档格式：用**内置样本**（本对象现场生成的合法 docx/xlsx/pptx）走真实抽取管线，
 *   逐格式红/绿；
 * - 真实语料：扫「项目文件」里的文档，列出**读不出/只读到一点**的文件
 *   （这是最有价值的一项——用户自己的课件什么状况，一次列全）；
 * - 联网搜索：发一条**有标准答案的测试 query**，验证结果相关性（不是只看"有没有返回"）。
 *
 * OCR 与网络需要 Android 侧 API，由 UI 层补充（见 AiHealthPanel）。
 *
 * ## 设计约束
 *
 * - **只读**：不写用户文件、不改任何设置；
 * - **不走 AI**：体检本身不消耗 token，也不打扰模型；
 * - 结果可解释：每一项都带一句话说明"这意味着什么、该怎么办"。
 */
object AiHealthCheck {

    enum class Level { OK, WARN, FAIL, SKIP }

    /** 单项检查结果 */
    data class Item(
        val name: String,
        val level: Level,
        val detail: String,
        /** 下一步该怎么办（FAIL/WARN 时给，OK 时留空） */
        val advice: String = ""
    )

    data class Report(
        val items: List<Item>,
        val finishedAt: Long
    ) {
        val failCount: Int get() = items.count { it.level == Level.FAIL }
        val warnCount: Int get() = items.count { it.level == Level.WARN }
        val overall: Level get() = when {
            failCount > 0 -> Level.FAIL
            warnCount > 0 -> Level.WARN
            else -> Level.OK
        }
        fun summary(): String = when (overall) {
            Level.OK -> "全部正常（" + items.size + " 项）"
            Level.FAIL -> failCount.toString() + " 项不可用，" + warnCount + " 项需注意"
            Level.WARN -> warnCount.toString() + " 项需注意"
            Level.SKIP -> "未检查"
        }
    }

    // ══════════════ 内置样本：现场生成最小合法文档 ══════════════

    /**
     * 生成一份**最小合法 docx**（zip + word/document.xml）。
     * 不用仓库里放二进制样本：样本进版本库既占地方又会被当成"测试数据"误改，
     * 现场生成能保证永远与解析器期望的结构对齐。
     */
    internal fun sampleDocx(dir: File): File {
        val f = File(dir, "health-sample.docx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>" 
            )
            put(
                "word/document.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\">" +
                    "<w:body><w:p><w:r><w:t>AI 能力体检样本段落一</w:t></w:r></w:p>" +
                    "<w:p><w:r><w:t>第二段：用于验证段落编号与文字抽取</w:t></w:r></w:p></w:body></w:document>"
            )
        }
        return f
    }

    /** 最小合法 pptx（zip + 1 张幻灯片） */
    internal fun samplePptx(dir: File): File {
        val f = File(dir, "health-sample.pptx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
            )
            put(
                "ppt/slides/slide1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><p:sld xmlns:p=\"http://schemas.openxmlformats.org/presentationml/2006/main\" " +
                    "xmlns:a=\"http://schemas.openxmlformats.org/drawingml/2006/main\"><p:cSld><p:spTree><p:sp><p:txBody>" +
                    "<a:p><a:r><a:t>体检样本幻灯片标题</a:t></a:r></a:p>" +
                    "</p:txBody></p:sp></p:spTree></p:cSld></p:sld>"
            )
        }
        return f
    }

    /** 最小合法 xlsx（zip + 共享字符串 + 一张表） */
    internal fun sampleXlsx(dir: File): File {
        val f = File(dir, "health-sample.xlsx")
        java.util.zip.ZipOutputStream(f.outputStream()).use { z ->
            fun put(name: String, body: String) {
                z.putNextEntry(java.util.zip.ZipEntry(name))
                z.write(body.toByteArray(Charsets.UTF_8))
                z.closeEntry()
            }
            put(
                "[Content_Types].xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>"
            )
            put(
                "xl/sharedStrings.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><sst xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" count=\"2\">" +
                    "<si><t>体检表格标题</t></si><si><t>第二行数据</t></si></sst>"
            )
            put(
                "xl/worksheets/sheet1.xml",
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?><worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">" +
                    "<sheetData><row r=\"1\"><c r=\"A1\" t=\"s\"><v>0</v></c></row>" +
                    "<row r=\"2\"><c r=\"A2\" t=\"s\"><v>1</v></c></row></sheetData></worksheet>"
            )
        }
        return f
    }

    /**
     * 跑「格式样本」这一组：每个格式喂内置样本，检查抽取结果是否含**预期文字**。
     *
     * 为什么要校验内容而不是"没抛异常"：最危险的失败是**静默返回空/乱码**
     * （PDF 空串、bogus xlsx 乱码都踩过）。所以每个样本都埋了一个特征词，
     * 抽不到就算 FAIL。
     */
    fun checkFormats(dir: File, parse: (File) -> String): List<Item> {
        if (!dir.isDirectory) dir.mkdirs()
        val cases = listOf(
            Triple("docx", sampleDocx(dir), "体检样本段落一"),
            Triple("xlsx", sampleXlsx(dir), "体检表格标题"),
            Triple("pptx", samplePptx(dir), "体检样本幻灯片标题")
        )
        return cases.map { (ext, file, marker) ->
            val r = runCatching { parse(file) }
            val text = r.getOrNull().orEmpty()
            when {
                r.isFailure -> Item(
                    "$ext 读取", Level.FAIL,
                    "抽取抛异常：" + (r.exceptionOrNull()?.message ?: "未知"),
                    "该格式当前不可用；先用截图/OCR 或换格式"
                )
                !text.contains(marker) -> Item(
                    "$ext 读取", Level.FAIL,
                    "样本里应出现「" + marker + "」，实际读到 " + text.length + " 字：" + text.take(60),
                    "解析器可能坏了（回归测试没跑到），先别依赖这个格式"
                )
                else -> Item("$ext 读取", Level.OK, "样本抽取正常（" + text.length + " 字）")
            }
        }
    }

    /**
     * 跑「真实语料」这一组：扫目录里所有文档，报告**读不出/读到极少**的。
     *
     * [maxFiles] 防止用户在超大资料夹上误点后等太久；超出时在 detail 里说明已截断。
     */
    fun checkCorpus(
        files: List<File>,
        parse: (File) -> String,
        maxFiles: Int = 40
    ): Item {
        val docs = files.filter { it.isFile && it.extension.lowercase() in DOC_EXTS_LOCAL }
        if (docs.isEmpty()) return Item("真实语料", Level.SKIP, "项目文件里没有可解析的文档")
        val scan = docs.take(maxFiles)
        val bad = mutableListOf<String>()
        var warn = 0
        scan.forEach { f ->
            val text = runCatching { parse(f) }.getOrDefault("")
            val visible = text.count { it.isLetterOrDigit() }
            when {
                text.isBlank() -> bad += f.name + "（读不出）"
                f.length() / 1024 >= 40 && visible < 80 -> {
                    warn++
                    bad += f.name + "（只读到 " + visible + " 字）"
                }
            }
        }
        val tail = if (docs.size > maxFiles) "；仅检查前 " + maxFiles + " 份（共 " + docs.size + "）" else ""
        return when {
            bad.isEmpty() -> Item("真实语料", Level.OK, docs.size.toString() + " 份文档都能正常读取")
            else -> Item(
                "真实语料", if (bad.size == scan.size) Level.FAIL else Level.WARN,
                bad.size.toString() + " 份有问题：" + bad.take(6).joinToString("；") +
                    (if (bad.size > 6) "…" else "") + tail,
                "读不出的多为整页图片/扫描件/未支持格式（如 .doc、.wps）：截图后走 OCR，或先转成 PDF/docx"
            )
        }
    }

    private val DOC_EXTS_LOCAL = setOf("docx", "xlsx", "pptx", "ppt", "pdf", "txt", "md", "csv")
    /**
     * ⑤ 附件链路（2026-11-09 夜）：对聊天附件里最近的文档跑**真实解析**。
     * 附件是用户材料的主入口（英语课几次报障全在附件），解析器换库（POI/PDFBox）
     * 之后这里就是"附件读不读得了"的第一手证据——样例再绿也不代表用户文件绿。
     */
    fun checkAttachments(files: List<File>, parse: (File) -> String): Item {
        val docs = files.filter { it.isFile && it.extension.lowercase() in DOC_EXTS_LOCAL }
        if (docs.isEmpty())
            return Item("附件链路", Level.SKIP, "attachments 里没有可解析文档（发一份 docx/pdf 后再体检即可覆盖）")
        val scan = docs.take(8)
        val bad = mutableListOf<String>()
        var scanned = 0
        scan.forEach { f ->
            val text = runCatching { parse(f) }.getOrDefault("")
            when {
                // 扫描件：体检只做"文字层探测"，不在这里跑整份 OCR
                // （几页就要几十秒；体检的承诺是 30 秒出结论，不是等一次全量识别）
                text.startsWith("[扫描件") -> scanned++
                text.isBlank() || text.startsWith("[抽取失败") || text.startsWith("[PDF 未抽取") ->
                    bad += f.name
            }
        }
        return when {
            bad.isEmpty() && scanned == 0 ->
                Item("附件链路", Level.OK, "最近 ${scan.size} 份附件全部解析出文本")
            bad.isEmpty() -> Item(
                "附件链路", Level.OK,
                "最近 ${scan.size} 份附件可解析；其中 $scanned 份是扫描件（无文字层）",
                "扫描件在会话里读时会自动走图片 OCR（慢但能读）"
            )
            bad.size == scan.size -> Item(
                "附件链路", Level.FAIL,
                "最近 ${scan.size} 份附件全部解析失败：" + bad.take(4).joinToString("、"),
                "对应会话里可让 AI 改走 file_read_image 截图 OCR；持续失败说明解析器坏了"
            )
            else -> Item(
                "附件链路", Level.WARN,
                "解析失败：" + bad.joinToString("、") + "（其余正常）",
                "失败的多为扫描件/加密文档；持续失败再按 FAIL 处理"
            )
        }
    }

    /**
     * ⑥ 网页抓取活体：抓一个最稳定的目标并**校验内容**（不是只看有没有返回）。
     * 判据能红：断网/被代理注入时 fetch 返回 null 或内容对不上。
     */
    fun checkWebFetch(fetch: (String) -> String?): Item {
        val body = runCatching { fetch("https://example.com/") }.getOrNull()
        return when {
            body == null || body.length < 60 -> Item(
                "网页抓取", Level.FAIL,
                "抓不到 example.com（${body?.length ?: 0} 字）——网络或抓取通道不可用",
                "会话里 web_fetch / web_search 都会受影响；先查网络与加速器"
            )
            !body.contains("Example Domain") -> Item(
                "网页抓取", Level.WARN,
                "抓到 ${body.length} 字但内容对不上（可能被代理注入/劫持）",
                "涉及网页引用的结论先人工核对原网址"
            )
            else -> Item("网页抓取", Level.OK, "抓取正常（${body.length} 字，内容校验通过）")
        }
    }

    /**
     * 云搜索活体（2026-11-14 云能力）：开关关 = INFO 跳过（不是问题，是未启用）；
     * 开 = 真调一次 DeepSeek Search API，判据是结果里出现 example.com 的痕迹——
     * 与搜索 probe 同一条纪律：判据在期望关键词，不拿被测件自证。
     */
    fun judgeCloudSearch(results: List<Pair<String, String>>, enabled: Boolean): Item = when {
        !enabled -> Item("云搜索兜底", Level.SKIP, "未开启（默认关闭，开在模型管理页「云端兜底」）")
        results.isEmpty() -> Item(
            "云搜索兜底", Level.FAIL,
            "开关开着但探测无结果——key/预算/网络任一不满足都会走降级链",
            "查：① DeepSeek 供应商是否配了 key；② 当日预算是否已用尽；③ 网络"
        )
        results.any { (t, u) -> t.contains("example", true) || u.contains("example.com", true) } ->
            Item("云搜索兜底", Level.OK, "探测通过（结果含 example.com）")
        else -> Item(
            "云搜索兜底", Level.WARN,
            "有 ${results.size} 条结果但内容对不上——结果可能不是期望网页",
            "涉及事实的结论先人工核对"
        )
    }


    // ══════════════ 搜索活体探测 ══════════════

    /**
     * 一组**有客观正确答案**的探测 query（2026-11-08 第三次报障后重做）。
     *
     * 旧版只有一条「全国大学英语四六级考试官网」——那是**单词品牌 query**，
     * 恰恰是搜索引擎从不退化的那一类：引擎退化时它照样返回中国教育考试网，
     * 于是体检永远绿、用户永远红（实网复核确认：◆3 三条 query 全废的同一天，这条 probe 依旧 OK）。
     * **体检必须覆盖会坏的那类输入**，否则它只是装饰。
     *
     * 三条各代表一种形状：
     * - 官网直达（单词品牌）→ 确认"通道通"；
     * - 多词内容页 → 引擎退化成首词站点匹配时立刻红（本次根因）；
     * - 菜谱正文 → 360 富摘要（richsum）模板，解析正则曾整页返回 0 条。
     *
     * 判据是**结果里有没有该出现的关键词**，绝不拿 WebSearch.isGoodEnough 当判据——
     * 那是被测件自证：闸一瞎，用例跟着一起绿（旧评估用例正是这么写的）。
     */
    data class SearchProbe(val label: String, val query: String, val expect: List<String>)

    val SEARCH_PROBES = listOf(
        SearchProbe("官网直达", "全国大学英语四六级考试官网", listOf("neea.edu.cn", "四、六级", "四六级", "CET")),
        SearchProbe("多词内容页", "◆3 后◆书 剧情", listOf("后◆书")),
        SearchProbe("菜谱正文", "番茄炒蛋 家常做法", listOf("炒蛋", "西红柿"))
    )

    /**
     * 评估探测结果：每条 probe 只要有一条结果的标题/链接含期望关键词就算通。
     * 纯函数（不联网），单测锁住判定逻辑。
     */
    fun judgeSearchProbes(results: List<Pair<SearchProbe, List<Pair<String, String>>>>): Item {
        if (results.isEmpty()) return Item(
            "联网搜索", Level.FAIL, "没有执行任何探测", "体检脚本自身出错，把这条发给开发者"
        )
        val empty = results.filter { it.second.isEmpty() }
        if (empty.size == results.size) return Item(
            "联网搜索", Level.FAIL, "全部 " + empty.size + " 组探测无结果",
            "检查网络/代理；搜索源可能整体不可用"
        )
        val failed = results.filter { (p, hits) ->
            hits.none { (title, url) -> p.expect.any { k -> title.contains(k, true) || url.contains(k, true) } }
        }
        return when {
            failed.isEmpty() -> Item(
                "联网搜索", Level.OK,
                results.size.toString() + "/" + results.size + " 组探测通过（官网 / 多词内容页 / 菜谱正文）"
            )
            else -> Item(
                "联网搜索", Level.WARN,
                failed.size.toString() + "/" + results.size + " 组失败：" + failed.joinToString("、") { (p, hits) ->
                    p.label + (if (hits.isEmpty()) "（无结果）" else "（首条：" + hits.first().first.take(20) + "）")
                },
                "失败的组就是这类 query 现在拿不到内容页的证据；重要事实改用 web_fetch 抓已知网址"
            )
        }
    }

    /**
     * 降级留痕的可见化：tools_audit 里 web_search 的 `detail` 带「（质量差）」就是一次降级。
     * 把"等用户来抱怨"变成"面板上能看见的数字"——用户开口之前我们自己先知道。
     */
    fun judgeDegradedHistory(count: Int, days: Int): Item = if (count == 0) Item(
        "搜索降级史", Level.OK, "最近 " + days + " 天没有降级记录"
    ) else Item(
        "搜索降级史", Level.WARN,
        "最近 " + days + " 天降级 " + count + " 次（明细见 自动化 → 工具审计）",
        "降级时结果会带「质量差」标记；频繁出现说明搜索源在变，按上面的探测组定位是哪类 query"
    )
}
