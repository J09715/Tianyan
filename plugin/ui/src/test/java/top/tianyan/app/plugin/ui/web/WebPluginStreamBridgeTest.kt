package top.tianyan.app.plugin.ui.web

import org.junit.Assert.*
import org.junit.Test

class WebPluginStreamBridgeTest {
    @Test fun oneStreamAtATimeAndChunksCarryTheRequestIdAndTheMoreFlag() {
        val bridge = WebPluginStreamBridge(maxChunkChars = 64, maxTotalChars = 128)
        val sent = mutableListOf<String>()
        assertTrue(bridge.attach("stream-1") { sent += it; true })
        assertFalse(bridge.attach("stream-2") { true })
        assertTrue(bridge.push("hello"))
        assertEquals("""{"id":"stream-1","success":true,"text":"hello","more":true}""", sent.single())
    }

    @Test fun detachingReleasesTheSlotForTheNextStreamAndItsChunks() {
        val bridge = WebPluginStreamBridge()
        val first = mutableListOf<String>()
        val second = mutableListOf<String>()
        assertTrue(bridge.attach("stream-1") { first += it; true })
        assertTrue(bridge.push("a"))
        bridge.detach()
        assertTrue(bridge.attach("stream-2") { second += it; true })
        assertTrue(bridge.push("b"))
        assertEquals(1, first.size)
        assertEquals("""{"id":"stream-2","success":true,"text":"b","more":true}""", second.single())
    }

    @Test fun oversizedPerChunkAndOverBudgetPushesAreRejected() {
        val bridge = WebPluginStreamBridge(maxChunkChars = 64, maxTotalChars = 128)
        var sent = 0
        assertTrue(bridge.attach("stream-1") { sent++; true })
        assertFalse(bridge.push(""))
        assertFalse(bridge.push("x".repeat(65)))
        assertTrue(bridge.push("x".repeat(64)))
        assertTrue(bridge.push("y".repeat(64)))
        assertFalse(bridge.push("z"))
        assertEquals(2, sent)
    }

    @Test fun anUnboundClosedOrDeclinedChannelStopsTheProducer() {
        val bridge = WebPluginStreamBridge()
        assertFalse(bridge.push("orphan"))
        assertTrue(bridge.attach("stream-1") { false })
        assertFalse(bridge.push("chunk"))
        bridge.close()
        assertFalse(bridge.attach("stream-2") { true })
        assertFalse(bridge.push("chunk"))
    }

    @Test fun budgetsOutsideTheDocumentedBoundsAreRejected() {
        assertTrue(runCatching { WebPluginStreamBridge(maxChunkChars = 32) }.isFailure)
        assertTrue(runCatching { WebPluginStreamBridge(maxChunkChars = 64, maxTotalChars = 32) }.isFailure)
        assertTrue(runCatching { WebPluginStreamBridge(maxChunkChars = 64, maxTotalChars = 65_537) }.isFailure)
    }
}
