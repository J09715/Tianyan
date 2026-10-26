package top.tianyan.app.ui.navigation

import androidx.compose.runtime.Composable
import top.tianyan.app.ui.settings.ToolDetailScreen
import top.tianyan.app.ui.settings.ToolSelfHealingHelper

/** Tool detail route wrapper; keeps [TianyanNavHost] slim while owning the healing prompt build. */
@Composable
internal fun ToolDetailRoute(
    destination: ToolDetailDestination,
    onBack: () -> Unit,
    onLaunchTerminal: (String) -> Unit,
    onHealing: (toolName: String, prompt: String) -> Unit,
    onOpenPluginPage: (String) -> Unit,
) {
    ToolDetailScreen(
        toolId = destination.toolId,
        onBack = onBack,
        onLaunchTerminal = onLaunchTerminal,
        onStartAiHealing = { toolId, toolName, logs ->
            onHealing(toolName, ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs))
        },
        onOpenPluginPage = onOpenPluginPage,
    )
}
