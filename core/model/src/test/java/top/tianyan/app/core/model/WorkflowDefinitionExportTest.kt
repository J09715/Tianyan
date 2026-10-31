package top.tianyan.app.core.model

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowDefinitionExportTest {
    private val definition = WorkflowDefinition("source", "source", isBuiltin = true,
        trigger = WorkflowTrigger.FileWatch("**/*"), createdAt = 1L, updatedAt = 2L, maxParallelNodes = 2,
        nodes = listOf(WorkflowNode("a", WorkflowNodeType.TRIGGER, "a"),
            WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "b", config = mapOf("command" to "printf '\${VALUE}\\n'"), canvasX = 123f)),
        edges = listOf(WorkflowEdge("edge", "a", "success", "b")), defaultVariables = mapOf("VALUE" to "quoted \" text\nnext"))

    @Test fun exportRoundTripPreservesGraphInputsPositionsAndExplicitParallelism() {
        val text = WorkflowDefinitionExport.encode(definition)
        val decoded = WorkflowDefinitionImport.parse(text)
        assertEquals(definition.nodes, decoded.nodes)
        assertEquals(definition.edges, decoded.edges)
        assertEquals(definition.defaultVariables, decoded.defaultVariables)
        assertEquals(2, decoded.maxParallelNodes)
        assertFalse(decoded.isBuiltin)
        assertEquals(WorkflowTrigger.Manual(), decoded.trigger)
        assertEquals(0L, decoded.createdAt)
        assertEquals(0L, decoded.updatedAt)
        assertTrue(text.contains('\n'))
    }

    @Test fun bothDynamicExamplesRetainPlanningContractsAndNestedReferences() {
        listOf("dynamic-workflow.json", "staged-dynamic-workflow.json").forEach { name ->
            val source = WorkflowDefinitionImport.parse(File("../../docs/examples/$name").readText())
            val result = WorkflowDefinitionImport.fresh(WorkflowDefinitionImport.parse(WorkflowDefinitionExport.encode(source)))
            assertEquals(source.nodes, result.nodes)
            assertEquals(source.edges, result.edges)
            assertEquals(source.defaultVariables, result.defaultVariables)
            assertNotEquals(source.id, result.id)
        }
    }

    @Test fun exportContainsOnlyDefinitionFieldsAndRejectsInvalidGraphs() {
        val objectValue = Json.parseToJsonElement(WorkflowDefinitionExport.encode(definition)).jsonObject
        assertTrue(objectValue.keys.intersect(setOf("executionId", "journal", "context", "nodeOutputs", "resumeCount", "baseDefinition")).isEmpty())
        assertTrue(objectValue.containsKey("maxParallelNodes"))
        assertTrue(runCatching { WorkflowDefinitionExport.encode(definition.copy(maxParallelNodes = 0)) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionExport.encode(definition.copy(edges = listOf(WorkflowEdge("bad", "missing", toNodeId = "b")))) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionExport.encode(definition.copy(nodes = emptyList())) }.isFailure)
    }

    @Test fun compactFallbackRemainsImportableNearTextLimit() {
        val normal = WorkflowDefinitionExport.encode(definition.copy(defaultVariables = emptyMap()))
        val compactNormal = Json { encodeDefaults = true }.encodeToString(WorkflowDefinition.serializer(), WorkflowDefinitionImport.parse(normal))
        val filler = "x".repeat(WorkflowDefinitionImport.MAX_CHARACTERS - compactNormal.length - 10)
        val large = definition.copy(description = filler, defaultVariables = emptyMap())
        val text = WorkflowDefinitionExport.encode(large)
        assertTrue(text.length <= WorkflowDefinitionImport.MAX_CHARACTERS)
        assertEquals(filler, WorkflowDefinitionImport.parse(text).description)
        assertFalse(text.contains('\n'))
    }

    @Test fun excessiveSizeIsRejectedRatherThanTruncatingDefinition() {
        val huge = definition.copy(description = "x".repeat(WorkflowDefinitionImport.MAX_CHARACTERS))
        assertTrue(runCatching { WorkflowDefinitionExport.encode(huge) }.isFailure)
    }
}
