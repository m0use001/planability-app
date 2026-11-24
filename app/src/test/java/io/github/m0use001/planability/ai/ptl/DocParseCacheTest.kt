package io.github.m0use001.planability.ai.ptl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 2026-11-15 用户「为什么 deepseek 余额烧得这么快」的排查结论里落地的一条：
 * 云端读文件是**按页计费**（1.5 元/千页），而 AI 一轮任务常把同一份文件读好几遍
 * （重读、分段、24 轮工具循环）——每次整份上云都重新计费。加 (路径, 大小, mtime)
 * 落盘缓存后，同一份文件重读不再花钱。
 *
 * 这里只测**纯函数**（缓存键与序列化）；「发请求前必查缓存」那条源码顺序断言
 * 放在 DeepSeekCloudTest（它已有既成的源码读取器，不再新增文件路径处理）。
 */
class DocParseCacheTest {

    @Test
    fun `缓存键随任一维度变化而变`() {
        val base = DeepSeekCloud.docCacheKeyOf("/a/b.pdf", 1000L, 1_700_000_000_000L)
        assertEquals(
            "同输入必须同键（否则永远命中不了）",
            base, DeepSeekCloud.docCacheKeyOf("/a/b.pdf", 1000L, 1_700_000_000_000L)
        )
        assertNotEquals("大小变了必须换键", base, DeepSeekCloud.docCacheKeyOf("/a/b.pdf", 1001L, 1_700_000_000_000L))
        assertNotEquals("mtime 变了必须换键（文件改过要重新解析）", base, DeepSeekCloud.docCacheKeyOf("/a/b.pdf", 1000L, 1_700_000_000_001L))
        assertNotEquals("路径变了换键", base, DeepSeekCloud.docCacheKeyOf("/a/c.pdf", 1000L, 1_700_000_000_000L))
    }

    @Test
    fun `缓存序列化往返 空文本视为无效`() {
        val p = DeepSeekCloud.DocParse("[云端解析（DeepSeek）· 共 3 页]\n[第1页] …", 3)
        val back = DeepSeekCloud.decodeDocCache(DeepSeekCloud.encodeDocCache(p))!!
        assertEquals(p.text, back.text)
        assertEquals(3, back.pages)
        // 坏 JSON / 空文本 → null（绝不把"缓存坏了"当成"文件是空的"）
        assertNull(DeepSeekCloud.decodeDocCache("not json"))
        assertNull(DeepSeekCloud.decodeDocCache("""{"text":"","pages":2}"""))
    }
}
