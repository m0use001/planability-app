package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v57：尺寸类断点守卫（平板适配，2026-11-16）。
 * 断点沿用 Material 3 WindowSizeClass：Compact < 600 / Medium 600–839 / Expanded ≥ 840。
 */
class WindowSizeTest {

    @Test
    fun `手机竖屏宽度为 Compact`() {
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(360))
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(411))
        assertEquals(WindowSizeClass.Compact, windowSizeClassOf(599))
    }

    @Test
    fun `600 到 839 为 Medium（小平板竖屏或大手机横屏）`() {
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(600))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(720))
        assertEquals(WindowSizeClass.Medium, windowSizeClassOf(839))
    }

    @Test
    fun `840 起为 Expanded（平板横屏或桌面窗口）`() {
        assertEquals(WindowSizeClass.Expanded, windowSizeClassOf(840))
        assertEquals(WindowSizeClass.Expanded, windowSizeClassOf(1280))
        assertEquals(WindowSizeClass.Expanded, windowSizeClassOf(1920))
    }

    @Test
    fun `isWide 在 Medium 及 Expanded 上为真`() {
        assert(!WindowSizeClass.Compact.isWide)
        assert(WindowSizeClass.Medium.isWide)
        assert(WindowSizeClass.Expanded.isWide)
    }
}
