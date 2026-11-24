package io.github.m0use001.planability.core

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 端侧 OCR（ML Kit 中文识别，完全离线） */
object OcrHelper {

    private val recognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }

    /** OCR 单行结果（含屏幕坐标，供按文字位置点击——微信/QQ 屏蔽无障碍节点后的采集通道） */
    data class OcrLine(
        val text: String,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val centerX: Int get() = (left + right) / 2
        val centerY: Int get() = (top + bottom) / 2
    }

    // ---------- 长图切条（2026-11-04 用户实测：1272×23060 的长截屏直喂 ML Kit，
    // 整图被压到识别极限，全文只吐出「●eDee」几个乱字） ----------
    private const val MAX_DIRECT_EDGE = 2048   // 单边超过就切条（ML Kit 有效识别范围）
    private const val TILE_H = 1600            // 每条带高（px）
    private const val TILE_OVERLAP = 96        // 条带重叠，避免正好切断一行文字
    private const val MAX_TILES = 60           // 条数上限（60×1600 ≈ 9.6 万 px 长图）

    /** 从图片 URI 提取文本（小图直喂；长图/超大图自动切条逐段识别再拼接） */
    suspend fun recognize(context: Context, uri: Uri): String = withContext(Dispatchers.IO) {
        // 先量尺寸
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
        }
        val w = bounds.outWidth
        val h = bounds.outHeight
        if (w <= 0 || h <= 0) return@withContext ""
        if (w <= MAX_DIRECT_EDGE && h <= MAX_DIRECT_EDGE) {
            return@withContext recognizeDirect(context, uri)
        }
        recognizeTiled(context, uri, w, h)
    }

    /** 小图：原路径（fromFilePath 自带 EXIF 旋转校正） */
    private suspend fun recognizeDirect(context: Context, uri: Uri): String {
        val image = InputImage.fromFilePath(context, uri)
        return runRecognizer(image).trim()
    }

    /**
     * 长图切条：横向超宽先降采样到 MAX_DIRECT_EDGE 内，纵向按 [TILE_H]（带重叠）切条，
     * 逐条 OCR 后按原顺序拼接；相邻条带的重叠区去重（删掉与上一条尾部重复的行）。
     */
    private suspend fun recognizeTiled(context: Context, uri: Uri, w: Int, h: Int): String {
        val decoder = runCatching {
            context.contentResolver.openInputStream(uri)?.use {
                BitmapRegionDecoder.newInstance(it, false)
            }
        }.getOrNull() ?: return ""
        val decoder0 = decoder ?: return ""
        try {
            // 横向超宽（横版长图）先降采样
            val sample = if (w > MAX_DIRECT_EDGE) {
                var s = 1
                while (w / (s * 2) >= MAX_DIRECT_EDGE) s *= 2
                s
            } else 1
            val step = TILE_H - TILE_OVERLAP
            val parts = ArrayList<String>()
            var y = 0
            var tiles = 0
            var truncated = false
            while (y < h) {
                if (tiles >= MAX_TILES) {
                    truncated = true
                    break
                }
                val bottom = minOf(y + TILE_H, h)
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val bmp = runCatching {
                    decoder0.decodeRegion(Rect(0, y, w, bottom), opts)
                }.getOrNull()
                if (bmp != null) {
                    val text = runCatching {
                        runRecognizer(InputImage.fromBitmap(bmp, 0))
                    }.getOrDefault("")
                    if (text.isNotBlank()) parts.add(text)
                    bmp.recycle()
                }
                tiles++
                if (bottom >= h) break
                y += step
            }
            if (parts.isEmpty()) return ""
            // 拼接 + 重叠区去重：后一条的开头若与前面已累计文本的结尾重复则丢弃
            val sb = StringBuilder()
            for ((i, p) in parts.withIndex()) {
                val text = if (i == 0) p.trim() else trimOverlap(sb.toString(), p)
                if (text.isBlank()) continue
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(text.trim())
            }
            if (truncated) sb.append("\n（图片过长，仅识别前 ").append(tiles).append(" 段）")
            return sb.toString().trim()
        } finally {
            decoder0.recycle()
        }
    }

    /** 去掉 [next] 开头与 [acc] 结尾重复的行（条带重叠区） */
    private fun trimOverlap(acc: String, next: String): String {
        val accLines = acc.lines().takeLast(8).map { it.trim() }.filter { it.isNotEmpty() }
        val nextLines = next.trim().lines().map { it.trim() }
        var drop = 0
        outer@ for (k in minOf(nextLines.size, accLines.size).coerceAtMost(8) downTo 1) {
            val head = nextLines.take(k)
            val tail = accLines.takeLast(k)
            if (head == tail) {
                drop = k
                break@outer
            }
        }
        return nextLines.drop(drop).joinToString("\n")
    }

    private suspend fun runRecognizer(image: InputImage): String =
        suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it.text) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }

    /**
     * 从 Bitmap 提取带坐标的文本行（截屏采集用）。
     *
     * [scale] < 1 时先缩放再识别（ML Kit 延迟大致随像素线性，0.7× → 约一半像素）。
     * **坐标会按 1/scale 还原回原始像素**，调用方拿到的几何仍是屏幕坐标系，
     * 所以点击/裁剪逻辑都不用改。
     */
    suspend fun recognizeLines(bitmap: Bitmap, scale: Float = 1f): List<OcrLine> = withContext(Dispatchers.IO) {
        val s = scale.coerceIn(0.3f, 1f)
        val src = if (s >= 0.99f) bitmap else {
            val w = (bitmap.width * s).toInt().coerceAtLeast(1)
            val h = (bitmap.height * s).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(bitmap, w, h, true)
        }
        val image = InputImage.fromBitmap(src, 0)
        val text = suspendCancellableCoroutine { cont ->
            recognizer.process(image)
                .addOnSuccessListener { cont.resume(it) }
                .addOnFailureListener { cont.resumeWithException(it) }
        }
        val inv = 1f / s
        text.textBlocks.flatMap { it.lines }.mapNotNull { l ->
            val t = l.text.trim()
            if (t.isBlank()) return@mapNotNull null
            val b = l.boundingBox ?: Rect()
            if (b.isEmpty) return@mapNotNull null
            OcrLine(
                t,
                (b.left * inv).toInt(),
                (b.top * inv).toInt(),
                (b.right * inv).toInt(),
                (b.bottom * inv).toInt()
            )
        }.also { if (src !== bitmap) src.recycle() }
    }
}
