package io.github.m0use001.planability.ai.office

import java.io.File

/**
 * 老版 PowerPoint（`.ppt`，PowerPoint 97-2003 的 OLE2 复合文档）的**纯文本抽取**。
 *
 * ## 为什么需要它
 *
 * 2026-11-05 用户报「AI 说无法读取 ppt」——他的微积分课件有一半是老 `.ppt`
 *（`4.1 introduction to area.ppt`、`8.1 Indeterminate Forms of 00 (1).ppt`…），
 * 而原有链路只认 `.pptx`（zip + XML）。大学课件里 .ppt 占比不小，不能只支持新版。
 *
 * 手机上没有任何能真正解析 PPT 版式的引擎，所以这里和 docx/pptx 预览同一取舍：
 * **只捞文字给人看**，不假装还原排版。
 *
 * ## 格式两层
 *
 * ① **OLE2 复合文档**（CFB）：文件像一个小文件系统，有 FAT / 目录项 / 迷你流。
 *    我们要找的流叫 `PowerPoint Document`。
 * ② **PowerPoint 二进制记录流**：8 字节头（ver/instance、recType、recLen，均小端），
 *    容器记录（ver 高半字节 = 0xF）里套子记录。文字落在
 *    `TextCharsAtom`(4000, UTF-16LE) 与 `TextBytesAtom`(4008, ANSI) 里；
 *    幻灯片内容在 `Slide`(1006) 容器内。
 *
 * 抽取策略：只下钻 `Slide` 容器，按文件顺序收集文本原子 → 每页一段。
 * 刻意**不进** MainMaster(1016)/Notes(1008)/SlideListWithText(4080)：
 * 前者是版式占位文字（"Click to edit Master text styles"），后者是全篇文字的重复索引。
 *
 * 边界：加密文档（目录里没有 `PowerPoint Document`，只有 EncryptedSummary）→ 抛异常，
 * 由调用方给出人话提示。宏（.pptm）走的是 pptx 的 zip 结构，不在这里。
 */
object PptBinary {

    class Slide(val texts: List<String>)

    private const val ENDOFCHAIN = 0xFFFFFFFEL
    private const val RT_SLIDE = 1006
    /** PPDrawing(1036)：OfficeArt 绘图层的外壳，**ver=0 但内部是逐条记录**（真机课件实测） */
    private const val RT_PP_DRAWING = 1036
    private const val RT_MAIN_MASTER = 1016
    private const val RT_TEXT_CHARS = 4000
    private const val RT_TEXT_BYTES = 4008
    private const val RT_SLIDE_LIST_WITH_TEXT = 4080

    /** 文件大小上限：课件通常 < 10MB，超过这个量级不像是幻灯片，直接拒绝免得 OOM */
    private const val MAX_FILE = 64L * 1024 * 1024

    /** 抽取整份 .ppt 的每页文字；失败抛异常（调用方兜底成人话）。 */
    fun extract(f: File): List<Slide> {
        require(f.length() in 1..MAX_FILE) { "文件大小异常（" + f.length() + " 字节）" }
        val bytes = f.readBytes()
        val stream = Cfb(bytes).readStream("PowerPoint Document")
            ?: throw IllegalStateException("不是有效的 .ppt（找不到 PowerPoint 文档流；加密文档也不支持）")
        // 先按 Slide 容器分页；一页文字都没有时回退按 MainMaster 分页——
        // LaTeX/PDF 转出来的课件常常"每页一个 master"，正文落在 master 里
        val bySlide = collect(stream, pageType = RT_SLIDE)
        if (bySlide.any { it.texts.isNotEmpty() }) return bySlide
        val byMaster = collect(stream, pageType = RT_MAIN_MASTER)
        return if (byMaster.any { it.texts.isNotEmpty() }) byMaster else bySlide
    }

    private fun collect(stream: ByteArray, pageType: Int): List<Slide> {
        val slides = ArrayList<Slide>()
        val cur = ArrayList<String>()
        var curOpen = false
        walk(stream, 0, stream.size, inSlide = false, depth = 0, pageType = pageType,
            onSlideStart = {
                // 上一页在离开时已提交；这里开新页
                curOpen = true
            },
            onText = { if (curOpen) cur.add(it) },
            onSlideEnd = {
                if (curOpen) {
                    slides.add(Slide(cur.toList()))
                    cur.clear()
                    curOpen = false
                }
            })
        if (curOpen) slides.add(Slide(cur.toList()))   // 容错：没有结束标记
        return slides
    }

