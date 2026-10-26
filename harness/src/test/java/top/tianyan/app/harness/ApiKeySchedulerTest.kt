package top.tianyan.app.harness

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ApiKeySchedulerTest {
    @Test
    fun `requests rotate across keys`() {
        val scheduler = ApiKeyScheduler { 1_000L }

        assertEquals("key-a", scheduler.select(listOf("key-a", "key-b"), rpmLimit = 0).key)
        assertEquals("key-b", scheduler.select(listOf("key-a", "key-b"), rpmLimit = 0).key)
        assertEquals("key-a", scheduler.select(listOf("key-a", "key-b"), rpmLimit = 0).key)
    }

    @Test
    fun `rpm limit rotates then reports time until next window`() {
        var now = 1_000L
        val scheduler = ApiKeyScheduler { now }
        val keys = listOf("key-a", "key-b")

        assertEquals("key-a", scheduler.select(keys, rpmLimit = 1).key)
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 1).key)
        val blocked = scheduler.select(keys, rpmLimit = 1)
        assertNull(blocked.key)
        assertEquals(60_000L, blocked.waitMillis)

        now += 60_000L
        assertEquals("key-a", scheduler.select(keys, rpmLimit = 1).key)
    }

    @Test
    fun `upstream rate limit cools current key and selects another`() {
        var now = 1_000L
        val scheduler = ApiKeyScheduler { now }
        val keys = listOf("key-a", "key-b")

        assertEquals("key-a", scheduler.select(keys, rpmLimit = 0).key)
        scheduler.markRateLimited("key-a", retryAfterSeconds = 30)
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 0).key)

        now += 30_000L
        assertEquals("key-a", scheduler.select(keys, rpmLimit = 0).key)
    }

    @Test
    fun `excluded keys are not retried in the same request`() {
        val scheduler = ApiKeyScheduler { 1_000L }

        val selection = scheduler.select(
            keys = listOf("key-a", "key-b"),
            rpmLimit = 0,
            excluded = setOf("key-a"),
        )

        assertEquals("key-b", selection.key)
    }

    @Test
    fun `429 transparently retries with the next key`() = runBlocking {
        val attempted = mutableListOf<String>()
        val model = ModelConfig(
            name = "test",
            provider = "test",
            model = "test-model",
            baseUrl = "https://example.com/v1",
            apiKey = "key-a",
            apiKeys = listOf("key-a", "key-b"),
        )

        val result = executeWithRotatedApiKey(model, ApiKeyScheduler { 1_000L }) { selected ->
            attempted += requireNotNull(selected.apiKey)
            if (selected.apiKey == "key-a") throw LlmRateLimitException("limited", retryAfterSeconds = 60)
            "ok"
        }

        assertEquals("ok", result)
        assertEquals(listOf("key-a", "key-b"), attempted)
    }

    @Test
    fun `requests stick to the same key within one session`() {
        val scheduler = ApiKeyScheduler { 1_000L }
        val keys = listOf("key-a", "key-b")

        assertEquals("key-a", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)
        // 同一会话复用同一 Key，不随轮询游标切换
        assertEquals("key-a", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)
        // 其他会话仍走轮询，落到下一个 Key
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 0, sessionKey = "s2").key)
    }

    @Test
    fun `sticky key falls back to rotation and rebinds after cooling`() {
        var now = 1_000L
        val scheduler = ApiKeyScheduler { now }
        val keys = listOf("key-a", "key-b")

        assertEquals("key-a", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)
        scheduler.markRateLimited("key-a", retryAfterSeconds = 30)
        // 粘性 Key 被冷却：本次会话改选备用 Key 并重新绑定
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)

        now += 30_000L
        // 原 Key 冷却结束，但会话已稳定绑定到备用 Key
        assertEquals("key-b", scheduler.select(keys, rpmLimit = 0, sessionKey = "s1").key)
    }

    @Test
    fun `the only available key is not cooled after a 429`() = runBlocking {
        val scheduler = ApiKeyScheduler { 1_000L }
        val model = ModelConfig(
            name = "test",
            provider = "test",
            model = "test-model",
            baseUrl = "https://example.com/v1",
            apiKey = "only-key",
            apiKeys = listOf("only-key"),
        )

        var thrown = false
        try {
            executeWithRotatedApiKey(model, scheduler) {
                throw LlmRateLimitException("limited", retryAfterSeconds = 60)
            }
        } catch (_: LlmRateLimitException) {
            thrown = true
        }

        assertTrue(thrown)
        // 唯一 Key 未进入冷却：后续请求仍能选中它，交由上层退避重试
        assertEquals("only-key", scheduler.select(listOf("only-key"), rpmLimit = 0).key)
    }
}
