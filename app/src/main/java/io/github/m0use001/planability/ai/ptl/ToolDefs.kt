package io.github.m0use001.planability.ai.ptl

/**
 * 风险三级：auto 读类 / confirm 创建修改 / danger 删除清空
 * **M2 批3 桥接**：枚举本体在 `:shared`（M4 起整份 ToolDefs 也在那边）。
 */
internal typealias Risk = io.github.m0use001.planability.shared.Risk

/**
 * **M4 契约真源桥接**：工具 schema（ToolParam/ToolDef/ToolRegistry 的 ALL/byName/DOMAINS/
 * PLUGINS/toToolsJson）真实现已整体迁入 `:shared`（jvmShared）。
 *
 * 全仓引用（ChatEngine/ToolExecutor/ToolExecImpls/ToolRouter/UI 三处/全部守卫测试）零改动。
 * 注意：`enabledDomains()`/`enabledPlugins()` 原是 ToolRegistry 的成员、读 `Graph.db`
 * （Room，M5 领域）——它们以**扩展函数**形式留在本文件（见下），调用点文本不变。
 */
internal typealias ToolParam = io.github.m0use001.planability.shared.ToolParam
internal typealias ToolDef = io.github.m0use001.planability.shared.ToolDef
internal typealias ToolRegistry = io.github.m0use001.planability.shared.ToolRegistry

/** 启用的工具域（KV "tool.domains"，缺省=全部）——IO 留 app，解析规则在 shared 单源 */
suspend fun ToolRegistry.enabledDomains(): Set<String>? {
    val raw = io.github.m0use001.planability.data.Graph.db.settingsDao().get("tool.domains") ?: return null
    return io.github.m0use001.planability.shared.ToolRegistry.parseDomains(raw)
}

/** 启用的插件能力包（KV "plugin.enabled"，缺省=全部内置包）——同上 */
suspend fun ToolRegistry.enabledPlugins(): Set<String>? {
    val raw = io.github.m0use001.planability.data.Graph.db.settingsDao().get("plugin.enabled") ?: return null
    return io.github.m0use001.planability.shared.ToolRegistry.parsePlugins(raw)
}
