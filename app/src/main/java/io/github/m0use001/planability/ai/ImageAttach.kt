package io.github.m0use001.planability.ai

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.collection.LruCache
import io.github.m0use001.planability.data.Graph
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt

/**
 * 2026-11-10 图片直通视觉模型（用户：「发个数学手写推到立即出问题」）。
 *
 * 以前图片只走 file_read_image → ML Kit OCR，模型收到的是**识别文本**——手写数学
 * 被 OCR 弄碎（真机：一张手写推导只认出 18 个零碎符号），而 deepseek-v4.1-flash 本身
 * 有视觉。现在序列化 user 消息时，把正文里引用的 attachments/ 图片转成 OpenAI 兼容的
 * image_url part（data URL），模型直接看图；file_read_image 降为兜底（路径引用、
 * 文本提取、或端点拒绝图片时模型仍可主动调用）。
 *
 * 降采样 ≤1568px + JPEG 82：视觉模型按图块计费，原图直塞既慢又贵；截图/手写清晰度够。
 * 缓存按 path+mtime：工具轮每轮都重建请求体，不能每轮重编一遍位图。
 */
object ImageAttach {

    /** 单条消息最多直出 4 张，防一段话贴十几张图把请求体撑爆 */
    const val MAX_PER_MESSAGE = 4

    private const val MAX_PX = 1568

    /** 用户消息正文里的附件引用（新旧标记长一个样：attachments/xxx.jpg） */
    private val REF = Regex(
        """(?:attachments|exports)/[^\s\]）()，,。；;"'<>]+\.(?:jpg|jpeg|png|webp)""",
        RegexOption.IGNORE_CASE
    )

    /** 识别正文里的附件图片引用（去重、封顶） */
    fun imageRefsIn(text: String): List<String> =
        REF.findAll(text).map { it.value }.distinct().take(MAX_PER_MESSAGE).toList()

    private val cache = LruCache<String, String>(8)

    /**
     * 相对路径 → data URL；文件缺失/解码失败返回 null（调用方按纯文本发，OCR 指令保留）。
     *
     * `exportDir`（2026-11-11 全链撞测 bug#3）：`exports/` 是**虚拟前缀**——真实文件在
     * 会话导出目录（ExportStore.dirFor）下，不在 filesDir/exports。工具直通链
     （file_read_image 快照）引用的正是 exports/ 路径，不传会话导出目录就永远 miss，
     * 直通静默失效。调用方（ChatEngine→streamChat）把本会话导出目录传进来。
     */
    fun dataUrl(relPath: String, exportDir: java.io.File? = null): String? {
        val f = java.io.File(Graph.appContext.filesDir, relPath).takeIf { it.isFile }
            ?: exportDir?.let { d ->
                val inner = relPath.trim().removePrefix("exports/").removePrefix("/")
                java.io.File(d, inner).takeIf { it.isFile }
            }
            ?: return null
        val key = f.absolutePath + "|" + f.lastModified()
        cache.get(key)?.let { return it }
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            // inSampleSize 只能 2 的幂：先粗降再精确缩到 MAX_PX
            var sample = 1
            var maxSide = maxOf(bounds.outWidth, bounds.outHeight)
            while (maxSide / 2 >= MAX_PX) {
                sample *= 2
                maxSide /= 2
            }
            val bmp = BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
                ?: return null
            val k = maxOf(bmp.width, bmp.height).toFloat() / MAX_PX
            val out = if (k > 1f) {
                Bitmap.createScaledBitmap(
                    bmp,
                    (bmp.width / k).roundToInt().coerceAtLeast(1),
                    (bmp.height / k).roundToInt().coerceAtLeast(1),
                    true
                )
            } else bmp
            val bos = ByteArrayOutputStream()
            out.compress(Bitmap.CompressFormat.JPEG, 82, bos)
            val url = "data:image/jpeg;base64," + Base64.encodeToString(bos.toByteArray(), Base64.NO_WRAP)
            if (out !== bmp) out.recycle()
            cache.put(key, url)
            url
        }.getOrNull()
    }

    /** data URL → (mime, 纯 base64)；Anthropic image 块要拆开传 */
    fun splitDataUrl(url: String): Pair<String, String>? {
        val m = Regex("""data:([^;]+);base64,(.+)""", RegexOption.DOT_MATCHES_ALL).find(url) ?: return null
        return m.groupValues[1] to m.groupValues[2]
    }
}
