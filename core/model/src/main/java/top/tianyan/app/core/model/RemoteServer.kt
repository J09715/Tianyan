package top.tianyan.app.core.model

import kotlinx.serialization.Serializable

@Serializable
enum class SshAuthentication { PASSWORD, PRIVATE_KEY }

@Serializable
data class RemoteServer(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val username: String,
    val authentication: SshAuthentication = SshAuthentication.PASSWORD,
    val workspace: String = "/home",
    val fingerprint: String = "",
) {
    fun validate() {
        require(id.matches(Regex("[A-Za-z0-9-]{1,80}"))) { "非法服务器标识" }
        require(name.isNotBlank() && name.length <= 100) { "请输入服务器名称" }
        require(host.isNotBlank() && host.length <= 253 && host.none { it.isWhitespace() || it == '\u0000' }) { "请输入有效主机地址" }
        require(port in 1..65535) { "端口必须为 1–65535" }
        require(username.isNotBlank() && username.none { it.isWhitespace() || it == '\u0000' }) { "请输入有效用户名" }
        require(workspace.startsWith('/') && workspace != "/" && workspace.split('/').none { it == ".." } && '\u0000' !in workspace) { "工作区必须是服务器上的绝对目录，不能为根目录" }
    }
    val distributionId get() = "ssh:$id"
}

/** Never include credentials in data-class generated toString, saved UI state or model context. */
@Serializable
class SshCredentials(val secret: String, val passphrase: String = "") {
    override fun toString() = "SshCredentials([redacted])"
}
