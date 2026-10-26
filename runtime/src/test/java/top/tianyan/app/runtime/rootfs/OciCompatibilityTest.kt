package top.tianyan.app.runtime.rootfs

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OciCompatibilityTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun dockerReferencesAndPins() {
        val pin = "sha256:" + "a".repeat(64)
        assertEquals("library/ubuntu", OciImageReference.parse("ubuntu").repo)
        assertEquals("latest", OciImageReference.parse("ubuntu").manifestRef)
        assertEquals("v1.0_RC", OciImageReference.parse("team/image:v1.0_RC").tag)
        assertEquals("library/ubuntu", OciImageReference.parse("docker.io/ubuntu:24.04").repo)
        assertEquals(pin, OciImageReference.parse("index.docker.io/ubuntu:24.04@$pin").manifestRef)
        assertEquals(pin, OciImageReference.parse("ubuntu@$pin").digest)
        listOf("", "Ubuntu", "ubuntu:", "../ubuntu", "ubuntu#x", "ubuntu&scope=x", "ubuntu\n",
            "a///b", "a___b", "ubuntu@sha256:abc", "ubuntu@sha256:" + "A".repeat(64),
            "ubuntu@sha512:" + "a".repeat(64), "a".repeat(64), "ubuntu:" + "x".repeat(129),
            "example.org/team/image:tag", "x".repeat(256)).forEach { value ->
            assertThrows("Rejected reference: $value", IllegalArgumentException::class.java) {
                OciImageReference.parse(value)
            }
        }
    }

    @Test fun pinnedManifestIsVerifiedAndReusedWithoutAuthenticationOrNetwork() {
        MockWebServer().use { server ->
            val raw = """{"schemaVersion":2,"layers":[]}"""
            val digest = sha256(raw)
            server.enqueue(MockResponse().setBody(raw).setHeader("Docker-Content-Digest", digest))
            val url = server.url("/v2/library/ubuntu/manifests/$digest").toString()
            val cache = temporary.newFolder()
            val store = OciManifestStore()
            val first = store.get(url, { "token" }, "application/json", OkHttpClient(), digest, cache)
            assertEquals(digest, first.digest)
            assertEquals("Bearer token", server.takeRequest().getHeader("Authorization"))
            server.shutdown()
            val offline = store.get(url, { error("Offline pull requested authentication") }, "application/json",
                OkHttpClient(), digest, cache)
            assertEquals(first, offline)
        }
    }

    @Test fun corruptedManifestCacheIsRefetchedAndMismatchingPinIsRejected() {
        MockWebServer().use { server ->
            val raw = """{"schemaVersion":2,"layers":[]}"""
            val digest = sha256(raw)
            val cache = temporary.newFolder()
            File(cache, "sha256-${digest.substringAfter(':')}.manifest.json").writeText("corrupt")
            server.enqueue(MockResponse().setBody(raw))
            val store = OciManifestStore()
            assertEquals(digest, store.get(server.url("/manifest").toString(), { "" }, "application/json",
                OkHttpClient(), digest, cache).digest)
            server.enqueue(MockResponse().setBody(raw))
            assertThrows(IllegalStateException::class.java) {
                store.get(server.url("/manifest").toString(), { "" }, "application/json", OkHttpClient(),
                    "sha256:" + "b".repeat(64), cache)
            }
        }
    }

    @Test fun manifestHeaderAndArm64ChildDigestAreVerified() {
        MockWebServer().use { server ->
            val child = """{"schemaVersion":2,"layers":[]}"""
            val childDigest = sha256(child)
            val index = """{"manifests":[{"digest":"$childDigest","platform":{"os":"linux","architecture":"arm64"}}]}"""
            val store = OciManifestStore()
            val cache = temporary.newFolder()
            server.enqueue(MockResponse().setBody(index))
            assertEquals(sha256(index), store.get(server.url("/index").toString(), { "" }, "application/json",
                OkHttpClient(), sha256(index), cache).digest)
            server.enqueue(MockResponse().setBody(child))
            assertEquals(childDigest, store.get(server.url("/child").toString(), { "" }, "application/json",
                OkHttpClient(), childDigest, cache).digest)
            server.enqueue(MockResponse().setBody(child).setHeader("Docker-Content-Digest", "sha256:" + "b".repeat(64)))
            assertThrows(IllegalStateException::class.java) {
                store.get(server.url("/tag").toString(), { "" }, "application/json", OkHttpClient())
            }
        }
    }

    @Test fun downloadsHaveInactivityTimeoutWithoutTotalTransferDeadline() {
        val client = OkHttpClient.Builder().callTimeout(1, TimeUnit.SECONDS).build().forRootfsDownloads()
        assertEquals(120_000, client.readTimeoutMillis)
        assertEquals(120_000, client.writeTimeoutMillis)
        assertEquals(0, client.callTimeoutMillis)
    }

    @Test fun httpsToHttpRedirectIsRejectedBeforeFollowUpRequest() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://example.org/layer"))
            val client = OkHttpClient().forRootfsDownloads().newBuilder()
                .addNetworkInterceptor { chain ->
                    // Emulate a TLS response without a platform-specific test certificate.
                    chain.proceed(chain.request()).newBuilder().request(chain.request().newBuilder()
                        .url("https://registry.example.org/layer").build()).build()
                }.build()
            assertThrows(java.io.IOException::class.java) {
                client.newCall(Request.Builder().url(server.url("/layer")).build()).execute().close()
            }
            assertEquals(1, server.requestCount)
        }
    }

    private fun sha256(value: String) = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
