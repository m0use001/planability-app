package io.github.m0use001.planability.browser

import io.github.m0use001.planability.ai.AiClient
import io.github.m0use001.planability.ui.files.DocTranslate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * **网页双语对照翻译**（2026-11-05，用户问「沉浸式翻译怎么在手机上实现」）。
 *
 * ## 为什么放在 AI 浏览器面板里
 *
 * 手机浏览器装不了扩展（Chrome for Android 不支持扩展），系统级划词翻译也不归 App 管。
 * 我们手上唯一能注入 DOM 的容器就是**自己的 WebView**——也就是 AI 浏览器窗口
 * （browser_* 工具已经在用它）。所以"沉浸式翻译"在这里的形态是：
 * 面板右上角一个「译」按钮 → 页面每个段落下方插入中文译文 → 再点一次还原。
 *
 * ## 为什么逐段插在原文下方（而不是替换/弹层）
 *
 * 替换原文会让人失去"对照"的意义（看不懂的地方需要看原文）；
 * 弹层要来回点。插在段后是唯一"读一遍就够"的形态，也最接近沉浸式翻译的观感。
 *
 * ## 边界
 *
 * 只取**块级元素**（p/li/标题/单元格/引用），跳过嵌套块的父级（否则同一段话翻两遍）；
 * 跳过太短、纯数字、已以中文为主的块；单页最多 [MAX_BLOCKS] 段、单段最多 1200 字，
 * 防止长文档把 token 打空。译文用 `currentColor` + 透明度着色，明暗主题页面都读得清。
 */
object BrowserTranslate {

    internal const val MAX_BLOCKS = 140

    /** 收集待翻段落：给每个块打 data-zht 标记，返回 [{i,t}] JSON */
    fun collectJs(): String = """
(function(){
  try{
    Array.prototype.forEach.call(document.querySelectorAll('.zht-tr'), function(n){ n.parentNode.removeChild(n); });
    var latin=function(s){ var m=s.match(/[A-Za-z]/g); return m?m.length:0; };
    var sel='p,li,h1,h2,h3,h4,h5,h6,td,th,blockquote,dd,dt,figcaption';
    var blocks=document.querySelectorAll(sel);
    var out=[]; var seen={};
    for(var k=0;k<blocks.length;k++){
      var el=blocks[k];
      if(el.querySelector('p,li,blockquote')) continue;
      var t=(el.innerText||'').replace(/\s+/g,' ').trim();
      if(t.length<12) continue;
      if(latin(t)<6) continue;
      if(t.length>1200) t=t.slice(0,1200);
      if(seen[t]) continue;
      seen[t]=1;
      var idx=out.length;
      out.push({i:idx,t:t});
      el.setAttribute('data-zht', String(idx));
      if(out.length>=${MAX_BLOCKS}) break;
    }
    return JSON.stringify(out);
  }catch(e){ return '[]'; }
})()
""".trimIndent()

    /** 把译文本插到对应块后面；[pairs] 为 (块下标, 译文) */
    fun injectJs(pairs: List<Pair<Int, String>>): String {
        val arr = JSONArray()
        pairs.forEach { (i, t) ->
            arr.put(JSONArray().put(i).put(t))
        }
        return """
(function(list){
  try{
    for(var k=0;k<list.length;k++){
      var i=list[k][0], t=list[k][1];
      var el=document.querySelector('[data-zht="'+i+'"]');
      if(!el) continue;
      var d=document.createElement('div');
      d.className='zht-tr';
      d.style.cssText='margin:6px 0 10px;padding:1px 0 1px 10px;border-left:3px solid rgba(90,190,120,0.65);color:currentColor;opacity:0.82;font-size:0.94em;line-height:1.6;';
      d.textContent=t;
      el.parentNode.insertBefore(d, el.nextSibling);
    }
    return 'OK';
  }catch(e){ return 'ERR:'+e; }
})(${arr})
""".trimIndent()
    }

    /** 还原原文（移除所有译文节点与标记） */
    fun clearJs(): String = """
(function(){
  try{
    Array.prototype.forEach.call(document.querySelectorAll('.zht-tr'), function(n){ n.parentNode.removeChild(n); });
    Array.prototype.forEach.call(document.querySelectorAll('[data-zht]'), function(n){ n.removeAttribute('data-zht'); });
    return 'OK';
  }catch(e){ return 'ERR:'+e; }
})()
""".trimIndent()

    data class Block(val index: Int, val text: String)

    /** 解析 collectJs 的返回值（数组不符就当作空，绝不抛） */
    internal fun parseBlocks(raw: Any?): List<Block> {
        val s = raw?.toString().orEmpty()
        if (s.isBlank()) return emptyList()
        val arr = runCatching { JSONArray(s) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Block>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val t = o.optString("t")
            if (t.isNotBlank()) out += Block(o.optInt("i", i), t)
        }
        return out
    }

    /**
     * 整页翻译：收集 → 分批翻 → **每批翻完立刻注入**（用户边读边出译文）。
     * 返回 (段落数, 错误信息或 null)。
     */
    suspend fun translatePage(
        eval: suspend (String) -> Any?,
        onProgress: (done: Int, total: Int) -> Unit
    ): Pair<Int, String?> {
        val blocks = parseBlocks(runCatching { eval(collectJs()) }.getOrNull())
        if (blocks.isEmpty()) return 0 to "这个页面没有可翻译的英文段落"
        val cfg = runCatching { AiClient.usableWorkerConfig() }.getOrNull()
            ?: return 0 to "没有可用的模型配置（先在「模型与智能体」里配一个能用的模型）"
        var done = 0
        blocks.chunked(DocTranslate.BATCH).forEach { chunk ->
            val texts = chunk.map { it.text }
            val raw = runCatching {
                withContext(Dispatchers.IO) {
                    AiClient.chatWith(
                        cfg,
                        "你是网页翻译助手，把用户给的段落翻译成简明准确的简体中文。" +
                            "公式、符号、代码、专有名词保持原样；不加解释；严格按「序号|译文」每行一条输出。",
                        DocTranslate.buildUserBlock(texts),
                        temperature = 0.2
                    )
                }
            }.getOrNull()
            val parsed = raw?.let { DocTranslate.parseBatch(it, chunk.size) }
                ?: List(chunk.size) { null }
            val pairs = ArrayList<Pair<Int, String>>()
            parsed.forEachIndexed { k, t ->
                if (!t.isNullOrBlank()) pairs += chunk[k].index to t
            }
            if (pairs.isNotEmpty()) runCatching { eval(injectJs(pairs)) }
            done += chunk.size
            onProgress(done, blocks.size)
        }
        return blocks.size to null
    }

}
