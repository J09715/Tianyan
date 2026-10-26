package top.tianyan.app.core.common.files

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File

class BoundedStreamCopyTest {

    @Test
    fun copyWithinLimitCompletesCleanly() {
        val payload = "Hello Tianyan Mobile Memory Guard!".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(payload)
        val output = ByteArrayOutputStream()

        val result = BoundedStreamCopy.copy(
            input = input,
            output = output,
            maxBytes = 1000,
            bufferSize = 8,
        )

        assertEquals(payload.size.toLong(), result.bytesCopied)
        assertFalse(result.isTruncated)
        assertTrue(result.reachedEof)
        assertEquals("Hello Tianyan Mobile Memory Guard!", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun copyExceedingLimitWithAbortThrowsException() {
        val payload = ByteArray(1024) { it.toByte() }
        val input = ByteArrayInputStream(payload)
        val output = ByteArrayOutputStream()

        try {
            BoundedStreamCopy.copy(
                input = input,
                output = output,
                maxBytes = 500,
                policy = BoundedStreamCopy.OverflowPolicy.ABORT,
            )
            fail("Expected PayloadTooLargeException")
        } catch (e: PayloadTooLargeException) {
            assertEquals(500L, e.maxBytes)
            assertTrue(e.actualBytesObserved > 500L)
        }
    }

    @Test
    fun copyExceedingLimitWithTruncateStopsPrecisely() {
        val payload = "1234567890ABCDEF".toByteArray(Charsets.UTF_8)
        val input = ByteArrayInputStream(payload)
        val output = ByteArrayOutputStream()

        val result = BoundedStreamCopy.copy(
            input = input,
            output = output,
            maxBytes = 10,
            bufferSize = 4,
            policy = BoundedStreamCopy.OverflowPolicy.TRUNCATE,
        )

        assertEquals(10L, result.bytesCopied)
        assertTrue(result.isTruncated)
        assertFalse(result.reachedEof)
        assertEquals("1234567890", output.toString(Charsets.UTF_8.name()))
    }

    @Test
    fun copyToFileCleansUpDestinationWhenAborted() {
        val tempFile = File.createTempFile("bounded_copy_test_", ".tmp")
        try {
            val payload = ByteArray(2048) { 1 }
            val input = ByteArrayInputStream(payload)

            try {
                BoundedStreamCopy.copyToFile(
                    input = input,
                    targetFile = tempFile,
                    maxBytes = 512,
                    policy = BoundedStreamCopy.OverflowPolicy.ABORT,
                )
                fail("Expected PayloadTooLargeException")
            } catch (_: PayloadTooLargeException) {
                // 预期行为：由于抛出异常，目标文件已被自动清理删除
                assertFalse("Target file should be deleted on overflow abort", tempFile.exists())
            }
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun progressCallbackTracksBytesWritten() {
        val payload = ByteArray(100) { 1 }
        val input = ByteArrayInputStream(payload)
        val output = ByteArrayOutputStream()
        val progressList = mutableListOf<Long>()

        BoundedStreamCopy.copy(
            input = input,
            output = output,
            maxBytes = 200,
            bufferSize = 30,
            onProgress = { progressList.add(it) },
        )

        assertEquals(listOf(30L, 60L, 90L, 100L), progressList)
    }

}
