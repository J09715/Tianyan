package top.tianyan.app.harness

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import top.tianyan.app.core.datastore.PhoneAgentEndpoint
import top.tianyan.app.core.model.workflow.PhoneWorkflowOperation
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull

class PhoneAgentRequestTest {
    @Test
    fun `phone HTTP request uses a bounded output budget and no chat tools`() = runBlocking {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"role":"assistant","content":"finish(message=\"done\")"}}]}"""))
            val model = phoneAgentModel(PhoneAgentEndpoint(server.url("/v4").toString(), "autoglm-phone", "test-key"))
            ChatApi(OkHttpClient(), Json { ignoreUnknownKeys = true }).chat(model, listOf(ApiMessage("user", "test")))
            val request = server.takeRequest()
            val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
            assertEquals(2048, body.getValue("max_tokens").jsonPrimitive.content.toInt())
            assertEquals("autoglm-phone", body.getValue("model").jsonPrimitive.content)
            assertFalse("tools" in body)
        } finally { server.shutdown() }
    }

    @Test
    fun `logs do not expose input or model commentary`() {
        assertEquals("Type textLength=6", phoneActionLog(PhoneAgentAction.Type("secret")))
        assertEquals("Finish", phoneActionLog(PhoneAgentAction.Finish("secret")))
    }

    @Test
    fun `recording requires a replayable app entry before coordinate actions`() {
        val click = PhoneWorkflowOperation("virtual_screen_click", mapOf("x" to "100", "y" to "500"))
        val launch = PhoneWorkflowOperation("virtual_screen_launch", mapOf("package" to "com.example.app"))
        assertNotNull(phoneRecordingIssue(emptyList()))
        assertNotNull(phoneRecordingIssue(listOf(click)))
        assertNotNull(phoneRecordingIssue(listOf(click, launch)))
        assertNull(phoneRecordingIssue(listOf(launch, click)))
    }
}
