package top.tianyan.app.core.tools

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.model.*
import top.tianyan.app.plugin.packages.WebPluginPackagePolicy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class WebPluginRepositoryTest {
    @Test fun cancelledFullPreviewRemovesItsTemporaryFilesAndDoesNotSelectPackage() {
        val fixture = fixture()
        val expected = manifest().let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        val uri = archive(expected)
        var checks = 0
        val failure = runCatching {
            ProotWebPackagePreflight.preview(fixture.context.cacheDir, expected, { File(uri.path!!).inputStream() }) {
                if (++checks == 3) throw kotlinx.coroutines.CancellationException("cancelled")
            }
        }.exceptionOrNull()
        assertTrue(failure is kotlinx.coroutines.CancellationException)
        assertTrue(fixture.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("web-preview-") })
        assertTrue(fixture.repository.packages.value.isEmpty())
    }

    @Test fun reopenedPreviewStreamMustMatchTheInitiallyReadManifest() {
        val fixture = fixture()
        val expected = manifest().let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        val changed = expected.copy(description = "changed after initial preview read")
        val uri = archive(changed)
        assertThrows(IllegalArgumentException::class.java) {
            ProotWebPackagePreflight.preview(fixture.context.cacheDir, expected, { File(uri.path!!).inputStream() }, {})
        }
        assertTrue(fixture.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("web-preview-") })
        assertTrue(fixture.repository.packages.value.isEmpty())
    }

    @Test fun oversizedProotPreviewAndDirectImportFailBeforeSelectingAnyVersion() = runBlocking {
        val fixture = fixture()
        val bytes = ByteArray(8192).also { java.util.Random(8).nextBytes(it) }
        val content = mapOf("index.html" to bytes)
        val expected = manifest(content = content).let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        val uri = archive(expected, content)
        assertTrue(runCatching { fixture.repository.preview(uri) }.exceptionOrNull() is top.tianyan.app.runtime.service.ProotWebBootstrapLimitException)
        assertTrue(runCatching { fixture.repository.import(uri, expected) }.exceptionOrNull() is top.tianyan.app.runtime.service.ProotWebBootstrapLimitException)
        assertTrue(fixture.repository.packages.value.isEmpty())
        assertNull(fixture.registry.localManifest(expected.id, expected.version))
        assertTrue(fixture.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("web-preview-") || it.name.startsWith("txplugin-") })
    }

    @Test fun rejectedProotUpgradeKeepsPreviousSelectionAndVerifiedBytes() = runBlocking {
        val fixture = fixture()
        val first = manifest()
        fixture.repository.import(archive(first), first)
        val content = mapOf("index.html" to ByteArray(8192).also { java.util.Random(9).nextBytes(it) })
        val next = manifest("2.0.0", content).let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        val uri = archive(next, content)
        reject { fixture.repository.preview(uri) }
        reject { fixture.repository.import(uri, next) }
        assertTrue(fixture.repository.isCurrent(first))
        assertArrayEquals(files.getValue("index.html"), fixture.repository.snapshot(first)["index.html"])
        assertNull(fixture.registry.localManifest(next.id, next.version))
    }

    @Test fun sameLargeAssetsRemainSupportedByDirectPackageSource() = runBlocking {
        val fixture = fixture()
        val content = mapOf("index.html" to ByteArray(8192).also { java.util.Random(10).nextBytes(it) })
        val expected = manifest(content = content)
        val uri = archive(expected, content)
        assertEquals(expected, fixture.repository.preview(uri).manifest)
        fixture.repository.import(uri, expected)
        assertArrayEquals(content.getValue("index.html"), fixture.repository.snapshot(expected)["index.html"])
    }

    @Test fun serviceSourcePackageImportsWithoutPayloadAndSourceDeclarationIsImmutable() = runBlocking {
        val fixture = fixture()
        val expected = manifest(content = emptyMap()).let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_SERVICE", serviceToolId = "web-tool")) }
        val uri = archive(expected, emptyMap())
        assertEquals(expected, fixture.repository.preview(uri).manifest)
        fixture.repository.import(uri, expected)
        assertTrue(fixture.repository.snapshot(expected).isEmpty())
        val moved = expected.copy(plugin = expected.plugin!!.copy(serviceToolId = "other-tool"))
        reject { fixture.repository.import(archive(moved, emptyMap()), moved) }
        assertTrue(fixture.repository.isCurrent(expected))
    }

    @Test fun prootPreviewVerifiesFullArchiveAndCleansUpOnHashFailure() = runBlocking {
        val fixture = fixture()
        val expected = manifest().let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        val uri = archive(expected, files + ("plugin.js" to "tampered".toByteArray()))
        reject { fixture.repository.preview(uri) }
        assertTrue(fixture.repository.packages.value.isEmpty())
        assertTrue(fixture.context.cacheDir.listFiles().orEmpty().none { it.name.startsWith("web-preview-") })
    }

    @Test fun sourceSelectionIsBoundToImportConfirmationAndImmutableVersion() = runBlocking {
        val fixture = fixture()
        val direct = manifest()
        val proot = direct.copy(plugin = direct.plugin!!.copy(webSource = "PROOT_STATIC"))
        val uri = archive(proot)
        assertEquals(proot, fixture.repository.preview(uri).manifest)
        reject { fixture.repository.import(uri, direct) }
        assertTrue(fixture.repository.packages.value.isEmpty())
        fixture.repository.import(uri, proot)
        assertTrue(fixture.repository.isCurrent(proot))
        reject { fixture.repository.import(archive(direct), direct) }
        assertFalse(fixture.repository.isCurrent(direct))
        assertArrayEquals(files.getValue("index.html"), fixture.repository.snapshot(proot)["index.html"])
    }

    @Test fun sourceUpgradeInvalidatesOldPageAndRemovalRevokesProotPackage() = runBlocking {
        val fixture = fixture()
        val direct = manifest()
        fixture.repository.import(archive(direct), direct)
        val proot = manifest("2.0.0").let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        fixture.repository.import(archive(proot), proot)
        assertFalse(fixture.repository.isCurrent(direct))
        reject { fixture.repository.snapshot(direct) }
        val payload = fixture.registry.localPayloadRoot(proot.id, proot.version)!!
        File(payload, "plugin.js").writeText("tampered")
        reject { fixture.repository.snapshot(proot) }
        fixture.repository.remove(proot)
        assertFalse(fixture.repository.isCurrent(proot))
        reject { fixture.repository.snapshot(proot) }
    }

    @get:Rule val temp = TemporaryFolder()
    private val files = mapOf("index.html" to "<html><script src=\"plugin.js\"></script></html>".toByteArray(), "plugin.js" to "'use strict';".toByteArray())
    private fun manifest(version: String = "1.0.0", content: Map<String, ByteArray> = files) = ToolManifest(
        schemaVersion = 2, id = "web-example", name = "Example", description = "Offline static", version = version,
        source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE",
        plugin = PluginPackageSpec("example.web", PackageApiRange(1, 1), "WEB", extensions = listOf(PackageExtension("main", "PAGE")),
            capabilities = listOf(WebPluginPackagePolicy.APP_INFO), integrity = content.mapValues { sha(it.value) }),
    )
    private fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    private fun archive(manifest: ToolManifest, content: Map<String, ByteArray> = files): Uri {
        val output = temp.newFile("${java.util.UUID.randomUUID()}.txplugin")
        ZipOutputStream(output.outputStream()).use { zip ->
            (mapOf("manifest.json" to Json.encodeToString(ToolManifest.serializer(), manifest).toByteArray()) + content.mapKeys { "payload/${it.key}" }).forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path)); zip.write(bytes); zip.closeEntry()
            }
        }
        return Uri.fromFile(output)
    }
    private data class Fixture(val context: Context, val registry: ToolRegistry, val repository: WebPluginRepository)
    private fun fixture(): Fixture {
        val root = temp.newFolder()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir() = File(root, "files").apply { mkdirs() }
            override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        }
        File(context.filesDir, "registry/tools.json").apply {
            parentFile!!.mkdirs(); writeText("""[{"id":"base-tool","name":"Base","description":"Base"}]""")
        }
        val registry = ToolRegistry(context, OkHttpClient(), AppLogger(context, SensitiveDataRedactor { it }))
        return Fixture(context, registry, WebPluginRepository(registry))
    }
    private suspend fun reject(action: suspend () -> Unit) {
        try { action(); fail("Expected rejection") } catch (_: IllegalStateException) { } catch (_: IllegalArgumentException) { }
    }

    @Test fun importsWithoutLinuxAndOpensVerifiedSnapshotOutsideProcessCatalog() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        val uri = archive(expected)
        assertTrue(fixture.repository.packages.value.isEmpty())
        val preview = fixture.repository.preview(uri)
        assertEquals(expected, preview.manifest)
        assertTrue(fixture.repository.packages.value.isEmpty())
        fixture.repository.import(uri, preview.manifest)
        assertEquals(listOf(expected), fixture.repository.packages.value)
        assertFalse(fixture.registry.load().any { it.id == expected.id })
        val snapshot = fixture.repository.snapshot(expected)
        assertEquals(files.keys, snapshot.keys)
        files.forEach { (path, bytes) -> assertArrayEquals(bytes, snapshot[path]) }
        val reopened = WebPluginRepository(fixture.registry)
        reopened.refresh()
        assertEquals(listOf(expected), reopened.packages.value)
    }

    @Test fun processImportEntryCannotActivateWebPackage() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        val uri = archive(expected)
        assertTrue(fixture.registry.inspectLocal(uri).isFailure)
        assertTrue(fixture.registry.importLocal(uri, expected = expected).isFailure)
        assertTrue(fixture.registry.webPackages().isEmpty())
    }

    @Test fun changedPreviewHashMismatchAndExtraFilesNeverPublishCandidate() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        reject { fixture.repository.import(archive(expected.copy(name = "Changed")), expected) }
        reject { fixture.repository.import(archive(expected, files + ("plugin.js" to "changed".toByteArray())), expected) }
        reject { fixture.repository.import(archive(expected, files + ("extra.js" to byteArrayOf())), expected) }
        assertTrue(fixture.repository.packages.value.isEmpty())
    }

    @Test fun updateInvalidatesOldSnapshotAndRejectsDowngradeOrStaleRemoval() = runBlocking {
        val fixture = fixture()
        val first = manifest()
        fixture.repository.import(archive(first), first)
        val next = manifest("2.0.0")
        fixture.repository.import(archive(next), next)
        assertFalse(fixture.repository.isCurrent(first))
        assertTrue(fixture.repository.isCurrent(next))
        reject { fixture.repository.snapshot(first) }
        reject { fixture.repository.remove(first) }
        reject { fixture.repository.import(archive(first), first) }
        val metadataOnly = manifest("2.0.0+other")
        reject { fixture.repository.import(archive(metadataOnly), metadataOnly) }
        assertTrue(fixture.repository.isCurrent(next))
    }

    @Test fun immutableVersionNeverOverwritesAndFailedUpgradeKeepsCurrentPackage() = runBlocking {
        val fixture = fixture()
        val first = manifest()
        fixture.repository.import(archive(first), first)
        val alias = first.copy(id = "web-alias")
        reject { fixture.repository.import(archive(alias), alias) }
        val renamed = first.copy(plugin = first.plugin!!.copy(id = "example.renamed"))
        reject { fixture.repository.import(archive(renamed), renamed) }
        val otherFiles = files + ("plugin.js" to "edited".toByteArray())
        val changed = manifest(content = otherFiles)
        reject { fixture.repository.import(archive(changed, otherFiles), changed) }
        val next = manifest("2.0.0")
        reject { fixture.repository.import(archive(next, otherFiles), next) }
        assertTrue(fixture.repository.isCurrent(first))
        assertArrayEquals(files.getValue("plugin.js"), fixture.repository.snapshot(first)["plugin.js"])
    }

    @Test fun openRevalidatesManifestAndEveryAssetAndRejectsExtraFiles() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        fixture.repository.import(archive(expected), expected)
        val payload = fixture.registry.localPayloadRoot(expected.id, expected.version)!!
        File(payload, "plugin.js").writeText("tampered")
        reject { fixture.repository.snapshot(expected) }
        File(payload, "plugin.js").writeBytes(files.getValue("plugin.js"))
        File(payload, "extra").writeText("unlisted")
        reject { fixture.repository.snapshot(expected) }
        File(payload, "extra").delete()
        File(payload.parentFile, "manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), expected.copy(name = "Tampered")))
        reject { fixture.repository.snapshot(expected) }
    }

    @Test fun openChecksDurableSelectionEvenWhenAnObserverHasNotRefreshed() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        fixture.repository.import(archive(expected), expected)
        val next = manifest("2.0.0")
        assertTrue(fixture.registry.importLocal(archive(next), expected = next, execution = "WEB").isSuccess)
        assertTrue(fixture.repository.isCurrent(expected)) // Deliberately stale observer state.
        reject { fixture.repository.snapshot(expected) }
        fixture.repository.refresh()
        assertTrue(fixture.repository.isCurrent(next))
    }

    @Test fun rejectsOversizedPayloadWithoutSelectingIt() = runBlocking {
        val fixture = fixture()
        val large = mapOf("index.html" to ByteArray((WebPluginPackagePolicy.MAX_PAYLOAD_BYTES + 1).toInt()))
        val expected = manifest(content = large)
        reject { fixture.repository.import(archive(expected, large), expected) }
        assertTrue(fixture.repository.packages.value.isEmpty())
    }

    @Test fun streamingPreviewCannotDecompressUnboundedPayloadBeforeALateManifest() {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("payload/large.bin"))
            zip.write(ByteArray((WebPluginPackagePolicy.MAX_ARCHIVE_BYTES + 1).toInt()))
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("manifest.json"))
            zip.write(Json.encodeToString(ToolManifest.serializer(), manifest()).toByteArray())
            zip.closeEntry()
        }
        assertThrows(IllegalArgumentException::class.java) {
            LocalPluginArchive.inspect(bytes.toByteArray().inputStream(), maximumBytes = WebPluginPackagePolicy.MAX_ARCHIVE_BYTES,
                maximumEntries = WebPluginPackagePolicy.MAX_ARCHIVE_ENTRIES)
        }
    }

    @Test fun boundedImplicitParentDirectoriesCanBeOpenedWithoutExplicitZipEntries() = runBlocking {
        val fixture = fixture()
        val nested = files + (1..20).associate { "group$it/d1/d2/d3/d4/d5/d6/d7/part.js" to "hello".toByteArray() }
        val expected = manifest(content = nested)
        fixture.repository.import(archive(expected, nested), expected)
        assertEquals(nested.keys, fixture.repository.snapshot(expected).keys)
    }

    @Test fun removeInvalidatesPackageAndDeletesOnlyItsOwnVersions() = runBlocking {
        val fixture = fixture()
        val expected = manifest()
        fixture.repository.import(archive(expected), expected)
        fixture.repository.remove(expected)
        assertFalse(fixture.repository.isCurrent(expected))
        assertTrue(fixture.repository.packages.value.isEmpty())
        assertFalse(File(fixture.context.filesDir, "plugins/${expected.id}").exists())
        assertEquals("base-tool", fixture.registry.load().single().id)
        reject { fixture.repository.snapshot(expected) }
    }

    @Test fun rejectsBuiltinIdentityAndProcessKindCollisionsAcrossSharedStorage() = runBlocking {
        val fixture = fixture()
        val builtinCollision = manifest().copy(id = "base-tool")
        reject { fixture.repository.import(archive(builtinCollision), builtinCollision) }
        val process = manifest().copy(plugin = manifest().plugin!!.copy(execution = "PROCESS", extensions = emptyList(), capabilities = emptyList()))
        assertTrue(fixture.registry.importLocal(archive(process), expected = process).isSuccess)
        reject { fixture.repository.import(archive(manifest()), manifest()) }
        assertTrue(fixture.registry.webPackages().isEmpty())
        assertEquals("PROCESS", fixture.registry.load().find { it.id == process.id }!!.plugin!!.execution)
    }
}
