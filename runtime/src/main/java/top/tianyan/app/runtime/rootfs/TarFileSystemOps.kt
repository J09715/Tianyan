package top.tianyan.app.runtime.rootfs

import android.system.Os
import java.io.File
import java.nio.file.Files

interface FileSystemOps {
    fun symlink(target: String, linkPath: String)
    fun chmod(path: String, mode: Int)
    fun hardlink(existing: String, link: String) {
        Files.createLink(File(link).toPath(), File(existing).toPath())
    }
    /** Permission bits only (07777), or null when the platform cannot read them. */
    fun posixMode(path: String): Int? = null
}

internal class AndroidFileSystemOps : FileSystemOps {
    override fun symlink(target: String, linkPath: String) {
        try {
            Os.symlink(target, linkPath)
        } catch (e: NoClassDefFoundError) {
            Files.createSymbolicLink(File(linkPath).toPath(), File(target).toPath())
        } catch (e: UnsatisfiedLinkError) {
            Files.createSymbolicLink(File(linkPath).toPath(), File(target).toPath())
        }
    }

    override fun chmod(path: String, mode: Int) {
        try {
            Os.chmod(path, mode)
        } catch (e: Throwable) {
            val file = File(path)
            if (mode and 0x100 != 0) file.setReadable(true, false)
            if (mode and 0x80 != 0) file.setWritable(true, false)
            if (mode and 0x40 != 0) file.setExecutable(true, false)
        }
    }

    override fun posixMode(path: String): Int? = try {
        Os.stat(path).st_mode and 0xfff
    } catch (e: Throwable) {
        null
    }
}

internal fun copyPreservingMode(source: File, target: File, fsOps: FileSystemOps) {
    source.copyTo(target, overwrite = true)
    val mode = runCatching { fsOps.posixMode(source.absolutePath) }.getOrNull()?.and(0xfff) ?: 0
    if (mode != 0) fsOps.chmod(target.absolutePath, mode)
    else if (source.canExecute()) target.setExecutable(true, false)
}
