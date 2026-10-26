package top.tianyan.app.plugin.packages

import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.*

class PluginPackageProtocolTest {
    private fun manifest(id: String = "sample", version: String = "1.0.0", spec: PluginPackageSpec = PluginPackageSpec("example.$id", PackageApiRange(1, 1), "PROCESS")) =
        ToolManifest(schemaVersion = 2, id = id, name = id, description = "Offline tool", version = version,
            source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE", plugin = spec)
    private fun rejects(action: () -> Unit) { assertThrows(IllegalArgumentException::class.java, action) }

    @Test fun semanticVersionsFollowPrereleasePrecedenceWithoutOverflow() {
        val versions = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0", "1.9.0", "1.10.0", "999999999999999999999.0.0")
        versions.zipWithNext().forEach { (a, b) -> assertTrue("$a < $b", SemanticVersion.parse(a)!! < SemanticVersion.parse(b)!!) }
        assertEquals(0, SemanticVersion.parse("1.0.0+first")!!.compareTo(SemanticVersion.parse("1.0.0+second")!!))
    }
    @Test fun invalidSemanticVersionsAreRejected() {
        listOf("1", "1.2", "01.0.0", "1.0.0-01", "v1.0.0", "1.0.0-", "1.0.0+", "../1.0.0").forEach { assertNull(it, SemanticVersion.parse(it)) }
    }
    @Test fun legacyUnknownFieldsRemainCompatibleButV2IsStrict() {
        val legacy = PluginPackageProtocol.decode("""{"id":"sample","name":"Old","description":"Old","future":true}""")
        assertEquals("legacy.tool-sample", PluginPackageProtocol.identity(legacy))
        val encoded = Json.encodeToString(ToolManifest.serializer(), manifest())
        assertEquals(manifest(), PluginPackageProtocol.decode(encoded))
        rejects { PluginPackageProtocol.decode(encoded.dropLast(1) + ",\"future\":true}") }
    }
    @Test fun nativeLoadingReservedIdentityAndIncompatibleSdkAreRejected() {
        val original = manifest()
        val originalSpec = original.plugin!!
        listOf(originalSpec.copy(execution = "NATIVE"), originalSpec.copy(id = "tianyan.sample"),
            originalSpec.copy(id = "legacy.sample"), originalSpec.copy(api = PackageApiRange(2, 3))).forEach { spec ->
            rejects { PluginPackageProtocol.validate(original.copy(plugin = spec)) }
        }
        rejects { PluginPackageProtocol.validate(original.copy(uninstallSteps = listOf("rm -rf /opt/shared"))) }
    }
    @Test fun dependencyInstallationUpgradeAndRemovalUseInstalledReceipts() {
        val base = manifest("base")
        val consumer = manifest("consumer").let { it.copy(plugin = it.plugin!!.copy(dependencies = listOf(PackageDependency("example.base", "1.0.0", "2.0.0")))) }
        assertThrows(IllegalStateException::class.java) { PackageInstallPolicy.requireInstall(consumer, emptyList()) }
        PackageInstallPolicy.requireInstall(consumer, listOf(PluginPackageProtocol.receipt(base)))
        val installed = listOf(base, consumer).map(PluginPackageProtocol::receipt)
        rejects { PackageInstallPolicy.requireInstall(base.copy(version = "2.0.0"), installed) }
        rejects { PackageInstallPolicy.requireUninstall("base", installed) }
        PackageInstallPolicy.requireUninstall("consumer", installed)
    }
    @Test fun replacementCannotIntroduceDependencyCycle() {
        val base = manifest("base")
        val consumer = manifest("consumer").let { it.copy(plugin = it.plugin!!.copy(dependencies = listOf(PackageDependency("example.base", "1.0.0")))) }
        val cycle = base.copy(plugin = base.plugin!!.copy(dependencies = listOf(PackageDependency("example.consumer", "1.0.0"))))
        rejects { PackageInstallPolicy.requireInstall(cycle, listOf(base, consumer).map(PluginPackageProtocol::receipt)) }
    }
    @Test fun sharedResourceNeedsSameIdentityPathAndContent() {
        val resource = PackageResource("example.sdk", "/opt/shared-sdk", "SHARED", "a".repeat(64))
        fun owner(id: String, claim: PackageResource) = manifest(id).let { it.copy(plugin = it.plugin!!.copy(resources = listOf(claim))) }
        val installed = listOf(PluginPackageProtocol.receipt(owner("first", resource)))
        PackageInstallPolicy.requireInstall(owner("second", resource), installed)
        listOf(resource.copy(sharing = "EXCLUSIVE"), resource.copy(contentSha256 = "b".repeat(64)),
            resource.copy(path = "/opt/shared-sdk/subdir"), resource.copy(path = "/opt/elsewhere")).forEach {
            rejects { PackageInstallPolicy.requireInstall(owner("second", it), installed) }
        }
    }
    @Test fun ownershipCannotClaimHostOrAnotherPluginsDirectory() {
        val base = manifest()
        listOf("/", "/opt", "/opt/tianyan", "/opt/tianyan/tools/other", "/opt/test/../host").forEach { path ->
            rejects { PluginPackageProtocol.validate(base.copy(plugin = base.plugin!!.copy(resources = listOf(PackageResource("example.data", path))))) }
        }
    }
    @Test fun permissionDeltaIncludesLegacyRequestsAndTargetChanges() {
        val old = manifest().copy(permissions = listOf("NETWORK"))
        val next = old.copy(plugin = old.plugin!!.copy(capabilities = listOf(PackageCapability("NETWORK", "example.com"))))
        assertEquals(listOf("NETWORK:example.com"), PackageInstallPolicy.addedCapabilitiesFromReceipt(next, PluginPackageProtocol.receipt(old)))
    }
    @Test fun inventoryPreservesToolRoutingAndRejectsDuplicateIdentity() {
        val first = manifest()
        val entry = PackagedPluginCatalog.create(listOf(first), mapOf("sample" to "0.9.0"), mapOf("sample" to "UPDATE_AVAILABLE")).single()
        assertEquals("sample", entry.toolId)
        assertEquals("0.9.0", entry.installedVersion)
        rejects { PackagedPluginCatalog.create(listOf(first, first.copy(id = "other")), emptyMap(), emptyMap()) }
    }
    @Test fun frameworkCommandOwnershipAlsoProtectsLegacyTools() {
        val legacy = ToolManifest(id = "old-tool", name = "Old", description = "Old", commandLinks = listOf("shared-command"))
        rejects { PackageInstallPolicy.requireInstall(manifest().copy(commandLinks = listOf("shared-command")), listOf(PluginPackageProtocol.receipt(legacy))) }
        rejects { PluginPackageProtocol.validate(manifest().copy(commandLinks = listOf("../escape"))) }
    }
}
