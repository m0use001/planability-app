package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 2026-11-10 图片直通视觉模型（用户：「图片给AI为什么强制OCR，我发个数学手写推到立即出问题」）：
 * user 消息里引用的 attachments 图片要转成多模态 part（不再只给 OCR 文本），
 * 且直通成功时改写「请用 file_read_image 读取」指令；剥图降级时指令原样保留。
 */
class ImageAttachSerializationTest {

    private val marker = "[附件已附: attachments/img_1.jpg 请用 file_read_image 读取]"

    /** OpenAI 兼容：user 消息 → text part（指令改写）+ image_url part（data URL） */
    @Test
    fun `user消息图片转image_url部分`() {
        val msgs = listOf(ChatApiMessage("user", "看看这题 $marker"))
        val arr = toApiMessages(msgs, imageLoader = { "data:image/jpeg;base64,QUJD" })
        val content = arr.getJSONObject(0).getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        assertEquals(
            "data:image/jpeg;base64,QUJD",
            content.getJSONObject(1).getJSONObject("image_url").getString("url")
        )
        val text = content.getJSONObject(0).getString("text")
        assertTrue("OCR 指令没改写：$text", !text.contains("请用 file_read_image 读取"))
        assertTrue(text.contains("attachments/img_1.jpg"))
    }

    /** 加载失败（文件没了）→ 整条回退纯文本，OCR 指令原样保留（file_read_image 兜底路径） */
    @Test
    fun `加载失败回退纯文本`() {
        val msgs = listOf(ChatApiMessage("user", "看看这题 $marker"))
        val arr = toApiMessages(msgs, imageLoader = { null })
        val content = arr.getJSONObject(0).getString("content")
        assertTrue(content.contains("请用 file_read_image 读取"))
    }

    /** 2026-11-11 起：最近一条 tool 消息直通（file_read_image 读到的快照喂给视觉模型） */
    @Test
    fun `最近一条tool消息直通`() {
        val msgs = listOf(
            ChatApiMessage("user", "读 8.1.ppt 第 4 页"),
            ChatApiMessage("tool", "[OK] [exports/8.1.slides/04.jpg] 第 1–260 字", toolCallId = "t1")
        )
        val arr = toApiMessages(msgs, imageLoader = { "data:image/jpeg;base64,QUJD" })
        val content = arr.getJSONObject(1).getJSONArray("content")
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
    }

    /** 历史 tool 消息不重发（只取最近一条，防撑爆请求体） */
    @Test
    fun `历史tool消息不直通`() {
        val msgs = listOf(
            ChatApiMessage("tool", "[OK] [exports/8.1.slides/01.jpg] 旧读的", toolCallId = "t1"),
            ChatApiMessage("assistant", "好"),
            ChatApiMessage("tool", "[OK] [exports/8.1.slides/02.jpg] 新读的", toolCallId = "t2")
        )
        val arr = toApiMessages(msgs, imageLoader = { "data:image/jpeg;base64,QUJD" })
        assertTrue(arr.getJSONObject(0).get("content") is String)
        assertTrue("最近一条 tool 消息应直通（content 变数组）", arr.getJSONObject(2).get("content") is org.json.JSONArray)
    }

    /** Anthropic 格式：image 块拆 base64 */
    @Test
    fun `anthropic格式转image块`() {
        val msgs = listOf(ChatApiMessage("user", "看 $marker"))
        val (_, arr) = toAnthropicMessages(msgs, imageLoader = { "data:image/png;base64,QUJD" })
        val blocks = arr.getJSONObject(0).getJSONArray("content")
        val img = (0 until blocks.length())
            .map { blocks.getJSONObject(it) }
            .first { it.optString("type") == "image" }
        val src = img.getJSONObject("source")
        assertEquals("base64", src.getString("type"))
        assertEquals("image/png", src.getString("media_type"))
        assertEquals("QUJD", src.getString("data"))
    }

    /** 每条消息最多直出 4 张 */
    @Test
    fun `图片数量封顶`() {
        val refs = (1..6).joinToString(" ") { "[附件已附: attachments/i$it.jpg 请用 file_read_image 读取]" }
        assertEquals(4, ImageAttach.imageRefsIn(refs).size)
    }

    /** splitDataUrl 拆 mime 与 base64 */
    @Test
    fun `dataUrl拆解`() {
        val (mime, b64) = ImageAttach.splitDataUrl("data:image/webp;base64,QUJD")!!
        assertEquals("image/webp", mime)
        assertEquals("QUJD", b64)
        assertEquals(null, ImageAttach.splitDataUrl("https://example.com/a.jpg"))
    }
}
