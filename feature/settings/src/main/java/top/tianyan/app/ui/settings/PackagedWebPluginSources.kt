package top.tianyan.app.ui.settings

import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.ToolManifest
import top.tianyan.app.core.tools.ToolManager
import top.tianyan.app.core.tools.WebPluginRepository
import top.tianyan.app.plugin.ui.web.WebPluginAssetLease
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.service.ProotToolServiceSource
import top.tianyan.app.runtime.service.ProotWebAssetService
import top.tianyan.app.runtime.service.ToolServiceDescriptor
import top.tianyan.app.runtime.service.ToolServiceWebSource

/** Called only inside the page's reserved resource owner; construction never launches Linux. */
class PackagedWebPluginSources(
    private val packages: WebPluginRepository,
    private val linux: LinuxRuntime,
    private val servers: RemoteServerRepository,
    private val tools: ToolManager,
) {
    suspend fun openProot(expected: ToolManifest): WebPluginAssetLease {
        val spec = requireNotNull(expected.plugin)
        require(spec.execution == "WEB" && spec.webSource == "PROOT_STATIC")
        // Bind bytes to the exact selected immutable version before preparing a lazy process source.
        val assets = packages.snapshot(expected)
        val source = ProotWebAssetService(linux, assets,
            isLocal = { servers.activeId.value == null && packages.isCurrent(expected) },
            ownerPluginId = spec.id)
        return WebPluginAssetLease(emptyMap(), available = { source.isAvailable },
            release = { source.close() }, load = { source.snapshot() })
    }

    /**
     * Page source over an installed tool service, chosen by native running state.
     *
     * When the tool center already runs the service the page only reads bounded responses and never
     * starts or stops it. Otherwise, and only when [allowStart] holds, the page starts the tool's own
     * declared service itself, then stops only that instance on close. A read-only service
     * declaration ([allowStart] false) can never cause a start. In both cases port, path, command and
     * environment come from native state through [ToolManager], never from the page.
     */
    suspend fun openService(toolId: String, allowStart: Boolean): WebPluginAssetLease {
        val spec = requireNotNull(tools.serviceSpec(toolId)) { "工具未声明本地服务" }
        if (tools.isGatewayRunning(toolId)) {
            val source = ToolServiceWebSource(
                ToolServiceDescriptor(
                    port = spec.port,
                    basePath = spec.path.trimEnd('/') + "/",
                    ownerPluginId = toolId,
                    distroId = requireNotNull(linux.activeDistroId.value) { "未选择本地系统" },
                ),
                isLocal = { servers.activeId.value == null },
            )
            return WebPluginAssetLease(emptyMap(), available = { source.isAvailable },
                release = { source.close() }, load = { source.snapshot() }, resolve = { source.resolve(it) },
                request = { source.read(it) }, stream = { path, emit -> source.stream(path, emit) })
        }
        require(allowStart) { "只读工具服务来源要求服务已在运行" }
        val launch = requireNotNull(tools.serviceLaunch(toolId)) { "工具不支持由插件页面启动服务" }
        val source = ProotToolServiceSource(
            linux, launch,
            isLocal = { servers.activeId.value == null },
        )
        return WebPluginAssetLease(emptyMap(), available = { source.isAvailable },
            release = { source.close() }, load = { source.snapshot() }, resolve = { source.resolve(it) },
            request = { source.read(it) }, stream = { path, emit -> source.stream(path, emit) })
    }

    suspend fun openToolService(toolId: String): WebPluginAssetLease = openService(toolId, allowStart = true)
}
