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
     * **内置优先**：APK assets 里带一份构建好的完整 Debian 镜像（含 sshd/bash/apt
     * 与注入的 guest init），解压即用，不碰网络。
     *
     * 为什么改为内置：完整 Stryker 镜像托管在 GitHub releases，实测从国内网络
     * 下载 90s 超时（curl exit 28），用户没有代理时根本拿不到镜像，
     * 于是永远起不来 —— 与其依赖一个不可达的源，不如内置。
     *
     * 内置不可用（老 APK）才走下载；下载失败写进 [lastError] 供引导页重试。
     *
     * @return true = [tmp] 已写入可用的 ext4 镜像。
     */
    private suspend fun obtainImage(tmp: File, onProgress: ((Float) -> Unit)?): Boolean =
        withContext(Dispatchers.IO) {
            if (assetAvailable(BUNDLED_ROOTFS_ASSET)) {
                artifactSource = "bundled asset"
                logger.i("UML rootfs: extracting bundled asset $BUNDLED_ROOTFS_ASSET")
                runCatching {
                    context.assets.open(BUNDLED_ROOTFS_ASSET).use { stream ->
                        GZIPInputStream(stream.buffered(1 shl 16), 1 shl 16).use { gz ->
                            tmp.outputStream().use { out -> gz.copyTo(out, 64 * 1024) }
                        }
                    }
                }.onFailure {
                    logger.w("bundled rootfs extraction failed: ${it.message}")
                    return@withContext false
                }
                onProgress?.invoke(1f)
                return@withContext true
            }
            downloadRootfs(tmp, onProgress)
        }

    private fun assetAvailable(path: String): Boolean = runCatching {
        context.assets.open(path).use { true }
    }.getOrDefault(false)

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
     * 确保镜像里有天衍自己的 guest init（`/tianyan-uml-init`）。
     *
     * 内核参数 `init=` 指向它。用自己的一份而不是镜像自带的 `/stryker-init`：
     * 后者依赖 `/usr/local/sbin/stryker-guest-init` 处理公钥，那个脚本不在镜像里时
     * sshd 能起来但公钥进不去 → 应用 150s 连不上。天衍的 init 自包含，不依赖它。
     *
     * 幂等：已存在同内容条目直接返回 true。
     */
    suspend fun ensureGuestInit(): Boolean = withContext(Dispatchers.IO) {
        if (!layout.rootfs.isFile) return@withContext false
        if (Ext4InlineWriter.containsEntry(layout.rootfs, GUEST_INIT_NAME)) return@withContext true
        embedGuestInit()
        Ext4InlineWriter.containsEntry(layout.rootfs, GUEST_INIT_NAME)
    }

    /** 把 APK 资源里的 init 脚本写进镜像根目录。 */
    private fun embedGuestInit() {
        runCatching {
            val script = context.assets.open("tianyan-uml/$GUEST_INIT_NAME").use { it.readBytes() }
            when (val result = Ext4InlineWriter.writeRootEntry(layout.rootfs, GUEST_INIT_NAME, script)) {
                is Ext4InlineWriter.Result.Ok ->
                    logger.i("guest init embedded into rootfs (inode ${result.inode})")
                is Ext4InlineWriter.Result.TooLarge ->
                    logger.w("guest init too large: need ${result.needed}, have ${result.available}")
                is Ext4InlineWriter.Result.NoFreeInode ->
                    logger.w("no free inode in rootfs to embed guest init")
                is Ext4InlineWriter.Result.Io ->
                    logger.w("failed to embed guest init: ${result.message}")
            }
        }.onFailure { logger.w("embedGuestInit failed: ${it.message}") }
    }

    companion object {
        private const val DEFAULT_DOWNLOAD_MAX_BYTES = 512L * 1024 * 1024
        private const val DEFAULT_DOWNLOAD_TIMEOUT_MS = 10 * 60 * 1000L
        private const val MIN_ROOTFS_BYTES = 64L * 1024 * 1024
        /** guest init 在镜像内的文件名（内核参数 init= 指向它）。 */
        const val GUEST_INIT_NAME = "tianyan-uml-init"
        /** 内置 rootfs 镜像（完整 Debian + sshd + 注入的 guest init，gzip 后约 34MB）。 */
        const val BUNDLED_ROOTFS_ASSET = "tianyan-uml/rootfs.imgz"
    }
}
