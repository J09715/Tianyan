package top.tianyan.app.core.tools

import android.net.Uri
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.model.ToolManifest

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [35])
class LocalPluginImportAdmissionTest {
    @Test fun activeJobOrUnfinishedImportRejectsNewDebugSnapshot() {
        val pending = LocalPluginImportState.PendingConfirmation(Uri.EMPTY, "sample.txplugin",
            ToolManifest(id = "sample", name = "Sample", description = "Test"), 0L)
        val states = listOf(
            LocalPluginImportState.Reading("sample.txplugin"), pending,
            LocalPluginImportState.Importing("sample.txplugin", null, 0L, null, null),
        )
        states.forEach { state ->
            assertThrows(IllegalStateException::class.java) { requireLocalPluginImportAvailable(state, false) }
        }
        assertThrows(IllegalStateException::class.java) {
            requireLocalPluginImportAvailable(LocalPluginImportState.Failed("Retry later"), true)
        }
    }

    @Test fun completedFailedAndCancelledImportsAllowRetryOnceJobEnds() {
        listOf(LocalPluginImportState.Idle, LocalPluginImportState.Failed("Try again"),
            LocalPluginImportState.Succeeded("Sample", "1.0.0"),
            LocalPluginImportState.AlreadyImported("Sample", "1.0.0"),
        ).forEach { requireLocalPluginImportAvailable(it, false) }
    }
}
