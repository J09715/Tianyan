package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import top.tianyan.app.core.model.InstalledDistro
import top.tianyan.app.core.model.RuntimeState
import top.tianyan.app.core.common.result.AppResult
import top.tianyan.app.runtime.DownloadProgress
import top.tianyan.app.runtime.LinuxRuntime
import top.tianyan.app.runtime.RuntimeHealth
import top.tianyan.app.runtime.RootfsUpdateInfo
import top.tianyan.app.runtime.shell.CommandResult
import top.tianyan.app.runtime.shell.SessionConfig
import top.tianyan.app.runtime.shell.ShellCommand
import top.tianyan.app.runtime.shell.LinuxSession
import top.tianyan.app.runtime.shell.ManagedProcess
import top.tianyan.app.runtime.shell.ProcessType
import top.tianyan.app.runtime.RuntimeInstallRequest as InstallRequest

/**
 * CcSwitchSandbox 行为测试（批次 E）：
 * 批次 A 已把「凭据缺失回落 admin/admin123」改成「返回 null，调用方中止」——
 * 这里锁死新行为：缺失/空文件/命令失败一律 null，绝不产出默认口令。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class CcSwitchSandboxTest {

    /** 只实现 execute 的假运行时：按脚本里的固定命令返回 canned stdout / 退出码。 */
    private class FakeRuntime(private val result: CommandResult) : LinuxRuntime {
        override val state: StateFlow<RuntimeState> = MutableStateFlow(RuntimeState.Ready)
        override val activeDistroId: StateFlow<String> = MutableStateFlow("")
        override val installedDistros: StateFlow<List<InstalledDistro>> = MutableStateFlow(emptyList())
        override suspend fun initialize(request: InstallRequest): AppResult<Unit> = error("unused")
        override suspend fun restoreInstalledState(): Boolean = false
        override suspend fun updateRootfs(distroId: String?): AppResult<Unit> = error("unused")
        override suspend fun checkRootfsUpdate(distroId: String?): AppResult<RootfsUpdateInfo> = error("unused")
        override suspend fun healthCheck(distroId: String?): RuntimeHealth = error("unused")
        override suspend fun switchActiveDistro(distroId: String): AppResult<Unit> = error("unused")
        override suspend fun installDistro(request: InstallRequest, onProgress: suspend (DownloadProgress) -> Unit): AppResult<Unit> = error("unused")
        override suspend fun uninstallDistro(distroId: String): AppResult<Unit> = error("unused")
        override suspend fun resetSandbox(distroId: String?): AppResult<Unit> = error("unused")
        override fun refreshInstalledDistros() = Unit
        override suspend fun execute(command: ShellCommand, distroId: String?): CommandResult = result
        override suspend fun startSession(config: SessionConfig, distroId: String?): LinuxSession = error("unused")
        override suspend fun startBackground(id: String, command: ShellCommand, toolId: String?, type: ProcessType, distroId: String?): ManagedProcess = error("unused")
        override suspend fun stopBackground(id: String): Boolean = true
        override fun listBackground(): List<ManagedProcess> = emptyList()
        override suspend fun cleanupDeadBackground(): Int = 0
        override suspend fun shutdown() = Unit
        override fun rootfsPath(distroId: String?): java.io.File = java.io.File("/tmp/rootfs")
        override fun workspacePath(): java.io.File = java.io.File("/tmp/workspace")
    }

    private fun ok(stdout: String) = CommandResult(exitCode = 0, stdout = stdout, stderr = "", durationMs = 1)
    private val fail = CommandResult(exitCode = 3, stdout = "", stderr = "", durationMs = 1)
    private val split = "---TIANYAN_SPLIT---"

    @Test
    fun `missing credential files exit 3 returns null not default password`() = runTest {
        val creds = withContext(Dispatchers.IO) { readCcSwitchWebCredentials(FakeRuntime(fail)) }
        assertNull(creds)
    }

    @Test
    fun `blank username or password returns null`() = runTest {
        // 文件存在但内容是空白：同样不能回落默认口令。
        val blank = ok("\n$split\n$split")
        assertNull(withContext(Dispatchers.IO) { readCcSwitchWebCredentials(FakeRuntime(blank)) })
        // 只有用户名没有密码：缺一半也拒绝。
        val half = ok("admin\n$split\n$split")
        assertNull(withContext(Dispatchers.IO) { readCcSwitchWebCredentials(FakeRuntime(half)) })
    }

    @Test
    fun `valid files yield triple with password passthrough`() = runTest {
        val out = ok("ops\n$split\nS3cret-Pass\n$split\nCSRF-TOKEN-123")
        val creds = withContext(Dispatchers.IO) { readCcSwitchWebCredentials(FakeRuntime(out)) }
        assertEquals(Triple("ops", "S3cret-Pass", "CSRF-TOKEN-123"), creds)
    }

    @Test
    fun `generated password has no default candidates`() = runTest {
        repeat(8) {
            val pwd = generateCcSwitchPassword()
            assertTrue(pwd.length >= 20)
            assertTrue(pwd !in setOf("admin", "admin123", "password", "123456"))
            // 全部落在去混淆字母表内（无 0/O/1/I）。
            assertTrue(pwd.all { it in "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789" })
        }
    }
}
