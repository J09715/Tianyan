package top.tianyan.app.harness.bot.memory

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.common.logging.AppLogger
import top.tianyan.app.runtime.ElfInspector
import top.tianyan.app.runtime.RuntimePathManager
import top.tianyan.app.runtime.rootfs.RootfsValidator

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BotMemoryManagerTest {
    private val context = ApplicationProvider.getApplicationContext<Context>().also { it.applicationInfo.nativeLibraryDir = it.cacheDir.absolutePath }
    private val manager = BotMemoryManager(RuntimePathManager(context, RootfsValidator(ElfInspector())), AppLogger(context) { it })

    @Test fun `concurrent group writes keep separate commits without losing files`() = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            manager.initRepo(id, "Test")
            (1..16).map { index -> async {
                manager.writeMemory(id, "knowledge/$index.md", "fact $index", "memory $index")
            } }.awaitAll()
            (1..16).forEach { assertEquals("fact $it", manager.readMemory(id, "knowledge/$it.md")) }
            val history = manager.getCommitHistory(id, 30)
            assertEquals(17, history.size)
            assertTrue(history.take(16).all { it.filesChanged.size == 1 })
        } finally { manager.deleteMemory(id) }
    }

    @Test fun `large persona cannot crowd preferences and notes out of context`() = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            manager.initRepo(id, "Test")
            manager.writeMemory(id, "persona.md", "p".repeat(20_000), "persona")
            manager.writeMemory(id, "preferences.md", "important preference", "preference")
            manager.writeMemory(id, "dialogue_notes.md", "important note", "note")
            val context = manager.assembleContext(id)
            assertTrue(context.length <= 6000)
            assertTrue(context.contains("important preference"))
            assertTrue(context.contains("important note"))
        } finally { manager.deleteMemory(id) }
    }

    @Test fun `unchanged content does not create a misleading commit`() = runBlocking {
        val id = UUID.randomUUID().toString()
        try {
            manager.initRepo(id, "Test")
            assertNotNull(manager.writeMemory(id, "preferences.md", "same", "first"))
            assertNull(manager.writeMemory(id, "preferences.md", "same", "second"))
            assertEquals(2, manager.getCommitHistory(id).size)
        } finally { manager.deleteMemory(id) }
    }
}
