package top.tianyan.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.stringResource
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import top.tianyan.app.feature.settings.R
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.compose.koinInject
import top.tianyan.app.core.tools.AgentProviderCatalog
import top.tianyan.app.core.tools.AgentProviderDefinition
import top.tianyan.app.core.tools.InstalledPluginCatalog

@Composable
internal fun livePluginProviders(preview: List<AgentProviderDefinition>, saved: String? = null): List<AgentProviderDefinition> {
    if (LocalInspectionMode.current) return preview
    val installed = koinInject<InstalledPluginCatalog>()
    val catalog = koinInject<AgentProviderCatalog>()
    val packages by installed.installed.collectAsStateWithLifecycle()
    return remember(catalog, packages, saved) {
        val available = catalog.providers
        if (saved?.startsWith("plugin.") == true && available.none { it.id == saved }) available + catalog.find(saved)
        else available
    }
}

@Composable
internal fun LocalPluginProviderNotice() {
    Text(stringResource(R.string.package_provider_local_notice), style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
}
