package top.tianyan.app.plugin.packages

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import top.tianyan.app.core.model.*
import top.tianyan.app.plugin.api.*

/** Explicit version dispatch. v2 is strict; legacy unknown fields remain compatible. */
object PluginPackageProtocol {
    private val legacyJson = Json { ignoreUnknownKeys = true }
    private val strictJson = Json
    private val qualifiedId = Regex("[a-z][a-z0-9]*(?:\\.[a-z][a-z0-9-]*)+")
    private val toolId = Regex("[a-z0-9][a-z0-9-]{1,63}")
    private val digest = Regex("[a-f0-9]{64}")
    private val capabilities = setOf("NETWORK", "WORKSPACE_READ", "WORKSPACE_WRITE", "LOCAL_WEB")

    fun decode(text: String, local: Boolean = true): ToolManifest {
        require(text.toByteArray(Charsets.UTF_8).size <= 1024 * 1024) { "插件清单过大" }
        val root = strictJson.parseToJsonElement(text).jsonObject
        val schema = root["schemaVersion"]?.jsonPrimitive?.int ?: 1
        require(schema in 1..2) { "不支持的插件包 Schema：$schema" }
        val parsed = (if (schema == 1) legacyJson else strictJson).decodeFromString<ToolManifest>(text)
        val manifest = if (local) parsed.copy(source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE") else parsed
        validate(manifest)
        return manifest
    }

    fun validate(manifest: ToolManifest) {
        require(manifest.schemaVersion in 1..2) { "不支持的插件包 Schema" }
        require(toolId.matches(manifest.id)) { "非法工具 ID" }
        require(manifest.version.matches(Regex("[A-Za-z0-9][A-Za-z0-9._+-]{0,127}"))) { "版本不能用作安全目录名" }
        if (manifest.schemaVersion == 1) {
            require(manifest.plugin == null) { "v1 不能声明 v2 插件能力" }
            return
        }
        val spec = requireNotNull(manifest.plugin) { "v2 缺少 plugin 声明" }
        require(manifest.source == "LOCAL" && manifest.installMethod == "LOCAL_PACKAGE" && manifest.offlineOnly) { "v2 当前只支持自包含本地包" }
        require(qualifiedId.matches(spec.id) && spec.id.length <= 128) { "插件身份无效" }
        require(!spec.id.startsWith("tianyan.") && !spec.id.startsWith("legacy.")) { "插件使用了宿主保留身份" }
        require(PluginApiRange.CURRENT in PluginApiRange(spec.api.minimum, spec.api.maximum)) { "插件 SDK 不兼容" }
        require(spec.execution in setOf("PROCESS", "WEB")) { "当前包只支持 PROCESS 或静态 WEB 执行形态" }
        require(SemanticVersion.parse(manifest.version) != null) { "v2 版本必须为 SemVer" }
        if (spec.execution == "WEB") {
            require(spec.harness == null) { "WEB 不能声明进程 Harness 扩展" }
            WebPluginPackagePolicy.validate(manifest)
            spec.integrity.forEach { (path, sha) -> require(safePayloadPath(path) && digest.matches(sha)) { "payload 完整性声明无效" } }
            return
        }
        require(spec.webSource == WebPluginPackagePolicy.PACKAGE_SOURCE && spec.serviceToolId == null) { "PROCESS 不能声明 Web 页面来源或工具服务引用" }
        require(manifest.uninstallSteps.isEmpty()) { "v2 使用框架卸载，暂不接受任意卸载脚本" }
        require(manifest.commandLinks.size <= 64 && manifest.commandLinks.distinct().size == manifest.commandLinks.size &&
            manifest.commandLinks.all { it.matches(Regex("[a-z0-9][a-z0-9._+-]{0,63}")) }) { "命令入口无效或重复" }
        PackageHarnessPolicy.validate(manifest)
        val processExtensions = spec.extensions.filter { it.point == "PROCESS" }
        require(processExtensions.size <= 1 && processExtensions.all { it.id == "launch" } &&
            spec.extensions.size == spec.extensions.distinct().size &&
            spec.extensions.filter { it.point != "PROCESS" }.toSet() == PackageHarnessPolicy.extensions(manifest).toSet()) { "扩展声明与 Harness 元数据不一致" }
        require(processExtensions.isEmpty() || !manifest.launchCommand.isNullOrBlank()) { "启动扩展缺少命令" }
        require(spec.dependencies.size <= 64 && spec.dependencies.map { it.id }.distinct().size == spec.dependencies.size) { "插件依赖重复或过多" }
        spec.dependencies.forEach { dependency ->
            require(qualifiedId.matches(dependency.id) && dependency.id != spec.id && !dependency.id.startsWith("tianyan.")) { "进程插件依赖身份无效" }
            val minimum = requireNotNull(SemanticVersion.parse(dependency.minimumVersion)) { "依赖最低版本无效" }
            dependency.maximumVersionExclusive?.let {
                require(requireNotNull(SemanticVersion.parse(it)) > minimum) { "依赖版本区间无效" }
            }
        }
        require(spec.capabilities.size <= 64 && spec.capabilities.distinct().size == spec.capabilities.size) { "能力请求重复或过多" }
        spec.capabilities.forEach {
            require(it.name in capabilities && it.target.length <= 512 && '\u0000' !in it.target) { "不支持的能力请求" }
        }
        require(spec.resources.size <= 128 && spec.resources.map { it.key }.distinct().size == spec.resources.size) { "资源声明重复或过多" }
        spec.resources.forEach { resource ->
            require(qualifiedId.matches(resource.key) && resource.key.length <= 128) { "资源标识无效" }
            require(resource.sharing in setOf("EXCLUSIVE", "SHARED") && resource.removal == "RETAIN") { "不支持的资源生命周期" }
            require(safeResourcePath(resource.path, manifest.id)) { "资源路径必须属于插件或独立的 /opt 子目录" }
            require(resource.contentSha256?.let(digest::matches) != false) { "资源指纹无效" }
            require(resource.sharing != "SHARED" || resource.contentSha256 != null) { "共享资源必须固定内容指纹" }
        }
        require(spec.integrity.size <= 20_000) { "payload 文件过多" }
        spec.integrity.forEach { (path, sha) -> require(safePayloadPath(path) && digest.matches(sha)) { "payload 完整性声明无效" } }
    }

    fun identity(manifest: ToolManifest): String = manifest.plugin?.id ?: "legacy.tool-${manifest.id}"

    fun descriptor(manifest: ToolManifest): PluginDescriptor {
        validate(manifest)
        val spec = manifest.plugin
        return PluginDescriptor(
            id = identity(manifest), version = manifest.version,
            api = spec?.api?.let { PluginApiRange(it.minimum, it.maximum) } ?: PluginApiRange(),
            dependencies = spec?.dependencies?.map { it.id }?.toSet().orEmpty(),
            extensions = if (spec != null) spec.extensions.map { PluginExtension(it.id,
                PluginExtensionPoint.valueOf(it.point)) }
                else if (manifest.launchCommand != null) listOf(PluginExtension("launch", PluginExtensionPoint.PROCESS)) else emptyList(),
        )
    }

    fun receipt(manifest: ToolManifest): PluginInstallReceipt {
        require(!WebPluginPackagePolicy.isWeb(manifest)) { "静态 Web 包不能进入进程安装或命令资源收据" }
        return PluginInstallReceipt(
        toolId = manifest.id, pluginId = identity(manifest), version = manifest.version,
        dependencies = manifest.plugin?.dependencies.orEmpty(),
        capabilities = (manifest.permissions.map { PackageCapability(it) } + manifest.plugin?.capabilities.orEmpty()).distinct(),
        resources = manifest.plugin?.resources.orEmpty() + listOf(
            PackageResource("host.tool-${manifest.id}", "/opt/tianyan/tools/${manifest.id}"),
            PackageResource("host.data-${manifest.id}", "/opt/tianyan/data/${manifest.id}"),
        ) + (manifest.commandLinks.ifEmpty { listOf(manifest.id) }).distinct().map {
            PackageResource("host.command-$it", "/opt/tianyan/bin/$it")
        },
        )
    }

    fun safePayloadPath(path: String): Boolean = path.isNotBlank() && path.length <= 512 &&
        !path.startsWith('/') && '\\' !in path && ':' !in path && '\u0000' !in path &&
        path.split('/').none { it.isEmpty() || it == "." || it == ".." }

    private fun safeResourcePath(path: String, id: String): Boolean {
        if (!path.startsWith("/opt/") || !safePayloadPath(path.removePrefix("/"))) return false
        return !path.startsWith("/opt/tianyan") ||
            path == "/opt/tianyan/tools/$id" || path.startsWith("/opt/tianyan/tools/$id/") ||
            path == "/opt/tianyan/data/$id" || path.startsWith("/opt/tianyan/data/$id/") ||
            path.startsWith("/opt/tianyan/shared/")
    }
}
