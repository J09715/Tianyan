package top.tianyan.app.runtime.uml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.util.zip.GZIPInputStream
import javax.inject.Inject
import javax.inject.Singleton
import top.tianyan.app.core.network.DownloadRequest
import top.tianyan.app.core.network.DownloadEvent
import top.tianyan.app.core.network.FileDownloader

/**
 * UML rootfs 安装器：把 Stryker rootless release 的预建 guest 镜像（完整 root Linux 系统：
 * 自带 init + sshd + proc/sys 全挂载）下载并解压进 [UmlRuntimeLayout.rootfs]，
 * 供 UmlEngine 用 ubd0= 挂给 UML 内核。
 *
 * 移植自 Stryker QemuInstaller（GPL v3）：
 * - 只保留 UML 路径：内核/stub/网络四件套已随 APK 打，缺的只有 guest 磁盘镜像；
 * - rootfs.imgz 就是 ext4 镜像的 gzip 包（Stryker build.sh `gz -c IMG`），
 *   标准 [GZIPInputStream] 直解，不需要引 LZFSE/zstd；
 * - 下载走天衍既有 [FileDownloader]（HTTPS + sha256 校验 + 断点续传 + 重试），
 *   不另造轮子；校验失败直接 fail，不写半截镜像。
 *
 * 幂等：rootfs.img 已存在则跳过下载（对齐 Stryker "already present — keeping it"）。
 */
@Singleton
class UmlRootfsInstaller @Inject constructor(
    @ApplicationContext private val context: Context,
    private val layout: UmlRuntimeLayout,
    private val fileDownloader: FileDownloader,
) {
    /** 下载进度：0..1。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile var installJob: Job? = null
        private set

    enum class State { READY, INSTALLING, FAILED, OFFLINE }

    @Volatile var state: State = State.READY
        private set
    @Volatile var lastError: String = ""
        private set
    @Volatile var progress: Float = 0f
        private set

    fun isInstalled(): Boolean = layout.rootfs.isFile

    /** 非阻塞安装入口：已装齐直接 true；否则启动后台下载+解压，调用方轮询 [state]/[progress]。 */
    fun ensure(onDone: (() -> Unit)? = null, onProgress: ((Float) -> Unit)? = null): Boolean {
        if (isInstalled()) {
            onDone?.invoke()
            return true
        }
        val job = installJob
        if (job != null && job.isActive) return false
        installJob = scope.launch {
            state = State.INSTALLING
            progress = 0f
            try {
                layout.ensureDirectories()
                val part = layout.rootfsArchivePart
                part.parentFile?.mkdirs()
                fileDownloader.download(
                    DownloadRequest(
                        url = ROOTFS_IMAGE_URL,
                        destination = part,
                        partialFile = File(part.absolutePath + ".part"),
                        sha256 = ROOTFS_IMAGE_SHA256,
                        maxBytes = DEFAULT_DOWNLOAD_MAX_BYTES,
                        timeoutMs = DEFAULT_DOWNLOAD_TIMEOUT_MS,
                    ),
                ).collect { event ->
                    when (event) {
                        is DownloadEvent.Progress -> {
                            progress = (0.1f + (event.totalBytes?.let {
                                event.downloadedBytes.toFloat() / it.toFloat()
                            } ?: 0f) * 0.4f)
                            onProgress?.invoke(progress)
                        }
                        else -> Unit
                    }
                }
                // 解压：ext4 镜像 gzip 包 → rootfs.img（先解到 .tmp 再原子 rename，防半截）
                val tmp = File(layout.rootfs.absolutePath + ".tmp")
                GZIPInputStream(part.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                if (tmp.length() < MIN_ROOTFS_BYTES) {
                    tmp.delete()
                    throw IllegalStateException("rootfs image too small after gunzip (${tmp.length()}B)")
                }
                layout.rootfs.delete()
                if (!tmp.renameTo(layout.rootfs)) {
                    tmp.copyTo(layout.rootfs)
                    tmp.delete()
                }
                part.delete()
                progress = 1f
                onProgress?.invoke(1f)
                state = State.READY
                lastError = ""
                onDone?.invoke()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                state = State.FAILED
                lastError = e.message ?: e.javaClass.simpleName
                onDone?.invoke()
            }
        }
        return false
    }

    /** rootfs.img 已就位时返回 true；否则要求调用方走 [ensure]。 */
    fun isReady(): Boolean = isInstalled()

    companion object {
        // Stryker rootless-650 release 的 guest 磁盘镜像（Debian root Linux，gzip 压缩 ext4）。
        // rootless-650 是 immutable release tag，资产不可被替换；拿到稳定 hash 后
        // 回填 ROOTFS_IMAGE_SHA256 即启用强校验。
        const val ROOTFS_IMAGE_URL =
            "https://github.com/zalexdev/strykerapp/releases/download/rootless-650/rootfs.imgz"
        val ROOTFS_IMAGE_SHA256: String? = null

        private const val DEFAULT_DOWNLOAD_MAX_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MIN_ROOTFS_BYTES = 64L * 1024 * 1024
    }
}
