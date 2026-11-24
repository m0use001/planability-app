package io.github.m0use001.planability.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 真实文件面的路径闸门（纯逻辑，不碰 Android API）。
 *
 * 边界来自真机事实：MANAGE_EXTERNAL_STORAGE 也读不到别的 App 的 /Android/data，
 * 所以 fs_* 必须在工具层就拒，而不是让模型撞系统错误。
 */
class FsGuardTest {

    private val root = "/storage/emulated/0"
    private val deny = listOf("$root/Android/data", "$root/Android/obb")

    @Test
    fun emptyPathRejected() {
        assertEquals("路径不能为空", FsGuard.validate("  ", root, deny))
    }

    @Test
    fun relativePathRejected() {
        // 需要绝对路径：相对路径在 App 进程里的 cwd 是 /，语义完全不可控
        assert(FsGuard.validate("Download/a.txt", root, deny) != null)
    }

    @Test
    fun outsideRootRejected() {
        assert(FsGuard.validate("/data/data/io.github.m0use001.planability/db", root, deny) != null)
        assert(FsGuard.validate("/system/etc/hosts", root, deny) != null)
    }

    @Test
    fun normalPathAllowed() {
        assertNull(FsGuard.validate("$root/Download/a.txt", root, deny))
        assertNull(FsGuard.validate("$root/Documents/课程/笔记.md", root, deny))
        // 根目录本身读操作允许（列目录）
        assertNull(FsGuard.validate(root, root, deny))
    }

    @Test
    fun appDataDirectoriesRejected() {
        assert(FsGuard.validate("$root/Android/data", root, deny) != null)
        assert(FsGuard.validate("$root/Android/data/com.tencent.mm/files", root, deny) != null)
        assert(FsGuard.validate("$root/Android/obb/com.x/main.obb", root, deny) != null)
    }

    @Test
    fun prefixBoundaryIsNotOverBlocked() {
        // Android/dataX 不是受保护目录（前缀判断必须带分隔符）
        assertNull(FsGuard.validate("$root/Android/dataX/a.txt", root, deny))
        assertNull(FsGuard.validate("$root/Android/database_backup.db", root, deny))
    }

    @Test
    fun escapedPathIsNormalized() {
        // .. 归一化后仍在允许根内 → 允许
        assertNull(FsGuard.validate("$root/Download/../Documents/a.md", root, deny))
        // .. 把路径带出根 → 拒绝（canonicalFile 归一化之后才做边界判断）
        assert(FsGuard.validate("$root/../sdcard0/x", root, deny) != null)
    }

    @Test
    fun humanSizeIsReadable() {
        assertEquals("512B", FsGuard.humanSize(512))
        assertEquals("1.0KB", FsGuard.humanSize(1024))
        assertEquals("2.0MB", FsGuard.humanSize(2L * 1024 * 1024))
    }
}
