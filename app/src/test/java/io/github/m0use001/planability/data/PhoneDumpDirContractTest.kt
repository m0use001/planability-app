package io.github.m0use001.planability.data

import io.github.m0use001.planability.ui.info.ChatSourceGuard
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * dump 落地目录的契约锁（2026-11-09）。
 *
 * phone-dump.json ⇄ pc-dump.json 是两端数据交换通道，落地目录必须精确落在
 * folder id = `planability-data` 的目录。曾经的实现取「config 里第一个 folder」，
 * 配了「大学文件」(fyh5h-hvwoj) 后它按字母序排到前面，dump 就写进个人文件夹，
 * PC 端从 sync-data 读不到——通道静默断裂，且没有任何报错。
 *
 * 这条锁钉住：选目录必须按共享契约 id 找，不允许再回到「第一个 folder」的写法。
 */
class PhoneDumpDirContractTest {

    @Test
    fun `dump 目录按 planability-data 契约 id 查找`() {
        val code = ChatSourceGuard.readSourceCode(
            "app/src/main/java/io/github/m0use001/planability/data/PhoneDump.kt"
        )
        assertTrue(
            "defaultDir 不再按契约 id（planability-data）选目录——dump 会落错文件夹，PC 端读不到",
            code.contains("optString(\"id\") == \"planability-data\"")
                || code.contains("id=\\\"planability-data\\\""),
        )
        assertFalse(
            "又回到了「第一个 folder」的写法（getJSONObject(0)）——配了大学文件后 dump 落点会漂移",
            code.contains("getJSONObject(0).optString(\"path\")"),
        )
    }
}
