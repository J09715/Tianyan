package top.tianyan.app.plugin.ui.web

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.runtime.PluginCapability
import top.tianyan.app.plugin.ui.NativePluginPageRegistry

class WebPagePluginTest {
    @Test fun registersThroughRealPageCatalogWithoutLoadingAssetsOrExecutingOperations() {
        val definition = WebPagePlugin("example.web", "1.0.0", { "Example" }, { "Example" },
            listOf(WebPluginCapability(PluginCapability("host.app-info", "application"), { "Read" }, { "Read app info" }) { _, _, _ ->
                error("registration must not execute")
            }),
        ) { error("registration must not load assets") }
        val plugin = definition.create()
        val catalog = NativePluginPageRegistry(listOf(plugin))
        assertNotNull(catalog.resolve(PluginExtensionRef("example.web", "main")))
        assertEquals("main", plugin.entries.single().pageId)
    }

    @Test fun rejectsDuplicateCapabilitiesBeforePublishingPage() {
        val capability = WebPluginCapability(PluginCapability("read", "app"), { "Read" }, { "Read" }) { _, _, _ -> "ok" }
        assertTrue(runCatching {
            WebPagePlugin("example.web", "1", { "Example" }, { "Example" }, listOf(capability, capability)) { emptyMap() }
        }.isFailure)
    }
}
