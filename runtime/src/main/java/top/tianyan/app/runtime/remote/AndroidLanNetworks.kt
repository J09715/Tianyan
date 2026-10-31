package top.tianyan.app.runtime.remote

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

internal fun androidLanSockets(context: Context): LanSocketOpener =
    LanSocketOpener(networks = { AndroidLanNetworks(context.applicationContext).current() })

internal class AndroidLanNetworks(private val context: Context) {
    fun current(): LanNetwork? = runCatching { read() }.getOrNull()

    @SuppressLint("MissingPermission")
    private fun read(): LanNetwork {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return LanNetwork(vpnActive = false, bind = null)
        val active = manager.getNetworkCapabilities(manager.activeNetwork)
        val vpnActive = active?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        if (!vpnActive) return LanNetwork(vpnActive = false, bind = null)
        val underlying = manager.allNetworks.firstOrNull { network ->
            val caps = manager.getNetworkCapabilities(network) ?: return@firstOrNull false
            isUnderlyingLan(
                notVpn = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN),
                vpnTransport = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
                ethernet = caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET),
            )
        } ?: return LanNetwork(vpnActive = true, bind = null)
        return LanNetwork(vpnActive = true, bind = { socket -> underlying.bindSocket(socket) })
    }
}
