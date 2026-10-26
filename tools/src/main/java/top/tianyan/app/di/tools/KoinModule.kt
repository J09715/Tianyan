package top.tianyan.app.di.tools

import android.content.Context
import java.io.File
import org.koin.dsl.module
import org.koin.dsl.onClose
import top.tianyan.app.core.tools.ProviderManager
import top.tianyan.app.core.tools.AgentModelConnectionTester
import top.tianyan.app.core.tools.AgentModelDiscovery
import top.tianyan.app.core.tools.AgentProviderCatalog
import top.tianyan.app.core.tools.AiProfileBackupCodec
import top.tianyan.app.core.tools.AiProfileWriter
import top.tianyan.app.core.tools.DependencyManagerImpl
import top.tianyan.app.core.tools.DependencyResolver
import top.tianyan.app.core.tools.FlutterSdkDownloader
import top.tianyan.app.core.tools.InstallLogRepository
import top.tianyan.app.core.tools.InstallTaskRepository
import top.tianyan.app.core.tools.InstallTransactionManager
import top.tianyan.app.core.tools.LocalPluginPayloadManager
import top.tianyan.app.core.tools.McpCatalogUpdater
import top.tianyan.app.core.tools.ProviderRepository
import top.tianyan.app.core.tools.RuntimeManagerImpl
import top.tianyan.app.core.tools.RuntimeRepository
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.core.tools.ToolManagerToolchainInstalls
import top.tianyan.app.runtime.doctor.ToolchainInstalls
import top.tianyan.app.core.tools.ToolNotificationNotifier
import top.tianyan.app.core.tools.ToolRegistry
import top.tianyan.app.core.tools.ToolRepository
import top.tianyan.app.core.tools.ToolServiceController
import top.tianyan.app.runtime.tools.AuthUrlDetector
import top.tianyan.app.runtime.tools.CodexToolInstaller
import top.tianyan.app.runtime.tools.HelloToolInstaller
import top.tianyan.app.runtime.tools.RemoteScriptRunner
import top.tianyan.app.runtime.tools.RuntimeBinaryInstaller
import top.tianyan.app.runtime.tools.ToolCommandLinker
import top.tianyan.app.core.tools.ModelCredentialStore
import top.tianyan.app.core.tools.backup.BackupLocations
import top.tianyan.app.core.tools.backup.LocalBackupService
import top.tianyan.app.core.tools.skill.ClawHubClient
import top.tianyan.app.core.tools.skill.SkillCompatibilityEvaluator
import top.tianyan.app.core.tools.skill.SkillInstallationManager
import top.tianyan.app.core.tools.skill.SkillPackageInspector
import top.tianyan.app.core.tools.skill.SkillPackageParser
import top.tianyan.app.runtime.RuntimePathManager
import org.koin.core.qualifier.named

