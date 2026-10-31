package top.tianyan.app.di.core.database

import org.koin.dsl.module
import android.content.Context
import java.io.File
import top.tianyan.app.core.database.EncryptedRequestDiagnosticsRepository
import top.tianyan.app.core.database.RequestDiagnosticsRepository
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.core.database.AgencyAgentCatalogLoader
import top.tianyan.app.core.database.AgentApprovalAuditRepository
import top.tianyan.app.core.database.AgentApprovalRepository
import top.tianyan.app.core.database.AgentContextRepository
import top.tianyan.app.core.database.AgentMessageFeedbackRepository
import top.tianyan.app.core.database.MessageFeedbackService
import top.tianyan.app.core.database.AgentSkillRepository
import top.tianyan.app.core.database.AgentSubagentRepository
import top.tianyan.app.core.database.AiModelRepository
import top.tianyan.app.core.database.AndroidAppRepository
import top.tianyan.app.core.database.repository.BotAssignmentRepository
import top.tianyan.app.core.database.repository.BotGroupSessionRepository
import top.tianyan.app.core.database.repository.BotMemberGroupRepository
import top.tianyan.app.core.database.BuildScriptRepository
import top.tianyan.app.core.database.DatabaseBackupRepository
import top.tianyan.app.core.database.RoomDatabaseBackupRepository
import top.tianyan.app.core.database.HarnessBlobStore
import top.tianyan.app.core.database.HarnessRuntimeRepository
import top.tianyan.app.core.database.HarnessSessionRepository
import top.tianyan.app.core.database.RedTeamFactRepository
import top.tianyan.app.core.database.RoomRedTeamFactRepository
import top.tianyan.app.core.database.McpOAuthCredentialRepository
import top.tianyan.app.core.database.McpServerRepository
import top.tianyan.app.core.database.QuickPhraseRepository
import top.tianyan.app.core.database.RoomAgentContextRepository
import top.tianyan.app.core.database.RoomAiModelRepository
import top.tianyan.app.core.database.RoomAndroidAppRepository
import top.tianyan.app.core.database.RoomBuildScriptRepository
import top.tianyan.app.core.database.RoomBotAssignmentRepository
import top.tianyan.app.core.database.RoomBotGroupSessionRepository
import top.tianyan.app.core.database.RoomBtwQueryRepository
import top.tianyan.app.core.database.RoomHarnessRuntimeRepository
import top.tianyan.app.core.database.RoomHarnessSessionRepository
import top.tianyan.app.core.database.RoomQuickPhraseRepository
import top.tianyan.app.core.database.RoomTerminalSessionRepository
import top.tianyan.app.core.database.RoomWorkflowRepository
import top.tianyan.app.core.database.RoomWorkflowScheduleStore
import top.tianyan.app.core.database.RoomWorkspaceRepository
import top.tianyan.app.core.database.StorageMountBindingRepository
import top.tianyan.app.core.database.TerminalSessionRepository
import top.tianyan.app.core.database.ToolSettingsRepository
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkspaceRepository
import top.tianyan.app.core.database.repository.BotScheduleRepository
import top.tianyan.app.core.database.repository.GroupChatRepository
import top.tianyan.app.core.database.repository.PersonaBotRepository
import top.tianyan.app.core.database.repository.RoomBotMemberGroupRepository
import top.tianyan.app.core.database.repository.RoomBotScheduleRepository
import top.tianyan.app.core.database.repository.RoomGroupChatRepository
import top.tianyan.app.core.database.repository.RoomPersonaBotRepository
import top.tianyan.app.core.database.task.AgentTaskRepository
import top.tianyan.app.core.database.task.RoomAgentTaskRepository

