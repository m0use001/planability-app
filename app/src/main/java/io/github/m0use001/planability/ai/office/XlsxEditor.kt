package io.github.m0use001.planability.ai.office

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * 手机端 xlsx 改稿器（2026-10-20）。
 *
 * ## 为什么需要它
 *
 * 改 Excel 一直只有电脑端一条路（`PcBridge.patchXlsx` → Node 的 exceljs）：
 * 电脑不在线就彻底改不了，而"改一个单元格"本来是手机完全做得到的事。
 *
 * ## 做法：**外科手术式改 XML**，不是"读进内存再整份重写"
 *
 * xlsx 是一包 XML，单元格就是 `<c r="B3"><v>13000</v></c>`。
 * 整份重新生成会丢掉用户原有的样式、合并单元格、图表、条件格式——
 * 这些恰恰是用户不舍得丢的东西。所以这里只动被点名的单元格/行，
 * 其余 zip 条目**原样按字节写回**（连顺序都不变）。
 *
 * ## 已知边界（写清楚，免得用户以为样式也保住了）
 *
 * - `delRow` 会把下面的行整体上移（行号与单元格引用一起减一）。
 *   引用了被删行的**公式**不会自动改——这是 Excel 自己也做不到的事。
 * - 不动 `sharedStrings.xml`：新写入的字符串一律 `t="inlineStr"`，天然不冲突。
 * - 不重算 `<dimension>`：Excel/WPS 自己会修，留旧值也不影响打开。
 */
object XlsxEditor {

    data class Result(
        val applied: List<String>,
        val failed: List<String>,
        /** 真实改到的 sheet 名，回执里要说清楚 */
        val sheet: String
    )

    private const val Q = "\""

    // ---------- 对外 ----------

    fun apply(src: File, ops: JSONArray, out: File): Result {
        val entries = readEntries(src)
        val wanted = if (ops.length() > 0) ops.optJSONObject(0)?.optString("sheet").orEmpty() else ""
        val resolved = resolveSheet(entries, wanted)
        val path = resolved?.first ?: "xl/worksheets/sheet1.xml"
        val sheetName = resolved?.second ?: "Sheet1"
        var xml = entries[path]?.toString(Charsets.UTF_8)
            ?: throw IllegalStateException("不是有效的 xlsx（缺 $path）")

        val applied = mutableListOf<String>()
        val failed = mutableListOf<String>()
        for (i in 0 until ops.length()) {
            val op = ops.optJSONObject(i)
            if (op == null) { failed += "第 ${i + 1} 项不是对象"; continue }
            when (op.optString("op").lowercase()) {
                "set" -> {
                    val ref = cellRefOf(op)
                    if (ref == null) { failed += "第 ${i + 1} 项缺 cell/row/col"; continue }
                    val v = valueOf(op.opt("value"))
                    xml = setCell(xml, ref, v)
                    applied += "把 $ref 设为「$v」"
                }
                "addrow", "add_row", "add" -> {
                    val arr = op.optJSONArray("values")
                    if (arr == null) { failed += "第 ${i + 1} 项 addRow 缺 values"; continue }
                    val values = (0 until arr.length()).map { valueOf(arr.opt(it)) }
                    val rowNo = maxRow(xml) + 1
                    xml = addRow(xml, rowNo, values)
                    applied += "末尾第 $rowNo 行加了一条（${values.size} 列）"
                }
                "delrow", "del_row", "del" -> {
                    val row = op.optInt("row", -1)
                    if (row <= 0) { failed += "第 ${i + 1} 项 delRow 缺 row"; continue }
                    if (!hasRow(xml, row)) { failed += "第 $row 行不存在"; continue }
                    xml = deleteRow(xml, row)
                    // 删行后引用要跟着走（Excel 也是这么干的）：
                    // 本表里的本地引用整体上移，被删行上的引用直接 #REF!；
                    // 其它工作表里带「本表名!」前缀的引用同样处理。
                    xml = shiftMerges(xml, row)
                    xml = adjustSheetFormulas(xml, row, sheetName, onlyThisSheet = false)
                    // 条件格式/数据验证的选区与规则公式同样跟随
                    xml = shiftSqrefs(xml, row)
                    xml = adjustRuleFormulas(xml, row)
                    entries.toList().forEach { (k, v) ->
                        if (k == path || !k.startsWith("xl/worksheets/") || !k.endsWith(".xml")) return@forEach
                        val text = v.toString(Charsets.UTF_8)
                        val fixed = adjustSheetFormulas(text, row, sheetName, onlyThisSheet = true)
                        if (fixed != text) entries[k] = fixed.toByteArray(Charsets.UTF_8)
                    }
                    applied += "删掉第 $row 行（下面的行与公式引用一起上移）"
                }
                else -> failed += "不认识的 op：「${op.optString("op")}」"
            }
        }
        entries[path] = xml.toByteArray(Charsets.UTF_8)
        writeEntries(entries, out)
        return Result(applied, failed, sheetName)
    }

