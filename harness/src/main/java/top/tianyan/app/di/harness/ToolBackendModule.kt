package top.tianyan.app.di.harness

import org.koin.dsl.module
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.harness.AskUserToolBackend
import top.tianyan.app.harness.ContextMemoryToolBackend
import top.tianyan.app.harness.DownloadToolBackend
import top.tianyan.app.harness.HarnessServiceToolBackend
import top.tianyan.app.harness.HostCapabilityToolBackend
import top.tianyan.app.harness.HostToolRequest
import top.tianyan.app.harness.LinuxCommandToolBackend
import top.tianyan.app.harness.PromptAssetToolBackend
import top.tianyan.app.harness.ToolExecutor
import top.tianyan.app.harness.ToolExecutionRequest
import top.tianyan.app.harness.ToolResult
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.WorkspaceToolBackend
import top.tianyan.app.harness.WorkspaceMutationSnapshots
import top.tianyan.app.harness.core.ToolCheckpoints
import top.tianyan.app.harness.directory.CapabilityToolGateway

internal val toolBackendModule = module {
    single<top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory>(org.koin.core.qualifier.named("local-execution")) {
        top.tianyan.app.harness.environment.LocalExecutionEnvironmentFactory(get(), get(), get(), get(), get(), get())
    }
    single {
        val selection = get<top.tianyan.app.runtime.environment.LocalDistributionSelection>()
        top.tianyan.app.harness.environment.SessionExecutionTargets(get(), get(), selection::capture)
    }
    single<top.tianyan.app.runtime.environment.ExecutionEnvironmentFactory> {
        top.tianyan.app.harness.environment.SelectedExecutionEnvironmentFactory(
            get(org.koin.core.qualifier.named("local-execution")), get(), get(),
            get(),
        )
    }
    single { top.tianyan.app.harness.environment.SessionExecutionEnvironments(get()) }
    single { get<top.tianyan.app.harness.plugins.HarnessPluginRegistry>().checkpoints() }
    single { WorkspaceMutationSnapshots(store = get(), events = get()) }
    single {
        val files = get<WorkspaceFileAccess>()
        WorkspaceToolBackend(
            operationsFor = { workspace -> if (workspace.isNotBlank()) files.withBase(workspace) else files },
            snapshots = get(),
            environments = get(),
        )
    }
    // 领域能力后端：各域独立持有自己的运行时依赖，ToolExecutor 只做管道编排与路由。
    single {
        HostCapabilityToolBackend(
            privilegeManager = get(),
            androidAppManager = get(),
            androidAppRepository = get(),
            shizukuApis = get(),
            hostGuiController = get(),
            virtualDisplayCoordinator = get(),
            virtualScreenToolkit = get(),
            providerClient = get(),
            settingsDataStore = get(),
            phoneAgentServices = get(),
            embeddedAdbManager = get(),
            secretRedactor = get(),
            eventBus = get(),
        )
    }
    single {
        LinuxCommandToolBackend(
            linuxRuntime = get(),
            pathResolver = get(),
            settingsDataStore = get(),
            workflowSignals = get(),
            environments = get(),
        )
    }
    single {
        DownloadToolBackend(
            fileDownloader = get(),
            fileAccess = get(),
            mutationSnapshots = get(),
            environments = get(),
        )
    }
    single {
        ContextMemoryToolBackend(
            messageStore = get(),
            compactionManager = get(),
            sessionDao = get(),
            providerClient = get(),
        )
    }
    single {
        AskUserToolBackend(
            approvalPolicyEngine = get(),
            approvalRepository = get(),
        )
    }
    single {
        PromptAssetToolBackend(
            skillRepository = get(),
            promptRouter = get(),
        )
    }
    single {
        HarnessServiceToolBackend(
            contextExecutor = get(),
            subagentOrchestrator = get(),
            dualAgentCoordinator = get(),
            buildScriptToolExecutor = get(),
            blockSubagentInGroupMain = { sessionId, rawToolName ->
                val bot = get<PersonaBotRepository>().getBotBySessionId(sessionId)
                val groupId = get<BotGroupSessionRepository>().groupIdForSession(sessionId)
                if (bot?.isMainBot == true && groupId != null) {
                    "群聊主控不能用 ${rawToolName ?: "invoke_subagent"} 替代成员派单；" +
                        "请使用 bot-management 的 assign-task，将任务分别派给不同群成员（同一轮可多次调用，wait=false）。"
                } else {
                    null
                }
            },
        )
    }
    // 能力统一网关：use_capability 分发 + codemode 脚本装配 + legacy mcp__ 直连回退。
    single<top.tianyan.app.harness.bot.BotCapabilityScope> {
        // Bot 级 MCP 能力白名单（DS Bot 能力库）：Bot 未配置白名单时返回 null = 不限制。
        // 条目按服务 id 与名称双向展开：挂载侧（@ 提及）id/name 都认，执行侧（use_capability/
        // legacy mcp__）此前只认 id；统一后避免"list 按名称命中、call 用 id 被拒"的可见不可调。
        top.tianyan.app.harness.bot.BotCapabilityScope { sessionId ->
            val entries = get<PersonaBotRepository>().getBotBySessionId(sessionId)?.capabilityServerNames
            if (entries == null) {
                null
            } else {
                val servers = runCatching {
                    get<top.tianyan.app.core.database.McpServerRepository>().servers.first()
                }.getOrDefault(emptyList())
                val allowed = mutableSetOf<String>()
                entries.forEach { entry ->
                    val lower = entry.lowercase()
                    val matched = servers.firstOrNull {
                        it.id.lowercase() == lower || it.name.lowercase() == lower
                    }
                    if (matched == null) {
                        allowed += lower
                    } else {
                        allowed += matched.id.lowercase()
                        allowed += matched.name.lowercase()
                    }
                }
                allowed
            }
        }
    }
    single {
        CapabilityToolGateway(
            mcpManager = get(),
            plugins = get(),
            argRedactor = { value: String -> get<SecretRedactor>().redact(value) },
            toolPolicy = get(),
            capabilityScope = get(),
            hostExecutor = { args, operationId, sessionId, metadata ->
                get<HostCapabilityToolBackend>().execute(HostToolRequest(args, operationId, sessionId, metadata))
            },
        )
    }
    single<ToolExecutor> {
        ToolExecutor(
            fileAccess = get(),
            pathResolver = get(),
            approvalPolicyEngine = get(),
            secretRedactor = get(),
            hostToolBackend = get(),
            linuxCommandToolBackend = get(),
            downloadToolBackend = get(),
            contextMemoryToolBackend = get(),
            askUserToolBackend = get(),
            promptAssetToolBackend = get(),
            harnessServiceToolBackend = get(),
            capabilityToolGateway = get(),
            linuxEnvironmentManager = get(),
            approvalRepository = get(),
            sessionDao = get(),
            mcpManager = get(),
            mcpToolPolicy = get(),
            eventBus = get(),
            checkpointStore = get(),
            sessionApprovalGrants = get(),
            approvalAuditor = get(),
            settingsDataStore = get(),
            toolCheckpoints = get(),
            workspaceToolBackend = get(), governanceGuard = get(),
            environments = get(),
        )
    }

}
