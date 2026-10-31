package top.tianyan.app.runtime.browser.cdp

import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class DevToolsSocketResolverTest {
    @Test fun `proc permission denial retains process candidate and diagnosis`() {
        val result = DevToolsSocketResolver.discover(42) { throw IOException("Permission denied") }
        assertEquals(listOf("webview_devtools_remote_42"), result.candidates)
        assertEquals("IOException: Permission denied", result.scanError)
    }

    @Test fun `scan deduplicates candidates and keeps current process first`() {
        val result = DevToolsSocketResolver.discover(42) {
            listOf("header", "0 0 @unrelated", "0 0 @webview_devtools_remote_42", "0 0 @webview_devtools_remote_43")
        }
        assertEquals(listOf("webview_devtools_remote_42", "webview_devtools_remote_43"), result.candidates)
        assertNull(result.scanError)
    }
}
