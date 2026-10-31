package top.tianyan.app.ui.settings

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.database.ToolEntity

class PluginCenterCatalogPolicyTest {
    private fun tool(id: String, state: String = "AVAILABLE", version: String? = null) = ToolEntity(
        distroId = "debian", id = id, name = id, description = "", dependencies = "", launchType = "cli",
        state = state, installedVersion = version, category = "AI_AGENT")

    @Test fun libraryPluginsDoNotAdvertiseDuplicateInstallers() {
        val entries = listOf("rtk", "rtk-suite-offline", "llama", "llama-cpp", "qemu-x86-64-compat",
            "cc-switch", "android-skills", "github-web", "agent2api").map { tool(it) }
        assertTrue(PluginCenterCatalogPolicy.visibleTools(entries, "ALL").isEmpty())
        assertTrue(PluginCenterCatalogPolicy.visibleTools(entries, "AI_AGENT").isEmpty())
    }

    @Test fun installedLibraryPluginsRemainManageableInInstalledList() {
        val entries = listOf(tool("rtk-suite-offline", "INSTALLED", "1.0.0"),
            tool("llama-cpp", "UPDATE_AVAILABLE", "1.0.0"))
        assertEquals(entries, PluginCenterCatalogPolicy.visibleTools(entries, "INSTALLED"))
        assertTrue(PluginCenterCatalogPolicy.visibleTools(entries, "ALL").isEmpty())
        assertTrue(PluginCenterCatalogPolicy.visibleTools(entries.map { it.copy(state = "UNINSTALLED", installedVersion = null) }, "INSTALLED").isEmpty())
    }

    @Test fun activeAndFailedInstallsRetainProgressAndRepairAccess() {
        val active = tool("rtk-suite-offline", "INSTALLING")
        val failed = tool("llama-cpp", "FAILED")
        assertEquals(listOf(active, failed), PluginCenterCatalogPolicy.visibleTools(listOf(active, failed), "ALL"))
        assertEquals(listOf(active), PluginCenterCatalogPolicy.visibleTools(listOf(active, failed), "INSTALLED"))
    }

    @Test fun otherToolsAndCategoryFiltersRemainAvailable() {
        val entry = tool("example-tool")
        assertEquals(listOf(entry), PluginCenterCatalogPolicy.visibleTools(listOf(entry), "ALL"))
        assertEquals(listOf(entry), PluginCenterCatalogPolicy.visibleTools(listOf(entry), "AI_AGENT"))
        assertTrue(PluginCenterCatalogPolicy.visibleTools(listOf(entry), "CODING_AGENT").isEmpty())
    }
}
