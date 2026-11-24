package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-17 用户实报：
 *
 * > 「记忆库里面如果记忆名称太长会把同一行其他东西挤到一边」
 *
 * 根因：`MemoryCard` 第一行是 类型标签 / 图钉 / **记忆名称** / 弹性 Spacer / 日期。
 * 名称 Text 原来既没有 `weight(1f)` 也没有宽度上限——Row 按顺序测量时，名称按**固有宽**
 * 铺开，把后面的弹性空隙压成 0，日期被顶到卡片右边界外（挤到一边/看不见）。
 *
 * 修法：名称吃剩余宽（`weight(1f)` + 已有的 maxLines=1/ellipsis），日期钉在右端。
 */
class MemoryCardRowGuardTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, "app/src/main/java/io/github/m0use001/planability/$rel")
        assertTrue("$rel 不在了", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    @Test
    fun 记忆名称必须吃剩余宽_不许把日期挤出去() {
        val s = source("ui/info/MemoryScreen.kt")
        val cardStart = s.indexOf("private fun MemoryCard(")
        assertTrue("找不到 MemoryCard", cardStart >= 0)
        val window = s.substring(cardStart, (cardStart + 2500).coerceAtMost(s.length))
        val nameIdx = window.indexOf("m.name")
        assertTrue("MemoryCard 里找不到记忆名称", nameIdx >= 0)
        // 名称 Text 的参数区（含 modifier）
        val tail = window.substring(nameIdx, (nameIdx + 600).coerceAtMost(window.length))
        assertTrue(
            "记忆名称 Text 没有 weight(1f)（长名称按固有宽铺开，把同行日期挤出去）：\n$tail",
            tail.contains("Modifier.weight(1f)")
        )
        assertTrue(
            "记忆名称没有省略号（超长会继续铺开）：\n$tail",
            tail.contains("TextOverflow.Ellipsis")
        )
    }

    @Test
    fun 名称吃掉弹性空隙后不许再留一个weight_spacer() {
        val s = source("ui/info/MemoryScreen.kt")
        val cardStart = s.indexOf("private fun MemoryCard(")
        val window = s.substring(cardStart, (cardStart + 2500).coerceAtMost(s.length))
        val nameIdx = window.indexOf("m.name")
        val after = window.substring(nameIdx, (nameIdx + 1200).coerceAtMost(window.length))
        assertFalse(
            "名称已经 weight(1f)，后面再留 Spacer(weight(1f)) 会把剩余宽对半分：\n$after",
            after.contains("Spacer(Modifier.weight(1f))")
        )
    }
}
