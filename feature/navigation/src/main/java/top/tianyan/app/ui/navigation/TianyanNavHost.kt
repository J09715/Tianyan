package top.tianyan.app.ui.navigation

import org.koin.compose.viewmodel.koinViewModel
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import top.tianyan.app.ui.chat.ChatScreen
import top.tianyan.app.ui.chat.ChatViewModel
import top.tianyan.app.ui.components.MainDestination
import top.tianyan.app.ui.chat.ChatNavigationDrawer
import top.tianyan.app.ui.developer.DeveloperScreen
import top.tianyan.app.ui.developer.AdbLogcatScreen
import top.tianyan.app.ui.home.HomeScreen
import top.tianyan.app.ui.settings.AgentSettingsScreen
import top.tianyan.app.ui.settings.ModelEditorScreen
import top.tianyan.app.ui.settings.ModelProfilesScreen
import top.tianyan.app.ui.settings.LocalLlmScreen
import top.tianyan.app.ui.settings.SettingsScreen
import top.tianyan.app.ui.settings.SettingsViewModel
import top.tianyan.app.ui.iteration.CustomIterationScreen
import top.tianyan.app.plugin.ui.PluginPages
import top.tianyan.app.plugin.runtime.PluginController
import org.koin.compose.koinInject
import top.tianyan.app.ui.workspace.CodeEditorScreen
import top.tianyan.app.ui.workspace.WorkspaceExplorerScreen
import top.tianyan.app.ui.workspace.WorkspaceScreen
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import top.tianyan.app.core.common.navigation.AppNavigationTarget
import top.tianyan.app.core.common.navigation.GlobalNavigationBus
import top.tianyan.app.feature.a2uipoc.A2uiPocScreen
import top.tianyan.app.ui.browser.BrowserViewModel
import top.tianyan.app.ui.bot.BotsScreen
import top.tianyan.app.ui.git.GitScreen
import top.tianyan.app.ui.settings.AboutCommunityScreen
import top.tianyan.app.ui.settings.AgentEcoSettingsScreen
import top.tianyan.app.ui.settings.AgentSettingsCategory
import top.tianyan.app.ui.settings.AppManagementScreen
import top.tianyan.app.ui.settings.AppearanceSettingsScreen
import top.tianyan.app.ui.settings.CcSwitchScreen
import top.tianyan.app.ui.settings.DistroManagementScreen
import top.tianyan.app.ui.settings.EnvironmentVariableSettingsScreen
import top.tianyan.app.ui.settings.FeatureHubScreen
import top.tianyan.app.ui.settings.FtpSettingsScreen
import top.tianyan.app.ui.settings.LinuxEnvironmentSettingsScreen
import top.tianyan.app.ui.settings.McpSettingsScreen
import top.tianyan.app.ui.settings.PhoneAgentSettingsScreen
import top.tianyan.app.ui.settings.QuickPhrasesScreen
import top.tianyan.app.ui.settings.SshSettingsScreen
import top.tianyan.app.ui.settings.StorageMountSettingsScreen
import top.tianyan.app.ui.settings.StorageUsageScreen
import top.tianyan.app.ui.settings.SystemDevSettingsScreen
import top.tianyan.app.ui.settings.ToolCenterScreen
import top.tianyan.app.ui.settings.ToolSelfHealingHelper
import top.tianyan.app.ui.settings.permission.PermissionGuideScreen
import top.tianyan.app.ui.settings.search.SettingsSearchScreen
import top.tianyan.app.ui.settings.search.SettingsSearchTarget
import top.tianyan.app.ui.settings.stats.StatsScreen
import top.tianyan.app.ui.workflow.WorkflowScreen
import top.tianyan.app.ui.workspace.WorkshopEnvironmentSettingsScreen
import top.tianyan.app.ui.workspace.WorkshopScriptEditorScreen
import top.tianyan.app.ui.workspace.WorkshopScriptType
import top.tianyan.app.ui.workspace.WorkshopSettingsScreen
import top.tianyan.app.ui.workspace.WorkshopSigningScreen

