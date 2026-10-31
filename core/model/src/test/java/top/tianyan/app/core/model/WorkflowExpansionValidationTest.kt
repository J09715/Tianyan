package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowExpansionValidationTest {
    private val definition = WorkflowDefinition("wf", "test", nodes = listOf(
        WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "plan", config = mapOf("resultSchema" to """{"type":"object"}""")),
        WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")),
    ), edges = listOf(WorkflowEdge("base", "plan", "success", "review")))
    private val patch = WorkflowExpansion(listOf(WorkflowNode("review.a", WorkflowNodeType.DELAY, "a")),
        listOf(WorkflowEdge("new", "review", "success", "review.a")))
    private fun rejected(value: WorkflowExpansion) = assertTrue(runCatching { WorkflowExpansions.append(definition, "review", value) }.isFailure)

    @Test fun documentedExampleImportsAndAcceptsMatchingProposal() {
        val example = Json.decodeFromString<WorkflowDefinition>(File("../../docs/examples/dynamic-workflow.json").readText())
        assertTrue(WorkflowValidator.validate(example).isEmpty())
        val schema = WorkflowResultSchema.parse(example.nodes.first { it.id == "plan" }.config.getValue("resultSchema"))
        val result = WorkflowResultSchema.validateResult("""{"nodes":[{"id":"review.echo","type":"BASH_COMMAND","title":"打印","config":{"command":"printf 'confirmed\\n'"}}],"edges":[{"id":"added_echo","fromNodeId":"review","fromPort":"success","toNodeId":"review.echo"}]}""", schema)
        val expanded = WorkflowExpansions.append(example, "review", WorkflowExpansions.decode(result))
        assertEquals(3, expanded.nodes.size)
        assertEquals(2, expanded.edges.size)
    }

    @Test fun cannotRewriteExistingNodesEdgesOrAttachOutsideAnchor() {
        rejected(patch.copy(nodes = listOf(patch.nodes.single().copy(id = "plan"))))
        rejected(patch.copy(edges = listOf(patch.edges.single().copy(id = "base"))))
        rejected(patch.copy(edges = listOf(patch.edges.single().copy(fromNodeId = "plan"))))
        rejected(patch.copy(edges = patch.edges + WorkflowEdge("back", "review.a", toNodeId = "plan")))
    }

    @Test fun rejectsUnreachableNodesCyclesAndResourceNodes() {
        rejected(patch.copy(nodes = patch.nodes + WorkflowNode("review.b", WorkflowNodeType.DELAY, "b")))
        rejected(patch.copy(edges = patch.edges + WorkflowEdge("loop", "review.a", toNodeId = "review.a")))
        rejected(patch.copy(nodes = listOf(patch.nodes.single().copy(type = WorkflowNodeType.HOST_ACTION))))
        rejected(patch.copy(nodes = listOf(patch.nodes.single().copy(config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")))))
        rejected(patch.copy(edges = listOf(patch.edges.single().copy(fromPort = "output"))))
    }

    @Test fun rejectsCapacityOverflowAndMalformedTypedPayload() {
        rejected(patch.copy(nodes = (1..33).map { patch.nodes.single().copy(id = "review.a$it") }))
        rejected(patch.copy(nodes = listOf(patch.nodes.single().copy(timeoutSeconds = 0))))
        assertTrue(runCatching { WorkflowExpansions.decode(Json.parseToJsonElement("""{"nodes":[],"edges":[],"replace":true}""").jsonObject) }.isFailure)
    }

    @Test fun requiresDirectSuccessfulStructuredSource() {
        assertTrue(WorkflowValidator.validate(definition).isEmpty())
        assertTrue(WorkflowValidator.validate(definition.copy(edges = emptyList())).isNotEmpty())
        assertTrue(WorkflowValidator.validate(definition.copy(nodes = definition.nodes.map { if (it.id == "plan") it.copy(config = emptyMap()) else it })).isNotEmpty())
    }

    @Test fun recoveryDetectsGraphTamperingMissingAndPrematureExpansion() {
        val proposal = NodeExecutionOutput(NodeRunStatus.SUCCESS, structuredResult = Json.parseToJsonElement(WorkflowExpansions.encode(patch)).jsonObject)
        val approved = NodeExecutionOutput(NodeRunStatus.SUCCESS)
        val journal = listOf(WorkflowJournalEntry(1, "plan", 1, WorkflowJournalKind.STARTED, 1L),
            WorkflowJournalEntry(2, "plan", 1, WorkflowJournalKind.SETTLED, 2L, proposal),
            WorkflowJournalEntry(3, "review", 1, WorkflowJournalKind.STARTED, 3L),
            WorkflowJournalEntry(4, "review", 1, WorkflowJournalKind.SETTLED, 4L, approved, patch))
        val expanded = WorkflowExpansions.append(definition, "review", patch)
        val state = WorkflowRuntimeState.initial("run", expanded).copy(status = WorkflowRunStatus.CANCELLED, journalVersion = 1,
            baseDefinition = definition, journal = journal, context = WorkflowRuntimeContext("run", "wf", "/workspace"))
        assertTrue(WorkflowRecovery.inspect(state).canResume)
        assertFalse(WorkflowRecovery.inspect(state.copy(definition = expanded.copy(name = "changed"))).canResume)
        assertFalse(WorkflowRecovery.inspect(state.copy(baseDefinition = null)).canResume)
        assertFalse(WorkflowRecovery.inspect(state.copy(journal = journal.dropLast(1))).canResume)
        assertFalse(WorkflowRecovery.inspect(state.copy(journal = listOf(WorkflowJournalEntry(1, "review.a", 1, WorkflowJournalKind.STARTED, 0L)) + journal)).canResume)
        val changedProposal = proposal.copy(structuredResult = Json.parseToJsonElement("""{"nodes":[],"edges":[]}""").jsonObject)
        assertFalse(WorkflowRecovery.inspect(state.copy(journal = journal.map { if (it.sequence == 2) it.copy(output = changedProposal) else it })).canResume)
    }
}
