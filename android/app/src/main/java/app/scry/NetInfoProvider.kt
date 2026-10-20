package app.scry

import android.content.Context
import android.net.ConnectivityManager
import android.net.RouteInfo
import core.NetworkInfo
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Answers the Go core's questions about the device's network using the platform's own APIs.
 * Android 11 and later refuse apps the kernel interface listing that Tailscale's library uses,
 * so this stands in for it.
 */
class NetInfoProvider(context: Context) : NetworkInfo {
    private val cm = context.getSystemService(ConnectivityManager::class.java)

    override fun interfaces(): String {
        val out = JSONArray()
        try {
            for (ni in NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()) {
                val addrs = JSONArray()
                for (ia in ni.interfaceAddresses) {
                    // Drop the zone suffix some IPv6 addresses carry ("fe80::1%wlan0").
                    val host = ia.address.hostAddress?.substringBefore('%') ?: continue
                    addrs.put("$host/${ia.networkPrefixLength}")
                }
                out.put(
                    JSONObject()
                        .put("name", ni.name)
                        .put("index", ni.index)
                        .put("mtu", ni.mtu.coerceAtLeast(576))
                        .put("up", ni.isUp)
                        .put("loopback", ni.isLoopback)
                        .put("multicast", ni.supportsMulticast())
                        .put("addrs", addrs)
                )
            }
        } catch (e: Exception) {
            // An empty list is better than a crash; the core treats it as "no network".
        }
        return out.toString()
    }

    override fun defaultInterface(): String =
        cm.getLinkProperties(cm.activeNetwork)?.interfaceName ?: ""

    override fun defaultGateway(): String {
        val routes: List<RouteInfo> = cm.getLinkProperties(cm.activeNetwork)?.routes ?: return ""
        // Prefer an IPv4 gateway: it is what NAT-PMP and similar discovery looks for.
        val gw = routes.filter { it.isDefaultRoute && it.gateway != null }
            .sortedByDescending { it.gateway is Inet4Address }
            .firstOrNull()?.gateway
        return gw?.hostAddress?.substringBefore('%') ?: ""
    }
}