/** 天衍核心导航分发系统：采用 Navigation 3，为每个 Tab 独立维护持久回退栈与状态生命周期 */
@Composable
fun TianyanNavHost(
    globalNavigationBus: GlobalNavigationBus? = null,
) {
    // Root tab entries are removed from composition when another tab becomes active. Keep the
    // conversation owner at the Activity scope so switching back to 智枢 does not rebuild Koin's
    // graph, restore the latest session, and restart its initialization skeleton on every visit.
    val chatViewModel: ChatViewModel = koinViewModel()
    val pluginPages: PluginPages = koinInject()
    val pluginPlatform: NativePluginPlatform = koinInject()
    val pluginController: PluginController = koinInject()
    // 浏览器引擎是全局单例：BrowserViewModel 同样挂到 Activity 作用域，
    // 使智枢内嵌浏览器面板与独立浏览器页共享同一份 tab/URL/共浏览状态。
    val browserViewModel: BrowserViewModel = koinViewModel()
    val browserUiState by browserViewModel.uiState.collectAsStateWithLifecycle()
    // SettingsViewModel owns dozens of eagerly shared DataStore/database streams and performs
    // repository initialization. Let the settings navigation graph share the Activity-scoped
    // instance instead of constructing that whole graph once for every Navigation3 entry.
    val settingsViewModel: SettingsViewModel = koinViewModel()
    val activeStack = rememberNavBackStack(AgentDestination)
    var pendingHealingTask by remember { mutableStateOf<HealingTask?>(null) }
    var showNavigation by rememberSaveable { mutableStateOf(false) }; var sidebarDocked by rememberSaveable { mutableStateOf(true) }
    var showNewSession by rememberSaveable { mutableStateOf(false) }
    /** Programmatic stack mutation (bus / workflow). */
    fun NavBackStack<NavKey>.pushRaw(destination: NavKey) {
        if (lastOrNull() != destination) add(destination)
    }

    val sidebarGesturesEnabled = activeStack.canSwipeOpenNavigation()
    LaunchedEffect(sidebarGesturesEnabled) { if (!sidebarGesturesEnabled) showNavigation = false }
    val selectedMain = activeStack.lastOrNull { it == HomeDestination || it == WorkspaceDestination || it == BotsDestination || it == SettingsDestination }.toMainDestination()
    fun navigateMain(destination: MainDestination) {
        if (activeStack.openMainOverlay(destination)) return
        if (destination == MainDestination.Agent) while (activeStack.size > 1) activeStack.removeLastOrNull()
    }

    LaunchedEffect(chatViewModel) {
        chatViewModel.workflowLaunchRequests.collect { request ->
            navigateMain(MainDestination.Agent)
            activeStack.pushRaw(WorkflowDestination(request.projectName, request.workflowId, request.initialVariables))
        }
    }

    LaunchedEffect(globalNavigationBus) {
        globalNavigationBus?.events?.collect { target ->
            when (target) {
                AppNavigationTarget.AdbLogcat -> activeStack.openSettingsPath(SystemDevSettingsDestination, AdbLogcatDestination)
                is AppNavigationTarget.WorkflowRun -> {
                    navigateMain(MainDestination.Agent)
                    activeStack.pushRaw(WorkflowDestination(executionId = target.executionId))
                }
                AppNavigationTarget.AgentSettings -> activeStack.openSettingsPath(AgentSettingsDestination)
                is AppNavigationTarget.SharedText -> {
                    navigateMain(MainDestination.Agent)
                    chatViewModel.onSharedTextReceived(target.text)
                }
                is AppNavigationTarget.AgentSession -> {
                    navigateMain(MainDestination.Agent)
                    val groupId = target.groupId
                    val sessionId = target.sessionId
                    if (groupId != null) activeStack.pushRaw(GroupChatDestination(groupId))
                    else if (!sessionId.isNullOrBlank() && sessionId != "all") chatViewModel.switchSession(sessionId)
                }
            }
            globalNavigationBus.clearLatest(target)
        }
    }

    fun NavBackStack<NavKey>.push(from: NavKey, destination: NavKey) {
        if (lastOrNull() == from && lastOrNull() != destination) {
            add(destination)
        }
    }

    fun popBack() {
        if (activeStack.size > 1) activeStack.removeLastOrNull()
    }

    @Composable
    fun GuardedEntry(
        destination: NavKey,
        content: @Composable () -> Unit,
    ) {
        val isActive = destination == activeStack.lastOrNull()
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isActive) Modifier
                    else Modifier.pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                )
        ) {
            content()
        }
    }

    val appEntryProvider: (NavKey) -> NavEntry<NavKey> = entryProvider {
            entry<HomeDestination> {
                GuardedEntry(HomeDestination) {
                    HomeScreen(
                        onNavigate = ::navigateMain, onBack = { if (activeStack.size > 1) popBack() else navigateMain(MainDestination.Agent) },
                        onOpenTerminal = { activeStack.push(HomeDestination, TerminalDestination()) },
                        onOpenToolCenter = { activeStack.push(HomeDestination, ToolCenterDestination) },
                    )
                }
            }
            entry<AgentDestination> {
                GuardedEntry(AgentDestination) {
                    LaunchedEffect(pendingHealingTask) {
                        pendingHealingTask?.let { task ->
                            chatViewModel.startHealingTask(task.title, task.prompt)
                            pendingHealingTask = null
                        }
                    }
                    ChatScreen(
                        viewModel = chatViewModel,
                        onNavigate = ::navigateMain,
                        onOpenNavigation = { showNavigation = true }, onInstallMcp = { activeStack.pushRaw(ToolCenterDestination) }, onCreateModelProfile = { activeStack.pushRaw(ModelEditorDestination()) },
                        // 内嵌终端面板非独立导航节点，无返回目标：隐藏顶栏返回箭头，避免点击无反馈
                        terminalPane = { project -> TerminalPluginPage(pluginPages, project = project, embedded = true, onBack = {}) },
                        // 内嵌浏览器面板：与独立浏览器页共享 Activity 级 BrowserViewModel，
                        // 手机端左右滑动切换对话/浏览器，宽屏双栏可切"终端/浏览器"
                        browserPane = { onExit -> BrowserPluginPage(pluginPages, embedded = true, onBack = onExit) },
                        browserActivityTick = browserUiState.activityTick,
                        browserBackPressed = { browserPluginBack(pluginController, browserViewModel) },
                        onOpenFile = { projectName, relativePath ->
                            activeStack.push(AgentDestination, CodeEditorDestination(projectName, relativePath))
                        },
                        onOpenRepository = { projectName ->
                            activeStack.push(AgentDestination, GitRepositoryDestination(projectName))
                        },
                    )
                }
            }
            entry<BotsDestination> { GuardedEntry(BotsDestination) { BotsScreen(onBack = { if (activeStack.size > 1) popBack() else navigateMain(MainDestination.Agent) }, onOpenBot = { activeStack.push(BotsDestination, BotDetailDestination(it)) }, onOpenGroup = { activeStack.push(BotsDestination, GroupChatDestination(it)) }) } }
            entry<WorkspaceDestination> {
                GuardedEntry(WorkspaceDestination) {
                    WorkspaceScreen(
                        onNavigate = ::navigateMain, onBack = { if (activeStack.size > 1) popBack() else navigateMain(MainDestination.Agent) },
                        onOpenExplorer = { projectName -> activeStack.push(WorkspaceDestination, WorkspaceExplorerDestination(projectName)) },
                        onOpenTerminal = { project -> activeStack.push(WorkspaceDestination, TerminalDestination(project = project)) },
                        onOpenToolCenter = { activeStack.push(WorkspaceDestination, ToolCenterDestination) },
                        onOpenWorkshopSettings = { activeStack.push(WorkspaceDestination, WorkshopSettingsDestination) },
                        onOpenWorkflows = { projectName -> activeStack.push(WorkspaceDestination, WorkflowDestination(projectName)) },
                    )
                }
            }
            entry<WorkflowDestination> { destination ->
                GuardedEntry(destination) {
                    WorkflowScreen(
                        projectName = destination.projectName,
                        initialWorkflowId = destination.workflowId,
                        initialVariables = destination.initialVariables,
                        initialExecutionId = destination.executionId,
                        onBack = ::popBack,
                    )
                }
            }
            entry<WorkshopSettingsDestination> {
                GuardedEntry(WorkshopSettingsDestination) {
                    WorkshopSettingsScreen(
                        onBack = ::popBack,
                        onOpenEnvironment = { activeStack.push(WorkshopSettingsDestination, WorkshopEnvironmentSettingsDestination) },
                        onOpenSigning = { activeStack.push(WorkshopSettingsDestination, WorkshopSigningSettingsDestination) },
                        onEditScript = { type -> activeStack.push(WorkshopSettingsDestination, WorkshopScriptEditorDestination(type.name)) },
                    )
                }
            }
            entry<WorkshopEnvironmentSettingsDestination> {
                GuardedEntry(WorkshopEnvironmentSettingsDestination) {
                    WorkshopEnvironmentSettingsScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopSigningSettingsDestination> {
                GuardedEntry(WorkshopSigningSettingsDestination) {
                    WorkshopSigningScreen(onBack = ::popBack)
                }
            }
            entry<WorkshopScriptEditorDestination> { destination ->
                GuardedEntry(destination) {
                    WorkshopScriptEditorScreen(
                        type = WorkshopScriptType.valueOf(destination.type),
                        onBack = ::popBack,
                    )
                }
            }
            entry<WorkspaceExplorerDestination> { destination ->
                GuardedEntry(destination) {
                    WorkspaceExplorerScreen(
                        projectName = destination.projectName,
                        initialPath = destination.initialPath,
                        onBack = ::popBack,
                        onOpenFile = { relativePath ->
                            activeStack.push(destination, CodeEditorDestination(destination.projectName, relativePath))
                        },
                        onOpenTerminal = { project ->
                            activeStack.push(destination, TerminalDestination(project = project))
                        },
                    )
                }
            }
            entry<CodeEditorDestination> { destination ->
                GuardedEntry(destination) {
                    CodeEditorScreen(
                        projectName = destination.projectName,
                        relativePath = destination.relativePath,
                        onBack = ::popBack,
                    )
                }
            }
            entry<SettingsDestination> {
                GuardedEntry(SettingsDestination) {
                    SettingsScreen(
                        onNavigate = ::navigateMain, onBack = ::popBack,
                        onOpenAgentEco = { activeStack.push(SettingsDestination, AgentEcoSettingsDestination) },
                        onOpenLinuxEnv = { activeStack.push(SettingsDestination, LinuxEnvSettingsDestination) },
                        onOpenAppearance = { activeStack.push(SettingsDestination, AppearanceSettingsDestination) },
                        onOpenSystemDev = { activeStack.push(SettingsDestination, SystemDevSettingsDestination) },
                        onOpenAboutCommunity = { activeStack.push(SettingsDestination, AboutCommunityDestination) },
                        onOpenSearch = { activeStack.push(SettingsDestination, SettingsSearchDestination) },
                        onOpenFeatureHub = { activeStack.push(SettingsDestination, FeatureHubDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SettingsSearchDestination> {
                GuardedEntry(SettingsSearchDestination) {
                    SettingsSearchScreen(
                        onBack = ::popBack,
                        onNavigateToTarget = { target ->
                            when (target) {
                                SettingsSearchTarget.MODEL_PROFILES -> activeStack.push(SettingsSearchDestination, ModelProfilesDestination)
                                SettingsSearchTarget.MODEL_EDITOR_NEW -> activeStack.push(SettingsSearchDestination, ModelEditorDestination())
                                SettingsSearchTarget.LOCAL_LLM -> activeStack.push(SettingsSearchDestination, LocalLlmDestination)
                                SettingsSearchTarget.QUICK_PHRASES -> activeStack.push(SettingsSearchDestination, QuickPhrasesDestination)
                                SettingsSearchTarget.STATS -> activeStack.push(SettingsSearchDestination, StatsDestination)
                                SettingsSearchTarget.TOOL_CENTER -> activeStack.push(SettingsSearchDestination, ToolCenterDestination)
                                SettingsSearchTarget.CC_SWITCH -> activeStack.push(SettingsSearchDestination, CcSwitchDestination)
                                SettingsSearchTarget.AGENT_EXECUTION -> activeStack.push(SettingsSearchDestination, AgentSettingsDestination)
                                SettingsSearchTarget.AGENT_SUBAGENTS -> activeStack.push(SettingsSearchDestination, AgentSubagentSettingsDestination)
                                SettingsSearchTarget.AGENT_SKILLS -> activeStack.push(SettingsSearchDestination, AgentSkillSettingsDestination)
                                SettingsSearchTarget.MCP_SETTINGS -> activeStack.push(SettingsSearchDestination, McpSettingsDestination)
                                SettingsSearchTarget.DISTRO_MANAGEMENT -> activeStack.push(SettingsSearchDestination, DistroManagementDestination)
                                SettingsSearchTarget.PLUGIN_LIBRARY -> activeStack.push(SettingsSearchDestination, RemotePluginLibraryDestination)
                                SettingsSearchTarget.STORAGE_USAGE -> activeStack.push(SettingsSearchDestination, StorageUsageDestination)
                                SettingsSearchTarget.STORAGE_MOUNTS -> activeStack.push(SettingsSearchDestination, StorageMountSettingsDestination)
                                SettingsSearchTarget.ENV_VARS -> activeStack.push(SettingsSearchDestination, EnvironmentVariableSettingsDestination)
                                SettingsSearchTarget.SSH_SETTINGS -> activeStack.push(SettingsSearchDestination, SshSettingsDestination)
                                SettingsSearchTarget.FTP_SETTINGS -> activeStack.push(SettingsSearchDestination, FtpSettingsDestination)
                                SettingsSearchTarget.WEB_CHAT,
                                SettingsSearchTarget.PRIVILEGE_MODE -> activeStack.push(SettingsSearchDestination, LinuxEnvSettingsDestination)
                                SettingsSearchTarget.APP_MANAGEMENT -> activeStack.push(SettingsSearchDestination, AppManagementDestination)
                                SettingsSearchTarget.APPEARANCE_SETTINGS,
                                SettingsSearchTarget.THEME_MODE,
                                SettingsSearchTarget.DYNAMIC_COLOR,
                                SettingsSearchTarget.FONT_SCALE,
                                SettingsSearchTarget.TERMINAL_SETTINGS,
                                SettingsSearchTarget.LANGUAGE_SETTINGS -> activeStack.push(SettingsSearchDestination, AppearanceSettingsDestination)
                                SettingsSearchTarget.BATTERY_OPTIMIZATION,
                                SettingsSearchTarget.PHANTOM_PROCESS -> activeStack.push(SettingsSearchDestination, SystemDevSettingsDestination)
                                SettingsSearchTarget.DEVELOPER_OPTIONS -> activeStack.push(SettingsSearchDestination, DeveloperDestination)
                                SettingsSearchTarget.ADB_LOGCAT -> activeStack.push(SettingsSearchDestination, AdbLogcatDestination)
                                SettingsSearchTarget.CUSTOM_ITERATION -> activeStack.push(SettingsSearchDestination, CustomIterationDestination)
                                SettingsSearchTarget.PERMISSION_GUIDE -> activeStack.push(SettingsSearchDestination, PermissionGuideDestination)
                                SettingsSearchTarget.WORKSHOP_SETTINGS -> {
                                    navigateMain(MainDestination.Workspace)
                                    activeStack.pushRaw(WorkshopSettingsDestination)
                                }
                                SettingsSearchTarget.WORKSHOP_ENVIRONMENT -> {
                                    navigateMain(MainDestination.Workspace)
                                    activeStack.pushRaw(WorkshopSettingsDestination)
                                    activeStack.pushRaw(WorkshopEnvironmentSettingsDestination)
                                }
                                SettingsSearchTarget.WORKSHOP_SIGNING -> {
                                    navigateMain(MainDestination.Workspace)
                                    activeStack.pushRaw(WorkshopSettingsDestination)
                                    activeStack.pushRaw(WorkshopSigningSettingsDestination)
                                }
                                SettingsSearchTarget.WORKFLOWS -> {
                                    navigateMain(MainDestination.Workspace)
                                    activeStack.pushRaw(WorkflowDestination())
                                }
                                SettingsSearchTarget.NAV_HOME ->
                                    navigateMain(MainDestination.Home)
                                SettingsSearchTarget.NAV_AGENT_CHAT ->
                                    navigateMain(MainDestination.Agent)
                                SettingsSearchTarget.NAV_WORKSPACE ->
                                    navigateMain(MainDestination.Workspace)
                                SettingsSearchTarget.NAV_TERMINAL -> {
                                    activeStack.pushRaw(TerminalDestination())
                                }
                                SettingsSearchTarget.NAV_BROWSER -> {
                                    activeStack.pushRaw(BrowserDestination)
                                }
                                SettingsSearchTarget.ABOUT_COMMUNITY,
                                SettingsSearchTarget.ABOUT_UPDATE -> activeStack.push(SettingsSearchDestination, AboutCommunityDestination)
                            }
                        }
                    )
                }
            }
            entry<AppearanceSettingsDestination> {
                GuardedEntry(AppearanceSettingsDestination) {
                    AppearanceSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentEcoSettingsDestination> {
                GuardedEntry(AgentEcoSettingsDestination) {
                    AgentEcoSettingsScreen(
                        onBack = ::popBack,
                        onOpenModelProfiles = { activeStack.push(AgentEcoSettingsDestination, ModelProfilesDestination) },
                        onOpenLocalLlm = { activeStack.push(AgentEcoSettingsDestination, LocalLlmDestination) },
                        onOpenCcSwitch = { activeStack.push(AgentEcoSettingsDestination, CcSwitchDestination) },
                        onOpenToolCenter = { activeStack.push(AgentEcoSettingsDestination, ToolCenterDestination) },
                        onOpenAgentSettings = { activeStack.push(AgentEcoSettingsDestination, AgentSettingsDestination) },
                        onOpenSubagentSettings = { activeStack.push(AgentEcoSettingsDestination, AgentSubagentSettingsDestination) },
                        onOpenSkillSettings = { activeStack.push(AgentEcoSettingsDestination, AgentSkillSettingsDestination) },
                        onOpenMcpSettings = { activeStack.push(AgentEcoSettingsDestination, McpSettingsDestination) },
                        onOpenQuickPhrases = { activeStack.push(AgentEcoSettingsDestination, QuickPhrasesDestination) },
                        onOpenStats = { activeStack.push(AgentEcoSettingsDestination, StatsDestination) },
                        onOpenPhoneAgent = { activeStack.push(AgentEcoSettingsDestination, PhoneAgentSettingsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LinuxEnvSettingsDestination> {
                GuardedEntry(LinuxEnvSettingsDestination) {
                    LinuxEnvironmentSettingsScreen(
                        onBack = ::popBack,
                        onOpenDistroManagement = { activeStack.push(LinuxEnvSettingsDestination, DistroManagementDestination) },
                        onOpenStorageMounts = { activeStack.push(LinuxEnvSettingsDestination, StorageMountSettingsDestination) },
                        onOpenStorageUsage = { activeStack.push(LinuxEnvSettingsDestination, StorageUsageDestination) },
                        onOpenAppManagement = { activeStack.push(LinuxEnvSettingsDestination, AppManagementDestination) },
                        onOpenEnvironmentVariables = { activeStack.push(LinuxEnvSettingsDestination, EnvironmentVariableSettingsDestination) },
                        onOpenSshSettings = { activeStack.push(LinuxEnvSettingsDestination, SshSettingsDestination) },
                        onOpenFtpSettings = { activeStack.push(LinuxEnvSettingsDestination, FtpSettingsDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SystemDevSettingsDestination> {
                GuardedEntry(SystemDevSettingsDestination) {
                    SystemDevSettingsScreen(
                        onBack = ::popBack,
                        onOpenDeveloper = { activeStack.push(SystemDevSettingsDestination, DeveloperDestination) },
                        onOpenAdbLogcat = { activeStack.push(SystemDevSettingsDestination, AdbLogcatDestination) },
                        onOpenCustomIteration = { activeStack.push(SystemDevSettingsDestination, CustomIterationDestination) },
                        onOpenPermissionGuide = { activeStack.push(SystemDevSettingsDestination, PermissionGuideDestination) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<PermissionGuideDestination> {
                GuardedEntry(PermissionGuideDestination) {
                    PermissionGuideScreen(
                        onBack = ::popBack,
                    )
                }
            }
            entry<AboutCommunityDestination> {
                GuardedEntry(AboutCommunityDestination) {
                    AboutCommunityScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<DistroManagementDestination> {
                GuardedEntry(DistroManagementDestination) {
                    DistroManagementScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSettingsDestination> {
                GuardedEntry(AgentSettingsDestination) {
                    AgentSettingsScreen(
                        onBack = ::popBack,
                        category = AgentSettingsCategory.EXECUTION,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<AgentSubagentSettingsDestination> {
                GuardedEntry(AgentSubagentSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = AgentSettingsCategory.SUBAGENTS, viewModel = settingsViewModel)
                }
            }
            entry<AgentSkillSettingsDestination> {
                GuardedEntry(AgentSkillSettingsDestination) {
                    AgentSettingsScreen(onBack = ::popBack, category = AgentSettingsCategory.SKILLS, viewModel = settingsViewModel)
                }
            }
            entry<McpSettingsDestination> {
                GuardedEntry(McpSettingsDestination) {
                    McpSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<ToolCenterDestination> {
                GuardedEntry(ToolCenterDestination) {
                    ToolCenterScreen(
                        onBack = ::popBack,
                        onLaunchPty = { toolId -> activeStack.push(ToolCenterDestination, TerminalDestination(toolId = toolId)) },
                        onOpenToolDetail = { toolId -> activeStack.push(ToolCenterDestination, ToolDetailDestination(toolId = toolId)) },
                        onStartAiHealing = { toolId, toolName, logs ->
                            val prompt = ToolSelfHealingHelper.buildHealingPrompt(toolId, toolName, logs)
                            pendingHealingTask = HealingTask("🔧 自愈: $toolName", prompt)
                            navigateMain(MainDestination.Agent)
                        },
                    )
                }
            }
            entry<CcSwitchDestination> {
                GuardedEntry(CcSwitchDestination) {
                    val context = androidx.compose.ui.platform.LocalContext.current
                    CcSwitchScreen(
                        onBack = ::popBack,
                        onLaunchTerminal = { executable -> activeStack.push(CcSwitchDestination, TerminalDestination(toolId = executable)) },
                        onOpenBrowser = { url ->
                            val targetUrl = url.ifBlank { "http://127.0.0.1:19870" }
                            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                            }
                            runCatching {
                                context.startActivity(intent)
                            }.onFailure {
                                Toast.makeText(context, "无法唤起外部浏览器: ${it.localizedMessage}", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
            }
            entry<ToolDetailDestination> { destination ->
                GuardedEntry(destination) {
                    ToolDetailRoute(
                        destination = destination,
                        onBack = ::popBack,
                        onLaunchTerminal = { toolId -> activeStack.push(destination, TerminalDestination(toolId = toolId)) },
                        onHealing = { name, prompt -> pendingHealingTask = HealingTask("🔧 自愈: $name", prompt); navigateMain(MainDestination.Agent) },
                        onOpenPluginPage = { activeStack.push(destination, PluginPageDestination("tianyan.tool-service", "main", mapOf("web.tool.id" to it))) },
                    )
                }
            }
            entry<StorageMountSettingsDestination> {
                GuardedEntry(StorageMountSettingsDestination) {
                    StorageMountSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StorageUsageDestination> {
                GuardedEntry(StorageUsageDestination) {
                    StorageUsageScreen(onBack = ::popBack)
                }
            }
            entry<AppManagementDestination> {
                GuardedEntry(AppManagementDestination) {
                    AppManagementScreen(onBack = ::popBack)
                }
            }
            entry<EnvironmentVariableSettingsDestination> {
                GuardedEntry(EnvironmentVariableSettingsDestination) {
                    EnvironmentVariableSettingsScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<SshSettingsDestination> {
                GuardedEntry(SshSettingsDestination) {
                    SshSettingsScreen(onBack = ::popBack)
                }
            }
            entry<FtpSettingsDestination> {
                GuardedEntry(FtpSettingsDestination) {
                    FtpSettingsScreen(onBack = ::popBack)
                }
            }
            entry<ModelProfilesDestination> {
                GuardedEntry(ModelProfilesDestination) {
                    ModelProfilesScreen(
                        onBack = ::popBack,
                        onCreate = { activeStack.push(ModelProfilesDestination, ModelEditorDestination()) },
                        onEdit = { modelId -> activeStack.push(ModelProfilesDestination, ModelEditorDestination(modelId)) },
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<LocalLlmDestination> {
                GuardedEntry(LocalLlmDestination) {
                    LocalLlmScreen(
                        onBack = ::popBack,
                        onOpenEngine = { activeStack.push(LocalLlmDestination, ToolDetailDestination("llama-cpp")) },
                    )
                }
            }
            entry<PhoneAgentSettingsDestination> {
                GuardedEntry(PhoneAgentSettingsDestination) {
                    PhoneAgentSettingsScreen(onBack = ::popBack)
                }
            }
            entry<ModelEditorDestination> { destination ->
                GuardedEntry(destination) {
                    ModelEditorScreen(
                        modelId = destination.modelId,
                        onBack = ::popBack,
                        onSaved = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<QuickPhrasesDestination> {
                GuardedEntry(QuickPhrasesDestination) {
                    QuickPhrasesScreen(
                        onBack = ::popBack,
                        viewModel = settingsViewModel,
                    )
                }
            }
            entry<StatsDestination> {
                GuardedEntry(StatsDestination) {
                    StatsScreen(onBack = ::popBack)
                }
            }
            entry<DeveloperDestination> {
                GuardedEntry(DeveloperDestination) {
                    DeveloperScreen(onBack = ::popBack)
                }
            }
            entry<AdbLogcatDestination> {
                GuardedEntry(AdbLogcatDestination) {
                    AdbLogcatScreen(onBack = ::popBack)
                }
            }
            entry<A2uiPocDestination> {
                GuardedEntry(A2uiPocDestination) {
                    A2uiPocScreen(onBack = ::popBack)
                }
            }
            entry<FeatureHubDestination> {
                GuardedEntry(FeatureHubDestination) {
                    FeatureHubScreen(
                        onBack = ::popBack,
                        onOpenCustomIteration = { activeStack.push(FeatureHubDestination, CustomIterationDestination) },
                        onStartRoundtable = { pendingHealingTask = HealingTask(AgentPresets.ROUNDTABLE_TITLE, AgentPresets.ROUNDTABLE_PROMPT); navigateMain(MainDestination.Agent) },
                        onOpenA2uiPoc = { activeStack.push(FeatureHubDestination, A2uiPocDestination) },
                    )
                }
            }
            entry<CustomIterationDestination> {
                GuardedEntry(CustomIterationDestination) {
                    CustomIterationScreen(
                        onBack = ::popBack,
                        onNavigateToChat = { prompt ->
                            pendingHealingTask = HealingTask("🚀 自定义迭代", prompt)
                            navigateMain(MainDestination.Agent)
                        },
                    )
                }
            }
            entry<PluginManagerDestination> { GuardedEntry(PluginManagerDestination) { ManagedPluginsScreen(pluginPlatform, pluginController, { activeStack.pushRaw(PluginPageDestination(it.pluginId, it.extensionId)) }, ::popBack) } }
            entry<WebPluginManagerDestination> { GuardedEntry(WebPluginManagerDestination) { top.tianyan.app.ui.settings.WebPluginsScreen({ id, version, plugin -> activeStack.pushRaw(PluginPageDestination(plugin, "main", mapOf("web.package.id" to id, "web.package.version" to version))) }, ::popBack) } }
            entry<PluginPageDestination> { destination ->
                GuardedEntry(destination) { RegisteredPluginPage(pluginPages, destination, ::popBack) }
            }
            entry<TerminalDestination> { destination ->
                GuardedEntry(destination) { TerminalPluginPage(pluginPages, project = destination.project, onBack = ::popBack) }
            }
            entry<BrowserDestination> {
                GuardedEntry(BrowserDestination) { BrowserPluginPage(pluginPages, onBack = ::popBack) }
            }
            entry<GitRepositoryDestination> { destination ->
                GuardedEntry(destination) {
                    GitScreen(projectName = destination.projectName, onBack = ::popBack)
                }
            }
            entry<BotDetailDestination> { destination -> GuardedEntry(destination) { top.tianyan.app.ui.bot.BotDetailScreen(botId = destination.botId, onBack = ::popBack, onOpenSession = { sessionId -> chatViewModel.switchSession(sessionId); navigateMain(MainDestination.Agent) }) } }
            entry<GroupChatDestination> { destination -> GuardedEntry(destination) { top.tianyan.app.ui.bot.GroupChatScreen(groupId = destination.groupId, onBack = ::popBack, onOpenBot = { activeStack.pushRaw(BotDetailDestination(it)) }) } }
    }
    // Keep entry state and ViewModels across pushes and pops in the shared navigation stack.
    // Explicit <NavKey>: outside NavDisplay's parameter context, listOf cannot infer T.
    val entryDecorators = listOf(rememberSaveableStateHolderNavEntryDecorator<NavKey>(), rememberViewModelStoreNavEntryDecorator<NavKey>())
    PluginNavigationShell(pluginPlatform, pluginController, browserViewModel, selectedPage = selectedPluginPage(activeStack.lastOrNull()),
        onOpen = { showNavigation = false; activeStack.pushRaw(PluginPageDestination(it.pluginId, it.extensionId)) },
        onOpenManager = { showNavigation = false; activeStack.pushRaw(PluginManagerDestination) }, onOpenNavigation = { showNavigation = true },
    ) {
        ChatNavigationDrawer(
            viewModel = chatViewModel, visible = showNavigation, onDismiss = { showNavigation = false },
            onOpen = { showNavigation = true }, gesturesEnabled = sidebarGesturesEnabled, docked = sidebarDocked, onDockedChange = { sidebarDocked = it }, newSessionVisible = showNewSession, selected = selectedMain,
            onNewSession = { showNewSession = true }, onDismissNewSession = { showNewSession = false },
            onNavigate = ::navigateMain, onOpenTerminal = { activeStack.pushRaw(TerminalDestination()) },
            onOpenTools = { activeStack.pushRaw(ToolCenterDestination) },
        ) {
            Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                NavDisplay(
                    backStack = activeStack, modifier = Modifier.fillMaxSize(), onBack = ::popBack,
                    entryDecorators = entryDecorators, entryProvider = appEntryProvider,
                )
            }
        }
    }
}

private data class HealingTask(val title: String, val prompt: String)
