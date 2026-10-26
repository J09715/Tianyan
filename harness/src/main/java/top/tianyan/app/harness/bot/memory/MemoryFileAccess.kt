package top.tianyan.app.harness.bot.memory

import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import top.tianyan.app.core.model.bot.BotMemoryDocument

internal object MemoryFileAccess {
    const val MAX_BYTES = 1024 * 1024

    fun resolve(root: File, path: String): File {
        val normalized = path.replace('\\', '/').trim()
        val parts = normalized.split('/')
        require(normalized.isNotBlank() && !normalized.startsWith('/') && ':' !in normalized && '\u0000' !in normalized &&
            parts.none { it.isEmpty() || it == "." || it == ".." || it.equals(".git", true) }) { "非法记忆路径: $path" }
        var current = root
        parts.forEach { part -> current = File(current, part); require(!Files.isSymbolicLink(current.toPath())) { "记忆路径不能经过符号链接" } }
        require(current.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) { "记忆路径越界: $path" }
        return current
    }

    fun read(root: File, path: String): BotMemoryDocument {
        val file = resolve(root, path)
        require(file.extension.equals("md", true)) { "记忆编辑仅支持 Markdown" }
        require(!file.exists() || file.isFile) { "记忆路径不是文件" }
        val bytes = if (file.exists()) bytes(file) else byteArrayOf()
        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val content = decoder.decode(ByteBuffer.wrap(bytes)).toString()
        val hash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return BotMemoryDocument(path, content, "${if (file.exists()) "file" else "missing"}:$hash")
    }

    fun bytes(file: File): ByteArray {
        require(file.length() <= MAX_BYTES) { "单个记忆文件不能超过 1 MiB，未截断或覆盖原文" }
        return file.inputStream().use { input ->
            val output = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_BYTES) { "单个记忆文件不能超过 1 MiB" }
                output.write(buffer, 0, count)
            }
            output.toByteArray()
        }
    }

    fun write(file: File, bytes: ByteArray) {
        require(bytes.size <= MAX_BYTES) { "单个记忆文件不能超过 1 MiB" }
        check(file.parentFile?.let { it.isDirectory || it.mkdirs() } == true) { "无法创建记忆目录" }
        val temporary = File.createTempFile(".memory-", ".tmp", file.parentFile)
        try {
            temporary.writeBytes(bytes)
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally { temporary.delete() }
    }
}
