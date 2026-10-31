package top.tianyan.app.ui.settings

import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.model.ToolState

/** Presentation only: retain manifests, receipts and installed-tool operations. */
internal object PluginCenterCatalogPolicy {
    // Warehouse-delivered plugins and legacy aliases no longer advertise a second installer here.
    private val libraryManagedIds = setOf(
        "rtk", "rtk-suite-offline", "llama", "llama-cpp",
        "qemu-x86-64-compat", "cc-switch", "android-skills", "github-web", "agent2api",
    )

    fun visibleTools(tools: List<ToolEntity>, category: String): List<ToolEntity> = tools.filter { tool ->
        val installed = tool.state in setOf(ToolState.INSTALLED.name, ToolState.UPDATE_AVAILABLE.name, ToolState.INSTALLING.name) ||
            (tool.installedVersion != null && tool.state != ToolState.UNINSTALLED.name)
        when {
            category == "INSTALLED" -> installed
            tool.id in libraryManagedIds && tool.state !in setOf(ToolState.INSTALLING.name, ToolState.FAILED.name) -> false
            category == "ALL" -> true
            else -> tool.category.equals(category, ignoreCase = true)
        }
    }
}
