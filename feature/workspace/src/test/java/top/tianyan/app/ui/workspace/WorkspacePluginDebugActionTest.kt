package top.tianyan.app.ui.workspace

import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import top.tianyan.app.runtime.WorkspaceProject

@OptIn(ExperimentalCoroutinesApi::class)
class WorkspacePluginDebugActionTest {
    @get:Rule val temp = TemporaryFolder()
    private fun project() = WorkspaceProject("sample", temp.root.path, "/workspace/sample", 0L)

    @Test fun repeatedClickPackagesOnceAndHandsOffBeforeOpeningToolCenter() = runTest {
        val archive = temp.newFile("snapshot.txplugin")
        var packages = 0
        val imports = mutableListOf<File>()
        val action = WorkspacePluginDebugAction(this, {}, { imports += it }, { root, _ ->
            assertEquals(temp.root, root)
            packages++
            archive
        }, StandardTestDispatcher(testScheduler))
        action.start(project())
        action.start(project())
        assertTrue(action.busy)
        runCurrent()
        assertEquals(1, packages)
        assertEquals(listOf(archive), imports)
        assertTrue(action.ready)
        assertFalse(action.busy)
        assertTrue(archive.exists())
    }

    @Test fun cancellationAtWorkerReturnDeletesUnclaimedSnapshot() = runTest {
        val archive = temp.newFile("cancelled.txplugin")
        var imports = 0
        lateinit var action: WorkspacePluginDebugAction
        action = WorkspacePluginDebugAction(this, {}, { imports++ }, { _, _ ->
            action.cancel()
            archive
        }, StandardTestDispatcher(testScheduler))
        action.start(project())
        runCurrent()
        assertEquals(0, imports)
        assertFalse(archive.exists())
        assertFalse(action.busy)
        assertFalse(action.ready)
        assertNull(action.error)
    }

    @Test fun cancellationBeforeCoroutineStartsAlsoClearsBusy() = runTest {
        var packages = 0
        val action = WorkspacePluginDebugAction(this, {}, { fail("Must not import") },
            { _, _ -> packages++; temp.newFile() }, StandardTestDispatcher(testScheduler))
        action.start(project())
        action.cancel()
        runCurrent()
        assertEquals(0, packages)
        assertFalse(action.busy)
        assertFalse(action.ready)
    }

    @Test fun rejectedHandoffCleansSnapshotAndAllowsRetry() = runTest {
        val first = temp.newFile("rejected.txplugin")
        val second = temp.newFile("retry.txplugin")
        var available = false
        var packages = 0
        val imports = mutableListOf<File>()
        val action = WorkspacePluginDebugAction(this, {}, {
            check(available) { "Another import is awaiting confirmation" }
            imports += it
        }, { _, _ -> if (++packages == 1) first else second }, StandardTestDispatcher(testScheduler))
        action.start(project())
        runCurrent()
        assertFalse(first.exists())
        assertFalse(action.busy)
        assertFalse(action.ready)
        assertNotNull(action.error)
        action.error = null
        available = true
        action.start(project())
        runCurrent()
        assertEquals(listOf(second), imports)
        assertTrue(action.ready)
        assertTrue(second.exists())
    }

    @Test fun existingPendingImportPreventsPackaging() = runTest {
        var packages = 0
        val action = WorkspacePluginDebugAction(this,
            { error("An import is awaiting confirmation") },
            { fail("Must not import") },
            { _, _ -> packages++; temp.newFile() }, StandardTestDispatcher(testScheduler))
        action.start(project())
        runCurrent()
        assertEquals(0, packages)
        assertFalse(action.busy)
        assertNotNull(action.error)
    }

    @Test fun packagingFailureResetsBusyWithoutOpeningToolCenter() = runTest {
        val action = WorkspacePluginDebugAction(this, {}, { fail("Must not import") },
            { _, _ -> error("Invalid manifest") }, StandardTestDispatcher(testScheduler))
        action.start(project())
        runCurrent()
        assertEquals("Invalid manifest", action.error)
        assertFalse(action.busy)
        assertFalse(action.ready)
    }
}
