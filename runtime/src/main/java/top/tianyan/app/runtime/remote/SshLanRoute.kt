package top.tianyan.app.runtime.remote

import com.jcraft.jsch.SocketFactory
import java.io.InputStream
import java.io.OutputStream
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket

internal enum class DirectConnectKind { LOOPBACK, LAN }

/**
 * Site-local, link-local and loopback targets must not be classified by DNS:
 * a VPN resolver can answer a name with an address that is not on the LAN.
 */
internal fun directConnectKind(host: String): DirectConnectKind? {
    if (host.equals("localhost", ignoreCase = true)) return DirectConnectKind.LOOPBACK
    val address = numericAddress(host) ?: return null
    return when {
        address.isLoopbackAddress -> DirectConnectKind.LOOPBACK
        address.isLinkLocalAddress || address.isSiteLocalAddress || isUniqueLocal(address) -> DirectConnectKind.LAN
        else -> null
    }
}

/** Loopback is not on Wi-Fi; binding it to that network would miss 127.0.0.1. */
internal fun bindsUnderlyingLan(host: String): Boolean = directConnectKind(host) == DirectConnectKind.LAN

internal fun isUnderlyingLan(notVpn: Boolean, vpnTransport: Boolean, wifi: Boolean, ethernet: Boolean): Boolean =
    notVpn && !vpnTransport && (wifi || ethernet)

internal data class LanNetwork(val vpnActive: Boolean, val bind: ((Socket) -> Unit)?)

/**
 * Opens SSH sockets. When a VPN is the active network and the target is site-local or
 * link-local, the socket is bound to a NOT_VPN Wi-Fi or Ethernet network. Any other case,
 * including a missing underlying network or a failed bind, uses the process default route.
 * A bind that succeeds and then fails to connect is reported as-is.
 */
class LanSocketOpener internal constructor(
    private val networks: () -> LanNetwork?,
    private val connectSocket: (Socket, String, Int, Int) -> Unit = { socket, host, port, timeoutMs ->
        socket.connect(InetSocketAddress(host, port), timeoutMs)
    },
) {
    constructor() : this(networks = { null })
    fun socketFactory(host: String, timeoutMs: Int): SocketFactory? {
        if (!bindsUnderlyingLan(host)) return null
        val network = runCatching { networks() }.getOrNull() ?: return null
        val bind = network.bind
        if (!network.vpnActive || bind == null) return null
        return BoundSocketFactory(host, timeoutMs, bind, connectSocket)
    }
}

private class BoundSocketFactory(
    private val fallbackHost: String,
    private val timeoutMs: Int,
    private val bind: (Socket) -> Unit,
    private val connectSocket: (Socket, String, Int, Int) -> Unit,
) : SocketFactory {
    override fun createSocket(host: String?, port: Int): Socket {
        val remoteHost = host?.takeIf { it.isNotBlank() } ?: fallbackHost
        val socket = Socket()
        if (!runCatching { bind(socket) }.isSuccess) {
            socket.closeQuietly()
            val fallback = Socket()
            try {
                connectSocket(fallback, remoteHost, port, timeoutMs)
                return fallback
            } catch (failure: Throwable) {
                fallback.closeQuietly()
                throw failure
            }
        }
        try {
            connectSocket(socket, remoteHost, port, timeoutMs)
            return socket
        } catch (failure: Throwable) {
            socket.closeQuietly()
            throw failure
        }
    }

    override fun getInputStream(socket: Socket): InputStream = socket.getInputStream()
    override fun getOutputStream(socket: Socket): OutputStream = socket.getOutputStream()
}

private fun Socket.closeQuietly() {
    runCatching { close() }
}

private fun numericAddress(host: String): InetAddress? {
    val literal = host.trim().removePrefix("[").removeSuffix("]").substringBefore('%')
    if (!isIpv4(literal) && !isIpv6(literal)) return null
    val address = runCatching { InetAddress.getByName(literal) }.getOrNull() ?: return null
    return ipv4Mapped(address) ?: address
}

private fun isIpv4(value: String): Boolean {
    val parts = value.split('.')
    if (parts.size != 4) return false
    return parts.all { part ->
        part.isNotEmpty() && part.all { it.isDigit() } && part.length <= 3 &&
            part.toInt() in 0..255 && (part.length == 1 || part.first() != '0')
    }
}

private fun isIpv6(value: String): Boolean {
    if (':' !in value || value.length > 64) return false
    return value.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' || it == ':' || it == '.' }
}

private fun ipv4Mapped(address: InetAddress): InetAddress? {
    if (address !is Inet6Address) return null
    val bytes = address.address
    val mapped = (0 until 10).all { bytes[it] == 0.toByte() } && bytes[10] == 0xff.toByte() && bytes[11] == 0xff.toByte()
    if (!mapped) return null
    return runCatching { InetAddress.getByAddress(bytes.copyOfRange(12, 16)) }.getOrNull()
}

private fun isUniqueLocal(address: InetAddress): Boolean {
    if (address !is Inet6Address) return false
    val first = address.address.first().toInt() and 0xff
    return first and 0xfe == 0xfc
}
