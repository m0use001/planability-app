package io.github.m0use001.planability.ai.local

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 模型存储治理回归锁（2026-11-10 优化体检 P0-1）。
 * 真机实测背景：files/geniex 9.38GB、六模型全下载、仅 2 个被活引用——
 * 「哪个能删」由引用核对（refsFor）+ 目录解析（findDir）两条纯函数决定，这里钉死。
 */
class ModelStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---------- refsFor：引用核对 ----------

    @Test
    fun `三个槽位各自产生引用原因`() {
        val refs = ModelStorage.refsFor(
            "unsloth/Qwen3.5-2B-GGUF",
            selected = "unsloth/Qwen3.5-2B-GGUF",
            workerName = "unsloth/Qwen3.5-2B-GGUF",
            compactName = null
        )
        assertEquals(listOf("引擎当前选择", "提纯 worker"), refs)
        assertTrue(ModelStorage.refsFor("unsloth/Qwen3-4B-GGUF", "unsloth/Qwen3.5-2B-GGUF", null, null).isEmpty())
    }

    @Test
    fun `dangling的compact引用不挡删除`() {
        // 真机实测：ctx.compactModel=ai-hub-models/Qwen3-4B 不在 ai_models 表，
        // compactConfig 回退 worker——传进来的 compactName 已经过解析校验（null），不得产生引用
        val refs = ModelStorage.refsFor(
            "ai-hub-models/Qwen3-4B",
            selected = "unsloth/Qwen3.5-2B-GGUF",
            workerName = "unsloth/Qwen3.5-2B-GGUF",
            compactName = null
        )
        assertTrue("QAIRT 4B 被误判在用，3.1GB 清不掉", refs.isEmpty())
    }

    @Test
    fun `null槽位不误伤`() {
        assertTrue(ModelStorage.refsFor("unsloth/Qwen3-0.6B-GGUF", null, null, null).isEmpty())
    }

    // ---------- findDir：pullName → 落盘目录 ----------

    private fun mk(root: java.io.File, rel: String): java.io.File {
        val d = java.io.File(root, rel)
        d.mkdirs()
        java.io.File(d, "weights.bin").writeBytes(ByteArray(16))
        return d
    }

    @Test
    fun `GGUF直路径解析`() {
        val root = tmp.newFolder("models")
        val want = mk(root, "unsloth/Qwen3.5-2B-GGUF")
        assertEquals(want.absolutePath, ModelStorage.findDir(root, "unsloth/Qwen3.5-2B-GGUF")!!.absolutePath)
        assertEquals(16L, ModelStorage.sizeOf(want))
    }

    @Test
    fun `AIHub模型落到qualcomm目录按末段解析`() {
        // 真机实测布局：pullName "ai-hub-models/Qwen3-4B" → models/qualcomm/Qwen3-4B
        val root = tmp.newFolder("models")
        val want = mk(root, "qualcomm/Qwen3-4B")
        assertEquals(want.absolutePath, ModelStorage.findDir(root, "ai-hub-models/Qwen3-4B")!!.absolutePath)
    }

    @Test
    fun `唯一同名目录兜底_多命中拒绝`() {
        val root = tmp.newFolder("models")
        val want = mk(root, "others/Qwen3-1.7B-GGUF")   // 既非直路径也非 qualcomm
        assertEquals(want.absolutePath, ModelStorage.findDir(root, "unsloth/Qwen3-1.7B-GGUF")!!.absolutePath)
        // 两个 hub 下同名 → 不唯一，宁可返回 null 也不删错
        mk(root, "another/Qwen3-1.7B-GGUF")
        assertNull(ModelStorage.findDir(root, "unsloth/Qwen3-1.7B-GGUF"))
    }

    @Test
    fun `根目录不存在返回null不抛`() {
        assertNull(ModelStorage.findDir(java.io.File(tmp.root, "nope"), "unsloth/X"))
        assertNull(ModelStorage.findDir(tmp.newFolder("empty"), "unsloth/X"))
    }

    // ---------- 判定真机的实际清单（按 2026-11-10 快照语义跑一遍） ----------

    @Test
    fun `快照语义_只有选择和worker在用`() {
        val catalog = listOf(
            "unsloth/Qwen3-0.6B-GGUF", "unsloth/Qwen3-1.7B-GGUF", "unsloth/Qwen3.5-0.8B-GGUF",
            "unsloth/Qwen3.5-2B-GGUF", "unsloth/Qwen3-4B-GGUF", "ai-hub-models/Qwen3-4B"
        )
        val free = catalog.filter {
            ModelStorage.refsFor(it, "unsloth/Qwen3.5-2B-GGUF", "unsloth/Qwen3.5-2B-GGUF", null).isEmpty()
        }
        // 可清：0.6B（旧 worker）+1.7B+0.8B+4B-GGUF+QAIRT 4B（dangling）——共 5 个
        assertEquals(5, free.size)
        assertTrue(free.contains("ai-hub-models/Qwen3-4B") && free.contains("unsloth/Qwen3-0.6B-GGUF"))
    }
}
