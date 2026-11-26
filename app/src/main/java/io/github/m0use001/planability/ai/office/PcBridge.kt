package io.github.m0use001.planability.ai.office

import android.util.Base64
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.shared.DocModel
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * v14.3：**电脑端高保真渲染桥**（P3）。
 *
 * ## 它解决什么
 *
 * 手机端自带的写出器零三方依赖、离线可用，但版式是我们自造的一套简化规则
 * （见 [PptxWriter]）：没有母版继承、没有原生图表对象、没有动画。电脑上装着
 * `pptxgenjs` 这类成熟引擎，能出保真度高的文件。用户拍板的是**混合路线**——
 * 离线出简版、电脑在线自动升级高保真——这个类就是那条自动切换的判据。
 *
 * ## 契约（两端没有共享代码，只能靠这份文档 + 对拍维持一致）
 *
 * - `GET  /health` → `{"ok":true,"caps":["pptx","docx","xlsx"]}`
 *   先问这个再决定用不用电脑：电脑没开、端口不通、服务在跑但版本不认，都在这里暴露，
 *   **不要靠"试一下渲染成功没有"来判断**，那会把"网络失败"和"渲染失败"混为一谈。
 * - `POST /render` → 请求体 `{"kind":"pptx","deck":{...}}`，响应体**直接是文件二进制**
 *   （Content-Type 按 kind）。用二进制而不是 base64 JSON：一份 20 页带图的 PPT
 *   base64 后要涨三分之一，白白占带宽和内存。
 *
 * 有意的取舍：**服务默认只绑 127.0.0.1**。局域网可达意味着同一网络里 anyone
 * 都能让用户的电脑生成文件；需要跨设备时由用户自己用 `adb reverse` 或显式改监听地址。
 *
 * ## 为什么不在渲染失败时静默回退到手机端
 *
 * 静默回退看起来"体验好"，实际是**骗人**：用户以为自己拿到的是高保真版，
 * 打开发现是简版，而且没有任何线索告诉他为什么。宁可明确告诉他"电脑端渲染失败，
 * 已用手机端生成"，把选择权交回给用户。
 */
object PcBridge {

    /** 电脑端服务的默认地址。127.0.0.1 配合 `adb reverse tcp:4097 tcp:4097` 可直连。 */
    const val DEFAULT_URL = "http://127.0.0.1:4097"

