package io.github.m0use001.planability.ui.profile

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.PowerManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Accessibility
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.BatteryStd
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.Layers
import androidx.compose.material.icons.rounded.LocationOn
import androidx.compose.material.icons.rounded.Mic
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Security
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.core.content.ContextCompat
import io.github.m0use001.planability.core.HapticEngine
import io.github.m0use001.planability.core.LocalHaptics
import io.github.m0use001.planability.app.PredictiveBackOverlay

/**
 * v19.51 系统权限总览。
 *
 * ## 为什么做这一屏
 * 之前 app 需要的授权散落在五六个地方：悬浮窗在「手机操控区域」里开、
 * 无障碍/通知监听在信息获取里开、精确闹钟和电池优化**根本没有入口**（功能失效时
 * 用户只能自己去系统设置里翻）。想"一次看全、一次补全"做不到。
 *
 * ## 设计
 * - 一行一个权限：图标 + 名称 + "缺了会怎样" + 实时状态 + 跳转按钮；
 * - 状态**每次回到前台都重新查**（ON_RESUME → refreshKey++），不缓存——
 *   用户从系统设置回来看到的状态必须是新的，否则会以为点了没用；
 * - 点整行 = 跳系统设置对应的那一页。少数权限系统不提供直达 Intent，
 *   退到应用详情页，让用户在里面找；
 * - ColorOS 特有的两项（自启动、后台弹出）没有标准判定 API，
 *   老实标成"需自行确认"，不假装能检测。
 */

private enum class PermState { GRANTED, MISSING, MANUAL }

