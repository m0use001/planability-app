package io.github.m0use001.planability.ui.edit

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import io.github.m0use001.planability.core.AmapApi
import io.github.m0use001.planability.core.GeoCodec
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.core.MapTileOcr
import io.github.m0use001.planability.core.PlaceAliasStore
import io.github.m0use001.planability.core.PlaceSearch
import kotlin.coroutines.resume
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.SideEffect
import io.github.m0use001.planability.data.CategoryEntity
import io.github.m0use001.planability.data.Graph
import io.github.m0use001.planability.ui.common.filterChipColors
import io.github.m0use001.planability.ui.common.motionEnter
import io.github.m0use001.planability.ui.common.pressScale

/**
 * 地图选点参数。坐标一律 **WGS84**（应用存储口径），`label` 是逆地理拿到的地名/地址。
 *
 * `label` 默认空串是为了**不改任何调用方的构造代码** —— 三个调用点
 * （日程/待办编辑器、自动化围栏）原来都是 `GeoPoint(lat, lng)`。
 */
data class GeoPoint(val lat: Double, val lng: Double, val label: String = "")

/**
 * 逆地理源在境外/无结果时会给出 JSON 空壳（腾讯 `address` 是空数组时 `optString` 直接
 * 返回字面量 `"[]"`），当成地名会原样显示在绑定卡上（模拟器 GPS 在境外的实测）。
 * 显示与回填统一洗一遍；`"{}"` / `"null"` 同理。
 */
private fun cleanLabel(s: String?): String {
    val t = s?.trim().orEmpty()
    return if (t == "[]" || t == "{}" || t.equals("null", true)) "" else t
}

/**
 * 地图选点的容器。
 *
 * ## 演进：两轮换法，根因是同一个
 *
 * **v14**：换掉 `ModalBottomSheet`。手势冲突的根因不在 WebView，而在 sheet 身上：
 * `ModalBottomSheet` 的 `AnchoredDraggable` 把拖拽识别铺在**整个 sheet 表面**上，
 * 地图里的一切手势——单指拖动、双指缩放——都会先被它当成"拖面板"吃掉。
 * 在 WebView 侧做的三件事（`setSupportZoom(false)`、页面 `touch-action:none`、
 * 双指 `touchmove` preventDefault）全都拦不住，因为那些只约束 WebView 内部，
 * 管不到外层 Compose 的拖拽层。真机实测也不是"有点抢"，而是**单指竖直拖 600px，
 * 面板直接被拽到关闭**——地图根本没法单指平移。
 *
 * **v14.4（本版）**：手写弹层也没解决问题，所以**不再要弹层**，改成全屏独立界面。
 * 自己写的弹层同样是"有第二个手势归属者"的容器：scrim 点按关闭、头部拖拽关闸、
 * 还要和系统返回抢焦点——只要底下还有拖拽层，地图区就永远不独占。
 * 现在地图就是 Leaflet 独占的一整块，返回交给顶部按钮 + `BackHandler`，**没有下拉关闭**。
 *
 * ## 保留 Dialog 包裹，只把它撑满
 *
 * 四个调用方（Editors×2 / PlanScreen / AutomationScreen）都用 `Dialog { MapPickerSheet(...) }`
 * 包着，那是 2026-09-09 为修"选点开不出"特意加的**不能去掉**。这里就地把当前窗口撑成
 * 全屏：Dialog 的 content 里 `LocalView.current.window` 就是那个 Dialog 的窗口
 * （`View.getWindow()` 返回所属 Window，不依赖 context 能否转成 Dialog 对象）。
 *
 * 这样 4 个调用方**一个都不用改**（含 phone-b 的 PlanScreen，避免跨线半迁移），
 * 09-09 那个修复也留着。`SideEffect` 每次组合后都设一次——只设一次的话，
 * Compose 后续套用 `usePlatformDefaultWidth` 会把它冲掉。
 *
 * ## 不变的规矩
 *
 * 地图区**不挂任何手势修饰符**：单指平移和双指缩放归 Leaflet 独占。
 */
/** 标题栏「保存」注册位：保存依赖选中点/显示名——状态都在内容层无法直接上提，
 *  内容层把 enabled+动作写进来，标题栏只读它渲染（2026-10-29 用户：底部双钮取消，右上保存）。 */
class MapSaveHandle {
    var enabled by mutableStateOf(false)
    var action: (() -> Unit)? by mutableStateOf(null)
}

@Composable
private fun MapSheet(onDismiss: () -> Unit, save: MapSaveHandle, content: @Composable (MapSaveHandle) -> Unit) {
    // 把所在的 Dialog 窗口撑成全屏（详见本函数上方注释）
    val view = androidx.compose.ui.platform.LocalView.current
    // 顶栏返回 = 中性点击 → Tap
    val haptics = LocalHaptics.current
    SideEffect {
        val win = dialogWindowOf(view) ?: return@SideEffect
        win.setLayout(
            android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            android.view.ViewGroup.LayoutParams.MATCH_PARENT
        )
    }

    // navigationBars / statusBars：装饰视图若已处理，这里算出的是 0（不会重复扣）；
    // 没处理则正好补上——两种情况都安全，不靠"猜这台设备怎么处理 insets"
    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surface)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            // 顶部标题栏：返回 + 「选点」（2026-10-27 用户要求标题上提到栏内），
            // 内容区第一行只剩状态 + 图层/定位两个动作，不再重复标题
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDismiss() }) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "返回")
                }
                Text(
                    "选点",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.weight(1f))
                // 2026-10-29 用户：底部「确认选点/取消」取消，保存收进标题栏右上角
                TextButton(
                    enabled = save.enabled,
                    onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        save.action?.invoke()
                    }
                ) { Text("保存") }
            }
            // 地图区在 weight(1f) 里，**不挂任何手势修饰符**：
            // 单指平移与双指缩放归 Leaflet 独占——全屏化之后这条规矩仍然要守
            Box(Modifier.weight(1f).fillMaxWidth()) { content(save) }
        }
    }
    BackHandler { onDismiss() }
}

/**
 * 找到当前组合所在的 Dialog 窗口；找不到返回 null（就别改窗口，至少不崩）。
 *
 * ## 为什么绕这么一层
 *
 * 想直觉上 `view.window` 就能拿到所属窗口（`View.getWindow()`），但**实测解析不到**
 * （报 `Unresolved reference 'window'`）。绕过它最稳的办法是走 context：
 * Compose 的 `Dialog { }` 会把 Dialog 自身挂进 content 的 context（可能包一层
 * [android.content.ContextWrapper]），沿链往上找就能碰到。
 *
 * 包一层 6 次上限是防自引用的保险：万一某个 context 的 `baseContext` 指回自己，
 * 死循环比拿不到窗口更糟。
 */
private fun dialogWindowOf(view: android.view.View): android.view.Window? {
    var c: android.content.Context? = view.context
    var guard = 0
    while (c != null && guard++ < 6) {
        if (c is android.app.Dialog) return c.window
        val next = (c as? android.content.ContextWrapper)?.baseContext
        if (next === c) return null          // 自引用，直接放弃
        c = next
    }
    return null
}

private val tileClient by lazy {
    okhttp3.OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .build()
}

