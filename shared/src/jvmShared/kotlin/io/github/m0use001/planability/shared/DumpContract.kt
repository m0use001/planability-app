package io.github.m0use001.planability.shared

/**
 * **phone-dump.json 跨端契约的机器可读真源**（M4）。
 *
 * 此前 dump 的字段集合散在两处：手机端 `data/PhoneDump.kt`（生产者，逐字段 o.put）与
 * PC 端 `desktop/shared/types.ts`（消费者，CollectionName + mergeDump）——这就是
 * AGENTS.md 说的「契约靠手工双份保持一致」。本对象把**字段级契约**收进 shared 单源，
 * 并由 app 侧的 drift 守卫（PhoneDumpContractGuardTest）逐字段比对生产者源码：
 * 多字段（PC 端不认识）/ 少字段（PC 端拿到残记录）都会 FAIL。
 *
 * 权威文档：◆/planability-cross-end-contract.md（PC 端为字段权威）。
 * 字段清单 = PhoneDump.kt v2 实际产出（2026-11-25 核对）。
 * 通用字段 id/createdAt/updatedAt/deletedAt? 每条记录都有，单独列出。
 */
object DumpContract {

    /** dump schema 版本（PhoneDump.buildJson 写的 schemaVersion；PC 端同时接受 1 与 2） */
    const val SCHEMA_VERSION = 2

    /** 顶层键（PhoneDump.buildJson 产出） */
    val TOP_LEVEL_KEYS = setOf("schemaVersion", "exportedAt", "entity")

    /** 每条记录的通用字段（deletedAt 可选） */
    val COMMON_FIELDS = setOf("id", "createdAt", "updatedAt", "deletedAt")

    /**
     * 集合 → 该集合的**专属字段**（不含通用字段）。
     * 与 desktop/shared/types.ts 的 CollectionName 及 mergeDump 的字段消费一一对应。
     */
    val COLLECTION_FIELDS: Map<String, Set<String>> = mapOf(
        "schedules" to setOf("title", "date", "startIso", "endIso", "note", "project", "priority"),
        "todos" to setOf("title", "date", "time", "priority", "status", "project"),
        "notes" to setOf("title", "body", "pinned", "project", "notebookId"),
        "notebooks" to setOf("name", "coverStyle", "coverHue", "coverText", "coverLayout"),
        "memories" to setOf("name", "description", "body", "type", "importance", "accessCount", "lastAccessAt", "inIndex"),
        "projects" to setOf("name", "dirPath", "emoji"),
        "assistants" to setOf("key", "name", "color", "slogan", "systemPrompt", "temperature", "builtin"),
        // dump v2：记忆分层（身份靠自然键：evidence=记忆名#messageId，scene=scene_<name>，persona 固定）
        "memory_evidence" to setOf("memory", "messageId", "kind"),
        "memory_scenes" to setOf("name", "summary", "heat", "project"),
        "memory_persona" to setOf("body", "version"),
    )
}
