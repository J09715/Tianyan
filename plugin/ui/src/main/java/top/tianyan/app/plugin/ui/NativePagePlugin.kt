package top.tianyan.app.plugin.ui

import androidx.compose.runtime.Composable
import java.util.Collections
import top.tianyan.app.plugin.api.PluginDescriptor
import top.tianyan.app.plugin.runtime.PluginActivation
import top.tianyan.app.ui.components.RuntimeIconName

/** Host navigation and ViewModel owners stay in the enclosing composition. */
class PluginPageRequest(
    arguments: Map<String, String> = emptyMap(),
    val embedded: Boolean = false,
    val onBack: () -> Unit,
    val activation: PluginActivation? = null,
) {
    val arguments: Map<String, String> = Collections.unmodifiableMap(arguments.toMap())
}

class NativePluginPage(
    val id: String,
    val content: @Composable (PluginPageRequest) -> Unit,
)

/** Trusted application code supplies implementations; metadata never loads executable classes. */
class NativePagePlugin(
    val descriptor: PluginDescriptor,
    val pages: List<NativePluginPage>,
    val title: @Composable () -> String = { descriptor.id },
    val entries: List<NativePluginEntry> = emptyList(),
    val activate: suspend (PluginActivation) -> Unit = {},
    val icon: RuntimeIconName = RuntimeIconName.Extension,
    val description: @Composable () -> String = { "" },
)

enum class PluginEntryLocation { MENU, SETTINGS }

class NativePluginEntry(
    val id: String,
    val pageId: String,
    val location: PluginEntryLocation,
    val title: @Composable () -> String,
)
