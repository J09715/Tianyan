package top.tianyan.app.core.model

import org.junit.Assert.*
import org.junit.Test

class McpInstallationTest {
    private val preset = McpCatalog.installable.first().serverPreset()
    private val installed = setOf(preset.installComponentId)

    @Test fun everyInstallableCapabilityHasAServiceDefinition() {
        assertEquals(McpCatalog.installable.map { it.id }.toSet(),
            BuiltinMcpPresets.presets.filter { it.requiresInstall }.map { it.id }.toSet())
        assertEquals(5, McpCatalog.installable.size)
    }

    @Test fun installationEnablesDefaultPreferenceIncludingAfterRestart() {
        assertTrue(preset.withInstalledComponents(installed).isEnabled)
        assertTrue(preset.copy(isEnabled = true).withInstalledComponents(installed).isEnabled)
        assertFalse(preset.withInstalledComponents(emptySet()).isEnabled)
    }

    @Test fun removalBlocksExplicitEnablementWithoutDiscardingPreference() {
        val enabled = preset.copy(isEnabled = true, userToggled = true)
        assertFalse(enabled.withInstalledComponents(emptySet()).isEnabled)
        assertTrue(enabled.isEnabled)
        assertTrue(enabled.withInstalledComponents(installed).isEnabled)
    }

    @Test fun explicitDisablementSurvivesInstallation() {
        val disabled = preset.copy(userToggled = true)
        val resolved = disabled.withInstalledComponents(installed)
        assertFalse(resolved.isEnabled)
        assertEquals(true, resolved.installationReady)
    }

    @Test fun unknownReadinessFailsClosedAndCustomServersAreUnaffected() {
        assertFalse(preset.copy(isEnabled = true).withInstalledComponents(null).isEnabled)
        val custom = McpServerConfig("custom", "custom", isEnabled = true)
        assertEquals(custom, custom.withInstalledComponents(emptySet()))
    }
}
