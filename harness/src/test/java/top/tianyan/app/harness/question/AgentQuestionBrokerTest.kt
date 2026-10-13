package top.tianyan.app.harness.question

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.AgentQuestion
import top.tianyan.app.core.model.AgentQuestionAnswer
import top.tianyan.app.core.model.AgentQuestionOption
import top.tianyan.app.core.model.AgentQuestionRecommendation
import top.tianyan.app.core.model.AgentQuestionResponse
import top.tianyan.app.core.model.isAnswered
import top.tianyan.app.core.model.normalized

/**
 * 提问中枢的行为契约。
 *
 * 重点覆盖三条真实事故路径：
 *  1. 挂起→作答→唤醒（正常路径）；
 *  2. 并发提问必须被拒绝（否则 UI 无法归属，卡片归属错乱）；
 *  3. 取消必须唤醒挂起协程（否则停止生成后 lane 永久悬挂）。
 * 这些用 runBlocking + 多线程调度，项目未引入 kotlinx-coroutines-test。
 */
class AgentQuestionBrokerTest {

    private val broker = AgentQuestionBroker(Json)

    private val question = AgentQuestion(
        id = "cleanup",
        question = "是否删除这些临时文件？",
        header = "确认",
        options = listOf(
            AgentQuestionOption("是，删掉它们 (Recommended)", "释放约 1.2 GB"),
            AgentQuestionOption("保留"),
        ),
    )

    /** 等待 broker 入队，避免测试靠 sleep 猜时序。 */
    private suspend fun awaitPending(callId: String) {
        withTimeout(5_000) {
            while (broker.pending.value?.callId != callId) yield()
        }
    }

    @Test
    fun `ask suspends until submit and returns the answer`() = runBlocking {
        val deferred = async(Dispatchers.Default) { broker.ask("s1", "call-1", listOf(question)) }
        awaitPending("call-1")

        val accepted = broker.submit(
            "call-1",
            AgentQuestionResponse(
                listOf(AgentQuestionAnswer("cleanup", selected = listOf("是，删掉它们 (Recommended)"))),
            ),
        )
        assertTrue("提交应唤醒挂起的提问", accepted)
        val response = withTimeout(5_000) { deferred.await() }
        assertEquals(1, response.answers.size)
        assertEquals(listOf("是，删掉它们 (Recommended)"), response.answers.single().selected)
        assertNull("作答后不应再留有挂起提问", broker.pending.value)
    }

    @Test
    fun `only one question card may be pending at a time`() = runBlocking {
        val first = async(Dispatchers.Default) { runCatching { broker.ask("s1", "call-1", listOf(question)) } }
        awaitPending("call-1")

        val second = withContext(Dispatchers.Default) { runCatching { broker.ask("s1", "call-2", listOf(question)) } }
        assertTrue("并发提问必须失败，否则 UI 无法归属", second.isFailure)
        assertTrue(
            "失败原因应说明已有待作答提问，实际=${second.exceptionOrNull()?.message}",
            second.exceptionOrNull()?.message?.contains("已有待作答的提问") == true,
        )

        broker.cancel("call-1")
        first.await()
        Unit
    }

    @Test
    fun `submitting an unknown callId returns false instead of throwing`() {
        assertFalse(broker.submit("ghost", AgentQuestionResponse()))
    }

    @Test
    fun `cancel settles pending questions as skipped so the coroutine cannot leak`() = runBlocking {
        val deferred = async(Dispatchers.Default) { broker.ask("s1", "call-9", listOf(question)) }
        awaitPending("call-9")

        assertTrue(broker.cancel("call-9"))
        val response = withTimeout(5_000) { deferred.await() }
        assertEquals(1, response.answers.size)
        assertTrue("取消应视为用户跳过", response.answers.single().skipped)
        assertNull(broker.pending.value)
    }

    @Test
    fun `pending exposes the questions the UI must render`() = runBlocking {
        val job = launch(Dispatchers.Default) { runCatching { broker.ask("s1", "call-7", listOf(question)) } }
        awaitPending("call-7")
        val pending = broker.pending.value
        assertEquals("s1", pending?.sessionId)
        assertEquals(1, pending?.questions?.size)
        assertEquals("cleanup", pending?.questions?.first()?.id)
        broker.cancel("call-7")
        job.join()
    }

    @Test
    fun `normalize marks unanswered questions skipped and keeps answered ones`() {
        val second = AgentQuestion(id = "scope", question = "作用域？")
        val response = broker.normalize(
            listOf(question, second),
            listOf(AgentQuestionAnswer("cleanup", selected = listOf("保留"))),
        )
        assertEquals(2, response.answers.size)
        assertEquals(listOf("保留"), response.answers[0].selected)
        assertFalse(response.answers[0].skipped)
        assertTrue("未作答的题应标为跳过", response.answers[1].skipped)
    }

    @Test
    fun `render emits compact json keyed by question id`() {
        val rendered = broker.render(
            AgentQuestionResponse(listOf(AgentQuestionAnswer("cleanup", selected = listOf("保留")))),
        )
        assertEquals("""{"answers":[{"id":"cleanup","selected":["保留"]}]}""", rendered)
    }
}

class AgentQuestionModelTest {

    @Test
    fun `recommended suffix is detected and stripped for display`() {
        val parsed = AgentQuestionRecommendation.parse("是，删掉它们 (Recommended)")
        assertTrue(parsed.recommended)
        assertEquals("是，删掉它们", parsed.displayLabel)

        val chinese = AgentQuestionRecommendation.parse("使用默认配置（推荐）")
        assertTrue(chinese.recommended)
        assertEquals("使用默认配置", chinese.displayLabel)
    }

    @Test
    fun `plain labels are not marked recommended`() {
        listOf("保留", "Use defaults", "删除 (delete)").forEach { label ->
            val parsed = AgentQuestionRecommendation.parse(label)
            assertFalse("$label 不应被识别为推荐", parsed.recommended)
            assertEquals(label, parsed.displayLabel)
        }
    }

    @Test
    fun `only the first option can be the leading recommendation`() {
        assertTrue(AgentQuestionRecommendation.isLeadingRecommendation(0, "A (Recommended)"))
        assertFalse("推荐标记只对第一位生效", AgentQuestionRecommendation.isLeadingRecommendation(1, "B (Recommended)"))
        assertFalse(AgentQuestionRecommendation.isLeadingRecommendation(0, "A"))
    }

    @Test
    fun `single select custom text is exclusive of selected options`() {
        val answer = AgentQuestionAnswer("q", selected = listOf("A", "B"), custom = "其它")
        val result = answer.normalized(multiSelect = false)
        assertTrue("单选填了自由文本应清空选项", result.selected.isEmpty())
        assertEquals("其它", result.custom)
    }

    @Test
    fun `multi select keeps both options and custom text`() {
        val answer = AgentQuestionAnswer("q", selected = listOf("A"), custom = "补充说明")
        val result = answer.normalized(multiSelect = true)
        assertEquals(listOf("A"), result.selected)
        assertEquals("补充说明", result.custom)
    }

    @Test
    fun `blank custom text is dropped and does not count as answered`() {
        val answer = AgentQuestionAnswer("q", custom = "   ")
        assertFalse(answer.isAnswered())
        val result = answer.normalized(multiSelect = false)
        assertEquals("", result.custom)
        assertTrue(result.selected.isEmpty())
    }

    @Test
    fun `selecting an option counts as answered even without custom text`() {
        assertTrue(AgentQuestionAnswer("q", selected = listOf("A")).isAnswered())
    }
}