/** 走应用层代理的瓦片域名。白名单式匹配——别用"包含"以外的模糊判断，也别代理非瓦片请求。 */
private fun isTileHost(url: String): Boolean =
    url.contains("appmaptile") ||              // 高德 webrd / webst
        url.contains("arcgisonline.com") ||      // Esri World Imagery（卫星+标注）
        url.contains("tianditu.gov.cn")          // 天地图

/** 底图档位。名字直接显示在头部按钮上——用户要能一眼知道自己在看哪张图。 */
private enum class BaseLayer(val cn: String, val datumGcj: Boolean, val needsKey: Boolean = false) {
    // 只剩一档：高德卫星 + 高德路网注记（两层都是 GCJ-02，叠加不会错位）。
    //
    // 为什么不是天地图：实测同一点 z16/17/18 的注记瓦片，
    // 天地图 cva/cia 在 z17、z18 **直接返回空图**（213 字节、0% 内容），
    // 只有 z16 有零星路名；高德 style=8 三档都有 15%~24% 的注记内容
    // （场馆、校园、地铁站、道路名全覆盖）。选点是要看清「哪栋楼、哪个门」，
    // 用户一定会放大，所以注记必须在 z17+ 仍然存在。
    //
    // 代价：高德 style=8 是「路网线 + 地名」的叠加图，不是纯注记，
    // 旧路网线条会一起压上来（这也是当初删掉「高德路网」档的原因）。
    // 但国内只有它同时满足「中文地名 + 高倍仍有注记 + 与卫星图同一基准」。
    SAT_LABEL("卫星+地名", true);

    companion object {
        /** 目前只有一档；留这个入口是因为以后加档（比如纯矢量）不用改调用方。 */
        fun available(): List<BaseLayer> = values().toList()
    }
}

private fun buildMapHtml(startLat: Double, startLng: Double, startZoom: Int, layerIdx: Int, tdKey: String?): String {
    return """<!DOCTYPE html><html><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,user-scalable=no">
<link rel="stylesheet" href="leaflet/leaflet.css">
<script src="leaflet/leaflet.js"></script>
<style>html,body,#m{height:100%;margin:0;touch-action:none;overscroll-behavior:none}</style>
</head><body><div id="m"></div>
<script>
var mm=document.getElementById('m');mm.style.height='100%';mm.style.width='100%';
var map=L.map('m').setView([$startLat,$startLng],$startZoom);
// 高德街道瓦片（GCJ-02）：国内 CDN 快、能一直缩到 z18。
// 不用 Esri 世界街图的原因（2026-10-08 实测）：它 z16+ 每张都回 2521 字节的错误图
// 「Map data not yet available」，z16 起整片灰——定位默认就跳 z16，所以一开就是灰的。
// 页面里的坐标一律是 GCJ：进（setView/setLocate）由 Kotlin 做 WGS→GCJ，出（onPick）做 GCJ→WGS。
// 图层：标准矢量 + 卫星。必须给切换——高德 web 矢量瓦片（style=7/8/9 全试过）一律不画楼块，
// 城区打开是一片空地，看着像十几年前的图；只有卫星（webst 域名 + style=6）有建筑和操场细节，
// 选点要看清具体楼栋/入口时只能靠它（卫星无注记，故两版并存而不是直接换掉默认图层）。
// ── v14.2：底图扩到 5 档，专治用户提的「路网过期没参考价值、卫星图没有标注」──
// 2 卫星+标注：Esri World Imagery。卫星底图上**烧进了路网和地名注记**，免 key，
//   数据是 WGS84。这是"要建筑细节又要看得懂街名"的那一档，也是最该当默认的一档。
//   （此前没用 Esri 是因为它的**街图** z16+ 全是 2521 字节错误图；卫星图服务本身没问题。）
// 3 天地图：国家级现状矢量，中文注记新，需 tianditu_key，坐标 CGCS2000≈WGS84 无偏移。
// 4 OSM：免 key 兜底，标注有、样式朴素。
//
// ⚠ 基准（datum）是这次改动最容易埋雷的地方：高德瓦片是 GCJ-02，天地图/OSM/Esri 是
// WGS84，两套差 300~600 米。同一张 Leaflet 地图混用会让"看到的楼"和"存下来的点"错开
// 半条街。所以页面里所有进出坐标的换算都由 DATUM 决定：Kotlin 侧 setLocate 只在
// datum==='gcj' 时做 WGS→GCJ，onPick 回传也只在 datum==='gcj' 时做 GCJ→WGS。
var DATUM='gcj';
function applyDatum(d){DATUM=d;}
var satLayer=L.tileLayer('https://webst0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=6&x={x}&y={y}&z={z}',{maxZoom:18,subdomains:'1234',attribution:'高德卫星'});
// 注记层必须**后加**（同一 pane 里后加的画在上面），否则被卫星图盖住。
var labelLayer=L.tileLayer('https://wprd0{s}.is.autonavi.com/appmaptile?lang=zh_cn&size=1&scale=1&style=8&x={x}&y={y}&z={z}',{maxZoom:18,subdomains:'1234',attribution:'高德注记'});
var ALL=[{base:satLayer, labels:labelLayer}].filter(function(o){return !!o.base;});
function setLayer(idx){
  ALL.forEach(function(o){
    if(o.base&&map.hasLayer(o.base)) map.removeLayer(o.base);
    if(o.labels&&map.hasLayer(o.labels)) map.removeLayer(o.labels);
  });
  applyDatum('gcj');
  var o=ALL[idx]; if(!o) return;
  o.base.addTo(map);
  if(o.labels) o.labels.addTo(map);
}
// ⚠ 这一行**不能丢**：上一版改图层时把它一起删了，结果刚进页面根本没挂底图，
// 必须手动点一下「图层」才出图（用户反馈「第一次进去卫星图加载不出来」）。
setLayer($layerIdx);
// 首次进图空白（第二重保险）：WebView 里容器尺寸在 map 初始化那一刻可能还是 0，
// Leaflet 只按 0 尺寸算可视区域，之后尺寸变了也不会补请求。反复校正尺寸并触发重绘。
function fixSize(){
  var w=(window.innerWidth||mm.clientWidth||1), h=(window.innerHeight||mm.clientHeight||1);
  if(w<2||h<2) return;
  mm.style.width=w+'px'; mm.style.height=h+'px';
  map.invalidateSize();
}
window.addEventListener('resize',fixSize);
setTimeout(fixSize,60);setTimeout(fixSize,300);setTimeout(fixSize,900);setTimeout(fixSize,1800);
var marker=L.circleMarker([$startLat,$startLng],{radius:8,color:'#1677ff',fillColor:'#1677ff',fillOpacity:0.9,weight:2}).addTo(map);
// 诊断：瓦片成/败各记头几个（会经 WebChromeClient 落到 logcat，tag=MapPicker）
var _ok=0,_err=0;
map.on('tileload',function(){if(++_ok<=3)console.log('tileload #'+_ok);});
map.on('tileerror',function(e){if(++_err<=3)console.log('tileerror #'+_err+' code='+(e&&e.code));});
// 缩放那一下别顺手放个标记（双指缩放收尾常被当成 click）
var zoomGuard=false;
map.on('zoomstart',function(){zoomGuard=true;setTimeout(function(){zoomGuard=false;},400);});
map.on('click',function(e){
  if(zoomGuard) return;
  if(marker) map.removeLayer(marker);
  marker=L.circleMarker(e.latlng,{radius:8,color:'#e0453a',fillColor:'#e0453a',fillOpacity:0.9,weight:2}).addTo(map);
  // v14.2：把当前底图的基准一起回传。高德两档是 GCJ-02，Kotlin 侧才需要转成 WGS84；
  // 天地图/OSM/Esri 本身就是 WGS84，转了反而会偏 300~600 米。
  Android.onPick(e.latlng.lat,e.latlng.lng,DATUM);
});
// Kotlin 定位回来后调用：跳视野 + 摆蓝点。坐标是 GCJ（Kotlin 侧已从 WGS 转好），
// selected 也已在 Kotlin 侧设过——这里不再回传 onPick，免得再转一圈白白引入往返误差
function setLocate(lat,lng){
  map.setView([lat,lng],16);
  if(marker) map.removeLayer(marker);
  marker=L.circleMarker([lat,lng],{radius:8,color:'#1677ff',fillColor:'#1677ff',fillOpacity:0.9,weight:2}).addTo(map);
}
// 双指移动一律 preventDefault：不让 WebView/系统把它当滚动或原生缩放，
// 否则同一路手势会被外面的 bottom sheet 拉手接走（v14 反馈的冲突）
document.addEventListener('touchmove',function(e){if(e.touches.length>1){e.preventDefault();}},{passive:false});
</script></body></html>"""
}