/** Dependency registrations owned by the tools module. */
val toolsModule = module {
    single { val local = get<top.tianyan.app.runtime.environment.LocalBackendAccess>()
        top.tianyan.app.core.tools.InstalledPluginCatalog(get(), get(), get(), local::requireLocal) }
        .onClose { it?.close() }
    single { top.tianyan.app.core.tools.WebPluginRepository(registry = get()) }
    single<BackupLocations> {
        val context = get<Context>()
        val paths = get<RuntimePathManager>()
        BackupLocations(context.filesDir, paths.attachmentsDir, paths.workspaceDir, File(context.cacheDir, "local-backup"))
    }
    single<LocalBackupService> {
        LocalBackupService(repository = get(), preferences = get(), locations = get())
    }
    single<ProviderManager> { ProviderManager(providerRepository = get()) }

    single<AgentModelConnectionTester> { AgentModelConnectionTester(http = get(), external = getOrNull()) }

    single<AgentModelDiscovery> { AgentModelDiscovery(http = get()) }

    single<AgentProviderCatalog> { AgentProviderCatalog(context = get(), installed = get()) }

    single<AiProfileBackupCodec> {
        AiProfileBackupCodec(
            aiModelDao = get(),
            providerRepository = get(),
            profileWriter = get(),
        )
    }

    single<AiProfileWriter> { AiProfileWriter(aiModelDao = get(), providerRepository = get()) }

    single<DependencyManagerImpl> { DependencyManagerImpl(resolver = get(), runtimeManager = get()) }

    single<DependencyResolver> { DependencyResolver() }

    single<FlutterSdkDownloader> {
        FlutterSdkDownloader(
            fileDownloader = get(),
            checksumVerifier = get(),
            pathManager = get(),
            json = get(),
        )
    }

    single<InstallLogRepository> { InstallLogRepository(dao = get()) }

    single<InstallTaskRepository> { InstallTaskRepository(dao = get()) }

    single<InstallTransactionManager> { InstallTransactionManager(pathManager = get(), logger = get()) }
    single { top.tianyan.app.core.tools.PluginReceiptStore(context = get()) }
    single { top.tianyan.app.core.tools.PackagePluginInventory(repository = get(), runtime = get()) }
    single { top.tianyan.app.core.tools.ToolPackageOperations(repository = get(), receipts = get(), hostGrants = get()) }

    single<LocalPluginPayloadManager> { LocalPluginPayloadManager(registry = get(), pathManager = get()) }

    single<ProviderRepository> { ProviderRepository(providerPreferences = get()) }
    single<ModelCredentialStore> { get<ProviderRepository>() }

    single<RuntimeManagerImpl> {
        RuntimeManagerImpl(
            linuxRuntime = get(),
            runtimeRepository = get(),
            runtimeBinaryInstaller = get(),
        )
    }

    single<RuntimeRepository> { RuntimeRepository(runtimeDao = get()) }

    single<ToolManager> {
        ToolManager(
            toolRepository = get(),
            installLogRepository = get(),
            installTaskRepository = get(),
            installTransactionManager = get(),
            dependencyManager = get(),
            linuxRuntime = get(),
            backgroundTaskRegistry = get(),
            providerManager = get(),
            toolCommandLinker = get(),
            notificationNotifier = get(),
            secretRedactor = get(),
            toolSettingsRepository = get(),
            settingsDataStore = get(),
            assetSynchronizer = get(),
            flutterSdkDownloader = get(),
            localPluginPayloadManager = get(),
            serviceController = get(),
            packageOperations = get(),
            installerAdapters = get(named("toolAdapters")),
            localAccess = get(),
        )
    }

    single<ToolchainInstalls> { ToolManagerToolchainInstalls(toolManager = get()) }

    single<ToolNotificationNotifier> { ToolNotificationNotifier(context = get()) }

    single<ToolRegistry> {
        ToolRegistry(
            context = get(),
            httpClient = get(),
            logger = get(),
        )
    }

    single<ToolRepository> { ToolRepository(toolDao = get(), toolRegistry = get()) }

    single<ToolServiceController> { ToolServiceController(linuxRuntime = get()) }

    /** MCP 能力仓库的「检查更新 / 一键升级」执行器：只依赖 Linux 运行时，联网都在装配脚本里。 */
    single<McpCatalogUpdater> { McpCatalogUpdater(linuxRuntime = get()) }

    single<AuthUrlDetector> { AuthUrlDetector() }

    single<CodexToolInstaller> {
        CodexToolInstaller(
            linuxRuntime = get(),
            dependencyManager = get(),
            providerManager = get(),
            remoteScriptRunner = get(),
            toolCommandLinker = get(),
        )
    }

    single<HelloToolInstaller> { HelloToolInstaller(linuxRuntime = get(), pathManager = get()) }

    single<RemoteScriptRunner> { RemoteScriptRunner(linuxRuntime = get()) }

    single<RuntimeBinaryInstaller> {
        RuntimeBinaryInstaller(
            pathManager = get(),
            linuxRuntime = get(),
            fileDownloader = get(),
            checksumVerifier = get(),
            tarStreamExtractor = get(),
        )
    }

    single<ToolCommandLinker> { ToolCommandLinker(linuxRuntime = get()) }

    single { SkillPackageParser() }

    single { SkillPackageInspector() }

    single { SkillCompatibilityEvaluator(toolRegistry = getOrNull()) }

    single { ClawHubClient(httpClient = get()) }

    single {
        SkillInstallationManager(
            packageParser = get(),
            inspector = get(),
            compatibilityEvaluator = get(),
            clawHubClient = get(),
            agentSkillRepository = get(),
        )
    }
}
