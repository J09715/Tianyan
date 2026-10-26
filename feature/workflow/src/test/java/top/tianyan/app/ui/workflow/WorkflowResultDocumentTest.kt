package top.tianyan.app.ui.workflow

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.WorkflowDefinitionImport

class WorkflowResultDocumentTest {
    @Test fun preservesTypedValuesAndUnicodeThroughFileTransfer() {
        val result = Json.parseToJsonElement("""{"说明":"诊断完成🙂","数量":1234567890123456789,"比例":1.25,"通过":true,"空":null,"列表":[1,"二",{"nested":"value"}]}""") as JsonObject
        val raw = WorkflowResultDocument.encode(result)
        val target = ByteArrayOutputStream()
        WorkflowDocumentTransfer.write(target, raw)
        val loaded = WorkflowDocumentTransfer.read(ByteArrayInputStream(target.toByteArray()))
        assertEquals(result, Json.parseToJsonElement(loaded))
        assertEquals(raw, WorkflowResultDocument.pages(raw).joinToString(""))
    }

    @Test fun allPagesPreserveTailAndNeverSplitEmoji() {
        val raw = "abc🙂诊断".repeat(2500) + "END🙂"
        val pages = WorkflowResultDocument.pages(raw, 4)
        assertEquals(raw, pages.joinToString(""))
        assertTrue(pages.all { it.length <= 4 })
        assertTrue(pages.none { it.first().isLowSurrogate() || it.last().isHighSurrogate() })
        assertTrue(pages.joinToString("").endsWith("END🙂"))
    }

    @Test fun paginationHandlesEmptyBoundaryAndLargePageSize() {
        assertEquals(listOf(""), WorkflowResultDocument.pages(""))
        assertEquals(listOf("🙂", "🙂", "a"), WorkflowResultDocument.pages("🙂🙂a", 2))
        assertEquals(listOf("abc", "def"), WorkflowResultDocument.pages("abcdef", 3))
        assertEquals(listOf("abc"), WorkflowResultDocument.pages("abc", Int.MAX_VALUE))
        try { WorkflowResultDocument.pages("abc", 1); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun formattingFallsBackToCompleteCompactJsonWhenIndentationExceedsLimit() {
        val result = buildJsonObject { put("items", JsonArray(List(60000) { JsonNull })) }
        val pretty = Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), result)
        assertTrue(pretty.length > WorkflowDefinitionImport.MAX_CHARACTERS)
        val raw = WorkflowResultDocument.encode(result)
        assertEquals(result.toString(), raw)
        assertEquals(result, Json.parseToJsonElement(raw))
        assertEquals(raw, WorkflowResultDocument.pages(raw).joinToString(""))
    }

    @Test fun oversizedResultFailsWithoutTruncationAndResultIsNotImportableAsDefinition() {
        val result = buildJsonObject { put("value", "x".repeat(WorkflowDefinitionImport.MAX_CHARACTERS)) }
        try { WorkflowResultDocument.encode(result); fail() } catch (_: IllegalArgumentException) { }
        val raw = WorkflowResultDocument.encode(buildJsonObject { put("answer", "完成") })
        try { WorkflowDefinitionImport.parse(raw); fail() } catch (_: IllegalArgumentException) { }
    }
}
