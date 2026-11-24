package io.github.m0use001.planability.ui.notes

import io.github.m0use001.planability.data.NoteEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v53.8 笔记列表的纯文本层。
 *
 * 这些断言全部来自真机截图上的丑：标题与副行一字不差、`# Alpha` 原样露出、
 * `[[Alpha]]` 把方括号带到列表上、以及"这篇到底多长"完全没有量感。
 */
class NotePreviewTest {

    private fun note(title: String, content: String) =
        NoteEntity(id = 1, notebookId = 1, title = title, kind = "md", content = content)

    @Test
    fun titleFollowsEditorRule() {
        assertEquals("实验报告", NotePreview.titleOf("# 实验报告\n正文"))
        assertEquals("无井号也行", NotePreview.titleOf("无井号也行"))
        assertEquals("", NotePreview.titleOf("\n\n"))
    }

    /**
     * 真机踩到过：用「匹配长度」去截标题，`# Gamma` 渲染成 "amma"
     * （匹配把"后面有内容"的那个字符也算进了长度）。
     */
    @Test
    fun splitHeadingKeepsTheFirstCharacter() {
        assertEquals(1 to "Gamma", NotePreview.splitHeading("# Gamma"))
        assertEquals(2 to "sec", NotePreview.splitHeading("## sec"))
        assertEquals(3 to "中文标题", NotePreview.splitHeading("### 中文标题"))
        // 国人不写空格
        assertEquals(1 to "标题", NotePreview.splitHeading("#标题"))
        // 缩进标题
        assertEquals(2 to "缩进", NotePreview.splitHeading("   ## 缩进"))
        // 空标题不成标题，免得把 `###` 画成空块
        assertNull(NotePreview.splitHeading("###"))
        assertEquals(null, NotePreview.splitHeading("普通正文"))
        // 七个 # 不是标题（CommonMark 上限 6 级）
        assertEquals(null, NotePreview.splitHeading("####### 太深"))
    }

    @Test
    fun previewSkipsTheLineThatRepeatsTheTitle() {
        val n = note("Alpha", "# Alpha\n\n第一段正文")
        assertEquals("第一段正文", NotePreview.body(n))
    }

    @Test
    fun previewKeepsBodyWhenTitleComesFromElsewhere() {
        // 标题与首行不同（例如 AI 改了标题），首行必须留在预览里
        val n = note("旧标题", "# 新标题\n正文")
        assertEquals("新标题 正文", NotePreview.body(n))
    }

    @Test
    fun markdownMarkersAreScrubbed() {
        val n = note("T", "T\n- [ ] 待办\n- 要点\n> 引用\n**粗** 与 `码`")
        val b = NotePreview.body(n)
        assertFalse("列表里不该再出现 - [ ] 这种原始标记", b.contains("[ ]"))
        assertTrue(b.contains("待办"))
        assertTrue(b.contains("· 要点"))
        assertTrue(b.contains("引用"))
        assertFalse(b.contains("**"))
        assertFalse(b.contains("`"))
    }

    @Test
    fun wikilinksShowTheirLabel() {
        assertEquals("看 那门课 与 微积分",
            NotePreview.stripLine("看 [[微积分|那门课]] 与 [[微积分]]"))
    }

    @Test
    fun tablesBecomeReadableLine() {
        assertEquals("项目 · 值", NotePreview.stripLine("| 项目 | 值 |"))
    }

    @Test
    fun emptyBodyHasAReadablePlaceholder() {
        assertEquals("（还没有正文）", NotePreview.body(note("空", "# 空\n\n")))
    }

    @Test
    fun wordCountCountsCjkPerCharAndLatinPerWord() {
        assertEquals(4, NotePreview.wordCount("实验报告"))
        assertEquals(2, NotePreview.wordCount("hello world"))
        assertEquals(0, NotePreview.wordCount("   \n\n"))
        // 中英混排：4 个汉字 + 1 个词
        assertEquals(5, NotePreview.wordCount("实验报告 done"))
    }

    @Test
    fun relativeTimeWalksTheCalendarNot24hWindows() {
        fun at(y: Int, m: Int, d: Int, h: Int, min: Int) = Calendar.getInstance().apply {
            set(y, m - 1, d, h, min, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        val now = at(2026, 11, 6, 14, 30)
        // 超过一小时才走日历日：昨天 21:00 与 3 天前都按"日"给
        assertEquals("昨天", NotePreview.relativeTime(at(2026, 11, 5, 21, 0), now))
        assertEquals("3 天前", NotePreview.relativeTime(at(2026, 11, 3, 9, 0), now))
        assertEquals("今天 11:20", NotePreview.relativeTime(at(2026, 11, 6, 11, 20), now))
        assertEquals("今天 09:10", NotePreview.relativeTime(at(2026, 11, 6, 9, 10), now))
        assertEquals("10月2日", NotePreview.relativeTime(at(2026, 10, 2, 9, 0), now))
        assertEquals("2025年12月31日", NotePreview.relativeTime(at(2025, 12, 31, 9, 0), now))
        // 跨零点的特例：一小时以内一律按分钟说——「20 分钟前」比「昨天」更有用（列表场景）
        val midnight = at(2026, 11, 6, 0, 10)
        assertEquals("20 分钟前", NotePreview.relativeTime(at(2026, 11, 5, 23, 50), midnight))
    }

    @Test
    fun relativeTimeRecentBuckets() {
        val now = System.currentTimeMillis()
        assertEquals("刚刚", NotePreview.relativeTime(now - 5_000, now))
        assertEquals("3 分钟前", NotePreview.relativeTime(now - 3 * 60_000, now))
    }

    /**
     * 真机踩到：两条笔记的 updatedAt 是**次日 00:00**（时钟偏差 / 旧版本按当天 0 点落库），
     * 列表于是显示「11月7日」——看起来像"明天更新过"。未来时间一律按「刚刚」。
     */
    @Test
    fun futureTimestampShowsAsJustNow() {
        val now = 1_800_000_000_000L
        assertEquals("刚刚", NotePreview.relativeTime(now + 3_600_000, now))
        assertEquals("刚刚", NotePreview.relativeTime(now + 86_400_000, now))
    }
}
