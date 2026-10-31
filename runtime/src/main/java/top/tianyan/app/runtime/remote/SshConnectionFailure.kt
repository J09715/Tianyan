package top.tianyan.app.runtime.remote

internal const val SSH_FAILURE_HOST_KEY = "服务器主机指纹已变化，拒绝连接"
internal const val SSH_FAILURE_TIMEOUT = "TCP 连接超时：未能在时限内连上 SSH 端口。若开启了 VPN 或分应用代理，局域网流量可能被送进隧道"
internal const val SSH_FAILURE_REFUSED = "TCP 连接被拒绝：目标端口没有接受连接"
internal const val SSH_FAILURE_UNREACHABLE = "TCP 无法到达目标主机：请确认与服务器处于同一网络"
internal const val SSH_FAILURE_CLOSED = "TCP 连接已关闭：SSH 握手尚未完成，连接可能被代理中断"
internal const val SSH_FAILURE_AUTH = "认证失败：请检查用户名、密码或私钥"
internal const val SSH_FAILURE_SFTP = "SFTP 或远程工作目录不可用：请确认 SFTP 子系统已开启，且工作目录存在并是目录"
internal const val SSH_FAILURE_GENERIC = "连接失败：请检查网络、认证信息、主机指纹和远程目录"
internal const val SSH_FAILURE_LOCAL_NETWORK = "无法访问局域网。${LocalNetworkAccess.USER_HINT}"

private val LAN_HINT_STAGES = setOf(
    SSH_FAILURE_TIMEOUT,
    SSH_FAILURE_REFUSED,
    SSH_FAILURE_UNREACHABLE,
    SSH_FAILURE_CLOSED,
    SSH_FAILURE_GENERIC,
)

/**
 * Maps a failed connection test to a fixed stage message.
 * The original exception text is never copied: JSch and the network stack can echo key material.
 * LAN targets also mention the Android 17 nearby-devices permission.
 */
internal fun sshTestFailure(error: Throwable, host: String? = null): SshConnectionException {
    val lan = host != null && LocalNetworkAccess.isLanLiteral(host)
    val base = classifySshFailure(error, lan)
    val message = if (lan && base in LAN_HINT_STAGES) "$base。${LocalNetworkAccess.USER_HINT}" else base
    return SshConnectionException(message)
}

private fun classifySshFailure(error: Throwable, lan: Boolean): String {
    val chain = generateSequence(error) { it.cause }.toList()
    val names = chain.joinToString(" ") { it.javaClass.name }
    val text = chain.joinToString("\n") { it.message.orEmpty() }
    return when {
        chain.any { it is IllegalStateException && it.message?.contains("指纹") == true } ||
            text.contains("HostKey") -> SSH_FAILURE_HOST_KEY
        names.contains("SocketTimeoutException") || text.contains("timed out") || text.contains("timeout:") ->
            SSH_FAILURE_TIMEOUT
        names.contains("ConnectException") || text.contains("Connection refused") -> SSH_FAILURE_REFUSED
        names.contains("NoRouteToHostException") || names.contains("PortUnreachableException") ||
            text.contains("No route to host") || text.contains("Network is unreachable") -> SSH_FAILURE_UNREACHABLE
        names.contains("EOFException") || text.contains("connection is closed", ignoreCase = true) ||
            text.contains("Connection reset") || text.contains("Broken pipe") ||
            text.contains("closed by foreign host") -> SSH_FAILURE_CLOSED
        text.contains("Auth fail") || text.contains("Auth cancel") || text.contains("USERAUTH") ||
            text.contains("invalid privatekey") -> SSH_FAILURE_AUTH
        chain.any { it.javaClass.simpleName == "SftpException" } || text.contains("远程工作目录") ||
            text.contains("sftp", ignoreCase = true) || text.contains("channel is not opened") -> SSH_FAILURE_SFTP
        lan && isLocalNetworkDenial(names, text) -> SSH_FAILURE_LOCAL_NETWORK
        else -> SSH_FAILURE_GENERIC
    }
}

private fun isLocalNetworkDenial(names: String, text: String): Boolean =
    names.contains("SecurityException") || text.contains("EACCES") || text.contains("Permission denied")
