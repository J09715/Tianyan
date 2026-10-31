package top.tianyan.app.plugin.cli

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

internal object FileOps {
    fun readUtf8(file: Path, maxBytes: Long, label: String): String =
        decodeUtf8(readBounded(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS), maxBytes, label))

    fun readBounded(input: InputStream, maxBytes: Long, label: String): ByteArray = input.use {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = it.read(buffer)
            if (count < 0) break
            require(output.size().toLong() + count <= maxBytes) { "$label 超过大小限制" }
            output.write(buffer, 0, count)
        }
        output.toByteArray()
    }

    fun decodeUtf8(bytes: ByteArray): String = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes)).toString()
    } catch (failure: CharacterCodingException) {
        throw IllegalArgumentException("manifest.json 不是有效的 UTF-8", failure)
    }

    fun sha256(file: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().hex()
    }

    fun writeAtomically(file: Path, bytes: ByteArray) {
        val target = file.toAbsolutePath().normalize()
        val parent = requireNotNull(target.parent) { "无法确定目标目录：$target" }
        Files.createDirectories(parent)
        val temporary = Files.createTempFile(parent, ".tianyan-plugin-", ".tmp")
        try {
            Files.write(temporary, bytes)
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

internal fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }
