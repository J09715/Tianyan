package top.tianyan.app.runtime.remote

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import java.net.ConnectException
import java.net.ServerSocket

class SshLanRouteTest {
    @Test fun `only site local and link local addresses bind to an underlying network`() {
        listOf(
            "192.168.10.8",
            "10.0.0.1",
            "172.16.0.1",
            "172.31.255.255",
            "169.254.1.8",
            "fe80::1",
            "[fe80::1]",
            "fd12:3456::1",
            "fc00::1",
            "::ffff:192.168.10.8",
        ).forEach { host ->
            assertEquals(host, DirectConnectKind.LAN, directConnectKind(host))
            assertTrue(host, bindsUnderlyingLan(host))
        }
        listOf("127.0.0.1", "::1", "localhost", "LOCALHOST").forEach { host ->
            assertEquals(host, DirectConnectKind.LOOPBACK, directConnectKind(host))
            assertFalse(bindsUnderlyingLan(host))
        }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "100.64.0.1", "example.test", "192.168.10.8.example", "").forEach { host ->
            assertNull(host, directConnectKind(host))
            assertFalse(bindsUnderlyingLan(host))
        }
    }

    @Test fun `underlying network must be wifi or ethernet and not a vpn`() {
        assertTrue(isUnderlyingLan(notVpn = true, vpnTransport = false, wifi = true, ethernet = false))
        assertTrue(isUnderlyingLan(notVpn = true, vpnTransport = false, wifi = false, ethernet = true))
        assertFalse(isUnderlyingLan(notVpn = false, vpnTransport = false, wifi = true, ethernet = false))
        assertFalse(isUnderlyingLan(notVpn = true, vpnTransport = true, wifi = true, ethernet = false))
        assertFalse(isUnderlyingLan(notVpn = true, vpnTransport = false, wifi = false, ethernet = false))
    }

    @Test fun `vpn lan target binds before connect and does not fall back after a failure`() {
        val order = mutableListOf<String>()
        val opener = LanSocketOpener(
            networks = { LanNetwork(vpnActive = true, bind = { order += "bind" }) },
            connectSocket = { _, host, port, _ ->
                order += "connect:$host:$port"
                throw ConnectException("Connection refused")
            },
        )
        val factory = opener.socketFactory("192.168.10.8", 1_500)!!
        try {
            factory.createSocket("192.168.10.8", 8022)
            fail()
        } catch (e: ConnectException) {
            assertEquals(listOf("bind", "connect:192.168.10.8:8022"), order)
        }
    }

    @Test fun `missing or inactive vpn network keeps the default route`() {
        var probes = 0
        val idle = LanSocketOpener(networks = { probes++; LanNetwork(vpnActive = false, bind = { error("bind") }) })
        assertNull(idle.socketFactory("192.168.10.8", 1_000))
        val missing = LanSocketOpener(networks = { probes++; null })
        assertNull(missing.socketFactory("10.1.2.3", 1_000))
        val broken = LanSocketOpener(networks = { probes++; error("no connectivity service") })
        assertNull(broken.socketFactory("169.254.1.1", 1_000))
        assertNull(LanSocketOpener(networks = { probes++; error("unused") }).socketFactory("127.0.0.1", 1_000))
        assertEquals(3, probes)
    }

    @Test fun `failed bind falls back to an unbound socket`() {
        var connects = 0
        val opener = LanSocketOpener(
            networks = { LanNetwork(vpnActive = true, bind = { error("stale network") }) },
            connectSocket = { socket, host, _, _ ->
                connects++
                assertEquals("10.0.0.8", host)
                assertTrue(socket.localAddress.isAnyLocalAddress || !socket.isBound)
            },
        )
        opener.socketFactory("10.0.0.8", 1_000)!!.createSocket("10.0.0.8", 8022)
        assertEquals(1, connects)
    }

    @Test fun `connector reports a refused lan attempt without secrets or a host key prompt`() = runBlocking {
        var binds = 0
        val opener = LanSocketOpener(
            networks = { LanNetwork(vpnActive = true, bind = { binds++ }) },
            connectSocket = { _, host, port, _ ->
                assertEquals("192.168.10.8", host)
                assertEquals(8022, port)
                throw ConnectException("Connection refused s3cret-password")
            },
        )
        val connector = SshConnector(opener)
        val server = RemoteServer("lan-phone", "Phone", "192.168.10.8", 8022, username = "root", workspace = "/root")
        try {
            connector.test(server, SshCredentials("s3cret-password", "hunter2-key"))
            fail()
        } catch (e: SshConnectionException) {
            assertEquals(1, binds)
            assertEquals("$SSH_FAILURE_REFUSED。${LocalNetworkAccess.USER_HINT}", e.message)
            assertTrue(e.message!!.contains("附近的设备"))
            assertFalse(e.message!!.contains("s3cret-password"))
            assertFalse(e.message!!.contains("hunter2-key"))
        }
    }

    @Test fun `loopback keeps the process socket instead of the wifi network`() = runBlocking {
        var probes = 0
        val connector = SshConnector(LanSocketOpener(networks = { probes++; error("wifi") }))
        ServerSocket(0).use { listener ->
            val port = listener.localPort
            listener.close()
            val server = RemoteServer("loop", "Loop", "127.0.0.1", port, username = "root", workspace = "/root", fingerprint = "SHA256:unused")
            try {
                connector.test(server, SshCredentials("s3cret-password"))
                fail()
            } catch (e: SshConnectionException) {
                assertEquals(SSH_FAILURE_REFUSED, e.message)
            }
        }
        assertEquals(0, probes)
    }
}
