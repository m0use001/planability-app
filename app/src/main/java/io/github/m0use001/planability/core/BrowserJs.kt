package io.github.m0use001.planability.core

import org.json.JSONObject

/**
 * 浏览器自动化用的 JS 片段（v43：用户定向的「浏览器自动化」线）。
 *
 * 纯字符串构造：不做任何网络/DOM 操作，单测直接验证转义与骨架
 *（真正的执行在 browser/BrowserSession，跑在主线程的 WebView 上）。
 *
 * 设计边界（与电脑端 Playwright 的差异）：只做**日常网页操作**——
 * 打开、按文字/选择器点击、填表、取文。不做多标签、不做反爬对抗、
 * 不做等待网络空闲的复杂调度；页面找不到元素就如实报 NOT_FOUND，
 * 由模型决定下一步（刷新/换选择器/直接取文）。
 */
object BrowserJs {

    /** JS 字符串字面量（转义引号/反斜杠/换行，防止参数注入脚本） */
    fun q(s: String): String = JSONObject.quote(s)

    /** 页面摘要：标题、URL、正文前 3000 字、前 60 个可交互元素 */
    fun summary(): String = """
(function(){
  var els=[];
  var cand=document.querySelectorAll('a,button,input,textarea,select,[role=button]');
  for(var i=0;i<cand.length && els.length<60;i++){
    var e=cand[i];
    var t=(e.innerText||e.value||e.getAttribute('aria-label')||e.placeholder||'').trim().replace(/\s+/g,' ').slice(0,40);
    if(t) els.push({tag:e.tagName.toLowerCase(),text:t});
  }
  var body=document.body?document.body.innerText:'';
  body=body.replace(/\n{3,}/g,'\n\n').slice(0,3000);
  return JSON.stringify({title:document.title,url:location.href,text:body,els:els});
})()
""".trimIndent()

    /** 按选择器优先、文字兜底点击；返回 CLICKED:<tag> 或 NOT_FOUND */
    fun click(selector: String?, text: String?): String = """
(function(){
  var sel=${q(selector.orEmpty())};
  var txt=${q((text ?: "").trim())};
  var el=null;
  if(sel){ try{ el=document.querySelector(sel); }catch(e){ el=null; } }
  if(!el && txt){
    var all=document.querySelectorAll('a,button,input,[role=button],li,span,div');
    for(var i=0;i<all.length;i++){
      var t=(all[i].innerText||all[i].value||'').trim().replace(/\s+/g,' ');
      if(t===txt){ el=all[i]; break; }
    }
  }
  if(!el) return 'NOT_FOUND';
  try{ el.scrollIntoView({block:'center'}); }catch(e){}
  el.click();
  return 'CLICKED:'+el.tagName.toLowerCase();
})()
""".trimIndent()

    /** 填表：设 value + 触发 input/change（React/Vue 受控输入依赖这两个事件） */
    fun fill(selector: String, text: String): String = """
(function(){
  var el=null;
  try{ el=document.querySelector(${q(selector)}); }catch(e){ el=null; }
  if(!el) return 'NOT_FOUND';
  el.focus();
  var proto=Object.getPrototypeOf(el);
  var desc=Object.getOwnPropertyDescriptor(proto,'value');
  if(desc && desc.set){ desc.set.call(el, ${q(text)}); } else { el.value=${q(text)}; }
  el.dispatchEvent(new Event('input',{bubbles:true}));
  el.dispatchEvent(new Event('change',{bubbles:true}));
  return 'FILLED';
})()
""".trimIndent()

    /** 取文：有选择器取该节点 innerText，否则整页正文（上限 6000 字） */
    fun extract(selector: String?): String = """
(function(){
  var sel=${q(selector.orEmpty())};
  var el=null;
  if(sel){ try{ el=document.querySelector(sel); }catch(e){ el=null; } }
  if(sel && !el) return 'NOT_FOUND';
  var t=el?el.innerText:(document.body?document.body.innerText:'');
  return (t||'').replace(/\n{3,}/g,'\n\n').slice(0,6000);
})()
""".trimIndent()

    /** 回退一页；返回当前 URL 或 NO_HISTORY */
    fun back(): String = """
(function(){
  if(history.length>1){ history.back(); return 'BACK'; }
  return 'NO_HISTORY';
})()
""".trimIndent()
}
