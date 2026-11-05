package top.tianyan.app.core.common.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 局域网端点放行的回归测试。
 *
 * 这组用例锁定「哪些主机允许明文 http」的口径：放太宽会把 API Key 泄到公网，
 * 放太窄会让 Ollama / LM Studio / one-api 这类局域网服务全部连不上。
 */
class PrivateNetworkHostsTest {

    @Test
    fun loopbackHostsAreCleartext() {
        assertTrue(PrivateNetworkHosts.isLoopback("localhost"))
        assertTrue(PrivateNetworkHosts.isLoopback("127.0.0.1"))
        assertTrue(PrivateNetworkHosts.isLoopback("127.8.9.10"))
        assertTrue(PrivateNetworkHosts.isLoopback("::1"))
        assertTrue(PrivateNetworkHosts.isLoopback("[::1]"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("localhost"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("127.0.0.1"))
    }

    @Test
    fun privateIpv4RangesAreCleartext() {
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("192.168.1.100"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("192.168.31.42"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("10.0.0.5"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("172.16.0.9"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("172.31.255.254"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("169.254.10.20"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("100.64.1.2"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("100.127.255.1"))
    }

    @Test
    fun mdnsAndPrivateSuffixesAreCleartext() {
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("ollama.local"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("gateway.lan"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("nas.internal"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("router.home.arpa"))
    }

    @Test
    fun ipv6UlaAndLinkLocalAreCleartext() {
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("fd00::1"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("[fd12:3456::1]"))
        assertTrue(PrivateNetworkHosts.isPrivateOrLoopback("fe80::1"))
    }

    @Test
    fun publicHostsAreNotCleartext() {
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("api.openai.com"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("103.233.252.180"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("172.32.0.1"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("192.169.0.1"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("100.128.0.1"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("8.8.8.8"))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback(""))
        assertFalse(PrivateNetworkHosts.isPrivateOrLoopback("999.1.1.1"))
    }
}
