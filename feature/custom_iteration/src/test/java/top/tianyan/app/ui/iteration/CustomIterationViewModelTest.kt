package top.tianyan.app.ui.iteration

import android.app.Application
import java.io.File
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.iteration.engine.TianyanDevBuildCoordinator
import top.tianyan.app.iteration.engine.AssetReader
import top.tianyan.app.iteration.engine.CustomIterationBootstrap
import top.tianyan.app.iteration.engine.CustomIterationPaths
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ShellCommand

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CustomIterationViewModelTest {

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `blank branch is sent as main`() = runTest {
        val commands = mutableListOf<String>()
        val viewModel = viewModel { command ->
            commands += command.commandLine
            CommandResult(1, "", "stop", 0)
        }

        viewModel.startCloudBuild("   ")

        // 首个命令是 origin 探测（此 fake 返回失败 → 回退官方仓库）
        assertEquals("git remote get-url origin", commands.first())
        val ghCommands = commands.filter { it.startsWith("gh ") }
        assertTrue("expected a gh run list probe, got $ghCommands", ghCommands.isNotEmpty())
        assertTrue(ghCommands.first().contains("--branch 'main'"))
        assertTrue(ghCommands.first().contains("-R 'tianyan-lab/tianyan'"))
        assertTrue(viewModel.buildState.value is TianyanDevBuildCoordinator.BuildStatus.Failed)
    }

    @Test
    fun `injection in the branch becomes a failed state and does not run gh`() = runTest {
        val commands = mutableListOf<String>()
        val viewModel = viewModel { command ->
            commands += command.commandLine
            CommandResult(0, "", "", 0)
        }

        viewModel.startCloudBuild("main; rm -rf /")

        assertTrue(commands.isEmpty())
        val state = viewModel.buildState.value
        assertTrue(state is TianyanDevBuildCoordinator.BuildStatus.Failed)
        assertTrue((state as TianyanDevBuildCoordinator.BuildStatus.Failed).error.contains("分支名"))
    }

    @Test
    fun `a second trigger is ignored while the pipeline is in flight`() = runTest {
        val gate = CompletableDeferred<CommandResult>()
        val commands = mutableListOf<String>()
        val viewModel = viewModel { command ->
            commands += command.commandLine
            if (command.commandLine.startsWith("gh run list")) gate.await()
            CommandResult(1, "", "stop", 0)
        }

        viewModel.startCloudBuild("main")
        assertTrue(viewModel.buildState.value is TianyanDevBuildCoordinator.BuildStatus.Dispatching)
        viewModel.startCloudBuild("feature/other")

        assertEquals(1, commands.count { it.startsWith("gh run list") })
        assertTrue(commands.none { it.contains("--branch 'feature/other'") })
        gate.complete(CommandResult(1, "", "stop", 0))
    }

    @Test
    fun `startCustomIteration reports the real workspace path from the runtime contract`() = runTest {
        val root = File(System.getProperty("java.io.tmpdir"), "tianyan-vm-real-${System.nanoTime()}")
        val vm = viewModel(
            execute = { CommandResult(0, "", "", 0) },
            root = root,
            assets = assetReader(),
        )

        var prompt: String? = null
        vm.startCustomIteration { prompt = it }

        val state = vm.uiState.value
        assertTrue("must be ready: ${state.errorMessage}", state.workspaceReady)
        // 工作区必须落在 homeDir（PRoot → /root）之下，而不是某个写死的 filesDir 相对路径
        assertEquals(File(root, "home/${CustomIterationBootstrap.WORKSPACE_NAME}").absolutePath, state.workspacePath)
        assertTrue(File(state.workspacePath).isDirectory)
        assertTrue("clone target must stay empty", File(state.workspacePath).list().isNullOrEmpty())
        assertTrue(prompt!!.contains("/root/custom_tianyan"))
        assertTrue(prompt.contains(CustomIterationBootstrap.FORK_REPO_PLACEHOLDER))
        assertFalse(prompt.contains("构建目标仓库 = tianyan-lab/tianyan"))
    }

    @Test
    fun `startCustomIteration interpolates the fork parsed from origin`() = runTest {
        val root = File(System.getProperty("java.io.tmpdir"), "tianyan-vm-fork-${System.nanoTime()}")
        val vm = viewModel(
            execute = { CommandResult(0, "", "", 0) },
            root = root,
            assets = assetReader(),
            readOriginRemoteUrl = { "https://github.com/haotaoran520/tianyan.git\n" },
        )

        var prompt: String? = null
        vm.startCustomIteration { prompt = it }

        assertTrue(vm.uiState.value.workspaceReady)
        assertTrue(prompt!!.contains("构建目标仓库 = haotaoran520/tianyan"))
        assertFalse(prompt.contains("构建目标仓库 = tianyan-lab/tianyan"))
        assertFalse(File(root, "home/${CustomIterationBootstrap.WORKSPACE_NAME}/.github").exists())
    }

    @Test
    fun `unparseable origin keeps the prompt off the official dispatch target`() = runTest {
        val root = File(System.getProperty("java.io.tmpdir"), "tianyan-vm-bad-${System.nanoTime()}")
        val vm = viewModel(
            execute = { CommandResult(0, "", "", 0) },
            root = root,
            assets = assetReader(),
            readOriginRemoteUrl = { "https://gitlab.com/someone/tianyan.git" },
        )

        var prompt: String? = null
        vm.startCustomIteration { prompt = it }

        assertTrue(prompt!!.contains(CustomIterationBootstrap.FORK_REPO_PLACEHOLDER))
        assertTrue(prompt.contains("git remote get-url origin"))
        assertFalse(prompt.contains("构建目标仓库 = tianyan-lab/tianyan"))
    }

    @Test
    fun `startCustomIteration surfaces a friendly error when assets are missing`() = runTest {
        val root = File(System.getProperty("java.io.tmpdir"), "tianyan-vm-miss-${System.nanoTime()}")
        val vm = viewModel(
            execute = { CommandResult(0, "", "", 0) },
            root = root,
            assets = AssetReader { null },
        )

        var prompt: String? = null
        vm.startCustomIteration { prompt = it }

        val state = vm.uiState.value
        assertFalse("must not claim readiness", state.workspaceReady)
        val app = ApplicationProvider.getApplicationContext<Application>()
        assertEquals(app.getString(top.tianyan.app.feature.custom_iteration.R.string.iteration_error_bootstrap), state.errorMessage)
        assertNull("must not open a chat session on failure", prompt)
    }

    private fun assetReader() = AssetReader { path ->
        when (path) {
            CustomIterationBootstrap.ASSET_SKILL -> "skill".toByteArray()
            CustomIterationBootstrap.ASSET_WORKFLOW -> "TAIXU_DEV_BUILD".toByteArray()
            else -> null
        }
    }

    private fun viewModel(
        root: File = File(System.getProperty("java.io.tmpdir"), "tianyan-vm-${System.nanoTime()}"),
        assets: AssetReader = AssetReader { null },
        readOriginRemoteUrl: suspend () -> String? = { null },
        execute: suspend (ShellCommand) -> CommandResult,
    ) = CustomIterationViewModel(
        application = ApplicationProvider.getApplicationContext<Application>(),
        buildCoordinator = TianyanDevBuildCoordinator(
            shellRunner = TianyanDevBuildCoordinator.ShellRunner { execute(it) },
            sleep = {},
            clock = { 1_760_000_000_000L },
        ),
        resolvePaths = {
            CustomIterationPaths(
                hostHomeDir = File(root, "home"),
                attachmentsDir = File(root, "attachments"),
                workspaceDir = File(root, "workspace"),
            )
        },
        assets = assets,
        readOriginRemoteUrl = readOriginRemoteUrl,
    )
}
