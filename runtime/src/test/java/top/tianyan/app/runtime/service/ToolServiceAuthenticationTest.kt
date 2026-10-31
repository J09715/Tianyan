package top.tianyan.app.runtime.service

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

class ToolServiceAuthenticationTest {
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    private fun digest(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun sign(key: ByteArray, value: String) = hex(Mac.getInstance("HmacSHA256").run {
        init(SecretKeySpec(key, "HmacSHA256")); doFinal(value.toByteArray())
    })

    @Test fun signsExactBodiesAndRejectsUnsignedAlteredAndPreviousResponses() = runBlocking {
        val auth = ToolServiceAuthentication()
        val text = auth.bootstrap().decodeToString()
        val key = Regex("[a-f0-9]{64}").find(text)!!.value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        assertFalse(auth.toString().contains(hex(key)))
        val nonces = mutableSetOf<String>()
        MockWebServer().use { server ->
            var count = 0
            var previous = ""
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val pieces = request.getHeader(ToolServiceAuthentication.HEADER)!!.split(':')
                    assertTrue(nonces.add(pieces[1]))
                    val body = request.body.readByteArray()
                    val expected = sign(key, "request\n${pieces[0]}\n${pieces[1]}\n${request.method}\n${request.path}\n${digest(body)}")
                    assertEquals(expected, pieces[2])
                    assertFalse(request.headers.toString().contains(hex(key)))
                    val bytes = "{\"accepted\":true}".toByteArray()
                    val signature = sign(key, "response\n${pieces[1]}\n200\n${digest(bytes)}")
                    return when (++count) {
                        1 -> MockResponse().setBody(bytes.decodeToString()).setHeader(ToolServiceAuthentication.RESPONSE_HEADER, signature.also { previous = it })
                        2 -> MockResponse().setBody(bytes.decodeToString())
                        3 -> MockResponse().setBody("modified").setHeader(ToolServiceAuthentication.RESPONSE_HEADER, signature)
                        else -> MockResponse().setBody(bytes.decodeToString()).setHeader(ToolServiceAuthentication.RESPONSE_HEADER, previous)
                    }
                }
            }
            server.start()
            val client = ManagedWebServiceClient(server.port, "/", available = { true }, authentication = auth)
            try {
                assertEquals("{\"accepted\":true}", client.write("api/auth/token", "{\"token\":\"FAKE\"}"))
                assertNull(client.read("api/status"))
                assertNull(client.read("api/status"))
                assertNull(client.read("api/status"))
                assertEquals(4, server.requestCount)
            } finally { client.close() }
        }
    }

    @Test fun aDifferentProcessCannotAuthenticateTheOriginalServicesResponses() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("fake page").setHeader(ToolServiceAuthentication.RESPONSE_HEADER, "0".repeat(64)))
            server.start()
            val assets = IndexedManagedWebAssets(server.port, "/", available = { true }, authentication = ToolServiceAuthentication())
            try { assertNull(assets.resolve("index.html")) } finally { assets.close() }
        }
    }
}
