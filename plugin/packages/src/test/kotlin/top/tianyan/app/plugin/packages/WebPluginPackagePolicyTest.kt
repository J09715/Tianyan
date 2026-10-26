package top.tianyan.app.plugin.packages

import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.core.model.*
import top.tianyan.app.plugin.api.PluginExtensionPoint

class WebPluginPackagePolicyTest {
    @Test fun omittedSourceRemainsCompatibleAndFixedProotSourceCanRoundTrip() {
        val original = web()
        val json = kotlinx.serialization.json.Json.encodeToString(ToolManifest.serializer(), original)
        assertFalse(json.contains("webSource"))
        assertEquals("PACKAGE", PluginPackageProtocol.decode(json).plugin!!.webSource)
        val proot = original.copy(plugin = original.plugin!!.copy(webSource = "PROOT_STATIC"))
        assertEquals(proot, PluginPackageProtocol.decode(kotlinx.serialization.json.Json.encodeToString(ToolManifest.serializer(), proot)))
        assertEquals(PluginExtensionPoint.PAGE, PluginPackageProtocol.descriptor(proot).extensions.single().point)
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.receipt(proot) }
    }

    @Test fun arbitrarySourcesAndProcessServiceSourceDeclarationsAreRejected() {
        val original = web()
        listOf("", "proot_static", "http://127.0.0.1:8000", "PROCESS", "python3 -m http.server").forEach { source ->
            assertThrows(IllegalArgumentException::class.java) {
                PluginPackageProtocol.validate(original.copy(plugin = original.plugin!!.copy(webSource = source)))
            }
        }
        val process = original.copy(plugin = original.plugin!!.copy(execution = "PROCESS", webSource = "PROOT_STATIC",
            extensions = emptyList(), capabilities = emptyList()))
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(process) }
    }

    @Test fun prootSourceDoesNotAllowPackageCodePortsEnvironmentOrNewCapabilities() {
        val original = web().let { it.copy(plugin = it.plugin!!.copy(webSource = "PROOT_STATIC")) }
        listOf(original.copy(installSteps = listOf("id")), original.copy(launchCommand = "python3 app.py"),
            original.copy(servicePort = 9000), original.copy(environment = mapOf("TOKEN" to "secret")),
            original.copy(plugin = original.plugin!!.copy(capabilities = listOf(PackageCapability("SHELL", "*")))),
        ).forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(candidate) } }
    }

    @Test fun clipboardIsExplicitAndExactAndDoesNotGrantReadAccess() {
        val web = web()
        val spec = web.plugin!!
        PluginPackageProtocol.validate(web.copy(plugin = spec.copy(capabilities = listOf(WebPluginPackagePolicy.CLIPBOARD_WRITE))))
        listOf(PackageCapability("CLIPBOARD_WRITE", "*"), PackageCapability("CLIPBOARD_READ", "clipboard"),
            PackageCapability("CLIPBOARD_WRITE", "application")).forEach {
            assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web.copy(plugin = spec.copy(capabilities = listOf(it)))) }
        }
    }
    @Test fun structuredHostActionsAreAdmittedOnlyWithTheirExactTargets() {
        val web = web()
        val spec = web.plugin!!
        PluginPackageProtocol.validate(web.copy(plugin = spec.copy(capabilities = listOf(
            WebPluginPackagePolicy.APP_INFO, WebPluginPackagePolicy.CLIPBOARD_WRITE,
            WebPluginPackagePolicy.INSTALL_APK, WebPluginPackagePolicy.LOGCAT,
        ))))
        listOf(
            PackageCapability("INSTALL_APK", "*"), PackageCapability("INSTALL_APK", "application"),
            PackageCapability("LOGCAT", "clipboard"), PackageCapability("LOGCAT", "*"),
            PackageCapability("SHELL", "*"),
        ).forEach {
            assertThrows(IllegalArgumentException::class.java) {
                PluginPackageProtocol.validate(web.copy(plugin = spec.copy(capabilities = listOf(it))))
            }
        }
    }

    @Test fun serviceSourcesReferenceExactlyOneInstalledToolIdAndCarryNoPayload() {
        val bare = web().let { it.copy(plugin = it.plugin!!.copy(integrity = emptyMap())) }
        listOf("TOOL_SERVICE", "PROOT_SERVICE").forEach { source ->
            PluginPackageProtocol.validate(bare.copy(plugin = bare.plugin!!.copy(webSource = source, serviceToolId = "web-tool")))
        }
        val spec = bare.plugin!!
        listOf(
            spec.copy(webSource = "TOOL_SERVICE"),
            spec.copy(webSource = "PROOT_SERVICE", serviceToolId = "Bad_Id"),
            spec.copy(webSource = "PROOT_SERVICE", serviceToolId = ""),
            spec.copy(webSource = "PROOT_SERVICE", serviceToolId = "web-tool", integrity = mapOf("index.html" to "a".repeat(64))),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(bare.copy(plugin = candidate)) }
        }
        val plain = web().plugin!!
        listOf(
            plain.copy(serviceToolId = "web-tool"),
            plain.copy(webSource = "PROOT_STATIC", serviceToolId = "web-tool"),
        ).forEach { candidate ->
            assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web().copy(plugin = candidate)) }
        }
        val process = bare.copy(plugin = bare.plugin!!.copy(execution = "PROCESS", extensions = emptyList(), capabilities = emptyList(), serviceToolId = "web-tool"))
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(process) }
    }

    private fun web() = ToolManifest(schemaVersion = 2, id = "web-example", name = "Example", description = "Static", version = "1.0.0",
        source = "LOCAL", offlineOnly = true, installMethod = "LOCAL_PACKAGE",
        plugin = PluginPackageSpec("example.web", PackageApiRange(1, 1), "WEB", extensions = listOf(PackageExtension("main", "PAGE")),
            capabilities = listOf(WebPluginPackagePolicy.APP_INFO), integrity = mapOf("index.html" to "a".repeat(64))))

    @Test fun validWebPackageUsesPageExtensionAndNeverProducesProcessReceipt() {
        PluginPackageProtocol.validate(web())
        assertEquals(PluginExtensionPoint.PAGE, PluginPackageProtocol.descriptor(web()).extensions.single().point)
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.receipt(web()) }
    }

    @Test fun rejectsAllExecutableAndAmbientProcessConfiguration() {
        val web = web()
        listOf(web.copy(installSteps = listOf("id")), web.copy(uninstallSteps = listOf("id")),
            web.copy(launchCommand = "id"), web.copy(verifyCommand = "id"), web.copy(commandLinks = listOf("test")),
            web.copy(environment = mapOf("TOKEN" to "secret")), web.copy(dependencies = listOf("node")),
            web.copy(permissions = listOf("NETWORK")), web.copy(servicePort = 8000), web.copy(launchType = "service"),
        ).forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(candidate) } }
    }

    @Test fun rejectsNetworkShellGlobalResourcesAndUnimplementedDependencies() {
        val web = web()
        val spec = web.plugin!!
        listOf(spec.copy(capabilities = listOf(PackageCapability("NETWORK", "example.com"))),
            spec.copy(capabilities = listOf(PackageCapability("SHELL", "*"))),
            spec.copy(capabilities = listOf(PackageCapability("APP_INFO", "other"))),
            spec.copy(capabilities = listOf(WebPluginPackagePolicy.APP_INFO, WebPluginPackagePolicy.APP_INFO)),
            spec.copy(resources = listOf(PackageResource("example.data", "/opt/data"))),
            spec.copy(dependencies = listOf(PackageDependency("example.other", "1.0.0"))),
        ).forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web.copy(plugin = candidate)) } }
    }

    @Test fun requiresFixedEntryAndSafeBoundedAssetNames() {
        val web = web()
        val spec = web.plugin!!
        listOf("../secret", "%2e%2e/secret", "a//b", "theme.css", "a?query", "/index.html", "x/".repeat(17) + "a").forEach { path ->
            assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web.copy(plugin = spec.copy(integrity = spec.integrity + (path to "a".repeat(64))))) }
        }
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web.copy(plugin = spec.copy(integrity = emptyMap()))) }
        assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(web.copy(plugin = spec.copy(extensions = listOf(PackageExtension("main", "PROCESS"))))) }
    }

    @Test fun continuesToRequireCommonIdentitySemverSdkAndSha256Rules() {
        val web = web()
        listOf(web.copy(version = "latest"), web.copy(plugin = web.plugin!!.copy(id = "tianyan.external")),
            web.copy(plugin = web.plugin!!.copy(api = PackageApiRange(2, 2))),
            web.copy(plugin = web.plugin!!.copy(integrity = mapOf("index.html" to "invalid"))),
        ).forEach { candidate -> assertThrows(IllegalArgumentException::class.java) { PluginPackageProtocol.validate(candidate) } }
    }
}
