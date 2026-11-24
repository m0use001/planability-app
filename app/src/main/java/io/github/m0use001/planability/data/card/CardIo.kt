package io.github.m0use001.planability.data.card

import io.github.m0use001.planability.ai.AgentCard
import io.github.m0use001.planability.data.LoreEntry
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.zip.ZipInputStream

/**
 * v55 卡 / 世界书格式导入导出（只实现格式，不复制任何 AGPL/GPL 代码）。
 *
 * 支持：
 * - V2/V3 角色卡 JSON（`spec: chara_card_v2/v3`，含 `data.character_book`）；
 * - V1 平铺卡 JSON（字段直接在顶层）；
 * - PNG 内嵌卡（tEXt 块 keyword=`chara`，V3 为 `ccv3`，ccv3 优先）——纯字节扫描；
 * - charx（ZIP，读 `card.json`）；
 * - SillyTavern 世界书 JSON（`{entries:{uid:{key,keysecondary,content,...}}}`）导入导出往返。
 *
 * 明确不做：PNG 写回（要导出一律用 JSON）、zTXt/iTXt 压缩文本块、正则触发键、
 * recursion / sticky / cooldown / delay 字段（导入时忽略）。
 */
object CardIo {

    /** 导入结果：卡主人名 + 归一化卡 + 卡内嵌世界书（assistantId 由调用方填）。 */
    data class ImportedCard(
        val name: String = "",
        val card: AgentCard = AgentCard(),
        val lore: List<LoreEntry> = emptyList(),
    )

    /** 任意角色卡 JSON 文本 → ImportedCard；坏 JSON 返回 null。 */
    fun parseCharacterCard(text: String): ImportedCard? = runCatching {
        val root = JSONObject(text)
        val spec = root.optString("spec", "")
        val d = when {
            spec.startsWith("chara_card_v", ignoreCase = true) ->
                root.optJSONObject("data") ?: root
            root.optJSONObject("data")?.has("description") == true ->
                root.getJSONObject("data")
            else -> root
        }
        val depth = d.optJSONObject("extensions")?.optJSONObject("depth_prompt")
        val persona = buildString {
            fun part(label: String, v: String) {
                if (v.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append("【").append(label).append("】").append(v)
                }
            }
            part("设定", d.optString("description", ""))
            part("性格", d.optString("personality", ""))
            part("场景", d.optString("scenario", ""))
        }
        val card = AgentCard(
            persona = persona,
            styleExamples = d.optString("mes_example", ""),
            postHistory = d.optString("post_history_instructions", ""),
            depthText = depth?.optString("prompt", "") ?: "",
            depth = (depth?.optInt("depth", 4) ?: 4).coerceIn(0, 200),
            depthRole = depth?.optString("role", "system") ?: "system",
            greeting = d.optString("first_mes", ""),
            temperature = null,
        )
        ImportedCard(
            name = d.optString("name", root.optString("name", "")),
            card = card,
            lore = d.optJSONObject("character_book")?.let { parseCharacterBook(it) } ?: emptyList(),
        )
    }.getOrNull()

