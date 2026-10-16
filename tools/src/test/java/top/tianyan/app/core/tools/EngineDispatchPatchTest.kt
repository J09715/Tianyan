package top.tianyan.app.core.tools

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineDispatchPatchTest {

    private val creds = EngineLaunchEnvironment.Credentials(
        apiKey = "sk-test",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-v4-pro",
    )

    @Test
    fun `deepseek harness produces a dispatch patch`() {
        val patch = EngineLaunchEnvironment.dispatchPatchFor("deepseek-harness", creds)
        assertNotNull(patch)
        assertTrue(patch!!.contains("id: llm-pi-ai"))
        assertTrue(patch.contains("tianyan-dispatch"))
        assertTrue(patch.contains("api: anthropic-messages"))
        assertTrue(patch.contains("baseURL: https://api.deepseek.com/anthropic"))
        assertTrue(patch.contains("deepseek-v4-pro"))
        assertTrue(patch.contains("id: acp"))
        assertTrue(patch.contains("provider: tianyan-dispatch"))
    }

    @Test
    fun `openai compatible endpoint uses openai-completions api`() {
        val patch = EngineLaunchEnvironment.dispatchPatchFor(
            "deepseek-harness",
            creds.copy(baseUrl = "https://my-proxy.example.com/v1"),
        )
        assertNotNull(patch)
        assertTrue(patch!!.contains("api: openai-completions"))
        assertTrue(patch.contains("baseURL: https://my-proxy.example.com/v1"))
    }

    @Test
    fun `non deepseek engine produces no patch`() {
        assertNull(EngineLaunchEnvironment.dispatchPatchFor("claude-code", creds))
        assertNull(EngineLaunchEnvironment.dispatchPatchFor("codex", creds))
    }

    @Test
    fun `missing credentials produce no patch`() {
        assertNull(EngineLaunchEnvironment.dispatchPatchFor("deepseek-harness", creds.copy(apiKey = " ")))
        assertNull(EngineLaunchEnvironment.dispatchPatchFor("deepseek-harness", creds.copy(baseUrl = " ")))
        assertNull(EngineLaunchEnvironment.dispatchPatchFor("deepseek-harness", creds.copy(model = " ")))
    }
}