    /**
     * 递归走记录树。容器判定 = ver(高 4 位) == 0xF；只有 Slide 容器内的文本原子才收。
     * [onSlideStart]/[onSlideEnd] 成对调用（离开 Slide 容器时提交一页）。
     */
    /** 递归走记录树（[PptSlides] 复用同一容器判定，见其 KDoc）；包内可见。 */
    internal fun walk(
        buf: ByteArray,
        start: Int,
        end: Int,
        inSlide: Boolean,
        depth: Int,
        pageType: Int,
        onSlideStart: () -> Unit,
        onText: (String) -> Unit,
        onSlideEnd: () -> Unit
    ) {
        if (depth > 64) return   // 异常文件防爆栈
        var p = start
        while (p + 8 <= end) {
            val verInst = le16(buf, p)
            val recType = le16(buf, p + 2)
            val recLen = le32(buf, p + 4)
            if (recLen < 0 || p + 8L + recLen > end) return
            val body = p + 8
            val bodyEnd = body + recLen
            // 容器判定：常规容器 ver 高半字节 = 0xF；**OfficeArt 包装（0xF000..0xF00F）
            // 例外**——它们的 ver 是 0，但内部还是逐条记录（0xF00D ClientTextbox 里装的
            // 正是 TextHeaderAtom + TextBytesAtom）。只按 ver 判会把整棵 OfficeArt 子树
            // 跳过：真机课件实测正是这样——文字全在 ClientTextbox 里，抽出来是空的。
            val isContainer = ((verInst shr 4) and 0xF) == 0xF ||
                recType in 0xF000..0xF00F || recType == RT_PP_DRAWING

            when {
                recType == pageType -> {
                    onSlideStart()
                    walk(buf, body, bodyEnd, inSlide = true, depth + 1, pageType,
                        onSlideStart, onText, onSlideEnd)
                    onSlideEnd()
                }
                recType == RT_SLIDE_LIST_WITH_TEXT -> Unit   // 全篇文字索引，跳过（防重复）
                inSlide && recType == RT_TEXT_CHARS -> {
                    val t = String(buf, body, recLen, Charsets.UTF_16LE)
                    cleanTexts(t).forEach(onText)
                }
                inSlide && recType == RT_TEXT_BYTES -> {
                    // ANSI 文本：英文课件是 cp1252；中文课件偶尔也用 BytesAtom 存 GBK。
                    // 有高位字节时试 GBK（Android/JVM 都自带），否则 ISO-8859-1（英文零风险）。
                    val hasHigh = (0 until recLen).any { (buf[body + it].toInt() and 0xFF) >= 0x80 }
                    val cs = if (hasHigh) runCatching { charset("GBK") }.getOrDefault(Charsets.ISO_8859_1)
                    else Charsets.ISO_8859_1
                    val t = String(buf, body, recLen, cs)
                    cleanTexts(t).forEach(onText)
                }
                isContainer -> walk(buf, body, bodyEnd, inSlide, depth + 1, pageType,
                    onSlideStart, onText, onSlideEnd)
            }
            p = bodyEnd
        }
    }

    /** 段落分隔：PPT 内部用 \r 分段、\u000B 软换行；其余控制字符丢掉。 */
    private fun cleanTexts(raw: String): List<String> =
        raw.replace('\u000B', '\n')
            .replace('\r', '\n')
            .replace("\u0000", "")
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    // ═══════════ 2026-11-12：课件正文在矢量图（WMF）里，从图上还原文字 ═══════════
    //
    // 用户「PPT 还是读不了，只能读到标题」的真机取证（数学课件 8 页）：
    // 文本原子只有页眉标题——第 3/4 页 0 字、第 5 页只剩 "Example 1 ... (1)"。
    // 正文（定理、例题公式）是 **9 个 WMF 矢量图**（Pictures 流里 0xF01B BLIP，
    // deflate 压缩）。WMF 的 EXTTEXTOUT 记录里带**真实字符**（实测能拿到
    // "Suppose that lim()lim()0."、"Cauchy's Mean Value Theorem" 等），只是按
    // 坐标逐段绘制、顺序打散。这里按 (y, x) 排序拼行还原。
    //
    // 页归属拿不到（该文件没有 pib 属性可映射），所以调用方按「图 N」标注来源，
    // 不装作是页内文字。

