package top.tianyan.app.di

import android.content.Context
import androidx.room.Room
import java.io.File
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.MIGRATION_27_28
import top.tianyan.app.core.database.MIGRATION_28_29
import top.tianyan.app.core.database.MIGRATION_30_31
import top.tianyan.app.core.database.MIGRATION_31_32
import top.tianyan.app.core.database.MIGRATION_33_34
import top.tianyan.app.core.database.MIGRATION_34_35
import top.tianyan.app.core.database.MIGRATION_35_36
import top.tianyan.app.core.database.MIGRATION_36_37
import top.tianyan.app.core.database.MIGRATION_37_38
import top.tianyan.app.core.database.MIGRATION_38_39
import top.tianyan.app.core.database.MIGRATION_39_40
import top.tianyan.app.core.database.MIGRATION_40_41
import top.tianyan.app.core.database.MIGRATION_41_42
import top.tianyan.app.core.database.MIGRATION_42_43
import top.tianyan.app.core.database.MIGRATION_43_44
import top.tianyan.app.core.database.MIGRATION_44_45
import top.tianyan.app.core.database.MIGRATION_45_46
import top.tianyan.app.core.database.MIGRATION_46_47
import top.tianyan.app.core.database.MIGRATION_47_48
import top.tianyan.app.core.database.MIGRATION_48_49
import top.tianyan.app.core.database.MIGRATION_49_50
import top.tianyan.app.core.database.MIGRATION_50_51
import top.tianyan.app.core.database.MIGRATION_51_52
import top.tianyan.app.core.database.MIGRATION_52_53
import top.tianyan.app.core.database.MIGRATION_53_54
import top.tianyan.app.core.database.MIGRATION_54_55
import top.tianyan.app.core.database.MIGRATION_55_56
import top.tianyan.app.core.database.MIGRATION_56_57
import top.tianyan.app.core.database.MIGRATION_57_58
import top.tianyan.app.core.database.MIGRATION_58_59
import top.tianyan.app.core.database.MIGRATION_62_63
import top.tianyan.app.core.database.MIGRATION_63_64
import top.tianyan.app.core.database.MIGRATION_64_65
import top.tianyan.app.core.database.MIGRATION_65_66
import top.tianyan.app.core.database.MIGRATION_66_67
import top.tianyan.app.core.database.MIGRATION_67_68
import top.tianyan.app.core.database.MIGRATION_68_69
import top.tianyan.app.core.database.MIGRATION_69_70
import top.tianyan.app.core.database.MIGRATION_71_72
import top.tianyan.app.core.database.MIGRATION_73_74
import top.tianyan.app.core.database.MIGRATION_78_79
import top.tianyan.app.core.database.MIGRATION_79_80
import top.tianyan.app.core.database.MIGRATION_80_81
import top.tianyan.app.core.database.MIGRATION_81_82
import top.tianyan.app.core.database.MIGRATION_82_83
import top.tianyan.app.core.database.MIGRATION_83_84
import top.tianyan.app.core.database.MIGRATION_77_78
import top.tianyan.app.core.database.MIGRATION_76_77
import top.tianyan.app.core.database.MIGRATION_75_76
import top.tianyan.app.core.database.MIGRATION_74_75
import top.tianyan.app.core.database.MIGRATION_72_73
import top.tianyan.app.core.database.MIGRATION_70_71
import top.tianyan.app.core.database.MIGRATION_61_62
import top.tianyan.app.core.database.MIGRATION_60_61
import top.tianyan.app.core.database.MIGRATION_59_60
import top.tianyan.app.core.database.BotAssignmentDao
import top.tianyan.app.core.database.BotGroupSessionDao
import top.tianyan.app.core.database.BtwQueryDao
import top.tianyan.app.core.database.BotMemberGroupDao
import top.tianyan.app.core.database.BotScheduleDeliveryDao
import top.tianyan.app.core.database.BotScheduleDao
import top.tianyan.app.core.database.GroupChatDao
import top.tianyan.app.core.database.PersonaBotDao
import top.tianyan.app.core.database.WorkflowDao
import top.tianyan.app.core.database.WorkflowScheduleDao
import top.tianyan.app.core.database.BuildScriptDao
import top.tianyan.app.core.database.task.AgentTaskDao
import top.tianyan.app.core.database.ToolDao
import top.tianyan.app.core.database.InstallLogDao
import top.tianyan.app.core.database.InstallTaskDao
import top.tianyan.app.core.database.RuntimeDao
import top.tianyan.app.core.database.HarnessSessionDao
import top.tianyan.app.core.database.AiModelDao
import top.tianyan.app.core.database.WorkspaceDao
import top.tianyan.app.core.database.TerminalSessionDao
import top.tianyan.app.core.database.AgentSubagentDao
import top.tianyan.app.core.database.AgentSkillDao
import top.tianyan.app.core.database.McpServerDao
import top.tianyan.app.core.database.McpOAuthCredentialDao
import top.tianyan.app.core.database.McpOAuthTransactionDao
import top.tianyan.app.core.database.StorageMountBindingDao
import top.tianyan.app.core.database.ToolSettingsDao
import top.tianyan.app.core.database.AgentApprovalAuditDao
import top.tianyan.app.core.database.AgentApprovalDao
import top.tianyan.app.core.database.AgentMessageFeedbackDao
import top.tianyan.app.core.database.QuickPhraseDao
import top.tianyan.app.core.database.HarnessRuntimeDao
import top.tianyan.app.core.database.AndroidAppDao
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.checkpoint.CheckpointStore
import top.tianyan.app.harness.checkpoint.FileCheckpointPersistence
import top.tianyan.app.core.database.AgentContextDao
import top.tianyan.app.core.database.RoomWorkflowScheduleStore
import top.tianyan.app.core.database.WorkflowScheduleStore
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.core.tools.RuntimeManager
import top.tianyan.app.core.tools.RuntimeManagerImpl
import top.tianyan.app.core.tools.DependencyManager
import top.tianyan.app.core.tools.DependencyManagerImpl
import top.tianyan.app.runtime.shell.ProcessRegistry
import top.tianyan.app.runtime.shell.ProcessRegistryImpl
import top.tianyan.app.core.network.HttpClientProvider
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.network.ResumableFileDownloader
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.LinuxRuntimeImpl
import top.tianyan.app.runtime.shell.ProcessShellExecutor
import top.tianyan.app.runtime.shell.ShellExecutor
import top.tianyan.app.runtime.pty.PtyManager
import top.tianyan.app.runtime.pty.NativePtyManager
import top.tianyan.app.runtime.service.LocalServiceLauncher
import top.tianyan.app.runtime.service.LocalServiceLauncherImpl
import top.tianyan.app.service.AgentForegroundLauncherImpl
import top.tianyan.app.harness.AgentForegroundLauncher
import io.ktor.client.HttpClient
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


