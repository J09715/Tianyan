package top.tianyan.app.ui.settings

import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.database.InstallLogEntity
import top.tianyan.app.plugin.packages.PackagedPluginEntry
import top.tianyan.app.plugin.packages.PluginPackageProtocol

class PackagedPluginDiagnosticsReportTest {
    @Test
    fun reportIncludesManifestDeclarationsAndRecentLogs() {
        val manifest = PluginPackageProtocol.decode(MANIFEST)
        val entry = PackagedPluginEntry(
            descriptor = PluginPackageProtocol.descriptor(manifest),
            toolId = manifest.id,
            name = manifest.name,
            source = manifest.source,
            installedVersion = "0.9.0",
            state = "UPDATE_AVAILABLE",
            manifest = manifest,
        )

        val report = buildPluginDiagnostics(entry, listOf(
            InstallLogEntity(toolId = manifest.id, event = "STARTED", message = "开始安装"),
            InstallLogEntity(toolId = manifest.id, event = "FAILED", message = "verify failed"),
        ))

        assertTrue(report.contains("插件 ID: example.sample"))
        assertTrue(report.contains("安装版本: 0.9.0"))
        assertTrue(report.contains("状态: UPDATE_AVAILABLE"))
        assertTrue(report.contains("能力: NETWORK:example.com"))
        assertTrue(report.contains("依赖: example.sdk [1.0.0, 2.0.0)"))
        assertTrue(report.contains("资源:"))
        assertTrue(report.contains("example.data /opt/tianyan/data/sample-plugin"))
        assertTrue(report.contains("[FAILED] verify failed"))
    }

    @Test
    fun legacyManifestDoesNotRequirePluginSpec() {
        val manifest = PluginPackageProtocol.decode(
            """{"schemaVersion":1,"id":"legacy-tool","name":"Legacy","description":"Legacy tool","version":"1.0.0","launchCommand":"legacy","commandLinks":["legacy"]}""",
        )
        val entry = PackagedPluginEntry(
            descriptor = PluginPackageProtocol.descriptor(manifest),
            toolId = manifest.id,
            name = manifest.name,
            source = manifest.source,
            installedVersion = null,
            state = "AVAILABLE",
            manifest = manifest,
        )

        val report = buildPluginDiagnostics(entry, emptyList())

        assertTrue(report.contains("执行形态: LEGACY"))
        assertTrue(report.contains("命令入口: legacy"))
        assertTrue(report.contains("(无)"))
    }

    private companion object {
        const val MANIFEST = """
        {
          "schemaVersion": 2,
          "id": "sample-plugin",
          "name": "Sample",
          "description": "Sample plugin",
          "version": "1.0.0",
          "source": "LOCAL",
          "offlineOnly": true,
          "installMethod": "LOCAL_PACKAGE",
          "installSteps": [],
          "plugin": {
            "id": "example.sample",
            "api": {"minimum": 1, "maximum": 1},
            "execution": "PROCESS",
            "extensions": [],
            "dependencies": [{"id": "example.sdk", "minimumVersion": "1.0.0", "maximumVersionExclusive": "2.0.0"}],
            "capabilities": [{"name": "NETWORK", "target": "example.com"}],
            "resources": [{"key": "example.data", "path": "/opt/tianyan/data/sample-plugin", "sharing": "EXCLUSIVE", "removal": "RETAIN"}],
            "integrity": {}
          }
        }
        """
    }
}
