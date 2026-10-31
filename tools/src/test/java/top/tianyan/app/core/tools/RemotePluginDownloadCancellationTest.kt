package top.tianyan.app.core.tools

import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class RemotePluginDownloadCancellationTest {
    @Test fun stoppingDownloadCancelsBlockedSocketWithoutPublishingPartialPackage() = runBlocking {
        val directory = Files.createTempDirectory("plugin-stop-test").toFile()
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024)).throttleBody(1024, 1, TimeUnit.SECONDS))
        server.start()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            chain.proceed(chain.request().newBuilder().url(server.url("/plugin")).build())
                .newBuilder().request(chain.request()).build()
        }.build()
        try {
            val size = 2L * 1024 * 1024
            val part = RemotePluginPart("https://example.test/plugin", size, "0".repeat(64))
            val release = RemotePluginRelease("example.plugin", "example", "Example", "", "PROCESS", "1.0.0",
                1, 1, size, "0".repeat(64), listOf(part))
            val task = async(Dispatchers.IO) { RemotePluginDownloader(client, directory).download(release) { _, _ -> } }
            assertNotNull(server.takeRequest(5, TimeUnit.SECONDS))
            task.cancel()
            withTimeout(5000) { task.join() }
            assertTrue(task.isCancelled)
            assertFalse(directory.walkTopDown().any { it.name == "package.txplugin" || it.name.endsWith(".tmp") })
        } finally {
            client.dispatcher.executorService.shutdownNow()
            client.connectionPool.evictAll()
            server.shutdown()
            directory.deleteRecursively()
        }
    }
}