/** 最近一次有缓存的位置（超 6 小时的旧点不算数，宁可用现取的） */
private fun cachedLocation(lm: LocationManager): Location? =
    runCatching {
        lm.allProviders
            .mapNotNull { p -> runCatching { lm.getLastKnownLocation(p) }.getOrNull() }
            .maxByOrNull { it.time }
    }.getOrNull()?.takeIf { System.currentTimeMillis() - it.time < 6 * 3600_000L }

/** 现取一个定位：系统融合定位 → 网络 → GPS，逐个试，单个最多等 6 秒 */
private suspend fun freshLocation(lm: LocationManager): Location? {
    val providers = listOf(
        LocationManager.FUSED_PROVIDER,     // API31+ 平台自带融合定位，网络+GNSS 一起算，最快
        LocationManager.NETWORK_PROVIDER,
        LocationManager.GPS_PROVIDER
    ).filter { runCatching { lm.isProviderEnabled(it) }.getOrDefault(false) }
    for (p in providers) {
        val loc = withTimeoutOrNull(6_000) { currentLocation(lm, p) }
        if (loc != null) return loc
    }
    return null
}

private suspend fun currentLocation(lm: LocationManager, provider: String): Location? =
    suspendCancellableCoroutine { cont ->
        val signal = CancellationSignal()
        val executor = java.util.concurrent.Executors.newSingleThreadExecutor()
        val finish: (Location?) -> Unit = { loc ->
            executor.shutdown()
            if (cont.isActive) cont.resume(loc)
        }
        try {
            // SAM 构造器显式写 java.util.function.Consumer：函数类型的变量不会自动转 Java SAM
            lm.getCurrentLocation(
                provider,
                signal,
                executor,
                java.util.function.Consumer { loc -> finish(loc) }
            )
        } catch (e: SecurityException) {
            finish(null)
        }
        cont.invokeOnCancellation { signal.cancel(); executor.shutdown() }
    }

/**
 * v12：真实地图选点——WebView+Leaflet+高德街道瓦片（GCJ-02）。
 * 瓦片请求经 shouldInterceptRequest 用应用内 OkHttp 代理取回（部分系统 WebView 层
 * 会被网络过滤返回空白图，应用层取字节后经 WebResourceResponse 喂回页面绕开该问题）。
 * 标记/点击坐标经 GCJ→WGS 转换后回传（应用存储仍为 WGS84）。国内直连、无 key。
 *
 * v14：补真实定位（此前没有定位逻辑，没历史点就直接落在北京，还被「确认选点」存进去）；
 * 手势上关掉 WebView 自带缩放 + 页面 touch-action:none，双指缩放整条留给 Leaflet，
 * 不再被外面 bottom sheet 的拉手接走。
 * 底图从 Esri 换回高德：Esri 街道图 z16+ 全是错误瓦片（定位默认跳 z16 → 开出来一片灰），
 * 高德能到 z18 且国内直连快；进出坐标按上文做 GCJ↔WGS，存储口径不变（仍是 WGS84）。
 *
 * v42 地名框语义（2026-10-23 用户要求）：框 = **显示地名**（与编辑器同一个字段），
 * 选点/逆地理自动填入绑定名，手改的是显示名、不再改绑定；绑定名与坐标在框下一行小字展示。
 * onPick 回传 (坐标+绑定名的点, 显示名) —— 调用方 place = 显示名、placePoi = 绑定名。
 */
