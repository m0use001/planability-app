package io.github.m0use001.planability.core

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import java.util.Base64
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * v44：落盘加密（Android Keystore AES-256-GCM）。
 *
 * ## 解决什么
 * 库里有 API key、备份里有整库内容，而 `/data/data/...`（root 后）与
 * `Download/Planability/` 下的整库快照（任何拿到手机的人）都是**可读明文**。
 *
 * ## 密钥放哪
 * 密钥由 [KeyStore] 生成并保管，私钥材料**不出 TEE/StrongBox**，App 只能"请求它加密"，
 * 导不出来。因此：
 * - 备份文件、库里的 key 密文，拷到电脑上就是一堆字节；
 * - **卸载 = 密钥销毁 = 旧密文再也解不开**。这是硬约束，也是取舍：
 *   加密备份保护的是"手机被翻"，不是"重装后恢复"。
 *   重装恢复靠 `tools/device-guard` 存在电脑上的明文快照（那是另一条路径），
 *   或用户在备份设置里显式打开的「急救明文副本」。
 *
 * ## 失败语义（两种方向，故意不同）
 * - [seal] 失败 → **返回明文**并打 E 日志。加密只是加固，不该让"填了 key 却发不出消息"；
 *   失败本身在 logcat 可见，不会静默。
 * - [open] 失败 → **返回空串**并打 W 日志（每次进程只打一次）。绝不能把密文当 key
 *   发出去：那会得到 "invalid api key" 这种把人带偏的报错，而且等于把密文上传给服务商。
 *
 * ## 幂等
 * 已带 [PREFIX] 的值原样返回，所以调用方不必区分"来自 UI 的明文"和"来自库的密文"。
 */
object SecretBox {

    private const val TAG = "SecretBox"
    private const val ALIAS = "planability-secretbox-v1"
    private const val GCM_TAG_BITS = 128
    private const val IV_LEN = 12

    /** 字符串密文前缀。带前缀 = 密文，不带 = 历史明文（迁移前）。 */
    const val PREFIX = "enc:v1:"

    /** 备份文件头：4 字节魔数 + 版本。后面的 iv/密文是二进制，别想用 sqlite 打开。 */
    private val FILE_MAGIC = byteArrayOf(0x50, 0x42, 0x4B, 0x31) // "PBK1"
    private const val FILE_HEADER_LEN = 4 + IV_LEN

    @Volatile
    private var cachedKey: SecretKey? = null

    private val warnedOpenFailure = AtomicBoolean(false)
    private val warnedSealFailure = AtomicBoolean(false)

    fun isSealed(stored: String): Boolean = stored.startsWith(PREFIX)

    /** 密文（或空串）。失败时降级为明文，见类注释。 */
    fun seal(plain: String): String {
        if (plain.isBlank()) return ""
        if (isSealed(plain)) return plain
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key())
            val iv = cipher.iv
            val ct = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
            val packed = ByteArray(iv.size + ct.size)
            iv.copyInto(packed, 0)
            ct.copyInto(packed, iv.size)
            PREFIX + Base64.getEncoder().encodeToString(packed)
        }.getOrElse { e ->
            if (warnedSealFailure.compareAndSet(false, true)) {
                // Log 本身也包一层：日志永远不该把功能带崩（单测环境没有 android.util.Log）
                runCatching { Log.e(TAG, "加密失败，本次按明文落库（Keystore 不可用？）", e) }
            }
            plain
        }
    }

    /** 明文（历史未加密值原样返回）；解不开时返回空串。 */
    fun open(stored: String): String {
        if (stored.isBlank()) return ""
        if (!isSealed(stored)) return stored
        val packed = runCatching {
            Base64.getDecoder().decode(stored.removePrefix(PREFIX))
        }.getOrNull() ?: return ""
        if (packed.size <= IV_LEN) return ""
        val iv = packed.copyOfRange(0, IV_LEN)
        val ct = packed.copyOfRange(IV_LEN, packed.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse { e ->
            if (warnedOpenFailure.compareAndSet(false, true)) {
                runCatching { Log.w(TAG, "解密失败：多半是重装后 Keystore 密钥已销毁——该字段需要重新填写", e) }
            }
            ""
        }
    }

    // ---------- 二进制（备份文件用） ----------

    fun looksSealed(data: ByteArray): Boolean =
        data.size > FILE_HEADER_LEN + 16 &&
            data[0] == FILE_MAGIC[0] && data[1] == FILE_MAGIC[1] &&
            data[2] == FILE_MAGIC[2] && data[3] == FILE_MAGIC[3]

    fun sealBytes(data: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(data)
        return ByteArray(FILE_HEADER_LEN + ct.size).also { out ->
            FILE_MAGIC.copyInto(out, 0)
            iv.copyInto(out, 4)
            ct.copyInto(out, FILE_HEADER_LEN)
        }
    }

    /** 解密备份文件；不是本格式或解不开 → null（调用方负责提示，别当成功）。 */
    fun openBytes(data: ByteArray): ByteArray? {
        if (!looksSealed(data)) return null
        val iv = data.copyOfRange(4, FILE_HEADER_LEN)
        val ct = data.copyOfRange(FILE_HEADER_LEN, data.size)
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        }.getOrElse { e ->
            if (warnedOpenFailure.compareAndSet(false, true)) {
                runCatching { Log.w(TAG, "备份解密失败：重装后旧备份不可解（Keystore 密钥已销毁）", e) }
            }
            null
        }
    }

    /** Keystore 是否可用（自检用；失败不抛）。 */
    fun isAvailable(): Boolean = runCatching {
        open(seal("ping")) == "ping"
    }.getOrDefault(false)

    // ---------- 内部 ----------

    private fun key(): SecretKey {
        cachedKey?.let { return it }
        synchronized(this) {
            cachedKey?.let { return it }
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            val existing = (ks.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.secretKey
            val k = existing ?: KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore"
            ).apply {
                init(
                    KeyGenParameterSpec.Builder(
                        ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
                    )
                        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                        .setKeySize(256)
                        .build()
                )
            }.generateKey()
            cachedKey = k
            return k
        }
    }
}