    /** 还原 Pictures 流里每张矢量图（WMF）的可见文字；每张图一条（无文字的图为空串跳过）。 */
    fun pictureTexts(f: File): List<String> {
        val bytes = runCatching { f.readBytes() }.getOrNull() ?: return emptyList()
        val stream = runCatching { Cfb(bytes).readStream("Pictures") }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>()
        var p = 0
        while (p + 8 <= stream.size) {
            val recType = le16(stream, p + 2)
            val recLen = le32(stream, p + 4)
            if (recLen < 0 || p + 8L + recLen > stream.size) break
            // 0xF018..0xF117 = OfficeArt BLIP 家族（EMF/WMF/PICT/JPEG/PNG/DIB…）
            if (recType in 0xF018..0xF117) {
                blipText(stream, p + 8, p + 8 + recLen)?.let { if (it.isNotBlank()) out.add(it) }
            }
            p += 8 + recLen
        }
        return out
    }

    private class Run(val y: Int, val x: Int, val text: String)

    /** BLIP → 文字：先剥 UID/文件头找到 deflate 起点（或裸 WMF），再走 WMF 记录。 */
    internal fun blipText(buf: ByteArray, start: Int, end: Int): String? {
        var z = -1
        val scanEnd = minOf(end, start + 80)
        var i = start
        while (i + 1 < scanEnd) {
            if ((buf[i].toInt() and 0xFF) == 0x78) {
                val b1 = buf[i + 1].toInt() and 0xFF
                if (b1 == 0x9C || b1 == 0x01 || b1 == 0xDA) { z = i; break }
            }
            i++
        }
        val data = if (z >= 0) inflate(buf, z, end) ?: return null
        else buf.copyOfRange(start, end)
        return wmfText(data)
    }

    private fun inflate(src: ByteArray, off: Int, end: Int): ByteArray? = runCatching {
        val inf = java.util.zip.Inflater()
        inf.setInput(src, off, end - off)
        val out = java.io.ByteArrayOutputStream(end - off)
        val tmp = ByteArray(8192)
        var guard = 0
        while (!inf.finished() && guard++ < 100_000) {
            val n = inf.inflate(tmp)
            if (n <= 0) break
            out.write(tmp, 0, n)
        }
        inf.end()
        out.toByteArray()
    }.getOrNull()

    /**
     * WMF 元文件 → 可见文字行。只认 WMF（头 type=1/headerWords=9）；
     * 文本记录 META_TEXTOUT(0x0521) / META_EXTTEXTOUT(0x0A32)。
     * ETO_GLYPH_INDEX 的 run（字形下标而非字符）跳过——解出来是乱码。
     */
    internal fun wmfText(b: ByteArray): String? {
        if (b.size < 24 || le16(b, 0) != 1 || le16(b, 2) != 9) return null
        var p = 18
        val runs = ArrayList<Run>()
        while (p + 6 <= b.size) {
            val words = le32(b, p)
            if (words < 3) break
            val fn = le16(b, p + 4)
            val recEnd = p + words * 2
            if (recEnd > b.size) break
            when (fn) {
                0x0521 -> {   // META_TEXTOUT: nChars, string, y, x
                    if (p + 8 <= recEnd) {
                        val n = le16(b, p + 6)
                        if (n in 1..2048 && p + 8 + n + 4 <= recEnd) {
                            runs.add(Run(le16(b, p + 8 + n), le16(b, p + 10 + n), decode(b, p + 8, n)))
                        }
                    }
                }
                0x0A32 -> {   // META_EXTTEXTOUT: y, x, count, options, [rect], string
                    if (p + 14 <= recEnd) {
                        val y = le16(b, p + 6)
                        val x = le16(b, p + 8)
                        val n = le16(b, p + 10)
                        val opt = le16(b, p + 12)
                        var off = p + 14
                        if (opt and 0x0006 != 0) off += 8   // ETO_OPAQUE / ETO_CLIPPED 带矩形
                        val glyph = (opt and 0x0010) != 0
                        if (!glyph && n in 1..2048 && off + n <= recEnd) {
                            runs.add(Run(y, x, decode(b, off, n)))
                        }
                    }
                }
            }
            p = recEnd
        }
        if (runs.isEmpty()) return null
        // 按绘制顺序还原：先按 y 分行（同行的 y 相差很小），行内按 x
        val sorted = runs.sortedWith(compareBy({ it.y }, { it.x }))
        val sb = StringBuilder()
        var lastY = Int.MIN_VALUE
        var lastText = ""
        for (r in sorted) {
            val t = r.text.trim()
            if (t.isEmpty() || t == lastText) continue
            if (lastY != Int.MIN_VALUE && kotlin.math.abs(r.y - lastY) > 16) sb.append('\n')
            else if (sb.isNotEmpty() && !sb.endsWith('\n')) sb.append(' ')
            sb.append(t)
            lastY = r.y
            lastText = t
        }
        return sb.toString().trim().ifEmpty { null }
    }

