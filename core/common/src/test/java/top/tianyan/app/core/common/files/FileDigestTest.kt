package top.tianyan.app.core.common.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FileDigestTest {

    @Test
    fun `sha256 matches a known digest and streams large files`() {
        val file = File.createTempFile("tianyan-digest", ".txt")
        try {
            file.writeText("abc")
            // echo -n abc | sha256sum
            assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", FileDigest.sha256(file))

            val large = File.createTempFile("tianyan-digest-large", ".bin")
            try {
                large.outputStream().use { out -> repeat(64) { out.write(ByteArray(64 * 1024) { 7 }) } }
                assertEquals(4 * 1024 * 1024L, large.length())
                assertEquals(64, FileDigest.sha256(large)?.length)
            } finally {
                large.delete()
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun `missing file and exceeded budget return null`() {
        assertNull(FileDigest.sha256(File("/definitely/not/here")))
        val file = File.createTempFile("tianyan-digest-budget", ".txt")
        try {
            file.writeText("abcdef")
            assertNull(FileDigest.sha256(file, maxBytes = 3))
            assertEquals(64, FileDigest.sha256(file, maxBytes = 6)?.length)
        } finally {
            file.delete()
        }
    }
}