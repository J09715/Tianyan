package top.tianyan.app.runtime.uml

import top.tianyan.app.runtime.RuntimePathManager
import java.io.File
import javax.inject.Inject

/**
 * UML（User Mode Linux）引擎的运行时文件布局契约。
 *
 * 与 Stryker 的 RootlessPaths/UmlEngine 对应：四个 native 二进制必须从 APK 的
 * nativeLibraryDir 解压执行（Android 10+ 禁止执行 filesDir 下代码），
 * guest rootfs 镜像放在 filesDir 下的 linux-runtime/uml/ 目录。
 *
 * @Inject constructor 让 Hilt 直接图解析（RuntimePathManager 已是 Singleton），
 * 不需要额外的 @Provides 桥。
 */
class UmlRuntimeLayout @Inject constructor(
    private val pathManager: RuntimePathManager,
) {
    val nativeDir: File = pathManager.nativeLibraryDir

    val kernel: File get() = File(nativeDir, UML_KERNEL_NAME)
    val stub: File get() = File(nativeDir, UML_STUB_NAME)
    val umnet: File get() = File(nativeDir, UML_UMNET_NAME)
    val passt: File get() = File(nativeDir, UML_PASST_NAME)

    val baseDir: File = File(pathManager.baseDir, UML_BASE_NAME)
    val rootfs: File = File(baseDir, UML_ROOTFS_NAME)
    /** 下载中的压缩镜像归档（gunzip 进 [rootfs] 后删除）。 */
    val rootfsArchivePart: File = File(baseDir, UML_ROOTFS_ARCHIVE_NAME)
    val console: File = File(baseDir, "console.log")
    val shareDir: File = File(baseDir, "share")

    companion object {
        const val UML_KERNEL_NAME = "libuml.so"
        const val UML_STUB_NAME = "libstub.so"
        const val UML_UMNET_NAME = "libumnet.so"
        const val UML_PASST_NAME = "libpasst.so"
        const val UML_BASE_NAME = "uml"
        const val UML_ROOTFS_NAME = "rootfs.img"
        const val UML_ROOTFS_ARCHIVE_NAME = "rootfs.img.gz"

        const val GUEST_SSH_PORT = 22
        const val HOST_SSH_PORT = 2222
        const val HOST_LOOPBACK = "127.0.0.1"
    }

    /** 引擎所需的完整产物清单。 */
    fun requiredArtifacts(): List<File> = listOf(
        kernel,
        stub,
        umnet,
        passt,
        rootfs,
    )

    /** 缺失（不存在或不可执行）的产物名列表；空 = 已装齐。 */
    fun missing(): List<String> = requiredArtifacts().mapNotNull { artifact ->
        val ok = if (artifact.name == UML_ROOTFS_NAME) artifact.isFile else artifact.canExecute()
        if (ok) null else artifact.name
    }

    fun isInstalled(): Boolean = missing().isEmpty()

    fun ensureDirectories() {
        baseDir.mkdirs()
        shareDir.mkdirs()
        File(shareDir, "firmware").mkdirs()
    }
}
