package top.tianyan.app.harness.environment

import top.tianyan.app.harness.core.OwnedResource
import top.tianyan.app.harness.core.acquire
import top.tianyan.app.harness.core.SessionResourceScope
import top.tianyan.app.runtime.shell.LinuxSession

/** Closing a subprocess also revokes its cleanup registration, releasing buffers after MCP reconnect. */
internal class OwnedLinuxSession private constructor(private val resource: OwnedResource<LinuxSession>) :
    LinuxSession by resource.value {
    override suspend fun close() = resource.close()

    companion object {
        suspend fun open(resources: SessionResourceScope, open: suspend () -> LinuxSession): LinuxSession =
            OwnedLinuxSession(resources.acquire(open) { it.close() })
    }
}
