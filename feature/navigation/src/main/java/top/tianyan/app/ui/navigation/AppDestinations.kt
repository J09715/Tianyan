package top.tianyan.app.ui.navigation

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable
sealed interface AppDestination : NavKey

@Serializable data object HomeDestination : AppDestination
@Serializable data object AgentDestination : AppDestination
@Serializable data object WorkspaceDestination : AppDestination
@Serializable data object WorkshopSettingsDestination : AppDestination
@Serializable data object WorkshopEnvironmentSettingsDestination : AppDestination
@Serializable data object WorkshopSigningSettingsDestination : AppDestination
@Serializable data class WorkshopScriptEditorDestination(val type: String) : AppDestination
@Serializable data class WorkspaceExplorerDestination(val projectName: String, val initialPath: String = "") : AppDestination
@Serializable data class CodeEditorDestination(val projectName: String, val relativePath: String) : AppDestination
@Serializable data object SettingsDestination : AppDestination
@Serializable data object SettingsSearchDestination : AppDestination
@Serializable data object AgentEcoSettingsDestination : AppDestination
@Serializable data object LinuxEnvSettingsDestination : AppDestination
@Serializable data object AppearanceSettingsDestination : AppDestination
@Serializable data object SystemDevSettingsDestination : AppDestination
@Serializable data object AboutCommunityDestination : AppDestination
@Serializable data object SponsorDestination : AppDestination
@Serializable data object AgentSettingsDestination : AppDestination
@Serializable data object AgentSubagentSettingsDestination : AppDestination
@Serializable data object AgentSkillSettingsDestination : AppDestination
@Serializable data object McpSettingsDestination : AppDestination
@Serializable data object ToolCenterDestination : AppDestination
@Serializable data object RemotePluginLibraryDestination : AppDestination
@Serializable data object CcSwitchDestination : AppDestination
@Serializable data class ToolDetailDestination(val toolId: String) : AppDestination
@Serializable data object DistroManagementDestination : AppDestination
@Serializable data object StorageMountSettingsDestination : AppDestination
@Serializable data object StorageUsageDestination : AppDestination
@Serializable data object AppManagementDestination : AppDestination
@Serializable data object EnvironmentVariableSettingsDestination : AppDestination
@Serializable data object SshSettingsDestination : AppDestination
@Serializable data object FtpSettingsDestination : AppDestination
@Serializable data object ModelProfilesDestination : AppDestination
@Serializable data object LocalLlmDestination : AppDestination
@Serializable data object PhoneAgentSettingsDestination : AppDestination
@Serializable data class ModelEditorDestination(val modelId: String? = null) : AppDestination
@Serializable data object QuickPhrasesDestination : AppDestination
@Serializable data object StatsDestination : AppDestination
@Serializable data object PermissionGuideDestination : AppDestination
@Serializable data object DeveloperDestination : AppDestination
@Serializable data object AdbLogcatDestination : AppDestination
@Serializable data object A2uiPocDestination : AppDestination
@Serializable data object FeatureHubDestination : AppDestination
@Serializable data object CustomIterationDestination : AppDestination
@Serializable data class TerminalDestination(val toolId: String = "", val project: String = "") : AppDestination
@Serializable data object BrowserDestination : AppDestination
@Serializable data class GitRepositoryDestination(val projectName: String) : AppDestination
@Serializable data object BotsDestination : AppDestination
@Serializable data class BotDetailDestination(val botId: String) : AppDestination
@Serializable data class GroupChatDestination(val groupId: String) : AppDestination
@Serializable data class WorkflowDestination(
    val projectName: String = "",
    val workflowId: String? = null,
    val initialVariables: Map<String, String> = emptyMap(),
    // 通知栏深链：进入工作流页后直接定位到该执行的运行视图
    val executionId: String? = null,
) : AppDestination

@Serializable data class PluginPageDestination(val pluginId: String, val pageId: String, val arguments: Map<String, String> = emptyMap()) : AppDestination
@Serializable data object PluginManagerDestination : AppDestination
@Serializable data object WebPluginManagerDestination : AppDestination
