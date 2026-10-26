package top.tianyan.app.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class FirstResponseTimeoutPolicyTest {
    @Test fun `missing value stays at the historical 90 seconds`() {
        assertEquals(90, FirstResponseTimeoutPolicy.normalizeSeconds(null))
        assertEquals(90_000L, FirstResponseTimeoutPolicy.timeoutMs(90))
        assertEquals(90_000L, FirstResponseTimeoutPolicy.DEFAULT_TIMEOUT_MS)
    }

    @Test fun `zero and negative values disable the watchdog`() {
        assertEquals(0, FirstResponseTimeoutPolicy.normalizeSeconds(0))
        assertEquals(0, FirstResponseTimeoutPolicy.normalizeSeconds(-30))
        assertEquals(0L, FirstResponseTimeoutPolicy.timeoutMs(0))
    }

    @Test fun `enabled values can rise but not drop below 90 or above 10 minutes`() {
        assertEquals(90, FirstResponseTimeoutPolicy.normalizeSeconds(30))
        assertEquals(180, FirstResponseTimeoutPolicy.normalizeSeconds(180))
        assertEquals(600, FirstResponseTimeoutPolicy.normalizeSeconds(10_000))
        assertEquals(180_000L, FirstResponseTimeoutPolicy.timeoutMs(180))
    }
}
