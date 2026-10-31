package top.tianyan.app.core.tools

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.*
import top.tianyan.app.plugin.packages.PluginPackageProtocol
import top.tianyan.app.plugin.packages.SemanticVersion
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.core.common.logging.SensitiveDataRedactor
import top.tianyan.app.core.common.result.AppResult
import okhttp3.OkHttpClient

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class PluginDebugPackagerTest {
    @get:Rule val temp = TemporaryFolder()

    private fun project(): File = temp.newFolder().also { root ->
        File(root, "payload/bin").mkdirs()
        File(root, "payload/bin/sample").writeText("#!/bin/sh\necho hello\n")
        File(root, "README.md").writeText("Development only")
        File(root, "manifest.json").writeText(Json.encodeToString(ToolManifest.serializer(), ToolManifest(
            schemaVersion = 2, id = "sample", name = "Sample", description = "Offline", version = "1.0.0",
            source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE",
            plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS"),
        )))
    }

    private fun unpack(archive: File): ToolManifest = archive.inputStream().use {
        LocalPluginArchive.extract(it, temp.newFolder(), archive.length(), {})
    }

    @Test fun packagesOnlyPayloadWithValidatedHashesAndLeavesReleaseSourceUntouched() {
        val root = project()
        val source = File(root, "manifest.json").readText()
        val archive = PluginDebugPackager.pack(root)
        val manifest = unpack(archive)
        assertTrue(manifest.version.startsWith("1.0.0+tianyan.debug."))
        assertNotNull(SemanticVersion.parse(manifest.version))
        assertEquals(setOf("bin/sample"), manifest.plugin!!.integrity.keys)
        ZipFile(archive).use { zip ->
            assertEquals(setOf("payload/bin/sample", "manifest.json"), zip.entries().asSequence().map { it.name }.toSet())
        }
        assertEquals(source, File(root, "manifest.json").readText())
    }

    @Test fun fileUriPreviewAndConfirmedRegistryImportAcceptNativeSnapshot() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "registry/tools.json").apply {
            parentFile!!.mkdirs()
            writeText("""[{"id":"base-tool","name":"Base","description":"Base"}]""")
        }
        val registry = ToolRegistry(context, OkHttpClient(), AppLogger(context, SensitiveDataRedactor { it }))
        val archive = PluginDebugPackager.pack(project())
        val uri = Uri.fromFile(archive)
        val preview = registry.inspectLocal(uri)
        assertTrue(preview.toString(), preview is AppResult.Success)
        val metadata = (preview as AppResult.Success).data
        assertFalse(metadata.alreadyImported)
        assertNull(registry.localManifest(metadata.manifest.id, metadata.manifest.version))
        val imported = registry.importLocal(uri, expected = metadata.manifest)
        assertTrue(imported.toString(), imported is AppResult.Success)
        assertEquals(metadata.manifest, registry.localManifest(metadata.manifest.id, metadata.manifest.version))
    }

    @Test fun successiveEditsImportIntoDistinctImmutableVersions() = runBlocking {
        val root = project()
        val first = PluginDebugPackager.pack(root)
        File(root, "payload/bin/sample").appendText("echo edited\n")
        val second = PluginDebugPackager.pack(root)
        val store = LocalPluginStore(temp.newFolder(), temp.newFolder())
        val old = store.import({ first.inputStream() }, first.length(), null, {}, {})
        val next = store.import({ second.inputStream() }, second.length(), null, {}, {})
        assertNotEquals(old.version, next.version)
        assertFalse(File(store.payload("sample", old.version)!!, "bin/sample").readText().contains("edited"))
        assertTrue(File(store.payload("sample", next.version)!!, "bin/sample").readText().contains("edited"))
        assertEquals(next.version, store.load().single().version)
    }

    @Test fun unchangedOrRevertedCodeCanBeReinstalledWithIndependentSnapshots() {
        val root = project()
        val first = PluginDebugPackager.pack(root)
        val second = PluginDebugPackager.pack(root)
        assertNotEquals(first, second)
        val firstManifest = unpack(first)
        val nextManifest = unpack(second)
        assertNotEquals(firstManifest.version, nextManifest.version)
        assertEquals(firstManifest.plugin!!.integrity, nextManifest.plugin!!.integrity)
    }

    @Test fun manifestOnlyChangesProduceNewDebugVersion() {
        val root = project()
        val first = unpack(PluginDebugPackager.pack(root))
        val source = File(root, "manifest.json")
        val manifest = PluginPackageProtocol.decode(source.readText()).copy(description = "Edited description")
        source.writeText(Json.encodeToString(ToolManifest.serializer(), manifest))
        assertNotEquals(first.version, unpack(PluginDebugPackager.pack(root)).version)
    }

    @Test fun cancellationCleansTemporaryArchiveAndPreservesExistingSnapshots() {
        val root = project()
        val first = PluginDebugPackager.pack(root)
        var checks = 0
        assertThrows(CancellationException::class.java) {
            PluginDebugPackager.pack(root) { if (++checks == 3) throw CancellationException("test") }
        }
        assertEquals(listOf(first), File(root, "dist").listFiles()!!.toList())
        unpack(first)
    }

    @Test fun rejectsLegacyManifestBeforeWritingOutput() {
        val root = project()
        File(root, "manifest.json").writeText("""{"id":"sample","name":"Sample","description":"Legacy","version":"1.0.0"}""")
        assertThrows(IllegalArgumentException::class.java) { PluginDebugPackager.pack(root) }
        assertFalse(File(root, "dist").exists())
    }

    @Test fun rejectsPayloadSymlinksWhenSupportedByHost() {
        val root = project()
        val link = File(root, "payload/link").toPath()
        try { Files.createSymbolicLink(link, File(root, "README.md").toPath()) }
        catch (_: Exception) { org.junit.Assume.assumeTrue("Host cannot create symlinks", false) }
        assertThrows(IllegalArgumentException::class.java) { PluginDebugPackager.pack(root) }
        assertTrue(File(root, "dist").listFiles().orEmpty().isEmpty())
    }
}