    private const val KV_URL = "office.pc.url"
    private const val KV_ENABLED = "office.pc.enabled"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)   // 探测要快：电脑没开时不能卡住整个生成
            .readTimeout(60, TimeUnit.SECONDS)     // 渲染可能真的慢
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    // ---------- 配置 ----------

    suspend fun enabled(): Boolean = Graph.settings.kvGet(KV_ENABLED) == "1"

    suspend fun setEnabled(on: Boolean) {
        Graph.settings.kvPut(KV_ENABLED, if (on) "1" else "0")
    }

    suspend fun baseUrl(): String = Graph.settings.kvGet(KV_URL)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL

    suspend fun setBaseUrl(url: String) {
        Graph.settings.kvPut(KV_URL, url.trim().trimEnd('/'))
    }

    // ---------- 探测 ----------

    data class Health(val ok: Boolean, val caps: List<String>, val detail: String)

    /**
     * 问电脑在不在、支不支持我们要的格式。
     *
     * 超时故意很短（3s）：这个函数在**每次生成前**都会调，电脑没开是常态，
     * 让用户等十几秒才知道"电脑不可用"是不能接受的。
     */
    suspend fun health(): Health = runCatching {
        val req = Request.Builder().url("${baseUrl()}/health").get().build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@runCatching Health(false, emptyList(), "HTTP ${resp.code}")
            val body = resp.body?.string().orEmpty()
            val obj = org.json.JSONObject(body)
            val caps = ArrayList<String>()
            obj.optJSONArray("caps")?.let { a ->
                for (i in 0 until a.length()) caps.add(a.optString(i))
            }
            Health(obj.optBoolean("ok"), caps, obj.optString("version"))
        }
    }.getOrElse { Health(false, emptyList(), it.message ?: it.javaClass.simpleName) }

    /** 电脑端能不能接这个活（启用 + 在线 + 支持该格式）。 */
    suspend fun usableFor(kind: String): Boolean = enabled() && runCatching { health() }.getOrNull()
        ?.let { it.ok && it.caps.contains(kind) } == true

    // ---------- 渲染 ----------

    /**
     * 让电脑端渲染 [deck]，结果写到 [out]。
     *
     * @return null 表示**电脑端没接住**（不在线/不支持/网络失败）；
     *   抛异常表示电脑端处理了但出错（那种情况应当报错，不要悄悄回退）
     */
    suspend fun render(kind: String, deck: DocModel.Deck, out: File): String? =
        renderRaw(
            org.json.JSONObject().put("deck", DocModel.deckToJson(deck)).let { o ->
                org.json.JSONObject().put("kind", kind).put("payload", o)
            },
            out
        )

    /**
     * 让电脑端渲染 docx（段落一行一段）。
     *
     * 与 [render] 分开是因为入参结构不同：pptx 要整套 DocModel，
     * 而 docx/xlsx 手机端本来就是"一行一段 / 一行一条记录"的朴素格式。
     * **不要让电脑端去猜手机端的内容格式**——直接传结构化数据，两边都省事。
     */
    suspend fun renderDocx(paragraphs: List<String>, title: String, out: File): String? =
        renderRaw(
            org.json.JSONObject().put("kind", "docx").put(
                "payload",
                org.json.JSONObject()
                    .put("title", title)
                    .put(
                        "paragraphs",
                        org.json.JSONArray().apply { paragraphs.forEach { put(it) } }
                    )
            ),
            out
        )

    /** 二维数组 → xlsx，首行作表头。 */
    suspend fun renderXlsx(rows: List<List<String>>, sheetName: String, out: File): String? =
        renderRaw(
            org.json.JSONObject().put("kind", "xlsx").put(
                "payload",
                org.json.JSONObject()
                    .put("sheetName", sheetName)
                    .put(
                        "rows",
                        org.json.JSONArray().apply {
                            rows.forEach { r ->
                                put(org.json.JSONArray().apply { r.forEach { put(it) } })
                            }
                        }
                    )
            ),
            out
        )

    /** 共用的请求与落盘逻辑。调用方只管给 payload，异常一律吞成 null（= 电脑端没接住）。 */
    private suspend fun renderRaw(body: org.json.JSONObject, out: File): String? = runCatching {
        val req = Request.Builder()
            .url("${baseUrl()}/render")
            .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                error("电脑端返回 HTTP ${resp.code}：${resp.message}")
            }
            val bytes = resp.body?.bytes() ?: error("电脑端返回了 0 字节")
            if (bytes.isEmpty()) error("电脑端返回了空内容")
            out.parentFile?.mkdirs()
            out.writeBytes(bytes)
            val hdr = resp.header("X-Office-Engine").orEmpty()
            if (hdr.isNotBlank()) hdr else "电脑端"
        }
    }.getOrNull()

    /** 改稿的结果。[applied] 是电脑端回报"实际做了哪几件事"，用于如实转述给用户。 */
    data class PatchResult(val engine: String, val applied: String)

    /** 电脑端把"不支持"和"坏了"分开回报；这里只把"不支持"当正常结果返回给上层判断。 */
    sealed interface Patch {
        data class Ok(val engine: String, val applied: String) : Patch
        data class Unsupported(val reason: String) : Patch
        data class Failed(val reason: String) : Patch
    }

    /**
     * 改既有 xlsx 的单元格/行。
     *
     * 只有 xlsx：pptxgenjs 与 docx 都没有读取既有文件的入口（查过原型与导出），
     * 只能"从零重新生成"——而那对改稿是**倒退**，会把母版/动画/图表全抹掉，
     * 正是手机端 [PptxEditor] 刻意避免的。所以那两种格式由手机端自己增量改。
     *
     * @param ops JSON 数组字符串，原样转发（格式见 [parseOps]）
     */
    suspend fun patchXlsx(file: File, opsJson: String, out: File): Patch {
        if (!enabled()) return Patch.Failed("电脑端渲染未开启（在「信息 → 文件库」页可以打开）")
        val b64 = Base64.encodeToString(file.readBytes(), Base64.NO_WRAP)
        val body = org.json.JSONObject()
            .put("kind", "xlsx")
            .put("fileBase64", b64)
            .put("ops", org.json.JSONArray(opsJson))
            .toString()
        val req = Request.Builder()
            .url("${baseUrl()}/patch")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return try {
            client.newCall(req).execute().use { resp ->
                when {
                    resp.code == 501 -> Patch.Unsupported(
                        resp.body?.string().orEmpty().let {
                            runCatching { org.json.JSONObject(it).optString("error") }
                                .getOrNull()?.takeIf { s -> s.isNotBlank() } ?: "电脑端不支持这个格式的改稿"
                        }
                    )
                    !resp.isSuccessful -> Patch.Failed(
                        "电脑端返回 HTTP ${resp.code}：" +
                            (resp.body?.string().orEmpty().take(200))
                    )
                    else -> {
                        val bytes = resp.body?.bytes() ?: error("空内容")
                        if (bytes.isEmpty()) error("空内容")
                        out.parentFile?.mkdirs()
                        out.writeBytes(bytes)
                        val applied = runCatching {
                            java.net.URLDecoder.decode(
                                resp.header("X-Office-Applied").orEmpty(), "UTF-8"
                            )
                        }.getOrDefault("")
                        Patch.Ok(
                            resp.header("X-Office-Engine").orEmpty().ifBlank { "电脑端" },
                            applied
                        )
                    }
                }
            }
        } catch (e: Exception) {
            Patch.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    /**
     * 让电脑端把**已生成的**文件转成 PDF 并写到 [out]。
     *
     * ## 为什么值得
     *
     * 手机端的"翻页预览"是**我们自己按排版数据画的 PNG**，它反映的是手机端自己的理解，
     * 和电脑上的真实版式不是一回事（尤其当电脑端引擎参与生成时）。有 LibreOffice 就能出
     * **真·所见即所得**的 PDF，而手机端自带 PdfRenderer，直接翻页看。
     *
     * @return null 表示没接住或本机没有 LibreOffice；上层据此退回自绘快照，**不报错**——
     *   PDF 是"更好的预览"，不是必需品，为它让整个流程失败不值得。
     */
    suspend fun toPdf(file: File, out: File, ext: String): String? {
        val body = org.json.JSONObject()
            .put("ext", ext)
            .put("fileBase64", Base64.encodeToString(file.readBytes(), Base64.NO_WRAP))
            .toString()
        val req = Request.Builder()
            .url("${baseUrl()}/transform?to=pdf")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (resp.code == 501) error("电脑端没有 LibreOffice")
                if (!resp.isSuccessful) error("HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: error("空内容")
                if (bytes.isEmpty()) error("空 PDF")
                out.parentFile?.mkdirs()
                out.writeBytes(bytes)
                resp.header("X-Office-Engine").orEmpty().ifBlank { "LibreOffice" }
            }
        }.getOrNull()
    }
}
