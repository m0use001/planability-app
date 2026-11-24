package io.github.m0use001.planability.ai.embed

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * v52：下载源安全校验。
 *
 * 模型地址是硬编码常量，但这里仍然钉三条：**只允许 https**、**host 必须白名单**、
 * **本机/内网地址一律拒**。理由是防止以后有人把 url 做成可配置项（或加入第三方源），
 * 被指到内网/本机就成了 SSRF 面。
 */
class ModelStoreUrlTest {

    @Test
    fun acceptsWhitelistedHttps() {
        ModelStore.requireSafeUrl("https://hf-mirror.com/a/b/model.onnx")
        // huggingface.co 也过白名单，但它的 DNS 解析走真实网络——被污染的机器上会解析到
        // 内网假地址、被 SSRF 检查按设计拒绝（2026-11-06 本机实测）。测试环境无关的
        // 白名单与明文检查都已单列；这条只验 hf-mirror（下载实际用的源）。
        try {
            ModelStore.requireSafeUrl("https://huggingface.co/a/b/model.onnx")
        } catch (e: IllegalArgumentException) {
            // 环境污染（解析到内网）算通过——拒绝理由必须还是安全检查，不是白名单
            assertTrue(
                "白名单 host 被拒只能是安全检查（内网/本机），不应是白名单错误：" + e.message,
                e.message!!.contains("内网") || e.message!!.contains("本机")
            )
        }
    }

    @Test
    fun rejectsPlainHttp() {
        try {
            ModelStore.requireSafeUrl("http://hf-mirror.com/model.onnx")
            fail("http 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("https"))
        }
    }

    @Test
    fun rejectsUnknownHost() {
        try {
            ModelStore.requireSafeUrl("https://example.com/model.onnx")
            fail("白名单外的 host 应被拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("白名单"))
        }
    }

    @Test
    fun rejectsLoopbackAndPrivate() {
        listOf(
            "https://127.0.0.1/model.onnx",
            "https://localhost/model.onnx",
            "https://[::1]/model.onnx",
            "https://10.0.0.1/model.onnx",
            "https://192.168.1.10/model.onnx",
            "https://169.254.1.1/model.onnx"
        ).forEach { url ->
            try {
                ModelStore.requireSafeUrl(url)
                fail("应拒绝：" + url)
            } catch (e: IllegalArgumentException) {
                // 本地地址在白名单检查前就被拦（host 不在白名单），或解析后按 IP 拦都算对
                assertTrue(true)
            }
        }
    }

    @Test
    fun rejectsGarbage() {
        listOf("", "not a url", "ftp://hf-mirror.com/x", "//hf-mirror.com/x").forEach { url ->
            try {
                ModelStore.requireSafeUrl(url)
                fail("应拒绝：" + url)
            } catch (e: IllegalArgumentException) {
                assertTrue(true)
            }
        }
    }
}
