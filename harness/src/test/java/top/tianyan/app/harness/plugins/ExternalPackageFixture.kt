package top.tianyan.app.harness.plugins

import java.io.File
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.*
import okhttp3.OkHttpClient
import org.robolectric.RuntimeEnvironment
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.core.database.ToolDao
import top.tianyan.app.core.database.ToolEntity
import top.tianyan.app.core.model.*
import top.tianyan.app.core.tools.*
import top.tianyan.app.harness.mcp.*
import top.tianyan.app.runtime.LinuxRuntime

internal class ExternalPackageFixture(scope: CoroutineScope) {
    val distro = MutableStateFlow("ubuntu")
    val rows = MutableStateFlow<List<ToolEntity>>(emptyList())
    var remote = false
    val calls = mutableListOf<Pair<McpServerConfig, JsonObject>>()
    val closed = mutableListOf<String>()
    var failClose = false
    private val context = RuntimeEnvironment.getApplication()
    private val registry: ToolRegistry
    private val operations: ToolPackageOperations
    val installed: InstalledPluginCatalog
    val external: ExternalHarnessPlugins
    val plugins: HarnessPluginRegistry

    init {
        File(context.filesDir, "registry/tools.json").apply {
            parentFile!!.mkdirs(); writeText("""[{"id":"base-tool","name":"Base","description":"Base"}]""")
        }
        registry = ToolRegistry(context, OkHttpClient(), AppLogger(context, SensitiveDataRedactor { it }))
        val dao = Proxy.newProxyInstance(ToolDao::class.java.classLoader, arrayOf(ToolDao::class.java)) { _, method, args ->
            when (method.name) {
                "observeForDistro" -> rows.map { all -> all.filter { it.distroId == args!![0] } }
                "getForDistro" -> rows.value.filter { it.distroId == args!![0] }
                "findById" -> rows.value.singleOrNull { it.distroId == args!![0] && it.id == args[1] }
                else -> error("Unexpected DAO method ${method.name}")
            }
        } as ToolDao
        val repository = ToolRepository(dao, registry)
        operations = ToolPackageOperations(repository, PluginReceiptStore(File(context.filesDir, "receipts")))
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, _ ->
            when (method.name) {
                "getActiveDistroId" -> distro
                "getState" -> MutableStateFlow<RuntimeState>(RuntimeState.Ready)
                else -> error("Unexpected runtime method ${method.name}")
            }
        } as LinuxRuntime
        installed = InstalledPluginCatalog(repository, operations, runtime, { check(!remote) }, scope)
        val transport = object : McpTransport {
            override suspend fun check(server: McpServerConfig) = error("Discovery must not launch a process")
            override suspend fun discover(server: McpServerConfig): List<McpToolInfo> = error("Discovery must be static")
            override suspend fun execute(server: McpServerConfig, toolName: String, arguments: JsonObject): Pair<Boolean, String> {
                calls += server to arguments
                return true to if (toolName == "provider") """{"content":"offline-response","toolCalls":[]}"""
                    else arguments.getValue("text").jsonPrimitive.content
            }
        }
        external = ExternalHarnessPlugins(installed, transport) { server -> closed += server; check(!failClose) { "Cleanup failed" } }
        plugins = HarnessPluginRegistry(emptyList(), external::plugins)
    }

    suspend fun import(version: String = "1.0.0"): ToolManifest {
        val root = java.nio.file.Files.createTempDirectory(context.cacheDir.toPath(), "plugin-source").toFile()
        File(root, "payload/bin").mkdirs()
        File(root, "payload/bin/sample").writeText("#!/bin/sh\necho fixture\n")
        val schema = Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"required":["text"],"additionalProperties":false}""").jsonObject
        val manifest = ToolManifest(2, "sample", "Sample", "Offline fixture", version = version,
            source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE", commandLinks = listOf("sample"),
            plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS",
                extensions = listOf(PackageExtension("echo", "TOOL"), PackageExtension("step", "WORKFLOW"), PackageExtension("offline", "PROVIDER")),
                harness = PackageHarnessSpec("sample", tools = listOf(PackageCallable("echo", "Echo", schema)),
                    nodes = listOf(PackageCallable("step", "Step", schema, "echo")), providers = listOf(PackageProvider("offline", "provider")))))
        File(root, "manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), manifest))
        val archive = PluginDebugPackager.pack(root)
        val uri = android.net.Uri.fromFile(archive)
        val preview = (registry.inspectLocal(uri) as AppResult.Success).data
        return (registry.importLocal(uri, expected = preview.manifest) as AppResult.Success).data
    }

    fun markInstalled(manifest: ToolManifest) {
        operations.record("ubuntu", manifest, manifest.version)
        rows.value = listOf(ToolEntity("ubuntu", manifest.id, manifest.name, manifest.description, "", "one_shot", "INSTALLED",
            manifestVersion = manifest.version, installedVersion = manifest.version))
    }
}
