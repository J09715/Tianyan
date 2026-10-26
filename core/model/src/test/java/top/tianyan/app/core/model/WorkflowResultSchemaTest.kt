package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowResultSchemaTest {
    private val schema = WorkflowResultSchema.parse("""{
        "type":"object", "required":["approved","severity","items"], "additionalProperties":false,
        "properties":{
            "approved":{"type":"boolean"},
            "severity":{"type":"string","enum":["low","high"]},
            "items":{"type":"array","items":{"type":"object","required":["count"],
                "properties":{"count":{"type":"integer"}}}}
        }
    }""")
    private val valid = """{"approved":true,"severity":"high","items":[{"count":2}]}"""

    @Test fun validatesTypesRequiredEnumAndNestedArrays() {
        val result = WorkflowResultSchema.validateResult(valid, schema)
        assertNotNull(result)
        listOf(
            valid.replace("true", "\"true\""),
            valid.replace("high", "unknown"),
            valid.replace("2", "2.5"),
            valid.replace("2", "9007199254740992.5"),
            """{"approved":true,"severity":"low"}""",
            valid.dropLast(1) + ",\"extra\":1}",
            "```json\n$valid\n```",
        ).forEach { assertTrue(it, runCatching { WorkflowResultSchema.validateResult(it, schema) }.isFailure) }
    }

    @Test fun rejectsDeepUnspecifiedFieldsBeforeParsing() {
        val permissive = WorkflowResultSchema.parse("""{"type":"object"}""")
        val deep = "{\"extra\":" + "[".repeat(50) + "0" + "]".repeat(50) + "}"
        assertTrue(runCatching { WorkflowResultSchema.validateResult(deep, permissive) }.isFailure)
    }

    @Test fun rejectsUnsupportedConstraintsBeforeRunning() {
        val workflow = WorkflowDefinition("wf", "测试", nodes = listOf(
            WorkflowNode("agent", WorkflowNodeType.AGENT_INFERENCE, "分析", config = mapOf(
                "resultSchema" to """{"type":"object","properties":{"x":{"type":"number","minimum":0}}}""",
            )),
        ))
        assertTrue(WorkflowValidator.validate(workflow).any { it.field.endsWith("resultSchema") })
        assertTrue(runCatching { WorkflowResultSchema.parse("""{"type":"array","items":{"type":"string"}}""") }.isFailure)
    }

    @Test fun resultReferencesAndConditionsUseValidatedValues() {
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, structuredResult = WorkflowResultSchema.validateResult(valid, schema))
        val context = WorkflowRuntimeContext("run", "wf", "/workspace", nodeOutputs = mapOf("audit" to output), upstreamNodeIds = listOf("audit"))
        assertEquals("true", context.resultReference("audit.result.approved"))
        assertEquals("2", context.resultReference("previous.result.items.0.count"))
        assertEquals("", context.resultReference("audit.result.items.-1.count"))
        assertTrue(WorkflowConditionEvaluator.evaluate("\${audit.result.approved} == true && \${audit.result.items.0.count} >= 2", context).matched)
        val ambiguous = context.copy(nodeOutputs = context.nodeOutputs + ("other" to output), upstreamNodeIds = listOf("audit", "other"))
        assertEquals("", ambiguous.resultReference("previous.result.approved"))
        assertEquals("high", context.resultReference("previous.result.severity", output))
    }

    @Test fun resultsAndArtifactsSurviveSnapshotRoundTripAndOldOutputStillDecodes() {
        val output = NodeExecutionOutput(NodeRunStatus.SUCCESS, artifacts = listOf("/workspace/report.md"),
            structuredResult = WorkflowResultSchema.validateResult(valid, schema))
        assertEquals(output, Json.decodeFromString<NodeExecutionOutput>(Json.encodeToString(NodeExecutionOutput.serializer(), output)))
        assertNull(Json.decodeFromString<NodeExecutionOutput>("""{"status":"SUCCESS","textOutput":"legacy"}""").structuredResult)
    }
}
