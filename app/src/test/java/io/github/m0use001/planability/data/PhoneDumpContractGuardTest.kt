package io.github.m0use001.planability.data

import io.github.m0use001.planability.shared.DumpContract
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **phone-dump 契约 drift 守卫（M4，会红的门）**。
 *
 * 真源 = `:shared` 的 [DumpContract]（机器可读字段清单）；
 * 被检方 = `data/PhoneDump.kt` 生产者源码里每个集合实际 `put` 出的字段。
 *
 * 会红的两向：
 * - **多字段**（生产者加了 PC 端不认识的字段）→ PC 端 mergeDump 静默丢弃或报错 → FAIL；
 * - **少字段**（删了/改名了某字段）→ PC 端拿到残缺记录、老数据覆盖出新字段 → FAIL。
 *
 * 植入故障验证：给任一集合加一个 `o.put("rogue_field", …)` 再跑，本测试必红（见 M4 交付记录）。
 */
class PhoneDumpContractGuardTest {

    private fun phoneDumpSource(): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" },
            "app/src/main/java/io/github/m0use001/planability/data/PhoneDump.kt")
        assertTrue("PhoneDump.kt 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    /** 从生产者源码切出 `ent.put("<collection>", …)` 的每个集合段 */
    private fun sections(src: String): Map<String, String> {
        val marker = "ent.put(\""
        val out = LinkedHashMap<String, String>()
        var idx = src.indexOf(marker)
        val endMark = src.indexOf("root.put(\"entity\"")
        while (idx >= 0) {
            val nameStart = idx + marker.length
            val nameEnd = src.indexOf('"', nameStart)
            val name = src.substring(nameStart, nameEnd)
            val next = src.indexOf(marker, nameEnd)
            val stop = if (next >= 0) next else endMark
            out[name] = src.substring(nameEnd, if (stop >= 0) stop else src.length)
            idx = next
        }
        return out
    }

    /** 一个集合段里实际产出的全部字段名（o.put / putIf / 链式 .put 都算） */
    private fun emittedFields(sectionSrc: String): Set<String> {
        val fields = HashSet<String>()
        Regex("""\.put\("([A-Za-z_]+)"""").findAll(sectionSrc).forEach { fields += it.groupValues[1] }
        Regex("""putIf\(o, "([A-Za-z_]+)"""").findAll(sectionSrc).forEach { fields += it.groupValues[1] }
        // apply 块里的裸 put（如 memory_scenes 的 project：`if (sc.projectId > 0) put("project", …)`）
        Regex("""(?<![.\w])put\("([A-Za-z_]+)"""").findAll(sectionSrc).forEach { fields += it.groupValues[1] }
        return fields
    }

    @Test
    fun `schemaVersion 与 shared 契约一致`() {
        assertTrue(
            "PhoneDump 的 schemaVersion 与 DumpContract.SCHEMA_VERSION 漂了——改一端必须两端同步（PC 端同时接受 1/2 的过渡期结束时要一起抬）",
            phoneDumpSource().contains("root.put(\"schemaVersion\", ${DumpContract.SCHEMA_VERSION})")
        )
    }

    @Test
    fun `每个集合的产出字段与 shared 契约逐字段一致`() {
        val src = phoneDumpSource()
        val sections = sections(src)
        assertEquals(
            "PhoneDump.kt 的集合清单与 DumpContract.COLLECTION_FIELDS 不一致（多/少集合都是漂移）",
            DumpContract.COLLECTION_FIELDS.keys.sorted(),
            sections.keys.sorted()
        )
        DumpContract.COLLECTION_FIELDS.forEach { (collection, contractSpecific) ->
            val section = sections[collection]
                ?: return@forEach // 集合缺失已在上面断言报过
            val emitted = emittedFields(section)
            val emittedSpecific = emitted - DumpContract.COMMON_FIELDS
            val extra = emittedSpecific - contractSpecific
            val missing = contractSpecific - emittedSpecific
            assertTrue(
                "[$collection] 字段漂移：多出 $extra（PC 端不认识）；缺失 $missing（PC 端拿到残记录）。" +
                    "改字段必须先改 shared 的 DumpContract，再同步 PhoneDump.kt 与 desktop/shared/types.ts",
                extra.isEmpty() && missing.isEmpty()
            )
            // 通用字段至少要有 id/createdAt/updatedAt（deletedAt 可选）
            assertTrue(
                "[$collection] 连通用字段（id/createdAt/updatedAt）都没产齐：缺 ${setOf("id", "createdAt", "updatedAt") - emitted}",
                (setOf("id", "createdAt", "updatedAt") - emitted).isEmpty()
            )
        }
    }
}
