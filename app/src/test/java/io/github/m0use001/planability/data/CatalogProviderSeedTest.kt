package io.github.m0use001.planability.data

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * DeepSeek / NVIDIA NIM 两个目录供应商的播种约定（源码级断言）。
 *
 * ## 为什么用源码断言
 * `ensureCatalogProviders` 读 Room DAO 与 BuildConfig，JVM 单测起不来；
 * 而它的失效症状是**静默的**：key 配了但供应商没出现，用户只会以为"没接上"，
 * 不报错也不崩。所以按源码钉。
 *
 * ## 要守住的事
 * 1. 两个端点地址必须对（写错一个字母就是 404，而报错长得很像 key 错了）；
 * 2. **幂等判据必须是 baseUrl**，不能是"表空不空"——否则用户手动删掉后
 *    下次启动又被塞回来，或反过来重复播种出两条同名供应商；
 * 3. key 空串必须跳过（没配 key 建个空供应商，界面平白多一行）；
 * 4. NVIDIA **不预置模型**（catalog 太大且常变，只能扫描）；
 *    DeepSeek 预置两款（官方就这两款）。
 */
class CatalogProviderSeedTest {

    private fun src(): String {
        var dir = File(System.getProperty("user.dir") ?: ".").absoluteFile
        while (dir != null) {
            val f = File(dir, "app/src/main/java/io/github/m0use001/planability/data/AiProviderEntities.kt")
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("找不到 AiProviderEntities.kt")
    }

    @Test
    fun `两个 OpenAI 兼容端点地址必须写对`() {
        val s = src()
        assertTrue(
            "DeepSeek 端点不是 api.deepseek.com",
            s.contains("https://api.deepseek.com")
        )
        assertTrue(
            "NVIDIA NIM 端点不是 integrate.api.nvidia.com/v1",
            s.contains("https://integrate.api.nvidia.com/v1")
        )
        // 两家都是 openai 格式，写错格式会走 /messages 那套（各判一条，别用 && 挤在一句里）
        assertTrue(
            "DeepSeek 那行的 apiFormat 不是 openai",
            Regex("""baseUrl = "https://api\.deepseek\.com",[\s\S]{0,200}?apiFormat = "openai"""").containsMatchIn(s)
        )
        assertTrue(
            "NVIDIA 那行的 apiFormat 不是 openai",
            Regex("""baseUrl = "https://integrate\.api\.nvidia\.com/v1",[\s\S]{0,200}?apiFormat = "openai"""").containsMatchIn(s)
        )
    }

    @Test
    fun `幂等判据必须是 baseUrl 而不是表空`() {
        val body = src().substringAfter("suspend fun ensureCatalogProviders")
            .substringBefore("\n    }")
        assertTrue("找不到 ensureCatalogProviders", body.isNotEmpty())
        assertTrue(
            "ensureCatalogProviders 没有按 baseUrl 判重——用户删掉的供应商会被反复塞回来",
            body.contains("fun has(base: String)") && body.contains("!has(")
        )
    }

    @Test
    fun `key 为空必须跳过该供应商`() {
        val body = src().substringAfter("suspend fun ensureCatalogProviders")
            .substringBefore("\n    }")
        assertTrue("DeepSeek 没判 key 非空", body.contains("deepseekKey.isNotBlank()"))
        assertTrue("NVIDIA 没判 key 非空", body.contains("nvidiaKey.isNotBlank()"))
    }

    @Test
    fun `NVIDIA 不预置模型，DeepSeek 预置两款`() {
        val body = src().substringAfter("suspend fun ensureCatalogProviders")
            .substringBefore("\n    }")
        // NVIDIA 段里不该有 modelDao.insert。
        // 2026-11-02：AMD Radeon 段接在 NVIDIA 之后，且有**有意**预置的三款模型
        // （AMD 的 catalog 是固定几款，不像 NVIDIA 几百款且常变），所以 NVIDIA 段的
        // 断言范围必须截到 AMD 段之前——否则会误伤。
        val nv = body.substringAfter("integrate.api.nvidia.com")
            .substringBefore("developer.amd.com.cn")
        assertTrue(
            "NVIDIA 段里预置了模型——它的 catalog 几百款且常变，只能扫描",
            !nv.contains("modelDao.insert")
        )
        val ds = body.substringBefore("integrate.api.nvidia.com")
        assertTrue("DeepSeek 没预置 deepseek-flash", ds.contains("deepseek-flash"))
        assertTrue("DeepSeek 没预置 deepseek-v4-pro", ds.contains("deepseek-v4-pro"))

    }

    @Test
    fun `BigModel 与 MIMO 在 key 为空时不播种`() {
        val body = src().substringAfter("suspend fun seedIfNeeded")
            .substringBefore("\n    }")
        assertTrue("seedIfNeeded 没判 key 空", body.contains("main.apiKey.isBlank() && worker.apiKey.isBlank()"))
    }
}
