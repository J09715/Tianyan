package top.tianyan.app.harness.question

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.harness.HarnessApiMapper
import top.tianyan.app.harness.HarnessTool
import top.tianyan.app.harness.ProviderClient
import top.tianyan.app.harness.validation.ToolSchemaValidator

/**
 * 工具接线一致性守卫。
 *
 * 拦的是一类**静默**的接线缺陷：[HarnessApiMapper.toolByName] 对未知工具名
 * 会兜底返回 `HarnessTool.BASE`。也就是说，如果新增了工具 schema 却漏配映射，
 * 模型调用时不会报「未知工具」，而是拿提问参数去执行一条 shell 命令——
 * 静默走错分支，比报错难查得多。
 *
 * 这里把三处必须同时成立的接线钉死：schema（模型看到的）↔ 映射（分派用的）↔ 校验（参数校验用的）。
 */
class ToolWiringConsistencyTest {

    @Test
    fun `ask_user_question maps to its own tool and never falls back to base`() {
        val mapped = HarnessApiMapper.toolByName("ask_user_question")
        assertEquals(
            "ask_user_question 必须映射到 ASK_USER；若映射到 BASE，模型提问会被当成 shell 命令执行",
            HarnessTool.ASK_USER,
            mapped,
        )
        assertEquals("ask_user_question", HarnessApiMapper.apiName(HarnessTool.ASK_USER))
    }

    @Test
    fun `tool name is case and whitespace tolerant`() {
        // 部分模型会输出大写或带空白，映射必须容错，否则同样落到 BASE 兜底
        listOf("ask_user_question", "ASK_USER_QUESTION", " Ask_User_Question ").forEach { raw ->
            assertEquals("$raw 应映射到 ASK_USER", HarnessTool.ASK_USER, HarnessApiMapper.toolByName(raw))
        }
    }

    @Test
    fun `every tool enum entry except mcp has a distinct api name that maps back`() {
        HarnessTool.entries
            .filter { it != HarnessTool.MCP } // mcp 是历史回放别名，模型不直接调用
            .forEach { tool ->
                val apiName = HarnessApiMapper.apiName(tool)
                assertEquals(
                    "工具 $tool 的 apiName 往返映射失败（会导致分派到错误分支）",
                    tool,
                    HarnessApiMapper.toolByName(apiName),
                )
            }
    }

    @Test
    fun `ask_user_question schema is registered and declares questions as required`() {
        val schema = ProviderClient.TOOLS.firstOrNull { it.function.name == "ask_user_question" }
        assertTrue("ask_user_question 必须出现在暴露给模型的工具表里", schema != null)
        val params = schema!!.function.parameters
        val required = params["required"].toString()
        assertTrue("questions 必须是必填参数，实际 required=$required", required.contains("questions"))
    }

    @Test
    fun `schema validator rejects a payload without the required questions field`() {
        // 校验器只实现 required / anyOf / type / enum，不实现 minItems。
        // 因此「空数组」由 ToolExecutor.parseQuestions 兜底（返回可读错误），
        // 这里钉的是必填字段确实生效——schema 缺失会让校验整体跳过。
        val schema = ProviderClient.TOOLS.first { it.function.name == "ask_user_question" }.function.parameters
        val problems = ToolSchemaValidator.validate(
            schema = schema,
            args = kotlinx.serialization.json.Json.parseToJsonElement("""{}""") as kotlinx.serialization.json.JsonObject,
        )
        assertTrue(
            "缺少 questions 应被 schema 拦下，实际 problems=$problems",
            problems.any { it.contains("questions") },
        )
    }

    @Test
    fun `schema validator accepts a well formed question payload`() {
        val schema = ProviderClient.TOOLS.first { it.function.name == "ask_user_question" }.function.parameters
        val problems = ToolSchemaValidator.validate(
            schema = schema,
            args = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"questions":[{"id":"q1","question":"选哪个？","options":[{"label":"A (Recommended)"},{"label":"B"}]}]}""",
            ) as kotlinx.serialization.json.JsonObject,
        )
        assertTrue("合法提问参数不应报错，实际 problems=$problems", problems.isEmpty())
    }
}
