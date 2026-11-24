package io.github.m0use001.planability.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 「后台冻结」（键前缀 `shell.freeze.*`）：进程被杀后重进，回到最后一次停留的界面。
 *
 * ## 为什么叫"冻结"却不是真冻结
 * Android 不允许第三方应用拒绝系统回收——ColorOS 杀后台我们拦不住，进程一死
 * 所有 `remember` 态（底栏页签、计划页筛选、当前会话、聊天草稿）全部归零，
 * 重进永远落在首页。能做的是把「用户停在哪」持续记进 KV，下次冷启动直接回到
 * 那里——对用户来说体感接近"没被杀过"。
 *
 * ## 存哪
 * 设置 KV 旁路（Graph.settings.kvGet/kvPut，SettingsRepo 的官方追加入口），
 * 不动 SettingsSnapshot、不写 Room 迁移。各页面的键：
 *  - `shell.freeze.tab`          底栏页签 id（NavShell 的 ShellNav.currentTab 写入）
 *  - `shell.freeze.plan.*`       计划页筛选（segment/range/status/dim/tag/label/imp/urg）
 *  - `shell.freeze.info.*`       信息页会话态（chatOpen/session）
 *  - `shell.freeze.chatdraft.<id>` 会话输入草稿（ChatScreen）
 *  - `shell.freeze.notebook`     笔记页打开的笔记本 id（NotesScreen）
 *
 * ## 为什么写路径走 Graph.appScope
 * 写入点都在组合/状态回调里，composition scope 会随页面重建被取消——Room 写
 * 半路被 cancel 就静默丢了。用应用级 scope（Graph.appScope）才保证落库，同
 * Graph.appScope 的既有用途。
 */
object UiFreeze {
    /** 开关本身的 KV 键（"1" = 开） */
    const val KEY_ON = "shell.freeze.on"

    /** 底栏页签的 KV 键 */
    const val KEY_TAB = "shell.freeze.tab"

    /**
     * 开关的内存镜像：冷启动由 AppShell 首个 effect 从 KV 装填；设置页切换时同步更新。
     * 写路径（[save]）读它判开关——关掉之后界面变化不再被记住，也不会白写库。
     */
    var enabled by mutableStateOf(false)
        private set

    /** 装填完成信号：页面级恢复 effect 必须先 [awaitLoaded]——各页的 effect 与
     *  AppShell 的装填 effect 并发跑，直接读 [enabled] 会在装填完成前看到 false，
     *  把恢复整个跳掉（plan36 实锤：页签恢复了、分段没恢复）。 */
    private val loaded = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 冷启动装填（AppShell 调用一次） */
    suspend fun load() {
        enabled = runCatching { Graph.settings.kvGet(KEY_ON) == "1" }.getOrDefault(false)
        loaded.value = true
    }

    /** 页面恢复前等装填完成（已装填则立即返回） */
    suspend fun awaitLoaded() {
        loaded.first { it }
    }

    /** 设置页开关：写 KV + 同步镜像；关闭时顺手清掉已存的界面键（重开不留旧值突袭） */
    suspend fun setEnabled(v: Boolean) {
        enabled = v
        runCatching {
            Graph.settings.kvPut(KEY_ON, if (v) "1" else "0")
            if (!v) {
                Graph.settings.rawAll().keys
                    .filter { it.startsWith("shell.freeze.") && it != KEY_ON }
                    .forEach { Graph.settings.removeRaw(it) }
            }
        }
    }

    /**
     * 冻结键写入（页面状态变化时调）。开关关闭时直接返回——既不写库也不留垃圾。
     * 阻塞不了调用方（内部 launch 到 appScope），调用处当普通函数用即可。
     *
     * @param debounceMs > 0 时同键去抖（逐键输入这类高频写入用；新值到来取消旧写）。
     *   注意去抖窗口内进程被杀会丢最后一次——对草稿类可接受，真正要紧的状态用 0。
     */
    fun save(key: String, value: String, debounceMs: Long = 0) {
        if (!enabled) return
        if (debounceMs > 0) {
            pendingWrites.remove(key)?.cancel()
            pendingWrites[key] = Graph.appScope.launch {
                kotlinx.coroutines.delay(debounceMs)
                pendingWrites.remove(key)
                runCatching { Graph.settings.kvPut(key, value) }
            }
        } else {
            Graph.appScope.launch { runCatching { Graph.settings.kvPut(key, value) } }
        }
    }

    /** 去抖中的写入（键 → job），新写到来时取消旧的 */
    private val pendingWrites =
        java.util.concurrent.ConcurrentHashMap<String, kotlinx.coroutines.Job>()
}
