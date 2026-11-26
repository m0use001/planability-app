package io.github.m0use001.planability.ui.notes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公式切分/归一化的回归锁（2026-11-10 用户笔记本报障）。
 *
 * 三条真机症状：
 * ① 两个长公式之间的**普通文字**被渲染成居中暗色小字（说明文字被并进了公式段，
 *    又被 stripCjkLabels 当成标签抽走了）；
 * ② 公式被压成一行、分数小得看不清；
 * ③ `\\[4pt]` 这类「换行+间距」命令被原样画出来（jlatexmath 不支持可选间距 →
 *    整块解析失败回退裸文本）。
 */
class MarkdownMathTest {

    /** Kotlin 字符串里 '$' 是模板符：用常量拼，别在字面量里写 $$ */
    private val D = "$"

    private fun mathOf(text: String): List<MathSeg.Math> =
        splitMathSegments(text).filterIsInstance<MathSeg.Math>()

    private fun textOf(text: String): String =
        splitMathSegments(text).filterIsInstance<MathSeg.Txt>().joinToString("") { it.text }

    /** ① 两公式之间的中文必须是 Txt，不能被吸进公式、更不能变成 labels */
    @Test
    fun `两公式之间的普通文字保持正文`() {
        val src = D + D + "x = 1" + D + D + "\n这里是一段普通说明文字，不该被公式吞掉。\n" + D + D + "y = 2" + D + D
        val maths = mathOf(src)
        assertEquals("应识别出 2 个公式段，实际 ${maths.size}", 2, maths.size)
        assertTrue(
            "普通文字被并进公式了：labels=${maths.joinToString { it.labels }}",
            maths.all { it.labels.isBlank() }
        )
        assertTrue("普通文字丢了：\n${textOf(src)}", textOf(src).contains("普通说明文字"))
    }

    /** ② 块级公式里的换行要保留（多行方程组不能压成一行） */
    @Test
    fun `块级公式的多行不被压缩`() {
        val src = D + D + "\na = 1\nb = 2\nc = 3\n" + D + D
        val m = mathOf(src).single()
        assertTrue("多行公式没有保留换行：\n${m.latex}", m.latex.contains("\\\\"))
    }

    /** ③ `\\[4pt]` 必须剥掉，剥完不能残留方括号内容 */
    @Test
    fun `换行间距命令被剥离`() {
        val src = D + D + "\nf(x)=a \\\\[4pt]\ng(x)=b \\\\[6pt]\n" + D + D
        val m = mathOf(src).single()
        assertTrue("残留了 4pt：\n${m.latex}", !m.latex.contains("[4pt]"))
        assertTrue("残留了 6pt：\n${m.latex}", !m.latex.contains("[6pt]"))
    }

    /** 已有 \begin{} 环境的公式不能被包进 gathered（会双重换行/报错） */
    @Test
    fun `已有环境不重复包裹`() {
        val src = D + D + "\n\\begin{aligned}a&=1\\\\b&=2\\end{aligned}\n" + D + D
        val m = mathOf(src).single()
        assertTrue("不该再包 gathered：\n${m.latex}", !m.latex.contains("gathered"))
        assertTrue("aligned 环境应保留：\n${m.latex}", m.latex.contains("aligned"))
    }

    /**
     * 2026-11-10 用户截图「一个长等式被强制换行了」（会话消息 5522）：
     * 模型把**一条**长等式的源码按排版习惯写成多行（续行以 \overset / \xrightarrow 开头），
     * 裸换行一律硬分会把一条等式拆成 N 行。续行必须合并回一行。
     */
    @Test
    fun `长等式的源码续行不硬分`() {
        val src = D + D + "\n\\frac{ax+1}{\\sqrt{x^2+ax+1}}\n\\overset{\\text{同除 }x}{=}\n" +
            "\\frac{a+\\frac1x}\n{-\\sqrt{1+\\frac ax}}\n\\xrightarrow{x\\to-\\infty}\n\\frac{a}{-1-1}=-\\frac a2\n" + D + D
        val m = mathOf(src).single()
        assertTrue("长等式被拆成多行了：\n${m.latex}", !m.latex.contains("gathered"))
        // 2026-11-11：\xrightarrow 已归一化为 \overset{…}{\rightarrow}（jlatexmath 不支持 x 系箭头），
        // 截断判据跟着映射走——能看到完整的 x→-∞ 标注 = 等式没被截断
        assertTrue("等式被截断：\n${m.latex}", m.latex.contains("\\overset{x\\to-\\infty}{\\rightarrow}"))
        assertEquals("中文标注丢了", "同除", m.labels)
    }

    /** 显式 \\\\ 换行仍要分行（真多行方程组不受续行合并影响） */
    @Test
    fun `显式换行仍分行`() {
        val src = D + D + "\nf(x)=a \\\\\ng(x)=b\n" + D + D
        val m = mathOf(src).single()
        assertTrue("显式换行被吞：\n${m.latex}", m.latex.contains("\\\\"))
    }

    /** 行内公式保持原样（不套 gathered、不影响正文） */
    @Test
    fun `行内公式不套环境`() {
        val src = "价格是 " + D + "x^2" + D + " 元"
        val m = mathOf(src).single()
        assertTrue("行内公式被套了环境：\n${m.latex}", !m.latex.contains("gathered"))
        assertTrue("正文丢了：\n${textOf(src)}", textOf(src).contains("价格是"))
    }

