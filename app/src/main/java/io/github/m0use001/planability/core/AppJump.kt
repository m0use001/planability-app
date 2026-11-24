package io.github.m0use001.planability.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import io.github.m0use001.planability.data.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ==================== 跨应用跳转 ====================
//
// 回答的问题是：**"别的软件有哪些页面，我能不能直接过去"**，而不是"打开它然后我自己翻"。
//
// ## 三条路，按可行性递减
// ① **组件直跳**：`Intent().setClassName(pkg, cls)`。只有 `ActivityInfo.exported == true` 才能成功，
//    否则 `SecurityException`。大部分应用的深层页面都是非导出的——这是系统约束，不是我们的 bug。
// ② **URI 深链**：`ACTION_VIEW + scheme://host`。很多应用把深链注册成 exported 的 intent-filter，
//    这是"跳进具体功能"最实用的一条，而且我们**能从 intent-filter 里把它读出来**，
//    等于白拿到一份"这个应用支持哪些跳转入口"的清单。
// ③ **都不可用** → 退化成打开应用主页 + 用无障碍 UI 自动化点进去（`ui/phone_*` 工具已有该能力）。
//
// ## 为什么要 QUERY_ALL_PACKAGES
// Android 11+（本项目 minSdk 36）起包可见性默认收紧，只能看见自己与 `<queries>` 声明的包，
// 而 `<queries>` 是逐个包写死的——这个功能是开放式的（用户想跳哪个应用就跳哪个），
// 逐个声明等于每次多看一个应用就要改 manifest 再装包。该权限 Play 会拒，本项目侧载自用。
//
// ## 为什么只读、且缓存
// 枚举要对每个包各问一次 `getPackageInfo`，几百个应用就是几百次 binder 调用。
// 所以结果进内存缓存；装/卸应用后由调用方 [invalidate] 刷新（不自动监听，避免多一层广播）。

/** 一个已安装应用（用于列表展示与搜索） */
data class AppEntry(
    val pkg: String,
    val label: String,
    /** 有 LAUNCHER 入口的应用才能"从桌面点开"，没有入口不代表不能深跳 */
    val launchable: Boolean,
    /** 该应用声明的 activity 总数（含非导出的），让人一眼看出"里面有多少页" */
    val activityCount: Int,
    /** 可用的 URI 深链条数 */
    val deepLinkCount: Int
)

/** 一个可跳转目标 */
data class JumpTarget(
    /** 展示名：activity 的 label，拿不到就退回类名的简单名 */
    val label: String,
    /** 类名；null 表示这是深链目标（不带组件） */
    val cls: String?,
    /** false 的点了会 SecurityException —— UI 上必须标出来，不能让人点了才失败 */
    val exported: Boolean,
    /** `ACTION_VIEW` 用的 URI（`scheme://host/path`，读 intent-filter 得到）；null = 无深链 */
    val uri: String?,
    /** 归类用：launcher / activity / deep */
    val kind: String
) {
    companion object {
        const val KIND_LAUNCHER = "launcher"
        const val KIND_ACTIVITY = "activity"
        const val KIND_DEEP = "deep"
    }
}

object AppJump {

    private const val TAG = "AppJump"

    @Volatile
    private var cache: List<AppEntry>? = null

    @Volatile
    private var targetCache = mutableMapOf<String, List<JumpTarget>>()

    /** 装/卸应用后调用；不自动监听，免得为了这个功能多一层广播常驻。 */
    fun invalidate() {
        cache = null
        synchronized(targetCache) { targetCache.clear() }
    }

    private fun pm(): PackageManager = Graph.appContext.packageManager

