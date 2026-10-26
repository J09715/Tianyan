package top.tianyan.app.ui.workflow

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport

/** Owns and closes the selected document stream on success, error, or cancellation. */
internal object WorkflowDocumentTransfer {
    const val MAX_BYTES = WorkflowDefinitionImport.MAX_CHARACTERS * 4 + 3

    fun read(input: InputStream, checkCancelled: () -> Unit = {}): String = input.use { source ->
        val bytes = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            checkCancelled()
            var count = source.read(buffer)
            if (count < 0) break
            if (count == 0) {
                val byte = source.read()
                if (byte < 0) break
                buffer[0] = byte.toByte()
                count = 1
            }
            require(bytes.size() + count <= MAX_BYTES) { "JSON 文件超过允许大小" }
            bytes.write(buffer, 0, count)
        }
        checkCancelled()
        val text = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        } catch (error: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("JSON 文件必须使用有效 UTF-8 编码", error)
        }
        val raw = text.removePrefix("\uFEFF")
        require(raw.length <= WorkflowDefinitionImport.MAX_CHARACTERS) { "工作流 JSON 最多允许 524288 个字符" }
        raw
    }

    fun write(output: OutputStream, raw: String, checkCancelled: () -> Unit = {}) = output.use { target ->
        require(raw.length <= WorkflowDefinitionImport.MAX_CHARACTERS) { "导出 JSON 超过允许大小" }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(raw))
        val buffer = ByteArray(8192)
        while (encoded.hasRemaining()) {
            checkCancelled()
            val count = minOf(buffer.size, encoded.remaining())
            encoded.get(buffer, 0, count)
            target.write(buffer, 0, count)
        }
        checkCancelled()
        target.flush()
    }

    fun fileName(name: String): String {
        val cleaned = name.replace(Regex("[\\p{Cntrl}\\\\/:*?\"<>|]"), "_")
            .replace(Regex("\\.json$", RegexOption.IGNORE_CASE), "").trim(' ', '.')
        var base = cleaned.take(cleaned.offsetByCodePoints(0, minOf(80, cleaned.codePointCount(0, cleaned.length)))).ifBlank { "workflow" }
        if (base.uppercase().matches(Regex("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]"))) base = "workflow_$base"
        return "$base.json"
    }
}
