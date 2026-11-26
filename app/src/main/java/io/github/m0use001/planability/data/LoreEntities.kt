package io.github.m0use001.planability.data

// M5 KMP 化：LoreEntry 实体 + LoreDao 已迁 `:shared`（Room 真源），同包跨模块直解。
// 本文件只留 LoreRepo（薄封装）。

class LoreRepo(private val dao: LoreDao) {
    suspend fun forAssistant(assistantId: Long): List<LoreEntry> = dao.byAssistant(assistantId)
    suspend fun all(): List<LoreEntry> = dao.all()
    suspend fun byId(id: Long): LoreEntry? = dao.byId(id)
    suspend fun upsert(e: LoreEntry): Long = dao.upsert(e)
    suspend fun delete(id: Long) = dao.delete(id)
    suspend fun deleteForAssistant(assistantId: Long) = dao.deleteForAssistant(assistantId)
    suspend fun countFor(assistantId: Long): Int = dao.countFor(assistantId)
}
