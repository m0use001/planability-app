package io.github.m0use001.planability.data

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import io.github.m0use001.planability.core.SecretBox
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 自动整库快照：App 切到后台时，把 `planability.db` 复制到**公共目录**
 * `Download/Planability/`，保留最近若干份。
 *
 * ## 为什么写公共目录而不是 filesDir
 * - `filesDir` / `getExternalFilesDir` 会随**卸载**一起被删；
 * - 2026-10-19 那次事故就是卸载重装 + 装错变体把 App 数据全清了，
 *   连 `run-as` 都失效（非调试包），任何 agent 都没法取证；
 * - `Download/` 属于用户可删除的公共空间，卸载 App **不会**动它。
 *
 * ## v44 起默认加密（"拿到手机就能读库"这条被堵掉）
 * 快照写成 `planability-<时间>.db.enc`：AES-256-GCM（密钥在 Android Keystore，
 * 见 [SecretBox]），文件头带魔数，普通 sqlite 打不开。
 *
 * **代价（必须让人知道）**：Keystore 密钥随卸载销毁，`*.enc` 在重装后**再也解不开**。
 * 所以这里给两条路：
 * 1. 重装恢复的主路径是电脑上的明文快照 `tools/device-guard`（与手机加密无关）；
 * 2. 想留"拔掉就能看"的急救明文，用户在 自动化 → 导入 → 本机快照 里显式打开
 *    [setKeepPlain]；那时额外写一份 `planability-<时间>.db`（只留最近 1 份）。
 * 3. 装还活着、但库坏了：用 [decryptNewestToRestoreCopy] 把最新密文解成
 *    `restore-<时间>.db`（明文），再 `device_guard restore <文件>`。
 *
 * ## 为什么整库而不是 exportJson
 * `BackupManager.exportJson` 目前不含 `projects` / `memories`，
 * 而整库快照包含**所有表**，恢复后就是原样。
 *
 * ## 节流
 * 5 分钟最多一份（整库 ~450KB，12 份约 5MB）。
 */
object AutoBackup {

    private const val PREFIX = "planability-"
    private const val RESTORE_PREFIX = "restore-"
    private const val ENC_SUFFIX = ".db.enc"
    private const val PLAIN_SUFFIX = ".db"
    private const val KEEP_ENC = 12
    private const val KEEP_PLAIN_RESCUE = 1
    private const val MIN_INTERVAL_MS = 5 * 60 * 1000L
    private const val LAST_KEY = "autoBackup.lastAt"
    /** "1" = 额外留一份明文急救副本（默认关；见类注释的取舍） */
    private const val KEEP_PLAIN_KEY = "autoBackup.keepPlain"
    private val DIR = Environment.DIRECTORY_DOWNLOADS + "/Planability"
    private val FMT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
    private val COLLECTION = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    data class Status(
        val encrypted: Int = 0,
        val plain: Int = 0,
        val restoreCopies: Int = 0,
        val keepPlain: Boolean = false,
        val lastAt: Long = 0L,
        val sealedOk: Boolean = false,
        val newestName: String = ""
    )

    /** 切后台/启动/危险删除前调用；内部自己判断要不要写。 */
    suspend fun snapshot(force: Boolean = false) = withContext(Dispatchers.IO) {
        runCatching {
            val now = System.currentTimeMillis()
            val last = Graph.settings.raw(LAST_KEY)?.toLongOrNull() ?: 0L
            if (!force && now - last < MIN_INTERVAL_MS) return@withContext

            val ctx = Graph.appContext
            val dbFile = ctx.getDatabasePath("planability.db")
            if (!dbFile.isFile) return@withContext

            // 先把 WAL 合并进主文件，否则复制到的 .db 可能少了最近的事务
            runCatching {
                Graph.db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(FULL)").use { it.moveToFirst() }
            }

            val bytes = dbFile.readBytes()
            if (bytes.size < 4096) return@withContext

            val stamp = LocalDateTime.now().format(FMT)
            var wroteAnything = false

            // 1) 加密快照（默认路径）
            val sealed = runCatching { SecretBox.sealBytes(bytes) }.getOrNull()
            if (sealed != null) {
                wroteAnything = write(ctx.contentResolver, PREFIX + stamp + ENC_SUFFIX, sealed) || wroteAnything
            } else {
                // 加密不可用（Keystore 坏了）：退化成明文，别让"备份"整个失效
                android.util.Log.e("AutoBackup", "加密失败，本份退化为明文快照")
            }

            // 2) 急救明文（显式开启，或上面的加密失败时兜底写一份）
            val keepPlain = keepPlainEnabledRaw()
            if (keepPlain || sealed == null) {
                wroteAnything = write(ctx.contentResolver, PREFIX + stamp + PLAIN_SUFFIX, bytes) || wroteAnything
            }

            if (!wroteAnything) return@withContext
            Graph.settings.putRawValue(LAST_KEY, now.toString())
            prune(ctx.contentResolver, keepPlain)
        }
    }

    /** 是否额外留明文急救副本。 */
    suspend fun keepPlain(): Boolean = keepPlainEnabledRaw()

    suspend fun setKeepPlain(on: Boolean) {
        Graph.settings.putRawValue(KEEP_PLAIN_KEY, if (on) "1" else "0")
    }

