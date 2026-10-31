package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder


class ChatBackgroundPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()

    @Test fun backgroundAndAdjustmentsPersistAndRemovalKeepsTheChosenStyle() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "background.preferences_pb") })
            val preferences = ChatBackgroundPreferences(store)
            assertNull(preferences.style.first().imageName)
            val image = "12345678-1234-1234-1234-123456789abc.img"
            preferences.setImage(image)
            preferences.setVisibility(0.82f)
            preferences.setBlur(6f)
            val restored = ChatBackgroundPreferences(store).style.first()
            assertEquals(image, restored.imageName)
            assertEquals(0.82f, restored.visibility, 0f)
            assertEquals(6f, restored.blurDp, 0f)
            preferences.setImage(null)
            assertNull(preferences.style.first().imageName)
            assertEquals(0.82f, preferences.style.first().visibility, 0f)
        } finally { scope.cancel() }
    }

    @Test fun invalidImportedPreferencesCannotEscapeTheImageDirectoryOrBreakRendering() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "corrupt.preferences_pb") })
            store.edit {
                it[stringPreferencesKey("chat_background_image")] = "../../other.png"
                it[floatPreferencesKey("chat_background_visibility")] = Float.NaN
                it[floatPreferencesKey("chat_background_blur_dp")] = Float.POSITIVE_INFINITY
            }
            val preferences = ChatBackgroundPreferences(store)
            val restored = preferences.style.first()
            assertNull(restored.imageName)
            assertEquals(0.65f, restored.visibility, 0f)
            assertEquals(0f, restored.blurDp, 0f)
            preferences.setVisibility(-1f)
            preferences.setBlur(100f)
            assertEquals(0f, preferences.style.first().visibility, 0f)
            assertEquals(24f, preferences.style.first().blurDp, 0f)
            try { preferences.setImage("../private"); fail("Traversal name was accepted") }
            catch (_: IllegalArgumentException) { }
        } finally { scope.cancel() }
    }
}
