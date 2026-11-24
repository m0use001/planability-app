package io.github.m0use001.planability.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 文件类型判定的单一真相（用户 2026-10-20：「其他文件也要支持生成，包括但不限于 .md、.htm」）。
 *
 * 这里盯的是三条边界，任何一条错都会让用户"生成不了"或"打开了乱码"：
 * 1. .md / .htm / .html / .json 这些**必须**算纯文本（能生成、能预览）；
 * 2. docx/xlsx/pptx/pdf/图片/压缩包**必须**算二进制（不能按文本读、不能冒名生成）；
 * 3. 不认识的扩展名两种都不是——由调用方决定（file_export 仍能按纯文本写）。
 */
class FileKindsTest {

    @Test
    fun commonTextFormatsAreText() {
        listOf("笔记.md", "页面.htm", "页面.html", "data.json", "a.xml", "b.yaml", "c.csv",
            "d.txt", "e.css", "f.js", "g.sql", "h.py", "i.kt", "j.svg", "k.ics")
            .forEach { assertTrue("$it 应算纯文本", FileKinds.isText(it)) }
    }

    @Test
    fun officeAndMediaAreBinary() {
        listOf("报告.docx", "表.xlsx", "稿.pptx", "文档.pdf", "图.png", "包.zip", "安装包.apk", "库.db")
            .forEach {
                assertTrue("$it 应算二进制", FileKinds.isBinary(it))
                assertFalse("$it 不该算纯文本", FileKinds.isText(it))
            }
    }

    @Test
    fun unknownExtensionIsNeither() {
        assertFalse(FileKinds.isText("数据.xyz"))
        assertFalse(FileKinds.isBinary("数据.xyz"))
    }

    @Test
    fun extensionMatchIsCaseInsensitive() {
        assertTrue(FileKinds.isText("README.MD"))
        assertTrue(FileKinds.isBinary("REPORT.DOCX"))
        assertTrue(FileKinds.extOf("a.HtM") == "htm")
    }
}
