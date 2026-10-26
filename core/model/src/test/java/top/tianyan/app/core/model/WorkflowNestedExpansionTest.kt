package top.tianyan.app.core.model

import java.io.File
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowNestedExpansionTest {
    private val schema = """{"type":"object","additionalProperties":true}"""
    private val base = WorkflowDefinition("wf", "nested", nodes = listOf(
        WorkflowNode("plan", WorkflowNodeType.AGENT_INFERENCE, "plan", config = mapOf("resultSchema" to schema)),
        WorkflowNode("review", WorkflowNodeType.HUMAN_APPROVAL, "review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")),
    ), edges = listOf(WorkflowEdge("base", "plan", "success", "review")))

    private fun stage(anchor: String) = WorkflowExpansion(nodes = listOf(
        WorkflowNode("$anchor.plan", WorkflowNodeType.AGENT_INFERENCE, "next plan", config = mapOf("resultSchema" to schema)),
        WorkflowNode("$anchor.next", WorkflowNodeType.HUMAN_APPROVAL, "next review", config = mapOf(WorkflowExpansions.SOURCE_KEY to "$anchor.plan")),
    ), edges = listOf(WorkflowEdge("$anchor.entry", anchor, "success", "$anchor.plan"),
        WorkflowEdge("$anchor.review", "$anchor.plan", "success", "$anchor.next")))

    @Test fun stagedExamplePreservesFutureReferencesAndValidatesBothProposals() {
        val example = Json.decodeFromString<WorkflowDefinition>(File("../../docs/examples/staged-dynamic-workflow.json").readText())
        assertTrue(WorkflowValidator.validate(example).isEmpty())
        val firstSchema = WorkflowResultSchema.parse(example.nodes.first { it.id == "plan" }.config.getValue("resultSchema"))
        val first = WorkflowExpansions.decode(WorkflowResultSchema.validateResult(example.defaultVariables.getValue("FIRST_PROPOSAL"), firstSchema))
        val graph = WorkflowExpansions.append(example, "review", first)
        val planner = graph.nodes.first { it.id == "review.plan" }
        assertTrue(planner.config.getValue("prompt").contains("\${previous.output}"))
        val nextSchema = WorkflowResultSchema.parse(planner.config.getValue("resultSchema"))
        val next = WorkflowExpansions.decode(WorkflowResultSchema.validateResult("""{"nodes":[{"id":"review.next.finish","type":"BASH_COMMAND","title":"打印结果","config":{"command":"printf 'done\\n'"}}],"edges":[{"id":"finish_edge","fromNodeId":"review.next","fromPort":"success","toNodeId":"review.next.finish"}]}""", nextSchema))
        assertEquals(6, WorkflowExpansions.append(graph, "review.next", next).nodes.size)
    }

    @Test fun permitsFourApprovalLevelsThenOnlyLeafSteps() {
        var graph = base
        var anchor = "review"
        repeat(3) { level ->
            assertEquals(level + 1, WorkflowExpansions.depth(graph, anchor))
            graph = WorkflowExpansions.append(graph, anchor, stage(anchor))
            anchor += ".next"
        }
        assertEquals(4, WorkflowExpansions.depth(graph, anchor))
        assertTrue(runCatching { WorkflowExpansions.append(graph, anchor, stage(anchor)) }.isFailure)
        val leaf = WorkflowExpansion(listOf(WorkflowNode("$anchor.done", WorkflowNodeType.TERMINAL_OUTPUT, "done")),
            listOf(WorkflowEdge("leaf", anchor, "success", "$anchor.done")))
        assertTrue(WorkflowValidator.validate(WorkflowExpansions.append(graph, anchor, leaf)).isEmpty())
    }

    @Test fun nestedApprovalCannotBorrowExternalSourceOrDropItsSuccessDependency() {
        val proposal = stage("review")
        val external = proposal.copy(nodes = proposal.nodes.map { if (it.type == WorkflowNodeType.HUMAN_APPROVAL)
            it.copy(config = mapOf(WorkflowExpansions.SOURCE_KEY to "plan")) else it })
        assertTrue(runCatching { WorkflowExpansions.append(base, "review", external) }.isFailure)
        val missing = proposal.copy(edges = proposal.edges.map { if (it.toNodeId == "review.next") it.copy(fromNodeId = "review") else it })
        assertTrue(runCatching { WorkflowExpansions.append(base, "review", missing) }.isFailure)
        val nonAgent = proposal.copy(nodes = proposal.nodes.map { if (it.id == "review.plan") it.copy(type = WorkflowNodeType.DELAY, config = emptyMap()) else it })
        assertTrue(runCatching { WorkflowExpansions.append(base, "review", nonAgent) }.isFailure)
    }

    @Test fun independentBranchesDoNotConsumeEachOthersDepthBudget() {
        val first = stage("review")
        val expanded = WorkflowExpansions.append(base, "review", first)
        val sibling = first.copy(nodes = first.nodes.map { it.copy(id = it.id.replace("review.", "review.side_"),
            config = it.config.mapValues { (_, value) -> value.replace("review.plan", "review.side_plan") }) },
            edges = first.edges.map { it.copy(id = "side_${it.id}", fromNodeId = if (it.fromNodeId == "review") "review" else "review.side_plan",
                toNodeId = it.toNodeId.replace("review.", "review.side_")) })
        val graph = WorkflowExpansions.append(base, "review", WorkflowExpansion(first.nodes + sibling.nodes, first.edges + sibling.edges))
        assertEquals(2, WorkflowExpansions.depth(graph, "review.next"))
        assertEquals(2, WorkflowExpansions.depth(graph, "review.side_next"))
        assertEquals(2, WorkflowExpansions.depth(expanded, "review.next"))
    }
}
