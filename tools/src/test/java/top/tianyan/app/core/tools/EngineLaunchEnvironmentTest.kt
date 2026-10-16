package top.tianyan.app.core.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineLaunchEnvironmentTest {

    private val creds = EngineLaunchEnvironment.Credentials(
        apiKey = "sk-test-123",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-v4-pro",
    )

    @Test
    fun `empty api key produces empty environment`() {
        assertEquals(emptyMap<String, String>(), EngineLaunchEnvironment.build("claude-code", creds.copy(apiKey = "  ")))
        assertEquals(emptyMap<String, String>(), EngineLaunchEnvironment.build("codex", creds.copy(apiKey = "")))
    }

    @Test
    fun `claude code gets anthropic key auth token base url and model`() {
        val env = EngineLaunchEnvironment.build("claude-code", creds)

        assertEquals("sk-test-123", env["ANTHROPIC_API_KEY"])
        assertEquals("sk-test-123", env["ANTHROPIC_AUTH_TOKEN"])
        assertEquals("https://api.deepseek.com/anthropic", env["ANTHROPIC_BASE_URL"])
        assertEquals("deepseek-v4-pro", env["ANTHROPIC_MODEL"])
        assertEquals("deepseek-v4-pro", env["ANTHROPIC_SMALL_FAST_MODEL"])
    }

    @Test
    fun `deepseek harness gets deepseek key base url and model`() {
        val env = EngineLaunchEnvironment.build("deepseek-harness", creds)

        assertEquals("sk-test-123", env["DEEPSEEK_API_KEY"])
        assertEquals("https://api.deepseek.com/anthropic", env["DEEPSEEK_BASE_URL"])
        assertEquals("deepseek-v4-pro", env["DSH_MODEL"])
    }

    @Test
    fun `kimi code gets both anthropic and kimi surfaces`() {
        val env = EngineLaunchEnvironment.build("kimi-code", creds)

        assertEquals("sk-test-123", env["ANTHROPIC_API_KEY"])
        assertEquals("sk-test-123", env["KIMI_MODEL_API_KEY"])
        assertEquals("https://api.deepseek.com/anthropic", env["KIMI_MODEL_BASE_URL"])
        assertEquals("deepseek-v4-pro", env["KIMI_MODEL_NAME"])
    }

    @Test
    fun `kimi code declares anthropic provider type for third party relays`() {
        // 回归：缺 KIMI_MODEL_PROVIDER_TYPE 时引擎按缺省 "kimi" 回退官方
        // api.moonshot.ai，第三方 key 会被 Cloudflare 拦截
        // （真机报 "Authentication required: Attention Required! | Cloudflare"）。
        val env = EngineLaunchEnvironment.build("kimi-code", creds)
        assertEquals("anthropic", env["KIMI_MODEL_PROVIDER_TYPE"])
    }

    @Test
    fun `codex gets openai key and normalized base url`() {
        val env = EngineLaunchEnvironment.build("codex", creds)

        assertEquals("sk-test-123", env["OPENAI_API_KEY"])
        assertEquals("https://api.deepseek.com/v1", env["OPENAI_BASE_URL"])
    }

    @Test
    fun `opencode gets openai key and normalized base url`() {
        val env = EngineLaunchEnvironment.build("opencode", creds)

        assertEquals("sk-test-123", env["OPENAI_API_KEY"])
        assertEquals("https://api.deepseek.com/v1", env["OPENAI_BASE_URL"])
    }

    @Test
    fun `unknown engine id produces empty environment`() {
        assertEquals(emptyMap<String, String>(), EngineLaunchEnvironment.build("nope", creds))
    }

    @Test
    fun `unsafe base url is not injected`() {
        val unsafe = creds.copy(baseUrl = "javascript:alert(1)")
        val env = EngineLaunchEnvironment.build("claude-code", unsafe)

        assertFalse(env.containsKey("ANTHROPIC_BASE_URL"))
        // key 仍照常注入，端点不注入
        assertEquals("sk-test-123", env["ANTHROPIC_API_KEY"])
    }

    @Test
    fun `missing model omits model variables`() {
        val env = EngineLaunchEnvironment.build("claude-code", creds.copy(model = "  "))

        assertFalse(env.containsKey("ANTHROPIC_MODEL"))
        assertFalse(env.containsKey("ANTHROPIC_SMALL_FAST_MODEL"))
    }
}
