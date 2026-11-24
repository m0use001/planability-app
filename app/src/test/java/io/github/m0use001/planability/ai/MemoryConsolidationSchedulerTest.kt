package io.github.m0use001.planability.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v36 B 项第二步：整理调度器的**纯逻辑**单测（不碰数据库、不碰协程）。
 *
 * 钉的都是"错一步就静默失效"的边界：
 * 1. 切轮按用户消息切，重新生成回复不产生新轮次（否则每轮都重复整理，token 白烧）；
 * 2. 初见只回看最近 12 个有文字的轮次（否则刚开记忆/导入旧对话会把整段历史交出去）；
 * 3. 积压超过一批时进度只推进到下一批之前（推到末尾 = 没整理的轮次再也轮不到）；
 * 4. 已经整理过的时间点不再算 pending（否则整理器空转、且每次都重复读同样内容）。
 */
class MemoryConsolidationSchedulerTest {

    private fun msg(id: Long, role: String, at: Long, text: String = "x") =
        MemoryConsolidationScheduler.MsgLite(id, role, at, text)

    private fun transcript(vararg pairs: Pair<String, Long>): List<MemoryConsolidationScheduler.MsgLite> {
        val out = mutableListOf<MemoryConsolidationScheduler.MsgLite>()
        var id = 1L
        pairs.forEach { (role, at) ->
            out += msg(id++, role, at, if (role == "user") "用户说第 ${id - 1} 句" else "助手回复")
        }
        return out
    }

    @Test
    fun `按用户消息切轮，助手的多条回复仍属同一轮`() {
        val turns = MemoryConsolidationScheduler.turnsOf(
            listOf(
                msg(1, "user", 100),
                msg(2, "assistant", 110),
                msg(3, "assistant", 120),
                msg(4, "user", 200),
                msg(5, "assistant", 210),
            )
        )
        assertEquals("应当是两轮", 2, turns.size)
        assertEquals("第一轮含 3 条消息", 3, turns[0].messages.size)
        assertEquals("第二轮含 2 条消息", 2, turns[1].messages.size)
    }

    @Test
    fun `consolidatedAt 之后的用户消息才算待整理，重生成回复不重复计`() {
        val msgs = listOf(
            msg(1, "user", 100),
            msg(2, "assistant", 110),
            // 重新生成：时间在 consolidatedAt 之后，但用户消息时间没变
            msg(3, "assistant", 150),
            msg(4, "user", 200),
            msg(5, "assistant", 210),
        )
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(msgs, consolidatedAt = 150, updatedAt = 210)
        assertEquals("只有第 4 条用户消息算新轮次", 1, pending.totalTurns)
        assertEquals("新轮次就是从 200 那条用户消息开始", 4, pending.turns.first().messages.first().id)
    }

    @Test
    fun `进度已覆盖到 updatedAt 时没有可推进的进度`() {
        val msgs = transcript("user" to 100L, "assistant" to 110L, "user" to 200L)
        // consolidatedAt 已经等于 updatedAt：takeIf > consolidatedAt 不成立 → null
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(msgs, consolidatedAt = 210, updatedAt = 210)
        assertTrue("没有待整理轮次", pending.turns.isEmpty())
        assertNull("没有可推进的进度", pending.consolidatedThrough)
    }

    @Test
    fun `没有新轮次但会话又更新过，仍给出推进点（免得下次还把它当候选）`() {
        val msgs = transcript("user" to 100L, "assistant" to 110L, "user" to 200L)
        // 例：只是重新生成了回复，updatedAt 被推到 210，但用户消息时间没变 → 没有新轮次，
        // 但进度仍要推到 210，否则每次扫描都把这个会话读一遍（rikkahub 同款处理）
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(msgs, consolidatedAt = 200, updatedAt = 210)
        assertTrue("没有新轮次", pending.turns.isEmpty())
        assertEquals("进度推到 updatedAt", 210L, pending.consolidatedThrough)
    }

    @Test
    fun `进度只推进到本批最后一条，剩下的留给下一次`() {
        // 造 20 个用户轮次；批大小 = 单次写入上限 × 2（见 MAX_TURNS_PER_RUN 的说明）
        val msgs = mutableListOf<MemoryConsolidationScheduler.MsgLite>()
        var at = 1000L
        var id = 1L
        repeat(20) {
            msgs += msg(id++, "user", at); at += 10
            msgs += msg(id++, "assistant", at); at += 10
        }
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(msgs, consolidatedAt = 0, updatedAt = at)
        assertEquals("总待整理 20 轮", 20, pending.totalTurns)
        assertEquals("本批只整理 8 轮", 8, pending.turns.size)
        // 第 9 个用户轮次的时间戳 - 1，不能再往前推到第 20 轮
        val ninth = MemoryConsolidationScheduler.turnsOf(msgs)[8].userMessage.createdAt
        assertEquals("进度停在第 9 轮之前", ninth - 1, pending.consolidatedThrough)
        // 单次写入上限变了，批大小必须跟着变（否则又会静默丢轮次）
        assertEquals(MEMORY_MAX_OPS * 2, MemoryConsolidationScheduler.MAX_TURNS_PER_RUN)
    }

    @Test
    fun `初见只回看最近 12 个有文字的轮次`() {
        val msgs = mutableListOf<MemoryConsolidationScheduler.MsgLite>()
        var at = 1000L
        var id = 1L
        repeat(20) {
            msgs += msg(id++, "user", at); at += 10
            msgs += msg(id++, "assistant", at); at += 10
        }
        val start = MemoryConsolidationScheduler.initialProgress(msgs)
        // takeLast(12) 的"第一个"= 第 20-12+1 = 9 个用户轮次（下标 8），不是第 13 个；
        // 20 轮里回看 12 轮，起点自然落在第 9 轮前面。
        val ninth = MemoryConsolidationScheduler.turnsOf(msgs)[8].userMessage.createdAt
        assertEquals("起点 = 第 9 个用户轮次的前 1ms", ninth - 1, start)
    }

    @Test
    fun `没有文字的用户轮次被跳过，不算进回看窗口`() {
        val msgs = listOf(
            msg(1, "user", 100, ""),          // 只发了图，没有文字
            msg(2, "assistant", 110),
            msg(3, "user", 200, "有字"),      // ← 起点应落在它前面
            msg(4, "assistant", 210),
        )
        val start = MemoryConsolidationScheduler.initialProgress(msgs)
        assertEquals(199L, start)
    }

    @Test
    fun `空会话不给出起点`() {
        assertEquals(0L, MemoryConsolidationScheduler.initialProgress(emptyList()))
    }

    @Test
    fun `字符量按批内消息累计，供转录预算用`() {
        val msgs = listOf(
            msg(1, "user", 100, "12345"),
            msg(2, "assistant", 110, "1234567890"),
            msg(3, "user", 200, "1"),
        )
        val pending = MemoryConsolidationScheduler.pendingTurnsOf(msgs, consolidatedAt = 150, updatedAt = 210)
        assertEquals("只算第 3 条之后的新轮次内容", 1, pending.chars)
    }
}
