package io.github.m0use001.planability.ui.plan

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 2026-11-25 用户（真机截图「编辑待办」页）：
 * 「百分比比例完成的待办就不要在详细编辑界面也弄个百分比输入窗口了，反而会造成混乱」。
 *
 * 详细编辑页对百分比只剩一个**开关**（是否启用），进度值在首页轻点该待办弹
 * PercentQuickMenu 设（计划页点状态胶囊同一入口）。编辑器里再放快捷档位 25/50/75、
 * 「填数字」输入框和滑条，会出现「同一个数三处可改、还和首页浮层各说各话」的混乱。
 *
 * 这条守卫钉住三件事：
 *  1. 编辑器**不得**再出现 `Fill数字` 标签 / 快捷档位 chips / 100 值滑条；
 *  2. 加载时仍要把已存进度读进 percentVal（**不看就丢** = 打开编辑一次 50% 变 0）；
 *  3. 保存时仍要把 percentVal 写回（关掉开关才写 -1）。
 */
class TodoPercentEditorGuardTest {

    private val src = File("src/main/java/io/github/m0use001/planability/ui/edit/Editors.kt").readText()

    @Test
    fun `编辑页不再有百分比输入控件`() {
        assertFalse(
            "编辑器又长出了「填数字」输入框——进度只该在首页百分比浮层里设",
            src.contains("label = { Text(\"填数字\") }")
        )
        assertFalse(
            "编辑器又长出 25/50/75 快捷档位 chips——与首页浮层重复且容易改错",
            src.contains("listOf(25, 50, 75).forEach")
        )
        assertFalse(
            "编辑器又长出 0..100 的进度滑条",
            src.contains("valueRange = 0f..100f")
        )
    }

    @Test
    fun `已存进度只搬运不丢失`() {
        assertTrue(
            "加载时必须读回已存进度（否则打开编辑再保存会把 50% 抹成 0）",
            src.contains("percentVal = t.percentDone.coerceAtLeast(0).coerceAtMost(100)")
        )
        assertTrue(
            "保存时必须把 percentVal 写回（开关关掉才写 -1）",
            src.contains("percentDone = if (percentOn) percentVal.coerceIn(0, 100) else -1")
        )
    }

    @Test
    fun `开关仍在_并指向首页入口`() {
        assertTrue(
            "百分比完成开关被误删了——这是编辑器唯一该保留的百分比控件",
            src.contains("ToggleRow(\"百分比完成（部分完成也计入完成率）\", percentOn)")
        )
        assertTrue(
            "开关下方要指路「进度在首页轻点这条待办来设」",
            src.contains("进度在首页轻点这条待办来设")
        )
    }
}
