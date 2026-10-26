package top.tianyan.app.core.model

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.workflow.*

class WorkflowParallelismValidationTest {
    private val definition = WorkflowDefinition("wf", "test", nodes = listOf(WorkflowNode("start", WorkflowNodeType.TRIGGER, "start")))

    @Test fun oldDefinitionsDefaultToFourAndExplicitLimitSurvivesSerialization() {
        val old = Json.decodeFromString<WorkflowDefinition>("""{"id":"wf","name":"old","nodes":[{"id":"start","type":"TRIGGER","title":"start"}]}""")
        assertEquals(4, old.maxParallelNodes)
        val explicit = definition.copy(maxParallelNodes = 2)
        assertEquals(explicit, Json.decodeFromString<WorkflowDefinition>(Json.encodeToString(WorkflowDefinition.serializer(), explicit)))
    }

    @Test fun rejectsZeroNegativeAndExcessiveParallelism() {
        listOf(-1, 0, 9, Int.MAX_VALUE).forEach { limit ->
            assertTrue(WorkflowValidator.validate(definition.copy(maxParallelNodes = limit)).any { it.field == "maxParallelNodes" })
        }
        listOf(1, 4, 8).forEach { assertTrue(WorkflowValidator.validate(definition.copy(maxParallelNodes = it)).isEmpty()) }
    }
}
