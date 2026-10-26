package top.tianyan.app.plugin.ui.web

import org.junit.Assert.*
import org.junit.Test

class WebPluginPolicyTest {
    private val policy = WebPluginPolicy("https://example.plugin.tianyan.invalid")
    @Test fun onlyExactDocumentAndLocalAssetsAreAccepted() {
        assertTrue(policy.canNavigate("${policy.origin}/index.html"))
        assertEquals("images/test.png", policy.assetPath("${policy.origin}/images/test.png"))
        assertFalse(policy.canNavigate("${policy.origin}/plugin.js"))
    }
    @Test fun rejectsOriginConfusionTraversalEncodedPathsAndExternalSchemes() {
        listOf(
            "http://example.plugin.tianyan.invalid/index.html", "https://example.plugin.tianyan.invalid.evil/index.html",
            "${policy.origin}:443/index.html", "https://user@example.plugin.tianyan.invalid/index.html",
            "${policy.origin}/../secret", "${policy.origin}/%2e%2e/secret", "${policy.origin}/a%2fb",
            "${policy.origin}//index.html", "${policy.origin}/index.html?q=1", "${policy.origin}/index.html#other",
            "file:///data/data/secret", "content://example", "javascript:alert(1)", "data:text/html,hello",
            "https://127.0.0.1:7980/api/shell", "https://example.plugin.tianyan.invalid\\@evil/index.html",
        ).forEach { assertNull(it, policy.assetPath(it)) }
    }
    @Test fun everyNewDocumentGetsAnUnsharedOrigin() {
        assertNotEquals(WebPluginPolicy().origin, WebPluginPolicy().origin)
    }
    @Test fun policyForbidsNetworkWorkersAndFraming() {
        assertTrue(WebPluginPolicy.CSP.contains("connect-src 'none'"))
        assertTrue(WebPluginPolicy.CSP.contains("worker-src 'none'"))
        assertTrue(WebPluginPolicy.CSP.contains("frame-src 'none'"))
        assertFalse(WebPluginPolicy.CSP.contains("unsafe-inline"))
    }
}
