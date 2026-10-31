package top.tianyan.app.ui.settings

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.runtime.remote.SshConnectionException
import top.tianyan.app.runtime.remote.SshConnectionTester

@OptIn(ExperimentalCoroutinesApi::class)
class RemoteConnectionStageTest {
    @Before fun setup() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun cleanup() { Dispatchers.resetMain() }

    private val server = RemoteServer("server", "Server", "192.168.10.8", port = 8022, username = "root", workspace = "/root", fingerprint = "SHA256:fixture")
    private val secret = "s3cret-password"

    @Test fun `connection test shows the stage and not a shared fallback`() = runTest {
        val stages = listOf(
            "TCP 连接超时：未能在时限内连上 SSH 端口。若开启了 VPN 或分应用代理，局域网流量可能被送进隧道",
            "TCP 连接被拒绝：目标端口没有接受连接",
            "TCP 连接已关闭：SSH 握手尚未完成，连接可能被代理中断",
            "TCP 无法到达目标主机：请确认与服务器处于同一网络",
            "服务器主机指纹已变化，拒绝连接",
            "认证失败：请检查用户名、密码或私钥",
            "SFTP 或远程工作目录不可用：请确认 SFTP 子系统已开启，且工作目录存在并是目录",
        )
        stages.forEach { stage ->
            val viewModel = viewModel(SshConnectionTester { _, _ -> throw SshConnectionException(stage) })
            viewModel.test(server, SshCredentials(secret, "hunter2"))
            advanceUntilIdle()
            assertEquals(stage, viewModel.connectionState.value.message)
            assertFalse(viewModel.connectionState.value.message!!.contains(secret))
            assertFalse(viewModel.connectionState.value.message!!.contains("hunter2"))
            assertFalse(viewModel.connectionState.value.saved)
        }
    }

    @Test fun `unclassified failures stay generic and do not echo secrets`() = runTest {
        val viewModel = viewModel(SshConnectionTester { _, _ ->
            error("Auth fail $secret hunter2 -----BEGIN OPENSSH PRIVATE KEY-----")
        })
        viewModel.connect(server, SshCredentials(secret, "hunter2"))
        advanceUntilIdle()
        val message = viewModel.connectionState.value.message!!
        assertEquals("连接失败：请检查网络、认证信息、主机指纹和远程目录。", message)
        assertFalse(message.contains(secret))
        assertFalse(message.contains("hunter2"))
        assertFalse(message.contains("PRIVATE KEY"))
        assertFalse(viewModel.connectionState.value.saved)
    }

    private fun viewModel(tester: SshConnectionTester) = RemoteServerViewModel(object : RemoteServerRepository {
        override val servers = MutableStateFlow(emptyList<RemoteServer>())
        override val activeId = MutableStateFlow<String?>(null)
        override suspend fun credentials(id: String) = error("unused")
        override suspend fun save(server: RemoteServer, credentials: SshCredentials) = error("must not save")
        override suspend fun select(id: String?) = Unit
        override suspend fun remove(id: String) = Unit
    }, tester)
}
