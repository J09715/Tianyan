package top.tianyan.app.di.app

import org.koin.dsl.module
import org.koin.androidx.workmanager.dsl.worker
import top.tianyan.app.bot.BotSchedulePlanner
import top.tianyan.app.bot.BotScheduleWorker
import top.tianyan.app.workflow.WorkflowScheduleWorker
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.harness.bot.schedule.BotScheduleWakeup
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import top.tianyan.app.core.database.AgentApprovalAuditDao
import top.tianyan.app.core.database.AgentMessageFeedbackDao
import top.tianyan.app.core.database.AgentApprovalDao
import top.tianyan.app.core.database.AgentContextDao
import top.tianyan.app.core.database.AgentSkillDao
import top.tianyan.app.core.database.AgentSubagentDao
import top.tianyan.app.core.database.AiModelDao
import top.tianyan.app.core.database.AndroidAppDao
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.BotAssignmentDao
import top.tianyan.app.core.database.BotGroupSessionDao
import top.tianyan.app.core.database.BtwQueryDao
import top.tianyan.app.core.database.BotMemberGroupDao
import top.tianyan.app.core.database.BotScheduleDao
import top.tianyan.app.core.database.BotScheduleDeliveryDao
import top.tianyan.app.di.AppModule.provideBotScheduleDeliveryDao
import top.tianyan.app.core.database.BuildScriptDao
import top.tianyan.app.core.database.GroupChatDao
import top.tianyan.app.core.database.HarnessRuntimeDao
import top.tianyan.app.core.database.HarnessSessionDao
import top.tianyan.app.core.database.InstallLogDao
import top.tianyan.app.core.database.InstallTaskDao
import top.tianyan.app.core.database.McpOAuthCredentialDao
import top.tianyan.app.core.database.McpOAuthTransactionDao
import top.tianyan.app.core.database.McpServerDao
import top.tianyan.app.core.database.PersonaBotDao
import top.tianyan.app.core.database.QuickPhraseDao
import top.tianyan.app.core.database.RuntimeDao
import top.tianyan.app.core.database.StorageMountBindingDao
import top.tianyan.app.core.database.TerminalSessionDao
import top.tianyan.app.core.database.ToolDao
import top.tianyan.app.core.database.ToolSettingsDao
import top.tianyan.app.core.database.WorkflowDao
import top.tianyan.app.core.database.WorkflowScheduleDao
import top.tianyan.app.core.database.WorkflowScheduleStore
import top.tianyan.app.core.database.WorkspaceDao
import top.tianyan.app.core.database.task.AgentTaskDao
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.tools.DependencyManager
import top.tianyan.app.core.tools.RuntimeManager
import top.tianyan.app.core.tools.ToolRuntimeAdapter
import top.tianyan.app.di.AppModule.provideAgentApprovalAuditDao
import top.tianyan.app.di.AppModule.provideAgentMessageFeedbackDao
import top.tianyan.app.di.AppModule.provideAgentApprovalDao
import top.tianyan.app.di.AppModule.provideAgentContextDao
import top.tianyan.app.di.AppModule.provideAgentForegroundLauncher
import top.tianyan.app.di.AppModule.provideAgentSkillDao
import top.tianyan.app.di.AppModule.provideAgentSubagentDao
import top.tianyan.app.di.AppModule.provideAgentTaskDao
import top.tianyan.app.di.AppModule.provideAiModelDao
import top.tianyan.app.di.AppModule.provideAndroidAppDao
import top.tianyan.app.di.AppModule.provideBotAssignmentDao
import top.tianyan.app.di.AppModule.provideBotGroupSessionDao
import top.tianyan.app.di.AppModule.provideBtwQueryDao
import top.tianyan.app.di.AppModule.provideBotScheduleDao
import top.tianyan.app.di.AppModule.provideBuildScriptDao
import top.tianyan.app.di.AppModule.provideGroupChatDao
import top.tianyan.app.di.AppModule.provideBotMemberGroupDao
import top.tianyan.app.di.AppModule.providePersonaBotDao
import top.tianyan.app.di.AppModule.provideCheckpointStore
import top.tianyan.app.di.AppModule.provideDatabase
import top.tianyan.app.di.AppModule.provideDependencyManager
import top.tianyan.app.di.AppModule.provideFileDownloader
import top.tianyan.app.di.AppModule.provideHarnessRuntimeDao
import top.tianyan.app.di.AppModule.provideHarnessSessionDao
import top.tianyan.app.di.AppModule.provideInstallLogDao
import top.tianyan.app.di.AppModule.provideInstallTaskDao
import top.tianyan.app.di.AppModule.provideJson
import top.tianyan.app.di.AppModule.provideKtorHttpClient
import top.tianyan.app.di.AppModule.provideLinuxRuntime
import top.tianyan.app.di.AppModule.provideLocalServiceLauncher
import top.tianyan.app.di.AppModule.provideMcpOAuthCredentialDao
import top.tianyan.app.di.AppModule.provideMcpOAuthTransactionDao
import top.tianyan.app.di.AppModule.provideMcpServerDao
import top.tianyan.app.di.AppModule.provideOkHttpClient
import top.tianyan.app.di.AppModule.provideProcessRegistry
import top.tianyan.app.di.AppModule.providePtyManager
import top.tianyan.app.di.AppModule.provideQuickPhraseDao
import top.tianyan.app.di.AppModule.provideRuntimeDao
import top.tianyan.app.di.AppModule.provideRuntimeManager
import top.tianyan.app.di.AppModule.provideShellExecutor
import top.tianyan.app.di.AppModule.provideStorageMountBindingDao
import top.tianyan.app.di.AppModule.provideTerminalSessionDao
import top.tianyan.app.di.AppModule.provideToolDao
import top.tianyan.app.di.AppModule.provideToolSettingsDao
import top.tianyan.app.di.AppModule.provideWorkflowDao
import top.tianyan.app.di.AppModule.provideWorkflowScheduleDao
import top.tianyan.app.di.AppModule.provideWorkflowScheduleStore
import top.tianyan.app.di.AppModule.provideWorkspaceDao
import top.tianyan.app.di.AppModule.provideWorkspaceFileAccess
import top.tianyan.app.harness.AgentForegroundLauncher
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.agent.AgentMcpForegroundLauncher
import top.tianyan.app.harness.checkpoint.CheckpointStore
import top.tianyan.app.harness.workflow.WorkflowScheduleDispatcher
import top.tianyan.app.lifecycle.RuntimeLifecycleSupervisor
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.pty.PtyManager
import top.tianyan.app.runtime.service.LocalServiceLauncher
import top.tianyan.app.runtime.service.RuntimeServiceController
import top.tianyan.app.runtime.shell.ProcessRegistry
import top.tianyan.app.runtime.shell.ShellExecutor
import top.tianyan.app.runtime.tools.CodexToolInstaller
import top.tianyan.app.runtime.tools.HelloToolInstaller
import top.tianyan.app.runtime.webchat.WebChatAgentGateway
import top.tianyan.app.service.AgentForegroundLauncherImpl
import top.tianyan.app.service.AgentMcpForegroundLauncherImpl
import top.tianyan.app.service.adb.AdbNotificationManager
import top.tianyan.app.webchat.TianyanWebChatAgentGateway
import top.tianyan.app.workflow.AppForegroundTracker
import top.tianyan.app.workflow.WorkManagerScheduleDispatcher
import top.tianyan.app.workflow.WorkManagerScheduleDispatcher.ScheduleDispatcherModule.provideDispatcher
import top.tianyan.app.workflow.WorkflowApprovalNotifier
import top.tianyan.app.workflow.WorkflowRunUiController
import org.koin.core.qualifier.named

