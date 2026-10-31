package com.ai.assistance.showerclient

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.ai.assistance.shower.IShowerService
import com.ai.assistance.shower.ShowerBinderContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Helper to manage the lifecycle of the Shower server (shower-server.jar) on the device.
 *
 * This implementation is host-agnostic: it relies only on [ShellRunner] and Binder
 * registration via [ShowerBinderRegistry]. The host app is responsible for:
 * - Providing a [ShellRunner] via [ShowerEnvironment.shellRunner]
 * - Packaging `shower-server.jar` into its assets
 */
object ShowerServerManager {

    private const val TAG = "ShowerServerManager"
    private const val ASSET_JAR_NAME = "shower-server.jar"
    private const val LOCAL_JAR_NAME = "shower-server.jar"
    private const val SERVER_MAIN_CLASS = "com.ai.assistance.shower.Main"
    private const val ACTION_SHOWER_BINDER_READY =
        "com.ai.assistance.operit.action.SHOWER_BINDER_READY"
    private const val EXTRA_BINDER_CONTAINER = "binder_container"

    /**
     * 终止旧 server。模式里用 `[c]` 字符类把首字母拆开，避免 `pkill -f` 匹配到
     * **执行它的那条 `sh -c` 命令行本身**（原写法会自杀，exit=143/SIGTERM，
     * 且 `|| true` 永远没机会执行）。
     */
    private const val KILL_SERVER_CMD = "pkill -f \"[c]om.ai.assistance.shower.Main\" || true"

    /** 启动命令里用于判定外层 shell 已经把 server 放进后台的自标记。 */
    private const val START_OK_MARK = "SHOWER_START_OK"

    /** 读回 server 落盘日志的尾部字节数。 */
    private const val SERVER_LOG_TAIL_BYTES = 4096

    @Volatile
    var additionalTargetPackages: Set<String> = emptySet()