/** Dependency registrations owned by the core:database module. */
val coreDatabaseModule = module {
    single<RequestDiagnosticsRepository> {
        EncryptedRequestDiagnosticsRepository(
            location = { File(get<Context>().noBackupFilesDir, "request-diagnostics/requests.enc") },
            encrypt = { get<SecretManager>().encrypt(it) },
            decrypt = { get<SecretManager>().decrypt(it) },
        )
    }
    single<DatabaseBackupRepository> { RoomDatabaseBackupRepository(database = get()) }
    single<AgencyAgentCatalogLoader> { AgencyAgentCatalogLoader(context = get(), json = get()) }

    single<AgentApprovalRepository> { AgentApprovalRepository(dao = get()) }

    single<AgentApprovalAuditRepository> { AgentApprovalAuditRepository(dao = get()) }

    single<AgentMessageFeedbackRepository> { AgentMessageFeedbackRepository(dao = get()) }

    single<MessageFeedbackService> { MessageFeedbackService(feedback = get(), runtime = get()) }

    single<AgentSkillRepository> { AgentSkillRepository(dao = get()) }

    single<AgentSubagentRepository> { AgentSubagentRepository(dao = get(), catalogLoader = get()) }

    single<HarnessBlobStore> { HarnessBlobStore(context = get()) }

    single<RoomHarnessRuntimeRepository> { RoomHarnessRuntimeRepository(dao = get(), blobStore = get()) }

    single<McpOAuthCredentialRepository> {
        McpOAuthCredentialRepository(
            credentials = get(),
            transactions = get(),
            secretManager = get(),
        )
    }

    single<McpServerRepository> { McpServerRepository(dao = get(), secretManager = get()) }

    single<RoomBuildScriptRepository> { RoomBuildScriptRepository(dao = get()) }

    single<RoomAndroidAppRepository> { RoomAndroidAppRepository(dao = get()) }

    single<RoomAiModelRepository> { RoomAiModelRepository(dao = get()) }

    single<RoomHarnessSessionRepository> { RoomHarnessSessionRepository(dao = get()) }
    single<RedTeamFactRepository> { RoomRedTeamFactRepository(dao = get()) }

    single<RoomWorkspaceRepository> { RoomWorkspaceRepository(dao = get()) }

    single<RoomTerminalSessionRepository> { RoomTerminalSessionRepository(dao = get()) }

    single<RoomAgentContextRepository> { RoomAgentContextRepository(dao = get()) }

    single<RoomQuickPhraseRepository> { RoomQuickPhraseRepository(dao = get()) }

    factory<AiModelRepository> { get<RoomAiModelRepository>() }

    factory<HarnessSessionRepository> { get<RoomHarnessSessionRepository>() }

    factory<WorkspaceRepository> { get<RoomWorkspaceRepository>() }

    single<RoomPersonaBotRepository> { RoomPersonaBotRepository(dao = get(), groupSessionDao = get()) }

    factory<PersonaBotRepository> { get<RoomPersonaBotRepository>() }

    single<RoomGroupChatRepository> { RoomGroupChatRepository(dao = get(), json = get()) }

    factory<GroupChatRepository> { get<RoomGroupChatRepository>() }

    single<RoomBotMemberGroupRepository> { RoomBotMemberGroupRepository(dao = get()) }

    factory<BotMemberGroupRepository> { get<RoomBotMemberGroupRepository>() }

    single<RoomBotScheduleRepository> { RoomBotScheduleRepository(dao = get(), deliveries = get()) }

    factory<BotScheduleRepository> { get<RoomBotScheduleRepository>() }

    single<top.tianyan.app.core.database.repository.TaskBudgetRepository> { top.tianyan.app.core.database.repository.RoomTaskBudgetRepository(get<top.tianyan.app.core.database.BotAssignmentDao>()) }
    single<RoomBotAssignmentRepository> { RoomBotAssignmentRepository(dao = get(), json = get()) }

    factory<BotAssignmentRepository> { get<RoomBotAssignmentRepository>() }

    single<RoomBotGroupSessionRepository> { RoomBotGroupSessionRepository(dao = get()) }

    factory<BotGroupSessionRepository> { get<RoomBotGroupSessionRepository>() }

    single<RoomBtwQueryRepository> { RoomBtwQueryRepository(dao = get()) }

    factory<top.tianyan.app.core.database.repository.BtwQueryRepository> { get<RoomBtwQueryRepository>() }

    factory<TerminalSessionRepository> { get<RoomTerminalSessionRepository>() }

    factory<AgentContextRepository> { get<RoomAgentContextRepository>() }

    factory<AndroidAppRepository> { get<RoomAndroidAppRepository>() }

    factory<QuickPhraseRepository> { get<RoomQuickPhraseRepository>() }

    factory<HarnessRuntimeRepository> { get<RoomHarnessRuntimeRepository>() }

    factory<BuildScriptRepository> { get<RoomBuildScriptRepository>() }

    single<StorageMountBindingRepository> { StorageMountBindingRepository(dao = get()) }

    single<ToolSettingsRepository> { ToolSettingsRepository(dao = get()) }

    single<RoomWorkflowScheduleStore> { RoomWorkflowScheduleStore(dao = get()) }

    single<RoomWorkflowRepository> { RoomWorkflowRepository(dao = get(), json = get()) }

    factory<WorkflowRepository> { get<RoomWorkflowRepository>() }

    single<RoomAgentTaskRepository> { RoomAgentTaskRepository(dao = get()) }

    factory<AgentTaskRepository> { get<RoomAgentTaskRepository>() }
}
