package top.tianyan.app.runtime.browser.cdp

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LocalSocketCdpTransportTest {
    private class StrictSocket(private val failureStage: String? = null) : LocalSocket() {
        var connected = false
        var closed = false
        var timeout = 0
        override fun connect(endpoint: LocalSocketAddress) {
            if (failureStage == "connect") throw IOException("Connection refused")
            connected = true
        }
        override fun setSoTimeout(n: Int) {
            if (!connected) throw IOException("socket not created")
            if (failureStage == "setSoTimeout") throw IOException("option failure")
            timeout = n
        }
        override fun close() { closed = true }
    }

    @Test fun `connect creates socket before timeout is configured`() {
        val socket = StrictSocket()
        val connection = LocalSocketCdpTransport("webview_devtools_remote_42") { socket }.open(1234)
        assertTrue(socket.connected)
        assertEquals(1234, socket.timeout)
        assertFalse(socket.closed)
        connection.close()
        assertTrue(socket.closed)
    }

    @Test fun `failures close socket and preserve stage and cause`() {
        for (stage in listOf("connect", "setSoTimeout")) {
            val socket = StrictSocket(stage)
            val error = assertThrows(IOException::class.java) {
                LocalSocketCdpTransport("webview_devtools_remote_42") { socket }.open()
            }
            assertTrue(socket.closed)
            assertTrue(error.message.orEmpty().contains("$stage failed: IOException"))
            assertNotNull(error.cause)
        }
    }
}
