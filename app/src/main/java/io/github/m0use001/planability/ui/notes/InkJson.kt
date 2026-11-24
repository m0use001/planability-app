package io.github.m0use001.planability.ui.notes

import org.json.JSONArray
import org.json.JSONObject

/** 墨迹笔画：颜色索引、线宽、点序列 [x,y,x,y,...] */
data class InkStroke(val colorIdx: Int, val width: Float, val points: FloatArray)

fun parseInk(json: String): MutableList<InkStroke> {
    val list = mutableListOf<InkStroke>()
    if (json.isBlank()) return list
    runCatching {
        val arr = JSONArray(json)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val pArr = o.getJSONArray("p")
            val pts = FloatArray(pArr.length())
            for (j in 0 until pArr.length()) pts[j] = pArr.getDouble(j).toFloat()
            list += InkStroke(o.getInt("c"), o.getDouble("w").toFloat(), pts)
        }
    }
    return list
}

fun inkToJson(strokes: List<InkStroke>): String {
    val arr = JSONArray()
    strokes.forEach { s ->
        val p = JSONArray()
        s.points.forEach { p.put(it.toDouble()) }
        arr.put(JSONObject().put("c", s.colorIdx).put("w", s.width.toDouble()).put("p", p))
    }
    return arr.toString()
}

/** PDF 批注按页存储：{"pages":[{"n":页码,"s":[笔画...]}]} */
fun parsePdfMarks(json: String): MutableMap<Int, MutableList<InkStroke>> {
    val map = mutableMapOf<Int, MutableList<InkStroke>>()
    if (json.isBlank()) return map
    runCatching {
        val root = JSONObject(json)
        val pages = root.getJSONArray("pages")
        for (i in 0 until pages.length()) {
            val o = pages.getJSONObject(i)
            map[o.getInt("n")] = parseInk(o.getJSONArray("s").toString())
        }
    }
    return map
}

fun pdfMarksToJson(pages: Map<Int, List<InkStroke>>): String {
    val arr = JSONArray()
    pages.entries.sortedBy { it.key }.forEach { (n, strokes) ->
        if (strokes.isNotEmpty()) {
            arr.put(JSONObject().put("n", n).put("s", JSONArray(inkToJson(strokes))))
        }
    }
    return JSONObject().put("pages", arr).toString()
}
