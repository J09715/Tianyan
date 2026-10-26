package top.tianyan.app.core.tools

import android.app.Application
import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.model.*
import top.tianyan.app.runtime.*
import top.tianyan.app.runtime.rootfs.RootfsValidator
import top.tianyan.app.runtime.shell.*
import top.tianyan.app.runtime.tools.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PluginRecipeIntegrationTest {
    @Before fun configureNativeDirectory() {
        val context = RuntimeEnvironment.getApplication()
        context.applicationInfo.nativeLibraryDir = File(context.cacheDir, "native-libs").apply { mkdirs() }.path
    }
    private class RuntimeDouble {
        val selected = MutableStateFlow("debian")
        val commands = mutableListOf<Pair<ShellCommand, String?>>()
        var verify = CommandResult(0, "Unrelated application banner", "", 0)
        var failLinks = false
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getActiveDistroId" -> selected
                "getState" -> MutableStateFlow<RuntimeState>(RuntimeState.Ready)
                "execute" -> {
                    val command = args[0] as ShellCommand
                    commands += command to (args[1] as String?)
                    selected.value = "ubuntu"
                    when {
                        command.commandLine == "sample --version" -> verify
                        failLinks && command.commandLine.contains("printf '%s") -> CommandResult(1, "", "link failure", 0)
                        else -> CommandResult(0, "", "", 0)
                    }
                }
                else -> error("Unexpected runtime method ${method.name}")
            }
        } as LinuxRuntime
    }
    private fun installer(double: RuntimeDouble): GenericRecipeInstaller {
        val context = RuntimeEnvironment.getApplication()
        val manifest = ToolManifest(schemaVersion = 2, id = "sample", name = "Sample", description = "Test", version = "1.2.3",
            source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE", installSteps = listOf("install-sample"),
            verifyCommand = "sample --version", plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS"))
        val version = File(context.filesDir, "plugins/sample/1.2.3")
        File(version, "payload").mkdirs()
        File(version, "manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), manifest))
        val registry = ToolRegistry(context, OkHttpClient(), AppLogger(context, SensitiveDataRedactor { it }))
        val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
        val dependencies = object : DependencyManager {
            override fun requirements(manifest: ToolManifest) = emptyList<RuntimeRequirement>()
            override suspend fun acquire(requirement: RuntimeRequirement, toolId: String): AppResult<InstalledRuntime> = error("Offline package acquired dependency")
            override suspend fun release(runtimeId: String, toolId: String) = AppResult.Success(Unit)
        }
        val pinned = PinnedToolRuntime(double.runtime, "debian")
        return GenericRecipeInstaller(manifest, pinned, dependencies, ProviderManager(), ToolCommandLinker(pinned), LocalPluginPayloadManager(registry, paths))
    }
    @Test fun successfulInstallPinsDistributionAndRecordsManifestVersion() = runBlocking {
        val double = RuntimeDouble()
        val events = installer(double).install().toList()
        assertEquals("1.2.3", events.filterIsInstance<InstallEvent.Completed>().single().version)
        assertTrue(double.commands.isNotEmpty())
        assertTrue(double.commands.all { it.second == "debian" })
        assertEquals("ubuntu", double.selected.value)
        val context = RuntimeEnvironment.getApplication()
        assertTrue(File(context.filesDir, "linux-runtime/distros/debian/opt/tianyan/imports/sample").isDirectory)
    }
    @Test fun failedVerificationNeverFallsBackToExecutableExistence() = runBlocking {
        val double = RuntimeDouble().apply { verify = CommandResult(1, "", "verification failure", 0) }
        val events = installer(double).install().toList()
        assertTrue(events.any { it is InstallEvent.Failed })
        assertTrue(events.none { it is InstallEvent.Completed })
        assertFalse(double.commands.any { it.first.commandLine.startsWith("test -x /opt/tianyan/tools/sample/bin/") })
    }
    @Test fun failedLinkCreationCannotBeReportedAsInstalled() = runBlocking {
        val events = installer(RuntimeDouble().apply { failLinks = true }).install().toList()
        assertTrue(events.any { it is InstallEvent.Failed })
        assertTrue(events.none { it is InstallEvent.Completed })
    }
    @Test fun explicitCrossDistributionCommandIsRejected() = runBlocking {
        try { PinnedToolRuntime(RuntimeDouble().runtime, "debian").execute(ShellCommand("true"), "ubuntu"); fail("Cross distribution") }
        catch (_: IllegalArgumentException) { }
    }
    @Test fun programRollbackRestoresOldVersionAndRetainsDataAndGlobalSideEffects() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
        val transactions = InstallTransactionManager(paths, AppLogger(context, SensitiveDataRedactor { it }))
        val program = File(paths.tianyanToolsDir("debian"), "sample")
        program.mkdirs()
        File(program, "version").writeText("old")
        val data = File(paths.tianyanDataDir("debian"), "sample/data").apply { parentFile!!.mkdirs(); writeText("user data") }
        val transaction = transactions.begin("debian", "sample", preserveExisting = true)
        File(program, "version").writeText("new")
        val global = File(paths.distroOptDir("debian"), "shared-sdk/value").apply { parentFile!!.mkdirs(); writeText("new global content") }
        transactions.rollback(transaction)
        assertEquals("old", File(program, "version").readText())
        assertEquals("user data", data.readText())
        assertEquals("new global content", global.readText())
    }
    @Test fun interruptedReplacementRestoresRetainedSnapshot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
        val transactions = InstallTransactionManager(paths, AppLogger(context, SensitiveDataRedactor { it }))
        val program = File(paths.tianyanToolsDir("debian"), "sample").apply { mkdirs() }
        File(program, "version").writeText("old")
        transactions.begin("debian", "sample", preserveExisting = true)
        File(program, "version").writeText("partial new version")
        assertTrue(transactions.recover("debian", "sample", preserveExisting = true))
        assertEquals("old", File(program, "version").readText())
    }
}
