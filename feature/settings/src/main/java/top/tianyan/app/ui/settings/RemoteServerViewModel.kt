package top.tianyan.app.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import top.tianyan.app.core.datastore.RemoteServerRepository
import top.tianyan.app.core.model.RemoteServer
import top.tianyan.app.core.model.SshCredentials
import top.tianyan.app.runtime.remote.HostKeyConfirmationRequired
import top.tianyan.app.runtime.remote.SshConnectionException
import top.tianyan.app.runtime.remote.SshConnectionTester

data class RemoteConnectionState(val busy: Boolean = false, val fingerprint: String? = null, val message: String? = null, val saved: Boolean = false, val tested: Boolean = false)

class RemoteServerViewModel(private val repository: RemoteServerRepository, private val connector: SshConnectionTester) : ViewModel() {
    val servers = repository.servers
    val activeId = repository.activeId
    val configurationError = repository.configurationError
    suspend fun credentials(id: String): SshCredentials = repository.credentials(id)
    private val state = MutableStateFlow(RemoteConnectionState())
    val connectionState: StateFlow<RemoteConnectionState> = state
    private class Pending(val server: RemoteServer, val credentials: SshCredentials, val save: Boolean)
    private var pending: Pending? = null
    private var verified: Pending? = null

    fun connect(server: RemoteServer, credentials: SshCredentials) {
        if (state.value.busy) return
        pending = null
        runConnection(server, credentials, save = true)
    }
    fun test(server: RemoteServer, credentials: SshCredentials) {
        if (state.value.busy) return
        pending = null
        runConnection(server, credentials, save = false)
    }
    fun confirmFingerprint() {
        val request = pending ?: return
        val fingerprint = state.value.fingerprint ?: return
        pending = null
        runConnection(request.server.copy(fingerprint = fingerprint), request.credentials, request.save)
    }
    private fun runConnection(server: RemoteServer, credentials: SshCredentials, save: Boolean) {
        val trusted = verified?.takeIf {
            it.server.copy(fingerprint = "") == server.copy(fingerprint = "") &&
                it.credentials.secret == credentials.secret && it.credentials.passphrase == credentials.passphrase
        }?.server ?: server
        state.value = RemoteConnectionState(busy = true)
        viewModelScope.launch {
            try {
                trusted.validate()
                require(credentials.secret.isNotBlank()) { "请输入密码或私钥" }
                connector.test(trusted, credentials)
                verified = Pending(trusted, credentials, save)
                if (save) repository.save(trusted, credentials)
                state.value = RemoteConnectionState(saved = save, tested = true, message = if (save) null else "连接测试成功，尚未保存")
            } catch (e: CancellationException) { throw e }
            catch (e: HostKeyConfirmationRequired) {
                pending = Pending(server, credentials, save)
                state.value = RemoteConnectionState(fingerprint = e.fingerprint)
            } catch (e: SshConnectionException) {
                state.value = RemoteConnectionState(message = e.message ?: "连接失败：请检查网络、认证信息、主机指纹和远程目录。")
            } catch (e: IllegalArgumentException) {
                state.value = RemoteConnectionState(message = e.message ?: "连接配置无效")
            } catch (e: Exception) {
                state.value = RemoteConnectionState(message = "连接失败：请检查网络、认证信息、主机指纹和远程目录。")
            }
        }
    }
    fun select(id: String?) = mutate { repository.select(id) }
    fun remove(id: String) = mutate { repository.remove(id) }
    private fun mutate(block: suspend () -> Unit) {
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { state.value = RemoteConnectionState(message = "服务器配置保存失败") }
        }
    }
    fun clear() { if (!state.value.busy) { pending = null; verified = null; state.value = RemoteConnectionState() } }
}
