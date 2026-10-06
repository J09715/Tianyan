package top.tianyan.app.runtime.proot

import java.io.File

/** Filesystem contract for the optional, isolated x86_64 compatibility payload. */
object QemuCompatibilityLayout {
    const val PLUGIN_ID = "qemu-x86-64-compat"
    const val ROOT_RELATIVE_TO_TIANYAN = "compat/x86_64"
    const val QEMU_BINARY_NAME = "qemu-x86_64"

    fun root(tianyanRoot: File): File = File(tianyanRoot, ROOT_RELATIVE_TO_TIANYAN)
    fun qemuBinary(tianyanRoot: File): File = File(root(tianyanRoot), QEMU_BINARY_NAME)
    fun guestRootfs(tianyanRoot: File): File = File(root(tianyanRoot), "rootfs")

    /**
     * QEMU alone is insufficient: require an x86_64 shell and dynamic loader as
     * a minimal guard against accidentally emulating the normal ARM64 rootfs.
     */
    fun isReady(tianyanRoot: File): Boolean {
        val qemu = qemuBinary(tianyanRoot)
        val rootfs = guestRootfs(tianyanRoot)
        val loader = sequenceOf(
            File(rootfs, "lib64/ld-linux-x86-64.so.2"),
            File(rootfs, "lib/x86_64-linux-gnu/ld-linux-x86-64.so.2"),
        ).any(File::isFile)
        return qemu.isFile && qemu.canExecute() &&
            File(rootfs, "bin/sh").isFile && loader
    }
}
