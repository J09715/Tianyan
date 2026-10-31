package top.tianyan.app.plugin.packages

import top.tianyan.app.core.model.PackageCapability
import top.tianyan.app.core.model.PackageExtension
import top.tianyan.app.core.model.ToolManifest

/** Static-only v2 Web packages. Metadata can select host operations, never executable callbacks. */
object WebPluginPackagePolicy {
    const val MAX_PAYLOAD_BYTES = 2 * 1024 * 1024L
    const val MAX_FILES = 63 // One additional slot is reserved for the host's theme.css.
    const val MAX_ARCHIVE_BYTES = MAX_PAYLOAD_BYTES + 1024 * 1024
    const val MAX_ARCHIVE_ENTRIES = 129
    const val MAX_TREE_ENTRIES = 1 + MAX_FILES * 16
    const val PACKAGE_SOURCE = "PACKAGE"
    const val PROOT_STATIC_SOURCE = "PROOT_STATIC"

    /**
     * Page served by a local service an installed PROCESS tool already runs. The package only names
     * the tool; the source is read-only and never starts the service.
     */
    const val TOOL_SERVICE_SOURCE = "TOOL_SERVICE"

    /**
     * Page served by a local service of the named installed PROCESS tool that the page may start
     * itself, then stop only that instance on close. Port, path, command and environment stay native.
     */
    const val PROOT_SERVICE_SOURCE = "PROOT_SERVICE"
    private val SERVICE_SOURCES = setOf(TOOL_SERVICE_SOURCE, PROOT_SERVICE_SOURCE)
    private val TOOL_ID = Regex("[a-z0-9][a-z0-9-]{1,63}")

    /**
     * Explicit capability registry: a packaged page may only request these structured host actions,
     * each shown with its own confirmation. Nothing else is admitted, and one-shot actions stay
     * one-shot because `PluginCapabilityInput.ONE_SHOT` owns that rule.
     */
    val APP_INFO = PackageCapability("APP_INFO", "application")
    val CLIPBOARD_WRITE = PackageCapability("CLIPBOARD_WRITE", "clipboard")
    val INSTALL_APK = PackageCapability("INSTALL_APK", "apk")
    val LOGCAT = PackageCapability("LOGCAT", "logcat")
    val SERVICE_WRITE = PackageCapability("SERVICE_WRITE", "service")
    private val CAPABILITIES = listOf(APP_INFO, CLIPBOARD_WRITE, INSTALL_APK, LOGCAT, SERVICE_WRITE)

    fun isWeb(manifest: ToolManifest) = manifest.schemaVersion == 2 && manifest.plugin?.execution == "WEB"

    fun validate(manifest: ToolManifest) {
        val spec = requireNotNull(manifest.plugin)
        require(spec.harness == null) { "WEB packages cannot register process callbacks" }
        require(isWeb(manifest)) { "不是静态 Web 插件" }
        require(spec.webSource in setOf(PACKAGE_SOURCE, PROOT_STATIC_SOURCE, TOOL_SERVICE_SOURCE, PROOT_SERVICE_SOURCE)) {
            "不支持的 Web 页面来源"
        }
        // A service source must name exactly one installed tool, and no other source may name any.
        val serviceSource = spec.webSource in SERVICE_SOURCES
        require((spec.serviceToolId != null) == serviceSource) { "只有工具服务来源才能引用已安装工具" }
        spec.serviceToolId?.let { require(TOOL_ID.matches(it)) { "工具服务引用的工具 ID 无效" } }
        require(manifest.name.isNotBlank() && manifest.name.length <= 128 && manifest.description.length in 1..2048) { "Web 插件名称或描述无效" }
        require(manifest.installSteps.isEmpty() && manifest.uninstallSteps.isEmpty() && manifest.environment.isEmpty() &&
            manifest.commandLinks.isEmpty() && manifest.launchCommand == null && manifest.verifyCommand == null &&
            manifest.dependencies.isEmpty() && manifest.permissions.isEmpty() && manifest.servicePort == null &&
            manifest.launchType == "one_shot" && manifest.servicePath == "/") { "静态 Web 插件不能声明脚本、进程、环境变量或服务" }
        require(spec.extensions == listOf(PackageExtension("main", "PAGE"))) { "Web 插件必须声明 main PAGE 扩展" }
        require(spec.dependencies.isEmpty() && spec.resources.isEmpty()) { "静态 Web 插件不支持依赖或全局资源" }
        require(spec.capabilities.distinct() == spec.capabilities && spec.capabilities.all { it in CAPABILITIES }) { "不支持的 Web 插件能力" }
        require(SERVICE_WRITE !in spec.capabilities || spec.webSource == TOOL_SERVICE_SOURCE) { "服务写入要求工具中心托管的认证服务来源" }
        if (serviceSource) {
            // The referenced tool service serves every byte; the package ships no page assets at all.
            require(spec.integrity.isEmpty()) { "工具服务来源不能携带包内页面资源" }
        } else {
            require(spec.integrity.size in 1..MAX_FILES && "index.html" in spec.integrity) { "Web 插件缺少 index.html 或文件过多" }
            require(spec.integrity.keys.all(::safeAssetPath)) { "Web 插件资源路径无效或占用 theme.css" }
        }
    }

    fun safeAssetPath(path: String): Boolean = path.length in 1..256 && path != "theme.css" &&
        path.split('/').let { parts -> parts.size <= 16 && parts.all { it.matches(Regex("[A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*")) } }
}
