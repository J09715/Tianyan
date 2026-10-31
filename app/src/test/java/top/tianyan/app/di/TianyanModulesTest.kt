package top.tianyan.app.di

import android.app.Application
import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.work.WorkerParameters
import org.junit.Assert.assertSame
import org.junit.Test
import org.koin.core.annotation.KoinExperimentalAPI
import org.koin.dsl.koinApplication
import org.koin.test.verify.verify
import org.koin.test.verify.definition
import org.koin.test.verify.injectedParameters
import java.io.File
import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngine
import okhttp3.OkHttpClient
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.harness.WorkspaceToolBackend
import top.tianyan.app.harness.core.ToolCheckpoints
import top.tianyan.app.harness.directory.CapabilityToolGateway
import top.tianyan.app.harness.subagent.SubagentLaneRunner
import top.tianyan.app.runtime.browser.tools.BrowserMcpTools
import top.tianyan.app.runtime.browser.tools.BrowserMcpResources
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import kotlinx.coroutines.Job
import top.tianyan.app.core.browser.BrowserPreferences
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.core.tools.backup.BackupLocations
import top.tianyan.app.harness.projection.CurrentSessionTracker
import top.tianyan.app.harness.diagnostics.RequestDiagnosticsStore
import top.tianyan.app.harness.workflow.VirtualScreenWorkflowRuns
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskRegistry
import top.tianyan.app.runtime.virtualdisplay.PhoneTaskState

@OptIn(KoinExperimentalAPI::class)
class TianyanModulesTest {
    @Test
    fun completeApplicationGraphHasAllConstructorDependencies() {
        tianyanModule.verify(
            // These arguments are assembled explicitly by provider lambdas, not container lookups.
            injections = injectedParameters(
                definition<WorkspaceFileAccess>(File::class),
                definition<WorkspaceToolBackend>(Function1::class),
                definition<CapabilityToolGateway>(Function5::class),
                definition<ToolCheckpoints<*, *>>(List::class),
                definition<BackupLocations>(File::class),
                definition<top.tianyan.app.core.tools.PluginReceiptStore>(File::class),
                definition<top.tianyan.app.harness.plugins.HarnessPluginRegistry>(List::class, Function0::class),
                // Bot plugin factories construct their immutable descriptors; these are not DI services.
                definition<top.tianyan.app.harness.plugins.HarnessPlugin>(top.tianyan.app.plugin.api.PluginDescriptor::class),
                definition<top.tianyan.app.harness.plugins.ExternalHarnessPlugins>(Function2::class, top.tianyan.app.harness.mcp.McpTransport::class),
                definition<top.tianyan.app.core.tools.InstalledPluginCatalog>(Function0::class, kotlinx.coroutines.CoroutineScope::class),
                definition<HttpClient>(HttpClientEngine::class),
                definition<OkHttpClient>(OkHttpClient.Builder::class),
                definition<SubagentLaneRunner>(Function0::class),
                definition<BrowserMcpTools>(List::class, Function1::class, BrowserPreferences::class),
                definition<BrowserMcpResources>(Function0::class),
                definition<top.tianyan.app.runtime.bridge.FullAccessGrant>(kotlinx.coroutines.flow.Flow::class, kotlinx.coroutines.CoroutineScope::class, Function0::class),
                // These callbacks/files are built by explicit provider lambdas or secondary constructors.
                definition<top.tianyan.app.runtime.SessionWorkspaceCatalog>(Function0::class, Function2::class),
                definition<top.tianyan.app.harness.environment.SessionExecutionTargets>(Function1::class),
                definition<top.tianyan.app.runtime.environment.LocalDistributionSelection>(kotlinx.coroutines.flow.Flow::class, kotlinx.coroutines.flow.StateFlow::class, kotlinx.coroutines.CoroutineScope::class),
                definition<top.tianyan.app.harness.mcp.StandaloneMcpScope>(Function0::class, kotlinx.coroutines.flow.Flow::class),
                definition<top.tianyan.app.harness.mcp.McpStdioTransport>(kotlinx.coroutines.flow.Flow::class, Function0::class),
                definition<top.tianyan.app.core.datastore.McpClientPreferences>(androidx.datastore.core.DataStore::class),
                definition<top.tianyan.app.core.datastore.McpToolPolicyPreferences>(androidx.datastore.core.DataStore::class),
                definition<top.tianyan.app.core.datastore.ChatBackgroundPreferences>(androidx.datastore.core.DataStore::class),
                definition<top.tianyan.app.core.datastore.ChatBackgroundImageStore>(File::class, Function1::class, Long::class),
                definition<top.tianyan.app.core.datastore.PluginPreferences>(androidx.datastore.core.DataStore::class),
                definition<top.tianyan.app.core.datastore.ContainerBackendPreferences>(androidx.datastore.core.DataStore::class),
                definition<top.tianyan.app.core.datastore.EncryptedRemoteServerRepository>(android.util.AtomicFile::class, Function1::class),
                // The build coordinator's adapter and default callbacks are constructed by its lambda.
                definition<top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator>(
                    top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator.ShellRunner::class, Function2::class, Function0::class, String::class,
                ),
                definition<top.tianyan.app.ui.iteration.CustomIterationViewModel>(
                    Function0::class, Function1::class, top.tianyan.app.iteration.engine.AssetReader::class,
                ),
            ),
            extraTypes = listOf(Context::class, Application::class, SavedStateHandle::class, WorkerParameters::class),
        )
    }

    @Test
    fun graphLoadsWithoutOverridesAndPreservesSharedSingletons() {
        val application = koinApplication {
            allowOverride(false)
            modules(tianyanModule)
        }
        try {
            val koin = application.koin
            assertSame(koin.get<SecretRedactor>(), koin.get<SensitiveDataRedactor>())
            assertSame(koin.get<CurrentSessionTracker>(), koin.get<CurrentSessionTracker>())
            assertSame(koin.get<RequestDiagnosticsStore>(), koin.get<RequestDiagnosticsStore>())
        } finally {
            application.close()
        }
    }

    @Test
    fun phoneWorkflowControlsResolveTheSharedRegistry() {
        val registry = PhoneTaskRegistry()
        val application = koinApplication {
            modules(tianyanModule, org.koin.dsl.module { single<PhoneTaskRegistry> { registry } })
        }
        try {
            val runs = application.koin.get<VirtualScreenWorkflowRuns>()
            assertSame(runs, application.koin.get<VirtualScreenWorkflowRuns>())
            val job = Job()
            runs.begin("test", job)
            runs.end("test", PhoneTaskState.COMPLETED)
            assertSame(registry, application.koin.get<PhoneTaskRegistry>())
            job.cancel()
        } finally { application.close() }
    }
}
