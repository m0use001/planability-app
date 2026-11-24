package io.github.m0use001.planability.ui.files

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.LockOpen
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.m0use001.planability.app.PredictiveBackOverlay
import io.github.m0use001.planability.data.SandboxAccess
import kotlinx.coroutines.launch

/**
 * v19.17 **沙箱访问设置**（文件页右上角那把锁进入）。
 *
 * 这一页只管**开关与状态**，不含浏览——所有文件都走文件页的统一列表，
 * `FilesScreen.reload()` 在普通 File 读不到时会自动改走 [SandboxAccess]。
 *
 * 未授权时 `Android/data` 不出现在文件列表里，授权后自己出现。
 *
 * ## 底层不依赖第三方提权 App
 *
 * 现在是自己连手机的 `adbd`（见 `AdbShellClient`），所以**不需要装任何第三方 App**。
 * 状态从四态收敛成三态：要开端口、要点确认、可用——后两种都是一次性动作。
 */
@Composable
fun SandboxSettingsScreen(onDone: () -> Unit, deferBack: Boolean = false) {

    PredictiveBackOverlay(onBack = onDone, deferBack = deferBack) {
        ScaffoldBox(onDone, deferBack)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun ScaffoldBox(onDone: () -> Unit, deferBack: Boolean = false) {

    // state() 只读缓存、立即返回；真正的连接放在这里做（协程里，内部切 IO 线程）
    var st by remember { mutableStateOf(SandboxAccess.state()) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    // **只有「从来没判过一次状态」才真连一次**；之后一律不连，
    // 由用户点「重新检测」发起。每次连接都可能换来一个授权弹窗，
    // 用户明确反馈过「一直反复弹窗调试申请，严重干扰开发」。
    LaunchedEffect(Unit) {
        st = SandboxAccess.state()
        SandboxAccess.refreshIfFirstTime()
        st = SandboxAccess.state()
    }

    // 2026-10-31 UI 修：全屏子页统一用 Scaffold + TopAppBar（与「功能开关」「计划设置」
    // 等二级页同款）。旧写法是手搓 Row + 无状态栏内边距——覆盖层窗口开了
    // decorFitsSystemWindows=false，标题和返回键直接压在状态栏时钟下面。
    androidx.compose.material3.Scaffold(
        containerColor = if (deferBack) androidx.compose.ui.graphics.Color.Transparent
        else MaterialTheme.colorScheme.background,
        topBar = {
            if (deferBack) {
                io.github.m0use001.planability.ui.common.BoardSubHeader("沙箱访问设置", onDone)
            } else {
            androidx.compose.material3.TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onDone) {
                        Icon(
                            Icons.AutoMirrored.Rounded.ArrowBack, "返回",
                            // R11：返回 = 导航图标 → 中性
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                },
                title = { Text("沙箱访问设置", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold) }
            )
            }
        }
    ) { pad ->
    Column(
        Modifier
            .fillMaxSize()
            .padding(pad)
            .verticalScroll(rememberScrollState())
    ) {

        // ---------------- 当前状态 ----------------
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceContainer
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (st == SandboxAccess.State.READY) Icons.Rounded.LockOpen
                        else Icons.Rounded.Lock,
                        null, Modifier.size(20.dp),
                        // R11：状态由图形（开锁/上锁）+ 文字表达，颜色不重复承担
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        st.title(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.weight(1f)
                    )
                }

                Spacer(Modifier.height(8.dp))
                Text(
                    st.explain(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(Modifier.height(12.dp))
                OutlinedButton(
                    onClick = {
                        if (st == SandboxAccess.State.NOT_ENABLED) {
                            SandboxAccess.openDeveloperOptions()
                        } else {
                            // 唯一会发起连接的地方。force=true：用户主动点的，
                            // 绕过冷却期。一次点击 -> 一次连接 -> 最多一个弹窗。
                            scope.launch {
                                SandboxAccess.refresh(force = true)
                                st = SandboxAccess.state()
                            }
                        }
                    }
                ) {
                    // 失败后有 3 分钟冷却（防止连点），倒计时显示在按钮上。
                    val cd = SandboxAccess.cooldownLeftMs()
                    Text(
                        if (cd > 0 && st != SandboxAccess.State.READY) {
                            "重新检测（${cd / 1000}s 后）"
                        } else {
                            when (st) {
                                SandboxAccess.State.NOT_ENABLED -> "去开启调试端口"
                                SandboxAccess.State.NOT_AUTHORIZED -> "重新检测"
                                SandboxAccess.State.READY -> "重新检测"
                            }
                        },
                        style = MaterialTheme.typography.labelLarge
                    )
                }

                // 「无线调试」这一步只能用户在开发者选项里手动开
                // （App 没有 WRITE_SECURE_SETTINGS，settings put 会被 framework 改回）。
                // 所以给它一个独立入口，不和「重新检测」混在一起。
                if (st != SandboxAccess.State.READY) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(
                        onClick = { SandboxAccess.openDeveloperOptions() }
                    ) {
                        Icon(
                            Icons.Rounded.Lock,
                            null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "打开开发者选项（开「无线调试」）",
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // ---------------- 能读什么、读不到什么 ----------------
        InfoRow(
            ok = true,
            title = "共享存储（Download / Pictures / Documents 等）",
            body = "零权限，文件页本来就能读，这一页不参与。"
        )
        InfoRow(
            ok = st == SandboxAccess.State.READY,
            title = "别的 App 的 Android/data",
            body = "就绪后在文件页直接进 Android/data，不用换页面。" +
                "未就绪时该目录在文件列表里不出现。"
        )
        InfoRow(
            ok = false,
            title = "别的 App 的 /data/data（数据库、聊天记录）",
            body = "读不到。内核级 UID 隔离，连 adb shell 自己都被拒绝。" +
                "这是硬边界，不是没实现。"
        )

        Spacer(Modifier.height(40.dp))
    }
    }
}

@Composable
private fun InfoRow(ok: Boolean, title: String, body: String) {
    Row(
        verticalAlignment = Alignment.Top,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 7.dp)
    ) {
        Icon(
            if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.Warning,
            null, Modifier.size(18.dp),
            tint = if (ok) MaterialTheme.colorScheme.primary
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun SandboxAccess.State.title() = when (this) {
    SandboxAccess.State.READY -> "沙箱访问已就绪"
    SandboxAccess.State.NOT_AUTHORIZED -> "需要先打开「无线调试」"
    SandboxAccess.State.NOT_ENABLED -> "尚未开启调试端口"
}

private fun SandboxAccess.State.explain() = when (this) {
    SandboxAccess.State.READY ->
        "已连上，可以在文件页直接进入 Android/data 浏览。"
    // 2026-10-31：这段以前混进了 Markdown 语法（**加粗**、反引号），Text 不解析
    // Markdown，用户看到的是满屏裸露的星号和反引号——改成纯文本 + 分段。
    SandboxAccess.State.NOT_AUTHORIZED ->
        "端口是通的，App 也已经把公钥交给系统了（dumpsys adb 的 " +
            "last_key_received 会更新），但系统不弹授权框。两种原因：\n\n" +
            "1. 开发者选项里的「无线调试」是关的——这条 TCP 通道被系统" +
            "算作「无线」传输，对这类传输不弹框。\n" +
            "2. 上一次弹窗被「返回键」关掉了。系统的授权流程是" +
            "「询问 → 显示 → 回一个应答」；返回键只是关掉窗口、不回应答，" +
            "系统那边的「待确认」就一直挂着，此后所有新请求一律忽略。\n\n" +
            "弹窗只能按「允许」或「取消」，绝对不要按返回键。\n" +
            "如果已经卡住：请到 开发者选项 → 撤销 USB 调试授权（会重置" +
            "待确认状态，也顺带清掉历史垃圾 key），再回到这里点「重新检测」。"
    SandboxAccess.State.NOT_ENABLED ->
        "手机还没有把调试服务开在 TCP 端口上，所以连不上。" +
            "需要开一次调试端口（开机前用电脑执行 adb tcpip 5555，" +
            "或开发者选项里开无线调试）。只开一次，不是每次。"
}