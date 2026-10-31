package top.tianyan.app.harness.mcp

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import top.tianyan.app.harness.environment.FakeWorld
import top.tianyan.app.harness.environment.SessionExecutionEnvironments
import top.tianyan.app.runtime.environment.*

class McpMetadataScopesTest {
    @Test fun `late settings result cannot restore obsolete metadata or replace discovery lock`() = runBlocking {
        val retained = mutableMapOf<String, String>()
        val metadata = McpMetadataScopes { key, _ -> retained.remove(key); Unit }
        val first = "mcp@environment:first"
        val second = "mcp@environment:second"
        metadata.register(first)
        retained[first] = "first schema"
        metadata.register(second)
        assertFalse(retained.containsKey(first))
        retained[first] = "late schema"
        metadata.retain(first) { fail("obsolete status must not publish") }
        metadata.retain(second) { retained[second] = "current schema" }
        assertEquals(mapOf(second to "current schema"), retained)
        assertEquals(metadata.mutexKey(first), metadata.mutexKey(second))
    }

    @Test fun `owned metadata is retained independently and released once on close`() = runBlocking {
        val releases = mutableListOf<Pair<String, Boolean>>()
        val metadata = McpMetadataScopes { key, owned -> releases += key to owned }
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("ssh", "ssh:host", workspace, owner))
        })
        val first = "mcp@environment:first"
        val second = "mcp@environment:second"
        registry.activity("one", "/srv/demo") { metadata.register(first); metadata.register(first) }
        registry.activity("two", "/srv/demo") { metadata.register(second) }
        assertEquals(first, metadata.mutexKey(first))
        registry.closeSession("one")
        assertEquals(listOf(first to true), releases)
        var published = false
        metadata.retain(second) { published = true }
        assertTrue(published)
        registry.closeSession("two")
        assertEquals(listOf(first to true, second to true), releases)
    }
}
