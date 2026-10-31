package top.tianyan.app.runtime.browser.cdp

import android.content.pm.PackageInfo
import android.os.Looper
import android.webkit.WebView
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [WebViewDebuggingTest.DebugWebViewShadow::class])
class WebViewDebuggingTest {
    @Implements(WebView::class)
    class DebugWebViewShadow {
        companion object {
            val calls = mutableListOf<Boolean>()
            var failNext = false
            @Implementation @JvmStatic
            fun setWebContentsDebuggingEnabled(enabled: Boolean) {
                check(Looper.myLooper() == Looper.getMainLooper())
                calls += enabled
                if (failNext) {
                    failNext = false
                    throw IllegalStateException("provider unavailable")
                }
            }
            @Implementation @JvmStatic
            fun getCurrentWebViewPackage(): PackageInfo? = null
        }
    }

    @Before fun reset() {
        DebugWebViewShadow.calls.clear()
        DebugWebViewShadow.failNext = false
    }

    @Test fun `main thread enabling completes and can be reapplied`() = runBlocking {
        WebViewDebugging.setEnabled(true)
        WebViewDebugging.setEnabled(true)
        WebViewDebugging.setEnabled(false)
        assertEquals(listOf(true, true, false), DebugWebViewShadow.calls)
    }

    @Test fun `provider failure is reported and subsequent attempt succeeds`() {
        DebugWebViewShadow.failNext = true
        val error = assertThrows(IllegalStateException::class.java) {
            runBlocking { WebViewDebugging.setEnabled(true) }
        }
        assertTrue(error.message.orEmpty().contains("IllegalStateException: provider unavailable"))
        assertNotNull(error.cause)
        runBlocking { WebViewDebugging.setEnabled(true) }
        assertEquals(listOf(true, true), DebugWebViewShadow.calls)
        assertTrue(WebViewDebugging.diagnostics().contains("enableError=null"))
    }
}
