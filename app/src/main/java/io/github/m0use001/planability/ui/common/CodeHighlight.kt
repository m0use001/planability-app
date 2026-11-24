package io.github.m0use001.planability.ui.common

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 代码块自动涂色 —— **配色与 token 分类对齐 VS Code 默认主题**（Dark+ / Light+）。
 *
 * ## 两种配色方案（2026-10-30 用户在「按 VS Code 来」之后追加要求：两套都提供，用户可切）
 * - [Style.VSCODE]：**完全复刻** VS Code 默认主题（下表官方色值，不随 app 配色变化）。
 * - [Style.THEME]：**主题派生** —— 保留 VS Code 整套色相关系（各 token 之间的色相间距），
 *   把色相整体旋转到"声明关键字 = 当前主题主色"的位置。换配色方案时整块代码跟着换色相，
 *   token 之间仍彼此可区分，也不会跟界面主色打架。
 *
 * ## 复刻方案的色值（VS Code 默认主题）
 * 颜色不走 M3 scheme role，直接用 VS Code 默认主题的官方色值，按当前背景明暗选亮/暗两套：
 *
 * | token | Dark+ | Light+ | 说明 |
 * |---|---|---|---|
 * | 注释 | #6A9955 | #008000 | `//` `#` `--` `/* */` |
 * | 字符串 | #CE9178 | #A31515 | 含引号、转义、三引号、f"" |
 * | 数字 | #B5CEA8 | #098658 | 数字常量/浮点/十六进制 |
 * | 控制流关键字 | #C586C0 | #AF00DB | if/else/for/while/return/try/catch/when… |
 * | 声明类关键字 | #569CD6 | #0000FF | def/class/fun/val/import/public… |
 * | 常量 | #569CD6 | #0000FF | true/false/null/None/this/self |
 * | 类型 | #4EC9B0 | #267F99 | 首字母大写的标识符 + 各语言原始类型名 |
 * | 函数名 | #DCDCAA | #795E26 | 标识符紧跟 `(` |
 * | 变量 | #9CDCFE | #001080 | 其余标识符 |
 * | 注解 | #DCDCAA | #795E26 | Kotlin/Java `@Foo`、装饰器 |
 *
 * ## 边界（做不到 VS Code 的事，说清楚）
 * - 纯词法扫描，没有语义模型：`obj.field` 里的 field 会按变量色，函数指针/模板嵌套等复杂情况按就近规则。
 * - 未知语言（```text、```、```python2）只染注释/字符串/数字，不猜关键字、不给标识符上色。
 * - 超过 [MAX_CHARS] 字符原样返回：流式输出每帧都要重涂，超长块会拖帧。
 * - 不做括号配对、语义高亮（VS Code 的 semantic tokens 需要编译器级信息）。
 */
object CodeHighlight {

    /** 超过这个长度就不再涂色（流式期间每帧都会重算，长文本会拖帧） */
    const val MAX_CHARS = 20_000

    /** VS Code 默认主题的官方色值（Dark+ / Light+） */
    data class Palette(
        val comment: Color,
        val string: Color,
        val number: Color,
        val keywordControl: Color,
        val keywordDecl: Color,
        val constant: Color,
        val type: Color,
        val function: Color,
        val variable: Color,
        val annotation: Color
    )

    private val DARK_PLUS = Palette(
        comment = Color(0xFF6A9955),
        string = Color(0xFFCE9178),
        number = Color(0xFFB5CEA8),
        keywordControl = Color(0xFFC586C0),
        keywordDecl = Color(0xFF569CD6),
        constant = Color(0xFF569CD6),
        type = Color(0xFF4EC9B0),
        function = Color(0xFFDCDCAA),
        variable = Color(0xFF9CDCFE),
        annotation = Color(0xFFDCDCAA)
    )

    private val LIGHT_PLUS = Palette(
        comment = Color(0xFF008000),
        string = Color(0xFFA31515),
        number = Color(0xFF098658),
        keywordControl = Color(0xFFAF00DB),
        keywordDecl = Color(0xFF0000FF),
        constant = Color(0xFF0000FF),
        type = Color(0xFF267F99),
        function = Color(0xFF795E26),
        variable = Color(0xFF001080),
        annotation = Color(0xFF795E26)
    )

    /** 代码配色方案 */
    enum class Style(val key: String, val label: String) {
        /** 完全复刻 VS Code 默认主题 */
        VSCODE("vscode", "VS Code 复刻"),

        /** 按 VS Code 的色相关系从主题主色生成 */
        THEME("theme", "跟随主题");

        companion object {
            fun of(key: String?): Style = entries.firstOrNull { it.key == key } ?: VSCODE
        }
    }

    /** 按背景明暗取 VS Code 亮/暗主题色板 */
    fun palette(scheme: ColorScheme): Palette =
        if (scheme.background.luminance() > 0.5f) LIGHT_PLUS else DARK_PLUS

    /**
     * 主题派生色板：以 VS Code 的色板为**骨架**，把整套色相整体旋转，
     * 使「声明关键字」的色相（VS Code 是 207° 蓝）落到当前主题主色的色相上。
     * 这样 token 之间的相对色相间隔（字符串→注释→数字……）与 VS Code 完全一致，
     * 但整块代码的主调跟着 app 配色走。
     */
    fun paletteDerived(scheme: ColorScheme): Palette {
        val base = palette(scheme)
        val drift = hueOf(scheme.primary) - 207f
        fun rot(c: Color): Color {
            val hsl = toHsl(c)
            return fromHsl((hsl[0] + drift).mod(360f), hsl[1], hsl[2])
        }
        return Palette(
            comment = rot(base.comment),
            string = rot(base.string),
            number = rot(base.number),
            keywordControl = rot(base.keywordControl),
            keywordDecl = rot(base.keywordDecl),
            constant = rot(base.constant),
            type = rot(base.type),
            function = rot(base.function),
            variable = rot(base.variable),
            annotation = rot(base.annotation)
        )
    }

    fun paletteFor(scheme: ColorScheme, style: Style): Palette =
        if (style == Style.THEME) paletteDerived(scheme) else palette(scheme)

    // ---------------- HSL 工具（Color 只暴露 RGB，这里转一圈用来旋转色相） ----------------

    private fun hueOf(c: Color): Float = toHsl(c)[0]

    /** RGB → [h(0..360), s(0..1), l(0..1)] */
    private fun toHsl(c: Color): FloatArray {
        val r = c.red
        val g = c.green
        val b = c.blue
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val l = (max + min) / 2f
        if (max == min) return floatArrayOf(0f, 0f, l)
        val d = max - min
        val s = if (l > 0.5f) d / (2f - max - min) else d / (max + min)
        val h = when (max) {
            r -> ((g - b) / d + if (g < b) 6f else 0f)
            g -> ((b - r) / d + 2f)
            else -> ((r - g) / d + 4f)
        } * 60f
        return floatArrayOf(h, s, l)
    }

    private fun fromHsl(h: Float, s: Float, l: Float): Color {
        if (s <= 0f) return Color(l, l, l)
        val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
        val p = 2f * l - q
        fun hue2rgb(t0: Float): Float {
            var t = t0
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 1f / 2f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        val hh = (h / 360f).mod(1f)
        return Color(hue2rgb(hh + 1f / 3f), hue2rgb(hh), hue2rgb(hh - 1f / 3f))
    }

    // ---------------- 控制流关键字（VS Code 里染 #C586C0 那一类） ----------------

    private val CONTROL = setOf(
        "if", "else", "elif", "for", "foreach", "while", "do", "switch", "case", "default",
        "break", "continue", "return", "try", "catch", "except", "finally", "throw", "throws",
        "raise", "when", "match", "guard", "defer", "goto", "yield", "await", "in", "is", "not",
        "and", "or", "as", "of", "instanceof", "typeof", "lambda", "with", "repeat", "until",
        "loop", "select", "range", "where", "having", "order", "group", "by", "on", "union",
        "limit", "offset", "join", "left", "right", "inner", "outer", "exists", "between", "like",
        "then", "fi", "done", "esac", "end"
    )

    /** 常量（VS Code 里与声明关键字同为蓝色） */
    private val CONSTANT = setOf(
        "true", "false", "null", "nil", "none", "nullptr", "undefined", "this", "self", "super"
    )

    // ---------------- 各语言的声明/其它关键字（染 #569CD6 那一类） ----------------

    private val KOTLIN = setOf(
        "package", "import", "class", "interface", "object", "fun", "val", "var", "data", "sealed",
        "companion", "override", "open", "private", "public", "internal", "protected", "abstract",
        "suspend", "inline", "crossinline", "noinline", "reified", "lateinit", "const", "enum",
        "annotation", "typealias", "init", "constructor", "get", "set", "vararg", "external",
        "operator", "infix", "tailrec", "actual", "expect"
    )

    private val JAVA = setOf(
        "package", "import", "class", "interface", "enum", "extends", "implements", "public",
        "private", "protected", "static", "final", "abstract", "synchronized", "volatile",
        "transient", "native", "new", "switch", "record", "sealed", "permits", "var", "assert"
    )

    private val PYTHON = setOf(
        "def", "class", "import", "from", "as", "global", "nonlocal", "assert", "del", "pass",
        "async", "print", "len", "range", "str", "int", "float", "list", "dict", "set", "tuple",
        "bool", "bytes", "object", "type", "isinstance"
    )

    private val JS = setOf(
        "const", "let", "var", "function", "class", "extends", "import", "export", "from",
        "async", "await", "interface", "type", "enum", "implements", "public", "private",
        "protected", "readonly", "declare", "namespace", "module", "require", "console", "window",
        "document", "null", "undefined"
    )

    private val C_FAMILY = setOf(
        "include", "define", "ifdef", "ifndef", "endif", "pragma", "struct", "union", "enum",
        "typedef", "const", "static", "extern", "inline", "volatile", "register", "sizeof",
        "class", "public", "private", "protected", "virtual", "override", "template", "typename",
        "namespace", "using", "new", "delete", "auto", "constexpr", "noexcept", "std"
    )

    private val GO = setOf(
        "package", "import", "func", "defer", "go", "chan", "var", "const", "type", "struct",
        "interface", "map", "make", "new", "append", "len", "cap", "panic", "recover", "print"
    )

    private val RUST = setOf(
        "fn", "let", "mut", "const", "static", "struct", "enum", "trait", "impl", "use", "mod",
        "pub", "crate", "unsafe", "async", "await", "move", "ref", "dyn", "where", "Some", "None",
        "Ok", "Err"
    )

    private val SWIFT = setOf(
        "func", "let", "var", "class", "struct", "enum", "protocol", "extension", "import",
        "init", "deinit", "public", "private", "internal", "fileprivate", "open", "static",
        "mutating", "override", "weak", "lazy", "some", "any", "throws", "rethrows"
    )

    private val SQL = setOf(
        "select", "from", "where", "insert", "into", "values", "update", "set", "delete", "create",
        "table", "drop", "alter", "add", "column", "index", "primary", "key", "foreign",
        "references", "distinct", "all", "count", "sum", "avg", "max", "min", "int", "varchar",
        "text", "bigint", "boolean", "date", "datetime", "timestamp"
    )

    private val SHELL = setOf(
        "function", "export", "local", "readonly", "echo", "cd", "ls", "cat", "grep", "sed", "awk",
        "rm", "cp", "mv", "mkdir", "chmod", "sudo", "source", "exit", "set", "unset", "shift"
    )

    private val YAML = setOf("yes", "no", "on", "off")

    private val CSS = setOf(
        "important", "media", "import", "keyframes", "font-face", "root", "supports", "none",
        "auto", "inherit", "initial", "unset", "important", "flex", "grid", "block", "inline",
        "absolute", "relative", "fixed", "sticky", "hidden", "visible"
    )

    /** 各语言的原始类型名（VS Code 里染青色） */
    private val PRIMITIVES = setOf(
        "int", "long", "short", "byte", "char", "float", "double", "boolean", "bool", "void",
        "string", "str", "unit", "any", "object", "number", "bigint", "symbol", "never", "unknown",
        "void", "int8", "int16", "int32", "int64", "uint", "uint8", "uint16", "uint32", "uint64",
        "usize", "isize", "f32", "f64", "rune", "i8", "i16", "i32", "i64", "u8", "u16", "u32", "u64"
    )

    private fun keywordsOf(lang: String): Set<String>? = when (lang.lowercase()) {
        "kotlin", "kt", "kts", "gradle" -> KOTLIN
        "java" -> JAVA
        "python", "py", "python3" -> PYTHON
        "javascript", "js", "jsx", "typescript", "ts", "tsx", "node" -> JS
        "c", "h", "cpp", "c++", "cc", "hpp", "objc", "objective-c" -> C_FAMILY
        "csharp", "cs", "c#" -> JAVA + setOf("namespace", "using", "string", "bool", "var", "async", "await", "foreach")
        "go", "golang" -> GO
        "rust", "rs" -> RUST
        "swift" -> SWIFT
        "sql", "mysql", "postgres", "sqlite" -> SQL
        "sh", "shell", "bash", "zsh", "console", "terminal" -> SHELL
        "yaml", "yml", "toml" -> YAML
        "json" -> emptySet()
        "css", "scss", "less" -> CSS
        else -> null
    }

    /** 行注释前缀 + 是否支持块注释 */
    private fun commentStyle(lang: String): Pair<List<String>, Boolean> = when (lang.lowercase()) {
        "python", "py", "python3", "sh", "shell", "bash", "zsh", "yaml", "yml", "toml",
        "ruby", "rb", "perl", "r", "makefile", "dockerfile" -> (listOf("#") to false)
        "sql", "mysql", "postgres", "sqlite" -> (listOf("--") to true)
        "lua", "haskell" -> (listOf("--") to false)
        else -> (listOf("//") to true)
    }

    private fun isIdentStart(c: Char) = c.isLetter() && c.code < 0x2E80 // 不把中文/日文等当作标识符
    private fun isIdentPart(c: Char) = (c.isLetterOrDigit() || c == '_' || c == '$') && c.code < 0x2E80

    /**
     * 把代码涂成 [AnnotatedString]。纯词法扫描；分类顺序=注释 → 字符串 → 数字 → 标识符
     * （标识符再细分为 控制流关键字 / 声明关键字 / 常量 / 类型 / 函数 / 变量）。
     */
    fun highlight(
        code: String,
        lang: String,
        scheme: ColorScheme,
        style: Style = Style.VSCODE
    ): AnnotatedString {
        if (code.isEmpty() || code.length > MAX_CHARS) return AnnotatedString(code)
        val p = paletteFor(scheme, style)
        val kws = keywordsOf(lang)
        val known = kws != null
        val (lineMarks, hasBlock) = commentStyle(lang)
        val caseInsensitive = lang.lowercase().contains("sql")

        return buildAnnotatedString {
            append(code)
            var i = 0
            val n = code.length
            while (i < n) {
                val c = code[i]
                // ---- 注释（优先级最高：注释里的关键字不再单独染色） ----
                val mark = lineMarks.firstOrNull { m -> code.startsWith(m, i) }
                if (mark != null) {
                    var e = code.indexOf('\n', i)
                    if (e < 0) e = n
                    addStyle(SpanStyle(color = p.comment), i, e)
                    i = e
                    continue
                }
                if (hasBlock && code.startsWith("/*", i)) {
                    val e = code.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    addStyle(SpanStyle(color = p.comment), i, e)
                    i = e
                    continue
                }
                // ---- 字符串 ----
                if (c == '"' || c == '\'' || c == '`') {
                    val triple = code.startsWith("$c$c$c", i)
                    val quoteLen = if (triple) 3 else 1
                    var j = i + quoteLen
                    var closed = false
                    while (j < n) {
                        if (code[j] == '\\' && j + 1 < n) { j += 2; continue }
                        if (!triple && code[j] == '\n') break
                        if (code.startsWith("$c".repeat(quoteLen), j)) { j += quoteLen; closed = true; break }
                        j++
                    }
                    val end = if (closed) j else j.coerceAtMost(n)
                    addStyle(SpanStyle(color = p.string), i, end)
                    i = end
                    continue
                }
                // ---- 注解/装饰器 ----
                if (c == '@' && i + 1 < n && isIdentStart(code[i + 1])) {
                    var j = i + 1
                    while (j < n && isIdentPart(code[j])) j++
                    addStyle(SpanStyle(color = p.annotation), i, j)
                    i = j
                    continue
                }
                // ---- 数字（前面紧贴标识符的不算） ----
                if (c.isDigit() && (i == 0 || !(code[i - 1].isLetterOrDigit() || code[i - 1] == '_'))) {
                    var j = i
                    while (j < n && (code[j].isLetterOrDigit() || code[j] == '.' || code[j] == '_')) j++
                    addStyle(SpanStyle(color = p.number), i, j)
                    i = j
                    continue
                }
                // ---- 标识符 ----
                if (isIdentStart(c)) {
                    var j = i + 1
                    while (j < n && isIdentPart(code[j])) j++
                    val word = code.substring(i, j)
                    val lower = word.lowercase()
                    val color = when {
                        CONTROL.contains(if (caseInsensitive) lower else word) -> p.keywordControl
                        CONSTANT.contains(if (caseInsensitive) lower else word) -> p.constant
                        kws != null && (if (caseInsensitive) kws.contains(lower) else kws.contains(word)) -> p.keywordDecl
                        PRIMITIVES.contains(if (caseInsensitive) lower else word) -> p.type
                        word[0].isUpperCase() -> p.type
                        // 后面紧跟 '(' → 函数调用/定义
                        code.getOrNull(j) == '(' -> p.function
                        known -> p.variable
                        else -> null
                    }
                    if (color != null) addStyle(SpanStyle(color = color), i, j)
                    i = j
                    continue
                }
                i++
            }
        }
    }
}
