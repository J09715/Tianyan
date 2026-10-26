package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


class PluginPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()

    @Test fun selectionSurvivesStoreRecreationAndOnlyChangesRequestedIdentity() = runBlocking {
        val file = File(temporary.root, "plugins.preferences_pb")
        val firstJob = SupervisorJob()
        val firstStore = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
        val first = PluginPreferences(firstStore)
        assertTrue(first.disabledPlugins().isEmpty())
        first.setEnabled("tianyan.terminal", false)
        first.setEnabled("tianyan.browser", false)
        first.setEnabled("tianyan.browser", true)
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val second = PluginPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file }))
            assertEquals(setOf("tianyan.terminal"), second.disabledPlugins())
            second.setEnabled("tianyan.terminal", true)
            assertTrue(second.disabledPlugins().isEmpty())
        } finally { secondJob.cancelAndJoin() }
    }
}
