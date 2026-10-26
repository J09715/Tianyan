package top.tianyan.app.runtime

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.datastore.RuntimePreferences
import top.tianyan.app.core.datastore.SettingsDataStore
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.runtime.environment.ExecutionEnvironment
import top.tianyan.app.runtime.environment.ExecutionEnvironmentContext
import top.tianyan.app.runtime.environment.ExecutionEnvironmentId
import top.tianyan.app.runtime.environment.ExecutionFiles
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SshSettingsEnvironmentTest {
    private val active = MutableStateFlow("ubuntu")
    private val targets = mutableListOf<String>()

    @Test fun `snapshot follows variables and an ssh context does not refresh the phone runtime`() = runBlocking {
        val manager = manager()
        assertEquals("secret-ubuntu", manager.snapshotForSsh().getValue("TOKEN"))
        assertEquals(listOf("TOKEN"), manager.variables.value.map { it.key })
        val loaded = targets.size
        assertTrue(loaded > 0)
        active.value = "debian"
        val ssh = ExecutionEnvironmentContext(SshBoundWorld()) { _ -> AutoCloseable { } }
        withContext(ssh) {
            assertEquals("secret-ubuntu", manager.snapshotForSsh().getValue("TOKEN"))
        }
        assertEquals(loaded, targets.size)
        assertEquals("secret-debian", manager.snapshotForSsh().getValue("TOKEN"))
        assertTrue(targets.size > loaded)
        assertEquals(listOf("TOKEN"), manager.variables.value.map { it.key })
    }

    private fun manager(): LinuxEnvironmentManager {
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) { _, method, args ->
            when (method.name) {
                "getActiveDistroId" -> active
                "execute" -> {
                    val distro = args[1] as String
                    targets += distro
                    val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("secret-$distro".toByteArray())
                    CommandResult(0, "# TIANYAN_ENV_V1|id|TOKEN|$encoded|-|0\n", "", 0)
                }
                else -> error("Unexpected local runtime call: ${method.name}")
            }
        } as LinuxRuntime
        val settings = SettingsDataStore(ApplicationProvider.getApplicationContext<Context>(), SecretManager())
        return LinuxEnvironmentManager(runtime, RuntimePreferences(settings))
    }

    private class SshBoundWorld : ExecutionEnvironment {
        override val id = ExecutionEnvironmentId("ssh", "linux", "/workspace", "owner")
        override val files: ExecutionFiles get() = error("unused")
        override fun workingDirectory(explicit: String?) = id.workspace
        override suspend fun execute(command: ShellCommand) = error("unused")
        override suspend fun startBackground(id: String, command: ShellCommand): ManagedProcess = error("unused")
        override suspend fun stopBackground(id: String) = false
        override fun listBackground() = emptyList<ManagedProcess>()
        override fun getBackgroundLogs(id: String) = emptyList<String>()
        override suspend fun openSession(config: SessionConfig) = error("unused")
        override suspend fun openTerminal(config: SessionConfig) = error("unused")
        override suspend fun close() = Unit
    }
}
