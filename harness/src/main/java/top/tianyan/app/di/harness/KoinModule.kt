package top.tianyan.app.di.harness
import org.koin.dsl.onClose
import org.koin.dsl.module
import android.content.Context
import java.io.File
import top.tianyan.app.harness.AgentContextExecutor
import top.tianyan.app.harness.ApprovalPolicyEngine
import top.tianyan.app.harness.BuildScriptToolExecutor
import top.tianyan.app.harness.HarnessLoop
import top.tianyan.app.harness.HarnessPathResolver
import top.tianyan.app.harness.HarnessProviderRunner
import top.tianyan.app.harness.HarnessToolRoundRunner
import top.tianyan.app.harness.HarnessWorkspaceRecommendations
import top.tianyan.app.harness.ProviderResponseNormalizer
import top.tianyan.app.harness.SubagentOrchestrator
import top.tianyan.app.harness.ToolExecutor
import top.tianyan.app.harness.ToolRoundDispatcher
import top.tianyan.app.harness.TurnRunner
import top.tianyan.app.harness.agent.AgentMcpBootstrap
import top.tianyan.app.harness.approval.ApprovalAuditRecorder
import top.tianyan.app.harness.subagent.BackgroundSubagentRegistry
import top.tianyan.app.harness.approval.ApprovalResumePolicy
import top.tianyan.app.harness.approval.SessionApprovalGrants
import top.tianyan.app.harness.browser.BrowserMcpBootstrap
import top.tianyan.app.harness.checkpoint.ConversationRewinder
import top.tianyan.app.harness.checkpoint.RewindController
import top.tianyan.app.harness.checkpoint.SessionForkConversationRewinder
import top.tianyan.app.harness.compaction.BranchSummarizer
import top.tianyan.app.harness.compaction.CompactionManager
import top.tianyan.app.harness.compaction.CompactionSummarizer
import top.tianyan.app.harness.dual.DualAgentCoordinator
import top.tianyan.app.harness.dual.PlannerPromptBuilder
import top.tianyan.app.harness.events.AgentEventLogger
import top.tianyan.app.harness.events.CapabilityEventWriter
import top.tianyan.app.harness.events.HarnessEventBus
import top.tianyan.app.harness.mcp.LinuxMcpStdioChannelFactory
import top.tianyan.app.harness.mcp.McpCommandBuilder
import top.tianyan.app.harness.mcp.McpHttpTransport
import top.tianyan.app.harness.mcp.McpManager
import top.tianyan.app.harness.mcp.McpStdioChannelFactory
import top.tianyan.app.harness.mcp.McpStdioTransport
import top.tianyan.app.harness.mcp.McpWorkspaceRecommender
import top.tianyan.app.harness.mcp.oauth.McpOAuthCoordinator
import top.tianyan.app.harness.mcp.oauth.McpOAuthTokenProvider
import top.tianyan.app.harness.mcp.server.HarnessToolProvider
import top.tianyan.app.harness.mcp.server.McpResourceDispatcher
import top.tianyan.app.harness.mcp.server.McpServerModule.provideBrowserMcpResources
import top.tianyan.app.harness.mcp.server.McpServerModule.provideBrowserMcpTools
import top.tianyan.app.harness.mcp.server.McpServerRuntime
import top.tianyan.app.harness.mcp.server.McpToolDispatcher
import top.tianyan.app.harness.operation.OperationCoordinator
import top.tianyan.app.harness.projection.CurrentSessionTracker
import top.tianyan.app.harness.projection.LiveMessagePort
import top.tianyan.app.harness.projection.SessionMessageProjector
import top.tianyan.app.harness.projection.SessionStateMirrors
import top.tianyan.app.harness.prompt.DefaultPrivilegeSectionRenderer
import top.tianyan.app.harness.prompt.MemoryRecallSelector
import top.tianyan.app.harness.prompt.PrivilegeSectionRenderer
import top.tianyan.app.harness.prompt.PromptAssetLoader
import top.tianyan.app.harness.prompt.PromptRouter
import top.tianyan.app.harness.prompt.SystemPromptBuilder
import top.tianyan.app.harness.queue.PromptQueueManager
import top.tianyan.app.harness.recovery.RecoveryManager
import top.tianyan.app.harness.session.ApiContextAssembler
import top.tianyan.app.harness.session.LaneManager
import top.tianyan.app.harness.session.SessionModelSwitcher
import top.tianyan.app.harness.session.SessionTreeStore
import top.tianyan.app.harness.session.SessionTurnCoordinator
import top.tianyan.app.harness.session.SessionTurnCoordinatorImpl
import top.tianyan.app.harness.skill.SkillEvolutionAdvisor
import top.tianyan.app.harness.subagent.SubagentLaneRunner
import top.tianyan.app.harness.task.AgentStateMachine
import top.tianyan.app.harness.workflow.AgentNodeExecutor
import top.tianyan.app.harness.workflow.ApprovalNodeExecutor
import top.tianyan.app.harness.workflow.ConditionNodeExecutor
import top.tianyan.app.harness.workflow.DelayNodeExecutor
import top.tianyan.app.harness.workflow.HarnessWorkflowAgentExecutionPort
import top.tianyan.app.harness.workflow.HostActionNodeExecutor
import top.tianyan.app.harness.workflow.LinuxNodeExecutor
import top.tianyan.app.harness.workflow.NodeExecutor
import top.tianyan.app.harness.workflow.PassthroughNodeExecutor
import top.tianyan.app.harness.workflow.ProactiveWorkflowAdvisor
import top.tianyan.app.harness.workflow.SetVariableNodeExecutor
import top.tianyan.app.harness.workflow.WorkflowAgentExecutionPort
import top.tianyan.app.harness.workflow.WorkflowApprovalBroker
import top.tianyan.app.harness.workflow.WorkflowGuiPilot
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.harness.workflow.WorkflowScheduleRepository
import top.tianyan.app.harness.workflow.WorkflowScheduler
import top.tianyan.app.harness.workflow.WorkflowSignalBus
import top.tianyan.app.runtime.browser.tools.BrowserMcpResources
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools
import org.koin.core.qualifier.named
import top.tianyan.app.harness.diagnostics.RequestDiagnosticsStore
import top.tianyan.app.harness.session.InteractiveSessionControl
import top.tianyan.app.harness.session.SessionControl

