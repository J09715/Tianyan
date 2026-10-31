package top.tianyan.app.core.tools

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.*

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalPluginStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private fun sha(text: String) = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun manifest(version: String = "1.0.0", content: String = "echo hello\n") = ToolManifest(
        schemaVersion = 2, id = "sample", name = "Sample", description = "Offline", version = version,
        source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE",
        plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS", integrity = mapOf("run.sh" to sha(content))),
    )
    private fun archive(manifest: ToolManifest, entries: List<Pair<String, String>> = listOf("payload/run.sh" to "echo hello\n")): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            (listOf("manifest.json" to Json.encodeToString(ToolManifest.serializer(), manifest)) + entries).forEach { (name, text) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray()); zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }
    private suspend fun import(store: LocalPluginStore, data: ByteArray, expected: ToolManifest? = null) =
        store.import({ data.inputStream() }, data.size.toLong(), expected, {}, {})

    @Test fun validV2ImportsAndPreservesVerifiedPayloadBytes() = runBlocking {
        val root = temp.newFolder("root")
        val store = LocalPluginStore(root, temp.newFolder("cache"))
        val manifest = manifest(content = "echo hello\r\n")
        import(store, archive(manifest, listOf("payload/run.sh" to "echo hello\r\n")), manifest)
        assertEquals(listOf(manifest), store.load())
        assertEquals("echo hello\r\n", File(store.payload("sample")!!, "run.sh").readText())
    }
    @Test fun changedMissingAndAdditionalPayloadsNeverActivate() = runBlocking {
        val store = LocalPluginStore(temp.newFolder("root"), temp.newFolder("cache"))
        listOf(emptyList(), listOf("payload/run.sh" to "changed"), listOf("payload/run.sh" to "echo hello\n", "payload/extra" to "extra")).forEach { entries ->
            try { import(store, archive(manifest(), entries)); fail("Expected integrity failure") } catch (_: IllegalArgumentException) { }
            assertTrue(store.load().isEmpty())
        }
    }
    @Test fun unsafeArchiveNamesAndDuplicateDirectoryAliasesAreRejected() {
        listOf("../manifest.json", "/payload/evil", "payload/../evil", "payload\\evil", "other/file", "payload/C:evil", "payload//evil").forEach { name ->
            assertThrows(IllegalArgumentException::class.java) { LocalPluginArchive.entryName(name) }
        }
        val staging = temp.newFolder("staging")
        assertThrows(IllegalArgumentException::class.java) {
            LocalPluginArchive.extract(archive(manifest(), listOf("payload/dir/" to "", "payload/dir" to "file")).inputStream(), staging, null, {})
        }
    }
    @Test fun confirmationIsBoundToPreviewManifest() = runBlocking {
        val store = LocalPluginStore(temp.newFolder("root"), temp.newFolder("cache"))
        try { import(store, archive(manifest()), manifest().copy(name = "Changed")); fail("Changed preview") } catch (_: IllegalArgumentException) { }
        assertTrue(store.load().isEmpty())
    }
    @Test fun legacyV1StillImportsWithoutV2IntegrityDeclarations() = runBlocking {
        val store = LocalPluginStore(temp.newFolder("root"), temp.newFolder("cache"))
        val legacy = manifest().copy(schemaVersion = 1, plugin = null)
        import(store, archive(legacy, listOf("payload/run.sh" to "legacy script")))
        assertEquals(legacy, store.load().single())
        assertEquals("legacy script", File(store.payload("sample")!!, "run.sh").readText())
    }
    @Test fun sameVersionNeverOverwritesStoredPayloadOrManifest() = runBlocking {
        val store = LocalPluginStore(temp.newFolder("root"), temp.newFolder("cache"))
        import(store, archive(manifest()))
        try { import(store, archive(manifest(content = "changed"), listOf("payload/run.sh" to "changed"))); fail("Same version") } catch (_: IllegalArgumentException) { }
        assertEquals("echo hello\n", File(store.payload("sample")!!, "run.sh").readText())
    }
    @Test fun explicitSelectionSurvivesLexicallyLargerOlderVersion() = runBlocking {
        val root = temp.newFolder("root")
        val store = LocalPluginStore(root, temp.newFolder("cache"))
        import(store, archive(manifest("1.9.0")))
        import(store, archive(manifest("1.10.0")))
        assertEquals("1.10.0", store.load().single().version)
        assertTrue(File(root, "sample/1.9.0/manifest.json").isFile)
        File(root, "sample/active-version").writeText("../escape")
        assertTrue(store.load().isEmpty())
    }
    @Test fun oldImplicitSelectionUsesSemanticVersionAndSkipsBrokenDirectories() {
        val root = temp.newFolder("root")
        listOf("1.9.0", "1.10.0").forEach { version ->
            File(root, "sample/$version").mkdirs()
            File(root, "sample/$version/manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), manifest(version)))
        }
        File(root, "sample/999.0.0").mkdirs()
        assertEquals("1.10.0", LocalPluginStore(root, temp.newFolder("cache")).load().single().version)
    }
    @Test fun cancellationStopsArchiveExtraction() {
        assertThrows(CancellationException::class.java) {
            LocalPluginArchive.extract(archive(manifest()).inputStream(), temp.newFolder("staging"), null, {}, { throw CancellationException() })
        }
    }
    @Test fun receiptsRemainBoundToActualInstalledVersionAndExcludeEnvironmentSecrets() {
        val root = temp.newFolder("receipts")
        val receipts = PluginReceiptStore(root)
        receipts.record("debian", manifest().copy(environment = mapOf("SECRET" to "do-not-store")))
        receipts.record("debian", manifest("2.0.0"))
        assertEquals("1.0.0", receipts.read("debian", "sample", "1.0.0")!!.version)
        assertNull(receipts.read("ubuntu", "sample", "1.0.0"))
        assertFalse(root.walkTopDown().filter { it.isFile }.any { it.readText().contains("do-not-store") })
        val receiptFile = root.walkTopDown().first { it.isFile }
        receiptFile.writeText("{}")
        assertThrows(Exception::class.java) { receipts.read("debian", "sample", "1.0.0"); receipts.read("debian", "sample", "2.0.0") }
    }
}