@Composable
fun MapPickerSheet(
    initial: GeoPoint?,
    /** 地名框初始值 = 编辑器当前显示地名；为空时用 [initial] 的绑定名兜底 */
    initialName: String = "",
    onDismiss: () -> Unit,
    onPick: (GeoPoint, String) -> Unit
) {
    val context = LocalContext.current

    // 没有历史点时**不再**默认北京：北京那个点会被「确认选点」原样存进去（v14 实测踩到）
    var selected by remember { mutableStateOf(initial) }
    // 地名框 = **显示地名**（编辑器 place 同款字段），不再是绑定名：
    // 初始显示编辑器带进来的显示名（为空时用绑定名兜底——顺手把灰占位变真值）；
    // 选新点/逆地理解析成功后自动填入绑定名；用户手改过（nameEdited）就尊重手改。
    // 绑定名只跟数据走（逆地理/别名/候选 chips/搜索结果），在框下一行小字展示。
    var placeName by remember { mutableStateOf(initialName.ifBlank { initial?.label.orEmpty() }) }
    var nameEdited by remember { mutableStateOf(false) }
    /**
     * 最近一次**自动解析**出来的绑定名（别名 / 瓦片楼号 / 逆地理）。
     * 确认选点时只有「绑定名 ≠ autoLabel」才写别名库——用户没纠正过就说明这是自动猜的，
     * 把猜错的名字记成别名会让同一坐标永远回放错误答案（2026-10-25「点 楼E 依旧绑成 楼F」）。
     */
    var autoLabel by remember { mutableStateOf("") }
    /** 逆地理带回来的贴邻候选（高德 pois 按距离序）；摆成 chips 供一键纠正 */
    var poiCandidates by remember { mutableStateOf<List<String>>(emptyList()) }
    /**
     * 该绑定地点**已保存过**的显示地名（2026-10-27 下拉菜单用）：点开「显示地名」
     * 直接把用过的名字列出来挑，省得每次重打楼层房间号。
     */
    var knownNames by remember { mutableStateOf<List<String>>(emptyList()) }
    var knownCategory by remember { mutableStateOf<CategoryEntity?>(null) }
    var knownRefresh by remember { mutableStateOf(0) }
    /**
     * 绑定名兜底（2026-10-29）：没选到点、或逆地理没结果时，点标签为空 —— 这时显示地名列表
     * 按 [selected]?.label 查分类永远查不到，表现就是"加了显示地名看着没保存，再添加又另起一栋"。
     * 用户在新建弹窗里写入的第一个名字回填到这里当绑定名（与 ensureDisplayName 的 ifBlank 同口径）。
     */
    var fallbackBound by remember { mutableStateOf("") }
    LaunchedEffect(selected?.label, fallbackBound, knownRefresh) {
        val bound = cleanLabel(selected?.label).ifBlank { fallbackBound }
        val cat = if (bound.isBlank()) null else runCatching {
            Graph.plan.categories(1).firstOrNull { it.name == bound }
        }.getOrNull()
        knownCategory = cat
        knownNames = cat?.let { Graph.plan.displayNamesOf(it) } ?: emptyList()
    }
    // 「回到起点」的起点 = 上次保存的点；没有就用本次定位到的点
    var origin by remember { mutableStateOf(initial) }
    // 标题栏「保存」注册位（2026-10-29 底部双钮取消）
    val save = remember { MapSaveHandle() }
    var locating by remember { mutableStateOf(false) }
    var locateReq by remember { mutableIntStateOf(if (initial == null) 1 else 0) }
    var locMsg by remember {
        mutableStateOf(
            if (initial != null) "已按上次保存的点显示 · 右上「定位」可跳到当前位置"
            else "定位中…"
        )
    }
    var webRef by remember { mutableStateOf<android.webkit.WebView?>(null) }
    // v14.2：底图 5 档循环切换（没配天地图 key 时那档不出现）。
    // 默认落在「卫星+标注」：用户明说高德路网过期没参考价值、卫星又没街名注记，
    // Esri World Imagery 是唯一同时给到建筑细节和地名的免key 档。
    val layers = remember { BaseLayer.available() }
    var layerIdx by remember { mutableStateOf(layers.indexOf(BaseLayer.SAT_LABEL).coerceAtLeast(0)) }
    fun cycleLayer() {
        layerIdx = (layerIdx + 1) % layers.size
        webRef?.evaluateJavascript("if(typeof setLayer==='function')setLayer($layerIdx)", null)
    }
    // 定位比页面加载快时先记下来，onPageFinished 再补一次 setLocate
    var pendingLoc by remember { mutableStateOf<GeoPoint?>(null) }

    // ---- v14.1：POI 搜索；v14.2 扩成多源合并 ----
    // v14.1 只走高德，用户反馈「有些地方还是搜不到」（高德覆盖有缺口）。
    // 现在一次搜索并发打所有配了key 的源，结果合并去重、每条标来源；
    // 没配 key 的源不参与（不是错误），任一源失败/超时的原因逐条摆出来。
    var query by remember { mutableStateOf("") }
    var poiResults by remember { mutableStateOf<List<PlaceSearch.Hit>?>(null) }
    var poiBusy by remember { mutableStateOf(false) }
    /** 搜索错误/空结果提示，直接贴在结果区里（失败必须让用户看见，别静默吞掉） */
    var poiMsg by remember { mutableStateOf<String?>(null) }
    /** 逐源失败原因（形如「腾讯：配额超限」）。有内容时在结果区顶部一行行摆出来。 */
    var poiProblems by remember { mutableStateOf<List<String>>(emptyList()) }
    /** 用户是否已经搜过（有搜索时才铺结果区占位，避免一开面板就多出一块空白） */
    var poiSearched by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    // 取控制器要用 @Composable 的读法，先在组合期取好，doSearch() 里才能用
    val kb = androidx.compose.ui.platform.LocalSoftwareKeyboardController.current

    fun doSearch() {
        val kw = query.trim()
        if (kw.isEmpty() || poiBusy) return
        poiBusy = true
        poiMsg = null
        poiProblems = emptyList()
        // 搜索时键盘先收起来：结果列表要占地方，键盘挡着就白搜了
        kb?.hide()
        scope.launch {
            // 参考点=当前选点，让结果按距离排（没有选点就不传 location）
            val near = selected
            val out = PlaceSearch.search(kw, near?.lat, near?.lng)
            poiResults = out.hits
            poiProblems = out.problems
            poiMsg = if (out.hits.isEmpty()) {
                val asked = out.asked.joinToString("、") { it.cn }
                if (asked.isBlank()) "没配任何地点检索 key，搜索不可用（见 local.properties）"
                else "已查 $asked 都没搜到「$kw」，换个说法或直接在地图上点"
            } else null
            poiSearched = true
            poiBusy = false
        }
    }

    // 没历史点先给个中国视野（别假装在北京），定位回来立刻跳过去
    val startLat = initial?.lat ?: 35.86166
    val startLng = initial?.lng ?: 104.195397
    val startZoom = if (initial != null) 16 else 5

    // 逆地理补地名：只有地图上盲点/定位拿的点才需要（搜索结果自带名字，不重复请求）。
    // 失败/无 key 时安静跳过——地名只是锦上添花，坐标本身已经选好了，不该因此报错。
    fun applyLabel(p: GeoPoint) {
        if (p.label.isNotBlank()) return
        scope.launch {
            // 2026-10-25 修「点 楼E 依旧绑成 楼F」：
            // 瓦片注记是**唯一认得 楼E/楼B 这类新楼**的来源（高德 POI 库没有这批楼，
            // 「最近具名 POI」会稳稳抓到贴邻的 楼F）。顺序改为：
            //   ① pin 正下方（≤50m）的瓦片楼号直接当绑定名；
            //   ② 别名只在与该楼号**同一栋**时才压过它（别名可能带房号 楼B-302）；
            //      名字对不上说明是别的楼留下的旧记忆，不许劫持新落点；
            //   ③ 都没有才退回逆地理的「最近具名 POI」。
            val tileHits = if (layers[layerIdx].datumGcj) {
                val g = GeoCodec.wgsToGcj(p.lat, p.lng)
                MapTileOcr.nearby(g.first, g.second, 18)
            } else emptyList()
            val tileNear = tileHits.firstOrNull()?.takeIf { it.distanceM <= 50.0 }
            val alias = PlaceAliasStore.nearest(p.lat, p.lng)
            val aliasUsable = alias != null && (
                tileNear == null ||
                    alias.name.contains(tileNear.name) ||
                    tileNear.name.contains(alias.name)
                )
            // 自愈：别名与 pin 正下方的楼号对不上 = 别的楼的旧记忆，删掉它，
            // 否则这次没用上、下次照样劫持（用户实测「点 楼E 依旧绑成 楼F」）。
            if (!aliasUsable && alias != null && tileNear != null) {
                PlaceAliasStore.forgetNear(p.lat, p.lng)
            }
            val auto = cleanLabel(
                when {
                    aliasUsable -> alias!!.name
                    tileNear != null -> tileNear.name
                    else -> ""
                }
            )

            fun stillShowing() = selected?.lat == p.lat && selected?.lng == p.lng
            fun refreshCandidates(extra: List<String>) {
                if (!stillShowing()) return
                val cur = selected?.label.orEmpty()
                poiCandidates = (tileHits.filter { it.distanceM <= 120.0 }.map { it.name } + extra)
                    .filter { it.isNotBlank() && it != cur }
                    .distinct()
                    .take(8)
            }

            if (auto.isNotBlank() && stillShowing()) {
                selected = p.copy(label = auto)
                if (!nameEdited) placeName = auto
                autoLabel = auto
                refreshCandidates(emptyList())
                // 逆地理继续在后台取候选（用户发现楼号不对时可一键换），但不覆盖自动名
                PlaceSearch.reverse(p.lat, p.lng).onSuccess { hit ->
                    refreshCandidates(listOf(hit.best) + hit.candidates)
                }
                return@launch
            }

            // v14.2：按源优先级取第一个成功的（高德→腾讯→百度→天地图）。
            // 地址只是锦上添花，失败仍然安静跳过——坐标已经选好了，不该因此报错。
            PlaceSearch.reverse(p.lat, p.lng).onSuccess { hit ->
                // 只回填仍在显示的这个点（异步期间用户可能又点了别的）
                if (!stillShowing()) return@onSuccess
                // 空壳名字（"[]"）等同没有地址，别拿它当绑定名/显示名
                val best = cleanLabel(hit.best)
                if (best.isBlank()) return@onSuccess
                // 不再拼「（高德）」这类来源后缀：用户要的是地名本身（楼C栋），
                // 来源对用户是噪音（2026-10-20 反馈）
                selected = p.copy(label = best)
                if (!nameEdited) placeName = best
                autoLabel = best
                refreshCandidates(listOf(best) + hit.candidates)
            }
        }
    }

    /**
     * 落点（WGS84）→ 页面坐标。
     *
     * v14.2 起**按当前底图的基准**决定要不要转：高德两档是 GCJ-02 需要转，
     * 天地图/OSM/Esri 本身就是 WGS84，转了反而偏 300~600 米。
     */
    fun toPageLatLng(lat: Double, lng: Double): Pair<Double, Double> =
        if (layers[layerIdx].datumGcj) GeoCodec.wgsToGcj(lat, lng) else (lat to lng)

    fun applyPoint(p: GeoPoint, pinOrigin: Boolean = false) {
        selected = p
        // 新落点 = 新绑定：框里的显示名跟着重置（上一段文字属于上一个点），
        // 有名字就先填（搜索结果自带名），盲点等 applyLabel 逆地理回来再填
        nameEdited = false
        if (cleanLabel(p.label).isNotBlank()) placeName = cleanLabel(p.label)
        // 起点只在「没有历史点」时由定位认领一次；有了 initial（上次保存的点）就以它为准，
        // 用户后来按「定位」重新定位也不该把起点拽走。
        if (pinOrigin && origin == null) origin = p
        pendingLoc = p
        val (pLat, pLng) = toPageLatLng(p.lat, p.lng)
        webRef?.evaluateJavascript("if(typeof setLocate==='function')setLocate($pLat,$pLng)", null)
        applyLabel(p)
    }

    /**
     * 「回到起点」= 回到打开这个面板时所在的那个点。
     *
     * 刻意**不复用 [applyPoint]**：那条路会把 p 当成新落点顺带处理 origin/pendingLoc，
     * 语义上「复位」和「选了一个新点」是两件事，混在一起迟早出下一次「点了没反应」。
     */
    fun restoreOrigin() {
        val o = origin ?: return
        selected = o
        pendingLoc = o
        val (pLat, pLng) = toPageLatLng(o.lat, o.lng)
        webRef?.evaluateJavascript("if(typeof setLocate==='function')setLocate($pLat,$pLng)", null)
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.any { it }) {
            locateReq++ // 授权到手 → 回到下面的 effect 真去定位
        } else {
            locating = false
            locMsg = "未授权定位 · 点地图手动选点（再点一次「定位」可重新授权）"
        }
    }

    LaunchedEffect(locateReq) {
        if (locateReq == 0) return@LaunchedEffect
        val fine = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        val coarse = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) {
            locating = false
            locMsg = "定位需要位置权限"
            permLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return@LaunchedEffect
        }
        locating = true
        locMsg = "定位中…"
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        // ① 缓存秒回：先把地图挪到人所在地（这一步就足以看出不是北京了）。
        //    缓存不认领起点——它可能偏几十米，认了会让「回到起点」回到一个略偏的点。
        val cached = withContext(Dispatchers.IO) { cachedLocation(lm) }
        if (cached != null) applyPoint(GeoPoint(cached.latitude, cached.longitude))
        // ② 再等一个新鲜定位精修；这一步的结果才是准的，由它认领起点
        val fresh = freshLocation(lm)
        locating = false
        when {
            fresh != null -> {
                applyPoint(GeoPoint(fresh.latitude, fresh.longitude), pinOrigin = true)
                locMsg = "已定位到当前位置 · 精度约 ${fresh.accuracy.coerceAtLeast(1f).toInt()} m"
            }
            cached != null -> locMsg = "用的是缓存位置 · 点「定位」可刷新"
            else -> locMsg = "未获取到定位 · 点地图手动选点，或点「定位」重试"
        }
    }

    // ── 独立全屏窗口 ──
    //
    // 原来由 4 个调用方各自 `Dialog { MapPickerSheet(...) }` 包一层，MapSheet 再靠
    // 「沿 context 链找 Window 然后 setLayout(MATCH_PARENT)」把它撑开。实测**撑不满**：
    // 对话框主题自带窗口内边距与背景，四周留出一圈（截图看就是一张浮起来的卡片），
    // 看着不像一个独立界面。
    // 现在自己开窗口：`usePlatformDefaultWidth = false` 从**属性**层面就拿掉宽度限制，
    // `decorFitsSystemWindows = false` 让内容自己吃 insets（MapSheet 里已经按
    // statusBars/navigationBars 各自补过，不会重复扣）。
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
        MapSheet(onDismiss = onDismiss, save = save) { save ->
        // ── 拖动区：只有拉手 + 这一段标题行 ──
        // 地图区上方刻意不挂任何 pointerInput/verticalScroll：手势冲突的根因是
        // ModalBottomSheet 的 AnchoredDraggable 铺在整个 sheet 上（见 MapSheet 的说明），
        // 把拖拽收进头部之后，地图里的单指拖动和双指缩放就都是 Leaflet 独占的了。
        Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            // ── 顶部工具行：状态 + 图层 + 定位（标题已在 MapSheet 的标题栏里）──
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text(
                    if (locating) "定位中…" else locMsg,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (locating) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (layers.size > 1) {
                    TextButton(
                        onClick = { cycleLayer() },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 10.dp, vertical = 4.dp
                        )
                    ) { Text(layers[layerIdx].cn, style = MaterialTheme.typography.labelMedium) }
                }
                TextButton(
                    onClick = { if (!locating) locateReq++ },
                    enabled = !locating,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 4.dp
                    )
                ) {
                    Text(
                        if (locating) "定位中" else "定位",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            // ---- POI 搜索：一整行输入 + 行尾搜索图标（原来是「窄输入框 + 大按钮」） ----
            OutlinedTextField(
                value = query,
                onValueChange = { query = it; poiMsg = null },
                placeholder = { Text("搜地点 / POI", maxLines = 1) },
                singleLine = true,
                enabled = !poiBusy,
                shape = RoundedCornerShape(14.dp),
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.None,
                    imeAction = ImeAction.Search
                ),
                keyboardActions = KeyboardActions(onSearch = { doSearch() }),
                trailingIcon = {
                    androidx.compose.material3.IconButton(
                        onClick = { doSearch() },
                        enabled = query.isNotBlank() && !poiBusy
                    ) {
                        Icon(
                            Icons.Rounded.Search,
                            contentDescription = "搜索",
                            tint = if (query.isNotBlank() && !poiBusy)
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth()
            )
            // 搜索结果：点一条即把地图挪过去并标记。空结果/失败都摆出来（poiMsg），不让它静默。
            if (poiSearched) {
                val res = poiResults
                // v14.2：逐源失败原因顶在结果区最上面。用户搜不到东西时第一反应是
                // "是不是它坏了" —— 得让他看见「腾讯：配额超限」而不是"没搜到"三个字。
                poiProblems.forEach { pb ->
                    Text(
                        pb,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                if (res.isNullOrEmpty()) {
                    if (poiMsg != null && !poiBusy) {
                        Text(
                            poiMsg!!,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .verticalScroll(rememberScrollState())
                    ) {
                        res.forEach { p ->
                            Column(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        // POI 自带名字，直接当 label，applyPoint 里的逆地理就会跳过
                                        applyPoint(GeoPoint(p.lat, p.lng, p.name))
                                        // 搜索结果点了**直接确认返回**——
                                        // 用户搜的就是要选的地方，漏按「确认选点」
                                        // 就等于没选（期末考 place 为空）
                                        onPick(GeoPoint(p.lat, p.lng, p.name), p.name)
                                        query = p.name
                                        poiResults = null
                                        poiSearched = false
                                        poiMsg = null
                                        poiProblems = emptyList()
                                        poiCandidates = emptyList()
                                    }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        p.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f, fill = false),
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    // 来源标注：一条被多家同时命中的显示成「高德+腾讯」，
                                    // 这本身就是信息——两家都认为它在这儿，比一家孤证可信
                                    Text(
                                        p.sources.joinToString("+") { it.cn },
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
                                    )
                                }
                                if (p.address.isNotBlank()) {
                                    Text(
                                        p.address,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            HorizontalDivider()
                        }
                    }
                }
            }
            // 地图吃掉所有剩余高度（原来固定 340dp，底下挤出一大块空白）
            // 2026-10-25 用户「给瓦片套一个好看一点的框」：圆角 + 1dp 描边 + 像素底，
            // 让卫星瓦片看起来是一张卡片而不是一块贴图。
            val mapShape = RoundedCornerShape(18.dp)
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clip(mapShape)
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .border(
                        1.dp,
                        MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.7f),
                        mapShape
                    )
            ) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        webRef = this
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = true
                        settings.domStorageEnabled = true
                        // 缩放全交给 Leaflet（页面里有 +/− 按钮和双指）；
                        // WebView 自带的双指缩放必须关，否则它和 sheet 拉手抢同一套手势
                        settings.setSupportZoom(false)
                        settings.builtInZoomControls = false
                        settings.displayZoomControls = false
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        webChromeClient = object : android.webkit.WebChromeClient() {
                            override fun onConsoleMessage(message: android.webkit.ConsoleMessage): Boolean {
                                android.util.Log.e("MapPicker", "js:${message.message()} @${message.lineNumber()}")
                                return true
                            }
                        }
                        webViewClient = object : android.webkit.WebViewClient() {
                            // 应用内代理瓦片：绕开系统 WebView 层的空白图过滤。
                            // v14.2：域名从只有 appmaptile 扩到 Esri / OSM / 天地图——
                            // 它们同样有"某些 ROM 的 WebView 网络层返回空白图"的老问题，
                            // 走应用层取字节再喂回页面最省事，不用逐个 ROM 试。
                            override fun shouldInterceptRequest(
                                view: android.webkit.WebView,
                                request: android.webkit.WebResourceRequest
                            ): android.webkit.WebResourceResponse? {
                                val url = request.url.toString()
                                if (!isTileHost(url)) return null
                                try {
                                    val req = okhttp3.Request.Builder()
                                        .url(url)
                                        // UA 必须是**应用标识**而不是伪装浏览器：天地图的服务端 key 会拒绝浏览器类 UA
                                        // （实测 403「Key权限类型为服务器端，请使用服务器端访问」），瓦片全变灰；
                                        // 高德/Esri 用应用 UA 一样正常。OSM 的使用政策同样要求可识别 UA。
                                        .header("User-Agent", "Planability/1.0 (Android)")
                                        .build()
                                    val resp = tileClient.newCall(req).execute()
                                    val bytes = resp.body?.bytes()
                                    // 只留失败日志：瓦片成功每屏几十条，刷 logcat 没意义
                                    if (!resp.isSuccessful || bytes == null) {
                                        android.util.Log.e("MapPicker", "proxy ${resp.code} $url")
                                        return null
                                    }
                                    // Esri World Imagery 回的是 JPEG，别硬标 png——
                                    // 标错类型时部分 WebView 会解码失败，表现为"整块空白但无报错"
                                    val mime = resp.header("Content-Type")
                                        ?.substringBefore(';')?.trim()
                                        ?.ifBlank { null }
                                        ?: if (url.contains("arcgisonline")) "image/jpeg" else "image/png"
                                    resp.close()
                                    return android.webkit.WebResourceResponse(
                                        mime, null, java.io.ByteArrayInputStream(bytes)
                                    )
                                } catch (e: Exception) {
                                    android.util.Log.e(
                                        "MapPicker",
                                        "proxy FAIL $url : ${e.javaClass.simpleName} ${e.message}"
                                    )
                                    return null
                                }
                            }

                            override fun onPageFinished(view: android.webkit.WebView, url: String) {
                                view.evaluateJavascript(
                                    "setTimeout(function(){if(typeof map!=='undefined')map.invalidateSize();},200);" +
                                        "setTimeout(function(){if(typeof map!=='undefined')map.invalidateSize();},600);", null
                                )
                                // 定位比页面先回来的话，等页面就绪再把视野挪过去（按当前底图基准换算）
                                pendingLoc?.let { p ->
                                    val (pLat, pLng) = toPageLatLng(p.lat, p.lng)
                                    view.evaluateJavascript(
                                        "if(typeof setLocate==='function')setLocate($pLat,$pLng)", null
                                    )
                                }
                            }
                        }
                        addJavascriptInterface(object : Any() {
                            @android.webkit.JavascriptInterface
                            fun onPick(lat: Double, lng: Double, datum: String) {
                                // 页面把当前底图的基准一起回传：高德两档是 GCJ-02，要转成 WGS84 再存；
                                // 天地图/OSM/Esri 本身就是 WGS84，**转了反而偏 300~600 米**。
                                // 标记/视野页面自己已经摆好了，这里不用再 setLocate（重复会闪一下），
                                // 但**要补地名**——盲点一个点，用户没法确认这是不是他要的地方。
                                val (wLat, wLng) =
                                    if (datum == "gcj") GeoCodec.gcjToWgs(lat, lng) else (lat to lng)
                                val p = GeoPoint(wLat, wLng)
                                val handler = android.os.Handler(android.os.Looper.getMainLooper())
                                handler.post {
                                    selected = p
                                    // 新点还没名字，上一轮的候选属于旧点，不清会串
                                    poiCandidates = emptyList()
                                    nameEdited = false
                                    applyLabel(p)
                                }
                            }
                        }, "Android")
                        // 起始视野与初始标记按当前底图基准换算
                        val (gStartLat, gStartLng) = toPageLatLng(startLat, startLng)
                        loadDataWithBaseURL(
                            "file:///android_asset/",
                            buildMapHtml(gStartLat, gStartLng, startZoom, layerIdx, io.github.m0use001.planability.core.PlaceHttp.tiandituKey()),
                            "text/html", null, null
                        )
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            }
            Spacer(Modifier.height(8.dp))
            // ── ① 绑定地名 + 坐标（紧贴地图下方的一整块）──
            // 2026-10-27 用户：「地图下方显示一个完整绑定地名+坐标」——名字、坐标同卡展示
            // （原来名字大字、坐标另起一行小字，中间还隔着候选 chips 与输入框）。
            // 逆地理猜错楼栋的自救候选 chips 也收进这张卡：它们改的就是绑定名。
            val boundPreview = cleanLabel(selected?.label).ifBlank { fallbackBound }
            BoundInfoCard(
                boundName = boundPreview,
                lat = selected?.lat,
                lng = selected?.lng,
                candidates = poiCandidates,
                canRestore = origin != null,
                onPickCandidate = { c ->
                    // chips 改的是**绑定名**（坐标不动）；框里只有用户还没手改显示名时才跟着换
                    selected = selected?.copy(label = c)
                    if (!nameEdited) placeName = c
                },
                onRestore = { restoreOrigin() }
            )
            Spacer(Modifier.height(10.dp))
            // ── ② 显示地名：编号列表 + 编辑弹窗 + 圆形加号新建 ──
            // 2026-10-28 用户草图（第五版）：绑定地名底下是编号列表（1. A1 / 2. A3），
            // 点条目编辑显示地名（弹窗里问是否填入绑定地名），最底下是加号新建。
            DisplayNameSection(
                value = placeName,
                known = knownNames,
                boundName = boundPreview,
                categoryId = knownCategory?.id,
                lat = selected?.lat,
                lng = selected?.lng,
                onValueChange = { v ->
                    placeName = v
                    nameEdited = true
                },
                onChanged = { knownRefresh++ },
                // 绑定名为空时把这次写的名字回填成绑定名：列表才能按名字找到分类刷新出来，
                // 后续再添加也挂到同一栋楼，而不是另建一条分类（"再添加无法保存"的根因）
                onBoundFallback = { name ->
                    if (cleanLabel(selected?.label).isBlank()) fallbackBound = name
                }
            )

            // ── 标题栏「保存」注册（2026-10-29 用户：底部「取消/确认选点」移除，右上角保存；
            // 退出走左上返回/系统返回，不带保存语义）──
            fun confirmPick() {
                val display = placeName.trim()
                val sel = selected
                if (sel == null) {
                    // 2026-10-29 修「加了显示地名就无法保存」：室内定位不到/没点地图时，
                    // 只要写入了显示地名也允许保存 —— 按"未绑坐标的地点"口径（lat/lng=0，
                    // 与既有「未绑坐标」分类一致）。原来保存键恒禁用，用户以为写不进去。
                    if (display.isNotBlank()) onPick(GeoPoint(0.0, 0.0, display), display)
                    return
                }
                // 本地别名记**绑定名**（chips/逆地理确认过的那个）：
                // 显示名（可带楼层号）存成别名会让同楼下次被「楼B-302」这种带房号的名字顶替，
                // 绑定名为空时才退回显示名。2026-10-25：**只学用户亲手纠正过**的名字
                // （绑定名 ≠ 自动解析结果），自动猜的也学会把 楼E 永久绑成 楼F（v14 实测）。
                val aliasName = cleanLabel(sel.label).ifBlank { placeName.trim() }
                if (aliasName.isNotBlank() && sel.label.isNotBlank() && sel.label != autoLabel) {
                    scope.launch { PlaceAliasStore.remember(aliasName, sel.lat, sel.lng) }
                }
                onPick(sel, display.ifBlank { sel.label })
            }
            // 用 SideEffect 而不是 LaunchedEffect(selected)：
            // ① enabled 还取决于「显示地名是否非空」（没选到点时靠它兜底保存）；
            // ② action 每次组合都要指向最新的 confirmPick —— LaunchedEffect 只在 selected
            //    变化时注册一次，闭包里的状态读取在极端时序下会落后一帧。
            androidx.compose.runtime.SideEffect {
                save.enabled = selected != null || placeName.isNotBlank()
                save.action = { confirmPick() }
            }
            Spacer(Modifier.height(14.dp))
        }
    }
}
}

