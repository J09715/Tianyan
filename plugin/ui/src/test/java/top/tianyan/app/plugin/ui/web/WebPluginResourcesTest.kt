package top.tianyan.app.plugin.ui.web

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WebPluginResourcesTest {
    private val policy = WebPluginPolicy()

    @Test fun servesSnapshotWithSecurityHeadersAndNoNetworkFallback() {
        val input = "hello".toByteArray()
        val files = mutableMapOf("index.html" to input)
        val resources = WebPluginResources(policy, files)
        input[0] = 'X'.code.toByte()
        files.clear()
        val page = resources.response("${policy.origin}/index.html", "GET")
        assertEquals(200, page.statusCode)
        assertEquals("hello", page.data.bufferedReader().readText())
        assertEquals(WebPluginPolicy.CSP, page.responseHeaders["Content-Security-Policy"])
        assertEquals("nosniff", page.responseHeaders["X-Content-Type-Options"])
        listOf("http://127.0.0.1:7980/api/health", "${policy.origin}/missing.js", "file:///etc/passwd").forEach {
            assertEquals(403, resources.response(it, "GET").statusCode)
        }
        assertEquals(403, resources.response("${policy.origin}/index.html", "POST").statusCode)
    }

    @Test fun rejectsInvalidAssetTreesAndOversizedSnapshots() {
        for (files in listOf(emptyMap(), mapOf("index.html" to byteArrayOf(), "../secret" to byteArrayOf()),
            mapOf("index.html" to ByteArray(2 * 1024 * 1024 + 1)))) {
            assertTrue(runCatching { WebPluginResources(policy, files) }.isFailure)
        }
    }

    @Test fun aLiveIndexedSourceOnlyAnswersThroughItsHostResolver() {
        val requested = mutableListOf<String>()
        val resources = WebPluginResources(policy, mapOf("index.html" to "entry".toByteArray())) { path ->
            requested += path
            if (path == "plugin.js") "live".toByteArray() else null
        }
        assertEquals(200, resources.response("${policy.origin}/plugin.js", "GET").statusCode)
        assertEquals(200, resources.response("${policy.origin}/index.html", "GET").statusCode)
        assertEquals(403, resources.response("${policy.origin}/missing.png", "GET").statusCode)
        assertEquals(403, resources.response("${policy.origin}/plugin.js", "POST").statusCode)
        assertEquals(403, resources.response("http://127.0.0.1:7980/api/health", "GET").statusCode)
        assertEquals(listOf("plugin.js", "missing.png"), requested)
    }

    @Test fun hostThemeHasItsOwnBoundedAllowanceAtThePayloadLimit() {
        val resources = WebPluginResources(policy, mapOf("index.html" to ByteArray(2 * 1024 * 1024), "theme.css" to ByteArray(4096)))
        assertEquals(200, resources.response("${policy.origin}/index.html", "GET").statusCode)
        assertTrue(runCatching {
            WebPluginResources(policy, mapOf("index.html" to byteArrayOf(), "theme.css" to ByteArray(4097)))
        }.isFailure)
    }

    @Test fun realBundledExampleContainsItsScriptAndStyles() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        val assets = listOf("index.html", "plugin.js", "plugin.css").associateWith { name ->
            context.assets.open("tianyan-web-demo/$name").use { it.readBytes() }
        }
        val resources = WebPluginResources(policy, assets)
        for (name in assets.keys) assertEquals(200, resources.response("${policy.origin}/$name", "GET").statusCode)
        assertTrue(assets.getValue("index.html").toString(Charsets.UTF_8).contains("src=\"plugin.js\""))
    }
}
