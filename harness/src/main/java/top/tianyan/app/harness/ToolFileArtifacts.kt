package top.tianyan.app.harness

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import top.tianyan.app.runtime.environment.FileFingerprint
import top.tianyan.app.runtime.environment.MAX_FILE_FINGERPRINT_BYTES

@Serializable
enum class FileArtifactKind { APK, IMAGE, FILE }
@Serializable
enum class FileArtifactOrigin { NEW_AFTER_COMMAND, CHANGED_AFTER_COMMAND, UNCHANGED_AFTER_COMMAND, OBSERVED_AFTER_COMMAND, AFTER_DOWNLOAD, IMAGE_READ }
@Serializable
data class ToolFileArtifact(val path: String, val sha256: String, val bytes: Long, val kind: FileArtifactKind,
    val origin: FileArtifactOrigin, val observedAt: Long)

/** Trusted producer observations, not paths parsed from stdout or model-authored artifact claims. */
@Serializable
data class ToolFileArtifacts(val files: List<ToolFileArtifact>, val complete: Boolean) {
    companion object {
        const val METADATA_KEY = "file_artifacts"
        const val MAX_FILES = 16
        fun encode(files: List<ToolFileArtifact>, complete: Boolean = true): String =
            Json.encodeToString(serializer(), ToolFileArtifacts(files.take(MAX_FILES), complete && files.size <= MAX_FILES))
        fun decode(value: String?): ToolFileArtifacts? = value?.let {
            runCatching { Json.decodeFromString(serializer(), it) }.getOrNull()?.takeIf { batch ->
                batch.files.size <= MAX_FILES && batch.files.all { file -> validPath(file.path) &&
                    file.sha256.matches(Regex("[a-f0-9]{64}")) && file.bytes in 1..MAX_FILE_FINGERPRINT_BYTES && file.observedAt > 0 }
            }
        }
        fun observation(path: String, fingerprint: FileFingerprint, origin: FileArtifactOrigin, now: Long) = ToolFileArtifact(
            path, fingerprint.sha256, fingerprint.bytes, when (path.substringAfterLast('.', "").lowercase()) {
                "apk" -> FileArtifactKind.APK
                "png", "jpg", "jpeg", "gif", "webp", "bmp", "ico" -> FileArtifactKind.IMAGE
                else -> FileArtifactKind.FILE
            }, origin, now)
        fun relativePath(path: String, workspace: String): String? {
            val root = workspace.trim().trimEnd('/')
            val relative = if (!path.startsWith('/')) path else if (root.isNotBlank() && path.startsWith("$root/")) path.removePrefix("$root/") else return null
            return relative.takeIf(::validPath)
        }
        private fun validPath(path: String) = path.length in 1..512 && !path.startsWith('/') && '\\' !in path &&
            path.none { it.isISOControl() } && path.split('/').all { it.isNotEmpty() && it != "." && it != ".." }

        suspend fun sanitize(metadata: MutableMap<String, String>, tool: HarnessTool, redact: suspend (String) -> String) {
            val value = metadata[METADATA_KEY] ?: return
            if (tool !in setOf(HarnessTool.BASE, HarnessTool.DOWNLOAD, HarnessTool.READ)) { metadata.remove(METADATA_KEY); return }
            val batch = decode(value) ?: run { metadata[METADATA_KEY] = encode(emptyList(), false); return }
            val retained = batch.files.filter { redact(it.path) == it.path }
            metadata[METADATA_KEY] = encode(retained, batch.complete && retained.size == batch.files.size)
        }
    }
}
