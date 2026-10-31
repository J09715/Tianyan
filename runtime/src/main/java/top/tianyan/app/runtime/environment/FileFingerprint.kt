package top.tianyan.app.runtime.environment

import java.io.InputStream
import java.security.MessageDigest
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

data class FileFingerprint(val sha256: String, val bytes: Long)

/** Bounded binary hashing; callers also verify that file identity/attributes did not change. */
suspend fun fingerprintStream(input: InputStream, maxBytes: Long): FileFingerprint {
    require(maxBytes in 1..MAX_FILE_FINGERPRINT_BYTES)
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(64 * 1024)
    var count = 0L
    while (true) {
        currentCoroutineContext().ensureActive()
        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), maxBytes - count + 1).toInt())
        if (read < 0) break
        count += read
        require(count <= maxBytes) { "文件指纹读取超过字节限制" }
        digest.update(buffer, 0, read)
    }
    currentCoroutineContext().ensureActive()
    return FileFingerprint(digest.digest().joinToString("") { "%02x".format(it) }, count)
}

const val MAX_FILE_FINGERPRINT_BYTES = 128L * 1024 * 1024
