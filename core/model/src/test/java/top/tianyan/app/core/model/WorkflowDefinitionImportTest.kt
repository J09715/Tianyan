package top.tianyan.app.core.model

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowDefinitionImportTest {
    private val definition = WorkflowDefinition("source", "source", isBuiltin = true,
        trigger = WorkflowTrigger.Proactive("event", suggestionLabel = "run"), createdAt = 1L, updatedAt = 2L,
        maxParallelNodes = 2, nodes = listOf(WorkflowNode("a", WorkflowNodeType.TRIGGER, "a"),
            WorkflowNode("b", WorkflowNodeType.BASH_COMMAND, "b", config = mapOf("command" to "echo \${a.output}"))),
        edges = listOf(WorkflowEdge("edge", "a", toNodeId = "b")), defaultVariables = mapOf("VALUE" to "saved"))
    private fun encode(value: WorkflowDefinition) = Json.encodeToString(WorkflowDefinition.serializer(), value)

    @Test fun importsGraphWithoutAutomaticTriggersOrBuiltinIdentity() {
        val imported = WorkflowDefinitionImport.parse(encode(definition))
        assertEquals(definition.nodes, imported.nodes)
        assertEquals(definition.edges, imported.edges)
        assertEquals(definition.defaultVariables, imported.defaultVariables)
        assertEquals(2, imported.maxParallelNodes)
        assertFalse(imported.isBuiltin)
        assertEquals(WorkflowTrigger.Manual(), imported.trigger)
        assertEquals(0L, imported.createdAt)
        assertEquals(0L, imported.updatedAt)
        val one = WorkflowDefinitionImport.fresh(imported)
        val two = WorkflowDefinitionImport.fresh(imported)
        assertNotEquals("source", one.id)
        assertNotEquals(one.id, two.id)
        assertEquals(imported.nodes, one.nodes)
    }

    @Test fun fileWatchAndSlashCommandBindingsAreRemoved() {
        listOf(WorkflowTrigger.FileWatch("**/*"), WorkflowTrigger.Manual("/wf existing")).forEach { trigger ->
            assertEquals(WorkflowTrigger.Manual(), WorkflowDefinitionImport.parse(encode(definition.copy(trigger = trigger))).trigger)
        }
    }

    @Test fun bothDocumentedExamplesImportAndRemainEditable() {
        listOf("dynamic-workflow.json", "staged-dynamic-workflow.json").forEach { name ->
            val raw = File("../../docs/examples/$name").readText()
            val source = Json.decodeFromString<WorkflowDefinition>(raw)
            val imported = WorkflowDefinitionImport.fresh(WorkflowDefinitionImport.parse(raw))
            assertEquals(source.nodes, imported.nodes)
            assertEquals(source.defaultVariables, imported.defaultVariables)
            assertTrue(WorkflowValidator.validate(imported).isEmpty())
            assertFalse(imported.isBuiltin)
        }
    }

    @Test fun rejectsInvalidGraphUnknownFieldsAndExecutionSnapshots() {
        val invalid = listOf(definition.copy(edges = listOf(WorkflowEdge("bad", "missing", toNodeId = "b"))),
            definition.copy(nodes = definition.nodes + definition.nodes.first()),
            definition.copy(edges = definition.edges + WorkflowEdge("cycle", "b", toNodeId = "a")),
            definition.copy(maxParallelNodes = 0))
        invalid.forEach { assertTrue(runCatching { WorkflowDefinitionImport.parse(encode(it)) }.isFailure) }
        assertTrue(runCatching { WorkflowDefinitionImport.parse("""{"id":"source","name":"source","unknown":true}""") }.isFailure)
        val snapshot = WorkflowRuntimeState.initial("run", definition)
        assertTrue(runCatching { WorkflowDefinitionImport.parse(Json.encodeToString(WorkflowRuntimeState.serializer(), snapshot)) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionImport.parse("""{"id":"source","name":"source","nodes":[{"id":"a","title":"a","type":"SHELL"}]}""") }.isFailure)
    }

    @Test fun boundsSizeDepthAndGraphCapacityBeforeAcceptingInput() {
        assertTrue(runCatching { WorkflowDefinitionImport.parse(" ".repeat(WorkflowDefinitionImport.MAX_CHARACTERS + 1)) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionImport.parse("[".repeat(33) + "]".repeat(33)) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionImport.parse(encode(definition.copy(nodes = (0..256).map {
            WorkflowNode("n$it", WorkflowNodeType.TRIGGER, "n$it") }))) }.isFailure)
        assertTrue(runCatching { WorkflowDefinitionImport.parse(encode(definition.copy(edges = (0..1024).map {
            WorkflowEdge("e$it", "a", toNodeId = "b") }))) }.isFailure)
        val quoted = definition.copy(description = "{".repeat(40) + "quoted \\\" text" + "}".repeat(40))
        assertEquals(quoted.description, WorkflowDefinitionImport.parse(encode(quoted)).description)
    }
}
