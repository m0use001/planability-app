package io.github.m0use001.planability.data

import android.util.Log
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPair
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * v19.17 **自己连 adbd**——不装任何第三方提权 App，手机自己当 ADB 主机。
 *
 * ## 为什么这样就够了
 *
 * 那类提权 App 干的唯一一件事，就是"在手机上配对无线调试、把服务端拉起来"。
 * 而只要我们能连上手机的 `adbd`，**每条命令本来就是 `shell` 身份**
 * （实测 `uid=2000(shell)`，带 `ext_data_rw(1078)` / `ext_obb_rw(1079)` 组），
 * 所以外部服务端根本不需要——省掉它，也就没有"adb 权限受限"那套厂商限制。
 *
 * 前置条件：`adbd` 在 TCP 端口上服务（`adb tcpip 5555` 之后 5555 常开）。
 *
 * ## 协议是抓包校准的，不是照文档猜的
 *
 * 第一次我照着"ADBC"常量的字面值写，`adbd` **静默丢弃**我的 CNXN——
 * 不报错、不回应，表现和服务没起来一模一样。用 TCP 代理挂在自己 PC 上、
 * 让 PC 的 adb 经过代理打一遍，把真实首包抓下来对比，才找到唯一差别：
 *
 * | 字段 | 我原来发的 | adb 实际发的 |
 * |---|---|---|
 * | command | `58 4e 43 4e` = "XNCN" | **`43 4e 58 4e` = "CNXN"（ASCII）** |
 * | arg0 | 0x01000000 | 0x01000001 |
 * | arg1 | 0x00100000 | 0x00100000 |
 * | data_crc32 | 算了 CRC | 0（可选字段，实际不发） |
 *
 * 第二个坑：头是 **6 个字段 24 字节**，我少写一个 `data_crc32` 只发出 20 字节，同样被丢。
 *
 * 所以这里的命令常量一律写成 4 字节 ASCII 字面量，不再用十六进制魔数——
 * 这两个坑都是"想当然"栽的。
 *
 * ## 握手顺序（抓包实测）
 *
 * ```
 * host -> CNXN   banner = "host::features=shell_v2,cmd,stat_v2,...\0"
 * adbd -> AUTH   arg0=1 (TOKEN)，20 字节随机数
 * host -> AUTH   arg0=2 (SIGNATURE)，token 的 RSA-SHA1 签名
 * adbd -> CNXN   banner = "device::ro.product.name=PLK1..."
 * ```
 *
 * ## 关于「允许调试」弹窗
 *
 * 首次连接时我们的公钥不在 adbd 的白名单里，手机上会弹「允许 USB 调试吗？」，
 * **需要用户在屏幕上点一次允许**。允许之后密钥落盘，本 App 把私钥也存在
 * 自己的沙箱里，重启 App 不用再弹。
 */
object AdbShellClient {

    private const val TAG = "AdbShellClient"
    private const val LOOPBACK = "127.0.0.1"
    private const val PORT = 5555

    /** 瞬时拒绝重试次数。adbd 队列小、并发时会被 RST，不能一次就判定端口没开。 */
    private const val CONNECT_RETRIES = 5

    /**
     * 失败原因——**在失败发生的那一刻记下**，而不是事后另开连接去猜。
     *
     * 之前 `SandboxAccess` 用 `canConnect()`（另开一个 socket）来判断
     * 「端口开了没」，但 adbd 只服务单 host，第二个连接会被拒，
     * 于是把「端口开着、只是在等用户授权」误报成「端口没开」，
     * 界面上一直显示「尚未开启调试端口」。
     */
    enum class Failure {
        /** 没有失败 */
        NONE,

        /** TCP 连不上：端口确实没开（adbd 不在 TCP 模式） */
        PORT_CLOSED,

        /** 端口开着，但 adbd 一直在挑战我们——多半在等用户点「允许」 */
        HANDSHAKE_TIMEOUT,

        /** 其它错误（握手被拒、消息解析失败等） */
        OTHER
    }

    @Volatile
    private var lastFailure: Failure = Failure.NONE

    /** 最近一次 [shell] 的失败原因。成功时为 [Failure.NONE]。 */
    fun lastFailure(): Failure = lastFailure

