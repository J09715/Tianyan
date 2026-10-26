package top.tianyan.app.runtime.proot

import java.io.File
import java.nio.file.Path

/** Paths shared by argument construction and storage diagnostics. */
internal object ProotMountLayout {
    val hostSystemPaths = listOf(
        "/apex", "/data/app", "/data/dalvik-cache",
        "/data/misc/apexdata/com.android.art/dalvik-cache",
        "/system", "/system_ext", "/vendor", "/product", "/odm",
        "/linkerconfig/com.android.art/ld.config.txt", "/linkerconfig/ld.config.txt",
        "/plat_property_contexts", "/property_contexts",
    )

    fun placeholderCandidates(rootfs: File): Set<Path> {
        val physicalRoot = rootfs.canonicalFile.toPath()
        val guestPaths = hostSystemPaths + listOf(
            "/dev", "/proc", "/sys", "/tmp", "/workspace", "/root", "/opt/tianyan", "/attachments",
            "/sdcard", "/sdcard/Download", "/sdcard/Documents",
            // Guest alias for link2symlink backing store, NOT the real rootfs/.l2s store.
            File(rootfs, ".l2s").absolutePath.replace('\\', '/'),
            File(rootfs.canonicalFile, ".l2s").absolutePath.replace('\\', '/'),
        )
        return guestPaths.map { physicalRoot.resolve(it.trimStart('/')).normalize() }
            .filter { it != physicalRoot && it.startsWith(physicalRoot) }.toSet()
    }

    /** PRoot glue.c creates final guest mount placeholders with mode 000. No chmod needed. */
    fun isRestrictedPlaceholder(
        path: Path, candidates: Set<Path>, isDirectory: Boolean, permissionBits: Int, ownerUid: Int, appUid: Int,
    ): Boolean = path in candidates && isDirectory && permissionBits == 0 && ownerUid == appUid
}
