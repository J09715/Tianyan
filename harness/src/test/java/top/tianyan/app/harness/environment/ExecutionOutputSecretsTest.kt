package top.tianyan.app.harness.environment

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.lang.reflect.Proxy
import java.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import top.tianyan.app.core.datastore.*
import top.tianyan.app.core.security.SecretManager
import top.tianyan.app.harness.HarnessPathResolver
import top.tianyan.app.harness.WorkspaceFileAccess
import top.tianyan.app.runtime.*
import top.tianyan.app.runtime.environment.*
import top.tianyan.app.runtime.shell.CommandResult

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ExecutionOutputSecretsTest {
    @Test fun `SSH authentication output is fully masked including short credentials`() = runBlocking {
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("ssh", "ssh:server", workspace, owner)).apply { secretValues += "abc" }
        })
        val output = redactExecutionOutput("echo abc", top.tianyan.app.core.security.SecretRedactor(), registry, null, null, "remote", "")
        assertEquals("echo [REDACTED]", output)
        registry.closeSession("remote")
    }
    @get:Rule val temporary = TemporaryFolder()
    private val active = MutableStateFlow("ubuntu")
    private val targets = mutableListOf<String>()
    private fun manager(root: java.io.File): Pair<LinuxRuntime, LinuxEnvironmentManager> {
        val runtime = Proxy.newProxyInstance(LinuxRuntime::class.java.classLoader, arrayOf(LinuxRuntime::class.java)) {
            _, method, args -> when (method.name) {
                "getActiveDistroId" -> active
                "workspacePath" -> root
                "execute" -> {
                    val distro = args[1] as String
                    targets += distro
                    if (distro == "broken") CommandResult(1, "", "unavailable", 0)
                    else {
                        val encoded = Base64.getUrlEncoder().withoutPadding().encodeToString("secret-$distro".toByteArray())
                        CommandResult(0, "# TIANYAN_ENV_V1|id|TOKEN|$encoded|-|0\n", "", 0)
                    }
                }
                else -> error("Unexpected local runtime call: ${method.name}")
            }
        } as LinuxRuntime
        val settings = SettingsDataStore(ApplicationProvider.getApplicationContext<Context>(), SecretManager())
        return runtime to LinuxEnvironmentManager(runtime, RuntimePreferences(settings))
    }
    @Test fun `local output secrets follow the pinned distro after the active distro changes`() = runBlocking {
        val root = temporary.newFolder()
        val (runtime, variables) = manager(root)
        val world = LocalExecutionEnvironmentFactory(runtime, WorkspaceFileAccess(root), HarnessPathResolver(), variables = variables)
            .open("one", "/workspace/p", null)
        active.value = "debian"
        assertEquals(listOf("secret-ubuntu"), world.outputSecrets())
        assertTrue(targets.isNotEmpty())
        assertTrue(targets.all { it == "ubuntu" })
        variables.redactionSecrets("debian")
        assertEquals(listOf("secret-ubuntu"), world.outputSecrets())
        world.close()
    }
    @Test fun `remote output secrets never refresh the legacy phone environment`() = runBlocking {
        val (_, variables) = manager(temporary.newFolder())
        val registry = SessionExecutionEnvironments(ExecutionEnvironmentFactory { owner, workspace, _ ->
            FakeWorld(ExecutionEnvironmentId("remote", "linux", workspace, owner)).apply { secretValues += "remote-secret" }
        })
        assertEquals(listOf("remote-secret"), executionOutputSecrets(registry, variables, "one", "/workspace/p"))
        assertTrue(targets.isEmpty())
        registry.closeSession("one")
    }
    @Test fun `failed secret refresh cannot return another distro's stale values`() = runBlocking {
        val (_, variables) = manager(temporary.newFolder())
        assertEquals(listOf("secret-ubuntu"), variables.redactionSecrets("ubuntu"))
        assertNotNull(runCatching { variables.redactionSecrets("broken") }.exceptionOrNull())
        assertEquals(listOf("secret-ubuntu"), variables.redactionSecrets("ubuntu"))
    }
}
