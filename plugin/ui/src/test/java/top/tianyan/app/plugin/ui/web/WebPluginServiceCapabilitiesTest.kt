package top.tianyan.app.plugin.ui.web

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.plugin.runtime.PluginCapabilityInput

class WebPluginServiceCapabilitiesTest {
    @Test fun registersOnlyTheTwoReadOnlyServiceCapabilities() {
        val request = WebPluginServiceCapabilities.request { null }
        val stream = WebPluginServiceCapabilities.stream { null }
        assertEquals(PluginCapabilityInput.SERVICE_REQUEST, request.capability)
        assertEquals(PluginCapabilityInput.SERVICE_STREAM, stream.capability)
        assertNotNull(request.execute)
        assertNull(request.stream)
        assertNull(stream.execute)
        assertNotNull(stream.stream)
        assertTrue(request.allowSession && stream.allowSession)
    }

    @Test fun aPageDefinitionAcceptsBothWithoutDuplicatingAKey() {
        val definition = WebPagePlugin("example.service", "1.0.0", { "Service" }, { "Service" },
            listOf(WebPluginServiceCapabilities.request { null }, WebPluginServiceCapabilities.stream { null })) { error("no assets") }
        assertEquals(listOf(PluginCapabilityInput.SERVICE_REQUEST, PluginCapabilityInput.SERVICE_STREAM),
            definition.capabilities.map { it.capability })
    }
}
