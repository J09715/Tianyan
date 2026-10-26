package top.tianyan.app.runtime

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFileAttributeView

/** 保存前的版本校验失败：目标文件已被外部进程（如 AI 文件工具）修改，原文件保持不变。 */
internal class FileWriteConflictException(message: String) : IllegalStateException(message)

/** Replace the resolved target, retaining symlinks and existing POSIX permissions. */
internal object WorkspaceAtomicWriter {
    /**
     * 原子替换 [file]。传入 [expectedContent] 时执行版本校验：临时文件写好、替换之前重新读取
     * 目标当前内容，与基线不一致即抛 [FileWriteConflictException]，避免用陈旧草稿覆盖外部改动。
     * 校验与替换紧邻，缩短「检查 → 写入」之间的竞争窗口。
     */
    fun write(file: File, expectedContent: String? = null, writeContent: (File) -> Unit) {
        val target = file.canonicalFile
        val parent = requireNotNull(target.parentFile)
        check(parent.isDirectory || parent.mkdirs()) { "无法创建父目录" }
        val permissions = if (target.exists()) {
            Files.getFileAttributeView(target.toPath(), PosixFileAttributeView::class.java)
                ?.readAttributes()?.permissions()
        } else null
        val temporary = File.createTempFile(".tianyan-", ".tmp", parent)
        try {
            writeContent(temporary)
            if (permissions != null && Files.getPosixFilePermissions(temporary.toPath()) != permissions) {
                Files.setPosixFilePermissions(temporary.toPath(), permissions)
            }
            if (expectedContent != null) {
                val current = if (target.isFile) target.readText(Charsets.UTF_8) else null
                if (current != expectedContent) throw FileWriteConflictException("文件已被外部修改")
            }
            try {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                // A failed move leaves the original intact; never truncate it with copyTo.
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            temporary.delete()
        }
    }
}
