package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstStreamEventTimeoutTest {
    @Test
    fun `resolveFirstEventTimeout scales with estimated input tokens`() {
        assertEquals(90_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000))
        assertEquals(90_000L, ProviderClient.resolveFirstEventTimeoutMs(40_000))
        assertEquals(150_000L, ProviderClient.resolveFirstEventTimeoutMs(40_001))
        assertEquals(150_000L, ProviderClient.resolveFirstEventTimeoutMs(80_000))
        assertEquals(240_000L, ProviderClient.resolveFirstEventTimeoutMs(80_001))
        assertEquals(240_000L, ProviderClient.resolveFirstEventTimeoutMs(104_122))
    }

    @Test
    fun `configured base raises the floor and zero disables the watchdog`() {
        assertEquals(180_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000, configuredBaseMs = 180_000L))
        assertEquals(180_000L, ProviderClient.resolveFirstEventTimeoutMs(50_000, configuredBaseMs = 180_000L))
        assertEquals(240_000L, ProviderClient.resolveFirstEventTimeoutMs(90_000, configuredBaseMs = 180_000L))
        assertEquals(600_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000, reasoningEnabled = true, configuredBaseMs = 180_000L))
        assertEquals(700_000L, ProviderClient.resolveFirstEventTimeoutMs(1_000, reasoningEnabled = true, configuredBaseMs = 700_000L))
        assertEquals(Long.MAX_VALUE, ProviderClient.resolveFirstEventTimeoutMs(1_000, configuredBaseMs = 0L))
        assertEquals(Long.MAX_VALUE, ProviderClient.resolveFirstEventTimeoutMs(90_000, reasoningEnabled = true, configuredBaseMs = 0L))
    }
}
