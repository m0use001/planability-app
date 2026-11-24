package io.github.m0use001.planability.ui.common

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 计划卡步骤名的序号口径（2026-11-19 用户实报）。
 *
 * 症状：模型写的步骤名「3.8 反导数：定义与公式表」被剥成「8 反导数…」，
 * 卡片再补一个自己的「1.」→ 屏幕上是「1. 8 反导数」，**模型输出的章节号被吃了**。
 */
class PlanStepNumberingTest {

    @Test
    fun `小数章节号原样保留且不配自动序号`() {
        val r = planStepLabel("3.8 反导数：定义与公式表")
        assertEquals("3.8 反导数：定义与公式表", r.text)
        assertFalse("模型自带章节号时不该再套一个自动序号", r.showIndex)
    }

    @Test
    fun `列表序号剥掉并保留自动序号`() {
        val a = planStepLabel("1. 备份")
        assertEquals("备份", a.text)
        assertTrue(a.showIndex)

        val b = planStepLabel("2、切流")
        assertEquals("切流", b.text)
        assertTrue(b.showIndex)

        val c = planStepLabel("3）验收")
        assertEquals("验收", c.text)
        assertTrue(c.showIndex)
    }

    @Test
    fun `无分隔符的两位序号照剥`() {
        val r = planStepLabel("1 锁定范围与验收标准")
        assertEquals("锁定范围与验收标准", r.text)
        assertTrue(r.showIndex)
    }

    @Test
    fun `多位数字不是序号不许剥`() {
        val r = planStepLabel("2024 年的计划")
        assertEquals("2024 年的计划", r.text)
        assertTrue(r.showIndex)
    }

    @Test
    fun `无序号原样保留`() {
        val r = planStepLabel("迁移到新库")
        assertEquals("迁移到新库", r.text)
        assertTrue(r.showIndex)
    }

    @Test
    fun `章节号后面跟分隔符也算章节号不剥`() {
        // "3.8、" 这种写法：分隔符是「.」后面紧跟数字 → 章节号
        val r = planStepLabel("3.8、反导数")
        assertEquals("3.8、反导数", r.text)
        assertFalse(r.showIndex)
    }

    @Test
    fun `空名不炸`() {
        val r = planStepLabel("   ")
        assertEquals("", r.text)
        assertFalse(r.showIndex)
    }

    @Test
    fun `只有序号没有正文时保留原文`() {
        val r = planStepLabel("1.")
        assertEquals("1.", r.text)
        assertTrue(r.showIndex)
    }
}
