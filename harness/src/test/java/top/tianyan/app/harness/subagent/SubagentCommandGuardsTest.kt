package top.tianyan.app.harness.subagent

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ToolCall

/**
 * 子智能体命令超时护栏：确保 base 的 10 分钟默认超时不会在 15 分钟子任务预算里
 * 静默吃掉大半时间，同时不覆盖调用方显式声明的意图。
 */
class SubagentCommandGuardsTest {

    private fun toolCall(tool: HarnessTool, args: JsonObject) =
        ToolCall(id = "call-1", createdAt = 0L, tool = tool, args = args)

    private fun ToolCall.timeoutSeconds(): Long? =
        args["timeout_seconds"]?.jsonPrimitive?.longOrNull

    @Test
    fun `base call without explicit timeout receives the subagent cap`() {
        val guarded = toolCall(
            HarnessTool.BASE,
            buildJsonObject { put("command", JsonPrimitive("find / -maxdepth 7 -name 'rhino*.jar'")) },
        ).withSubagentCommandTimeoutCap()

        assertEquals(SUBAGENT_COMMAND_TIMEOUT_CAP_SECONDS, guarded.timeoutSeconds())
        assertEquals(
            "find / -maxdepth 7 -name 'rhino*.jar'",
            guarded.args["command"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `explicit timeout is respected so genuine long commands keep their budget`() {
        val guarded = toolCall(
            HarnessTool.BASE,
            buildJsonObject {
                put("command", JsonPrimitive("./gradlew assembleRelease"))
                put("timeout_seconds", JsonPrimitive(900))
            },
        ).withSubagentCommandTimeoutCap()

        assertEquals(900L, guarded.timeoutSeconds())
    }

    @Test
    fun `non base tools are untouched`() {
        val original = toolCall(HarnessTool.READ, buildJsonObject { put("path", JsonPrimitive("README.md")) })
        val guarded = original.withSubagentCommandTimeoutCap()

        assertSame(original, guarded)
    }

    @Test
    fun `string encoded timeout counts as explicit`() {
        val guarded = toolCall(
            HarnessTool.BASE,
            buildJsonObject {
                put("command", JsonPrimitive("sleep 600"))
                put("timeout_seconds", JsonPrimitive("600"))
            },
        ).withSubagentCommandTimeoutCap()

        assertEquals(600L, guarded.timeoutSeconds())
    }
}