    /** WMF 字符串：有高位字节按 GBK 试（中文课件），否则 cp1252（英文零风险）。 */
    private fun decode(b: ByteArray, off: Int, n: Int): String {
        val hasHigh = (0 until n).any { (b[off + it].toInt() and 0xFF) >= 0x80 }
        val cs = if (hasHigh) runCatching { charset("GBK") }.getOrDefault(Charsets.ISO_8859_1)
        else Charsets.ISO_8859_1
        return String(b, off, n, cs)
    }

    private fun le16(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8) or
            ((b[i + 2].toInt() and 0xFF) shl 16) or ((b[i + 3].toInt() and 0xFF) shl 24)

    private fun leu32(b: ByteArray, i: Int): Long = le32(b, i).toLong() and 0xFFFFFFFFL

    // ═══════════════════════ OLE2 / CFB 容器读取 ═══════════════════════

    /** 只实现"按名字读一个流"所需的最小子集（FAT / 迷你 FAT / 目录项 / DIFAT 链）。 */
    /** CFB 读取器；[PptSlides] 读 Pictures 流要复用，包内可见。 */
    internal class Cfb(private val b: ByteArray) {

        private val sectorSize: Int
        private val miniSectorSize: Int
        private val miniCutoff: Int
        private val fat: IntArray
        private var dirStart: Long = 0
        private var rootStart: Long = 0
        private var rootSize: Long = 0
        private var miniFat: IntArray = IntArray(0)
        private var miniStream: ByteArray? = null

        init {
            require(b.size >= 512) { "文件太小，不是复合文档" }
            require(
                b[0].toInt() and 0xFF == 0xD0 && b[1].toInt() and 0xFF == 0xCF &&
                    b[2].toInt() and 0xFF == 0x11 && b[3].toInt() and 0xFF == 0xE0
            ) { "不是 OLE2 复合文档（.ppt 文件头不对）" }
            sectorSize = 1 shl le16(b, 0x1E)
            miniSectorSize = 1 shl le16(b, 0x20)
            miniCutoff = le32(b, 0x38)
            val numFat = le32(b, 0x2C)
            val firstDir = leu32(b, 0x30)
            dirStart = firstDir
            val firstMiniFat = leu32(b, 0x3C)
            val numMiniFat = le32(b, 0x40)
            val firstDifat = leu32(b, 0x44)
            val numDifat = le32(b, 0x48)

            // DIFAT：头里 109 个，之后按链继续（每扇区 127 个）
            val fatSectors = ArrayList<Long>()
            for (i in 0 until 109) {
                val v = leu32(b, 0x4C + i * 4)
                if (v <= ENDOFCHAIN) fatSectors.add(v)
            }
            var difat = firstDifat
            var guard = 0
            while (difat <= ENDOFCHAIN && fatSectors.size < numFat && guard++ < 10000) {
                val off = offsetOf(difat)
                if (off < 0 || off + sectorSize > b.size) break
                val per = sectorSize / 4 - 1
                for (i in 0 until per) {
                    val v = leu32(b, off + i * 4)
                    if (v <= ENDOFCHAIN) fatSectors.add(v)
                }
                difat = leu32(b, off + per * 4)
            }

            // FAT 本体
            val fatValues = ArrayList<Int>(fatSectors.size * (sectorSize / 4))
            fatSectors.forEach { s ->
                val off = offsetOf(s)
                if (off < 0 || off + sectorSize > b.size) return@forEach
                var i = 0
                while (i < sectorSize) {
                    fatValues.add(le32(b, off + i))
                    i += 4
                }
            }
            fat = fatValues.toIntArray()

            // 目录项 → 找根 + 逐个流的位置
            val dir = chainToBytes(firstDir, -1)
                ?: throw IllegalStateException("目录流损坏")
            var idx = 0
            var foundRoot = false
            while (idx + 128 <= dir.size) {
                val base = idx
                val type = dir[base + 0x42].toInt() and 0xFF
                if (type == 5) {   // root：迷你流的宿主
                    rootStart = leu32(dir, base + 0x74)
                    rootSize = leu32(dir, base + 0x78)
                    foundRoot = true
                }
                idx += 128
            }
            require(foundRoot) { "复合文档缺少根目录项" }

            // 迷你 FAT
            if (numMiniFat > 0 && firstMiniFat <= ENDOFCHAIN) {
                val mf = chainToBytes(firstMiniFat, -1)
                if (mf != null) {
                    val n = mf.size / 4
                    val arr = IntArray(n)
                    for (i in 0 until n) arr[i] = le32(mf, i * 4)
                    miniFat = arr
                }
            }
            miniStream = chainToBytes(rootStart, -1)?.let {
                // 迷你流按 rootSize 截断（FAT 链可能带出尾部垃圾）
                if (rootSize in 1 until it.size.toLong()) it.copyOf(rootSize.toInt()) else it
            }
        }

