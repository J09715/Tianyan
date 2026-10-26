package top.tianyan.app.core.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.lang.reflect.Proxy
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.database.*
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.datastore.ToolPreferences
import top.tianyan.app.core.model.*
import top.tianyan.app.core.network.ChecksumVerifier
import top.tianyan.app.core.network.FileDownloader
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.core.security.SecretRedactor
import top.tianyan.app.runtime.*
import top.tianyan.app.runtime.rootfs.RootfsValidator
import top.tianyan.app.runtime.scripts.RuntimeAssetSynchronizer
import top.tianyan.app.runtime.shell.*
import top.tianyan.app.runtime.tools.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PluginRecoveryTest {
    private fun manifest(version: String = "1.0.0") = ToolManifest(
        schemaVersion = 2, id = "sample", name = "Sample", description = "Recovery test", version = version,
        source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE", launchType = "web",
        installSteps = listOf("install-$version"), launchCommand = "launch-$version", verifyCommand = "verify-$version",
        commandLinks = listOf("sample"), environment = mapOf("PACKAGE_FLAVOR" to version),
        servicePort = if (version == "1.0.0") 8001 else 8002,
        plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS"),
    )

    @Test fun reinstallRepairsMissingBinaryWithoutRequiringOldVerification() = runBlocking {
        val fixture = Fixture()
        fixture.seed(manifest())
        File(fixture.program, "version").delete()
        val events = fixture.manager.install("sample").toList()
        assertTrue(events.toString(), events.any { it is InstallEvent.Completed })
        assertEquals("1.0.0", File(fixture.program, "version").readText())
        val commands = fixture.commands.map { it.commandLine }
        assertTrue(commands.indexOf("install-1.0.0") < commands.indexOf("verify-1.0.0"))
        assertEquals(1, commands.count { it == "verify-1.0.0" })
    }

    @Test fun failedUpgradeKeepsInstalledLaunchEnvironmentVerificationAndServiceConfig() = runBlocking {
        val fixture = Fixture()
        fixture.seed(manifest())
        fixture.import(manifest("2.0.0"))
        fixture.failInstallation = true
        val events = fixture.manager.install("sample").toList()
        assertTrue(events.any { it is InstallEvent.Failed })
        assertEquals("1.0.0", File(fixture.program, "version").readText())
        assertEquals("1.0.0", fixture.repository.findById("debian", "sample")!!.installedVersion)
        assertEquals("2.0.0", fixture.repository.manifest("sample")!!.version)
        // Rebuild the manager to exclude an in-memory manifest cache masking the failure.
        val restarted = fixture.newManager()
        assertTrue(restarted.launch("sample").isSuccess)
        assertEquals("launch-1.0.0", fixture.commands.last().commandLine)
        assertEquals("1.0.0", fixture.commands.last().environment["PACKAGE_FLAVOR"])
        assertTrue(restarted.verify("sample").healthy)
        assertEquals("verify-1.0.0", fixture.commands.last().commandLine)
        assertEquals(8001, restarted.serviceSpec("sample")!!.port)
    }

    @Test fun successfulUpgradeUsesCandidateVerificationThenSwitchesRuntimeConfig() = runBlocking {
        val fixture = Fixture()
        fixture.seed(manifest())
        fixture.import(manifest("2.0.0"))
        assertTrue(fixture.manager.update("sample").toList().any { it is InstallEvent.Completed })
        assertEquals("2.0.0", fixture.repository.findById("debian", "sample")!!.installedVersion)
        assertTrue(fixture.manager.launch("sample").isSuccess)
        assertEquals("launch-2.0.0", fixture.commands.last().commandLine)
        assertEquals("2.0.0", fixture.commands.last().environment["PACKAGE_FLAVOR"])
        assertEquals(8002, fixture.manager.serviceSpec("sample")!!.port)
    }

    @Test fun cancelledUpgradeKeepsOldRuntimeConfig() = runBlocking {
        val fixture = Fixture()
        fixture.seed(manifest())
        fixture.import(manifest("2.0.0"))
        fixture.cancelInstallation = true
        try { fixture.manager.install("sample").toList(); fail("Expected cancellation") }
        catch (_: CancellationException) { }
        assertEquals("1.0.0", File(fixture.program, "version").readText())
        assertTrue(fixture.newManager().launch("sample").isSuccess)
        assertEquals("launch-1.0.0", fixture.commands.last().commandLine)
    }

    @Test fun legacyBannerReceiptBindsPackageAndPreservesLegacyUninstallDuringMigration() = runBlocking {
        val fixture = Fixture()
        val legacy = manifest().copy(schemaVersion = 1, plugin = null, uninstallSteps = listOf("cleanup-legacy"))
        fixture.seed(legacy, "Sample runtime banner 2026")
        fixture.import(manifest("2.0.0"))
        assertEquals(legacy, fixture.operations.runtimeManifest("debian", "sample"))
        fixture.operations.uninstall("debian", "sample") { assertEquals(listOf("cleanup-legacy"), it.uninstallSteps) }
    }

    @Test fun missingInstalledManifestCannotFallBackToUpgradeCandidate() = runBlocking {
        val fixture = Fixture()
        fixture.seed(manifest())
        fixture.import(manifest("2.0.0"))
        File(fixture.context.filesDir, "plugins/sample/1.0.0/manifest.json").delete()
        try { fixture.manager.launch("sample"); fail("Missing installed manifest") }
        catch (_: IllegalArgumentException) { }
        assertTrue(fixture.commands.isEmpty())
    }

    private class Fixture {
        val context = RuntimeEnvironment.getApplication()
        val selected = MutableStateFlow("debian")
        val commands = mutableListOf<ShellCommand>()
        var failInstallation = false
        var cancelInstallation = false
        private val tools = mutableMapOf<Pair<String, String>, ToolEntity>()
        private val tasks = mutableMapOf<Pair<String, String>, InstallTaskEntity>()
        private val redactor = SecretRedactor()
        private val logger = AppLogger(context, redactor)
        private val paths: RuntimePathManager
        val program: File
        private val registry: ToolRegistry
        val repository: ToolRepository
        val operations: ToolPackageOperations
        val manager: ToolManager
        private val runtime = proxy<LinuxRuntime> { name, args -> when (name) {
            "getActiveDistroId" -> selected
            "getState" -> MutableStateFlow<RuntimeState>(RuntimeState.Ready)
            "listBackground" -> emptyList<ManagedProcess>()
            "execute" -> {
                val command = args[0] as ShellCommand
                commands += command
                if (command.commandLine.startsWith("install-")) {
                    File(program, "version").apply { parentFile!!.mkdirs(); writeText(command.commandLine.removePrefix("install-")) }
                    if (cancelInstallation) throw CancellationException("Cancelled recipe")
                }
                val failed = (failInstallation && command.commandLine.startsWith("install-")) ||
                    (command.commandLine.startsWith("verify-") && File(program, "version").takeIf { it.exists() }?.readText() != command.commandLine.removePrefix("verify-"))
                CommandResult(if (failed) 1 else 0, "", if (failed) "deliberate failure" else "", 0)
            }
            else -> error("Unexpected runtime method $name")
        } }

        init {
            context.applicationInfo.nativeLibraryDir = File(context.cacheDir, "native").apply { mkdirs() }.path
            context.applicationInfo.icon = android.R.drawable.ic_dialog_info
            paths = RuntimePathManager(context, RootfsValidator(ElfInspector()))
            program = File(paths.tianyanToolsDir("debian"), "sample")
            // A minimal remote registry keeps the test independent from app-module assets.
            File(context.filesDir, "registry/tools.json").apply {
                parentFile!!.mkdirs()
                writeText("""[{"id":"base-tool","name":"Base","description":"Base"}]""")
            }
            registry = ToolRegistry(context, OkHttpClient(), logger)
            repository = ToolRepository(proxy<ToolDao> { name, args ->
                val key = (args.getOrNull(0) as? String).orEmpty() to (args.getOrNull(1) as? String).orEmpty()
                when (name) {
                    "findById" -> tools[key]
                    "getForDistro" -> tools.values.filter { it.distroId == args[0] }
                    "upsert" -> { val tool = args[0] as ToolEntity; tools[tool.distroId to tool.id] = tool; Unit }
                    "updateState" -> { tools[key] = tools.getValue(key).copy(state = args[2] as String); Unit }
                    "updateStateAndInstalledVersion" -> { tools[key] = tools.getValue(key).copy(state = args[2] as String, installedVersion = args[3] as String?); Unit }
                    else -> error("Unexpected tool DAO method $name")
                }
            }, registry)
            operations = ToolPackageOperations(repository, PluginReceiptStore(File(context.filesDir, "receipts")))
            manager = newManager()
        }

        suspend fun import(manifest: ToolManifest) {
            val bytes = ByteArrayOutputStream()
            ZipOutputStream(bytes).use { zip ->
                zip.putNextEntry(ZipEntry("manifest.json"))
                zip.write(Json.encodeToString(ToolManifest.serializer(), manifest).toByteArray())
                zip.closeEntry()
            }
            LocalPluginStore(File(context.filesDir, "plugins"), context.cacheDir).import(
                { bytes.toByteArray().inputStream() }, null, manifest, {}, {},
            )
        }

        suspend fun seed(manifest: ToolManifest, installedVersion: String = manifest.version) {
            import(manifest)
            tools["debian" to "sample"] = manifest.toEntity("debian", null).copy(state = "INSTALLED", installedVersion = installedVersion)
            operations.record("debian", manifest, installedVersion)
            File(program, "version").apply { parentFile!!.mkdirs(); writeText(manifest.version) }
        }

        fun newManager(): ToolManager {
            val logs = InstallLogRepository(proxy<InstallLogDao> { name, _ -> if (name == "observeForTool") flowOf(emptyList<InstallLogEntity>()) else Unit })
            val taskRepository = InstallTaskRepository(proxy<InstallTaskDao> { name, args -> when (name) {
                "upsert" -> { val task = args[0] as InstallTaskEntity; tasks[task.distroId to task.toolId] = task; Unit }
                "findByTool" -> tasks[args[0] to args[1]]
                else -> error("Unexpected task DAO method $name")
            } })
            val dependencies = object : DependencyManager {
                override fun requirements(manifest: ToolManifest) = emptyList<RuntimeRequirement>()
                override suspend fun acquire(requirement: RuntimeRequirement, toolId: String): AppResult<InstalledRuntime> = error("Unexpected download")
                override suspend fun release(runtimeId: String, toolId: String) = AppResult.Success(Unit)
            }
            val downloader = object : FileDownloader { override fun download(request: top.tianyan.app.core.network.DownloadRequest): kotlinx.coroutines.flow.Flow<top.tianyan.app.core.network.DownloadEvent> = error("Unexpected download") }
            return ToolManager(repository, logs, taskRepository, InstallTransactionManager(paths, logger), dependencies,
                runtime, BackgroundTaskRegistry(), ProviderManager(), ToolCommandLinker(runtime), ToolNotificationNotifier(context), redactor,
                ToolSettingsRepository(proxy<ToolSettingsDao> { _, _ -> error("Unexpected settings access") }),
                ToolPreferences(SettingsDataStore(context, SecretManager())), RuntimeAssetSynchronizer(context, paths),
                FlutterSdkDownloader(downloader, ChecksumVerifier(), paths, Json), LocalPluginPayloadManager(registry, paths),
                ToolServiceController(runtime), operations, emptySet())
        }
    }
}

private inline fun <reified T> proxy(crossinline invoke: (String, Array<out Any?>) -> Any?): T =
    Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args -> invoke(method.name, args.orEmpty()) } as T
