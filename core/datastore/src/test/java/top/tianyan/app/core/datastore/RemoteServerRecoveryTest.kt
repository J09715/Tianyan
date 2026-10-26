package top.tianyan.app.core.datastore

import android.util.AtomicFile
import java.io.File
import kotlinx.coroutines.runBlocking
import top.tianyan.app.core.datastore.RequiresAtomicRenameOverwrite
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RemoteServerRecoveryTest {
    @get:Rule val folder = TemporaryFolder()
    @get:Rule val renameGuard = RequiresAtomicRenameOverwrite()
    @Test fun `corrupt archive blocks execution until explicit local recovery preserves original`() = runBlocking {
        val original = File(folder.root, "remote-servers.enc").apply { writeText("invalid ciphertext") }
        fun repository() = EncryptedRemoteServerRepository(AtomicFile(original), { "test:$it" }, { it.removePrefix("test:").takeIf { _ -> it.startsWith("test:") } })
        val broken = repository()
        assertEquals(REMOTE_CONFIGURATION_UNAVAILABLE, broken.activeId.value)
        assertNotNull(broken.configurationError.value)
        assertEquals("invalid ciphertext", original.readText())
        try { broken.select("other"); fail("implicit recovery allowed") } catch (_: IllegalStateException) { }
        broken.select(null)
        assertNull(broken.activeId.value); assertNull(broken.configurationError.value)
        val preserved = folder.root.listFiles()!!.single { it.name.contains(".unreadable-") }
        assertEquals("invalid ciphertext", preserved.readText())
        val reopened = repository()
        assertNull(reopened.activeId.value); assertNull(reopened.configurationError.value)
    }
}