        /** 按名字读流；不存在返回 null。 */
        fun readStream(name: String): ByteArray? {
            // 目录流起点是头部的 firstDir（不是 root 链——root 链是迷你流宿主）
            val dir = chainToBytes(dirStart, -1) ?: return null
            var idx = 0
            var found: Pair<Long, Long>? = null
            while (idx + 128 <= dir.size) {
                val base = idx
                val nameLen = le16(dir, base + 0x40)
                val type = dir[base + 0x42].toInt() and 0xFF
                if (type == 2 && nameLen in 2..64) {
                    val n = String(dir, base, nameLen - 2, Charsets.UTF_16LE)
                    if (n == name) {
                        found = leu32(dir, base + 0x74) to leu32(dir, base + 0x78)
                        break
                    }
                }
                idx += 128
            }
            val (start, size) = found ?: return null
            return if (size < miniCutoff) readMini(start, size.toInt()) else
                chainToBytes(start, size.toInt())
        }

        /** 迷你流（< 4096 字节）：64 字节一个迷你扇区，链在迷你 FAT 里。 */
        private fun readMini(start: Long, size: Int): ByteArray {
            val ms = miniStream ?: return ByteArray(0)
            val out = java.io.ByteArrayOutputStream(size.coerceAtLeast(0))
            var s = start
            var guard = 0
            while (s <= ENDOFCHAIN && guard++ < 1_000_000) {
                val off = (s.toInt()) * miniSectorSize
                if (off < 0 || off >= ms.size) break
                val n = minOf(miniSectorSize, ms.size - off)
                out.write(ms, off, n)
                if (miniFat.isEmpty()) break
                val si = s.toInt()
                if (si < 0 || si >= miniFat.size) break
                s = miniFat[si].toLong() and 0xFFFFFFFFL
            }
            val arr = out.toByteArray()
            return if (size in 1 until arr.size) arr.copyOf(size) else arr
        }

        /** 沿 FAT 链读整条链；[limit] 给出时按它截断（-1 = 不限）。 */
        private fun chainToBytes(start: Long, limit: Int): ByteArray? {
            if (start > ENDOFCHAIN) return null
            val out = java.io.ByteArrayOutputStream()
            var s = start
            var guard = 0
            while (s <= ENDOFCHAIN && guard++ < 5_000_000) {
                val off = offsetOf(s)
                if (off < 0 || off + sectorSize > b.size) break
                out.write(b, off, sectorSize)
                if (limit in 1..out.size()) break
                val si = s.toInt()
                if (fat.isEmpty() || si < 0 || si >= fat.size) break
                s = fat[si].toLong() and 0xFFFFFFFFL
            }
            if (out.size() == 0) return null
            return if (limit in 1 until out.size()) out.toByteArray().copyOf(limit)
            else out.toByteArray()
        }

        /** 扇区号 → 文件内字节偏移（头部占一整扇区）。 */
        private fun offsetOf(sector: Long): Int = ((sector + 1) * sectorSize).toInt()
    }
}
