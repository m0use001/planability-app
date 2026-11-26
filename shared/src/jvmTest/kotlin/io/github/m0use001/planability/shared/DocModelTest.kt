package io.github.m0use001.planability.shared

import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * DocModel / SlideLayout 往返与排版（M2 收官批迁移时新增：这两块原先只有 app 侧的
 * PptxWriterTest / OfficeTextTest 间接覆盖，没有直接的模型/排版断言）。
 */
class DocModelTest {

    @Test
    fun `deck JSON 往返保字段`() {
        val deck = DocModel.Deck(
            title = "季度汇报",
            slides = listOf(
                DocModel.Slide(
                    layout = DocModel.SlideLayout.TITLE,
                    title = "封面",
                    subtitle = "2026 Q4",
                    notes = "讲两句",
                    pageNo = 1
                ),
                DocModel.Slide(
                    layout = DocModel.SlideLayout.BULLETS,
                    title = "要点",
                    blocks = listOf(
                        DocModel.Block.Bullets(listOf("一", "二")),
                        DocModel.Block.Para("段", bold = true),
                        DocModel.Block.Table(listOf(listOf("h1", "h2"))),
                        DocModel.Block.Picture("a.png", "图注")
                    ),
                    pageNo = 2
                )
            ),
            theme = DocModel.Theme(dark = true),
            author = "phone-c"
        )
        val json = DocModel.deckToJson(deck)
        assertEquals("季度汇报", json.getString("title"))
        assertEquals("phone-c", json.getString("author"))
        assertEquals(2, json.getJSONArray("slides").length())
        assertTrue(json.getJSONObject("theme").getBoolean("dark"))

        // 回读：layout/notes/pageNo/blocks 都要保住（改稿靠 pageNo 定位）
        val back = DocModel.deckFromJson("季度汇报", DocModel.Theme(dark = true), json.getJSONArray("slides"))
        assertEquals(2, back.slides.size)
        assertEquals(DocModel.SlideLayout.TITLE, back.slides[0].layout)
        assertEquals("讲两句", back.slides[0].notes)
        assertEquals(0, back.slides[1].pageNo, "deckFromJson 不填 pageNo（那是 PptxEditor 读盘时的职责）")
        assertEquals(4, back.slides[1].blocks.size)
    }

    @Test
    fun `宽容解析_未知layout落BULLETS_坏页只丢自己`() {
        val arr = JSONArray(
            """
            [
              {"layout":"不存在的版式","title":"甲","blocks":[{"type":"text","text":"正文"}]},
              {"layout":"bullets","title":"乙","blocks":[{"type":"bullets","items":["x","y"]}]}
            ]
            """.trimIndent()
        )
        val deck = DocModel.deckFromJson("t", DocModel.Theme(), arr)
        assertEquals(2, deck.slides.size, "单页出问题不能拖垮整份")
        assertEquals(DocModel.SlideLayout.BULLETS, deck.slides[0].layout, "未知 layout 落默认")
        assertEquals("正文", (deck.slides[0].blocks.first() as DocModel.Block.Para).text)
        assertEquals(listOf("x", "y"), (deck.slides[1].blocks.first() as DocModel.Block.Bullets).items)
    }

    @Test
    fun `主题缺省与覆盖`() {
        val t = DocModel.themeFromJson(null)
        assertEquals("默认", t.name)
        assertTrue(!t.dark)
        val t2 = DocModel.themeFromJson(JSONObject().put("name", "夜").put("dark", true).put("accent", "FF0000"))
        assertEquals("夜", t2.name)
        assertTrue(t2.dark)
        // 深色主题三色必须是深底浅字
        assertEquals("11131A", t2.bgHex)
        assertEquals("F2F4F8", t2.fgHex)
    }

    @Test
    fun `排版_标题页标题更大且居中_内容按序下移`() {
        val theme = DocModel.Theme()
        val titlePage = SlideLayout.page(
            DocModel.Slide(layout = DocModel.SlideLayout.TITLE, title = "封面", subtitle = "副"),
            theme
        )
        val titleText = titlePage.items[0] as SlideLayout.Item.Text
        assertEquals(DocModel.Geometry.TITLE_SZ_BIG, titleText.sz)
        assertEquals("ctr", titleText.align)
        val subText = titlePage.items[1] as SlideLayout.Item.Text
        assertEquals("ctr", subText.align, "封面副标题同样居中")

        val bodyPage = SlideLayout.page(
            DocModel.Slide(
                layout = DocModel.SlideLayout.BULLETS,
                title = "要点",
                blocks = listOf(DocModel.Block.Para("段"))
            ),
            theme
        )
        val bodyTitle = bodyPage.items[0] as SlideLayout.Item.Text
        assertEquals(DocModel.Geometry.TITLE_SZ, bodyTitle.sz)
        assertEquals("l", bodyTitle.align)
        // 后面的图元必须在上一个之下（y 单调递增，除表格内的行）
        val ys = bodyPage.items.map { it.y() }
        assertEquals(ys.sorted(), ys, "图元按序下移")
        // 所有图元都在页内
        bodyPage.items.forEach {
            assertTrue(it.x() >= 0 && it.x() + it.w() <= DocModel.Geometry.SLIDE_W, "横向不越界")
            assertTrue(it.y() >= 0 && it.y() + it.h() <= DocModel.Geometry.SLIDE_H, "纵向不越界")
        }
    }

    @Test
    fun `几何换算_EMU到像素按页宽等比`() {
        assertEquals(100, DocModel.Geometry.emuToPx(DocModel.Geometry.SLIDE_W, 100))
        assertEquals(50, DocModel.Geometry.emuToPx(DocModel.Geometry.SLIDE_W / 2, 100))
        assertTrue(DocModel.Geometry.szToPx(100, 1000) >= 1, "字号换算不得归零")
    }
}

private fun SlideLayout.Item.x(): Long = when (this) {
    is SlideLayout.Item.Text -> x
    is SlideLayout.Item.Bullets -> x
    is SlideLayout.Item.Picture -> x
    is SlideLayout.Item.Table -> x
}

private fun SlideLayout.Item.y(): Long = when (this) {
    is SlideLayout.Item.Text -> y
    is SlideLayout.Item.Bullets -> y
    is SlideLayout.Item.Picture -> y
    is SlideLayout.Item.Table -> y
}

private fun SlideLayout.Item.w(): Long = when (this) {
    is SlideLayout.Item.Text -> w
    is SlideLayout.Item.Bullets -> w
    is SlideLayout.Item.Picture -> w
    is SlideLayout.Item.Table -> w
}

private fun SlideLayout.Item.h(): Long = when (this) {
    is SlideLayout.Item.Text -> h
    is SlideLayout.Item.Bullets -> h
    is SlideLayout.Item.Picture -> h
    is SlideLayout.Item.Table -> h
}
