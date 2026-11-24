package io.github.m0use001.planability.ui.common

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.material3.MotionScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Motion 三档必须**等于**官方 `MotionScheme.expressive()` 的对应弹簧（档位映射防手滑）：
 * quick→fastSpatial、standard→defaultSpatial、emphasized→slowSpatial、contentSize→defaultSpatial 参数。
 *
 * 为什么敢这么钉：Motion.kt 里三档本来就是从 expressive() 现取的，看似同义反复——
 * 但**映射关系**（哪档对哪个官方规格）是设计决策，历史上出过"把 emphasized 接到 fast 上"
 * 这类手滑；参数对比能把接错的档抓出来。数值也顺带钉住了 material3 版本——
 * 官方如果哪天改弹簧参数，这条测试会红，提醒我们决定跟不跟（跟 = 改 Motion.kt 注释里的表）。
 */
class MotionSpringSpecTest {

    private fun params(spec: FiniteAnimationSpec<Float>): Triple<Float, Float, Float> {
        val ss = spec as SpringSpec<Float>
        return Triple(ss.dampingRatio, ss.stiffness, ss.visibilityThreshold ?: Float.NaN)
    }

    private fun same(a: FiniteAnimationSpec<Float>, b: FiniteAnimationSpec<Float>, label: String) {
        val (dr1, st1, _) = params(a)
        val (dr2, st2, _) = params(b)
        assertEquals("$label 的 dampingRatio 与官方不一致", dr2, dr1, 1e-6f)
        assertEquals("$label 的 stiffness 与官方不一致", st2, st1, 1e-6f)
    }

    @Test
    fun `三档分别对应官方的 fast _ default _ slow spatial`() {
        val m = MotionScheme.expressive()
        same(Motion.quick(), m.fastSpatialSpec(), "quick→fastSpatial")
        same(Motion.standard(), m.defaultSpatialSpec(), "standard→defaultSpatial")
        same(Motion.emphasized(), m.slowSpatialSpec(), "emphasized→slowSpatial")
    }

    @Test
    fun `contentSize 用 defaultSpatial 同参数（轻微过冲=东西坐下去）`() {
        val ss = Motion.contentSize as SpringSpec<androidx.compose.ui.unit.IntSize>
        assertEquals(0.8f, ss.dampingRatio, 1e-6f)
        assertEquals(380f, ss.stiffness, 1e-6f)
    }

    @Test
    fun `wobble 是 MediumBouncy 级过冲（0_5 damping + defaultSpatial 刚度）`() {
        // v57 批 0：wobble 与三档节奏弹簧是两个维度——那三档管"多快落定"，
        // 这根管"抖得多明显"。0.5 过冲明显（冲过头回弹一次），380 刚度保证 ≈200ms 沉降。
        // 改这两个数 = 改全 app「抖一下」的手感，必须显式过这条测试。
        val ss = Motion.wobble() as SpringSpec<Float>
        assertEquals(0.5f, ss.dampingRatio, 1e-6f)
        assertEquals(380f, ss.stiffness, 1e-6f)
    }

    @Test
    fun `sizeSpring 与 quickSpatial 泛型版对齐官方 spatial`() {
        // 泛型版是给 SizeTransform/animateContentSize（IntSize）与 animateDpAsState（Dp）
        // 这类非 Float 接口的入口，参数必须与对应档位同源——接错档（如 quick 接成 default）
        // 手感立刻不对，参数对比能把映射错抓出来。
        val m = MotionScheme.expressive()
        same(Motion.sizeSpring(), m.defaultSpatialSpec(), "sizeSpring→defaultSpatial")
        same(Motion.quickSpatial(), m.fastSpatialSpec(), "quickSpatial→fastSpatial")
    }

    @Test
    fun `pageSlide 是临界阻尼（页级零过冲）`() {
        // v57 批 5（2026-11-08 用户拍板 tween→弹簧）：整屏页面切换过冲会先滑过头、
        // 露出隔壁页边缘再弹回来，显得不稳——所以 dampingRatio 必须是 1.0（临界阻尼）。
        // 谁把它改成带过冲的档（哪怕 defaultSpatial 的 0.8），这条会红，提醒页级语义不同。
        val ss = Motion.pageSlide() as SpringSpec<Float>
        assertEquals(1.0f, ss.dampingRatio, 1e-6f)
        assertEquals(380f, ss.stiffness, 1e-6f)
    }

    @Test
    fun `官方弹簧常数与注释表一致`() {
        val m = MotionScheme.expressive()
        val (drF, stF, _) = params(m.fastSpatialSpec())
        val (drD, stD, _) = params(m.defaultSpatialSpec())
        val (drS, stS, _) = params(m.slowSpatialSpec())
        assertEquals(0.6f, drF, 1e-6f); assertEquals(800f, stF, 1e-6f)
        assertEquals(0.8f, drD, 1e-6f); assertEquals(380f, stD, 1e-6f)
        assertEquals(0.8f, drS, 1e-6f); assertEquals(200f, stS, 1e-6f)
    }
}
