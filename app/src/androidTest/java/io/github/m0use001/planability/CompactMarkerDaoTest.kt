package io.github.m0use001.planability

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.m0use001.planability.data.AppDatabase
import io.github.m0use001.planability.data.ChatSession
import io.github.m0use001.planability.data.CompactMarkerEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v37 压缩标记的**数据库侧**真机断言（instrumented）。
 *
 * 为什么不用 JVM 单测：`CompactRepo` / `ChatDao` 要真 Room，而本项目没有 Robolectric。
 * 迁移本身已在真机手工验过（`PRAGMA user_version = 37`、两张索引、外键在、老数据无损），
 * 这里钉的是**运行时行为**——这些是编译和迁移都验不出来的：
 * 1. marker 写得进、读得回，锚点是**消息 id**（不是计数）；
 * 2. `updateSummary` 只改摘要侧字段，锚点不许被顺手改掉；
 * 3. 删会话时 marker 被外键 CASCADE 带走（没有孤儿行）；
 * 4. `latest()` 在没有压缩过时返回 null（调用方要能区分"没压过"和"压过"）。
 */
@RunWith(AndroidJUnit4::class)
class CompactMarkerDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun newSession(title: String = "t"): Long {
        val now = System.currentTimeMillis()
        val s = ChatSession(title = title, createdAt = now, updatedAt = now, projectId = 1)
        return db.chatDao().insertSession(s)
    }

    @Test
    fun `marker 写得进读得回，锚点是消息 id`() = runBlocking {
        val sid = newSession()
        val id = db.compactMarkerDao().insert(
            CompactMarkerEntity(
                sessionId = sid,
                summary = "[10/19] 用户在装衣柜",
                lastCompactedMessageId = 4242L,
                firstKeptMessageId = 4243L,
                compactedCount = 21,
                source = "manual",
                createdAt = 1_000L
            )
        )
        assertTrue(id > 0)
        val back = db.compactMarkerDao().latest(sid)!!
        assertEquals("[10/19] 用户在装衣柜", back.summary)
        assertEquals("锚点必须是消息 id，不是计数下标", 4242L, back.lastCompactedMessageId)
        assertEquals(4243L, back.firstKeptMessageId)
        assertEquals(21, back.compactedCount)
        assertEquals("manual", back.source)
        assertEquals(1, back.version)
    }

    @Test
    fun `没有压缩过时 latest 返回 null`() = runBlocking {
        val sid = newSession()
        assertNull(db.compactMarkerDao().latest(sid))
    }

    @Test
    fun `updateSummary 只改摘要侧，锚点不动`() = runBlocking {
        val sid = newSession()
        val id = db.compactMarkerDao().insert(
            CompactMarkerEntity(sessionId = sid, summary = "旧摘要", lastCompactedMessageId = 7L)
        )
        db.compactMarkerDao().updateSummary(id, "新摘要", 3, 9L, 2_000L)
        val back = db.compactMarkerDao().latest(sid)!!
        assertEquals("新摘要", back.summary)
        assertEquals(3, back.compactedCount)
        assertEquals(9L, back.firstKeptMessageId)
        assertEquals("锚点是事实记录，改它等于改历史", 7L, back.lastCompactedMessageId)
    }

    @Test
    fun `latest 取最新一条`() = runBlocking {
        val sid = newSession()
        db.compactMarkerDao().insert(
            CompactMarkerEntity(sessionId = sid, summary = "早", lastCompactedMessageId = 1L, createdAt = 1L)
        )
        db.compactMarkerDao().insert(
            CompactMarkerEntity(sessionId = sid, summary = "晚", lastCompactedMessageId = 5L, createdAt = 2L)
        )
        assertEquals("晚", db.compactMarkerDao().latest(sid)!!.summary)
    }

    @Test
    fun `删会话时 marker 被外键带走`() = runBlocking {
        val sid = newSession()
        db.compactMarkerDao().insert(
            CompactMarkerEntity(sessionId = sid, summary = "s", lastCompactedMessageId = 1L)
        )
        assertEquals(1, db.compactMarkerDao().allOf(sid).size)
        // 会话行删除（对齐 ChatRepo.deleteSession 的顺序）→ marker 必须跟着消失
        db.chatDao().deleteMessagesOf(sid)
        db.chatDao().deleteSession(sid)
        assertEquals("外键 CASCADE 没生效，留下了孤儿标记", 0, db.compactMarkerDao().allOf(sid).size)
    }
}
