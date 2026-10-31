package top.tianyan.app.runtime

import java.io.File
import java.nio.file.Files

/**
 * 把登录壳在执行任何外部命令之前就落到干净的 Linux PATH。
 *
 * `/etc/profile` 里的 `id -u` 早于 `/etc/profile.d`，所以只写
 * `00-tianyan-env.sh` 挡不住第 12 行的 `id`。profile 与 bashrc 顶部的
 * PATH 赋值不调用外部命令；`00-tianyan-env.sh` 再在 `debuginfod.sh` 之前
 * 把 `/opt/tianyan/bin` 补回去。新装和 [DistroConfigurator.configureEnvironment]
 * 恢复已解压 rootfs 时都会执行。
 */
object GuestLoginEnvironment {
    const val MARKER = "# tianyan-path-guard"
    const val EARLY_PROFILE = "00-tianyan-env.sh"
    const val PROFILE = "tianyan-env.sh"

    private val guardBlock = Regex("(?m)^# tianyan-path-guard\\nPATH=.*\\nexport PATH\\n")

    fun install(rootfs: File) {
        val etc = File(rootfs, "etc")
        val profileDir = File(etc, "profile.d")
        profileDir.mkdirs()
        val script = profileScript()
        File(profileDir, EARLY_PROFILE).writeText(script)
        File(profileDir, PROFILE).writeText(script)
        writeGuarded(File(etc, "profile"))
        writeGuarded(File(etc, "bash.bashrc"))
    }

    fun profileScript(): String {
        val path = EnvironmentResolver().runtimePath()
        return """
            export LANG=C.UTF-8
            export PATH=$path
            export HOME=/root
            # HostBridge — 沙箱通过 localhost HTTP 桥接触发宿主侧操作
            export TAIXU_BRIDGE_URL="http://127.0.0.1:7980"
            export TAIXU_BRIDGE_PORT=7980
            # Android 二进制参考路径（用 tianyan-android-exec 包装器执行）
            export ANDROID_BIN_PATH="/system/bin:/system/xbin"
            export ANDROID_LIB_PATH="/system/lib64:/system/lib:/vendor/lib64:/vendor/lib"
            """.trimIndent() + "\n"
    }

    fun preamble(): String = """
        $MARKER
        PATH=${EnvironmentResolver().runtimePath()}
        export PATH
        """.trimIndent() + "\n"

    internal fun merge(existing: String, preamble: String = preamble()): String {
        val stripped = existing.replace(guardBlock, "")
        if (stripped.contains(MARKER)) return existing
        return preamble + stripped
    }

    private fun writeGuarded(file: File) {
        file.parentFile?.mkdirs()
        val path = file.toPath()
        val linked = Files.isSymbolicLink(path)
        val existing = if (linked || file.isFile) runCatching { file.readText() }.getOrDefault("") else ""
        val updated = merge(existing)
        if (!linked && updated == existing) return
        if (linked) Files.delete(path)
        file.writeText(updated)
    }
}
