package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ToolStatusPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()

    @Test fun defaultsToIconsAndRetainsTheChoiceAfterReopeningStorage() = runBlocking {
        val file = File(temporary.root, "appearance.preferences_pb")
        val firstJob = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(firstJob + Dispatchers.IO), produceFile = { file })
            val preferences = ToolStatusPreferences(store)
            assertFalse(preferences.classicDots.first())
            preferences.setClassicDots(true)
        } finally { firstJob.cancelAndJoin() }

        val secondJob = SupervisorJob()
        try {
            val store = PreferenceDataStoreFactory.create(scope = CoroutineScope(secondJob + Dispatchers.IO), produceFile = { file })
            val preferences = ToolStatusPreferences(store)
            assertTrue(preferences.classicDots.first())
            preferences.setClassicDots(false)
            assertFalse(preferences.classicDots.first())
        } finally { secondJob.cancelAndJoin() }
    }
}
