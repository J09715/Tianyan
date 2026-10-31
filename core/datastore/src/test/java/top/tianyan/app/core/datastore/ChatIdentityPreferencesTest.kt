package top.tianyan.app.core.datastore

import androidx.datastore.preferences.core.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.core.model.ChatIdentityStyle

class ChatIdentityPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()
    @Test fun identityPersistsAndResetDoesNotChangeOtherPreferences() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val store = PreferenceDataStoreFactory.create(scope = scope, produceFile = { File(temporary.root, "identity.preferences_pb") })
            val preferences = ChatIdentityPreferences(store)
            val image = "12345678-1234-1234-1234-123456789abc.img"
            preferences.setName("  自定义  ")
            preferences.setImage(image)
            assertEquals(ChatIdentityStyle("自定义", image), ChatIdentityPreferences(store).style.first())
            store.edit { it[stringPreferencesKey("other")] = "kept" }
            preferences.reset()
            assertEquals(ChatIdentityStyle(), preferences.style.first())
            assertEquals("kept", store.data.first()[stringPreferencesKey("other")])
            try { preferences.setImage("../private"); fail("Traversal was accepted") }
            catch (_: IllegalArgumentException) { }
            store.edit { it[stringPreferencesKey("chat_identity_image")] = "../private" }
            assertNull(preferences.style.first().imageName)
        } finally { scope.cancel() }
    }
    @Test fun nameNormalizationPreservesUnicodeAndDefaults() {
        assertEquals("天衍", ChatIdentityStyle("  ").normalized().name)
        val emoji = String(Character.toChars(0x1F430))
        assertEquals(emoji.repeat(40), ChatIdentityStyle(emoji.repeat(41)).normalized().name)
        assertEquals("a b", ChatIdentityStyle("a\nb").normalized().name)
    }
}
