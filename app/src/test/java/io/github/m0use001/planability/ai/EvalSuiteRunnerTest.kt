package io.github.m0use001.planability.ai

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评估用例表的**运行器**回归（2026-11-05，promptfoo 式）。
 *
 * 不动网络、不碰数据库：只锁运行器的语义——
 * 1. 逐条顺序执行且**每条都回调**（UI 才能边跑边出结果）；
 * 2. 一条用例抛异常 → 记 FAIL 并带证据，**不影响后续用例**；
 * 3. 汇总的通过数/总数正确；耗时字段有值。
 */
class EvalSuiteRunnerTest {

    private fun case(name: String, ok: Boolean) = EvalSuite.Case(
        group = "测试", name = name, assertion = "assert: 恒为 " + ok
    ) {
        EvalSuite.Result(ok, "evidence-" + name, 1)
    }

    @Test
    fun `逐条执行并全部回调`() = runBlocking {
        val cases = listOf(case("a", true), case("b", false), case("c", true))
        val seen = mutableListOf<String>()
        val snap = EvalSuite.run(cases) { c, _ -> seen += c.name }
        assertEquals(listOf("a", "b", "c"), seen)
        assertEquals(2, snap.passed)
        assertEquals(3, snap.total)
        assertEquals("2/3 通过", snap.summary())
    }

    @Test
    fun `单条抛异常记为FAIL且不中断`() = runBlocking {
        val boom = EvalSuite.Case("测试", "炸", "assert: 不应崩溃") {
            throw IllegalStateException("boom")
        }
        val snap = EvalSuite.run(listOf(case("正常", true), boom, case("之后", true))) { _, _ -> }
        assertEquals(3, snap.total)
        assertEquals(2, snap.passed)
        val failed = snap.results.first { it.first.name == "炸" }.second
        assertTrue("失败证据应包含原因", failed.evidence.contains("boom"))
        assertTrue("之后的用例仍应执行", snap.results.last().second.passed)
    }

    @Test
    fun `耗时字段有值`() = runBlocking {
        val snap = EvalSuite.run(listOf(case("t", true))) { _, _ -> }
        assertTrue(snap.results.first().second.ms >= 0)
    }

    @Test
    fun `内置用例表结构完整`() {
        val dir = createTempDir("eval")
        val cases = EvalSuite.builtin(dir)
        assertTrue("至少有 6 条内置用例", cases.size >= 6)
        assertTrue("每条都要有断言文案", cases.all { it.assertion.startsWith("assert:") })
        assertTrue("分组应覆盖 搜索/文档/数据",
            cases.map { it.group }.toSet().containsAll(listOf("搜索", "文档", "数据")))
        assertTrue("搜索用例应标记需要联网", cases.filter { it.group == "搜索" }.all { it.network })
    }
}
