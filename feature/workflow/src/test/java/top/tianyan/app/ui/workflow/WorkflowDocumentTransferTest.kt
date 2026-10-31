package top.tianyan.app.ui.workflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.CancellationException
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowDocumentTransferTest {
    private class Source(bytes: ByteArray) : ByteArrayInputStream(bytes) {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private class Target : ByteArrayOutputStream() {
        var closed = false
        override fun close() { closed = true; super.close() }
    }
    private val definition = WorkflowDefinition("source", "中文😀", nodes = listOf(
        WorkflowNode("a", WorkflowNodeType.BASH_COMMAND, "输出", config = mapOf("command" to "printf '中文😀\\n'"))), maxParallelNodes = 2)

    @Test fun utf8FileRoundTripPreservesCompleteDefinitionAndClosesStreams() {
        val raw = WorkflowDefinitionExport.encode(definition)
        val target = Target()
        WorkflowDocumentTransfer.write(target, raw)
        assertTrue(target.closed)
        assertArrayEquals(raw.toByteArray(Charsets.UTF_8), target.toByteArray())
        val input = Source(target.toByteArray())
        val restored = WorkflowDefinitionImport.parse(WorkflowDocumentTransfer.read(input))
        assertEquals(definition.nodes, restored.nodes)
        assertEquals(definition.name, restored.name)
        assertEquals(2, restored.maxParallelNodes)
        assertTrue(input.closed)
    }

    @Test fun acceptsUtf8BomButRejectsMalformedOrTruncatedEncoding() {
        val raw = WorkflowDefinitionExport.encode(definition)
        val withBom = Source(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + raw.toByteArray(Charsets.UTF_8))
        assertEquals(raw, WorkflowDocumentTransfer.read(withBom))
        listOf(byteArrayOf(0xC3.toByte(), 0x28), byteArrayOf(0xF0.toByte(), 0x9F.toByte())).forEach { bytes ->
            val source = Source(bytes)
            assertThrows(IllegalArgumentException::class.java) { WorkflowDocumentTransfer.read(source) }
            assertTrue(source.closed)
        }
    }

    @Test fun rejectsByteAndCharacterOverflowWithoutAcceptingTruncatedInput() {
        val hugeBytes = Source(ByteArray(WorkflowDocumentTransfer.MAX_BYTES + 1) { 'x'.code.toByte() })
        assertThrows(IllegalArgumentException::class.java) { WorkflowDocumentTransfer.read(hugeBytes) }
        assertTrue(hugeBytes.closed)
        val hugeText = Source("x".repeat(WorkflowDefinitionImport.MAX_CHARACTERS + 1).toByteArray())
        assertThrows(IllegalArgumentException::class.java) { WorkflowDocumentTransfer.read(hugeText) }
        assertTrue(hugeText.closed)
    }

    @Test fun cancellationClosesInputAndOutputWithoutReportingCompletion() {
        val input = Source(ByteArray(30_000))
        var checks = 0
        assertThrows(CancellationException::class.java) {
            WorkflowDocumentTransfer.read(input) { if (++checks == 2) throw CancellationException("cancelled") }
        }
        assertTrue(input.closed)
        val output = Target()
        checks = 0
        assertThrows(CancellationException::class.java) {
            WorkflowDocumentTransfer.write(output, "x".repeat(30_000)) { if (++checks == 2) throw CancellationException("cancelled") }
        }
        assertTrue(output.closed)
        assertEquals(8192, output.size())
    }

    @Test fun providerReadWriteAndCloseFailuresPropagateAndCloseStreams() {
        var inputClosed = false
        val input = object : InputStream() {
            override fun read(): Int = throw IOException("read failed")
            override fun close() { inputClosed = true }
        }
        assertThrows(IOException::class.java) { WorkflowDocumentTransfer.read(input) }
        assertTrue(inputClosed)
        var outputClosed = false
        val output = object : OutputStream() {
            override fun write(value: Int) { throw IOException("write failed") }
            override fun close() { outputClosed = true }
        }
        assertThrows(IOException::class.java) { WorkflowDocumentTransfer.write(output, "data") }
        assertTrue(outputClosed)
        val closeFailure = object : ByteArrayOutputStream() { override fun close() { throw IOException("close failed") } }
        assertThrows(IOException::class.java) { WorkflowDocumentTransfer.write(closeFailure, "data") }
    }

    @Test fun invalidOutputTextIsRejectedBeforeWritingAndStillClosesTarget() {
        val output = Target()
        assertThrows(IllegalArgumentException::class.java) { WorkflowDocumentTransfer.write(output, "x".repeat(WorkflowDefinitionImport.MAX_CHARACTERS + 1)) }
        assertEquals(0, output.size())
        assertTrue(output.closed)
        val surrogate = Target()
        assertTrue(runCatching { WorkflowDocumentTransfer.write(surrogate, "\uD800") }.isFailure)
        assertEquals(0, surrogate.size())
        assertTrue(surrogate.closed)
    }

    @Test fun zeroLengthBulkReadsDoNotSpinOrLoseBytes() {
        val bytes = "测试".toByteArray()
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(buffer: ByteArray, offset: Int, length: Int): Int = if (available() > 0) 0 else -1
        }
        assertEquals("测试", WorkflowDocumentTransfer.read(input))
    }

    @Test fun suggestedNamesArePortableAndKeepJsonSuffix() {
        assertEquals("workflow.json", WorkflowDocumentTransfer.fileName(" .. "))
        assertEquals("folder_name_.json", WorkflowDocumentTransfer.fileName("folder/name?.JSON"))
        assertEquals("workflow_CON.json", WorkflowDocumentTransfer.fileName("CON"))
        assertEquals("中文😀.json", WorkflowDocumentTransfer.fileName("中文😀"))
        val long = WorkflowDocumentTransfer.fileName("x".repeat(79) + "😀" + "more")
        assertTrue(long.endsWith("😀.json"))
    }
}
