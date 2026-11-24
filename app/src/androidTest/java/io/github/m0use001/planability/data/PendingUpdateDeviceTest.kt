package io.github.m0use001.planability.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.ai.ptl.LifeTools
import io.github.m0use001.planability.core.AppTime
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 2026-11-12 「AI 说无法处理草稿内容」会话真库验收（仅模拟器，日用机禁跑）。
 *
 * 钉死的性质：pending_update 改草稿时刻后，
 * ①草稿字段（timeText/建议时段）立即生效；
 * ②**payload 里的时间键被清掉**——入库按 payload 优先重建，不清就会还原旧时间（静默失效）；
 * ③改标题同步 payload 标题；
 * ④非 pending 草稿拒改。
 */
@RunWith(AndroidJUnit4::class)
class PendingUpdateDeviceTest {

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext

    private suspend fun seed(): Long {
        val payload = JSONObject()
            .put("title", "四级：真题精听（回归闸）")
            .put("date", "2026-11-12")
            .put("due", "16:35")
            .put("repeat", "weekly:4")
        return Graph.info.addPendingItems(
            listOf(
                PendingItem(
                    title = "四级：真题精听（回归闸）", kind = "todo",
                    timeText = "周四 16:35", status = "pending",
                    createdAt = AppTime.nowMillis(), source = "session",
                    payload = payload.toString()
                )
            )
        ).first()
    }

    private suspend fun load(id: Long) =
        (Graph.info.pendingItems() + Graph.info.ignoredItems() + Graph.info.importedItems())
            .first { it.id == id }

    @Test
    fun updateTimeClearsPayloadTimeKeys() = runBlocking {
        Graph.init(ctx)
        var id = 0L
        try {
            id = seed()
            val a = JSONObject().put("id", id).put("time", "17:10")
            val r = LifeTools.run("pending_update", a, 0L)
            assertTrue("pending_update 失败：" + r?.payload, r?.ok == true)
            val it = load(id)
            assertEquals("草稿时间描述没改", "17:10", it.timeText)
            assertTrue("建议时段没落", it.suggestedStart > 0)
            val pb = JSONObject(it.payload)
            assertFalse("payload 还留着旧 due（入库会还原 16:35）", pb.has("due"))
            assertFalse("payload 还留着旧 date（入库会还原旧日期）", pb.has("date"))
            assertTrue("重复规则不该被时间修改连坐删掉", pb.has("repeat"))
        } finally {
            if (id > 0) runCatching { Graph.info.deletePending(id) }
        }
    }

    @Test
    fun updateTitleSyncsPayloadTitle() = runBlocking {
        Graph.init(ctx)
        var id = 0L
        try {
            id = seed()
            val a = JSONObject().put("id", id).put("title", "四级：真题精听（改名后）")
            val r = LifeTools.run("pending_update", a, 0L)
            assertTrue(r?.ok == true)
            val it = load(id)
            assertEquals("草稿标题没改", "四级：真题精听（改名后）", it.title)
            assertEquals("payload 标题没同步（入库会还原旧标题）", "四级：真题精听（改名后）", JSONObject(it.payload).optString("title"))
        } finally {
            if (id > 0) runCatching { Graph.info.deletePending(id) }
        }
    }

    @Test
    fun nonPendingDraftRejected() = runBlocking {
        Graph.init(ctx)
        var id = 0L
        try {
            id = seed()
            Graph.info.markPending(id, "imported")
            val a = JSONObject().put("id", id).put("time", "18:00")
            val r = LifeTools.run("pending_update", a, 0L)
            assertTrue("已入库的草稿不该被改", r?.ok == false)
            assertEquals("时间没被偷改", "周四 16:35", load(id).timeText)
        } finally {
            if (id > 0) runCatching { Graph.info.deletePending(id) }
        }
    }
}