object AppModule {

    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 请求/存储时省略 null 字段：未配置的 reasoning_content 不会发给非推理模型
        explicitNulls = false
    }

    fun provideDatabase(context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "tianyan.db")
            .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48, MIGRATION_48_49, MIGRATION_49_50, MIGRATION_50_51, MIGRATION_51_52, MIGRATION_52_53, MIGRATION_53_54, MIGRATION_54_55, MIGRATION_55_56, MIGRATION_56_57, MIGRATION_57_58, MIGRATION_58_59, MIGRATION_59_60, MIGRATION_60_61, MIGRATION_61_62, MIGRATION_62_63, MIGRATION_63_64, MIGRATION_64_65, MIGRATION_65_66, MIGRATION_66_67, MIGRATION_67_68, MIGRATION_68_69, MIGRATION_69_70, MIGRATION_70_71, MIGRATION_71_72, MIGRATION_72_73, MIGRATION_73_74, MIGRATION_74_75, MIGRATION_75_76, MIGRATION_76_77, MIGRATION_77_78, MIGRATION_78_79, MIGRATION_79_80, MIGRATION_80_81, MIGRATION_81_82, MIGRATION_82_83, MIGRATION_83_84)
            .build()
    }

    fun provideToolDao(database: AppDatabase): ToolDao = database.toolDao()

    fun provideInstallLogDao(database: AppDatabase): InstallLogDao = database.installLogDao()

    fun provideInstallTaskDao(database: AppDatabase): InstallTaskDao = database.installTaskDao()

    fun provideWorkflowDao(database: AppDatabase): WorkflowDao = database.workflowDao()

    fun provideWorkflowScheduleDao(database: AppDatabase): WorkflowScheduleDao = database.workflowScheduleDao()

    fun provideWorkflowScheduleStore(store: RoomWorkflowScheduleStore): WorkflowScheduleStore = store

    fun providePersonaBotDao(database: AppDatabase): PersonaBotDao = database.personaBotDao()

    fun provideBotMemberGroupDao(database: AppDatabase): BotMemberGroupDao = database.botMemberGroupDao()

    fun provideGroupChatDao(database: AppDatabase): GroupChatDao = database.groupChatDao()

    fun provideBotScheduleDeliveryDao(database: AppDatabase): BotScheduleDeliveryDao = database.botScheduleDeliveryDao()

    fun provideBotScheduleDao(database: AppDatabase): BotScheduleDao = database.botScheduleDao()

    fun provideBotAssignmentDao(database: AppDatabase): BotAssignmentDao = database.botAssignmentDao()

    fun provideBotGroupSessionDao(database: AppDatabase): BotGroupSessionDao = database.botGroupSessionDao()

    fun provideBtwQueryDao(database: AppDatabase): BtwQueryDao = database.btwQueryDao()

    fun provideRuntimeDao(database: AppDatabase): RuntimeDao = database.runtimeDao()

    fun provideHarnessSessionDao(database: AppDatabase): HarnessSessionDao = database.harnessSessionDao()

    fun provideAiModelDao(database: AppDatabase): AiModelDao = database.aiModelDao()

    fun provideWorkspaceDao(database: AppDatabase): WorkspaceDao = database.workspaceDao()

    fun provideTerminalSessionDao(database: AppDatabase): TerminalSessionDao = database.terminalSessionDao()

    fun provideAgentContextDao(database: AppDatabase): AgentContextDao = database.agentContextDao()

    fun provideAgentSubagentDao(database: AppDatabase): AgentSubagentDao = database.agentSubagentDao()

    fun provideMcpServerDao(database: AppDatabase): McpServerDao = database.mcpServerDao()

    fun provideMcpOAuthCredentialDao(database: AppDatabase): McpOAuthCredentialDao = database.mcpOAuthCredentialDao()

    fun provideMcpOAuthTransactionDao(database: AppDatabase): McpOAuthTransactionDao = database.mcpOAuthTransactionDao()

    fun provideAgentSkillDao(database: AppDatabase): AgentSkillDao = database.agentSkillDao()

    fun provideStorageMountBindingDao(database: AppDatabase): StorageMountBindingDao = database.storageMountBindingDao()

    fun provideToolSettingsDao(database: AppDatabase): ToolSettingsDao = database.toolSettingsDao()

    fun provideAgentApprovalDao(database: AppDatabase): AgentApprovalDao = database.agentApprovalDao()

    fun provideAgentApprovalAuditDao(database: AppDatabase): AgentApprovalAuditDao = database.agentApprovalAuditDao()

    fun provideAgentMessageFeedbackDao(database: AppDatabase): AgentMessageFeedbackDao = database.agentMessageFeedbackDao()

    fun provideQuickPhraseDao(database: AppDatabase): QuickPhraseDao = database.quickPhraseDao()

    fun provideHarnessRuntimeDao(database: AppDatabase): HarnessRuntimeDao = database.harnessRuntimeDao()

    fun provideAndroidAppDao(database: AppDatabase): AndroidAppDao = database.androidAppDao()

    fun provideBuildScriptDao(database: AppDatabase): BuildScriptDao = database.buildScriptDao()

    fun provideAgentTaskDao(database: AppDatabase): AgentTaskDao = database.agentTaskDao()

    fun provideWorkspaceFileAccess(pathManager: RuntimePathManager): WorkspaceFileAccess =
        WorkspaceFileAccess(pathManager.workspaceDir)

    /** checkpoint 快照落盘到应用私有目录（linux-runtime/checkpoints/<sessionId>/），模型不可见。 */
    fun provideCheckpointStore(
        pathManager: RuntimePathManager,
    ): CheckpointStore =
        CheckpointStore().apply {
            persistence = FileCheckpointPersistence(
                File(pathManager.baseDir, "checkpoints"),
            )
        }

    fun provideRuntimeManager(impl: RuntimeManagerImpl): RuntimeManager = impl

    fun provideDependencyManager(impl: DependencyManagerImpl): DependencyManager = impl

    fun provideProcessRegistry(impl: ProcessRegistryImpl): ProcessRegistry = impl

    fun provideOkHttpClient(provider: HttpClientProvider): OkHttpClient = provider.create()

    fun provideKtorHttpClient(
        provider: HttpClientProvider,
        okHttpClient: OkHttpClient,
    ): HttpClient = provider.createKtorClient(okHttpClient)

    fun provideFileDownloader(impl: ResumableFileDownloader): FileDownloader = impl

    fun provideShellExecutor(impl: ProcessShellExecutor): ShellExecutor = impl

    fun providePtyManager(impl: NativePtyManager): PtyManager = impl

    fun provideLinuxRuntime(impl: LinuxRuntimeImpl): LinuxRuntime = impl

    fun provideLocalServiceLauncher(impl: LocalServiceLauncherImpl): LocalServiceLauncher = impl

    fun provideAgentForegroundLauncher(impl: AgentForegroundLauncherImpl): AgentForegroundLauncher = impl
}


