package top.tianyan.app.runtime.rootfs

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OciLayerStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun compressedLayerDigestMatchesWireBytesAndCacheWorksOffline() = runBlocking {
        MockWebServer().use { server ->
            val bytes = ByteArrayOutputStream().apply {
                GZIPOutputStream(this).use { it.write("rootfs tar contents".toByteArray()) }
            }.toByteArray()
            val digest = digest(bytes)
            val cache = temporary.newFolder()
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val url = server.url("/layer").toString()
            val store = OciLayerStore(OkHttpClient().forRootfsDownloads())
            val file = store.download(url, digest, { "" }, cache) {}
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(1, server.requestCount)
            server.shutdown()
            assertEquals(file, store.download(url, digest, { error("Offline authentication") }, cache) {})
        }
    }

    @Test fun rangeResumeHashesPartialAndRemainingBytesTogether() = runBlocking {
        MockWebServer().use { server ->
            val bytes = "complete-layer".toByteArray()
            val digest = digest(bytes)
            val cache = temporary.newFolder()
            File(cache, "sha256-${digest.substringAfter(':')}.part").writeBytes(bytes.copyOfRange(0, 5))
            server.enqueue(MockResponse().setResponseCode(206)
                .setHeader("Content-Range", "bytes 5-${bytes.lastIndex}/${bytes.size}")
                .setBody(Buffer().write(bytes.copyOfRange(5, bytes.size))))
            val file = OciLayerStore(OkHttpClient()).download(server.url("/layer").toString(), digest, { "" }, cache) {}
            assertArrayEquals(bytes, file.readBytes())
            assertEquals("bytes=5-", server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun ignoredRangeReplacesPartialRatherThanAppending() = runBlocking {
        MockWebServer().use { server ->
            val bytes = "complete-layer".toByteArray()
            val digest = digest(bytes)
            val cache = temporary.newFolder()
            File(cache, "sha256-${digest.substringAfter(':')}.part").writeText("bad-prefix")
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val file = OciLayerStore(OkHttpClient()).download(server.url("/layer").toString(), digest, { "" }, cache) {}
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun completedPartialWith416IsVerifiedAndCommitted() = runBlocking {
        MockWebServer().use { server ->
            val bytes = "complete-layer".toByteArray()
            val digest = digest(bytes)
            val cache = temporary.newFolder()
            File(cache, "sha256-${digest.substringAfter(':')}.part").writeBytes(bytes)
            server.enqueue(MockResponse().setResponseCode(416))
            val file = OciLayerStore(OkHttpClient()).download(server.url("/layer").toString(), digest, { "" }, cache) {}
            assertArrayEquals(bytes, file.readBytes())
        }
    }

    @Test fun corruptCacheAndBadDownloadAreReplacedOnlyAfterVerification() = runBlocking {
        MockWebServer().use { server ->
            val bytes = "verified-layer".toByteArray()
            val digest = digest(bytes)
            val cache = temporary.newFolder()
            val target = File(cache, "sha256-${digest.substringAfter(':')}.layer").apply { writeText("bad-cache") }
            server.enqueue(MockResponse().setBody("bad-download"))
            server.enqueue(MockResponse().setBody(Buffer().write(bytes)))
            val file = OciLayerStore(OkHttpClient()).download(server.url("/layer").toString(), digest, { "" }, cache) {}
            assertEquals(target, file)
            assertArrayEquals(bytes, file.readBytes())
            assertEquals(2, server.requestCount)
            assertNull(server.takeRequest().getHeader("Range"))
            assertNull(server.takeRequest().getHeader("Range"))
        }
    }

    @Test fun persistentDigestMismatchNeverCommitsLayer() {
        MockWebServer().use { server ->
            val digest = digest("expected".toByteArray())
            val cache = temporary.newFolder()
            repeat(2) { server.enqueue(MockResponse().setBody("wrong")) }
            assertThrows(IllegalStateException::class.java) {
                runBlocking { OciLayerStore(OkHttpClient()).download(server.url("/layer").toString(), digest, { "" }, cache) {} }
            }
            assertFalse(File(cache, "sha256-${digest.substringAfter(':')}.layer").exists())
            assertEquals(2, server.requestCount)
        }
    }

    private fun digest(bytes: ByteArray) = "sha256:" + MessageDigest.getInstance("SHA-256")
        .digest(bytes).joinToString("") { "%02x".format(it) }
}
