package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


class McpClientPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()
    @Test fun idlePolicyPersistsAndBoundsImportedValues() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "mcp.preferences_pb") })
            val prefs = McpClientPreferences(store)
            assertEquals(10, prefs.idleTimeoutMinutes.first())
            prefs.setIdleTimeoutMinutes(3)
            assertEquals(3, McpClientPreferences(store).idleTimeoutMinutes.first())
            prefs.setIdleTimeoutMinutes(-1)
            assertEquals(1, prefs.idleTimeoutMinutes.first())
            prefs.setIdleTimeoutMinutes(Int.MAX_VALUE)
            assertEquals(60, prefs.idleTimeoutMinutes.first())
        } finally { scope.cancel() }
    }
}
