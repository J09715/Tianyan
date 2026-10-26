package top.tianyan.app.harness

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FirstResponseTimeoutMessageTest {
    @Test fun `failure text keeps a timeout distinct from a generic execution error`() {
        val timeout = FirstResponseTimeoutException.fallbackMessage(90_000L)
        assertTrue(timeout.startsWith(FIRST_RESPONSE_TIMEOUT_MARK))
        assertEquals(timeout, presentRunFailure(timeout))
        assertEquals(timeout, presentExecutionFault(timeout))
        assertEquals("❌ 执行失败：上游返回 500", presentRunFailure("上游返回 500"))
        assertEquals("❌ 执行异常：连接被重置", presentExecutionFault("连接被重置"))
        assertFalse(timeout.contains("执行异常"))
        assertFalse(timeout.contains("LLM 请求失败"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "zh-rCN")
    fun `chinese resource describes a local wait`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.getString(R.string.harness_first_response_timeout, 90)
        assertTrue(text, text.startsWith(FIRST_RESPONSE_TIMEOUT_MARK))
        assertTrue(text, text.contains("90"))
        assertTrue(text, text.contains("上游没有返回错误"))
        assertFalse(text, text.contains("执行异常"))
    }

    @Test
    @Config(sdk = [34], qualifiers = "en")
    fun `english resource describes a local wait`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val text = context.getString(R.string.harness_first_response_timeout, 180)
        assertTrue(text.startsWith(FIRST_RESPONSE_TIMEOUT_MARK))
        assertTrue(text.contains("180"))
        assertTrue(text.contains("not an upstream error"))
        assertFalse(text.contains("执行异常"))
    }

    @Test fun `socket timeout before any event stays a transport failure`() {
        val error = streamFailure(SocketTimeoutException("timeout"), ProviderClient.FIRST_EVENT_WAITING, 90_000L, 90_000L)
        assertTrue(error is SocketTimeoutException)
        assertFalse(error.message.orEmpty().startsWith(FIRST_RESPONSE_TIMEOUT_MARK))
    }
}