    // 命令常量按**线上 ASCII** 写死。写成十六进制魔数就是上次踩坑的原因。
    private val CMD_CNNX = "CNXN".toByteArray(Charsets.US_ASCII)
    private val CMD_AUTH = "AUTH".toByteArray(Charsets.US_ASCII)
    private val CMD_OPEN = "OPEN".toByteArray(Charsets.US_ASCII)
    private val CMD_WRTE = "WRTE".toByteArray(Charsets.US_ASCII)
    private val CMD_CLSE = "CLSE".toByteArray(Charsets.US_ASCII)
    private val CMD_OKAY = "OKAY".toByteArray(Charsets.US_ASCII)

    private const val AUTH_TOKEN = 1
    private const val AUTH_SIGNATURE = 2

    /**
     * 主机主动上报自己的公钥。
     *
     * 只有**陌生主机**才需要这一步——已经授权过的主机直接跳到签名。
     * 抓包显示 PC 的 adb 只发 CNXN + AUTH SIGNATURE，因为它早就被授权了。
     * 我们第一次连必然是陌生的，少了这一步 adbd 永远找不到能验签的 key，
     * 于是每秒重新挑战一次，永远握手不完。
     */
    private const val AUTH_RSAPUBLICKEY = 3

    /**
     * 连着这么多次签名都验不过，才把公钥报上去换授权框。
     *
     * adbd 验签是拿签名去试 `adb_keys` 里的每一把公钥（`adb_auth_verify`
     * 每次都重读文件），所以**已授权的 key 不需要报公钥、也就不再弹框**。
     * 只有真的不在文件里，报上去才换得来那个框。
     */
    private const val SIGN_BEFORE_REPORT = 3

    /**
     * 本进程最多报多少次公钥。**这是防弹窗堆叠的硬闸门。**
     *
     * 每报一次 `AUTH RSAPUBLICKEY`，`adb_auth_confirm_key` 就会往 framework
     * 写一个 `PK<key>`，框架随后在 systemui 里 `startActivity` 一次
     * `UsbDebuggingActivity`。Android 14+ 这些弹窗是**排队**的：消掉一个
     * 立刻又弹下一个，用户看到的就是「无穷无尽的弹窗」。
     *
     * 3 次还过不去，说明这条路本来就不通（比如 `adb_wifi_enabled=0`，
     * TCP 传输根本不弹框），继续要只是骚扰，不会有进展。
     */
    private const val MAX_PUB_REPORTS = 3

    /** 本进程已报公钥的次数。跨连接累计。 */
    private var pubReports = 0

    private const val KEY_AUTHORIZED = "authorized"
    private const val VERSION = 0x01000001
    private const val MAX_PAYLOAD = 0x00100000

    private const val BANNER_PREFIX =
        "host::features=shell_v2,cmd,stat_v2,ls_v2,remount_shell,track_app"

    /**
     * CNXN banner = `host::features=...` + NUL + **公钥 PEM**。
     *
     * NUL 之后的内容就是公钥——adbd 从这里取出「临时 key」来验 AUTH SIGNATURE。
     * 我第一版只发了 features 就收尾，adbd 直接报
     * `E/adbd: Invalid base64 key`，然后不停重发 AUTH，永远握手不完。
     * （证据在手机端 adbd 日志里，不是猜的。）
     */
    private fun banner(): ByteArray = BANNER_PREFIX.toByteArray(Charsets.UTF_8)

    /** 握手一次的成本不小（RSA 运算 + 往返），所以复用连接。 */
    @Volatile
    private var conn: Conn? = null

    /**
     * 所有 ADB 操作都跑在这个单线程池上。
     *
     * 单线程是刻意的：ADB 协议是**一条连接上的严格请求/应答流**，
     * 多线程并发进去会把消息序列打乱。调用方（UI）只用排队等结果。
     */
    private val worker = Executors.newSingleThreadExecutor { r ->
        Thread(r, "adb-shell").apply { isDaemon = true }
    }

    // -----------------------------------------------------------------------
    // 对外 API
    // -----------------------------------------------------------------------