/**
 * 绑定地名 + 坐标卡（地图正下方的一整块）。
 *
 * 2026-10-27 用户口径：「地图下方显示一个**完整**绑定地名+坐标」——名字与坐标必须在
 * 同一块里一次读全（旧版名字是大字、坐标在输入框下面一行小字，中间还隔着候选 chips，
 * 眼睛要跳两处）。居左的圆点用主色表示"已绑坐标"，未选点时为中性 outline。
 * 「是不是：」候选 chips 跟着绑定名走，收进同一张卡（它们改的就是绑定名，坐标不动）。
 */
@Composable
private fun BoundInfoCard(
    boundName: String,
    lat: Double?,
    lng: Double?,
    candidates: List<String>,
    canRestore: Boolean,
    onPickCandidate: (String) -> Unit,
    onRestore: () -> Unit
) {
    val haptics = LocalHaptics.current
    val hasPoint = lat != null && lng != null
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 14.dp, vertical = 10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(
                        if (hasPoint) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.outline
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    boundName.ifBlank { if (hasPoint) "未命名地点" else "还没选点" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (boundName.isBlank()) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    if (hasPoint) "坐标  %.5f, %.5f".format(lat, lng)
                    else "点地图、搜地点或等定位",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (canRestore) {
                TextButton(
                    onClick = onRestore,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 10.dp, vertical = 4.dp
                    )
                ) { Text("回到起点", style = MaterialTheme.typography.labelMedium) }
            }
        }
        if (candidates.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
            ) {
                Text(
                    "是不是：",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                candidates.forEach { c ->
                    FilterChip(
                        colors = filterChipColors(),
                        selected = false,
                        onClick = {
                            haptics.perform(HapticEngine.Kind.Select)
                            onPickCandidate(c)
                        },
                        label = { Text(c) },
                        modifier = Modifier.padding(start = 4.dp)
                    )
                }
            }
        }
    }
}