/** 被控端（独立 MCP server）在 Koin 中的限定符：与浏览器自环的默认定义互不覆盖。 */
private const val AGENT_MCP_DISPATCHER = "agentMcpToolDispatcher"
private const val AGENT_MCP_RUNTIME = "agentMcpServerRuntime"

/** Dependency registrations owned by the harness module. */
val harnessModule = module {
    phoneWorkflowDefinitions()
    single<BrowserMcpResources> { provideBrowserMcpResources(registry = get()) }

    single<BrowserMcpTools> { provideBrowserMcpTools(registry = get(), browserPrefs = get()) }

    single<AgentContextExecutor> { AgentContextExecutor(agentContextDao = get(), json = get()) }

    single<ApprovalPolicyEngine> { ApprovalPolicyEngine(pathResolver = get()) }

    single<BuildScriptToolExecutor> { BuildScriptToolExecutor(repository = get(), environments = get()) }

    single<SessionTurnCoordinator> { SessionTurnCoordinatorImpl(preferences = get(), logger = get()) }

    single<ApprovalAuditRecorder> { ApprovalAuditRecorder(audit = get(), logger = get()) }

    single<BackgroundSubagentRegistry> { BackgroundSubagentRegistry(promptQueueManager = get(), logger = get()) }

    single<HarnessLoop> {
        HarnessLoop(
            workspaceRecommendations = get(),
            providerRunner = get(),
            toolRoundRunner = get(),
            foregroundLauncher = get(),
            providerClient = get(),
            toolExecutor = get(),
            toolRoundDispatcher = get(),
            messageStore = get(),
            sessionDao = get(),
            modelRepository = get(),
            settingsDataStore = get(),
            json = get(),
            logger = get(),
            approvalRepository = get(),
            operationCoordinator = get(),
            recoveryManager = get(),
            promptQueueManager = get(),
            sessionTracker = get(),
            stateMirrors = get(),
            messageProjector = get(),
            agentEventLogger = get(),
            resumePolicy = get(),
            sessionApprovalGrants = get(),
            agentTaskStateMachine = get(),
            turnRunner = get(),
            rewindController = get(),
            branchSummarizer = get(),
            skillEvolutionAdvisor = get(),
            turnCoordinator = get(),
            executionEnvironments = get(),
            approvalAuditor = get(),
            feedbackService = get(),
            backgroundSubagents = get(),
        )
    }
    single<SessionControl> { get<HarnessLoop>() }
    single<InteractiveSessionControl> { get<HarnessLoop>() }
    single<HarnessPathResolver> { HarnessPathResolver() }
    single { RequestDiagnosticsStore(redactor = get(), repository = get()) }.onClose { it?.close() }
    factory<HarnessProviderRunner> {
        HarnessProviderRunner(
            providerClient = get(),
            requestDiagnostics = get(),
            messageStore = get(),
            operationCoordinator = get(),
            stateMirrors = get(),
            messageProjector = get(),
            toolMountResolver = get(),
            agentEventLogger = get(),
            contextAssembler = get(),
            compactionManager = get(), context = get(),
        )
    }
    single { top.tianyan.app.harness.McpToolMountResolver(mcpManager = get(), repository = get(), policies = get(), capabilityWriter = get(), capabilityScope = get()) }

    factory<HarnessToolRoundRunner> {
        HarnessToolRoundRunner(
            toolExecutor = get(),
            sessionDao = get(),
            json = get(),
            operationCoordinator = get(),
            messageProjector = get(),
            stateMirrors = get(),
            agentEventLogger = get(),
            toolRoundDispatcher = get(),
        )
    }

    factory<HarnessWorkspaceRecommendations> {
        HarnessWorkspaceRecommendations(
            recommender = get(),
            servers = get(),
            paths = get(),
            logger = get(),
        )
    }

    includes(providerModule)
    includes(harnessStewardProviders)
    single<ProviderResponseNormalizer> { ProviderResponseNormalizer(json = get()) }

    single { top.tianyan.app.harness.SubagentSummaryPublisher(get(), get(), get(), get(), get()) }
    single<SubagentOrchestrator> {
        SubagentOrchestrator(
            sessionDao = get(),
            laneManager = get(),
            laneRunner = get(),
            subagentRepository = get(),
            promptAssets = get(),
            agentContextRepo = get(),
            fileAccess = get(),
            providerClient = get(),
            logger = get(),
            summaryPublisher = get(),
            backgroundSubagents = get(),
        )
    }
    includes(toolBackendModule)
    single<ToolRoundDispatcher> { ToolRoundDispatcher() }
    single<TurnRunner> { TurnRunner(normalizer = get(), groupFreshness = get()) }

    single<ApprovalResumePolicy> { ApprovalResumePolicy(sessionDao = get(), operationCoordinator = get()) }

    single<SessionApprovalGrants> { SessionApprovalGrants() }

    single<BrowserMcpBootstrap> {
        BrowserMcpBootstrap(
            context = get(),
            runtime = lazy { get<McpServerRuntime>() },
            registry = get(),
            browserPrefs = get(),
        )
    }

    single<RewindController> { RewindController(
            store = get(),
            fileAccess = get(),
            conversationRewinder = get(),
            environments = get(),
        ) }
    single<SessionForkConversationRewinder> { SessionForkConversationRewinder(
            sessionDao = get(),
            runtimeRepo = get(),
            checkpointStore = get(),
        ) }

    single<BranchSummarizer> { BranchSummarizer(
            repository = get(),
            json = get(),
            logger = get(),
            summarizer = get(),
        ) }

    single<CompactionManager> { CompactionManager(
            repository = get(),
            json = get(),
            sessionStore = get(),
            summarizer = get(),
        ) }

    single<CompactionSummarizer> { CompactionSummarizer(providerClient = get()) }

    single<PrivilegeSectionRenderer> { get<DefaultPrivilegeSectionRenderer>() }

    single<LiveMessagePort> { get<SessionMessageProjector>() }

    single<ConversationRewinder> { get<SessionForkConversationRewinder>() }

    single<DualAgentCoordinator> {
        DualAgentCoordinator(
            providerClient = get(),
            laneRunner = get(),
            promptBuilder = get(),
            sessionRepository = get(),
            eventBus = get(),
        )
    }

    single<PlannerPromptBuilder> { PlannerPromptBuilder() }

    single<AgentEventLogger> { AgentEventLogger(preferences = get(), logger = get()) }

    single<CapabilityEventWriter> {
        CapabilityEventWriter(
            port = get(),
            skillRepository = get(),
            mcpServerRepository = get(),
            mcpManager = get(),
        )
    }

    single<HarnessEventBus> { HarnessEventBus() }
    single<LinuxMcpStdioChannelFactory> { LinuxMcpStdioChannelFactory(linuxRuntime = get(), commandBuilder = get(), environments = get(), spillDirectory = File(get<Context>().cacheDir, "tianyan_mcp_spills")) }

    single<McpCommandBuilder> { McpCommandBuilder() }

    single<McpHttpTransport> {
        McpHttpTransport(
            client = get(),
            json = get(),
            logger = get(),
            oauthTokens = get(),
            spillDirectory = File(get<Context>().cacheDir, "tianyan_mcp_spills"),
        )
    }

    single<top.tianyan.app.harness.mcp.McpCatalogAvailability> { top.tianyan.app.harness.mcp.McpCatalogAvailability(get(), get(), get(), get()) }
    single<McpManager> {
        McpManager(
            repository = get(), stdio = get(), http = get(),
            commandBuilder = get(), linuxRuntime = get(), logger = get(),
            agentEventLogger = get(), remoteServers = get(), catalogAvailability = get(),
            toolPolicy = get(),
        )
    }
    single { top.tianyan.app.harness.mcp.McpToolCallPolicy(mcpManager = get(), repository = get(), preferences = get()) }
    single<McpStdioChannelFactory> { get<LinuxMcpStdioChannelFactory>() }

    single<McpStdioTransport> {
        McpStdioTransport(
            json = get(),
            commandBuilder = get(),
            channelFactory = get(), standaloneScope = get(), idleTimeoutMinutes = get<top.tianyan.app.core.datastore.McpClientPreferences>().idleTimeoutMinutes,
        )
    }

    single<McpWorkspaceRecommender> { McpWorkspaceRecommender() }
    single { val selection = get<top.tianyan.app.runtime.environment.LocalDistributionSelection>(); top.tianyan.app.harness.mcp.StandaloneMcpScope(get(), selection::current, selection.changes) }
    single<McpOAuthCoordinator> { McpOAuthCoordinator(credentials = get(), client = get()) }

    single<McpOAuthTokenProvider> { McpOAuthTokenProvider(credentials = get(), coordinator = get()) }

    single<McpResourceDispatcher> { McpResourceDispatcher(browserResources = get()) }

    single<McpServerRuntime> { McpServerRuntime(toolDispatcher = get(), resourceDispatcher = get()) }

    single<McpToolDispatcher> { McpToolDispatcher(browserTools = get()) }

    // ===== MCP 被控端（外部 AI 客户端控制本 App）：独立端点 + 独立 dispatcher =====
    // 工具集复用 ProviderClient.TOOLS，经 HarnessToolProvider 按能力分层暴露；
    // 写入/执行层开关由 AgentMcpBootstrap 从偏好实时刷新。
    single<HarnessToolProvider> { HarnessToolProvider(executor = get()) }

    single(named(AGENT_MCP_DISPATCHER)) {
        McpToolDispatcher(
            browserTools = get(),
            extraProviders = listOf(get<HarnessToolProvider>()),
        )
    }

    single(named(AGENT_MCP_RUNTIME)) {
        McpServerRuntime(
            toolDispatcher = get(named(AGENT_MCP_DISPATCHER)),
            resourceDispatcher = get(),
            serverName = McpServerRuntime.AGENT_SERVER_NAME,
        )
    }

    // runtime/toolProvider 均为 Lazy：被控端默认关闭，其构造图（ToolExecutor 全家桶）不应在启动时展开
    single<AgentMcpBootstrap> {
        AgentMcpBootstrap(
            runtime = lazy { get<McpServerRuntime>(named(AGENT_MCP_RUNTIME)) },
            toolProvider = lazy { get<HarnessToolProvider>() },
            prefs = get(),
            foregroundLauncher = get(),
        )
    }

    single<OperationCoordinator> {
        OperationCoordinator(
            repository = get(),
            json = get(),
            eventBus = get(),
        )
    }

    single<CurrentSessionTracker> { CurrentSessionTracker() }

    single<SessionMessageProjector> { SessionMessageProjector(store = get(), tracker = get()) }

    single<SessionStateMirrors> { SessionStateMirrors(tracker = get()) }

    single<MemoryRecallSelector> { MemoryRecallSelector(agentContextDao = get()) }

    single<DefaultPrivilegeSectionRenderer> { DefaultPrivilegeSectionRenderer(context = get(), privilegeManager = get(), promptAssets = get()) }

    single<PromptAssetLoader> { PromptAssetLoader(context = get()) }

    single<PromptRouter> { PromptRouter(promptAssets = get()) }

    single<SystemPromptBuilder> {
        SystemPromptBuilder(
            context = get(),
            settingsDataStore = get(),
            skillRepository = get(),
            toolRepository = get(),
            agentContextDao = get(),
            subagentRepository = get(),
            mcpServerRepository = get(),
            promptAssets = get(),
            fileAccess = get(),
            privilegeRenderer = get(), promptRouter = get(), environments = get(),
        )
    }

    single<PromptQueueManager> {
        PromptQueueManager(
            repository = get(),
            json = get(),
            sessionStore = get(),
            logger = get(),
        )
    }

    single<RecoveryManager> {
        RecoveryManager(
            repository = get(),
            coordinator = get(),
            approvalRepository = get(),
            json = get(),
            eventBus = get(),
        )
    }

    factory<ApiContextAssembler> {
        ApiContextAssembler(
            compactionManager = get(),
            settingsDataStore = get(),
            systemPromptBuilder = get(),
            sessionStore = get(),
            memoryRecallSelector = get(),
            agentApprovalRepository = get(), operationCoordinator = get(), plugins = get(), secretRedactor = get(), environments = get(),
            compactionSteward = get(),
        )
    }

    single<LaneManager> { LaneManager(repository = get(), treeStore = get()) }

    single<SessionModelSwitcher> {
        SessionModelSwitcher(
            sessionDao = get(),
            modelDao = get(),
            settingsDataStore = get(),
            compactionManager = get(),
            providerClient = get(),
            messagePort = get(),
        )
    }

    single<SessionTreeStore> {
        SessionTreeStore(
            repository = get(),
            json = get(),
            logger = get(),
        )
    }

    single<SkillEvolutionAdvisor> {
        SkillEvolutionAdvisor(
            providerClient = get(),
            skillRepository = get(),
            settingsDataStore = get(),
            sessionDao = get(),
            projector = get(),
            logger = get(),
        )
    }

    single<SubagentLaneRunner> {
        SubagentLaneRunner(
            context = get(),
            providerClient = get(),
            toolExecutor = { get<ToolExecutor>() },
            environments = get(),
            treeStore = get(),
            operations = get(),
            settingsDataStore = get(),
            promptAssets = get(),
            json = get(),
            toolRoundDispatcher = get(),
        )
    }
    single<AgentStateMachine> { AgentStateMachine(repository = get(), logger = get()) }
    factory<PassthroughNodeExecutor> { PassthroughNodeExecutor() }

    factory<ApprovalNodeExecutor> { ApprovalNodeExecutor(broker = get()) }

    factory<LinuxNodeExecutor> { LinuxNodeExecutor(linuxRuntime = get()) }

    factory<AgentNodeExecutor> { AgentNodeExecutor(agentExecution = get()) }

    factory<ConditionNodeExecutor> { ConditionNodeExecutor() }

    factory<DelayNodeExecutor> { DelayNodeExecutor() }

    factory<SetVariableNodeExecutor> { SetVariableNodeExecutor() }

    single<WorkflowSignalBus> { WorkflowSignalBus() }

    single<ProactiveWorkflowAdvisor> { ProactiveWorkflowAdvisor(signalBus = get(), workflows = get()) }

    single<HarnessWorkflowAgentExecutionPort> {
        HarnessWorkflowAgentExecutionPort(
            sessions = get(),
            models = get(),
            laneRunner = get(),
            subagentOrchestrator = get(),
        )
    }

    single<WorkflowApprovalBroker> { WorkflowApprovalBroker() }

    factory<WorkflowAgentExecutionPort> { get<HarnessWorkflowAgentExecutionPort>() }

    single<WorkflowGuiPilot> {
        WorkflowGuiPilot(
            gui = get(),
            privilegeManager = get(),
            providerClient = get(),
            hud = get(),
        )
    }

    single<WorkflowRunManager> {
        WorkflowRunManager(
            scheduler = get(),
            repository = get(), linuxRuntime = get(),
            json = get(),
            logger = get(),
        )
    }

    single<WorkflowScheduleRepository> {
        WorkflowScheduleRepository(
            store = get(),
            workflowRepository = get(),
            dispatcher = get(),
        )
    }

    single<WorkflowScheduler> { WorkflowScheduler(executors = get(named("workflowExecutors")), approvalBroker = get(), virtualScreenRuns = get()) }

    single<Set<NodeExecutor>>(named("workflowExecutors")) {
        builtinWorkflowExecutors(this)
    }
}
