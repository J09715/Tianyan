package top.tianyan.app.harness

import java.security.MessageDigest
import java.io.OutputStream
import java.io.OutputStreamWriter
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.harness.effects.hasIncompleteCommandOutput

/** Only the trusted output publisher sets this metadata, after redaction and a successful atomic write. */
@Serializable
data class ToolOutputArtifact(val path: String, val sha256: String, val bytes: Long, val complete: Boolean) {
    companion object {
        const val METADATA_KEY = "output_artifact"
        fun capture(path: String, content: String): ToolOutputArtifact {
            val digest = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            val sink = object : OutputStream() {
                override fun write(value: Int) { digest.update(value.toByte()); bytes++ }
                override fun write(buffer: ByteArray, offset: Int, length: Int) { digest.update(buffer, offset, length); bytes += length }
            }
            OutputStreamWriter(sink, Charsets.UTF_8).use { writer ->
                var offset = 0
                while (offset < content.length) {
                    val count = minOf(8192, content.length - offset)
                    writer.write(content, offset, count)
                    offset += count
                }
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            return ToolOutputArtifact(path, hash, bytes, !hasIncompleteCommandOutput(content))
        }
        fun decode(value: String?): ToolOutputArtifact? = value?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()?.takeIf { artifact ->
                artifact.path.matches(Regex("\\.tianyan-outputs/[A-Za-z0-9_.-]{1,180}")) &&
                    artifact.sha256.matches(Regex("[a-f0-9]{64}")) && artifact.bytes in 1..(16L * 1024 * 1024)
            }
        }
    }
}