    /** V2/V3 `character_book` → 世界书条目（数组形态，enabled=false 跳过）。 */
    fun parseCharacterBook(book: JSONObject): List<LoreEntry> {
        val arr = book.optJSONArray("entries") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val e = arr.optJSONObject(i) ?: return@mapNotNull null
            if (!e.optBoolean("enabled", true)) return@mapNotNull null
            LoreEntry(
                assistantId = 0,
                name = e.optString("name", e.optString("comment", "")),
                keys = (e.optJSONArray("keys") ?: JSONArray()).toString(),
                secondKeys = (e.optJSONArray("secondary_keys") ?: JSONArray()).toString(),
                logic = logicFromInt(e.optInt("selectiveLogic", 0)),
                content = e.optString("content", ""),
                constant = e.optBoolean("constant", false),
                position = when (e.optString("position", "after_char")) {
                    "before_char" -> "desc_before"
                    else -> "desc_after"
                },
                depth = e.optInt("depth", 4),
                role = "system",
                order = e.optInt("insertion_order", 100),
                priority = e.optInt("priority", 1).coerceAtLeast(0),
                scanDepth = e.optInt("scanDepth", 0),
                probability = e.optInt("probability", 100),
                disabled = false,
            )
        }
    }

    /**
     * v56：本 App 的卡 → V2 角色卡 JSON（可再导入回来，可给别的酒馆前端用）。
     *
     * 为什么要导出：卡是「资产环」的一部分——导进去的卡拿不出来，等于只能单向消费
     * （用户换机、重装、备份、分享给别人都不行）。
     *
     * 映射约定：
     * - persona → `description`（含【性格】【场景】小节的一体文本）
     * - styleExamples → `mes_example`（附 `<START>` 分隔，导入端会再展开）
     * - postHistory → `post_history_instructions`
     * - depthText → `extensions.depth_prompt`
     * - greeting → `first_mes`
     * - 世界书 → `data.character_book`
     */
    fun exportCharacterCard(
        name: String,
        card: AgentCard,
        lore: List<LoreEntry> = emptyList(),
    ): String {
        val d = JSONObject().apply {
            put("name", name)
            put("description", card.persona)
            put("personality", "")
            put("scenario", "")
            put("first_mes", card.greeting)
            put("mes_example", card.styleExamples)
            put("creator_notes", "")
            put("system_prompt", "")
            put("post_history_instructions", card.postHistory)
            put("alternate_greetings", JSONArray())
            put("tags", JSONArray())
            if (lore.isNotEmpty()) {
                put("character_book", JSONObject().apply {
                    put("entries", JSONArray().apply {
                        lore.forEach { e ->
                            put(JSONObject().apply {
                                put("keys", JSONArray(e.keyList()))
                                put("secondary_keys", JSONArray(e.secondKeyList()))
                                put("content", e.content)
                                put("enabled", !e.disabled)
                                put("insertion_order", e.order)
                                put("position", if (e.position == "desc_before") "before_char" else "after_char")
                                put("priority", e.priority)
                                put("name", e.name)
                            })
                        }
                    })
                })
            }
            put("extensions", JSONObject().apply {
                put("depth_prompt", JSONObject().apply {
                    put("prompt", card.depthText)
                    put("depth", card.depth)
                    put("role", card.depthRole)
                })
            })
        }
        return JSONObject().apply {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", d)
        }.toString()
    }

    /** ST 世界书 JSON（`{entries:{uid:{...}}}`）→ 条目。 */
    fun parseWorldbook(text: String): List<LoreEntry> = runCatching {
        val entries = JSONObject(text).optJSONObject("entries") ?: return@runCatching emptyList()
        entries.keys().asSequence().mapNotNull { uid ->
            val e = entries.optJSONObject(uid) ?: return@mapNotNull null
            LoreEntry(
                assistantId = 0,
                name = e.optString("comment", e.optString("name", "")),
                keys = (e.optJSONArray("key") ?: JSONArray()).toString(),
                secondKeys = (e.optJSONArray("keysecondary") ?: JSONArray()).toString(),
                logic = logicFromInt(e.optInt("selectiveLogic", 0)),
                content = e.optString("content", ""),
                constant = e.optInt("constant", 0) == 1,
                position = when (e.optInt("position", 1)) {
                    0 -> "desc_before"
                    4 -> "depth"
                    else -> "desc_after"
                },
                depth = e.optInt("depth", 4),
                role = when (e.optInt("role", 0)) {
                    1 -> "user"
                    2 -> "assistant"
                    else -> "system"
                },
                order = e.optInt("order", 100),
                // ST 没有独立的 priority 字段：以 order 兼任选拔权重
                priority = e.optInt("order", 100),
                scanDepth = e.optInt("scanDepth", 0),
                probability = e.optInt("probability", 100),
                disabled = e.optInt("disable", 0) == 1,
            )
        }.toList()
    }.getOrDefault(emptyList())

    /** 条目 → ST 世界书 JSON（导出，可与 parseWorldbook 往返）。 */
    fun exportWorldbook(entries: List<LoreEntry>): String {
        val map = JSONObject()
        entries.forEachIndexed { i, e ->
            map.put(
                i.toString(),
                JSONObject().apply {
                    put("uid", i)
                    put("key", JSONArray(e.keyList()))
                    put("keysecondary", JSONArray(e.secondKeyList()))
                    put("comment", e.name)
                    put("content", e.content)
                    put("constant", if (e.constant) 1 else 0)
                    put("selective", true)
                    put("selectiveLogic", logicToInt(e.logic))
                    put("order", e.order)
                    put("position", when (e.position) {
                        "desc_before" -> 0
                        "depth" -> 4
                        else -> 1
                    })
                    put("depth", e.depth)
                    put("role", when (e.role) {
                        "user" -> 1
                        "assistant" -> 2
                        else -> 0
                    })
                    put("disable", if (e.disabled) 1 else 0)
                    put("probability", e.probability)
                    put("scanDepth", e.scanDepth)
                }
            )
        }
        return JSONObject().put("entries", map).toString()
    }

    /** PNG tEXt 内嵌卡：ccv3 优先于 chara；非 PNG / 无卡返回 null。 */
    fun cardTextFromPng(bytes: ByteArray): String? {
        if (bytes.size < 8) return null
        val sig = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        for (i in sig.indices) if (bytes[i] != sig[i]) return null
        var pos = 8
        var chara: String? = null
        var ccv3: String? = null
        while (pos + 12 <= bytes.size) {
            val len = readIntBE(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            when (type) {
                "tEXt" -> {
                    var zero = -1
                    for (i in 0 until len) {
                        if (bytes[dataStart + i] == 0.toByte()) { zero = i; break }
                    }
                    if (zero > 0) {
                        val keyword = String(bytes, dataStart, zero, Charsets.ISO_8859_1)
                        val value = String(bytes, dataStart + zero + 1, len - zero - 1, Charsets.ISO_8859_1)
                        val json = runCatching {
                            String(Base64.getDecoder().decode(value.trim()), Charsets.UTF_8)
                        }.getOrNull()
                        if (keyword == "ccv3") ccv3 = json
                        if (keyword == "chara" && chara == null) chara = json
                    }
                }
                "IEND" -> break
            }
            pos += 12 + len
        }
        return ccv3 ?: chara
    }

    /** charx（ZIP）→ `card.json` 文本；没有则 null。 */
    fun cardTextFromCharx(bytes: ByteArray): String? = runCatching {
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                if (entry.name == "card.json" || entry.name.endsWith("/card.json")) {
                    return String(zin.readBytes(), Charsets.UTF_8)
                }
                entry = zin.nextEntry
            }
        }
        null
    }.getOrNull()

    private fun readIntBE(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or (b[off + 3].toInt() and 0xFF)

    private fun logicFromInt(v: Int): String = when (v) {
        1 -> "NOT_ALL"
        2 -> "NOT_ANY"
        3 -> "AND_ALL"
        else -> "AND_ANY"
    }

    private fun logicToInt(v: String): Int = when (v) {
        "NOT_ALL" -> 1
        "NOT_ANY" -> 2
        "AND_ALL" -> 3
        else -> 0
    }
}