    /**
     * 读成"二维表"用于预览：共享字符串表在这里解析——
     * xlsx 的文本多半不在 sheet 里，而是按下标引用 `t="s"`。
     */
    fun readGrid(file: File, limit: Int = 500): Pair<String, List<List<String>>> {
        val entries = readEntries(file)
        val resolved = resolveSheet(entries, "") ?: return "Sheet1" to emptyList()
        val shared = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)?.let { xml ->
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(xml).map { si ->
                Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .findAll(si.groupValues[1]).joinToString("") { unescape(it.groupValues[1]) }
            }.toList()
        } ?: emptyList()
        val xml = entries[resolved.first]?.toString(Charsets.UTF_8) ?: return resolved.second to emptyList()
        // 数字格式：预览不能把 52% 显示成 0.52、把日期显示成 46319
        val xfs = parseCellFormats(entries["xl/styles.xml"]?.toString(Charsets.UTF_8))
        val rows = mutableListOf<List<String>>()
        for (rm in Regex("<row\\b[^>]*>(.*?)</row>", RegexOption.DOT_MATCHES_ALL).findAll(xml)) {
            if (rows.size >= limit) break
            val cells = mutableListOf<String>()
            for (cm in Regex("<c\\b([^>]*?)(?:/>|>(.*?)</c>)", RegexOption.DOT_MATCHES_ALL)
                .findAll(rm.groupValues[1])) {
                val attrs = cm.groupValues[1]
                val inner = cm.groupValues.getOrElse(2) { "" }
                val col = Regex("\\br=\"([A-Z]+)\\d+\"").find(attrs)?.groupValues?.get(1)
                    ?.let { colIndex(it) } ?: cells.size
                while (cells.size < col) cells += ""
                val type = Regex("\\bt=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1) ?: ""
                val styleIdx = Regex("\\bs=\"(\\d+)\"").find(attrs)?.groupValues?.get(1)?.toInt()
                val fmt = styleIdx?.let { xfs.getOrNull(it) } ?: ""
                val v = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(inner)?.groupValues?.get(1)
                val inline = Regex("<t[^>]*>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                    .find(inner)?.groupValues?.get(1)
                cells += when {
                    type == "s" -> v?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                    type == "inlineStr" -> inline?.let { unescape(it) } ?: ""
                    else -> v?.let { formatNumber(unescape(it), fmt) }
                        ?: inline?.let { unescape(it) } ?: ""
                }
            }
            rows += cells
        }
        return resolved.second to rows
    }

    /** 解析 `cellXfs` 的序号 → 数字格式代码（预览与 AI 读文件共用） */
    fun parseCellFormats(stylesXml: String?): List<String> {
        if (stylesXml == null) return emptyList()
        val fmts = Regex("<numFmt\\b[^>]*numFmtId=\"(\\d+)\"[^>]*formatCode=\"([^\"]*)\"")
            .findAll(stylesXml).associate { it.groupValues[1].toInt() to it.groupValues[2] }
        val block = Regex("<cellXfs[^>]*>(.*?)</cellXfs>", RegexOption.DOT_MATCHES_ALL)
            .find(stylesXml)?.groupValues?.get(1) ?: ""
        return Regex("<xf\\b[^>]*?numFmtId=\"(\\d+)\"").findAll(block)
            .map { it.groupValues[1].toInt() }.toList()
            .map { id -> fmts[id] ?: builtinFormat(id) }
    }

    /** Excel 内置数字格式里我们关心的那几个（14~22 日期，9/10 百分比） */
    private fun builtinFormat(id: Int): String = when (id) {
        in 14..22, 45, 46, 47 -> "yyyy-mm-dd"
        9, 10 -> "0.0%"
        4, 37, 38, 39, 40 -> "#,##0.00"
        else -> ""
    }

    /** 按格式把原始数值变成人能读的文本（只处理百分比/日期/千分位这三类） */
    fun formatNumber(raw: String, code: String): String {
        if (code.isEmpty()) return raw
        val d = raw.toDoubleOrNull() ?: return raw
        if (code.contains("%")) {
            val decimals = if (code.contains("0.0")) 1 else 0
            return String.format("%." + decimals + "f%%", d * 100)
        }
        if (code.contains("#,##0")) {
            val decimals = when {
                code.contains(".00") -> 2
                code.contains(".0") -> 1
                else -> 0
            }
            return String.format("%,." + decimals + "f", d)
        }
        if (code.contains("y") && code.contains("d")) {
            return runCatching {
                java.time.LocalDate.of(1899, 12, 30).plusDays(d.toLong()).toString()
            }.getOrDefault(raw)
        }
        return raw
    }

    // ---------- 单元格操作 ----------

    private fun setCell(xml: String, ref: String, value: String): String {
        val rowNo = rowNumberOf(ref)
        val style = cellStyle(xml, ref)
        val isNum = value.trim().isNotEmpty() && value.trim().toDoubleOrNull() != null
        val attrs = StringBuilder("r=" + Q + ref + Q)
        if (style != null) attrs.append(" s=" + Q + style + Q)
        if (!isNum) attrs.append(" t=" + Q + "inlineStr" + Q)
        val cell = when {
            value.isEmpty() -> "<c " + attrs + "/>"
            isNum -> "<c " + attrs + "><v>" + value.trim() + "</v></c>"
            else -> "<c " + attrs + "><is><t xml:space=" + Q + "preserve" + Q + ">" +
                escape(value) + "</t></is></c>"
        }

        val existing = Regex(
            "<c\\b[^>]*\\br=" + Q + Regex.escape(ref) + Q + "[^>]*/>|<c\\b[^>]*\\br=" +
                Q + Regex.escape(ref) + Q + "[^>]*>.*?</c>",
            RegexOption.DOT_MATCHES_ALL
        )
        if (existing.containsMatchIn(xml)) return xml.replace(existing, cell)

        val rowRe = Regex(
            "<row\\b([^>]*\\br=" + Q + rowNo + Q + "[^>]*)>(.*?)</row>",
            RegexOption.DOT_MATCHES_ALL
        )
        val rm = rowRe.find(xml)
        if (rm != null) {
            val newInner = insertCellInRow(rm.groupValues[2], ref, cell)
            return xml.replace(rm.value, "<row" + rm.groupValues[1] + ">" + newInner + "</row>")
        }
        return insertRow(xml, rowNo, cell)
    }

    private fun insertCellInRow(inner: String, ref: String, cell: String): String {
        val targetCol = colNumberOf(ref)
        val cRe = Regex("<c\\b[^>]*?\\br=\"([A-Z]+)\\d+\"[^>]*?(?:/>|>.*?</c>)", RegexOption.DOT_MATCHES_ALL)
        for (m in cRe.findAll(inner)) {
            val col = colIndex(m.groupValues[1])
            if (col > targetCol) {
                return inner.substring(0, m.range.first) + cell + inner.substring(m.range.first)
            }
        }
        return inner + cell
    }

    private fun insertRow(xml: String, rowNo: Int, cell: String): String {
        val row = "<row r=" + Q + rowNo + Q + ">" + cell + "</row>"
        val rows = Regex("<row\\b[^>]*\\br=\"(\\d+)\"").findAll(xml).toList()
        val before = rows.firstOrNull { it.groupValues[1].toInt() > rowNo }
        if (before != null) {
            val idx = before.range.first
            return xml.substring(0, idx) + row + xml.substring(idx)
        }
        val end = xml.indexOf("</sheetData>")
        return if (end >= 0) xml.substring(0, end) + row + xml.substring(end) else xml
    }

    private fun addRow(xml: String, rowNo: Int, values: List<String>): String {
        val cells = values.mapIndexed { i, v ->
            val ref = colName(i) + rowNo
            val isNum = v.trim().isNotEmpty() && v.trim().toDoubleOrNull() != null
            when {
                v.isEmpty() -> "<c r=" + Q + ref + Q + "/>"
                isNum -> "<c r=" + Q + ref + Q + "><v>" + v.trim() + "</v></c>"
                else -> "<c r=" + Q + ref + Q + " t=" + Q + "inlineStr" + Q + "><is><t xml:space=" +
                    Q + "preserve" + Q + ">" + escape(v) + "</t></is></c>"
            }
        }.joinToString("")
        val row = "<row r=" + Q + rowNo + Q + ">" + cells + "</row>"
        val end = xml.indexOf("</sheetData>")
        return if (end >= 0) xml.substring(0, end) + row + xml.substring(end) else xml
    }

    /** 删行并把下面的行整体上移（行号 + 单元格引用一起减一） */
    private fun deleteRow(xml: String, rowNo: Int): String {
        var out = xml.replace(
            Regex(
                "<row\\b[^>]*\\br=" + Q + rowNo + Q + "[^>]*/>|<row\\b[^>]*\\br=" +
                    Q + rowNo + Q + "[^>]*>.*?</row>",
                RegexOption.DOT_MATCHES_ALL
            ),
            ""
        )
        out = Regex("(<row\\b[^>]*\\br=\")(\\d+)(\")").replace(out) { m ->
            val n = m.groupValues[2].toInt()
            m.groupValues[1] + (if (n > rowNo) n - 1 else n) + m.groupValues[3]
        }
        out = Regex("(<c\\b[^>]*\\br=\"[A-Z]+)(\\d+)(\")").replace(out) { m ->
            val n = m.groupValues[2].toInt()
            m.groupValues[1] + (if (n > rowNo) n - 1 else n) + m.groupValues[3]
        }
        return out
    }

    private fun hasRow(xml: String, row: Int): Boolean =
        Regex("<row\\b[^>]*\\br=" + Q + row + Q).containsMatchIn(xml)

    // ---------- 删行后的引用修正（公式 / 合并单元格） ----------
    //
    // 2026-10-20 用户要求：删行后引用要跟着走，不能留下指向错行或 #REF! 的公式。
    // 规则与 Excel 一致：
    // - 删掉第 k 行后，指向 k 以下的引用整体减一；
    // - 指向第 k 行本身的单个引用变 #REF!；
    // - 区间（A1:B5）跨越 k 时收缩一行，收缩到空则整段 #REF!；
    // - 其它工作表里带「本表名!」前缀的引用同样处理（不带前缀的属于别的表，不动）。

    private const val REF = "\\$?[A-Z]{1,3}\\$?\\d+(?::\\$?[A-Z]{1,3}\\$?\\d+)?"

    private fun adjustSheetFormulas(
        xml: String,
        deleted: Int,
        sheetName: String?,
        onlyThisSheet: Boolean
    ): String {
        val re = if (onlyThisSheet && sheetName != null) {
            val pre = "(?:'" + Regex.escape(sheetName) + "'!|" + Regex.escape(sheetName) + "!)"
            Regex(pre + "(" + REF + ")")
        } else {
            // 本表的本地引用：前面不能是 "!"（那是别的表的引用）
            Regex("(?<!!)(" + REF + ")")
        }
        return Regex("<f\\b([^>]*)>(.*?)</f>", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            "<f" + m.groupValues[1] + ">" +
                adjustText(m.groupValues[2], deleted, sheetName, onlyThisSheet) + "</f>"
        }
    }

    /** 对一段文本里的引用做删行修正（公式与条件格式的 `<formula>` 共用） */
    private fun adjustText(text: String, deleted: Int, sheetName: String?, onlyThisSheet: Boolean): String {
        val re = if (onlyThisSheet && sheetName != null) {
            val pre = "(?:'" + Regex.escape(sheetName) + "'!|" + Regex.escape(sheetName) + "!)"
            Regex(pre + "(" + REF + ")")
        } else {
            Regex("(?<!!)(" + REF + ")")
        }
        return re.replace(text) { r ->
            val ref = r.groupValues[1]
            r.value.substring(0, r.value.length - ref.length) + shiftRefs(ref, deleted)
        }
    }

    /**
     * 条件格式 / 数据验证的选区（`sqref="A1:B5 A7"`）跟着行号走，
     * 否则删行后规则还在、却套在错行上——用户看不见错误，只看到颜色标错。
     */
    private fun shiftSqrefs(xml: String, k: Int): String =
        Regex("(sqref=\")([^\"]*)(\")").replace(xml) { m ->
            val refs = m.groupValues[2].split(" ").filter { it.isNotBlank() }
                .map { shiftRange(it.trim(), k) }.filter { it != "#REF!" }
            m.groupValues[1] + refs.joinToString(" ") + m.groupValues[3]
        }

    /** 条件格式/数据验证里的 `<formula>` 也要跟着改（它们同样按单元格引用判断） */
    private fun adjustRuleFormulas(xml: String, k: Int): String =
        Regex("<formula>(.*?)</formula>", RegexOption.DOT_MATCHES_ALL).replace(xml) { m ->
            "<formula>" + adjustText(m.groupValues[1], k, null, false) + "</formula>"
        }

    private fun shiftRefs(ref: String, k: Int): String =
        if (ref.contains(":")) shiftRange(ref, k) else (shiftSingle(ref, k) ?: ref)

    private fun shiftSingle(ref: String, k: Int): String? {
        val m = Regex("(\\$?)([A-Z]{1,3})(\\$?)(\\d+)").matchEntire(ref) ?: return null
        val row = m.groupValues[4].toInt()
        return when {
            row == k -> "#REF!"
            row > k -> m.groupValues[1] + m.groupValues[2] + m.groupValues[3] + (row - 1)
            else -> ref
        }
    }

    private fun shiftRange(ref: String, k: Int): String {
        val parts = ref.split(":")
        if (parts.size != 2) return shiftSingle(ref, k) ?: ref
        val a = Regex("(\\$?)([A-Z]{1,3})(\\$?)(\\d+)").matchEntire(parts[0]) ?: return ref
        val b = Regex("(\\$?)([A-Z]{1,3})(\\$?)(\\d+)").matchEntire(parts[1]) ?: return ref
        var r1 = a.groupValues[4].toInt()
        var r2 = b.groupValues[4].toInt()
        when {
            k < r1 -> { r1 -= 1; r2 -= 1 }
            k <= r2 -> r2 -= 1
        }
        if (r2 < r1) return "#REF!"
        return a.groupValues[1] + a.groupValues[2] + a.groupValues[3] + r1 + ":" +
            b.groupValues[1] + b.groupValues[2] + b.groupValues[3] + r2
    }

    /** 合并单元格跟着行号走；被删行压垮的合并直接去掉 */
    private fun shiftMerges(xml: String, k: Int): String {
        var changed = false
        val out = Regex("<mergeCell ref=\"([^\"]+)\"/>").replace(xml) { m ->
            changed = true
            val shifted = shiftRange(m.groupValues[1], k)
            if (shifted == "#REF!") "" else "<mergeCell ref=\"" + shifted + "\"/>"
        }
        if (!changed) return out
        val n = Regex("<mergeCell ").findAll(out).count()
        return Regex("<mergeCells count=\"\\d+\">").replace(out, "<mergeCells count=\"$n\">")
    }

    // ---------- 工具 ----------

    private fun cellRefOf(op: JSONObject): String? {
        val cell = op.optString("cell").trim()
        if (cell.isNotEmpty()) return cell.uppercase().replace("$", "")
        val row = op.optInt("row", 0)
        val col = op.optInt("col", 0)
        return if (row > 0 && col > 0) colName(col - 1) + row else null
    }

    private fun valueOf(v: Any?): String = when (v) {
        null, JSONObject.NULL -> ""
        is Double -> if (v == v.toLong().toDouble()) v.toLong().toString() else v.toString()
        else -> v.toString()
    }

    private fun cellStyle(xml: String, ref: String): String? =
        Regex("<c\\b[^>]*\\br=" + Q + Regex.escape(ref) + Q + "[^>]*>").find(xml)
            ?.let { Regex("\\bs=\"(\\d+)\"").find(it.value)?.groupValues?.get(1) }

    private fun rowNumberOf(ref: String): Int =
        Regex("(\\d+)$").find(ref)?.groupValues?.get(1)?.toInt() ?: 1

    private fun colNumberOf(ref: String): Int =
        Regex("^([A-Z]+)").find(ref.uppercase())?.groupValues?.get(1)?.let { colIndex(it) } ?: 0

    private fun maxRow(xml: String): Int =
        Regex("<row\\b[^>]*\\br=\"(\\d+)\"").findAll(xml).maxOfOrNull { it.groupValues[1].toInt() } ?: 0

    fun colIndex(letters: String): Int {
        var n = 0
        for (ch in letters.uppercase()) n = n * 26 + (ch - 'A' + 1)
        return n - 1
    }

    fun colName(index: Int): String {
        var i = index
        val sb = StringBuilder()
        while (i >= 0) {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        }
        return sb.toString()
    }

    private fun escape(s: String): String = s
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    private fun unescape(s: String): String = s
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
        .replace("&apos;", "'").replace("&amp;", "&")

    // ---------- zip ----------

    private fun readEntries(f: File): LinkedHashMap<String, ByteArray> {
        val map = LinkedHashMap<String, ByteArray>()
        ZipFile(f).use { z ->
            z.entries().asSequence().forEach { e ->
                if (!e.isDirectory) map[e.name] = z.getInputStream(e).readBytes()
            }
        }
        return map
    }

    private fun writeEntries(entries: Map<String, ByteArray>, out: File) {
        ZipOutputStream(FileOutputStream(out)).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
    }

    /** 工作表名 → (zip 内路径, 名称)。找不到返回 null（调用方退回 sheet1）。 */
    private fun resolveSheet(entries: Map<String, ByteArray>, wanted: String): Pair<String, String>? {
        val wb = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8) ?: return null
        val rels = entries["xl/_rels/workbook.xml.rels"]?.toString(Charsets.UTF_8)?.let { xml ->
            Regex("<Relationship\\b[^>]*?Id=\"([^\"]+)\"[^>]*?Target=\"([^\"]+)\"")
                .findAll(xml).associate { m -> m.groupValues[1] to m.groupValues[2] }
        } ?: emptyMap()
        val sheets = Regex("<sheet\\b[^>]*>").findAll(wb).mapNotNull { m ->
            val tag = m.value
            val name = Regex("\\bname=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: return@mapNotNull null
            val rid = Regex("\\br:id=\"([^\"]+)\"").find(tag)?.groupValues?.get(1) ?: ""
            val target = rels[rid] ?: ""
            if (target.isEmpty()) null else name to target
        }.toList()
        if (sheets.isEmpty()) return null
        val pick = sheets.firstOrNull { it.first == wanted } ?: sheets.first()
        val target = pick.second.removePrefix("/").let { if (it.startsWith("xl/")) it else "xl/" + it }
        return target to pick.first
    }
}