    /** 行内公式占位高度必须跟位图自然高度走（2026-11-10 用户截图：分数被压到 1.4em 变小） */
    @Test
    fun `行内公式占位高度按自然比例`() {
        val font = 56f   // bodyLarge 16sp @3.5x
        // 高公式：位图高 = 3 倍字号 → 封顶 2.2em（大于旧的 1.4，且不顶穿行）
        val (_, tall) = inlinePlaceholderEm(300, 168, font)
        assertTrue("高公式高度异常：$tall em（应封顶 2.2）", tall in 2.0f..2.25f)
        // 超宽公式按 14em 等比缩，宽高比不变
        val (w, h) = inlinePlaceholderEm(4000, 200, font)
        assertTrue("超宽公式没缩到 14em：$w", w <= 14.01f)
        assertEquals("宽度封顶后比例被破坏", 4000f / 200f, w / h, 0.05f)
        // 普通单行公式：高≈1.2 倍字号
        val (_, flat) = inlinePlaceholderEm(120, 67, font)
        assertTrue("普通公式高度异常：$flat em", flat in 1.0f..1.5f)
        // 宽度按同一比例缩放（不拉伸变形）
        val (w2, h2) = inlinePlaceholderEm(300, 168, font)
        assertEquals("宽高比被破坏", 300f / 168f, w2 / h2, 0.01f)
        // 退化输入不崩
        val (dw, dh) = inlinePlaceholderEm(0, 0, font)
        assertEquals(1.4f to 1.4f, dw to dh)
    }

    /**
     * 2026-11-10 用户报障「() 内注释被挤出了」：块级公式尾注 `\quad(\text{…})` 剥 CJK 后
     * 残留空壳 `()` / `(A)`，且标签被 \text 边界拆散丢字母（"右边还有 A"→"右边还有·"）。
     * 修复：整段括注连壳抽成一条标签。
     * （phone-a 注：本用例原稿的字符串字面量被生成脚本转义损坏——\t 变制表符、字面量里
     *  有真实换行，整个测试模块编不过。这里按注释里的原意重写 src，断言未动。）
     */
    @Test
    fun `括注尾注连壳抽标签不留空括号`() {
        val src = D + D + "\na_n=g(n)\\to A=\\lim_{n\\to\\infty}g(n)\\quad(\\text{右边是数，算完收工})\n" + D + D
        val m = mathOf(src).single()
        assertEquals(
            "括号壳残留在公式里：\n${m.latex}", false, m.latex.contains("()")
        )
        assertEquals("标签丢了", "右边是数，算完收工", m.labels)
    }

    @Test
    fun `括注里的裸字母跟标签走而不留在公式里`() {
        val src = D + D + "\na_{n+1}=F(a_n)\\to A=F(A)\\quad(\\text{右边还有 }A\\text{，只能解方程})\n" + D + D
        val m = mathOf(src).single()
        assertEquals(
            "括号壳残留在公式里：\n${m.latex}", false, m.latex.contains("()")
        )
        assertEquals("标签丢字母了", "右边还有 A，只能解方程", m.labels)
    }

    /** 纯数学括号（无 \text{CJK}）不得被当成标签抽走 */
    @Test
    fun `纯数学括号不误伤`() {
        val src = D + D + "\ng(n)=2n+1\\quad(n\\in\\mathbb{N})\n" + D + D
        val m = mathOf(src).single()
        assertTrue("数学括号被误删：\n${m.latex}", m.latex.contains("(n"))
        assertTrue("不该有标签", m.labels.isBlank())
    }

    /**
     * 2026-11-10 用户截图「公式串行了」：4 列表格的「练什么」文字压在公式上。
     * 根因：占位宽上限写死 14em，远超表格单元格 ~8em 列宽——Image 溢出占位盒
     * （Box/Row 不裁剪）画到相邻单元格上。占位宽必须能按容器宽夹紧（等比缩高）。
     */
    @Test
    fun `占位宽能按容器夹紧`() {
        val font = 56f
        // 宽公式（aspect 5:1、自然高 2.2em → 自然宽 11em）放进 8em 的窄列：
        // 宽夹到 ≤8em，高按同一比例缩（比例不变形），且明显小于封顶 2.2em
        val (w, h) = inlinePlaceholderEm(3000, 600, font, maxWEm = 8f)
        assertTrue("宽度没夹进容器：$w em", w in 7.5f..8.01f)
        assertEquals("夹紧后宽高比被破坏", 5f, w / h, 0.05f)
        assertTrue("夹紧后高度没跟着缩：$h em", h < 2.0f)
        // 窄公式不受影响（本来就比容器窄）
        val (nw, nh) = inlinePlaceholderEm(300, 168, font, maxWEm = 8f)
        assertTrue("窄公式被误缩：$nw em", nw < 4.5f)
        assertEquals("窄公式比例被破坏", 300f / 168f, nw / nh, 0.01f)
        // 不传 maxWEm 保持旧行为（14em 上限）
        val (ow, _) = inlinePlaceholderEm(4000, 200, font)
        assertTrue("默认上限变了：$ow", ow <= 14.01f)
    }

