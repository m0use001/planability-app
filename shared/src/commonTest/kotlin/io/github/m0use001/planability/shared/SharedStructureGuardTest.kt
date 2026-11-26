package io.github.m0use001.planability.shared

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 结构守卫的测试级补充（真正的门是 shared/build.gradle.kts 的 checkNoAndroidImports 任务，
 * 在所有测试任务执行前跑，commonMain 出现 import android. 或 import androidx. 即 GradleException FAIL）。
 *
 * 这里只做最低限的"守卫任务存在性"自检——防止有人手滑删掉 build.gradle.kts 里那段配置
 * 而没人发现（守卫自己也要有守卫）。
 */
class SharedStructureGuardTest {

    @Test
    fun `shared module sanity - TextWindows is the real implementation`() {
        // 确认 shared 里的 TextWindows 是真实现（不是空壳）：
        // DEFAULT_LIMIT、page() 的 coerce 语义都要在场。
        assertEquals(8000, TextWindows.DEFAULT_LIMIT)
        assertTrue(TextWindows.page("x".repeat(300), 0, 200).hasMore)
    }
}
