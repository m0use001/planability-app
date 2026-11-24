package io.github.m0use001.planability.core

import java.net.URI

/**
 * 外链访问的前置校验（2026-11-08）。
 *
 * 为什么必须有：`web_fetch` / `browser_open` / 无头渲染的 URL 是**模型给的**，
 * 而手机里有大量只监听本机的服务（例如本地 llama-server :8081、Syncthing 面板、
 * OpenCode+ 的工作台），同一局域网里还有路由后台、NAS、其它设备。让模型随手抓
 * `http://127.0.0.1:8081/...` 等于给它一条读本机数据的通道——这类请求叫 SSRF，
 * 在"AI 能自己决定去哪抓"的架构里是真实的攻击面，不是理论风险。
 *
 * 规则（只做**字面**判定，不做 DNS 解析——纯函数、可单测、无网络副作用）：
 * - 只允许 http / https（file://、content://、javascript: 一律拒）；
 * - 拒 localhost / *.localhost / *.local / 全数字主机名；
 * - 拒 IPv4 环回 127/8、私网 10/8、172.16/12、192.168/16、链路本地 169.254/16、
 *   运营商 NAT 100.64/10、0/8、组播与保留 224+；
 * - 拒 IPv6 环回 ::1、链路本地 fe80::/10、唯一本地 fc00::/7。
 *
 * 局限（写在明处）：域名解析到私网（DNS rebinding）不在字面判定能覆盖的范围里。
 * 要堵死得在连接层比对解析后的 IP——当前工具面是"用户自己的手机上让 AI 帮忙查资料"，
 * 字面层已经拦掉模型最容易犯的那一类（直接写 IP / localhost）。
 */
object SafeUrl {

    fun isAllowed(raw: String): Boolean {
        val s = raw.trim()
        if (s.isEmpty()) return false
        val uri = runCatching { URI(s) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = uri.host?.lowercase()?.trimEnd('.')?.trim('[', ']') ?: return false
        if (host.isEmpty()) return false

        // 主机名形态
        if (host == "localhost" || host.endsWith(".localhost") || host.endsWith(".local")) return false
        if (host.all { it.isDigit() }) return false          // 2130706433 这类十进制 IP
        if (host.startsWith("0x")) return false

        // IPv4 字面量
        val m = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$").find(host)
        if (m != null) {
            val a = m.groupValues[1].toInt()
            val b = m.groupValues[2].toInt()
            if (a == 0 || a == 10 || a == 127 || a >= 224) return false
            if (a == 169 && b == 254) return false
            if (a == 172 && b in 16..31) return false
            if (a == 192 && b == 168) return false
            if (a == 100 && b in 64..127) return false
            return true
        }

        // IPv6 字面量（URI.host 去掉方括号后仍是带冒号的形式）
        if (host.contains(':')) {
            if (host == "::1" || host == "::") return false
            if (host.startsWith("fe80") || host.startsWith("fc") || host.startsWith("fd")) return false
        }
        return true
    }
}
