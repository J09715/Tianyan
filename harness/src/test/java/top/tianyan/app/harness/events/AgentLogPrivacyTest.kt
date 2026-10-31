package top.tianyan.app.harness.events

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AgentLogPrivacyTest {
    @Test
    fun `android logs never receive original throwable cause or suppressed secrets`() {
        val secret = "synthetic-log-secret"
        val logger = AppLogger(ApplicationProvider.getApplicationContext(), SensitiveDataRedactor {
            it.replace(secret, "[REDACTED]")
        })
        val error = IllegalStateException("outer", IllegalArgumentException(secret)).apply {
            addSuppressed(IllegalStateException(secret))
        }
        ShadowLog.clear()
        logger.e("test", error)
        logger.logAgent("session", "ModelError", "test", error)
        val logs = ShadowLog.getLogsForTag("Tianyan")
        assertTrue(logs.size >= 2)
        logs.forEach {
            assertFalse(it.msg.contains(secret))
            assertTrue(it.msg.contains("[REDACTED]"))
            assertNull(it.throwable)
        }
    }
}
