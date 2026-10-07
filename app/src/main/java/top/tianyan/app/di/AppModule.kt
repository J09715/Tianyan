package top.tianyan.app.di

import android.content.Context
import androidx.room.Room
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.MIGRATION_27_28
import top.tianyan.app.core.database.MIGRATION_28_29
import top.tianyan.app.core.database.MIGRATION_29_30
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
import top.tianyan.app.core.database.StorageMountBindingDao
import top.tianyan.app.core.database.ToolSettingsDao
import top.tianyan.app.core.database.AgentApprovalDao
import top.tianyan.app.core.database.QuickPhraseDao
import top.tianyan.app.core.database.WorkflowDao
import top.tianyan.app.core.database.HarnessRuntimeDao
import top.tianyan.app.core.database.AndroidAppDao
import top.tianyan.app.harness.WorkspaceFileAccess
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
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import io.ktor.client.HttpClient
import javax.inject.Singleton
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient


@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideJson(): Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // 请求/存储时省略 null 字段：未配置的 reasoning_content 不会发给非推理模型
        explicitNulls = false
    }

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return Room.databaseBuilder(context, AppDatabase::class.java, "tianyan.db")
            // 完整迁移链 27→47（含此前缺失的 29→30）：升级不再破坏性丢数据。
            .addMigrations(MIGRATION_27_28, MIGRATION_28_29, MIGRATION_29_30, MIGRATION_30_31, MIGRATION_31_32, MIGRATION_33_34, MIGRATION_34_35, MIGRATION_35_36, MIGRATION_36_37, MIGRATION_37_38, MIGRATION_38_39, MIGRATION_39_40, MIGRATION_40_41, MIGRATION_41_42, MIGRATION_42_43, MIGRATION_43_44, MIGRATION_44_45, MIGRATION_45_46, MIGRATION_46_47, MIGRATION_47_48)
            .build()
    }

    @Provides
    @Singleton
    fun provideToolDao(database: AppDatabase): ToolDao = database.toolDao()

    @Provides
    @Singleton
    fun provideInstallLogDao(database: AppDatabase): InstallLogDao = database.installLogDao()

    @Provides
    @Singleton
    fun provideInstallTaskDao(database: AppDatabase): InstallTaskDao = database.installTaskDao()

    @Provides
    @Singleton
    fun provideRuntimeDao(database: AppDatabase): RuntimeDao = database.runtimeDao()

    @Provides
    @Singleton
    fun provideHarnessSessionDao(database: AppDatabase): HarnessSessionDao = database.harnessSessionDao()

    @Provides
    @Singleton
    fun provideAiModelDao(database: AppDatabase): AiModelDao = database.aiModelDao()

    @Provides
    @Singleton
    fun provideWorkspaceDao(database: AppDatabase): WorkspaceDao = database.workspaceDao()

    @Provides
    @Singleton
    fun provideTerminalSessionDao(database: AppDatabase): TerminalSessionDao = database.terminalSessionDao()

    @Provides
    @Singleton
    fun provideAgentContextDao(database: AppDatabase): top.tianyan.app.core.database.AgentContextDao = database.agentContextDao()

    @Provides
    @Singleton
    fun provideAgentSubagentDao(database: AppDatabase): AgentSubagentDao = database.agentSubagentDao()

    @Provides
    @Singleton
    fun provideMcpServerDao(database: AppDatabase): McpServerDao = database.mcpServerDao()

    @Provides
    @Singleton
    fun provideAgentSkillDao(database: AppDatabase): AgentSkillDao = database.agentSkillDao()

    @Provides
    @Singleton
    fun provideStorageMountBindingDao(database: AppDatabase): StorageMountBindingDao = database.storageMountBindingDao()

    @Provides
    @Singleton
    fun provideToolSettingsDao(database: AppDatabase): ToolSettingsDao = database.toolSettingsDao()

    @Provides
    @Singleton
    fun provideAgentApprovalDao(database: AppDatabase): AgentApprovalDao = database.agentApprovalDao()

    @Provides
    @Singleton
    fun provideQuickPhraseDao(database: AppDatabase): QuickPhraseDao = database.quickPhraseDao()

    @Provides
    @Singleton
    fun provideWorkflowDao(database: AppDatabase): WorkflowDao = database.workflowDao()

    @Provides
    @Singleton
    fun provideHarnessRuntimeDao(database: AppDatabase): HarnessRuntimeDao = database.harnessRuntimeDao()

    @Provides
    @Singleton
    fun provideAndroidAppDao(database: AppDatabase): AndroidAppDao = database.androidAppDao()

    @Provides
    @Singleton
    fun provideBuildScriptDao(database: AppDatabase): BuildScriptDao = database.buildScriptDao()

    @Provides
    @Singleton
    fun provideAgentTaskDao(database: AppDatabase): AgentTaskDao = database.agentTaskDao()

    @Provides
    @Singleton
    fun provideWorkspaceFileAccess(pathManager: top.tianyan.app.runtime.RuntimePathManager): WorkspaceFileAccess =
        WorkspaceFileAccess(pathManager.workspaceDir)

    /** checkpoint 快照落盘到应用私有目录（linux-runtime/checkpoints/<sessionId>/），模型不可见。 */
    @Provides
    @Singleton
    fun provideCheckpointStore(
        pathManager: top.tianyan.app.runtime.RuntimePathManager,
    ): top.tianyan.app.harness.checkpoint.CheckpointStore =
        top.tianyan.app.harness.checkpoint.CheckpointStore().apply {
            persistence = top.tianyan.app.harness.checkpoint.FileCheckpointPersistence(
                java.io.File(pathManager.baseDir, "checkpoints"),
            )
        }

    @Provides
    @Singleton
    fun provideRuntimeManager(impl: RuntimeManagerImpl): RuntimeManager = impl

    @Provides
    @Singleton
    fun provideDependencyManager(impl: DependencyManagerImpl): DependencyManager = impl

    @Provides
    @Singleton
    fun provideProcessRegistry(impl: ProcessRegistryImpl): ProcessRegistry = impl

    @Provides
    @Singleton
    fun provideOkHttpClient(provider: HttpClientProvider): OkHttpClient = provider.create()

    @Provides
    @Singleton
    fun provideKtorHttpClient(
        provider: HttpClientProvider,
        okHttpClient: OkHttpClient,
    ): HttpClient = provider.createKtorClient(okHttpClient)

    @Provides
    @Singleton
    fun provideFileDownloader(impl: ResumableFileDownloader): FileDownloader = impl

    @Provides
    @Singleton
    fun provideShellExecutor(impl: ProcessShellExecutor): ShellExecutor = impl

    @Provides
    @Singleton
    fun providePtyManager(impl: NativePtyManager): PtyManager = impl

    @Provides
    @Singleton
    fun provideLinuxRuntime(impl: LinuxRuntimeImpl): LinuxRuntime = impl

    @Provides
    @Singleton
    fun provideLocalServiceLauncher(impl: LocalServiceLauncherImpl): LocalServiceLauncher = impl

    @Provides
    @Singleton
    fun provideAgentForegroundLauncher(impl: AgentForegroundLauncherImpl): AgentForegroundLauncher = impl
}


