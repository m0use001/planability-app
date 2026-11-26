package io.github.m0use001.planability.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-12 用户「老是漏东西，要不就重复一堆」A 段的真库验收（仅模拟器，日用机禁跑）。
 *
 * 钉死的性质：capture 来源的抽取草稿，判重池必须覆盖**三态**——
 * ①同事件已被「已忽略」→ 不再插回（旧实现清一次来一次的根因）；
 * ②同事件已被「已入库」→ 不再重插；
 * ③非 capture 来源（AI 工具/手动建草稿）不受历史忽略挡住（用户主动动作）。
 * 走真库（Graph），自建自清。
 */
@RunWith(AndroidJUnit4::class)
class CaptureDedupPoolDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun item(title: String, source: String) = PendingItem(
        title = title, kind = "todo", timeText = "11月15日 14:00",
        status = "pending", createdAt = AppTime.nowMillis(), source = source
    )

    private suspend fun cleanup(ids: List<Long>) {
        ids.forEach { runCatching { Graph.info.deletePending(it) } }
    }

    @Test
    fun captureDraftNotReinsertedAfterIgnored() = runBlocking {
        Graph.init(ctx)
        val seed = Graph.info.addPendingItems(listOf(item("学生体质健康测评（回归闸）", "capture")))
        assertTrue(seed.isNotEmpty())
        Graph.info.markPending(seed.first(), "ignored")
        // 措辞略变的同事件再抽一轮（旧实现会插回来）
        val again = Graph.info.addPendingItems(listOf(item("体质健康测评（回归闸）", "capture")))
        assertEquals("已忽略的 capture 草稿不应再插回", emptyList<Long>(), again)
        cleanup(seed)
    }

    @Test
    fun captureDraftNotReinsertedAfterImported() = runBlocking {
        Graph.init(ctx)
        val seed = Graph.info.addPendingItems(listOf(item("院运会裁判培训（回归闸）", "capture")))
        assertTrue(seed.isNotEmpty())
        Graph.info.markPending(seed.first(), "imported")
        val again = Graph.info.addPendingItems(listOf(item("参加院运会裁判培训（回归闸）", "capture")))
        assertEquals("已入库的 capture 事件不应再重插", emptyList<Long>(), again)
        cleanup(seed)
    }

    @Test
    fun nonCaptureDraftNotBlockedByIgnoredHistory() = runBlocking {
        Graph.init(ctx)
        val seed = Graph.info.addPendingItems(listOf(item("完成实验作业1（回归闸）", "capture")))
        assertTrue(seed.isNotEmpty())
        Graph.info.markPending(seed.first(), "ignored")
        // 用户主动让 AI 建的同名草稿（source=session）不能被历史忽略挡住
        val toolDraft = Graph.info.addPendingItems(listOf(item("完成实验作业1（回归闸）", "session")))
        assertTrue("非 capture 来源不该被三态池拦截", toolDraft.isNotEmpty())
        cleanup(seed + toolDraft)
    }
}
