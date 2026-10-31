package top.tianyan.app.runtime

/**
 * 传给 PRoot / `su` 进程的初始环境。
 *
 * PRoot 会把这份环境原样带进客态。`/bin/sh -lc` 在执行 `-c` 里的 `export PATH`
 * 之前就会 source `/etc/profile`。此时若 PATH 仍是 Android 宿主路径
 * （`/system/bin`、`/vendor/bin` 等在沙箱里存在但 exec 返回 EACCES），
 * dash 遇到 EACCES 会停止继续搜索，于是 `id` / `tr` 报 Permission denied。
 *
 * `su` 要在宿主上解析（Droidspaces）。客态 PATH 由 `env PATH=... sh -lc` 在
 * 登录壳启动前设置，宿主进程本身保留 Android PATH。
 */
object GuestProcessEnvironment {
    const val ANDROID_HOST_PATH = "/system/bin:/system/xbin"

    fun pathForLaunch(executable: String, inheritedPath: String? = null): String =
        if (isAndroidSu(executable)) {
            inheritedPath?.takeIf { it.isNotBlank() } ?: ANDROID_HOST_PATH
        } else {
            EnvironmentResolver().runtimePath()
        }

    /**
     * Termux JNI 会整表替换进程环境。先保留宿主变量与 PRoot 变量，再按启动程序
     * 覆盖 PATH，避免 `putIfAbsent` 把 Android PATH 留到登录壳。
     */
    fun interactiveLaunchEnvironment(
        androidEnvironment: Map<String, String>,
        hostEnvironment: Map<String, String>,
        executable: String,
        fallbackHome: String,
    ): LinkedHashMap<String, String> = LinkedHashMap<String, String>().apply {
        putAll(androidEnvironment)
        putAll(hostEnvironment)
        putIfAbsent("HOME", fallbackHome)
        put("PATH", pathForLaunch(executable, androidEnvironment["PATH"]))
    }

    /** ProcessBuilder 会清空环境。命令启动必须显式带上客态或 `su` 的 PATH。 */
    fun commandLaunchEnvironment(
        hostEnvironment: Map<String, String>,
        executable: String,
        androidPath: String? = null,
    ): Map<String, String> = LinkedHashMap(hostEnvironment).apply {
        val inherited = androidPath?.takeIf { it.isNotBlank() } ?: hostEnvironment["PATH"]
        put("PATH", pathForLaunch(executable, inherited))
    }

    private fun isAndroidSu(executable: String): Boolean =
        executable.substringAfterLast('/') == "su"
}
