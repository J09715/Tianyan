package top.tianyan.app.core.database

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.spec.GCMParameterSpec

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EncryptedRequestDiagnosticsRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun encrypt(text: String): String {
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        return Base64.getEncoder().encodeToString(iv + cipher.doFinal(text.toByteArray()))
    }
    private fun decrypt(text: String): String? = runCatching {
        val bytes = Base64.getDecoder().decode(text)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }.getOrNull()

    @Test fun writesOnlyCiphertextAndRestoresWithFreshRepository() {
        val file = File(temporary.root, "no-backup/requests.enc")
        val repository = EncryptedRequestDiagnosticsRepository({ file }, ::encrypt, ::decrypt)
        assertNull(repository.read())
        repository.write("redacted conversation content")
        assertFalse(file.readText().contains("conversation"))
        assertEquals("redacted conversation content", EncryptedRequestDiagnosticsRepository({ file }, ::encrypt, ::decrypt).read())
        assertEquals(listOf("requests.enc"), file.parentFile!!.list()!!.toList())
    }

    @Test fun encryptionFailurePreservesPreviousArchiveAndNeverWritesPlaintext() {
        val file = File(temporary.root, "requests.enc")
        val repository = EncryptedRequestDiagnosticsRepository({ file }, ::encrypt, ::decrypt)
        repository.write("previous")
        val before = file.readBytes()
        val failing = EncryptedRequestDiagnosticsRepository({ file }, { error("unavailable key") }, ::decrypt)
        assertThrows(IllegalStateException::class.java) { failing.write("new private content") }
        assertArrayEquals(before, file.readBytes())
        assertEquals("previous", repository.read())
    }

    @Test fun corruptCiphertextAndOversizedInputAreRejected() {
        val file = File(temporary.root, "requests.enc")
        val repository = EncryptedRequestDiagnosticsRepository({ file }, ::encrypt, ::decrypt)
        file.writeText("damaged ciphertext")
        assertThrows(java.io.IOException::class.java) { repository.read() }
        assertThrows(IllegalStateException::class.java) {
            repository.write("x".repeat(EncryptedRequestDiagnosticsRepository.MAX_PLAINTEXT_BYTES + 1))
        }
        assertEquals("damaged ciphertext", file.readText())
        java.io.RandomAccessFile(file, "rw").use { it.setLength(EncryptedRequestDiagnosticsRepository.MAX_CIPHERTEXT_BYTES.toLong() + 1) }
        assertThrows(IllegalStateException::class.java) { repository.read() }
    }
}
