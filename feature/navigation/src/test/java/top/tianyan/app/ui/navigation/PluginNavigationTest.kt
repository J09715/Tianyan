package top.tianyan.app.ui.navigation

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.koin.dsl.koinApplication
import top.tianyan.app.di.feature.navigation.navigationModule
import top.tianyan.app.plugin.api.PluginExtensionRef
import top.tianyan.app.plugin.ui.PluginPages
import top.tianyan.app.plugin.ui.web.WebPluginDemo
import top.tianyan.app.ui.terminal.TerminalPlugin
import top.tianyan.app.ui.browser.BrowserPlugin

class PluginNavigationTest {
    @Test
    fun realCompositionResolvesTerminalWithoutCreatingPtyOrViewModel() {
        val application = koinApplication {
            allowOverride(false)
            modules(navigationModule)
        }
        try {
            val pages = application.koin.get<PluginPages>()
            assertSame(pages, application.koin.get<PluginPages>())
            assertNotNull(pages.catalog.plugin(TerminalPlugin.page.pluginId))
            assertNotNull(pages.resolve(TerminalPlugin.page))
            assertNotNull(pages.resolve(BrowserPlugin.page))
            val platform = application.koin.get<NativePluginPlatform>()
            assertNotNull(pages.resolve(WebPluginDemo.page))
            assertNotNull(pages.resolve(WebPluginDemo.managedPage))
            assertNotNull(pages.resolve(WebPluginDemo.prootPage))
            platform.plugins.forEach { plugin ->
                assertTrue(plugin.entries.isNotEmpty())
                plugin.entries.forEach { entry ->
                    assertNotNull(pages.resolve(PluginExtensionRef(plugin.descriptor.id, entry.pageId)))
                }
            }
            assertNull(pages.resolve(PluginExtensionRef("test.absent", "main")))
        } finally {
            application.close()
        }
    }

    @Test
    fun genericPageDestinationRestoresIdentityAndArguments() {
        val destination = PluginPageDestination("tianyan.terminal", "main", mapOf("project" to "项目/a b"))
        val encoded = Json.encodeToString(PluginPageDestination.serializer(), destination)
        assertEquals(destination, Json.decodeFromString(PluginPageDestination.serializer(), encoded))
    }

    @Test
    fun savedTerminalRouteStillAcceptsOldFields() {
        val restored = Json.decodeFromString(TerminalDestination.serializer(), """{"toolId":"bash","project":"existing"}""")
        assertEquals(TerminalDestination("bash", "existing"), restored)
    }

    @Test fun pluginManagerRouteCanBeRestoredWithoutAnExecutableAction() {
        val encoded = Json.encodeToString(PluginManagerDestination.serializer(), PluginManagerDestination)
        assertEquals(PluginManagerDestination, Json.decodeFromString(PluginManagerDestination.serializer(), encoded))
    }

    @Test fun externalWebRoutePinsIdentityAndVersionWithoutPayloadOrPermissions() {
        val route = PluginPageDestination("example.web-info", "main", mapOf("web.package.id" to "web-info", "web.package.version" to "1.0.0"))
        assertEquals(route, Json.decodeFromString(PluginPageDestination.serializer(), Json.encodeToString(PluginPageDestination.serializer(), route)))
        val manager = Json.encodeToString(WebPluginManagerDestination.serializer(), WebPluginManagerDestination)
        assertEquals(WebPluginManagerDestination, Json.decodeFromString(WebPluginManagerDestination.serializer(), manager))
    }
}