/** Dependency registrations owned by the app module. */
val appModule = module {
    worker { params ->
        WorkflowScheduleWorker(get(), params.get(), get(), get())
    }

    worker { params ->
        BotScheduleWorker(get(), params.get(), get(), get())
    }

    single { BotSchedulePlanner(context = get(), scheduleRepository = get<BotScheduleRepository>(), runner = get()) }
    single<BotScheduleWakeup> { get<BotSchedulePlanner>() }

    single<Json> { provideJson() }

    single<AppDatabase> { provideDatabase(context = get()) }

    single<ToolDao> { provideToolDao(database = get()) }

    single<InstallLogDao> { provideInstallLogDao(database = get()) }

    single<InstallTaskDao> { provideInstallTaskDao(database = get()) }

    single<WorkflowDao> { provideWorkflowDao(database = get()) }

    single<WorkflowScheduleDao> { provideWorkflowScheduleDao(database = get()) }

    single<WorkflowScheduleStore> { provideWorkflowScheduleStore(store = get()) }

    single<PersonaBotDao> { providePersonaBotDao(database = get()) }

    single<BotMemberGroupDao> { provideBotMemberGroupDao(database = get()) }

    single<GroupChatDao> { provideGroupChatDao(database = get()) }

    single<BotScheduleDao> { provideBotScheduleDao(database = get()) }
    single<BotScheduleDeliveryDao> { provideBotScheduleDeliveryDao(database = get()) }

    single<BotAssignmentDao> { provideBotAssignmentDao(database = get()) }

    single<BotGroupSessionDao> { provideBotGroupSessionDao(database = get()) }

    single<BtwQueryDao> { provideBtwQueryDao(database = get()) }

    single<RuntimeDao> { provideRuntimeDao(database = get()) }

    single<HarnessSessionDao> { provideHarnessSessionDao(database = get()) }

    single<AiModelDao> { provideAiModelDao(database = get()) }

    single<WorkspaceDao> { provideWorkspaceDao(database = get()) }

    single<TerminalSessionDao> { provideTerminalSessionDao(database = get()) }

    single<AgentContextDao> { provideAgentContextDao(database = get()) }

    single<AgentSubagentDao> { provideAgentSubagentDao(database = get()) }

    single<McpServerDao> { provideMcpServerDao(database = get()) }

    single<McpOAuthCredentialDao> { provideMcpOAuthCredentialDao(database = get()) }

    single<McpOAuthTransactionDao> { provideMcpOAuthTransactionDao(database = get()) }

    single<AgentSkillDao> { provideAgentSkillDao(database = get()) }

    single<StorageMountBindingDao> { provideStorageMountBindingDao(database = get()) }

    single<ToolSettingsDao> { provideToolSettingsDao(database = get()) }

    single<AgentApprovalDao> { provideAgentApprovalDao(database = get()) }

    single<AgentApprovalAuditDao> { provideAgentApprovalAuditDao(database = get()) }

    single<AgentMessageFeedbackDao> { provideAgentMessageFeedbackDao(database = get()) }

    single<QuickPhraseDao> { provideQuickPhraseDao(database = get()) }

    single<HarnessRuntimeDao> { provideHarnessRuntimeDao(database = get()) }

    single<AndroidAppDao> { provideAndroidAppDao(database = get()) }

    single<BuildScriptDao> { provideBuildScriptDao(database = get()) }

    single<AgentTaskDao> { provideAgentTaskDao(database = get()) }

    single<WorkspaceFileAccess> { provideWorkspaceFileAccess(pathManager = get()) }

    single<CheckpointStore> { provideCheckpointStore(pathManager = get()) }

    single<RuntimeManager> { provideRuntimeManager(impl = get()) }

    single<DependencyManager> { provideDependencyManager(impl = get()) }

    single<ProcessRegistry> { provideProcessRegistry(impl = get()) }

    single<OkHttpClient> { provideOkHttpClient(provider = get()) }

    single<HttpClient> { provideKtorHttpClient(provider = get(), okHttpClient = get()) }

    single<FileDownloader> { provideFileDownloader(impl = get()) }

    single<ShellExecutor> { provideShellExecutor(impl = get()) }

    single<PtyManager> { providePtyManager(impl = get()) }

    single<LinuxRuntime> { provideLinuxRuntime(impl = get()) }

    single<LocalServiceLauncher> { provideLocalServiceLauncher(impl = get()) }

    single<AgentForegroundLauncher> { provideAgentForegroundLauncher(impl = get()) }

    single<AgentMcpForegroundLauncher> { AgentMcpForegroundLauncherImpl(context = get()) }

    single<WorkflowScheduleDispatcher> { provideDispatcher(context = get()) }

    factory<WebChatAgentGateway> { get<TianyanWebChatAgentGateway>() }

    single<RuntimeServiceController> { RuntimeServiceController(context = get()) }

    single<RuntimeLifecycleSupervisor> { RuntimeLifecycleSupervisor(context = get()) }

    factory<AgentForegroundLauncherImpl> { AgentForegroundLauncherImpl(context = get()) }

    single<AdbNotificationManager> {
        AdbNotificationManager(
            context = get(),
            embeddedAdbManager = get(),
            preferences = get(),
        )
    }

    single<TianyanWebChatAgentGateway> {
        TianyanWebChatAgentGateway(
            harnessLoop = get(),
            sessions = get(),
            models = get(),
            approvals = get(),
            tasks = get(),
            feedback = get(),
        )
    }

    single<AppForegroundTracker> { AppForegroundTracker() }

    single<WorkflowApprovalNotifier> { WorkflowApprovalNotifier(runManager = get(), foregroundTracker = get()) }

    single<WorkflowRunUiController> {
        WorkflowRunUiController(
            runManager = get(),
            hud = get(),
            appContext = get(),
        )
    }

    factory<WorkManagerScheduleDispatcher> { WorkManagerScheduleDispatcher(context = get()) }

    single<Set<ToolRuntimeAdapter>>(named("toolAdapters")) {
        setOf(get<HelloToolInstaller>(), get<CodexToolInstaller>())
    }
}
