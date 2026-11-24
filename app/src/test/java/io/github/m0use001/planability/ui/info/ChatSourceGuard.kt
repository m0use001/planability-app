package io.github.m0use001.planability.ui.info

import org.junit.Assert.assertTrue
import java.io.File

/**
 * 源码守卫测试的公共读取器（钉 Compose 层布局/条件时用）。
 *
 * 路径安全（Mimosa L2 两次指到这类代码，统一收口在这一个文件）：
 * - 仓库根**单跳**定位：Gradle 测试 JVM 的 user.dir = app 模块目录，其 canonical 父目录
 *   即仓库根，并用仓库标记目录验证——不做任意层级的 parentFile 循环遍历；
 * - 相对路径**禁 `..` 段**；
 * - 目标文件 canonical 化后**必须真在仓库根之下**才允许读。
 *
 * 没有外部输入参与拼路径（相对路径全是测试里的字面量），这里的校验是把
 * "只读工作区内固定源码文件"写成扫描器可审计的形式。
 */
internal object ChatSourceGuard {

    /** 仓库标记目录（相对仓库根） */
    private const val MARKER = "app/src/main/java/io/github/m0use001/planability"

    fun repoRoot(): File {
        val appDir = File(System.getProperty("user.dir") ?: ".").canonicalFile
        val root = appDir.parentFile?.canonicalFile
        assertTrue(
            "在 ${appDir.path} 的上一级找不到仓库标记 $MARKER（user.dir 应为 app 模块目录）",
            root != null && File(root, MARKER).isDirectory
        )
        return root!!
    }

    /** 只允许读仓库根之下的文件：禁 .. 段、canonical 化后必须落在根内 */
    fun readUnderRoot(root: File, relative: String): String {
        assertTrue("相对路径不许含 .. 段（当前：$relative）", relative.split('/', '\\').none { it == ".." })
        val rootCanon = root.canonicalFile
        val target = File(rootCanon, relative).canonicalFile
        assertTrue(
            "目标文件跑出了仓库根：${target.path}",
            target.path.startsWith(rootCanon.path + File.separator)
        )
        assertTrue("源码文件不在了：${target.path}", target.isFile)
        return target.readText(Charsets.UTF_8)
    }

    /** 读仓库内源码并剥掉注释行（源码级断言的常规预处理） */
    fun readSourceCode(relative: String): String =
        readUnderRoot(repoRoot(), relative)
            .lines().filterNot { it.trimStart().startsWith("//") }.joinToString("\n")
}