/**
 * 显示地名（2026-10-28 用户草图第五版：**编号列表 + 编辑弹窗 + 圆形加号新建**）。
 *
 * 演进：第一版下拉菜单行 → 第二版常显 chips + 输入框 → 本版按草图重排：
 * 绑定地名卡底下是**编号列表**（1. A1 / 2. A3，当前值高亮+✓），点条目进编辑弹窗
 * （弹窗里**问是否填入绑定地名**——一键把绑定名填进输入框，方便再补楼层房号），
 * 最底下**圆形加号**新建。落库走两级模型三件套：新建 [io.github.m0use001.planability.data.PlanRepo.ensureDisplayName]、
 * 改名 [io.github.m0use001.planability.data.PlanRepo.renameDisplayName]（同名自动合并）、
 * 删除 [io.github.m0use001.planability.data.PlanRepo.removeDisplayName]；
 * 保存后该名即为当前显示地名（onValueChange → placeName）。
 */
@Composable
private fun DisplayNameSection(
    /** 当前显示地名：仅用于删除兜底（删掉当前名时回退到绑定名），不再做行高亮 */
    value: String,
    known: List<String>,
    boundName: String,
    categoryId: Long?,
    lat: Double?,
    lng: Double?,
    onValueChange: (String) -> Unit,
    onChanged: () -> Unit,
    /** 绑定名为空（逆地理无结果 / 没选到点）时，把刚保存的显示名回填成绑定名（2026-10-29） */
    onBoundFallback: (String) -> Unit = {}
) {
    val haptics = LocalHaptics.current
    // 落库协程的宿主：一律 Graph.appScope（应用级）——挂在弹窗/面板自己的 scope 上时，
    // 弹窗 dismiss 或面板关闭把组合销毁、协程连带取消，Room 事务没提交就丢（「无法保存」）。
    // null = 关闭；"" = 新建；非空 = 编辑该名
    var editing by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "显示地名",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, bottom = 4.dp)
        )
        known.forEachIndexed { i, n ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 6.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerLow)
                    .clickable {
                        haptics.perform(HapticEngine.Kind.Select)
                        editing = n
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "${i + 1}.",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(24.dp)
                )
                Text(
                    n,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        if (known.isEmpty()) {
            Text(
                "还没有显示地名，点下面的 + 新建",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp)
            )
        }
        // 圆形加号：新建（草图最底下的 +）
        Box(Modifier.fillMaxWidth().padding(top = 2.dp), contentAlignment = Alignment.Center) {
            FilledIconButton(
                onClick = {
                    haptics.perform(HapticEngine.Kind.Select)
                    editing = ""
                },
                shape = CircleShape,
                modifier = Modifier.size(44.dp)
            ) {
                Icon(Icons.Rounded.Add, contentDescription = "新建显示地名")
            }
        }
    }

    editing?.let { old ->
        DisplayNameEditDialog(
            boundName = boundName,
            initial = old,
            isEdit = old.isNotEmpty(),
            current = value,
            categoryId = categoryId,
            lat = lat,
            lng = lng,
            onDismiss = { editing = null },
            onValueChange = onValueChange,
            onChanged = onChanged,
            onBoundFallback = onBoundFallback
        )
    }
}

