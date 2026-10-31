package top.tianyan.app.core.tools

import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Signature
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test

class RemotePluginLibraryTest {
    @Test fun sourceSelectionNeverFallsBackToGithubForMissingGiteeAssets() {
        val release = RemotePluginRelease("example.hello", "hello-plugin", "Hello", "", "PROCESS", "1.0.0", 1, 1,
            3, "0".repeat(64), listOf(RemotePluginPart("https://github.com/a/b/file", 3, "0".repeat(64))))
        val gitee = top.tianyan.app.core.model.PluginLibraryChannel.GITEE
        assertFalse(RemotePluginSource.available(release, gitee))
        assertThrows(IllegalArgumentException::class.java) { RemotePluginSource.select(release, gitee) }
        val mirrored = release.copy(parts = release.parts.map { it.copy(mirrors = mapOf("GITEE" to "https://gitee.com/a/b/file")) })
        assertTrue(RemotePluginSource.available(mirrored, gitee))
        assertEquals("https://gitee.com/a/b/file", RemotePluginSource.select(mirrored, gitee).parts.single().url)
        assertEquals(release.sha256, RemotePluginSource.select(mirrored, gitee).sha256)
    }
    private fun hash(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun catalog(version: String = "1.0.0", minimum: Int = 1) = """{"schemaVersion":1,"revision":1,"plugins":[{
        "pluginId":"example.hello","toolId":"hello-plugin","name":"Hello","description":"Example","execution":"PROCESS",
        "releases":[{"version":"$version","channel":"stable","platform":"linux-arm64","pluginApi":{"minimum":$minimum,"maximum":3},
        "size":3,"sha256":"${hash("abc".toByteArray())}","parts":[{"url":"https://example.com/p","size":3,"sha256":"${hash("abc".toByteArray())}"}]}]}]}"""

    @Test fun signedCatalogRejectsTamperingAndWrongKeys() {
        val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        val bytes = catalog().toByteArray()
        val signature = Base64.getEncoder().encode(Signature.getInstance("Ed25519").run { initSign(keys.private); update(bytes); sign() })
        assertEquals("1.0.0", RemotePluginCatalog.verify(bytes, signature, keys.public.encoded).latest().single().version)
        assertThrows(IllegalArgumentException::class.java) { RemotePluginCatalog.verify(catalog("1.0.1").toByteArray(), signature, keys.public.encoded) }
        val other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        assertThrows(IllegalArgumentException::class.java) { RemotePluginCatalog.verify(bytes, signature, other.public.encoded) }
    }

    @Test fun catalogEnforcesCompatibilityAndBounds() {
        assertTrue(RemotePluginCatalog.parse(catalog(minimum = 2)).latest().isEmpty())
        listOf(catalog().replace("https://", "http://"), catalog().replaceFirst("\"size\":3", "\"size\":4"),
            catalog("1.0.0-beta"), catalog().replace("\"schemaVersion\":1", "\"schemaVersion\":2")).forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { RemotePluginCatalog.parse(invalid) }
        }
    }

    @Test fun legacyCatalogIdentityMustMatchLegacyPackage() {
        val text = catalog().replace("example.hello", "legacy.tool-hello-plugin")
            .replace("\"version\":\"1.0.0\"", "\"version\":\"1.0.0\",\"packageSchema\":1")
        val release = RemotePluginCatalog.parse(text).latest().single()
        val manifest = top.tianyan.app.plugin.packages.PluginPackageProtocol.decode("""{"id":"hello-plugin","name":"Hello","description":"Legacy","version":"1.0.0"}""")
        assertTrue(RemotePluginSource.matches(release, manifest))
        assertFalse(RemotePluginSource.matches(release.copy(pluginId = "legacy.tool-other-plugin"), manifest))
    }

    @Test fun downloadsMergeAndReuseVerifiedPartsAfterFailure() = runBlocking {
        val folder = Files.createTempDirectory("library-test").toFile()
        try {
            val first = "abc".toByteArray(); val second = "def".toByteArray()
            var calls = 0; var broken = true
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                calls++
                val body = if (chain.request().url.encodedPath == "/1") first else if (broken) "bad".toByteArray() else second
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            val release = RemotePluginRelease("example.hello", "hello-plugin", "Hello", "", "PROCESS", "1.0.0", 1, 1,
                6, hash(first + second), listOf(RemotePluginPart("https://example.com/1", 3, hash(first)), RemotePluginPart("https://example.com/2", 3, hash(second))))
            val downloader = RemotePluginDownloader(http, folder)
            try { downloader.download(release) { _, _ -> }; fail("Corruption must fail") } catch (_: IllegalArgumentException) { }
            assertEquals(2, calls)
            broken = false
            assertArrayEquals(first + second, downloader.download(release) { _, _ -> }.readBytes())
            assertEquals(3, calls)
            val cachedProgress = mutableListOf<Pair<Long, Long>>()
            val cached = downloader.download(release) { done, total -> cachedProgress += done to total }
            assertEquals(listOf(6L to 6L), cachedProgress)
            assertEquals(3, calls)
            cached.writeBytes("broken".toByteArray())
            assertArrayEquals(first + second, downloader.download(release) { _, _ -> }.readBytes())
            assertEquals(5, calls)
        } finally { folder.deleteRecursively() }
    }

    @Test fun rejectsOversizedBodiesAndIncorrectWholePackageHash() = runBlocking {
        val folder = Files.createTempDirectory("library-bounds-test").toFile()
        try {
            val bytes = "abc".toByteArray()
            var body = "too-long".toByteArray()
            val http = OkHttpClient.Builder().addInterceptor { chain ->
                Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body(body.toResponseBody()).build()
            }.build()
            val release = RemotePluginRelease("example.hello", "hello-plugin", "Hello", "", "PROCESS", "1.0.0", 1, 1,
                3, hash("bad".toByteArray()), listOf(RemotePluginPart("https://example.com/1", 3, hash(bytes))))
            val downloader = RemotePluginDownloader(http, folder)
            try { downloader.download(release) { _, _ -> }; fail("Oversized body accepted") } catch (_: IllegalArgumentException) { }
            body = bytes
            try { downloader.download(release) { _, _ -> }; fail("Incorrect package hash accepted") } catch (_: IllegalArgumentException) { }
            assertFalse(folder.walkTopDown().any { it.name == "package.txplugin" })
        } finally { folder.deleteRecursively() }
    }
}