    /**
     * 以 `shell` 身份跑一条命令，返回完整 stdout。
     *
     * **内部强制切到后台线程**——socket 连接是实打实的网络 IO，在主线程做会抛
     * `NetworkOnMainThreadException`（第一版就这么崩的，UI 里直接在图标上调了它）。
     * 放在这里兜住，调用方就不用各自记得切线程；这里也顺带做超时。
     *
     * @return null 表示连不上 / 没授权 / 超时。**不抛异常**——
     *         这条链路横跨 socket、加密、弹窗授权，任何一环都可能失败，
     *         调用方只该看到"读不到"，不该崩。
     */
    fun shell(command: String, timeoutMs: Int = 15_000): String? {
        // 已经在后台线程上就直接跑，别再多一次线程切换和等待
        if (Thread.currentThread().name?.startsWith("adb-shell") == true) {
            return shellBlocking(command, timeoutMs)
        }
        val f: Future<*> = worker.submit<Any?> { shellBlocking(command, timeoutMs) }
        return try {
            @Suppress("UNCHECKED_CAST")
            f.get(timeoutMs.toLong() + 2_000L, TimeUnit.MILLISECONDS) as String?
        } catch (e: Throwable) {
            f.cancel(true)
            Log.w(TAG, "shell 超时或被中断: ${e.javaClass.simpleName}")
            closeQuietly()
            null
        }
    }

    private fun shellBlocking(command: String, timeoutMs: Int): String? = try {
        val r = withConn(timeoutMs) { it.shell(command, timeoutMs) }
        lastFailure = Failure.NONE
        r
    } catch (e: Throwable) {
        // **只在还没有归因时**才判定。connect() 和握手超时处已经分别记了
        // PORT_CLOSED / HANDSHAKE_TIMEOUT，这里再按异常类型判一次会把它们盖掉
        // （我抛的是 IllegalStateException，会被笼统归成 OTHER）。
        if (lastFailure == Failure.NONE) {
            lastFailure = if (e is java.util.concurrent.TimeoutException ||
                e is java.net.SocketTimeoutException
            ) {
                Failure.HANDSHAKE_TIMEOUT
            } else {
                Failure.OTHER
            }
        }
        Log.w(TAG, "shell 失败（${lastFailure}）: ${e.javaClass.simpleName}: ${e.message}")
        closeQuietly()
        null
    }

