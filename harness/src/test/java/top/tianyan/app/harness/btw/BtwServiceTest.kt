package top.tianyan.app.harness.btw

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import top.tianyan.app.core.database.HarnessSessionEntity
import top.tianyan.app.core.database.HarnessUsageEntity
import top.tianyan.app.core.database.repository.BtwQueryRepository
import top.tianyan.app.core.model.BtwQuery
import top.tianyan.app.core.model.BtwStatus
import top.tianyan.app.harness.ApiMessage
import top.tianyan.app.harness.ApiToolCallSpec
import top.tianyan.app.harness.ChatResult
import top.tianyan.app.harness.ChatUsage
import top.tianyan.app.harness.ModelConfig

class BtwServiceTest {

    private val json = Json { ignoreUnknownKeys = true }
    private val saved = LinkedHashMap<String, BtwQuery>()
    private val usages = mutableListOf<HarnessUsageEntity>()

    private val repository = object : BtwQueryRepository {
        override fun observeForSession(sessionId: String, limit: Int): Flow<List<BtwQuery>> =
            MutableStateFlow(saved.values.filter { it.sessionId == sessionId })

        override suspend fun save(query: BtwQuery) {
            saved[query.id] = query
        }

        override suspend fun get(id: String): BtwQuery? = saved[id]
    }

    private fun session() = HarnessSessionEntity(
        id = "sess", title = "t", createdAt = 1, updatedAt = 1, modelId = null,
    )

    private fun model() = ModelConfig(
        name = "测试模型", provider = "p", model = "m", baseUrl = "http://localhost", apiKey = null,
    )

    private fun transcript(hasAssistant: Boolean) = buildList {
        add(ApiMessage(role = "system", content = "sys"))
        add(ApiMessage(role = "user", content = "hi"))
        if (hasAssistant) add(ApiMessage(role = "assistant", content = "正在编译"))
    }

    private class CallerScript(val results: MutableList<ChatResult>) {
        val requests = mutableListOf<List<ApiMessage>>()
        var cancellable = false
    }

    private fun service(transcript: List<ApiMessage>, caller: CallerScript) = BtwService(
        sessions = { id -> if (id == "sess") session() else null },
        modelResolver = { model() },
        transcriptSource = { _, _ -> transcript },
        modelCaller = { _, messages, _, text ->
            caller.requests += messages
            if (caller.cancellable) throw CancellationException("user")
            val result = caller.results.removeFirst()
            result.content?.let { text.append(it) }
            result
        },
        usageSink = { usages += it },
        repository = repository,
        json = json,
    )

    @Test
    fun `answers from snapshot without touching transcript`() = runTest {
        val caller = CallerScript(
            mutableListOf(
                ChatResult(
                    "做到第 3 步了", emptyList(),
                    usage = ChatUsage(inputTokens = 100, outputTokens = 20, cacheReadTokens = 60),
                ),
            ),
        )
        val record = service(transcript(hasAssistant = true), caller).ask("sess", "现在做到哪一步了？")
        assertEquals(BtwStatus.COMPLETED, record.status)
        assertEquals("做到第 3 步了", record.answer)
        assertEquals("m", record.modelId)
        // 记录带回答案 token 用量，并作为 btw 流水落账
        assertEquals(100L, record.inputTokens)
        assertEquals(1, usages.size)
        assertEquals("btw", usages.single().operationId)
        // 请求 = 原转录 + 旁路问题（不修改原转录）
        val sent = caller.requests.single()
        assertEquals(transcript(hasAssistant = true).size + 1, sent.size)
        assertTrue(sent.last().content.orEmpty().contains("现在做到哪一步了？"))
    }

    @Test
    fun `retries once when the model attempts a tool call`() = runTest {
        val toolCall = ChatResult(null, listOf(ApiToolCallSpec("call_1", "read_file", "{}")))
        val caller = CallerScript(mutableListOf(toolCall, ChatResult("只回答，不调用", emptyList())))
        var reset = 0
        val record = service(transcript(true), caller)
            .ask("sess", "卡住了吗？", onReset = { reset += 1 })
        assertEquals(BtwStatus.COMPLETED, record.status)
        assertEquals("只回答，不调用", record.answer)
        assertEquals(1, reset)
        assertEquals(2, caller.requests.size)
        // 第二次请求在旁路问题之后追加了重试说明
        assertTrue(caller.requests[1].last().content.orEmpty().contains("不要调用任何工具"))
    }

    @Test
    fun `fails when the model insists on tools`() = runTest {
        val toolCall = ChatResult(null, listOf(ApiToolCallSpec("call_1", "read_file", "{}")))
        val caller = CallerScript(mutableListOf(toolCall, toolCall))
        val record = service(transcript(true), caller).ask("sess", "进度？")
        assertEquals(BtwStatus.FAILED, record.status)
        assertTrue(record.error.contains("工具"))
    }

    @Test
    fun `rejects sessions without any progress`() = runTest {
        val caller = CallerScript(mutableListOf(ChatResult("x", emptyList())))
        val record = service(transcript(hasAssistant = false), caller).ask("sess", "在吗")
        assertEquals(BtwStatus.FAILED, record.status)
        assertTrue(record.error.contains("先正常对话一次"))
        assertTrue(caller.requests.isEmpty())
    }

    @Test
    fun `cancellation is persisted and rethrown`() = runTest {
        val caller = CallerScript(mutableListOf(ChatResult("x", emptyList()))).apply { cancellable = true }
        try {
            service(transcript(true), caller).ask("sess", "在吗")
            fail("应抛出取消异常")
        } catch (cancelled: CancellationException) {
            assertEquals(BtwStatus.CANCELLED, saved.values.single().status)
        }
    }

    @Test
    fun `missing session returns a failed record`() = runTest {
        val caller = CallerScript(mutableListOf(ChatResult("x", emptyList())))
        val record = service(transcript(true), caller).ask("missing", "在吗")
        assertEquals(BtwStatus.FAILED, record.status)
        assertEquals("会话不存在或已删除", record.error)
    }
}
