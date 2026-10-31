package top.tianyan.app.harness.core

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ResourceDisposalTest {
    @Test fun scopeReleaseCannotRecursivelyCloseScopeAndCanBeRetried() = runTest {
        val scope = SessionResourceScope()
        var recursive = true
        var attempts = 0
        scope.own { attempts++; if (recursive) scope.close() }
        val error = runCatching { scope.close() }.exceptionOrNull() as ResourceCleanupException
        assertTrue(error.failures.single().message!!.contains("recursively"))
        recursive = false
        scope.close()
        scope.close()
        assertEquals(2, attempts)
    }
    @Test fun ownedResourceCannotRecursivelyCloseItself() = runTest {
        val scope = SessionResourceScope()
        lateinit var owned: OwnedResource<Any>
        var recursive = true
        var attempts = 0
        owned = scope.acquire({ Any() }) { attempts++; if (recursive) owned.close() }
        assertTrue(runCatching { owned.close() }.exceptionOrNull()!!.message!!.contains("recursively"))
        recursive = false
        scope.close()
        assertEquals(2, attempts)
    }
    @Test fun nestedResourceReleaseCannotCycleBackToAncestorScope() = runTest {
        val parent = SessionResourceScope()
        val child = SessionResourceScope()
        var cyclic = true
        parent.own { child.close() }
        child.own { if (cyclic) parent.close() }
        assertTrue(runCatching { parent.close() }.exceptionOrNull() is ResourceCleanupException)
        cyclic = false
        parent.close()
    }
    @Test fun resourceCallbackCannotCloseAnOwnerThatReleasesTheSameResource() = runTest {
        val scope = SessionResourceScope()
        var recursive = true
        val owned = scope.acquire({ Any() }) { if (recursive) scope.close() }
        assertTrue(runCatching { owned.close() }.exceptionOrNull() is ResourceCleanupException)
        recursive = false
        scope.close()
    }
    @Test fun failedOwnershipDetachmentDoesNotRepeatSuccessfulRelease() = runTest {
        var releases = 0
        var detaches = 0
        val owned = OwnedResource<Any> { releases++ }.also { it.acquired(Any()) }
        owned.ownership = AutoCloseable { if (++detaches == 1) error("detach failed") }
        assertNotNull(runCatching { owned.close() }.exceptionOrNull())
        owned.close()
        owned.close()
        assertEquals(1, releases)
        assertEquals(2, detaches)
    }
}
