package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 浏览器自动化的 JS 构造器（纯逻辑）。
 *
 * 重点是**参数注入**：text/selector 来自模型输出，必须转义成 JS 字符串字面量，
 * 否则一段 `'; alert(1); //` 就能改写注入的脚本。
 */
class BrowserJsTest {

    @Test
    fun jsQuoteEscapesDangerousCharacters() {
        val quoted = BrowserJs.q("a\"b\\c\nd")
        assertTrue(quoted.startsWith("\""))
        assertTrue(quoted.endsWith("\""))
        // 引号被转义后，内部不再有裸引号（除首尾）
        assertTrue(quoted.substring(1, quoted.length - 1).contains("\\\""))
        assertTrue(quoted.contains("\\n"))
    }

    @Test
    fun clickInjectionIsNeutralized() {
        // 真正的闭合风险是双引号：它能提前结束 JS 字符串字面量。
        // 单引号在双引号字面量里没有特殊含义（上面用例里的 ' 不算注入）。
        val evil = "\"}); alert(1); ({\""
        val js = BrowserJs.click(null, evil)
        assertEquals(-1, js.indexOf(evil))          // 未转义的原串绝不能出现
        assertTrue(js.contains(BrowserJs.q(evil)))  // 只能以转义后的字面量出现
    }

    @Test
    fun fillBuildsInputAndChangeEvents() {
        val js = BrowserJs.fill("#user", "张三")
        assertTrue(js.contains("querySelector"))
        assertTrue(js.contains("'input'"))
        assertTrue(js.contains("'change'"))
        assertTrue(js.contains("张三"))
    }

    @Test
    fun summaryAndExtractSkeletons() {
        assertTrue(BrowserJs.summary().contains("document.title"))
        assertTrue(BrowserJs.summary().contains("location.href"))
        assertTrue(BrowserJs.extract(null).contains("innerText"))
        assertTrue(BrowserJs.back().contains("history.back"))
    }
}
