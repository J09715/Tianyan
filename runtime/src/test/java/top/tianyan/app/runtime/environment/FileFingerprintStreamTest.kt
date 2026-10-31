package top.tianyan.app.runtime.environment

import java.io.ByteArrayInputStream
import java.security.MessageDigest
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class FileFingerprintStreamTest {
    @Test fun streamsBinaryBytesWithoutTextConversion() = runBlocking {
        val bytes = ByteArray(200_000) { (it * 17).toByte() }
        val observed = fingerprintStream(ByteArrayInputStream(bytes), bytes.size.toLong())
        assertEquals(bytes.size.toLong(), observed.bytes)
        assertEquals(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }, observed.sha256)
    }

    @Test fun boundReadsAtMostOneExtraByteAndRejectsOversizedContent() = runBlocking {
        var read = 0
        val input = object : ByteArrayInputStream(ByteArray(1000)) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = super.read(buffer, offset, length).also { if (it > 0) read += it }
        }
        assertTrue(runCatching { fingerprintStream(input, 8) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(9, read)
    }

    @Test fun cancellationStopsBeforeReadingTheNextChunk() = runBlocking {
        var reads = 0
        val running = async {
            val job = currentCoroutineContext()[Job]!!
            val input = object : ByteArrayInputStream(ByteArray(200_000)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    reads++; job.cancel(); return super.read(buffer, offset, length)
                }
            }
            fingerprintStream(input, 200_000)
        }
        assertTrue(runCatching { running.await() }.exceptionOrNull() is CancellationException)
        assertEquals(1, reads)
    }
}
