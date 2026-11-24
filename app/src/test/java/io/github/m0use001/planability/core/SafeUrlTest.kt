package io.github.m0use001.planability.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 外链安全前置的判据锁（2026-11-08）。
 *
 * 背景：`web_fetch` / `browser_open` / 无头渲染的 URL 由**模型**决定。手机上跑着本地模型服务
 * （:8081）、Syncthing 面板、局域网设备——放任模型抓 `127.0.0.1` / `192.168.x` 等于给它开一条
 * 读本机/内网数据的通道（SSRF）。这条规则拦住模型最容易犯的那一类：直接写 IP 或 localhost。
 *
 * 局限（也写在实现注释里）：DNS rebinding 不在字面判定范围；需要连接层 IP 比对才能彻底堵。
 */
class SafeUrlTest {

    @Test
    fun `公网 http 与 https 放行`() {
        assertTrue(SafeUrl.isAllowed("https://wiki.biligame.com/bh3/主线章节"))
        assertTrue(SafeUrl.isAllowed("http://example.com/a/b?c=1"))
        assertTrue(SafeUrl.isAllowed("https://www.zhihu.com/question/1"))
    }

    @Test
    fun `本机与环回收敛`() {
        assertFalse(SafeUrl.isAllowed("http://localhost:8081/"))
        assertFalse(SafeUrl.isAllowed("http://LOCALHOST/x"))
        assertFalse(SafeUrl.isAllowed("http://app.localhost/"))
        assertFalse(SafeUrl.isAllowed("http://127.0.0.1:8081/v1/models"))
        assertFalse(SafeUrl.isAllowed("http://127.1.2.3/"))
        assertFalse(SafeUrl.isAllowed("http://[::1]/"))
    }

    @Test
    fun `私有网段与链路本地收敛`() {
        assertFalse(SafeUrl.isAllowed("http://10.0.0.5/"))
        assertFalse(SafeUrl.isAllowed("http://172.16.3.4/"))
        assertFalse(SafeUrl.isAllowed("http://172.31.255.1/"))
        assertFalse(SafeUrl.isAllowed("http://192.168.1.1/"))
        assertFalse(SafeUrl.isAllowed("http://169.254.1.1/"))
        assertFalse(SafeUrl.isAllowed("http://100.64.0.1/"))
        assertFalse(SafeUrl.isAllowed("http://0.0.0.0/"))
        assertFalse(SafeUrl.isAllowed("http://224.0.0.1/"))
    }

    @Test
    fun `公网相邻地址不被误杀`() {
        assertTrue(SafeUrl.isAllowed("http://172.32.0.1/"))
        assertTrue(SafeUrl.isAllowed("http://172.15.0.1/"))
        assertTrue(SafeUrl.isAllowed("http://11.0.0.1/"))
        assertTrue(SafeUrl.isAllowed("http://8.8.8.8/"))
    }

    @Test
    fun `非 http 协议与畸形串收敛`() {
        assertFalse(SafeUrl.isAllowed("file:///sdcard/DCIM/a.jpg"))
        assertFalse(SafeUrl.isAllowed("javascript:alert(1)"))
        assertFalse(SafeUrl.isAllowed("ftp://example.com/a"))
        assertFalse(SafeUrl.isAllowed(""))
        assertFalse(SafeUrl.isAllowed("not a url"))
    }

    @Test
    fun `十进制 IP 与十六进制写法收敛`() {
        assertFalse(SafeUrl.isAllowed("http://2130706433/"))
        assertFalse(SafeUrl.isAllowed("http://0x7f000001/"))
    }
}