    private suspend fun keepPlainEnabledRaw(): Boolean =
        Graph.settings.raw(KEEP_PLAIN_KEY) == "1"

    /** 下载目录里现存哪些快照（给设置页展示/诊断用）。 */
    suspend fun status(): Status = withContext(Dispatchers.IO) {
        val resolver = Graph.appContext.contentResolver
        val names = listNames(resolver)
        val enc = names.filter { it.endsWith(ENC_SUFFIX) }
        val plain = names.filter { it.endsWith(PLAIN_SUFFIX) }
        Status(
            encrypted = enc.size,
            plain = plain.size,
            restoreCopies = names.count { it.startsWith(RESTORE_PREFIX) },
            keepPlain = keepPlainEnabledRaw(),
            lastAt = Graph.settings.raw(LAST_KEY)?.toLongOrNull() ?: 0L,
            sealedOk = SecretBox.isAvailable(),
            newestName = enc.maxOrNull().orEmpty()
        )
    }

    /**
     * 把**最新一份加密快照**解密成明文 `restore-<时间>.db`（同目录），返回文件名。
     *
     * 这条路径存在的意义：库坏了但安装还在（最常见的"写坏了/迁移炸了"）时，
     * 不需要电脑、也不需要先修好 App 的读库逻辑，就能拿回一份可被
     * `device_guard restore` 直接吃掉的明文。
     *
     * 返回 null = 没有加密快照，或解不开（重装后密钥已销毁）。
     */
    suspend fun decryptNewestToRestoreCopy(): String? = withContext(Dispatchers.IO) {
        runCatching {
            val resolver = Graph.appContext.contentResolver
            val newest = listNames(resolver).filter { it.endsWith(ENC_SUFFIX) }.maxOrNull()
                ?: return@withContext null
            val uri = uriOf(resolver, newest) ?: return@withContext null
            val sealed = resolver.openInputStream(uri)?.use { it.readBytes() } ?: return@withContext null
            val plain = SecretBox.openBytes(sealed) ?: return@withContext null
            val name = RESTORE_PREFIX + LocalDateTime.now().format(FMT) + PLAIN_SUFFIX
            if (!write(resolver, name, plain)) return@withContext null
            pruneRestoreCopies(resolver, keep = 2)
            name
        }.getOrNull()
    }

    // ---------- 内部 ----------

    private fun write(resolver: ContentResolver, name: String, bytes: ByteArray): Boolean {
        return runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = resolver.insert(COLLECTION, values) ?: return false
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrElse { e ->
            // 同名文件已存在时 insert 可能失败：删掉旧同名再试一次
            runCatching {
                uriOf(resolver, name)?.let { resolver.delete(it, null, null) }
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, DIR)
                }
                val uri = resolver.insert(COLLECTION, values) ?: return false
                resolver.openOutputStream(uri)?.use { it.write(bytes) }
                true
            }.getOrElse {
                android.util.Log.w("AutoBackup", "写快照失败：$name", e)
                false
            }
        }
    }

    /** 目录里的文件名（`planability-*` 与 `restore-*`）。 */
    private fun listNames(resolver: ContentResolver): List<String> {
        val out = mutableListOf<String>()
        runCatching {
            resolver.query(
                COLLECTION, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf(DIR + "/"), null
            )?.use { c ->
                while (c.moveToNext()) {
                    val n = c.getString(0) ?: continue
                    if (n.startsWith(PREFIX) || n.startsWith(RESTORE_PREFIX)) out += n
                }
            }
        }
        return out
    }

    private fun uriOf(resolver: ContentResolver, name: String): Uri? {
        var id = -1L
        runCatching {
            resolver.query(
                COLLECTION, arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(DIR + "/", name), null
            )?.use { c -> if (c.moveToFirst()) id = c.getLong(0) }
        }
        return if (id >= 0) ContentUris.withAppendedId(COLLECTION, id) else null
    }

    /**
     * 按份数清理：加密留 [KEEP_ENC]；明文急救留 [KEEP_PLAIN_RESCUE]（未开启时也留 1 份
     * 老版本写过明文，过渡期别把人最后一份明文快照删了）。
     */
    private fun prune(resolver: ContentResolver, keepPlain: Boolean) {
        runCatching {
            val names = listNames(resolver)
            val enc = names.filter { it.startsWith(PREFIX) && it.endsWith(ENC_SUFFIX) }.sortedDescending()
            val plain = names.filter { it.startsWith(PREFIX) && it.endsWith(PLAIN_SUFFIX) }.sortedDescending()
            enc.drop(KEEP_ENC).forEach { delete(resolver, it) }
            plain.drop(if (keepPlain) KEEP_PLAIN_RESCUE + 1 else KEEP_PLAIN_RESCUE).forEach {
                delete(resolver, it)
            }
        }
    }

    private fun pruneRestoreCopies(resolver: ContentResolver, keep: Int) {
        runCatching {
            listNames(resolver)
                .filter { it.startsWith(RESTORE_PREFIX) }
                .sortedDescending()
                .drop(keep)
                .forEach { delete(resolver, it) }
        }
    }

    private fun delete(resolver: ContentResolver, name: String) {
        runCatching { uriOf(resolver, name)?.let { resolver.delete(it, null, null) } }
    }
}