private data class PermItem(
    val icon: ImageVector,
    val title: String,
    val why: String,
    val state: PermState,
    val action: String,
    val onClick: (Context) -> Unit
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun SystemPermissionsScreen(onDone: () -> Unit, deferBack: Boolean = false) {
    val haptics = LocalHaptics.current
    var refreshKey by remember { mutableStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) refreshKey++
        }
        lifecycle.lifecycle.addObserver(obs)
        onDispose { lifecycle.lifecycle.removeObserver(obs) }
    }

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        Scaffold(
            containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent else androidx.compose.material3.MaterialTheme.colorScheme.background,
            topBar = {
                if (deferBack) io.github.m0use001.planability.ui.common.BoardSubHeader("系统权限", onDone) else
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { haptics.perform(HapticEngine.Kind.Tap); onDone() }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "返回")
                        }
                    },
                    title = { Text("系统权限", fontWeight = FontWeight.SemiBold) }
                )
            }
        ) { pad ->
            val context = LocalContext.current
            val items = remember(refreshKey) { buildPermItems(context) }
            Column(
                Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
            ) {
                val missing = items.count { it.state == PermState.MISSING }
                Text(
                    if (missing == 0) "全部已就绪" else "还有 $missing 项待处理",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (missing == 0) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error
                )
                Spacer(Modifier.height(8.dp))
                items.forEach { item -> PermRow(item) }
                Spacer(Modifier.height(20.dp))
                Text(
                    "这些授权都由系统保管，app 只能申请、不能替你打开。" +
                        "「通知 / 麦克风 / 位置」在点进去后的权限页里允许即可，" +
                        "其余要在对应的系统页面手动打开。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun PermRow(item: PermItem) {
    val context = LocalContext.current
    val haptics = LocalHaptics.current
    Card(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { haptics.perform(HapticEngine.Kind.Tap); runCatching { item.onClick(context) } }
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                item.icon, null,
                tint = if (item.state == PermState.MISSING) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(item.title, style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium)
                Text(item.why, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    when (item.state) {
                        PermState.GRANTED -> "已开启"
                        PermState.MISSING -> "未开启"
                        PermState.MANUAL -> "需自行确认"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = when (item.state) {
                        PermState.GRANTED -> MaterialTheme.colorScheme.primary
                        PermState.MISSING -> MaterialTheme.colorScheme.error
                        PermState.MANUAL -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
                TextButton(
                    onClick = {
                        haptics.perform(HapticEngine.Kind.Tap)
                        runCatching { item.onClick(context) }
                    },
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 2.dp
                    )
                ) { Text(item.action, style = MaterialTheme.typography.labelSmall) }
            }
        }
    }
}

// ---------- 各项判定 ----------

private fun buildPermItems(c: Context): List<PermItem> {
    val pkg = c.packageName
    fun granted(p: String) =
        ContextCompat.checkSelfPermission(c, p) == PackageManager.PERMISSION_GRANTED

    val nm = c.getSystemService(NotificationManager::class.java)
    val am = c.getSystemService(AlarmManager::class.java)
    val pm = c.getSystemService(PowerManager::class.java)

    fun notifListenerOn(): Boolean {
        val flat = Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners") ?: ""
        return flat.contains(pkg)
    }
    fun a11yOn(): Boolean {
        val flat = Settings.Secure.getString(
            c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        return flat.contains(pkg)
    }
    fun appDetails() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))

    val items = mutableListOf<PermItem>()

    items += PermItem(
        Icons.Rounded.Notifications, "通知",
        "早报、待办提醒都靠它；没有就完全没有提示",
        if (nm.areNotificationsEnabled()) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c ->
        runCatching {
            c.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra("android.provider.extra.APP_PACKAGE", pkg)
            )
        }.onFailure { c.startActivity(appDetails()) }
    }

    items += PermItem(
        Icons.Rounded.Accessibility, "无障碍",
        "模拟点击、读屏幕内容、跨应用操作都靠它",
        if (a11yOn()) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c -> c.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

    items += PermItem(
        Icons.Rounded.Notifications, "通知使用权",
        "读取其他应用的通知（信息获取、群摘要）",
        if (notifListenerOn()) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c ->
        runCatching {
            c.startActivity(Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))
        }.onFailure { c.startActivity(appDetails()) }
    }

    items += PermItem(
        Icons.Rounded.Layers, "显示在其他应用上层",
        "悬浮球、区域框、审批卡要浮在别的应用上面",
        if (Settings.canDrawOverlays(c)) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c ->
        c.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$pkg")))
    }

    items += PermItem(
        Icons.Rounded.Alarm, "精确闹钟",
        "每天的定时任务、事件准点触发",
        if (Build.VERSION.SDK_INT < 31 || am.canScheduleExactAlarms()) PermState.GRANTED
        else PermState.MISSING,
        "去开启"
    ) { c ->
        runCatching {
            if (Build.VERSION.SDK_INT >= 31) {
                c.startActivity(
                    Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$pkg"))
                )
            } else c.startActivity(appDetails())
        }.onFailure { c.startActivity(appDetails()) }
    }

    items += PermItem(
        Icons.Rounded.BatteryStd, "允许后台运行",
        "15 分钟一轮的后台巡检不被系统收掉；进去后点「耗电管理」那一行",
        if (pm.isIgnoringBatteryOptimizations(pkg)) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c -> openBatteryPage(c, pkg) }

    items += PermItem(
        Icons.Rounded.LocationOn, "位置（始终允许）",
        "地理触发：到/离开某个地点自动跑任务",
        if (granted(Manifest.permission.ACCESS_FINE_LOCATION) &&
            granted(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        ) PermState.GRANTED else PermState.MISSING,
        "去设置"
    ) { c -> c.startActivity(appDetails()) }

    items += PermItem(
        Icons.Rounded.Mic, "麦克风",
        "语音输入、语音采集",
        if (granted(Manifest.permission.RECORD_AUDIO)) PermState.GRANTED else PermState.MISSING,
        "去允许"
    ) { c -> c.startActivity(appDetails()) }

    items += PermItem(
        Icons.Rounded.Folder, "所有文件访问",
        "文件页管理整机存储",
        if (Environment.isExternalStorageManager()) PermState.GRANTED else PermState.MISSING,
        "去开启"
    ) { c ->
        runCatching {
            c.startActivity(
                Intent(
                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                    Uri.parse("package:$pkg")
                )
            )
        }.onFailure { c.startActivity(appDetails()) }
    }

    // ColorOS 特有：没有标准 API 判定
    items += PermItem(
        Icons.Rounded.PlayArrow, "自启动",
        "ColorOS 默认不给；开机触发、每日早报全靠它",
        PermState.MANUAL, "去确认"
    ) { c -> c.startActivity(appDetails()) }

    items += PermItem(
        Icons.Rounded.Security, "后台弹出界面",
        "锁屏/后台时的提醒与审批卡要能弹出来",
        PermState.MANUAL, "去确认"
    ) { c -> c.startActivity(appDetails()) }

    return items
}

/**
 * 打开 ColorOS「耗电管理」页（本 app 那一页）。
 *
 * ## 为什么不直接用 Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS
 * 那个 Intent 落到**原生安卓**的「电池优化」列表，ColorOS 上那一页不生效——
 * 用户 10-18 实测反馈"跳过去没用，后台还是被杀"。
 *
 * ## 真正的路径（真机 2026-10-18 实测）
 * 应用详情页 →「耗电管理」→ 落地 `com.oplus.battery/.powermanager.fuelgaue.PowerControlActivity`，
 * 页面内可选：允许后台运行 / 允许自动后台 / 允许应用间跳转 / 禁用后台运行。
 * 直接 `am start -n` 该组件验证可拉起 ✓
 *
 * ## 兜底顺序
 * 1. oplus 的 PowerControlActivity（ColorOS 真页）；
 * 2. 应用详情页（部分版本组件名不同，从那里点「耗电管理」一定找得到）；
 * 3. 原生电池优化列表（Other Android 机型上这才是对的）。
 */
private fun openBatteryPage(c: Context, pkg: String) {
    // v19.56：**只用系统标准 Intent，不再 setPackage、不再猜 oplus 组件。**
    //
    // 这一行先后错过三次，都记下来免得再改：
    //  ① `am start -n com.oplus.battery/...fuelgaue.PowerControlActivity` —— 起不来。
    //     dumpsys 显示该 activity 不在 resolver 表（exported=false）、没有对应 Action，
    //     `am start` 直接抛 Exception，静默落回原生页面。
    //  ② `setPackage("com.oplus.appdetail")` —— 更糟：那个包是「应用商店」的详情页
    //     （dumpsys 里只有 InstallGuideActivity / SafeGrade / WebView，没有应用信息），
    //     系统 Action 根本 resolve 不出来 → ActivityNotFoundException。
    //  ③ 什么都不加（现在这版）—— 落到 com.android.settings/.applications.InstalledAppDetails，
    //     真机实测**那一页有「耗电管理」这一行**，点进去才是
    //     com.oplus.battery/com.oplus.powermanager.fuelgaue.PowerControlActivity
    //     （可设「允许后台运行 / 允许自动后台 / 允许应用间跳转 / 禁用后台」）。
    //
    // 结论：ColorOS 没给"直达单 app 耗电管理"的公开入口，**多戳一下那一行**是唯一可靠路径。
    // 兜底：原生电池优化列表（非 ColorOS 机型上那才是对的）。
    val details = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg"))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val stock = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { c.startActivity(details) }
        .onFailure { runCatching { c.startActivity(stock) } } }

