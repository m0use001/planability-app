package io.github.m0use001.planability

import io.github.m0use001.planability.notify.A11yWatch
import io.github.m0use001.planability.notify.AutoReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ①采集完自动返回（AutoReturn.decide）②无障碍服务开启判定（A11yWatch.enabled）的纯函数测试。
 *
 * 两者都不碰任何 Android API，纯 JVM 直接跑。接线层（execute 的 finally、
 * 巡检/解锁两条自检路）由 EventTriggerTest 的补充用例做源码级断言——
 * 这两处最贵的失败模式都是「安静地没生效」：跑完忘了还前台只在真人观察下才暴露；
 * 自检漏钩子 = 服务被关了没人知道。
 */
class AutoReturnAndA11yWatchTest {

    private val OWN = "io.github.m0use001.planability"
    private val LAUNCHER = "com.oppo.launcher"

    // ---------- AutoReturn.decide ----------

    @Test
    fun `前台没变 - 不动`() {
        // 「打开微信时」类事件触发：用户本来就在微信里，自动化跑完还在微信——不能把人踢去桌面
        assertEquals(
            AutoReturn.Action.NONE,
            AutoReturn.decide("com.tencent.mm", "com.tencent.mm", OWN, LAUNCHER)
        )
    }

    @Test
    fun `前台读不到 - 不动`() {
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide(null, "com.tencent.mm", OWN, LAUNCHER))
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide("com.tencent.mm", null, OWN, LAUNCHER))
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide("", "com.tencent.mm", OWN, LAUNCHER))
    }

    @Test
    fun `跑完已在自家或桌面 - 不动`() {
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide(LAUNCHER, OWN, OWN, LAUNCHER))
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide("com.tencent.mm", OWN, OWN, LAUNCHER))
        assertEquals(AutoReturn.Action.NONE, AutoReturn.decide("com.tencent.mm", LAUNCHER, OWN, LAUNCHER))
    }

    @Test
    fun `跑前在自家 - 跑完拉回自家`() {
        assertEquals(
            AutoReturn.Action.BACK_TO_OWN,
            AutoReturn.decide(OWN, "com.tencent.mm", OWN, LAUNCHER)
        )
    }

    @Test
    fun `采集把前台带到别的应用 - 回桌面`() {
        assertEquals(
            AutoReturn.Action.HOME,
            AutoReturn.decide(LAUNCHER, "com.tencent.mobileqq", OWN, LAUNCHER)
        )
        assertEquals(
            AutoReturn.Action.HOME,
            AutoReturn.decide("com.tencent.mm", "com.android.settings", OWN, LAUNCHER)
        )
    }

    @Test
    fun `launcher 解析不到时仍按停在第三方处理 - 回桌面`() {
        // launcherPkg=null 只代表「不知道桌面是谁」，不代表「现在停在桌面」——
        // 此时按 HOME（回桌面无害），不能因为解析失败就放弃收尾
        assertEquals(
            AutoReturn.Action.HOME,
            AutoReturn.decide(LAUNCHER, "com.tencent.mobileqq", OWN, null)
        )
    }

    // ---------- A11yWatch.enabled ----------

    private val PKG = "io.github.m0use001.planability"
    // 服务类名对解析器只是纯数据输入（真实读屏服务在设备功能包里，公开树无此类），
    // 这里用中性样例名，两棵树同语义。
    private val CLS = "io.github.m0use001.planability.a11y.SampleA11yService"

    @Test
    fun `扁平串里有本服务才算开启`() {
        val flat = "io.github.m0use001.planability/io.github.m0use001.planability.a11y.SampleA11yService"
        assertTrue(A11yWatch.enabled(flat, PKG, CLS))
        // 短类名写法（部分 ROM/工具会这么写）
        assertTrue(A11yWatch.enabled("$PKG/.$CLS", PKG, CLS))
    }

    @Test
    fun `空串或只有别人的服务 - 一律算关`() {
        assertFalse(A11yWatch.enabled(null, PKG, CLS))
        assertFalse(A11yWatch.enabled("", PKG, CLS))
        assertFalse(
            A11yWatch.enabled(
                "com.example.other/com.example.other.SomeService",
                PKG, CLS
            )
        )
    }

    @Test
    fun `前缀相似的包名不能误判`() {
        // "io.github.m0use001.planability2/..." 与本包只差一个字符，
        // 整段 "pkg/类名" 匹配不能把它当成自家服务
        assertFalse(
            A11yWatch.enabled(
                "io.github.m0use001.planability2/io.github.m0use001.planability.a11y.SampleA11yService",
                PKG, CLS
            )
        )
    }

    // ---------- A11yWatch.serviceOn（总开关 + 清单双查） ----------

    @Test
    fun `总开关关着时清单里有名字也不算在工作`() {
        // 2026-10-22 真机实证：install -r 后 accessibility_enabled=0 而服务名还在清单里——
        // 只查清单会把这种「关了一半」的状态当成正常，自检从此永远静默
        val flat = "$PKG/$CLS"
        assertTrue(A11yWatch.serviceOn(true, flat, PKG, CLS))
        assertFalse(A11yWatch.serviceOn(false, flat, PKG, CLS))
    }

    @Test
    fun `清单里没有本服务时总开关再开也算关`() {
        assertFalse(A11yWatch.serviceOn(true, "com.example.other/com.example.other.SomeService", PKG, CLS))
        assertFalse(A11yWatch.serviceOn(true, null, PKG, CLS))
    }
}