    /**
     * Ensure the Shower server is started in the background.
     * Returns true if the start command was issued successfully and a Binder
     * was received within the timeout window.
     */
    suspend fun ensureServerStarted(context: Context): Boolean {
        // 0) If we already have an alive Binder from the handoff broadcast, just reuse it.
        if (ShowerBinderRegistry.hasAliveService()) {
            ShowerLog.d(TAG, "Shower Binder already cached and alive, skipping start")
            return true
        }

        val runner = ShowerEnvironment.shellRunner
        if (runner == null) {
            ShowerLog.e(TAG, "No ShellRunner configured in ShowerEnvironment; cannot start server")
            return false
        }

        val appContext = context.applicationContext
        val jarFile = try {
            copyJarToExternalDir(appContext)
        } catch (e: Exception) {
            ShowerLog.e(TAG, "Failed to copy shower-server.jar from assets", e)
            return false
        }

        // 1) Kill existing server (ignore errors about missing process).
        val killCmd = KILL_SERVER_CMD
        ShowerLog.d(TAG, "Stopping existing Shower server (if any) with command: $killCmd")
        runner.run(killCmd, ShellIdentity.DEFAULT)

        // 2) 选一个「存在且可写」的工作目录。
        //    /data/local/tmp 在部分设备上缺失（见 ShowerEnvironment.workDirCandidates 注释），
        //    旧实现硬编码单目录，缺失时 cp 必败、虚拟屏完全不可用。
        val workDir = resolveWorkDir(runner)
        if (workDir == null) {
            ShowerLog.e(
                TAG,
                "无可用工作目录（已尝试 ${ShowerEnvironment.workDirCandidates}）：检查 Shizuku/Root 授权与 SELinux 限制"
            )
            return false
        }

        // 3) Remove any stale jar and log in the resolved work dir.
        val remoteJarPath = "$workDir/$LOCAL_JAR_NAME"
        val remoteLogPath = "$workDir/shower.log"
        val cleanupCmd = "rm -f $remoteJarPath $remoteLogPath || true"
        ShowerLog.d(TAG, "Cleaning up previous Shower jar and log with command: $cleanupCmd")
        val cleanupResult = runner.run(cleanupCmd, ShellIdentity.DEFAULT)
        if (!cleanupResult.success) {
            ShowerLog.w(
                TAG,
                "Cleanup of Shower jar/log may have failed (exitCode=${cleanupResult.exitCode}). stdout='${cleanupResult.stdout}', stderr='${cleanupResult.stderr}'"
            )
        }

        // 4) Copy the jar into the work dir using shell identity, so that the resulting
        //    file is owned by the shell user.
        val copyCmd = "cp ${jarFile.absolutePath} $remoteJarPath"
        ShowerLog.d(TAG, "Copying Shower jar with shell identity using command: $copyCmd")
        val copyResult = runner.run(copyCmd, ShellIdentity.SHELL)
        if (!copyResult.success) {
            ShowerLog.e(
                TAG,
                "Failed to copy Shower jar to $remoteJarPath (exitCode=${copyResult.exitCode}). stdout='${copyResult.stdout}', stderr='${copyResult.stderr}'"
            )
            return false
        }

        // 5) 在发启动命令之前先动态注册交接接收器。
        //    Manifest 接收器在进程处于后台时可能收不到 shell 进程发出的广播；
        //    动态接收器与进程同生共死，只要 App 还活着就能接到 Binder。
        val handoff = registerBinderHandoff(appContext)
        try {
            // 6) 用 setsid 把 server 拉到新会话。
            //    天衍的 HostProcessRunner 在命令结束时会对仍存活的 shell 调 destroy()，
            //    Android 的实现是 killProcessGroup：server 若留在同一个进程组里会被一起杀掉，
            //    表现为「命令成功但 Binder 永远等不到」。setsid 后即使外层 shell 被回收，
            //    app_process 也不在那个进程组里。stdin/stdout 全部重定向，避免管道被排空超时
            //    关闭后给 server 送 SIGPIPE。
            val targetPackagesArg = appContext.packageName
            // CLASSPATH 必须是简单命令的赋值前缀。写成 `setsid CLASSPATH=...` 时
            // setsid 会把赋值当成参数，server 的 classpath 是空的，直接 ClassNotFound。
            val detached = "CLASSPATH=$remoteJarPath setsid app_process / $SERVER_MAIN_CLASS " +
                "$targetPackagesArg </dev/null > $remoteLogPath 2>&1"
            val attached = "CLASSPATH=$remoteJarPath app_process / $SERVER_MAIN_CLASS " +
                "$targetPackagesArg </dev/null > $remoteLogPath 2>&1"
            val startCmd = "if command -v setsid >/dev/null 2>&1; then $detached & else $attached & fi; " +
                "echo $START_OK_MARK; exit 0"
            ShowerLog.d(TAG, "Starting Shower server with command: $startCmd")
            val startResult = runner.run(startCmd, ShellIdentity.SHELL)
            if (!startResult.success && !startResult.stdout.contains(START_OK_MARK)) {
                ShowerLog.e(
                    TAG,
                    "Failed to start Shower server (exitCode=${startResult.exitCode}). stdout='${startResult.stdout}', stderr='${startResult.stderr}'"
                )
                return false
            }

            // 7) Poll for up to 10 seconds for the Binder handoff broadcast to be received and cached.
            for (attempt in 0 until 50) { // 50 * 200ms = 10s
                delay(200)
                if (ShowerBinderRegistry.hasAliveService()) {
                    ShowerLog.d(
                        TAG,
                        "Shower Binder cached and alive after ~${(attempt + 1) * 200}ms"
                    )
                    return true
                }
            }

            ShowerLog.e(
                TAG,
                "Shower Binder was not received within the expected time。\n" +
                    "--- $remoteLogPath ---\n${readServerLog(runner, remoteLogPath)}"
            )
            return false
        } finally {
            runCatching { appContext.unregisterReceiver(handoff) }
        }
    }

