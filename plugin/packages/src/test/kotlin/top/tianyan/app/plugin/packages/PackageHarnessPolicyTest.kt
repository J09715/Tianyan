package top.tianyan.app.plugin.packages

import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.*

class PackageHarnessPolicyTest {
    private val schema = Json.parseToJsonElement("""{"type":"object","properties":{"text":{"type":"string"}},"additionalProperties":false}""").jsonObject
    private fun manifest() = ToolManifest(schemaVersion = 2, id = "sample", name = "Sample", description = "Test", version = "1.0.0",
        source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE", commandLinks = listOf("sample"),
        plugin = PluginPackageSpec("example.sample", PackageApiRange(1, 1), "PROCESS",
            extensions = listOf(PackageExtension("echo", "TOOL"), PackageExtension("step", "WORKFLOW"), PackageExtension("offline", "PROVIDER")),
            harness = PackageHarnessSpec("sample", tools = listOf(PackageCallable("echo", "Echo", schema)),
                nodes = listOf(PackageCallable("step", "Step", schema)), providers = listOf(PackageProvider("offline", "provider")))))

    @Test fun processMetadataRegistersExactlyTheDeclaredExtensionKinds() {
        val manifest = manifest()
        PluginPackageProtocol.validate(manifest)
        assertEquals(setOf("TOOL", "WORKFLOW", "PROVIDER"), PluginPackageProtocol.descriptor(manifest).extensions.map { it.point.name }.toSet())
    }
    @Test fun mismatchedExtensionsAndDuplicateIdsFailBeforeInstallation() {
        val manifest = manifest()
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(manifest.copy(plugin = manifest.plugin!!.copy(extensions = emptyList()))) }
        val spec = manifest.plugin!!
        val duplicate = spec.harness!!.copy(nodes = listOf(PackageCallable("echo", "Duplicate", schema)))
        assertThrows(IllegalArgumentException::class.java) { PackageHarnessPolicy.validate(manifest.copy(plugin = spec.copy(harness = duplicate))) }
    }
    @Test fun arbitraryCommandsAndUndeclaredEntriesAreRejected() {
        val manifest = manifest()
        val spec = manifest.plugin!!
        listOf("sh -c evil", "../sample", "/usr/bin/python3", "missing").forEach { entry ->
            assertThrows(IllegalArgumentException::class.java) { PackageHarnessPolicy.validate(manifest.copy(plugin = spec.copy(harness = spec.harness!!.copy(entry = entry)))) }
        }
    }
    @Test fun unsupportedSchemasAndProviderProtocolsAreRejected() {
        val manifest = manifest()
        val spec = manifest.plugin!!
        val harness = spec.harness!!
        assertThrows(IllegalArgumentException::class.java) { PackageHarnessPolicy.validate(manifest.copy(plugin = spec.copy(harness = harness.copy(tools = listOf(PackageCallable("echo", "Invalid", buildJsonObject { put("type", "object"); put("$" + "ref", "remote") })))))) }
        assertThrows(IllegalArgumentException::class.java) { PackageHarnessPolicy.validate(manifest.copy(plugin = spec.copy(harness = harness.copy(providers = listOf(PackageProvider("offline", "provider", "UNKNOWN")))))) }
    }
    @Test fun webPackagesCannotGainProcessCallbacks() {
        val manifest = manifest()
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(manifest.copy(plugin = manifest.plugin!!.copy(execution = "WEB"))) }
    }
}
