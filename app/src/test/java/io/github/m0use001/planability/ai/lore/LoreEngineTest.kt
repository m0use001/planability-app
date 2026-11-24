package io.github.m0use001.planability.ai.lore

import io.github.m0use001.planability.data.LoreEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LoreEngineTest {

    private var nextId = 1L

    private fun entry(
        name: String = "条目",
        keys: List<String> = listOf("关键词"),
        secondKeys: List<String> = emptyList(),
        logic: String = LoreEngine.LOGIC_AND_ANY,
        content: String = "正文内容",
        constant: Boolean = false,
        position: String = LoreEngine.POS_DESC_AFTER,
        depth: Int = 4,
        role: String = "system",
        order: Int = 100,
        priority: Int = 1,
        scanDepth: Int = 0,
        probability: Int = 100,
        disabled: Boolean = false,
    ): LoreEntry {
        fun arr(list: List<String>) =
            "[" + list.joinToString(",") { "\"" + it + "\"" } + "]"
        return LoreEntry(
            id = nextId++,
            assistantId = 1,
            name = name,
            keys = arr(keys),
            secondKeys = arr(secondKeys),
            logic = logic,
            content = content,
            constant = constant,
            position = position,
            depth = depth,
            role = role,
            order = order,
            priority = priority,
            scanDepth = scanDepth,
            probability = probability,
            disabled = disabled,
        )
    }

    private fun texts(vararg t: String): List<String> = t.toList() // 新→旧

    @Test
    fun 主键命中才注入() {
        val e = entry(keys = listOf("白帝城"))
        val hit = LoreEngine.activate(listOf(e), texts("他说白帝城那边下雨了"), 10_000)
        assertEquals(1, hit.size)
        val miss = LoreEngine.activate(listOf(e), texts("今天天气不错"), 10_000)
        assertTrue(miss.isEmpty())
    }

    @Test
    fun 主键大小写不敏感() {
        val e = entry(keys = listOf("Alpha"))
        assertEquals(1, LoreEngine.activate(listOf(e), texts("alpha 来了"), 10_000).size)
    }

    @Test
    fun 副键AND_ANY_任一命中() {
        val e = entry(keys = listOf("白帝城"), secondKeys = listOf("三峡", "江陵"), logic = LoreEngine.LOGIC_AND_ANY)
        assertEquals(1, LoreEngine.activate(listOf(e), texts("白帝城在三峡口"), 10_000).size)
        assertEquals(1, LoreEngine.activate(listOf(e), texts("白帝城到了江陵"), 10_000).size)
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城到了重庆"), 10_000).isEmpty())
    }

    @Test
    fun 副键AND_ALL_全命中() {
        val e = entry(keys = listOf("白帝城"), secondKeys = listOf("三峡", "江陵"), logic = LoreEngine.LOGIC_AND_ALL)
        assertEquals(1, LoreEngine.activate(listOf(e), texts("白帝城 三峡 江陵"), 10_000).size)
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城 三峡"), 10_000).isEmpty())
    }

    @Test
    fun 副键NOT_ANY_全不命中才注入() {
        val e = entry(keys = listOf("白帝城"), secondKeys = listOf("三峡", "江陵"), logic = LoreEngine.LOGIC_NOT_ANY)
        assertEquals(1, LoreEngine.activate(listOf(e), texts("白帝城到了重庆"), 10_000).size)
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城在三峡"), 10_000).isEmpty())
    }

    @Test
    fun 副键NOT_ALL_非全命中() {
        val e = entry(keys = listOf("白帝城"), secondKeys = listOf("三峡", "江陵"), logic = LoreEngine.LOGIC_NOT_ALL)
        assertEquals(1, LoreEngine.activate(listOf(e), texts("白帝城在三峡"), 10_000).size)
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城三峡江陵"), 10_000).isEmpty())
    }

    @Test
    fun 常驻条目无条件注入且不受概率影响() {
        val e = entry(
            name = "世界观", keys = listOf("绝不出现的词"), content = "三律",
            constant = true, probability = 0,
        )
        val hit = LoreEngine.activate(listOf(e), texts("随便聊聊"), 10_000)
        assertEquals(1, hit.size)
        assertEquals("世界观", hit[0].name)
    }

    @Test
    fun 停用条目永不参与() {
        val e = entry(keys = listOf("白帝城"), disabled = true)
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城"), 10_000).isEmpty())
    }

    @Test
    fun 空内容跳过() {
        val e = entry(keys = listOf("白帝城"), content = "  ")
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城"), 10_000).isEmpty())
    }

    @Test
    fun 无主键非常驻永不命中() {
        val e = entry(keys = emptyList())
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城"), 10_000).isEmpty())
    }

    @Test
    fun 扫描深度限制在最近N条() {
        val e = entry(keys = listOf("白帝城"), scanDepth = 0)
        // 关键词只出现在第 3 条（旧），默认深度 2 → 不命中
        assertTrue(LoreEngine.activate(listOf(e), texts("最新", "中间", "旧文提到白帝城"), 10_000, defaultScanDepth = 2).isEmpty())
        // 深度 3 → 命中
        assertEquals(1, LoreEngine.activate(listOf(e), texts("最新", "中间", "旧文提到白帝城"), 10_000, defaultScanDepth = 3).size)
        // 条目覆写深度 1 → 又不命中
        val narrow = e.copy(scanDepth = 1)
        assertTrue(LoreEngine.activate(listOf(narrow), texts("最新", "中间", "旧文提到白帝城"), 10_000, defaultScanDepth = 3).isEmpty())
    }

    @Test
    fun 概率零不中_概率百必中() {
        val never = entry(keys = listOf("白帝城"), probability = 0)
        val always = entry(keys = listOf("白帝城"), probability = 100)
        assertTrue(LoreEngine.activate(listOf(never), texts("白帝城"), 10_000).isEmpty())
        assertEquals(1, LoreEngine.activate(listOf(always), texts("白帝城"), 10_000).size)
    }

    @Test
    fun 预算整条计数_超预算跳过但继续尝试更小的() {
        val big = entry(keys = listOf("白帝城"), content = "B".repeat(100), priority = 9)
        val small = entry(keys = listOf("白帝城"), content = "s", priority = 5)
        // big 成本 10、small 成本 1；预算 5 → big 装不下被跳过，small 入选（整条进/整条出）
        val hit = LoreEngine.activate(
            listOf(big, small), texts("白帝城"), maxTokens = 5,
            tokenCost = { if (it.length > 50) 10 else 1 },
        )
        assertEquals(1, hit.size)
        assertEquals(small.id, hit[0].id)
    }

    @Test
    fun priority选拔_order排位() {
        val a = entry(keys = listOf("白帝城"), content = "A", priority = 1, order = 300)
        val b = entry(keys = listOf("白帝城"), content = "B", priority = 9, order = 100)
        val c = entry(keys = listOf("白帝城"), content = "C", priority = 5, order = 200)
        val hit = LoreEngine.activate(listOf(a, b, c), texts("白帝城"), 10_000, tokenCost = { 1 })
        // 全部入选后按 order 降序：a(300) → c(200) → b(100)
        assertEquals(listOf(a.id, c.id, b.id), hit.map { it.id })
    }

    @Test
    fun 预算只有一格时常驻优先于高priority条目() {
        val constant = entry(name = "常驻", constant = true, content = "K", priority = 0)
        val strong = entry(name = "高优先", keys = listOf("白帝城"), content = "S", priority = 100)
        val hit = LoreEngine.activate(
            listOf(strong, constant), texts("白帝城"), maxTokens = 1, tokenCost = { 1 },
        )
        assertEquals(1, hit.size)
        assertEquals("常驻", hit[0].name)
    }

    @Test
    fun 深度与角色缺省夹取() {
        val e = entry(keys = listOf("白帝城"), depth = 0, role = "")
        val hit = LoreEngine.activate(listOf(e), texts("白帝城"), 10_000)
        assertEquals(1, hit.size)
        assertEquals(4, hit[0].depth)
        assertEquals("system", hit[0].role)
    }

    @Test
    fun 坏键JSON条目被忽略() {
        val e = entry(keys = listOf("白帝城")).copy(keys = "[broken")
        assertTrue(LoreEngine.activate(listOf(e), texts("白帝城"), 10_000).isEmpty())
    }
}