    /** 列目录。null = 读不到（与「空目录」不同，调用方要分开显示）。 */
    fun listDir(path: String): List<String>? =
        shell("ls -1A '$path'")?.lines()
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() && it != "." && it != ".." }

    /** 读文件，限大小防 OOM。超限返回 null。 */
    fun readText(path: String, maxBytes: Long = 2L * 1024 * 1024): String? {
        val out = shell("head -c $maxBytes '$path'") ?: return null
        return if (out.length.toLong() > maxBytes) null else out
    }

    fun isAlive(): Boolean = try {
        shell("echo ok", 5_000)?.trim() == "ok"
    } catch (_: Throwable) {
        false
    }

    fun closeQuietly() {
        try {
            conn?.close()
        } catch (_: Throwable) {
        }
        conn = null
    }

    // -----------------------------------------------------------------------
    // 连接管理
    // -----------------------------------------------------------------------

    private inline fun <T> withConn(timeoutMs: Int, body: (Conn) -> T): T {
        var c = conn
        if (c == null || !c.alive) {
            c = connect(timeoutMs)
            conn = c
        }
        return body(c)
    }

    private fun connect(timeoutMs: Int): Conn {
        val s = Socket()
        // 重试：**瞬时 ECONNREFUSED 不等于端口没开**。
        // 实测端口明明 LISTEN（adbd 已运行 2h26m、未重启），App 却拿到
        // `ECONNREFUSED` —— adbd 的 accept 队列很小，排队时新连接会被直接 RST。
        // 一次拒绝就报「端口没开」，正是用户看到的假象。
        var last: Throwable? = null
        for (tryNo in 1..CONNECT_RETRIES) {
            try {
                s.connect(InetSocketAddress(LOOPBACK, PORT), timeoutMs / CONNECT_RETRIES)
                last = null
                break
            } catch (e: Throwable) {
                last = e
                Log.i(TAG, "第 $tryNo 次连 $LOOPBACK:$PORT 失败（${e.javaClass.simpleName}），重试")
                try { s.close() } catch (_: Throwable) {}
            }
        }
        if (last != null) {
            lastFailure = Failure.PORT_CLOSED
            Log.i(TAG, "连不上 $LOOPBACK:$PORT（${last.javaClass.simpleName}）→ 判定端口没开")
            error("adbd 不在 TCP 模式：$last")
        }
        s.soTimeout = timeoutMs
        s.tcpNoDelay = true
        val io = Io(s.getInputStream(), s.getOutputStream())

        val kp = loadOrCreateKeyPair()
        io.write(CMD_CNNX, VERSION, MAX_PAYLOAD, banner())
        Log.i(TAG, "已发 CNXN，等挑战…")

        // **这里不发 AUTH RSAPUBLICKEY。**
        // AOSP adb.cpp：验签失败时 adbd 只是再发一次 AUTH TOKEN，**不弹框**；
        // 弹框只由 AUTH RSAPUBLICKEY 触发（adb_auth_confirm_key）。
        // 我原来每条连接开口就报一次公钥 —— 等于每条连接都跟手机要一次授权，
        // 用户反馈「一直反复弹窗调试申请，严重干扰开发」，机制就是这个。
        // 现在改成：先老实签名，**连续两次验签不过**才报一次公钥换框。
        // 已经授权过（本地记着）就**一次都不报** —— 免得第二次连接又弹框。
        // **不做「已授权」判断，直接靠轮次把关。**
        //
        // 我原来加了个本地 authorized 标记，想的是「授权过就别再报公钥，
        // 免得第二次连接又弹框」。**这是错的**：它分不清「永久授权（进了
        // adb_keys）」和「本次会话有效（没勾一律允许）」。实测踩到了：
        // 11:50 那次握手成功了（所以 authorized=true），但那次授权是临时的，
        // key 并没进 adb_keys；于是 11:52 新进程直接跳过报公钥，
        // 验签必然失败 -> 超时 -> 状态卡在「等待手机确认」。
        //
        // 而这个门本来就没必要：key 在 adb_keys 里时**第 1~2 轮就验签通过**，
        // 根本走不到 [SIGN_BEFORE_REPORT] 分支。真正的防刷屏靠
        // [MAX_PUB_REPORTS]（每进程 3 次上限）+ 只有用户点按钮才连。
        var pubReported = false
        Log.i(TAG, "HANDSHAKE_START pubReports=" + pubReports +
            " reportAt=" + SIGN_BEFORE_REPORT)

        // 循环 challenge/签名，直到对方批准（CNXN）或超时。
        // adbd 不认识我们的公钥时会**反复重发** AUTH TOKEN，
        // 同时在系统里弹「允许 USB 调试吗？」——我们要一直等在那里，
        // 用户点了才会发 CNXN。中途放弃 = 弹窗永远等不到人。
        val t0 = System.currentTimeMillis()
        val deadline = t0 + timeoutMs
        var round = 0
        while (true) {
            val cmd = io.readCommand()
            when {
                cmd.contentEquals(CMD_CNNX) -> {
                    Log.i(TAG, "握手完成（第 $round 轮）")
                    // 记住「已授权」：以后连接不再报公钥，不再弹框
                    markAuthorized(true)
                    return Conn(s, io)
                }
                cmd.contentEquals(CMD_AUTH) && io.readArg0() == AUTH_TOKEN -> {
                    val token = io.readPayload()
                    round++
                    // 连着 [SIGN_BEFORE_REPORT] 次签名都没被认可，说明 adb_keys
                    // 里没有我们的 key —— 这时才报一次公钥去换那个授权框。
                    //
                    // 之前这里「紧跟 CNXN 就报公钥」，实测也能弹框，但每条连接
                    // 都弹一次；而且 payload 少了结尾 NUL，`adb_auth_confirm_key`
                    // 第一行就 return，框都弹不出来。
                    if (!pubReported && round >= SIGN_BEFORE_REPORT) {
                        pubReported = true
                        // **硬上限**：本进程最多报 3 次。
                        // 每报一次都可能换来一个「允许 USB 调试吗？」弹窗叠在屏幕上；
                        // 用户明确反馈过「一直反复弹窗调试申请，严重干扰开发」。
                        // 而 Android 14+ 是把弹窗**排队**的——AOSP `AdbService`
                        // 每次收到新 key 都往 systemui 里 `startActivity` 一次，
                        // 消掉一个又弹下一个，看起来就是无穷无尽。
                        // 3 次还过不去，说明这条路本来就不通（比如
                        // `adb_wifi_enabled=0`，TCP 传输根本不弹框），
                        // 这时再要下去只是骚扰用户，不会有任何进展。
                        if (pubReports >= MAX_PUB_REPORTS) {
                            Log.i(TAG, "PUBKEY_CAP_HIT reports=" + pubReports +
                                " round=" + round)
                        } else {
                            pubReports++
                            val pb = publicKeyBytes(kp)
                            io.write(CMD_AUTH, AUTH_RSAPUBLICKEY, 0, pb)
                            // ASCII 标记：中文日志在 PowerShell 过滤时会被损坏，
                            // 排查「到底有没有发公钥」时需要一个能稳定 grep 的锚点。
                            Log.i(TAG, "PUBKEY_REPORT sent bytes=" + pb.size +
                                " round=" + round + " reports=" + pubReports)
                        }
                    }
                    io.write(CMD_AUTH, AUTH_SIGNATURE, 0, signToken(kp, token))
                    // **限流**：adbd 未认可时几乎是一收到签名就重发下一个 token，
                    // 一秒能打 200+ 行，把别的关键行全冲没了（我排查时就被冲掉过）。
                    // 只记前 3 轮 + 每 50 轮一次。
                    if (round <= 3 || round % 50 == 0) {
                        Log.i(TAG, "第 $round 次挑战，签名回送（距握手开始 " +
                            (System.currentTimeMillis() - t0) + "ms）")
                    }
                }
                else -> {
                    Log.w(TAG, "握手收到意外命令 ${cmd.ascii()}（arg0=${io.readArg0()}）")
                }
            }
            if (System.currentTimeMillis() > deadline) {
                s.close()
                // 没握成手 -> 清掉「已授权」标记，下次才允许再报公钥换框
                markAuthorized(false)
                // 明确记成 HANDSHAKE_TIMEOUT：端口是通的、只是在等用户点允许。
                // 不记的话这里抛的 IllegalStateException 会被笼统归成 OTHER，
                // 上层就没法把它和「端口没开」区分开。
                lastFailure = Failure.HANDSHAKE_TIMEOUT
                error("等待授权超时（$round 轮）。手机上若有「允许 USB 调试吗？」请点允许")
            }
        }
    }

    /**
     * 用本 App 的私钥对 token 做 RSA-SHA1 签名。
     *
     * 私钥存在 App 沙箱里，**跨启动复用**——否则每开一次 App 手机都要弹一次
     * 「允许 USB 调试吗？」，用户会直接把弹窗当成骚扰关掉。
     */
    private fun signToken(kp: KeyPair, token: ByteArray): ByteArray {
        val sig = Signature.getInstance("SHA1withRSA")
        sig.initSign(kp.private)
        sig.update(token)
        return sig.sign()
    }

    /**
     * AUTH RSAPUBLICKEY 的 payload。
     *
     * **结尾那个 NUL 是必须的**：AOSP `adb_auth_confirm_key` 第一行就是
     * ```c
     * if (key[len - 1] != '\0') { D("Key must be a null-terminated string"); return; }
     * ```
     * 主机端 `send_auth_publickey` 用的是 `strlen(pubkey) + 1` 作为
     * `data_length`，**含那个 NUL**。少它就直接 return，授权框根本不弹。
     */
    private fun publicKeyBytes(kp: KeyPair): ByteArray {
        val rsa = kp.public as java.security.interfaces.RSAPublicKey
        val text = adbPublicKey(rsa.modulus, rsa.publicExponent)
        Log.i(TAG, "公钥串 ${text.length} 字符（modulus ${rsa.modulus.bitLength()} 位）")
        return text.toByteArray(Charsets.UTF_8) + 0
    }

    /**
     * adb 的公钥序列化 —— **`le32(len) + 数据`，两段拼接后整体 base64，单行**。
     *
     * 这里曾经错成「X.509 SPKI 的 base64、每 64 字符换一行」，后果是
     * adbd 报一屏 `Invalid base64 key`（按行解码必然失败），验签过不去，
     * **key 永远存不进 adb_keys，于是每次连接都重新弹授权框**。
     *
     * 为什么弹框看着还正常、骗过了好几轮：framework 算指纹是**对公钥字符串
     * 做 SHA1**（`key.replaceAll("\\s","")` 之后摘要），**根本不解析密钥**，
     * 所以格式再离谱也会显示一个像模像样的指纹。
     *
     * 对照真实的 `~/.android/adbkey.pub`：721 字符、**1 行**，开头 `QAAA`
     * 解出来是 `40 00 00 00…`（长度前缀），而不是 ASN.1 的 `0x30`。
     */
    private fun adbPublicKey(modulus: BigInteger, exponent: BigInteger): String {
        val buf = java.io.ByteArrayOutputStream()
        fun put(v: BigInteger) {
            val b = v.toByteArray()
            // 去掉 BigInteger 自己补的那个前导 0 字节
            val body = if (b.size > 1 && b[0] == 0.toByte()) b.copyOfRange(1, b.size) else b
            val len = java.nio.ByteBuffer.allocate(4)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(body.size).array()
            buf.write(len)
            buf.write(body)
        }
        put(modulus)
        put(exponent)
        // NO_WRAP：**绝不能有换行**。adb_keys 是一行一个 key，带换行 adbd 读不出来。
        return android.util.Base64.encodeToString(buf.toByteArray(), android.util.Base64.NO_WRAP)
    }

    private fun keyFile(): File = File(Graph.appContext.filesDir, "adb_host_key.pk8")

    // -----------------------------------------------------------------------
    // 「已授权」记忆
    //
    // 实测：授权落盘后，**下一次连接的头几个签名仍然验不过**（adb_keys 是
    // 异步写的），要过好几轮 adbd 才重新读到文件。所以我原来「第 3 次签名
    // 失败就报公钥」的逻辑，会在**第二次连接时又多报一次** —— 又换一个弹窗。
    //
    // 记一个本地标记：握过手成功过就**再也不报公钥**，只有握手失败才清掉。
    // 这样**一辈子只会弹一次框**。
    // -----------------------------------------------------------------------
    private fun authPrefs() = Graph.appContext.getSharedPreferences(
        "adb_auth", android.content.Context.MODE_PRIVATE
    )

    private fun isAuthorized(): Boolean =
        authPrefs().getBoolean(KEY_AUTHORIZED, false)

    private fun markAuthorized(ok: Boolean) {
        authPrefs().edit().putBoolean(KEY_AUTHORIZED, ok).apply()
    }

    private fun loadOrCreateKeyPair(): KeyPair {
        val f = keyFile()
        if (f.exists() && f.length() > 0) {
            try {
                val priv = KeyFactory.getInstance("RSA")
                    .generatePrivate(PKCS8EncodedKeySpec(f.readBytes()))
                // 公钥要从私钥**正确**重建：RSAPrivateCrtKey 自带 modulus 和
                // publicExponent。我原来把指数写成 null，生成出一个废公钥——
                // 那样发到 banner 里 adbd 只会报 Invalid base64 key。
                val crt = priv as java.security.interfaces.RSAPrivateCrtKey
                val pub = KeyFactory.getInstance("RSA").generatePublic(
                    java.security.spec.RSAPublicKeySpec(crt.modulus, crt.publicExponent)
                )
                return KeyPair(pub, priv)
            } catch (e: Throwable) {
                Log.w(TAG, "已有私钥读不了，重新生成: ${e.message}")
                f.delete()
            }
        }
        // **必须是 4096 位。**
        // AOSP adb_auth_client.cpp 的 read_keys()：
        //     ret = __b64_pton(buf, keybuf, sizeof(keybuf));
        //     if (ret != ANDROID_PUBKEY_ENCODED_SIZE) { D("Invalid base64 data"); continue; }
        // 长度必须**恰好等于** ANDROID_PUBKEY_ENCODED_SIZE，而那个常量是按
        // 512 字节 modulus 定的（`ANDROID_PUBKEY_MODULUS_SIZE = 512`），也就是
        // **只认 RSA-4096**。我原来用 2048，解出来 267 字节，恒不等于，
        // 于是每一把都判无效 —— 这正是「点了无数次还在等确认」的根因之一。
        // 实测参照：真实的 `~/.android/adbkey.pub` 解出来 524 字节。
        val g = KeyPairGenerator.getInstance("RSA")
        g.initialize(4096)
        val kp = g.generateKeyPair()
        f.writeBytes(kp.private.encoded)
        Log.i(TAG, "已生成新的 ADB 主机密钥（RSA-4096）并落盘")
        return kp
    }

    // -----------------------------------------------------------------------
    // 协议读写
    // -----------------------------------------------------------------------

    private class Io(private val `in`: InputStream, private val out: OutputStream) {

        @Synchronized
        fun write(cmd: ByteArray, arg0: Int, arg1: Int, payload: ByteArray) {
            val magic = intOf(cmd) xor -1
            val head = java.nio.ByteBuffer.allocate(24).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            head.put(cmd).putInt(arg0).putInt(arg1)
                .putInt(payload.size).putInt(0)   // data_crc32：可选，实际填 0
                .putInt(magic)
            out.write(head.array())
            if (payload.isNotEmpty()) out.write(payload)
            out.flush()
        }

        /** 读一条消息，返回 command 字段；后续字段由 [readArg0]/[readPayload] 取。 */
        fun readCommand(): ByteArray {
            val head = readFully(24)
            val bb = java.nio.ByteBuffer.wrap(head).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            val cmd = ByteArray(4).also { bb.get(it) }
            // **全部用绝对偏移读**：头是 command(0..4) arg0(4) arg1(8)
            // data_length(12) data_crc32(16) magic(20)。
            // 一旦用顺序读，游标错位会把 arg0 当成 magic，每条消息都判失败。
            lastArg0 = bb.getInt(4)
            lastArg1 = bb.getInt(8)
            val len = bb.getInt(12)
            val crc = bb.getInt(16)
            val magic = bb.getInt(20)
            val expect = intOf(cmd) xor -1
            if (magic != expect) {
                error("magic 不对：收到 0x%08X 期望 0x%08X".format(magic, expect))
            }
            lastPayload = if (len > 0) readFully(len) else ByteArray(0)
            if (len > 0 && crc != 0 && crc.toLong() and 0xFFFFFFFFL != crc32(lastPayload)) {
                Log.w(TAG, "data_crc32 校验不过")
            }
            return cmd
        }

        var lastArg0 = 0
            private set
        var lastArg1 = 0
            private set
        var lastPayload = ByteArray(0)
            private set

        fun readArg0() = lastArg0
        fun readArg1() = lastArg1
        fun readPayload() = lastPayload

        private fun crc32(b: ByteArray): Long =
            java.util.zip.CRC32().apply { update(b) }.value

        private fun readFully(n: Int): ByteArray {
            val b = ByteArray(n)
            var got = 0
            while (got < n) {
                val r = `in`.read(b, got, n - got)
                if (r < 0) error("连接被关闭（要 $n 字节，只剩 $got）")
                got += r
            }
            return b
        }

        fun close() {
            try { out.close() } catch (_: Throwable) {}
            try { `in`.close() } catch (_: Throwable) {}
        }
    }

    /** 一条已握手的 shell 连接。 */
    private class Conn(private val socket: Socket, private val io: Io) {

        var alive = true
            private set

        /**
         * 走 `shell:` 服务跑一条命令。
         *
         * OPEN 之后 adbd 先回 OKAY（arg0=本地 id，arg1=远端 id），
         * 之后 WRTE 收结果、CLSE 表示结束。CLSE 的 arg1 是本地的流 id。
         *
         * **命令必须放在服务名里**：`shell:<命令>\0`。
         * 我原来写成 `shell:\0` 再把命令当 WRTE 发进去 —— 实测那不是"执行命令"，
         * 而是往一个**交互式 shell 的 stdin** 里写字：pty 把命令回显回来
         * （所以 `id -u` 也确实拿到了 `2000\n`，骗过了好几轮），
         * 但交互式 shell 不会退出，于是**永远等不到 CLSE**，每条命令都必然超时。
         *
         * 实测对照（同一把已授权 key、同一条连接）：
         *   服务名 `shell:id -u`      -> WRTE 5 字节 "2000\n" + CLSE，干净结束
         *   服务名 `shell:` + WRTE     -> 立刻 CLSE，0 字节
         * 而且输出就是**原始字节，没有 shell_v2 分帧**（banner 里虽然带了
         * shell_v2，`shell:` 这个服务名走的是老协议）。
         */
        fun shell(command: String, timeoutMs: Int): String {
            val localId = 1
            val svc = ("shell:" + command + "\u0000").toByteArray(Charsets.UTF_8)
            io.write(CMD_OPEN, localId, 0, svc)
            Log.i(TAG, "已发 OPEN 服务名=shell:%s（共 %d 字节）"
                .format(command, svc.size))

            // 先等 OKAY。中间可能夹带 AUTH（adbd 在新流上会再发几个 token），
            // 这些不能当错误，跳过即可。
            var n = 0
            while (true) {
                val cmd = io.readCommand()
                // **必须记**：上一版把这行删了，结果 OPEN 之后一片寂静，
                // 根本看不出 adbd 是没回包、还是在回别的东西（我为此白查一轮）。
                if (n < 6) {
                    Log.i(TAG, "OPEN 阶段收到 %s arg0=%d arg1=%d len=%d"
                        .format(cmd.ascii(), io.readArg0(), io.readArg1(),
                            io.readPayload().size))
                }
                n++
                if (cmd.contentEquals(CMD_OKAY)) break
                if (cmd.contentEquals(CMD_CLSE)) {
                    // arg0=0 才是「对端没开这个服务」的真拒绝；
                    // arg0 是个流 id 说明是**上一条流滞留的 CLSE**（见下面读回环的注释）。
                    // 真拒绝就抛，滞留的就回一条清掉、继续等 OKAY。
                    if (io.readArg0() == 0) error("OPEN 被拒绝：服务不可用")
                    io.write(CMD_CLSE, localId, io.readArg0(), ByteArray(0))
                    continue
                }
                if (cmd.contentEquals(CMD_AUTH)) continue
            }
            // **对方的**本地 id 在 arg0：OKAY 是 [对方id, 我的id] 这个顺序。
            // 第一版取了 arg1（=我的 id），等于把数据写给了自己。
            val remoteId = io.readArg0()

            val sb = StringBuilder()
            var m = 0
            while (true) {
                val cmd = io.readCommand()
                if (m < 6) {
                    Log.i(TAG, "shell 读回 %s arg0=%d arg1=%d len=%d"
                        .format(cmd.ascii(), io.readArg0(), io.readArg1(),
                            io.readPayload().size))
                }
                m++
                when {
                    cmd.contentEquals(CMD_WRTE) -> {
                        val from = io.readArg0()
                        sb.append(String(io.readPayload(), Charsets.UTF_8))
                        // ADB 是**显式确认制**：每收一条 WRTE 必须回一条 OKAY，
                        // 不回对方就认为没送达、不再继续发，表现就是整条命令超时。
                        io.write(CMD_OKAY, localId, from, ByteArray(0))
                    }
                    // **必须回一条 CLSE 才算把流关掉。**
                    // ADB 的流是显式关闭制：对端发来 CLSE 后我方也要回一条，
                    // 对端才认为这条流结束了。我原来直接 break 不回 —— adbd 就把
                    // 这条 CLSE 一直挂着，**下一条流的 OPEN 一开口先撞上它**，
                    // 被我自己的代码判成「OPEN 被拒绝：566」。
                    // 表现正好是：第一条命令好用、第二条永远失败，
                    // 于是 `Android/data` 里永远是「此文件夹为空」。
                    cmd.contentEquals(CMD_CLSE) -> {
                        io.write(CMD_CLSE, localId, io.readArg0(), ByteArray(0))
                        break
                    }
                    cmd.contentEquals(CMD_OKAY) -> Unit
                    // 新流上 adbd 会再发几个 AUTH token，忽略即可（已授过权了）。
                    cmd.contentEquals(CMD_AUTH) -> Unit
                    else -> Log.w(TAG, "shell 收到意外命令 ${cmd.ascii()}")
                }
            }
            // CLSE 的 arg0=对方流 id、arg1=本地流 id，**不含退出码**
            // （老协议里没有退出码，那要 `shell,v2,`）。别拿它判断连接死活：
            // 第一版写 `alive = arg0 == 0`，导致每条命令跑完连接就被判死，
            // 下一条命令又要重握手 —— 又弹一次框。
            socket.soTimeout = timeoutMs
            return sb.toString()
        }

        fun close() {
            alive = false
            io.close()
            try { socket.close() } catch (_: Throwable) {}
        }
    }

    private fun intOf(b: ByteArray): Int =
        java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).int

    private fun ByteArray.ascii(): String =
        String(this, Charsets.US_ASCII).replace('\u0000', '?')
}