package io.github.m0use001.planability.core

import android.os.Environment
import java.io.File

/**
 * 真实文件面的路径闸门（v43：用户定向「文件面放开」）。
 *
 * AI 的 fs_* 工具直接操作手机外部存储——这是本轮放开的重点，也是风险最高的一处。
 * 三条硬边界：
 * 1. 只允许 **外部存储根**（/storage/emulated/0）以下；系统私有目录不在范围。
 * 2. `/Android/data`、`/Android/obb` 恒拒——作用域存储的硬规则，MANAGE_EXTERNAL_STORAGE
 *    也读不到别的 App 的 data（本项目文件页实测过：显示「此文件夹为空」）。
 * 3. canonicalPath 校验：符号链接/`..` 逃逸一律拦下。
 *
 * 写类工具还要求会话权限档不放行（Risk.CONFIRM/DANGER 由 ToolExecutor 统一把关），
 * 所有调用由 ToolExecutor 统一写 tools_audit——这里不做第二套审计。
 */
object FsGuard {

    /** 允许访问的根；按卷只放开主存储（第二存储卡/OTG 后续按需再谈） */
    fun root(): File = Environment.getExternalStorageDirectory()

    /** 恒拒前缀（应用间数据隔离，系统级不允许） */
    fun denyPrefixes(): List<String> = buildList {
        add(File(root(), "Android/data").canonicalPath)
        add(File(root(), "Android/obb").canonicalPath)
    }

    data class Guarded(val file: File?, val error: String?) {
        fun ok(): Boolean = file != null
    }

    /** 解析并校验一个路径；失败时 error 是给模型看的中文原因 */
    fun resolve(raw: String, forWrite: Boolean = false): Guarded {
        if (!Environment.isExternalStorageManager()) {
            return Guarded(
                null,
                "没有「所有文件访问」权限：请到「我的 → 系统权限」授权后再试（这是访问系统文件的必要授权）"
            )
        }
        val rootPath = root().canonicalPath
        validate(raw, rootPath, denyPrefixes())?.let { return Guarded(null, it) }
        val canon = File(raw.trim()).canonicalFile
        if (forWrite && canon.path == rootPath) {
            return Guarded(null, "不允许把存储根目录本身作为写入/删除目标")
        }
        return Guarded(canon, null)
    }

    /**
     * 纯校验（不碰 Android API，单测直接测）：返回 null = 通过，否则为原因。
     *
     * 注意分隔符：真机（Linux）canonicalPath 是 `/a/b`，但 JVM 单测跑在 Windows 上
     * 会得到 `D:\a\b` —— 两边统一换成 `/` 再比，否则测试与真机行为不一致。
     *
     * @param rootPath 允许根（真机 = /storage/emulated/0）
     * @param denyPrefixes 恒拒前缀（真机 = Android/data、Android/obb）
     */
    fun validate(raw: String, rootPath: String, denyPrefixes: List<String>): String? {
        if (raw.isBlank()) return "路径不能为空"
        if (!raw.trim().startsWith("/")) {
            return "需要绝对路径（例如 /storage/emulated/0/Download/笔记.txt）"
        }
        val canon = runCatching { File(raw.trim()).canonicalFile }.getOrNull()
            ?: return "路径不合法：$raw"
        val canonPath = canon.path.replace('\\', '/')
        val root = runCatching { File(rootPath).canonicalPath }.getOrNull() ?: rootPath
        val rootNorm = root.replace('\\', '/')
        if (canonPath != rootNorm && !canonPath.startsWith("$rootNorm/")) {
            return "只允许访问外部存储（$rootNorm）内的路径；系统内部目录不在范围内"
        }
        denyPrefixes
            .map { runCatching { File(it).canonicalPath }.getOrNull() ?: it }
            .map { it.replace('\\', '/') }
            .firstOrNull { canonPath == it || canonPath.startsWith("$it/") }?.let {
                return "受保护目录不允许访问：$it（应用间数据隔离，系统也不允许）"
            }
        return null
    }

    /** 展示用：根目录缩写成 ~，减少模型看到的噪音 */
    fun pretty(f: File): String = f.path.replaceFirst(root().canonicalPath, "~")

    /** 人类可读大小 */
    fun humanSize(bytes: Long): String = when {
        bytes >= 1_073_741_824 -> String.format("%.1fGB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576 -> String.format("%.1fMB", bytes / 1_048_576.0)
        bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }
}
