package top.tianyan.app.di

import android.app.Application
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import androidx.work.ListenableWorker
import androidx.work.testing.TestListenableWorkerBuilder
import java.lang.reflect.Proxy
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.koin.android.ext.koin.androidContext
import org.koin.androidx.workmanager.factory.KoinWorkerFactory
import org.koin.core.annotation.KoinInternalApi
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import org.koin.dsl.module
import org.koin.viewmodel.factory.KoinViewModelFactory
import org.robolectric.annotation.Config
import top.tianyan.app.core.database.AppDatabase
import top.tianyan.app.core.database.WorkflowRepository
import top.tianyan.app.core.database.WorkflowScheduleStore
import top.tianyan.app.harness.HarnessLoop
import top.tianyan.app.harness.session.InteractiveSessionControl
import top.tianyan.app.harness.session.PromptSubmission
import top.tianyan.app.harness.session.SessionControl
import top.tianyan.app.harness.workflow.WorkflowRunManager
import top.tianyan.app.harness.workflow.WorkflowScheduler
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.ui.iteration.CustomIterationViewModel
import top.tianyan.app.workflow.WorkflowScheduleWorker
import top.tianyan.app.testing.Aarch64AwareRobolectricTestRunner

@RunWith(Aarch64AwareRobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, manifest = Config.NONE)
class KoinAndroidIntegrationTest {
    @After
    fun tearDown() = stopKoin()

    @OptIn(KoinInternalApi::class)
    @Test
    fun viewModelIdentityFollowsItsOwnerAndSurvivesProviderRecreation() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val application = startKoin {
            allowOverride(false)
            androidContext(context)
            modules(tianyanModule)
        }
        val firstOwner = ViewModelStore()
        val secondOwner = ViewModelStore()
        fun resolve(store: ViewModelStore): CustomIterationViewModel {
            val factory = KoinViewModelFactory(
                CustomIterationViewModel::class,
                application.koin.scopeRegistry.rootScope,
            )
            return ViewModelProvider.create(store, factory)[CustomIterationViewModel::class]
        }
        try {
            val first = resolve(firstOwner)
            assertSame(first, resolve(firstOwner))
            assertNotSame(first, resolve(secondOwner))
            assertSame(context, first.getApplication<Application>())
            firstOwner.clear()
            assertNotSame(first, resolve(firstOwner))
        } finally {
            firstOwner.clear()
            secondOwner.clear()
        }
    }

    @Test
    fun systemFactoryCreatesFreshWorkersSharingTheProcessManager() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val application = startKoin {
            androidContext(context)
            modules(tianyanModule, module {
                // Exercise the real worker and manager registrations without starting Linux or Room.
                single<LinuxRuntime> { unusedPort() }
                single<WorkflowRepository> { unusedPort() }
                single<WorkflowScheduleStore> { unusedPort() }
                single { WorkflowScheduler(emptySet(), get()) }
            })
        }
        val manager = application.koin.get<WorkflowRunManager>()
        val factory = KoinWorkerFactory()
        fun worker() = TestListenableWorkerBuilder<WorkflowScheduleWorker>(context)
            .setWorkerFactory(factory)
            .build()

        val first = worker()
        val second = worker()
        assertNotSame(first, second)
        assertSame(context, first.applicationContext)
        val managerField = WorkflowScheduleWorker::class.java.getDeclaredField("runManager").apply {
            isAccessible = true
        }
        assertSame(manager, managerField.get(first))
        assertSame(manager, managerField.get(second))
        // No schedule id: validates the actual worker is usable without dispatching any work.
        assertEquals(ListenableWorker.Result.failure(), first.doWork())
    }

    private inline fun <reified T> unusedPort(): T = Proxy.newProxyInstance(
        T::class.java.classLoader,
        arrayOf(T::class.java),
    ) { _, method, _ -> error("Unexpected external operation: ${method.name}") } as T

    @Test
    fun sessionEntryPointsResolveOneRuntimeAndRejectMissingRemoteSession() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
        val application = startKoin { androidContext(context); modules(tianyanModule) }
        val koin = application.koin
        try {
            val loop = koin.get<HarnessLoop>()
            val control = koin.get<SessionControl>()
            assertSame(loop, control)
            assertSame(loop, koin.get<InteractiveSessionControl>())
            listOf("tianyan.bot-memory", "tianyan.bot-management", "tianyan.bot-schedule").forEach { id ->
                val plugin = koin.get<top.tianyan.app.harness.plugins.HarnessPlugin>(org.koin.core.qualifier.named(id))
                assertEquals(id, plugin.descriptor.id)
            }
            val foreground = control.currentSessionId.value
            assertEquals(PromptSubmission.Rejected(
                PromptSubmission.Rejection.SESSION_NOT_FOUND),
                control.submit("missing-remote", "hello"))
            assertEquals(foreground, control.currentSessionId.value)
        } finally {
            koin.get<AppDatabase>().close()
        }
    }

    @Test
    fun idleBotResumptionKeepsTheOriginalWorkspaceAndExecutionTarget() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        context.applicationInfo.nativeLibraryDir = context.filesDir.absolutePath
        val application = startKoin { androidContext(context); modules(tianyanModule) }
        val koin = application.koin
        try {
            val bots = koin.get<top.tianyan.app.core.database.repository.PersonaBotRepository>()
            val sessions = koin.get<top.tianyan.app.core.database.HarnessSessionRepository>()
            val bot = top.tianyan.app.core.model.bot.Bot("resumed-bot", "Worker", "coder", "persona", "seed",
                sessionId = "resumed-session", createdAt = 1, updatedAt = 1)
            bots.saveBot(bot)
            val target = """{"distribution":"debian","workspace":"/workspace/project"}"""
            sessions.upsert(top.tianyan.app.core.database.HarnessSessionEntity(bot.sessionId, "Worker", 1, 1, null,
                workspace = "/workspace/project", executionTargetJson = target, approvalMode = "assisted",
                modelVariant = "stale-variant"))
            koin.get<top.tianyan.app.harness.bot.BotOrchestrator>().ensureSession(bot)
            val resumed = sessions.findById(bot.sessionId)!!
            assertEquals("/workspace/project", resumed.workspace)
            assertEquals(target, resumed.executionTargetJson)
            assertEquals("full_access", resumed.approvalMode)
            assertNull(resumed.modelId)
            assertNull(resumed.modelVariant)
        } finally {
            koin.get<AppDatabase>().close()
        }
    }
}
