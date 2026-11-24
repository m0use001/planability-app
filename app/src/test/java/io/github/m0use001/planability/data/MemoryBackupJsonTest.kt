package io.github.m0use001.planability.data

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v45 备份编解码的字段对称性（纯 JVM）。
 *
 * 这类错编译期完全看不出来：少写一个 `put`，导出正常、恢复时那列悄悄变成默认值。
 * 所以每个新字段都必须进「写出去 → 读回来」的等值断言。
 */
class MemoryBackupJsonTest {

    @Test
    fun `记忆全字段往返一致`() {
        val m = MemoryEntity(
            id = 7, name = "cpp-env", type = "feedback", description = "环境",
            body = "正文内容", importance = 3, accessCount = 4, lastAccessAt = 11,
            expiration = 12, inIndex = true, isDeleted = true, originSession = 9,
            createdAt = 13, updatedAt = 14, projectId = 5,
            priority = -1, scene = "我在和用户做 C++ 复习",
            validAt = 15, invalidAt = 16, expiredAt = 17,
            version = 3, supersededBy = 8
        )
        assertEquals(m, memoryFromJson(memoryJson(m)))
    }

    @Test
    fun `证据边往返一致`() {
        val e = MemoryEvidence(id = 2, memoryId = 7, messageId = 42, kind = "context", createdAt = 99)
        val back = MemoryEvidence(
            memoryId = e.memoryId, messageId = e.messageId, kind = e.kind, createdAt = e.createdAt
        )
        val json = memoryEvidenceJson(e)
        assertEquals(back.memoryId, json.getLong("memoryId"))
        assertEquals(back.messageId, json.getLong("messageId"))
        assertEquals(back.kind, json.getString("kind"))
    }

    @Test
    fun `场景往返一致`() {
        val s = MemoryScene(
            id = 3, name = "C++ 复习", summary = "摘要", heat = 5, projectId = 7,
            validAt = 21, invalidAt = 22, createdAt = 23, updatedAt = 24
        )
        assertEquals(s, memorySceneFromJson(memorySceneJson(s)))
    }

    @Test
    fun `画像往返一致`() {
        val p = MemoryPersona(id = 1, body = "四段式画像", version = 2, updatedAt = 31)
        assertEquals(p, memoryPersonaFromJson(memoryPersonaJson(p)))
    }
}
