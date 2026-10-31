package top.tianyan.app.core.database

import android.util.AtomicFile
import java.io.File
import java.io.IOException

/** The location is supplied lazily and must belong to the application's no-backup directory. */
class EncryptedRequestDiagnosticsRepository(
    private val location: () -> File,
    private val encrypt: (String) -> String,
    private val decrypt: (String) -> String?,
) : RequestDiagnosticsRepository {
    override fun read(): String? {
        val archive = AtomicFile(location())
        val ciphertext = try {
            archive.openRead().use { input ->
                check(archive.baseFile.length() <= MAX_CIPHERTEXT_BYTES) { "Diagnostics archive exceeds limit" }
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    check(output.size() + count <= MAX_CIPHERTEXT_BYTES) { "Diagnostics archive exceeds limit" }
                    output.write(buffer, 0, count)
                }
                output.toString("UTF-8")
            }
        } catch (missing: java.io.FileNotFoundException) {
            if (archive.baseFile.exists()) throw missing
            return null
        }
        val plaintext = decrypt(ciphertext) ?: throw IOException("Cannot decrypt diagnostics archive")
        check(plaintext.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) { "Diagnostics archive exceeds limit" }
        return plaintext
    }

    override fun write(redactedArchive: String) {
        check(redactedArchive.toByteArray(Charsets.UTF_8).size <= MAX_PLAINTEXT_BYTES) { "Diagnostics archive exceeds limit" }
        // Encrypt before opening a transaction; failure never leaves plaintext on disk.
        val ciphertext = encrypt(redactedArchive).toByteArray(Charsets.UTF_8)
        check(ciphertext.size <= MAX_CIPHERTEXT_BYTES) { "Diagnostics archive exceeds limit" }
        val file = location()
        check(file.parentFile!!.let { it.isDirectory || it.mkdirs() }) { "Cannot create diagnostics directory" }
        val archive = AtomicFile(file)
        val output = archive.startWrite()
        try {
            output.write(ciphertext)
            archive.finishWrite(output)
        } catch (failure: Throwable) {
            archive.failWrite(output)
            throw failure
        }
    }

    companion object {
        const val MAX_PLAINTEXT_BYTES = 32 * 1024 * 1024
        const val MAX_CIPHERTEXT_BYTES = 44 * 1024 * 1024
    }
}
