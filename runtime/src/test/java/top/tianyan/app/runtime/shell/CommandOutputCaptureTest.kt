package top.tianyan.app.runtime.shell

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.SequenceInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CommandOutputCaptureTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun longOutputPreservesMiddleAndTailBeyondLegacyCaptureLimit() = runBlocking {
        val directory = temporary.newFolder()
        val output = "begin\n" + "日志\n".repeat(400_000) + "middle-evidence\n" + "line\n".repeat(80_000) + "final-error\n"
        val executor = ProcessShellExecutor({ emptyMap() }, { directory }) { _, _, _ -> finiteProcess(output) }
        val expanded = CommandOutputCapture().use { capture ->
            val result = withContext(capture) { executor.execute(listOf("fake")) }
            assertTrue(result.stdout.contains("进程输出已截断"))
            assertFalse(result.stdout.contains("final-error"))
            capture.expand(result)
        }
        assertEquals(output, expanded.stdout)
        assertTrue(directory.listFiles()!!.isEmpty())
        assertFalse(executor.hasPending())
    }

    @Test fun smallOutputCreatesNoSpoolAndPreservesUtf8AcrossByteChunks() {
        val directory = temporary.newFolder()
        val stream = CommandStreamCapture(directory, inlineBytes = 100)
        stream.use {
            val bytes = "中文🙂\n".toByteArray()
            bytes.forEach { stream.add(byteArrayOf(it), 1) }
            stream.finished()
            assertEquals("中文🙂\n", stream.text(""))
            assertTrue(directory.listFiles()!!.isEmpty())
        }
    }

    @Test fun storageCapIsExplicitKeepsTailAndDropsCutSecretLines() {
        val directory = temporary.newFolder()
        CommandStreamCapture(directory, inlineBytes = 8, maxBytes = 32, tailBytes = 24).use { capture ->
            val output = "head\napi_key=" + "secret".repeat(20) + "\nfinal-error\n"
            val bytes = output.toByteArray()
            capture.add(bytes, bytes.size)
            capture.finished()
            val saved = capture.text("")
            assertTrue(saved.contains("输出保存达到上限"))
            assertTrue(saved.contains("final-error"))
            assertFalse(saved.contains("secret"))
            assertTrue(directory.listFiles()!!.single().length() <= 32)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun unavailableCacheDoesNotTurnSuccessfulCommandIntoFailure() = runBlocking {
        val blocked = temporary.newFile()
        val output = "line\n".repeat(30_000)
        val executor = ProcessShellExecutor({ emptyMap() }, { blocked }) { _, _, _ -> finiteProcess(output) }
        val result = CommandOutputCapture().use { capture ->
            capture.expand(withContext(capture) { executor.execute(listOf("fake")) })
        }
        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("完整输出缓存不可用"))
        assertTrue(blocked.isFile)
    }

    @Test fun timeoutPreservesReadOutputAndRemovesSpool() = runBlocking {
        val directory = temporary.newFolder()
        val blocked = FakeSessionProcess()
        val output = "progress\n".repeat(20_000)
        val executor = ProcessShellExecutor({ emptyMap() }, { directory }) { _, _, _ -> blockingProcess(output, blocked) }
        val result = CommandOutputCapture().use { capture ->
            capture.expand(withContext(capture) { executor.execute(listOf("fake"), timeoutMs = 100) })
        }
        assertEquals(124, result.exitCode)
        assertEquals(output, result.stdout)
        assertTrue(result.stderr.contains("timed out"))
        assertTrue(directory.listFiles()!!.isEmpty())
        assertFalse(executor.hasPending())
    }

    @Test fun cancellationDrainsProcessBeforeDeletingSpool() = runBlocking {
        val directory = temporary.newFolder()
        val blocked = FakeSessionProcess()
        val executor = ProcessShellExecutor({ emptyMap() }, { directory }) { _, _, _ -> blockingProcess("line\n".repeat(20_000), blocked) }
        val job = launch {
            CommandOutputCapture().use { capture -> withContext(capture) { executor.execute(listOf("fake")) } }
        }
        withContext(Dispatchers.IO) { assertTrue(blocked.stdout.entered.await(3, TimeUnit.SECONDS)) }
        assertTrue(directory.listFiles()!!.isNotEmpty())
        job.cancelAndJoin()
        assertFalse(blocked.alive)
        assertFalse(executor.hasPending())
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun orphanCleanupNeverDeletesAnotherActiveCapture() {
        val directory = temporary.newFolder()
        val orphan = File(directory, "command-orphan.tmp").apply { writeText("old") }
        val unrelated = File(directory, "keep.txt").apply { writeText("keep") }
        CommandStreamCapture(directory, inlineBytes = 8).use { first ->
            first.add("first-live-output\n".toByteArray(), 18)
            CommandStreamCapture(directory, inlineBytes = 8).use { second ->
                val bytes = "second-live-output\n".toByteArray()
                second.add(bytes, bytes.size)
                first.finished(); second.finished()
                assertEquals("first-live-output\n", first.text(""))
                assertEquals("second-live-output\n", second.text(""))
                assertFalse(orphan.exists())
                assertTrue(unrelated.exists())
            }
        }
        assertEquals(listOf("keep.txt"), directory.listFiles()!!.map { it.name })
    }

    @Test fun globalSpoolReservationsAreBoundedAndFailureKeepsDrainAvailable() {
        val directory = temporary.newFolder()
        val captures = List(11) { CommandStreamCapture(directory, inlineBytes = 8) }
        try {
            val bytes = "long-enough\n".toByteArray()
            captures.forEach { it.add(bytes, bytes.size); it.finished() }
            assertEquals(10, directory.listFiles()!!.size)
            assertEquals("long-enough\n", captures.first().text("fallback"))
            assertTrue(captures.last().text("fallback").contains("完整输出缓存不可用"))
        } finally { captures.forEach { it.close() } }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun partiallyDrainedPipeIsNeverLabelledComplete() {
        val directory = temporary.newFolder()
        CommandStreamCapture(directory).use { capture ->
            val bytes = "partial progress\n".toByteArray()
            capture.add(bytes, bytes.size)
            assertTrue(capture.text("").contains("输出管道未完全排空"))
        }
    }

    @Test fun bootstrapCommandsDoNotConsumeTheForegroundCapture() = runBlocking {
        val directory = temporary.newFolder()
        val executor = ProcessShellExecutor({ emptyMap() }, { directory }) { args, _, _ -> finiteProcess(args.single()) }
        CommandOutputCapture().use { capture ->
            val command = ShellCommand("target-output", outputCapture = capture)
            // Mirrors LinuxRuntime: readiness and layout resolution precede the capture activation.
            assertEquals("bootstrap-output", executor.execute(listOf("bootstrap-output")).stdout)
            val result = command.withOutputCapture { executor.execute(listOf(command.commandLine)) }
            assertEquals("target-output", capture.expand(result).stdout)
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun nullableCapturedOperationIsNotExecutedTwice() = runBlocking {
        var executions = 0
        CommandOutputCapture().use { capture ->
            val result = ShellCommand("fake", outputCapture = capture).withOutputCapture<Int?> { executions++; null }
            assertNull(result)
        }
        assertEquals(1, executions)
    }

    private fun finiteProcess(output: String) = object : Process() {
        private val input = ByteArrayInputStream(output.toByteArray())
        override fun getInputStream() = input
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor() = 0
        override fun waitFor(timeout: Long, unit: TimeUnit) = true
        override fun exitValue() = 0
        override fun isAlive() = false
        override fun destroy() { }
    }

    private fun blockingProcess(output: String, blocked: FakeSessionProcess) = object : Process() {
        private val input = SequenceInputStream(ByteArrayInputStream(output.toByteArray()), blocked.stdout)
        override fun getInputStream() = input
        override fun getErrorStream() = blocked.stderr
        override fun getOutputStream() = blocked.stdin
        override fun waitFor() = blocked.waitFor()
        override fun waitFor(timeout: Long, unit: TimeUnit) = blocked.waitFor(timeout, unit)
        override fun exitValue() = blocked.exitValue()
        override fun isAlive() = blocked.isAlive
        override fun destroy() = blocked.destroy()
        override fun destroyForcibly(): Process { blocked.destroyForcibly(); return this }
    }
}
