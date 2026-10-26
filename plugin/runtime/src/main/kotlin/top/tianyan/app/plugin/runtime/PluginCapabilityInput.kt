package top.tianyan.app.plugin.runtime

/** Typed input policy shared by wire decoding and native admission. */
object PluginCapabilityInput {
    val CLIPBOARD_WRITE = PluginCapability("host.clipboard-write", "clipboard")

    /**
     * Read-only dynamic read of the page's own bound live service. The target stays a fixed marker so
     * grants keep covering many paths, while the relative sub-path travels in `text` like the
     * clipboard payload. Host and port are always resolved natively.
     */
    val SERVICE_REQUEST = PluginCapability("host.service-request", "service")
    val SERVICE_STREAM = PluginCapability("host.service-stream", "service")
    val SERVICE_ACTIONS = setOf(SERVICE_REQUEST.action, SERVICE_STREAM.action)

    /**
     * Structured host actions a page may request after an explicit confirmation. The APK path travels
     * in `text` and is only shape-checked here; the host side re-checks the sandbox root and resolves
     * the real file itself, so a page can never name a host path that the host would not accept.
     */
    val INSTALL_APK = PluginCapability("host.install-apk", "apk")
    val LOGCAT = PluginCapability("host.logcat", "logcat")

    /**
     * Actions that change device or clipboard state and therefore may never be cached for a session:
     * every call needs its own confirmation. Shared by the session admission and the page registry.
     */
    val ONE_SHOT = setOf(CLIPBOARD_WRITE, INSTALL_APK)

    const val MAX_TEXT_LENGTH = 1024
    const val MAX_APK_PATH_LENGTH = 256
    private val SERVICE_PATH = Regex("[A-Za-z0-9_./-]{1,256}")
    private val APK_PATH = Regex("/[A-Za-z0-9_./+-]{1,240}\\.apk", RegexOption.IGNORE_CASE)

    fun valid(call: PluginCapabilityCall): Boolean = when (call.capability) {
        CLIPBOARD_WRITE -> call.text != null && call.text.length in 1..MAX_TEXT_LENGTH && call.text.none { it == '\u0000' }
        SERVICE_REQUEST, SERVICE_STREAM -> call.text != null && validServicePath(call.text)
        INSTALL_APK -> call.text != null && validApkPath(call.text)
        else -> call.text == null
    }

    /**
     * A relative sub-path under the native base path. No scheme or host, no query or fragment and no
     * absolute or traversal segment; native code still re-validates against its own asset rule.
     */
    fun validServicePath(path: String): Boolean = SERVICE_PATH.matches(path) &&
        !path.startsWith("/") && path.split('/').none { it == ".." || it == "." }

    /**
     * A bounded absolute path to an `.apk` the host may install. Shape only: the host re-checks that
     * the path stays inside a resolvable sandbox root before touching the file.
     */
    fun validApkPath(path: String): Boolean {
        if (!APK_PATH.matches(path) || path.length > MAX_APK_PATH_LENGTH) return false
        val segments = path.removePrefix("/").split('/')
        return segments.size >= 2 && segments.none { it.isEmpty() || it == "." || it == ".." }
    }

    /** Keep control / directional characters from changing the native permission prompt. */
    fun preview(text: String): String = buildString {
        append('"')
        text.forEach { char ->
            when {
                char == '"' || char == '\\' -> { append('\\'); append(char) }
                Character.isISOControl(char) || Character.getType(char) == Character.FORMAT.toInt() ->
                    append("\\u" + char.code.toString(16).padStart(4, '0'))
                else -> append(char)
            }
        }
        append('"')
    }
}
