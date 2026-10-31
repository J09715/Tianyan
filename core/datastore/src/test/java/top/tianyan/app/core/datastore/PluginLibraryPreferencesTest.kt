package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.PluginLibraryChannel

class PluginLibraryPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()
    @Test fun channelSelectionSurvivesStoreRecreation() = runBlocking {
        val file = File(temporary.root, "library.preferences_pb")
        val firstJob = SupervisorJob()
        val preferences = PluginLibraryPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file }))
        assertEquals(PluginLibraryChannel.GITHUB, preferences.channel())
        preferences.select(PluginLibraryChannel.GITEE)
        firstJob.cancelAndJoin()
        val secondJob = SupervisorJob()
        try {
            val restored = PluginLibraryPreferences(PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file }))
            assertEquals(PluginLibraryChannel.GITEE, restored.channel())
        } finally { secondJob.cancelAndJoin() }
    }
}
