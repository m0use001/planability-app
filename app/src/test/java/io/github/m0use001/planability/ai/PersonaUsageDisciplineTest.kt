package io.github.m0use001.planability.ai

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 画像注入使用纪律（2026-11-11 线代会话 288 事故）。
 *
 * 实录：模型把画像「兴趣图谱·在学 Python/C++」泛化成「讲线代也附整段 Python 求行列式
 * 实现并主动推销 C++ 版」，且把画像内容当成对话里聊过的事实（未标依据）。
 * 根因 = ContextEngine 裸注入画像（只有标题、零条用法约束）。
 * 这批测试钉住：纪律常量三条规则齐全；注入点必须先纪律后正文；
 * 纪律是静态常量且在稳定段（provider 前缀缓存不破）。
 */
class PersonaUsageDisciplineTest {

    private fun source(rel: String): String {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null && !File(dir, "app/src/main/java/io/github/m0use001/planability").isDirectory) {
            dir = dir.parentFile
        }
        val f = File(requireNotNull(dir) { "找不到仓库根" }, rel)
        assertTrue("源码不在了：$rel", f.isFile)
        return f.readText(Charsets.UTF_8)
    }

    // ---------- 行为：纪律常量内容 ----------

    @Test
    fun `纪律常量三条规则齐全`() {
        val r = ContextEngine.PERSONA_USAGE_RULES
        // ① 学科纯净：非编程学科不主动附代码，「在学」不构成转成代码的理由
        assertTrue("缺学科纯净规则（不主动附代码）", r.contains("不主动附代码"))
        assertTrue("缺「正在学某语言」反例", r.contains("正在学某语言"))
        assertTrue("缺「用户本轮明确要代码」的放行口", r.contains("明确要代码"))
        // ② 来源归属：画像条目不是对话事实
        assertTrue("缺来源归属规则", r.contains("不是本轮对话里聊过的事实"))
        assertTrue("缺依据标注示例", r.contains("你画像里记着"))
        // ③ 交互协议按字面主题适用，不外推成全局风格
        assertTrue("缺协议范围规则（字面主题）", r.contains("字面主题"))
    }

    // ---------- 结构：注入点接线 ----------

    @Test
    fun `注入点先纪律后正文，纪律是常量引用且在稳定段`() {
        val ctx = source("app/src/main/java/io/github/m0use001/planability/ai/ContextEngine.kt")
        val injectIdx = ctx.indexOf(".append(PERSONA_USAGE_RULES)")
        assertTrue("画像注入点没挂使用纪律（裸注入回归）", injectIdx > 0)
        val bodyIdx = ctx.indexOf(".append(personaText)")
        assertTrue("纪律必须在画像正文之前（先规则后内容）",
            injectIdx in 1 until bodyIdx)
        val tsIdx = ctx.indexOf("当前时间：")
        assertTrue("画像注入应仍在稳定段（易变尾部之前，保住前缀缓存）",
            injectIdx in 1 until tsIdx)
    }
}
