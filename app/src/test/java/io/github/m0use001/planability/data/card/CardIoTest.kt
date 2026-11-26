package io.github.m0use001.planability.data.card

import io.github.m0use001.planability.ai.AgentCard
import io.github.m0use001.planability.data.LoreEntry
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CardIoTest {

    private fun b64(s: String): String = Base64.getEncoder().encodeToString(s.toByteArray(Charsets.UTF_8))

    private fun pngChunk(type: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        val n = data.size
        out.write(byteArrayOf((n ushr 24).toByte(), (n ushr 16).toByte(), (n ushr 8).toByte(), n.toByte()))
        out.write(type.toByteArray(Charsets.US_ASCII))
        out.write(data)
        out.write(byteArrayOf(0, 0, 0, 0)) // CRC：解析器不校验
        return out.toByteArray()
    }

    private fun pngWithText(vararg pairs: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        pairs.forEach { (k, v) ->
            val data = k.toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + v.toByteArray(Charsets.ISO_8859_1)
            out.write(pngChunk("tEXt", data))
        }
        out.write(pngChunk("IEND", ByteArray(0)))
        return out.toByteArray()
    }

    private fun zipWithCard(json: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry("card.json"))
            z.write(json.toByteArray(Charsets.UTF_8))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private val v2Card = """{
      "spec": "chara_card_v2",
      "spec_version": "2.0",
      "data": {
        "name": "◆",
        "description": "17 岁，湘西赶尸末代传人。",
        "personality": "性子闷，护短。",
        "scenario": "三峡口的旧城。",
        "mes_example": "<START>\n{{user}}: 你回来了？\n{{char}}: 嗯。",
        "first_mes": "铃声在雨里响了一下。",
        "post_history_instructions": "先问再动。",
        "extensions": { "depth_prompt": { "prompt": "第三人称限制视角。", "depth": 6, "role": "user" } },
        "character_book": {
          "entries": [
            { "keys": ["白帝城"], "secondary_keys": ["三峡"], "content": "旧城据点。", "enabled": true, "insertion_order": 200, "position": "before_char", "priority": 5 },
            { "keys": ["停用卡"], "content": "不该出现", "enabled": false }
          ]
        }
      }
    }""".trimIndent()

    @Test
    fun V2卡解析_字段落位与内嵌世界书() {
        val c = CardIo.parseCharacterCard(v2Card)!!
        assertEquals("◆", c.name)
        assertTrue(c.card.persona.contains("17 岁"))
        assertTrue(c.card.persona.contains("【性格】"))
        assertTrue(c.card.persona.contains("【场景】"))
        assertTrue(c.card.styleExamples.contains("<START>"))
        assertEquals("先问再动。", c.card.postHistory)
        assertEquals("第三人称限制视角。", c.card.depthText)
        assertEquals(6, c.card.depth)
        assertEquals("user", c.card.depthRole)
        assertEquals("铃声在雨里响了一下。", c.card.greeting)
        // 世界书：enabled=false 被跳过
        assertEquals(1, c.lore.size)
        val e = c.lore[0]
        assertEquals(listOf("白帝城"), e.keyList())
        assertEquals(listOf("三峡"), e.secondKeyList())
        assertEquals("desc_before", e.position)
        assertEquals(200, e.order)
        assertEquals(5, e.priority)
    }

    @Test
    fun V1平铺卡解析() {
        val v1 = """{"name":"旧卡","description":"设定","first_mes":"你好"}"""
        val c = CardIo.parseCharacterCard(v1)!!
        assertEquals("旧卡", c.name)
        assertEquals("【设定】设定", c.card.persona)
        assertEquals("你好", c.card.greeting)
        assertTrue(c.lore.isEmpty())
    }

    @Test
    fun 坏JSON返回null() {
        assertNull(CardIo.parseCharacterCard("not json"))
    }

    @Test
    fun 世界书导出再导入_字段往返() {
        fun arr(vararg s: String) = s.joinToString(",", "[", "]") { "\"$it\"" }
        val src = listOf(
            LoreEntry(
                id = 1, assistantId = 3, name = "◆", keys = arr("◆", "◆"),
                secondKeys = arr("主角"), logic = "AND_ALL", content = "17 岁",
                constant = false, position = "depth", depth = 6, role = "assistant",
                order = 200, priority = 200, scanDepth = 3, probability = 80, disabled = false,
            ),
            LoreEntry(
                id = 2, assistantId = 3, name = "常驻", keys = "[]", content = "三律",
                constant = true, position = "desc_after", order = 100, priority = 100,
            ),
        )
        val json = CardIo.exportWorldbook(src)
        val back = CardIo.parseWorldbook(json)
        assertEquals(2, back.size)
        val a = back.first { it.name == "◆" }
        assertEquals(listOf("◆", "◆"), a.keyList())
        assertEquals(listOf("主角"), a.secondKeyList())
        assertEquals("AND_ALL", a.logic)
        assertEquals("depth", a.position)
        assertEquals(6, a.depth)
        assertEquals("assistant", a.role)
        assertEquals(3, a.scanDepth)
        assertEquals(80, a.probability)
        val b = back.first { it.name == "常驻" }
        assertTrue(b.constant)
        assertEquals("desc_after", b.position)
    }

    @Test
    fun 坏世界书返回空() {
        assertTrue(CardIo.parseWorldbook("[]").isEmpty())
        assertTrue(CardIo.parseWorldbook("not json").isEmpty())
    }

    @Test
    fun PNG的chara块可读_ccv3优先() {
        val cardJson = """{"name":"PNG卡","description":"设定"}"""
        val onlyChara = pngWithText("chara" to b64(cardJson))
        assertEquals(cardJson, CardIo.cardTextFromPng(onlyChara))

        val ccv3Json = """{"name":"V3卡","description":"新"}"""
        val both = pngWithText("chara" to b64(cardJson), "ccv3" to b64(ccv3Json))
        assertEquals(ccv3Json, CardIo.cardTextFromPng(both))
    }

    @Test
    fun 非PNG返回null() {
        assertNull(CardIo.cardTextFromPng(byteArrayOf(1, 2, 3)))
        assertNull(CardIo.cardTextFromPng(ByteArray(0)))
    }

    @Test
    fun charx读取cardjson() {
        val json = """{"spec":"chara_card_v3","data":{"name":"ZIP卡"}}"""
        assertEquals(json, CardIo.cardTextFromCharx(zipWithCard(json)))
        assertNull(CardIo.cardTextFromCharx(ByteArray(4)))
    }

    @Test
    fun charx内容可继续走卡解析() {
        val json = """{"spec":"chara_card_v3","data":{"name":"ZIP卡","description":"设定"}}"""
        val text = CardIo.cardTextFromCharx(zipWithCard(json))!!
        val c = CardIo.parseCharacterCard(text)!!
        assertEquals("ZIP卡", c.name)
        assertTrue(c.card.persona.contains("设定"))
    }

    @Test
    fun 导出世界书根节点是entries对象() {
        val root = JSONObject(CardIo.exportWorldbook(emptyList()))
        assertNotNull(root.optJSONObject("entries"))
    }

    // ---------- v56：角色卡导出往返 ----------

    @Test
    fun 导出卡是合法V2且能再导入回来() {
        val src = AgentCard(
            persona = "17 岁，湘西赶尸末代传人。",
            styleExamples = "---\n我: 你回来了？\n◆: 嗯。",
            postHistory = "先问再动。",
            depthText = "第三人称限制视角。",
            depth = 6,
            depthRole = "user",
            greeting = "铃声在雨里响了一下。",
        )
        val lore = listOf(
            LoreEntry(name = "白帝城", keys = """["白帝城"]""", content = "旧城据点。",
                position = "desc_before", order = 200, priority = 5)
        )
        val json = CardIo.exportCharacterCard("◆", src, lore)
        val root = JSONObject(json)
        assertEquals("chara_card_v2", root.optString("spec"))
        assertEquals("2.0", root.optString("spec_version"))
        assertNotNull("必须有 data 对象", root.optJSONObject("data"))

        // 往返：导出的卡再走导入器，关键字段不能丢。
        // 注意：导入器会给 description/personality/scenario 套「【设定】/【性格】/【场景】」小节
        // （它要把三段合成一段 persona），所以人设正文只断言「原样包含」，不要求逐字相等。
        val back = CardIo.parseCharacterCard(json)!!
        assertEquals("◆", back.name)
        assertTrue(
            "人设正文丢了：${back.card.persona}",
            back.card.persona.contains(src.persona)
        )
        // 文风示例：导入器可能补 `<START>` 分隔（酒馆惯例），只要求正文原样保留
        assertTrue(
            "文风示例正文丢了：${back.card.styleExamples}",
            back.card.styleExamples.contains(src.styleExamples)
        )
        assertEquals(src.postHistory, back.card.postHistory)
        assertEquals(src.depthText, back.card.depthText)
        assertEquals(6, back.card.depth)
        assertEquals("user", back.card.depthRole)
        assertEquals(src.greeting, back.card.greeting)
        assertEquals(1, back.lore.size)
        assertEquals(listOf("白帝城"), back.lore[0].keyList())
        assertEquals("desc_before", back.lore[0].position)
    }

    @Test
    fun 导出无世界书的卡不塞空character_book() {
        val json = CardIo.exportCharacterCard("某人", AgentCard(persona = "设定"))
        val d = JSONObject(json).getJSONObject("data")
        assertNull("没条目就别写 character_book", d.optJSONObject("character_book"))
    }
}