/**
 * 显示地名编辑弹窗：输入框 + 「是否填入绑定地名？」（[填入] 一键把绑定名填进输入框，
 * 再补楼层房号用）；编辑态带删除（[io.github.m0use001.planability.data.PlanRepo.removeDisplayName]）。
 * 确定后该名落库并成为当前显示地名。
 */
@Composable
private fun DisplayNameEditDialog(
    boundName: String,
    initial: String,
    isEdit: Boolean,
    current: String,
    categoryId: Long?,
    lat: Double?,
    lng: Double?,
    onDismiss: () -> Unit,
    onValueChange: (String) -> Unit,
    onChanged: () -> Unit,
    onBoundFallback: (String) -> Unit = {}
) {
    val haptics = LocalHaptics.current
    var text by remember(initial) { mutableStateOf(initial) }

    fun save() {
        val v = text.trim()
        if (v.isEmpty()) return
        // 根修（2026-10-29 用户「加了显示地名点保存就丢」）：写库协程原来挂在**选点面板**的
        // scope 上 —— 弹窗确定后紧接着点标题栏「保存」，整个面板移出组合、scope 连带取消，
        // Room 事务没提交就被掐死（取消被 runCatching 吞掉，连 Toast 都没有）。
        // 改挂 Graph.appScope（应用级），面板关不关都写完；UI 反馈再回主线程。
        io.github.m0use001.planability.data.Graph.appScope.launch {
            val err = runCatching {
                // 分类 id 解析兜底（2026-10-29）：绑定名与分类对不上（空绑定/改过名的老数据）时，
                // 编辑态原来会掉进 ensureDisplayName 新建一条分类 —— 表现为"改名没生效、楼变多了"。
                // 这里按绑定名现查一次，查到就改名。
                val cid = categoryId ?: Graph.plan.categories(1)
                    .firstOrNull { it.name == boundName.trim() }?.id
                if (isEdit && cid != null) {
                    Graph.plan.renameDisplayName(cid, initial, v)
                } else {
                    // 绑定名为空（逆地理无结果→「未命名地点」）时按确认选点同语义兜底：
                    // 用显示名当绑定名建档，否则 ensureDisplayName 会静默 no-op（「无法保存」）。
                    Graph.plan.ensureDisplayName(boundName.ifBlank { v }, v, lat ?: 0.0, lng ?: 0.0)
                }
            }.exceptionOrNull()
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (err != null) {
                    // 失败必须让用户看见：原来 runCatching 吞掉，用户视角就是"点了保存没反应"
                    android.widget.Toast.makeText(
                        io.github.m0use001.planability.data.Graph.appContext,
                        "显示地名保存失败：${err.message ?: err.javaClass.simpleName}",
                        android.widget.Toast.LENGTH_LONG
                    ).show()
                } else if (boundName.isBlank()) {
                    // 绑定名为空：把这次的名字回填成绑定名，列表才能刷新出来、后续添加也挂同一栋
                    onBoundFallback(v)
                }
                // 2026-10-29 用户：新建显示地名不加额外特效（成功触感也一并去掉）
                onValueChange(v)
                onChanged()
            }
        }
        onDismiss()
    }

    AlertDialog(
        modifier = Modifier.motionEnter(),
        onDismissRequest = onDismiss,
        title = { Text(if (isEdit) "编辑显示地名" else "新建显示地名") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("显示地名（可写楼层房间号）") },
                    singleLine = true,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth()
                )
                if (boundName.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "是否填入绑定地名？",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f)
                        )
                        TextButton(onClick = {
                            haptics.perform(HapticEngine.Kind.Select)
                            text = boundName
                        }) { Text("填入") }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = text.isNotBlank(),
                onClick = { save() }
            ) { Text("确定") }
        },
        dismissButton = {
            Row {
                if (isEdit && categoryId != null) {
                    TextButton(
                        onClick = {
                            // 删除同样挂应用级 scope（与 save 同一取消风险：面板随手关掉 = 删除丢失）
                            io.github.m0use001.planability.data.Graph.appScope.launch {
                                val err = runCatching { Graph.plan.removeDisplayName(categoryId, initial) }
                                    .exceptionOrNull()
                                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                    if (err == null) haptics.perform(HapticEngine.Kind.Success)
                                    if (initial == current) onValueChange(boundName)
                                    onChanged()
                                }
                            }
                            onDismiss()
                        },
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = MaterialTheme.colorScheme.error
                        )
                    ) { Text("删除") }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        }
    )
}
