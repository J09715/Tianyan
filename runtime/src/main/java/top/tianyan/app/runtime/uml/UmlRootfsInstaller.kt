package top.tianyan.app.runtime.uml

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    private val artifactRepository: UmlArtifactRepository,
    private val logger: top.tianyan.app.core.common.logging.AppLogger,
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
    /** 当前使用的产物来源（manifest / 内置回退），供 UI 说明。 */
    @Volatile var artifactSource: String = ""
        private set

    fun isInstalled(): Boolean = layout.rootfs.isFile

    /** 非阻塞安装入口：已装齐直接 true；否则后台补齐镜像，调用方轮询 [state]/[progress]。 */
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
                val tmp = File(layout.rootfs.absolutePath + ".tmp")
                val obtained = obtainImage(tmp, onProgress)
                if (!obtained) throw IllegalStateException("no rootfs image available (bundled asset missing and download failed)")
                if (tmp.length() < MIN_ROOTFS_BYTES) {
                    tmp.delete()
                    throw IllegalStateException("rootfs image too small after extraction (${tmp.length()}B)")
                }
                layout.rootfs.delete()
                if (!tmp.renameTo(layout.rootfs)) {
                    tmp.copyTo(layout.rootfs)
                    tmp.delete()
                }
                // 把 guest init 写进镜像：内核执行 init= 时 /host 还没挂载（/host 正是
                // init 自己挂的），指向 /host 必然 panic（实测 error -2）。init 必须在
                // rootfs 内部 —— 用纯 Kotlin 的 ext4 写入器注入，无需 root / debugfs。
                // 内置镜像已含 init，这一步是幂等的（见 ensureGuestInit）。
                embedGuestInit()
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

    /**
     * 拿到镜像并解压进 [tmp]。
     *
     * **引导安装**：与 Stryker 一致 —— 首次启动时下载完整 guest 镜像（含 Debian
     * + sshd + bash + apt），不在 APK 里内置。内置会让安装包从 47MB 涨到 96MB，
     * 且镜像更新必须跟着发版，成本远高于一次下载。
     *
     * 下载失败不回退 PRoot，而是把原因写进 [lastError]，让引导页显示可重试的错误。
     *
     * @return true = [tmp] 已写入可用的 ext4 镜像。
     */
    private suspend fun obtainImage(tmp: File, onProgress: ((Float) -> Unit)?): Boolean =
        downloadRootfs(tmp, onProgress)

    /** 下载路径（内置不可用时）。 */
    private suspend fun downloadRootfs(tmp: File, onProgress: ((Float) -> Unit)?): Boolean =
        withContext(Dispatchers.IO) {
            val part = layout.rootfsArchivePart
            part.parentFile?.mkdirs()
            val asset = artifactRepository.resolveRootfsAsset()
            artifactSource = asset.source
            logger.i("UML rootfs source: ${asset.source} (${asset.url})")
            val ok = runCatching {
                fileDownloader.download(
                    DownloadRequest(
                        url = asset.url,
                        destination = part,
                        partialFile = File(part.absolutePath + ".part"),
                        sha256 = asset.sha256,
                        maxBytes = maxOf(asset.size, MIN_ROOTFS_BYTES),
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
            }.isSuccess
            if (!ok) return@withContext false
            runCatching {
                GZIPInputStream(part.inputStream().buffered(1 shl 16), 1 shl 16).use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
                part.delete()
            }.onFailure {
                logger.w("downloaded rootfs gunzip failed: ${it.message}")
                return@withContext false
            }
            onProgress?.invoke(1f)
            true
        }

    /** rootfs.img 已就位时返回 true；否则要求调用方走 [ensure]。 */
    fun isReady(): Boolean = isInstalled()

    /**
     * 启动前自愈：镜像在、但缺 `/tianyan-uml-init` 就补写。
     *
     * 存在的理由：早期下载的镜像没有 init（那时路径还是错的 `/host/...`），
     * 而 [ensure] 只在镜像**缺失**时才跑 —— 镜像在就不会触发注入，
     * 结果是老用户永远起不来。这里在每次启动前兜一次。
     *
     * @return true = 镜像已含 init，可以直接启动。
     */
    suspend fun ensureGuestInit(): Boolean = withContext(Dispatchers.IO) {
        if (!layout.rootfs.isFile) return@withContext false
        if (Ext4InlineWriter.containsEntry(layout.rootfs, GUEST_INIT_NAME)) return@withContext true
        embedGuestInit()
        Ext4InlineWriter.containsEntry(layout.rootfs, GUEST_INIT_NAME)
    }

    /**
     * 把 APK 资源里的 guest init 写进 rootfs 镜像根目录（`/tianyan-uml-init`）。
     *
     * 为什么必须写进镜像：内核参数 `init=` 在内核挂载 rootfs 后**立即**执行，
     * 而 `/host` 这个挂载点是 init 自己创建的 —— 内核找 `/host/tianyan-uml-init`
     * 时它还不存在，实测 `Kernel panic: Requested init /host/tianyan-uml-init failed (error -2)`。
     * 写入镜像内部后路径变成 `/tianyan-uml-init`，内核直接可读（实测能启动）。
     *
     * 幂等：内容一致时写入器会跳过；失败不阻断安装（下次启动仍会重试）。
     */
    private fun embedGuestInit() {
        val script = context.assets.open("tianyan-uml/tianyan-uml-init").use { it.readBytes() }
        when (val result = Ext4InlineWriter.writeRootEntry(layout.rootfs, "tianyan-uml-init", script)) {
            is Ext4InlineWriter.Result.Ok -> {
                logger.i("guest init embedded into rootfs image (inode ${result.inode})")
            }
            is Ext4InlineWriter.Result.TooLarge -> {
                logger.w("guest init too large for image: need ${result.needed}, have ${result.available}")
            }
            is Ext4InlineWriter.Result.NoFreeInode -> {
                logger.w("no free inode in rootfs image to embed guest init")
            }
            is Ext4InlineWriter.Result.Io -> {
                logger.w("failed to embed guest init: ${result.message}")
            }
        }
    }

    companion object {
        private const val DEFAULT_DOWNLOAD_MAX_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MIN_ROOTFS_BYTES = 64L * 1024 * 1024
        /** guest init 在镜像内的文件名（内核参数 init= 指向它）。 */
        const val GUEST_INIT_NAME = "tianyan-uml-init"
    }
}
