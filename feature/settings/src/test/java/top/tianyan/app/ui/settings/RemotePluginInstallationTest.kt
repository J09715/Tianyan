package top.tianyan.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.tools.RemotePluginRelease

class RemotePluginInstallationTest {
    private val release = RemotePluginRelease("legacy.tool-example", "example", "Example", "", "PROCESS",
        "1.0.0", 1, 1, 100, "0".repeat(64), emptyList(), 1)
    private fun tool(version: String? = "1.0.0", state: String = "INSTALLED") = ToolEntity(
        distroId = "debian", id = "example", name = "Example", description = "", dependencies = "",
        launchType = "cli", state = state, installedVersion = version, manifestVersion = "1.0.0")

    @Test fun installedLegacyPluginDoesNotDependOnSemanticVersionParsing() {
        listOf("1.0.0", "v1.0.0", "release-custom").forEach { version ->
            val result = remotePluginInstallation(release, listOf(tool(version)), emptyList())
            assertTrue(result.installed)
            assertEquals(version, result.version)
            assertFalse(result.updateAvailable)
        }
    }
    @Test fun installationAndUpdatesFollowCurrentSystemRecords() {
        assertFalse(remotePluginInstallation(release, emptyList(), emptyList()).installed)
        assertTrue(remotePluginInstallation(release, listOf(tool()), emptyList()).installed)
        assertTrue(remotePluginInstallation(release.copy(version = "1.0.1"), listOf(tool()), emptyList()).updateAvailable)
        assertFalse(remotePluginInstallation(release, listOf(tool("1.1.0")), emptyList()).updateAvailable)
        assertFalse(remotePluginInstallation(release, listOf(tool(null, "UNINSTALLED")), emptyList()).installed)
        assertFalse(remotePluginInstallation(release, listOf(tool(null, "FAILED")), emptyList()).installed)
        assertTrue(remotePluginInstallation(release, listOf(tool(null, "INSTALLING")), emptyList()).installing)
    }
    @Test fun installedStateWithoutVersionRetainsLegacyInstallation() {
        val result = remotePluginInstallation(release, listOf(tool(null)), emptyList())
        assertTrue(result.installed)
        assertEquals("1.0.0", result.version)
    }
}