    /**
     * 全部已安装应用。
     *
     * 一次 [PackageManager.queryIntentActivities] 拿到**所有**声明 ACTION_VIEW 的组件，
     * 顺手按包分组——比"每个包各查一次"快得多，也够我们算出 deepLinkCount。
     * activity 数则要逐包问，所以单独一条路径并且结果进缓存。
     */
    suspend fun apps(): List<AppEntry> = withContext(Dispatchers.IO) {
        cache ?: run {
            val p = pm()
            // 深链：一次查询拿全量，按包分组计数
            val viewCounts = runCatching {
                p.queryIntentActivities(
                    Intent(Intent.ACTION_VIEW),
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            }.getOrDefault(emptyList())
                .groupingBy { it.activityInfo.packageName }.eachCount()

            val infos = runCatching {
                p.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0L))
            }.getOrDefault(emptyList())

            val list = infos.mapNotNull { ai ->
                val pkg = ai.packageName
                // 排除自己：没有意义，且点开就是回到当前页面
                if (pkg == Graph.appContext.packageName) return@mapNotNull null
                val label = runCatching { p.getApplicationLabel(ai).toString() }
                    .getOrDefault(pkg).ifBlank { pkg }
                val activities = activityCount(p, pkg)
                AppEntry(
                    pkg = pkg,
                    label = label,
                    launchable = p.getLaunchIntentForPackage(pkg) != null,
                    activityCount = activities,
                    deepLinkCount = viewCounts[pkg] ?: 0
                )
            }.sortedBy { it.label }
            cache = list
            list
        }
    }

    private fun activityCount(p: PackageManager, pkg: String): Int =
        runCatching {
            p.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
                .activities?.size ?: 0
        }.getOrDefault(0)

    /** 某个应用的全部可跳转目标：launcher 入口 + 每个 activity + 每条深链。 */
    suspend fun targetsOf(pkg: String): List<JumpTarget> = withContext(Dispatchers.IO) {
        synchronized(targetCache) { targetCache[pkg] }?.let { return@withContext it }
        val p = pm()
        val out = mutableListOf<JumpTarget>()

        // ① LAUNCHER 入口——排最前，它永远可用
        runCatching { p.getLaunchIntentForPackage(pkg) }.getOrNull()?.let { li ->
            li.component?.let { c ->
                out += JumpTarget(
                    label = "打开应用", cls = c.className, exported = true, uri = null,
                    kind = JumpTarget.KIND_LAUNCHER
                )
            }
        }

        // ② 全部 activity。`exported` 决定能不能跳，读出来是为了**在点之前就告诉用户**，
        //    而不是点了之后收到一个裸 SecurityException。
        val pi: PackageInfo? = runCatching {
            p.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(PackageManager.GET_ACTIVITIES.toLong()))
        }.getOrNull()
        val launchCls = out.firstOrNull()?.cls
        pi?.activities?.forEach { ai: ActivityInfo ->
            if (ai.name == launchCls) return@forEach
            val label = runCatching { ai.loadLabel(p).toString() }
                .getOrDefault(ai.name.substringAfterLast('.'))
            out += JumpTarget(
                label = label.ifBlank { ai.name.substringAfterLast('.') },
                cls = ai.name,
                exported = ai.exported,
                uri = null,
                kind = JumpTarget.KIND_ACTIVITY
            )
        }

        // ③ 深链。从 intent-filter 里读出 `scheme://host`——
        //    host 有就带上（更可能直落目标页），没有就只留 scheme（多半是应用首页）。
        //    注意 `dataScheme`/`dataHost` 是 **String?**（返回第一个匹配值），不是 List：
        //    写 `?.firstOrNull()` 取到的是**首字符**（"weixin" → "w"），这种错不编译失败、
        //    只会让生成的 URI 奇奇怪怪，所以这里直接用原值。
        val viewInfos = runCatching {
            p.queryIntentActivities(
                Intent(Intent.ACTION_VIEW),
                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
            )
        }.getOrDefault(emptyList())
        viewInfos.filter { it.activityInfo.packageName == pkg }.forEach { ri ->
            val f = ri.filter ?: return@forEach
            // IntentFilter 的读法是 **countXxx() + getXxx(int)**（带下标），不是 dataScheme 那种属性——
            // Kotlin 只会给无参 getter 合成属性，所以 `f.dataScheme` 一律 unresolved（这里连错两版）。
            // host 走的是 AuthorityEntry 那一套（countDataAuthorities / AuthorityEntry），
            // 这里**只用 scheme**：`scheme://` 已能拉起对应应用，落不到具体页面时
            // jump() 自己会退回主页——比为了带 host 而在不确定的 API 上多折返一次编译划算。
            if (f.countDataSchemes() <= 0) return@forEach
            val scheme = f.getDataScheme(0) ?: return@forEach
            val uri = "$scheme://"
            val label = runCatching { ri.loadLabel(p).toString() }.getOrDefault("")
                .ifBlank { "$scheme 深链" }
            out += JumpTarget(
                label = "$label  $uri",
                cls = null, exported = ri.activityInfo.exported, uri = uri,
                kind = JumpTarget.KIND_DEEP
            )
        }

        // 同一个 activity 既在 ② 又在 ③ 时会重复；深链排在活动之后，保留两份（语义不同：一个用组件、一个用 URI）
        val result = out.distinctBy { it.kind + "|" + (it.cls ?: it.uri ?: it.label) }
        synchronized(targetCache) { targetCache[pkg] = result }
        result
    }

    /**
     * 跳到目标。成功返回 null，失败返回**给用户看的中文原因**。
     *
     * 降级顺序 **组件 → 深链 → 主页**：一次点击的成功率比"严格按选中那条走到底"更重要，
     * 而这三条的终点其实都是用户想去的地方，所以降级不等于改主意。
     *
     * @param uriOnly true 时跳过组件直跳（例如用户在界面上明确只点了深链那一项）
     */
    suspend fun jump(pkg: String, target: JumpTarget, uriOnly: Boolean = false): String? =
        withContext(Dispatchers.IO) {
            val ctx = Graph.appContext

            // 关键：**只有适用的步骤才进列表**。
            // 曾经写成「函数里 if (cls == null) return null」——那里的 null 在这条链上表示
            // **成功**，于是没组件的目标一律被判成功、后面两条压根不会执行，点了界面毫无反应。
            // "跳过"和"成功"必须是两件事，用步骤是否入列来表达。
            val steps = mutableListOf<suspend () -> String?>()
            if (target.cls != null && !uriOnly) {
                steps += {
                    val i = Intent().setComponent(ComponentName(pkg, target.cls!!))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(i); null
                    } catch (e: SecurityException) {
                        // 说人话：SecurityException 对用户毫无信息量，"没有导出"才是真正的原因——
                        // 而这个原因在点之前就该能知道（JumpTarget.exported）
                        "「${target.label}」没有导出（exported=false），系统不允许外部应用跳转它的页面"
                    } catch (e: Exception) {
                        "启动 ${target.cls} 失败：${e.javaClass.simpleName}"
                    }
                }
            }
            val uri = target.uri
            if (!uri.isNullOrBlank()) {
                steps += {
                    val i = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        .setPackage(pkg)   // 锁包：否则弹出"用哪个应用打开"的二选一
                    try {
                        ctx.startActivity(i); null
                    } catch (e: Exception) {
                        "深链 $uri 打不开：${e.javaClass.simpleName}（有些应用只在特定页面时才处理）"
                    }
                }
            }
            // 主页永远兜底：前面几条都不通才把最后的原因报回去
            steps += {
                val li = runCatching { pm().getLaunchIntentForPackage(pkg) }.getOrNull()
                if (li == null) "这个应用没有可启动的入口"
                else {
                    li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    try {
                        ctx.startActivity(li); null
                    } catch (e: Exception) {
                        // Kotlin 的 catch **必须带块**，`catch (e: X) "str"` 是语法错误（这里踩过）
                        "启动主页失败：${e.javaClass.simpleName}"
                    }
                }
            }

            var lastErr: String? = null
            for (step in steps) {
                val err = step()
                if (err == null) return@withContext null
                lastErr = err
            }
            lastErr ?: "跳转失败"
        }

    /** 按包名或应用名找包（"微信" / "com.tencent.mm" 都能命中） */
    suspend fun resolvePackage(query: String): String? = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isEmpty()) return@withContext null
        val p = pm()
        // 先当包名问一次（最快且精确）
        runCatching {
            p.getPackageInfo(q, PackageManager.PackageInfoFlags.of(0L)).packageName
        }.getOrNull()?.let { return@withContext it }
        val all = apps()
        all.firstOrNull { it.pkg.equals(q, true) || it.label.equals(q, true) }?.pkg
            ?: all.firstOrNull { it.label.contains(q, true) || it.pkg.contains(q, true) }?.pkg
    }

    /**
     * [io.github.m0use001.planability.core.ActionRegistry] 的 `app_open` 动作用：把三个参数合成
     * (包名, 目标)。**参数不足以定位时抛 IllegalArgumentException（中文原因）**——
     * 交给流水线的"遇错即停"，比静默跳到错误的应用好。
     *
     * 只给应用名 = 打开主页（target 三个属性全空，靠 jump 的主页兜底）。
     */
    suspend fun buildTarget(app: String, activity: String, uri: String): Pair<String, JumpTarget> =
        withContext(Dispatchers.IO) {
            val p = pm()
            if (app.isBlank() && activity.isBlank() && uri.isBlank()) {
                throw IllegalArgumentException("app / activity / uri 至少给一个")
            }
            val pkg: String = when {
                app.isNotBlank() ->
                    resolvePackage(app) ?: throw IllegalArgumentException("找不到应用「$app」")
                uri.isNotBlank() -> {
                    val res = runCatching {
                        p.queryIntentActivities(
                            Intent(Intent.ACTION_VIEW, Uri.parse(uri)),
                            PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                        )
                    }.getOrDefault(emptyList())
                    res.firstOrNull()?.activityInfo?.packageName
                        ?: throw IllegalArgumentException("没有应用能处理深链 $uri")
                }
                else ->
                    throw IllegalArgumentException("只给了 activity 没给应用名——类名反推不出包名（同名类可能存在于多个应用）")
            }

            if (activity.isBlank() && uri.isBlank()) {
                return@withContext pkg to JumpTarget(
                    label = "打开应用", cls = null, exported = true, uri = null,
                    kind = JumpTarget.KIND_LAUNCHER
                )
            }
            // 已知目标优先：拿到真实的 exported 与规范化的深链，比自己拼的准
            val known = targetsOf(pkg).firstOrNull {
                (activity.isNotBlank() && it.cls == activity) || (uri.isNotBlank() && it.uri == uri)
            }
            (pkg to (known ?: JumpTarget(
                label = activity.substringAfterLast('.').ifBlank { uri },
                cls = activity.ifBlank { null },
                exported = true,   // 不知道就说"试试看"，失败时 jump 会把 SecurityException 翻成人话
                uri = uri.ifBlank { null },
                kind = if (uri.isNotBlank()) JumpTarget.KIND_DEEP else JumpTarget.KIND_ACTIVITY
            )))
        }
}