    /** 源码守卫：MdInlineText 必须量容器宽并把 maxWem 传进占位计算（组合期逻辑，JVM 测不到，钉源码） */
    @Test
    fun `行内公式占位按容器宽夹紧_源码守卫`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt").readText()
        assertTrue(
            "inlinePlaceholderEm 缺 maxWEm 参数（默认 14f）",
            src.contains("maxWEm: Float = 14f")
        )
        // 2026-11-21 放宽调用形状：正式渲染的实参从 (bmp.width, bmp.height) 换成了
        // 尺寸索引的 (wh[0], wh[1])（36a64785 压缩毛巾根修），守卫钉「实参对 + 第 4 参 maxWem」
        Regex(
            "inlinePlaceholderEm\\(\\s*(bmp\\.width,\\s*bmp\\.height|wh\\[0\\],\\s*wh\\[1\\]),\\s*fontPx,\\s*maxWem[,)]"
        ).find(src) ?: error("MdInlineText 的正式渲染没有把容器宽 maxWem 传进占位计算")
        // 容器宽捕获必须走 Modifier.layout（透传 intrinsic）——BoxWithConstraints/SubcomposeLayout
        // 会被任何 IntrinsicSize 父布局炸出 IllegalStateException（2026-11-10 真机 crash-loop 教训）
        assertTrue("MdInlineText 没有量容器宽（Modifier.layout 捕获 maxWidth）", src.contains("capPx"))
        assertTrue(
            "MdInlineText 禁用 BoxWithConstraints（SubcomposeLayout 不支持 intrinsic）",
            !src.contains("import androidx.compose.foundation.layout.BoxWithConstraints") &&
                !Regex("BoxWithConstraints\\s*\\(").containsMatchIn(src)
        )
    }

    // ==================== 2026-11-11 夜撞：公式/表格输出形态语料（真机快照 20261111-001317） ====================

    /**
     * 形态①（消息 5572 实锤）：`$$…\xRightarrow{\text{平方}}…$$` 整块回退裸文本——
     * jlatexmath 不支持 \xRightarrow 系。归一化后必须是 \overset{…}{\Rightarrow}，
     * CJK 标注照旧剥成 labels（"平方"不再孤儿化——公式本身能渲了）。
     */
    @Test
    fun `形态_xRightarrow块级_映射为overset且标签保留`() {
        val src = D + D + "\nA=\\sqrt{A+6}\\ \\xRightarrow{\\text{平方}}\\ A^2=A+6\\ \\Rightarrow\\ A^2-A-6=0\n" + D + D
        val m = mathOf(src).single()
        assertTrue("\\xRightarrow 残留（整块会解析失败回退裸文本）：\n${m.latex}", !m.latex.contains("xRight"))
        // CJK 标注被剥走后空标注退化为纯箭头（overset 只在 ASCII 标注时出现）
        assertTrue("箭头丢了：\n${m.latex}", m.latex.contains("\\Rightarrow"))
        assertEquals("平方", m.labels)
    }

    /** 形态①b：行内 ASCII 标注 \xrightarrow{\text{step}}（一层嵌套大括号）也要映射 */
    @Test
    fun `形态_xrightarrow行内_嵌套括号标注`() {
        val src = "先 " + D + "a \\xrightarrow{\\text{step}} b" + D + " 后"
        val m = mathOf(src).single()
        assertTrue("xrightarrow 残留：\n${m.latex}", !m.latex.contains("xright"))
        assertTrue(m.latex.contains("\\overset{\\text{step}}{\\rightarrow}"))
    }

    /** 形态①c：空标注 \xRightarrow{} 退化为纯箭头 */
    @Test
    fun `形态_空标注退化为纯箭头`() {
        val src = D + "x \\xRightarrow{} y" + D
        val m = mathOf(src).single()
        assertTrue(m.latex.contains("\\Rightarrow") && !m.latex.contains("\\xRight"))
    }

    /** 形态②（消息 5574 实锤）：表格 8em 列里的 16em 单行公式 → 按顶层关系符拆 gathered */
    @Test
    fun `形态_链式不等式拆行`() {
        val latex = "\\sqrt{x+6}\\le x\\iff x^2-x-6\\ge0\\iff x\\ge3"
        val gathered = gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f)
        assertTrue("没拆行：\n$gathered", gathered.contains("\\begin{gathered}"))
        val lines = gathered.replace("\\begin{gathered}", "").replace("\\end{gathered}", "").split("\\\\")
        assertTrue("应拆出 ≥3 行：\n$gathered", lines.size >= 3)
        assertTrue("拆行后仍有单行塞不下风险的整行：\n$gathered", gathered.contains("\\iff"))
    }

    /** 形态②b：轻微溢出（<25%）不拆——缩放更整齐 */
    @Test
    fun `形态_轻微溢出不拆行`() {
        assertEquals("a \\le b", gatherIfNeeded("a \\le b", naturalWem = 9f, maxWem = 8f))
    }

    /** 形态②c：关系符都在大括号/左右组内 → 没有顶层拆分点，回退缩放 */
    @Test
    fun `形态_关系符在括号内不拆`() {
        val latex = "\\frac{a \\iff b}{c}"
        assertEquals(latex, gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f))
    }

    /** 形态②d：已是 gathered/matrix 环境的不二次包裹 */
    @Test
    fun `形态_已有环境不拆`() {
        val latex = "\\begin{gathered}a \\iff b \\\\ c \\iff d\\end{gathered}"
        assertEquals(latex, gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f))
    }

    /**
     * 2026-11-15 用户截图（例 2 乘积法则）：`f'=…=…=…` 按等号拆成 4 段，
     * 第一段是左值 `f'`、其余行首悬挂等号（渲染时多行公式按 Top 对齐，
     * 标签与第一行同高——见 MdInlineText 的 gathered 分支）。
     */
    @Test
    fun `等号拆行_左值独立成行_其余行首悬挂等号`() {
        val latex = "f'=2x(x^2+3x)+x^2(2x+3)=2x^3+6x^2+2x^3+3x^2=4x^3+9x^2"
        val parts = splitAtTopEquals(latex)!!
        assertEquals("应拆 4 段：$parts", 4, parts.size)
        assertEquals("首段是左值：${parts[0]}", "f'", parts[0])
        assertTrue("其余段行首悬挂等号：${parts.drop(1)}", parts.drop(1).all { it.startsWith("=") })
    }

    /** 形态②e（2026-11-12 用户「表格内公式压得太小」）：无关系符的加/乘链改按**顶层二元运算符**拆行，不再硬缩 */
    @Test
    fun `形态_加乘链按运算符拆行`() {
        val latex = "x^2+x^3+x^4+x^5+x^6+x^7+x^8"
        val gathered = gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f)
        assertTrue("加法链没拆行：\n$gathered", gathered.contains("\\begin{gathered}"))
        assertTrue("拆行应保留运算符（行首悬挂）：\n$gathered", gathered.contains("+x^3"))
    }

    /** 形态②f：括号内的一元负号/加号不是拆分点——(-x)^2 不能被拆坏 */
    @Test
    fun `形态_括号内一元负号不拆`() {
        val latex = "(-x)^2+y^2"
        val gathered = gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f)
        // 顶层只有一个二元 +：拆出两行，括号组保持完整
        assertTrue("应拆行：\n$gathered", gathered.contains("\\begin{gathered}"))
        assertTrue("括号组被拆坏：\n$gathered", gathered.contains("(-x)^2"))
    }

    /** 形态②g：行首一元负号不拆（前无运算对象） */
    @Test
    fun `形态_行首一元负号不拆`() {
        val latex = "-a_1-b_2-c_3"
        val gathered = gatherIfNeeded(latex, naturalWem = 16f, maxWem = 8f)
        assertTrue("应拆行：\n$gathered", gathered.contains("\\begin{gathered}"))
        // 首行的 -a_1 必须完整（那个 - 是一元负号）
        assertTrue("一元负号被当二元拆掉：\n$gathered", gathered.contains("-a_1"))
    }

    /**
     * 形态②h（2026-11-14 用户截图，消息 6907 行81）：`[...]` 组还开着时组内的 +/−
     * 不是拆分点——旧逻辑把 `=a\bigl[(dh-eg)B_1+(bg-ah)B_2+(ae-bd)B_3\bigr]` 拆成
     * 3 行竖摞（`]` 落在第三行末尾），句子中间竖着一块且整摞被压小。方括号和圆括号
     * 同样是分组：组内不拆，整行回退缩放（宽段落里 75% 缩放可读得多）。
     */
    @Test
    fun `形态_方括号组内不拆`() {
        val latex = "=a\\bigl[(dh-eg)B_1+(bg-ah)B_2+(ae-bd)B_3\\bigr]"
        assertEquals(latex, gatherIfNeeded(latex, naturalWem = 18f, maxWem = 14f))
        // 闭合的方括号不影响顶层拆分：[a,b]+[c,d] 的 + 是顶层二元
        val g2 = gatherIfNeeded("[a,b]+[c,d]", naturalWem = 16f, maxWem = 8f)
        assertTrue("闭合括号外的顶层 + 应拆：\n$g2", g2.contains("\\begin{gathered}"))
        assertTrue("拆行破坏了括号组：\n$g2", g2.contains("[a,b]") && g2.contains("[c,d]"))
    }

    /** 源码守卫：gathered 占位上限必须 ≥5em（3 行竖摞 ~4.5em，3.4em 上限会整摞压小） */
    @Test
    fun `源码守卫_gathered占位不压小`() {
        val src = java.io.File("app/src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
            .let { if (it.exists()) it else java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt") }
            .readText()
        // 2026-11-16：三档上限合并进 mathCapEm（唯一定义）。数值本身由
        // 「占位上限三档唯一口径」用例直接断言，这里只钉调用链没断。
        assertTrue("gathered 占位上限丢了（应有 gathered -> 5.2f）",
            src.contains("gathered -> 5.2f"))
        assertTrue("占位上限没走共享函数 mathCapEm",
            src.contains("hCap = mathCapEm(s.latex, usedGathered)"))
    }

    /**
     * 形态③：渲染失败回退文本要可读——`\ ` `\,` 等间距命令不能原样可见（消息 5572
     * 裸倾泻里满屏 `\ `）。真实片段：A=\sqrt{A+6}\ \Rightarrow\ A^2=A+6
     */
    @Test
    fun `形态_回退文本清洗间距命令`() {
        val cleaned = fallbackText("A=\\sqrt{A+6}\\ \\Rightarrow\\ A^2=A+6\\\\B")
        assertEquals("A=\\sqrt{A+6} \\Rightarrow A^2=A+6 B", cleaned)
    }

    /** 形态④：真实消息 5574 的完整表格行——四段行内公式，切段不丢 */
    @Test
    fun `形态_真实表格行切段不丢`() {
        val src = "| **试凑** | 代 " + D + "1,2,3,5,10" + D + " 逐个验 " + D + "F(M)\\le M" + D +
            " | " + D + "F(3)=3" + D + " ✓，" + D + "F(4)=\\sqrt{10}\\approx3.16\\le4" + D + " ✓ |"
        val maths = mathOf(src)
        assertEquals(4, maths.size)
    }

    /**
     * 形态⑤（消息 5572 全链路）：块级 + 链式 ⇒ —— 映射后整条链仍在一行 latex 里
     * （块级自然宽渲染，不拆行），无 \x 残留、标签齐。
     */
    @Test
    fun `形态_块级推理链_无残留`() {
        val src = D + D + "\nA=\\sqrt{A+6}\\ \\xRightarrow{}\\ A^2=A+6\\ \\Rightarrow\\ A^2-A-6=0\\ \\Rightarrow\\ (A-3)(A+2)=0\n" + D + D
        val m = mathOf(src).single()
        assertTrue(!m.latex.contains("\\xR") && !m.latex.contains("\\xr"))
        assertEquals(3, Regex("\\\\Rightarrow").findAll(m.latex).count())
    }

    // ==================== 2026-11-13 用户七截图：粘连/\tag/标签乱序/等号拆行 ====================

    /**
     * 图1/图6 解析失败根因（模拟器 LatexParseProbeTest 实探）：
     * `\tag{1}` 没注册（ParseException）、`A\iffI` 被最大吞并读成未知命令 `iffI`。
     * [repairTex] 只在解析失败后由 LatexRender 重试时调用，能解析的公式一字不动。
     */
    @Test
    fun `修复_tag转qquad_notag删除`() {
        assertEquals("ax_1+bx_2=y_1\\qquad(1)", repairTex("ax_1+bx_2=y_1\\tag{1}"))
        assertEquals("x=1", repairTex("x=1\\notag"))
        assertEquals("x=1\\qquad(2)", repairTex("x=1\\notag\\tag{2}"))
        // 能解析的原样（返回 null = 不重写）
        assertEquals(null, repairTex("\\det A=ad-bc"))
        assertEquals(null, repairTex("a\\ne0"))
    }

    @Test
    fun `修复_命令粘连按最长已知前缀拆`() {
        // 图1 实锤：模型连写 `A\iffI`（A ⟺ I）→ TeX 吞成未知命令 \iffI → 整段灰字回退
        assertEquals("A\\iff I", repairTex("A\\iffI"))
        // 已知整词不动；未知且无 ≥2 已知前缀的原样（交给解析器报错回退）
        assertEquals(null, repairTex("\\Delta x"))
        assertEquals(null, repairTex("\\foo"))
        // 最长前缀：\neqX → neq（不是 ne）
        assertEquals("\\neq X", repairTex("\\neqX"))
    }

    /** 图1/图7 标签乱序：旧 varBridge 全局预扫把靠后的桥接标签先入列 → 「的 n可逆 · …」 */
    @Test
    fun `标签顺序按源码顺序_桥接不抢跑`() {
        val src = D + D +
            "A\\text{可逆}\\iff A\\mathbf{x}=\\mathbf{b}\\text{对任意}\\mathbf{b}\\text{有唯一解}" +
            "\\iff A\\text{的 }n\\text{个列向量线性无关}" + D + D
        val m = mathOf(src).single()
        // 2026-11-14 二轮：\iff 是推导链连接，剥 \text 时随标签走（转 Unicode ⟺），
        // 不再悬空留在公式行——标签顺序仍按源码顺序（本用例的核心意图不变）
        assertEquals(
            "可逆 ⟺ · 对任意 · 有唯一解 ⟺ A 的 n 个列向量线性无关",
            m.labels
        )
        assertTrue("公式行残留悬空 \\iff：\n${m.latex}", !m.latex.contains("\\iff"))
        // 剥标签处留 \,：两个 \mathbf{b} 之间不能粘成 "bb"
        assertTrue("b 粘连：\n${m.latex}", m.latex.contains("\\mathbf{b}\\,\\mathbf{b}"))
    }

    /** 图2/图3 caption：两个 \text{CJK} 夹 `p, r` = 一句话，并成一条标签、不留在公式里 */
    @Test
    fun `括号caption整句并成一条标签`() {
        val src = D + D +
            "\\begin{cases}ap+br=1\\\\cp+dr=0\\end{cases}\\text{（决定第一列}p, r\\text{）}" + D + D
        val m = mathOf(src).single()
        assertEquals("（决定第一列 p, r）", m.labels)
        assertTrue("caption 变量残留在公式里：\n${m.latex}", !m.latex.contains("p, r"))
        assertTrue("cases 应保留：\n${m.latex}", m.latex.contains("\\begin{cases}"))
    }

    /** 图4：推导链按顶层等号拆 3 行（行首悬挂 =），不再被 +/- 切成 5 个碎片 */
    @Test
    fun `等号拆行_推导链三行不切碎片`() {
        val latex = "-gae+gbd+adh-dbg=adh-gae=a(dh-eg)"
        val g = gatherIfNeeded(latex, naturalWem = 20f, maxWem = 8f)
        assertTrue("没拆行：\n$g", g.contains("\\begin{gathered}"))
        val lines = g.removePrefix("\\begin{gathered}").removeSuffix("\\end{gathered}")
            .split(" \\\\ ")
        assertEquals("应恰拆 3 行：\n$g", 3, lines.size)
        assertTrue("首行应完整保留整段加法：\n$g", lines[0].contains("+gbd"))
        assertTrue("行首悬挂等号：\n$g", lines[1].startsWith("=") && lines[2].startsWith("="))
    }

    @Test
    fun `等号拆行_括号与大括号内不拆_超过四段回退`() {
        // 大括号内（分数分子）的等号不是顶层：不拆
        assertEquals(null, splitAtTopEquals("\\frac{a=b}{c}"))
        // f(x)=1：括号已闭合，等号是顶层——两行合法
        assertEquals(listOf("f(x)", "=1"), splitAtTopEquals("f(x)=1"))
        // 5 段长链不硬拆（回退缩放由调用方兜底）
        assertEquals(null, splitAtTopEquals("a=b=c=d=e"))
        // 2~4 段可用
        assertEquals(listOf("x", "=1"), splitAtTopEquals("x=1"))
        assertEquals(3, splitAtTopEquals("a=b=c")!!.size)
    }

    /** 源码守卫：渲染失败必须走 repairTex 重试，不许直接躺平回退裸文本 */
    @Test
    fun `源码守卫_解析失败先走repairTex重试`() {
        val src = java.io.File("app/src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
            .let { if (it.exists()) it else java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt") }
            .readText()
        assertTrue("bitmap 缺 repairTex 重试路径", src.contains("repairTex(latex)"))
        assertTrue("repairTex 必须是 internal（androidTest 探针要调）",
            src.contains("internal fun repairTex"))
    }

    /** 2026-11-14 抽搐根修：块级公式 pending 期按估算预留高度——量级必须落在真实位图附近 */
    @Test
    fun `块级预留高度_估算量级贴真实`() {
        // 单行普通公式：真实位图 ~1.8em，估算 2.0em（误差 <0.5em）
        assertEquals(2.0f, estimateBlockEm("x+y=z"), 0.01f)
        // 分数/极限等高结构：真实 ~3.5em，估算 3.2em
        assertEquals(3.2f, estimateBlockEm("\\frac{a}{b}+\\lim_{n\\to\\infty}a_n"), 0.01f)
        // 多行（cases 两行）：真实 ~2 行高，估算 = 行数×1.8
        assertEquals(2 * 1.8f, estimateBlockEm("\\begin{cases}ap+br=1\\\\cp+dr=0\\end{cases}"), 0.01f)
        // gathered 拆行版（3 行）：也是按 \\ 数估
        assertEquals(3 * 1.8f, estimateBlockEm("\\begin{gathered}a=b \\\\ =c \\\\ =d\\end{gathered}"), 0.01f)
        // 尾随 \\ 分隔符不多算一行（dropLastWhile 空段）
        assertEquals(2.0f, estimateBlockEm("x=1\\\\".trimEnd('\\')), 0.01f)
        // 全域有界：极端嵌套会高估行数，钳在 7em 以内
        assertTrue(estimateBlockEm("\\begin{cases}" + "a\\\\ ".repeat(20) + "\\end{cases}") <= 7f)
    }

    /** 源码守卫：渲染派发必须单队列（无界并发=快速滚动掉帧根因）+ pending/失败分层预留 */
    @Test
    fun `源码守卫_渲染单队列与高度预留`() {
        val src = java.io.File("app/src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt")
            .let { if (it.exists()) it else java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt") }
            .readText()
        assertTrue("渲染派发必须 limitedParallelism(1)（无界并发=快速滚动掉帧）",
            src.contains("limitedParallelism(1)"))
        assertTrue("pending 与渲染失败必须分层（LatexSlot）", src.contains("LatexSlot.Pending"))
        assertTrue("LatexBlock 渲染中必须预留高度（2026-11-20 起参数为 displayLatex：拆行重渲的 Pending 也吃预留）", src.contains("estimateBlockEm(displayLatex)"))
    }

    // ------------------------------------------------------------------ 悬空推导箭头（2026-11-14 用户截图）

    /**
     * 用户截图「`1 ≠ -1  ⇒  ⇒` / 双侧极限不存在·不可导」：`$$…$$` 里
     * `1 \neq -1 \Rightarrow \text{…} \Rightarrow \text{…}` 剥中文后数学流残留两个悬空
     * `\Rightarrow`（连排+`\,` 间隙），中文被挤到公式下面。修法：剥除点两侧的推导箭头
     * 转 Unicode 随说明走——公式行只剩干净的数学，箭头在标签行里保住推导方向。
     */
    @Test
    fun `块级推导链的中文剥出后公式行不剩悬空箭头`() {
        val src = D + D + "\n1 \\neq -1 \\Rightarrow \\text{双侧极限不存在} \\Rightarrow \\text{不可导}\n" + D + D
        val m = mathOf(src).single()
        assertTrue("公式行残留悬空箭头：\n${m.latex}", !m.latex.contains("\\Rightarrow"))
        // 链式两个中文段是同一条推论：箭头连接、并成一个标签
        assertEquals("标签丢了推导箭头", "⇒ 双侧极限不存在 ⇒ 不可导", m.labels)
    }

    @Test
    fun `块级单段说明的前侧箭头随标签走`() {
        // keepFilter 只保留 CJK/字母数字/空格/中文标点（「·」会被滤）——用「，」连接
        val src = D + D + "\n1 \\neq -1 \\Rightarrow \\text{双侧极限不存在，不可导}\n" + D + D
        val m = mathOf(src).single()
        assertTrue("公式行残留悬空箭头：\n${m.latex}", !m.latex.contains("\\Rightarrow"))
        assertTrue("公式行数学丢了：\n${m.latex}", m.latex.contains("\\neq"))
        assertEquals("箭头没挂到说明头部", "⇒ 双侧极限不存在，不可导", m.labels)
    }

    /** 真数学里的 `\to`（`g(n)\to A`）不在剥除点旁，不得被剥 */
    @Test
    fun `非剥除点的真数学箭头不误伤`() {
        val src = D + D + "\ng(n)\\to A \\quad(\\text{右边是数，算完收工})\n" + D + D
        val m = mathOf(src).single()
        assertTrue("数学箭头 \\to 被误剥：\n${m.latex}", m.latex.contains("\\to"))
        assertEquals("标签丢了", "右边是数，算完收工", m.labels)
    }

    /** 行内同病同修：`$1 \neq -1 \Rightarrow \text{…}$` 拆件后正文带上箭头、数学段干净 */
    @Test
    fun `行内推导链拆件箭头随中文走`() {
        val pieces = splitInlineCjk("1 \\neq -1 \\Rightarrow \\text{双侧极限不存在·不可导}")
        val mathParts = pieces.filter { it.second }.joinToString("") { it.first }
        val prose = pieces.filter { !it.second }.joinToString(" ") { it.first }
        assertTrue("数学段残留箭头：$mathParts", !mathParts.contains("\\Rightarrow"))
        assertTrue("数学段丢了不等号", mathParts.contains("\\neq"))
        assertTrue("正文没带上箭头：$prose", prose.contains("⇒ 双侧极限不存在·不可导"))

        // 链式（两个中文段）：箭头独立小段拼在两段之间，读序 = 原文
        val chain = splitInlineCjk("1 \\neq -1 \\Rightarrow \\text{双侧极限不存在} \\Rightarrow \\text{不可导}")
        val chainProse = chain.filter { !it.second }.joinToString("|") { it.first }
        assertTrue("链式正文形态不对：$chainProse",
            chainProse == "⇒ 双侧极限不存在|⇒|不可导")
        assertTrue("链式数学段残留箭头", !chain.filter { it.second }.joinToString("") { it.first }.contains("\\Rightarrow"))
    }

    /** 纯函数：多箭头连剥、`\top` 不误当 `\to`、间距命令跟随剥除 */
    @Test
    fun `箭头剥除纯函数边界`() {
        // 多箭头连排（截图形态）：全剥、原序
        assertEquals("1 \\neq -1" to "⇒⇒", stripTrailingArrows("1 \\neq -1 \\Rightarrow \\Rightarrow"))
        // 间距命令跟随
        assertEquals("a=b" to "→", stripTrailingArrows("a=b \\, \\to"))
        // \top 不是 \to（全名匹配）
        assertEquals("x \\top" to "", stripTrailingArrows("x \\top"))
        // 前向：剥「间距+箭头」推进位置
        val s = " \\Rightarrow \\text{x}"
        val (next, syms) = stripLeadingArrows(s, 0)
        assertEquals("⇒", syms)
        assertEquals("\\text{x}", s.substring(next).trim())
        // 无箭头：原样返回
        assertEquals("a+b" to "", stripTrailingArrows("a+b"))
        assertEquals(0 to "", stripLeadingArrows("a+b", 0))
    }

    /** 「体积=」这类既有行内拆件不得被箭头逻辑改坏 */
    @Test
    fun `行内拆件既有形态回归`() {
        val pieces = splitInlineCjk("\\text{体积}=\\frac{4}{3}\\pi r^{3}")
        assertEquals("体积", pieces.first().first)
        assertTrue(pieces.first().second == false)
        assertTrue(pieces.last().second && pieces.last().first.contains("\\frac"))
    }

    // ============ 2026-11-16 用户截图「字体还是太小」（表格里的 \lim 公式）============

    /**
     * 公式渲染字号不得小于**正文口径**。
     *
     * 病根：公式字号 = 所在行字号 × 1.25。表格格与聊天卡片的 style 是 bodySmall(12sp)
     * → 同一道公式在表里只有 15sp，在正文里有 20sp。公式在这些格里是主要内容，不该
     * 跟着「辅助文字」的字号一起缩。
     */
    @Test
    fun `公式字号不低于正文口径`() {
        val body = 56f    // bodyLarge 16sp @3.5x
        val small = 42f   // bodySmall  12sp @3.5x
        // 表格/卡片：被抬到正文口径（16sp × 1.25）
        assertEquals("表格里的公式没抬到正文口径", body * 1.25f, mathRenderSizePx(small, body), 0.01f)
        // 正文：不变（与旧行为一致，不许改坏）
        assertEquals("正文公式字号变了", body * 1.25f, mathRenderSizePx(body, body), 0.01f)
        // 标题里的大公式：跟随所在行继续变大
        val h1 = 84f
        assertEquals("标题里的公式没跟着放大", h1 * 1.25f, mathRenderSizePx(h1, body), 0.01f)
    }

    /** 占位/行高上限是**唯一定义**：矩阵 6em、拆行 5.2em、普通 2.2em */
    @Test
    fun `占位上限三档唯一口径`() {
        assertEquals("矩阵上限变了", 6f, mathCapEm("\\begin{bmatrix}1&2\\\\3&4\\end{bmatrix}", false), 0f)
        assertEquals("拆行上限变了", 5.2f, mathCapEm("f(x)", gathered = true), 0f)
        assertEquals("普通上限变了", 2.2f, mathCapEm("\\lim_{x\\to c}f(x)", false), 0f)
        // 矩阵优先于 gathered（环境比拆行更宽）
        assertEquals("矩阵被判成拆行", 6f, mathCapEm("\\begin{cases}x&1\\end{cases}", gathered = true), 0f)
    }

    /** 字号抬升后上限必须同步放大，否则抬过的字号又被压回去 */
    @Test
    fun `上限随字号倍数放大`() {
        assertEquals("倍数 1.0 必须与旧版完全一致", 2.2f, mathHeightCapEm(2.2f, 1f), 0f)
        // 表格格：12sp→16sp = 1.333 倍
        assertEquals("上限没跟着放大", 2.2f * 4f / 3f, mathHeightCapEm(2.2f, 4f / 3f), 0.01f)
        // 退化输入（除零/负值）不许把上限压到 0
        assertEquals("退化倍数把上限压没了", 2.2f, mathHeightCapEm(2.2f, 0.1f), 0f)
    }

    /** 源码守卫：两处上限必须都走共享函数，不许再各写一套数字 */
    @Test
    fun `上限不许回到两处各写一套`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt").readText()
        assertTrue("行高预量没走 mathCapEm", src.contains("val baseCap = mathCapEm("))
        assertTrue("占位没走 mathCapEm", src.contains("hCap = mathCapEm(s.latex, usedGathered)"))
        // 2026-11-24 compactMath（表格格紧凑渲染）加了一条「本行字号」分支，
        // 但正文口径仍必须来自 mathRenderSizePx——这里钉的是字号真源不被绕过。
        assertTrue("字号没走 mathRenderSizePx", src.contains("mathRenderSizePx(fontPx, bodyPx)"))
        // 旧的散装写法不得回潮
        assertTrue(
            "占位上限又写回散装 when 分支了",
            !src.contains("hCap = when {")
        )
    }

    /**
     * 行高预量与占位必须用**同一档**上限。
     *
     * 2026-11-16 我自己踩的回归：把两处上限合并成 mathCapEm 时，预量那侧写死
     * `gathered = false` → 普通公式量到 2.2em，而正式渲染真拆了行会占 5.2em，
     * 行距预留不足 → 拆行公式顶穿上下行。这里钉住「预量也要先判会不会拆行」。
     */
    @Test
    fun `行高预量与占位同档`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt").readText()
        assertTrue(
            "预量没判拆行（gatherIfNeeded 的结果要参与上限选择）",
            src.contains("val gatheredLatex = natural?.let { n ->") &&
                src.contains("gatherIfNeeded(s.latex, n.first, maxWem).takeIf { it != s.latex }")
        )
        assertTrue(
            "预量没把 willGather 传进 mathCapEm",
            src.contains("val baseCap = mathCapEm(s.latex, willGather)")
        )
        assertTrue(
            "预量又写死 gathered=false 了（会导致拆行版顶穿行）",
            !src.contains("mathCapEm(s.latex, gathered = false)")
        )
    }

    // ── 2026-11-17 用户「文字被挤出 / 公式被切一半」──

    /**
     * 块级公式的摆放策略：装得下居中；装不下先等比缩到容器宽；缩到下限还装不下才横滑。
     */
    @Test
    fun `块级公式超宽先缩放再横滑`() {
        assertEquals(LatexFitMode.Fits, latexFitMode(100f, 200f))
        // 4% 余量：取整误差不算超宽（沿用 2026-11-13 假滑条修复）
        assertEquals(LatexFitMode.Fits, latexFitMode(104f, 100f))
        // 超宽 20%：缩到 0.83 → 缩放，不给滑动条
        assertEquals(LatexFitMode.Scaled, latexFitMode(120f, 100f))
        // 超宽太多：缩到 0.62 以下 → 退回横滑（缩了也读不了）
        assertEquals(LatexFitMode.Scroll, latexFitMode(400f, 100f))
        // 容器宽还没量到（首帧）→ 按"装得下"处理，不误报超宽
        assertEquals(LatexFitMode.Fits, latexFitMode(500f, 0f))
    }

    /** 缩放不能把公式压成蚂蚁：下限 0.62 */
    @Test
    fun `公式缩放有下限`() {
        assertEquals(0.62f, LATEX_MIN_SCALE, 0.0001f)
        // 刚好到下限 → 仍然缩放（不横滑）
        assertEquals(LatexFitMode.Scaled, latexFitMode(1000f / LATEX_MIN_SCALE, 1000f))
    }

    /**
     * 结构守卫：块级公式必须①首帧有宽度兜底 ②裁到自身边界 ③超宽先缩放。
     *
     * 2026-11-17 实锤：首帧 containerPx=0 → over=false → 固定自然宽的 Image 在
     * Row(Center) 里居中溢出。Compose 的 Row **不裁剪**，位图直接画到兄弟节点上，
     * 把公式下面那行中文盖掉、两端挤出屏幕（用户报「文字被挤出」）。
     */
    @Test
    fun `块级公式不许溢出盖住相邻文字`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/MarkdownMath.kt").readText()
        assertTrue(
            "LatexBlock 没有首帧宽度兜底（containerPx=0 时必须用屏幕宽先判一次）",
            src.contains("LocalConfiguration.current.screenWidthDp.dp.toPx()") &&
                src.contains("val effPx = if (containerPx > 0) containerPx.toFloat() else screenPx")
        )
        assertTrue(
            "LatexBlock 没有裁到自身边界（超宽位图会画到相邻行）",
            src.contains(".clipToBounds()")
        )
        assertTrue(
            "LatexBlock 没有走缩放策略（超宽公式只剩横滑，左半边永远被切）",
            src.contains("val mode = latexFitMode(naturalPx, effPx)") &&
                src.contains("val scale = if (mode == LatexFitMode.Scaled)")
        )
        assertTrue(
            "缩放时宽高没同比（会拉变形）",
            src.contains(".width(naturalW * scale)") && src.contains(".height(naturalH * scale)")
        )
    }

    /** 公式下方的标签行必须按容器宽换行，不许在无界宽里铺开后被裁 */
    @Test
    fun `公式标签行按容器宽换行`() {
        val src = java.io.File("src/main/java/io/github/m0use001/planability/ui/notes/NotesScreen.kt").readText()
        val idx = src.indexOf("if (s.labels.isNotBlank())")
        assertTrue("找不到公式标签行渲染点", idx >= 0)
        val window = src.substring(idx, idx + 1200)
        assertTrue("标签行没显式 softWrap=true（宽标签会溢出屏幕两端）", window.contains("softWrap = true"))
    }
}