    /**
     * 进程内接收 SHOWER_BINDER_READY。与 Manifest 接收器并行，不替代它：
     * 谁先把活着的 Binder 放进 [ShowerBinderRegistry] 谁生效。
     */
    private fun registerBinderHandoff(context: Context): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_SHOWER_BINDER_READY) return
                if (!isTrustedBinderSender(intent)) return
                try {
                    val container = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(EXTRA_BINDER_CONTAINER, ShowerBinderContainer::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra<ShowerBinderContainer>(EXTRA_BINDER_CONTAINER)
                    }
                    val service = container?.binder?.let { IShowerService.Stub.asInterface(it) }
                    val alive = service?.asBinder()?.isBinderAlive == true
                    ShowerLog.d(TAG, "dynamic handoff: service=$service alive=$alive")
                    if (service != null && alive) {
                        ShowerBinderRegistry.setService(service)
                    }
                } catch (t: Throwable) {
                    ShowerLog.w(TAG, "dynamic handoff failed", t)
                }
            }
        }
        val filter = IntentFilter(ACTION_SHOWER_BINDER_READY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        return receiver
    }

    /**
     * API 34+ 能读到发送者 uid 时，只接受 shell / root / system，以及来源未回填的 -1。
     * 读不到（低版本，或 @hide 方法不存在）则放行，与 Operit 的接收器一致。
     */
    private fun isTrustedBinderSender(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        val uid = runCatching {
            intent.javaClass.getMethod("getSentFromUid").invoke(intent) as Int
        }.getOrNull() ?: return true
        val trusted = uid == -1 || uid == 0 || uid == 1000 || uid == 2000
        if (!trusted) {
            ShowerLog.w(TAG, "拒绝来源不明的 Binder 广播：sentFromUid=$uid")
        }
        return trusted
    }

    /** 读回 server 落盘日志的尾部，用于定位启动失败原因；本身绝不抛异常。 */
    private suspend fun readServerLog(runner: ShellRunner, path: String): String {
        val result = runner.run("tail -c $SERVER_LOG_TAIL_BYTES $path 2>/dev/null", ShellIdentity.DEFAULT)
        return when {
            !result.success -> "(读取失败 exit=${result.exitCode})"
            result.stdout.isBlank() -> "(空)"
            else -> result.stdout
        }
    }

    /**
     * Stop the Shower server process if running.
     */
    suspend fun stopServer(): Boolean {
        val runner = ShowerEnvironment.shellRunner
        if (runner == null) {
            ShowerLog.e(TAG, "No ShellRunner configured in ShowerEnvironment; cannot stop server")
            return false
        }
        val cmd = KILL_SERVER_CMD
        val result = runner.run(cmd, ShellIdentity.DEFAULT)
        if (!result.success) {
            ShowerLog.e(TAG, "Failed to stop Shower server: ${result.stderr}")
        }
        return result.success
    }

    /**
     * 按顺序探测第一个「存在且可写」的工作目录；均不可用时返回 null。
     *
     * 探测在 **shell 身份下**真实试写一个文件（而非只看 `ls`/`test -d`），
     * 因为目录存在不代表可写（SELinux 与 UNIX 权限都可能拦）。
     */
    private suspend fun resolveWorkDir(runner: ShellRunner): String? {
        for (dir in ShowerEnvironment.workDirCandidates) {
            // 先尝试确保目录存在（已是 shell 拥有的目录时 mkdir -p 为幂等空操作）。
            val probeFile = "$dir/.shower_probe"
            val cmd = "mkdir -p $dir 2>/dev/null; " +
                "if touch $probeFile 2>/dev/null; then rm -f $probeFile; echo $SHOWER_WORKDIR_PROBE_MARK; fi"
            val result = runner.run(cmd, ShellIdentity.SHELL)
            if (result.success && result.stdout.contains(SHOWER_WORKDIR_PROBE_MARK)) {
                ShowerLog.d(TAG, "resolveWorkDir: 选用 $dir")
                return dir
            }
            ShowerLog.w(
                TAG,
                "resolveWorkDir: $dir 不可写（exit=${result.exitCode}），尝试下一个候选"
            )
        }
        return null
    }

    /**
     * Copy shower-server.jar from assets to an external directory.
     * Host apps can override this behaviour by providing a different wrapper
     * around [ShellRunner] if needed.
     */
    private suspend fun copyJarToExternalDir(context: Context): File = withContext(Dispatchers.IO) {
        val baseDir = resolveStagingDir(context)
        val outFile = File(baseDir, LOCAL_JAR_NAME)
        context.assets.open(ASSET_JAR_NAME).use { input ->
            FileOutputStream(outFile).use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
                output.flush()
            }
        }
        // shell uid 要能 cp 走这个文件。Download 目录在未授予「所有文件」权限时
        // 往往只有创建者可读，显式放开其它用户的读位。
        outFile.setReadable(true, false)
        ShowerLog.d(TAG, "Copied $ASSET_JAR_NAME to ${outFile.absolutePath}")
        outFile
    }

    /**
     * jar 的过渡目录：shell 随后会 `cp` 它。优先公共 Download（与 Operit 相同，shell 可读），
     * 没存储权限时退到本应用外部目录，最后才是 cache（多数设备上 shell 读不到，仅作最后尝试）。
     */
    private fun resolveStagingDir(context: Context): File {
        val candidates = listOfNotNull(
            File("/sdcard/Download/Tianyan"),
            context.getExternalFilesDir(null)?.let { File(it, "shower") },
            File(context.cacheDir, "shower"),
        )
        val failures = mutableListOf<String>()
        for (dir in candidates) {
            val ready = (dir.exists() || dir.mkdirs()) && dir.exists() && dir.canWrite()
            if (ready) return dir
            failures += dir.absolutePath
        }
        throw java.io.IOException("无法创建 shower-server.jar 过渡目录，已尝试：$failures")
    }
}
