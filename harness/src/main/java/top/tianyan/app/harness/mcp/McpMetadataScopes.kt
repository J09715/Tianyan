package top.tianyan.app.harness.mcp

import kotlinx.coroutines.currentCoroutineContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext

/** Owned metadata dies with its world; settings keep only the current scope for each server. */
internal class McpMetadataScopes(private val release: (key: String, owned: Boolean) -> Unit) {
    private val lock = Any()
    private val owners = mutableMapOf<String, Any>()
    private val standalone = mutableMapOf<String, String>()
    suspend fun register(key: String) {
        val binding = currentCoroutineContext()[ExecutionEnvironmentContext]
        synchronized(lock) {
            if (binding == null) {
                standalone.put(key.mcpServerKey(), key)?.takeIf { it != key }?.let { release(it, false) }
            } else if (key !in owners) {
                val token = Any()
                owners[key] = token
                try {
                    binding.own {
                        synchronized(lock) {
                            if (owners[key] === token) { owners.remove(key); release(key, true) }
                        }
                    }
                } catch (e: Throwable) { owners.remove(key); release(key, true); throw e }
            }
        }
    }
    fun mutexKey(key: String): String = synchronized(lock) { if (key in owners) key else key.mcpServerKey() }
    fun retain(key: String, publish: () -> Unit) = synchronized(lock) {
        if (key in owners || standalone[key.mcpServerKey()] == key) publish()
        else release(key, false)
    }
}
