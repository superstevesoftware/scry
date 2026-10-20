package app.scry

import core.Core
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Server reachability checks that run through the tunnel via the worker listener. */
object Health {
    /** Outcome of one status probe. [code] is the HTTP status when the server (or proxy) answered. */
    data class Result(val ok: Boolean, val code: Int?, val message: String, val millis: Long, val route: String? = null)

    /** Returns null when PhotoPrism answered, otherwise a specific cause. */
    fun check(): String? = probe().let { if (it.ok) null else it.message }

    fun probe(): Result {
        val t0 = System.currentTimeMillis()
        fun result(ok: Boolean, code: Int?, msg: String) = Result(ok, code, msg, System.currentTimeMillis() - t0)
        val conn = URL("http://${Core.workerEndpoint()}/api/v1/status").openConnection() as HttpURLConnection
        conn.setRequestProperty("X-Scry-Token", Core.workerToken())
        conn.connectTimeout = 10_000
        conn.readTimeout = 20_000
        return try {
            when (val code = conn.responseCode) {
                in 200..299 -> result(true, code, "PhotoPrism answered")
                502 -> result(false, code, noHandshakeOr("Server unreachable through the tunnel"))
                else -> result(false, code, "Server answered HTTP $code from /api/v1/status")
            }
        } catch (e: SocketTimeoutException) {
            result(false, null, noHandshakeOr("Server did not respond in time") + " (timeout)")
        } catch (e: Exception) {
            result(false, null, "Health check failed: ${e.message}")
        } finally {
            conn.disconnect()
        }
    }

    /**
     * Picks a route the same way the app does, probes the server over it, and releases the
     * lease. The result says which route was used and how long it took to get connected.
     */
    fun test(ctx: android.content.Context, target: Connector.Target): Result {
        val t0 = System.currentTimeMillis()
        val route = try {
            Connector.open(ctx, target)
        } catch (e: Exception) {
            return Result(false, null, e.message ?: e.toString(), System.currentTimeMillis() - t0)
        }
        return try {
            probe().copy(millis = route.millis, route = Connector.describe(route))
        } finally {
            Core.release(route.lease)
        }
    }

    /**
     * Turns a technical failure into plain advice. [kind] is "wireguard", "tailscale" or "direct".
     * An error it does not recognise is returned unchanged, so nothing useful is ever hidden.
     */
    fun explain(kind: String, raw: String): String {
        val r = raw.lowercase()
        return when {
            "refused" in r -> "Your server answered, but PhotoPrism is not listening at that address and port. " +
                "Check the address and the port (usually :2342)."
            "auth key" in r -> "Please check the Tailscale auth key."
            "handshake" in r -> raw // already written for people
            "deadline" in r || "timed out" in r || "timeout" in r || "did not respond" in r -> when (kind) {
                "direct" -> "Scry could not find PhotoPrism at that address. Check that this phone is on your home Wi-Fi " +
                    "and that the address, including http:// and the port, is right."
                "tailscale" -> "Scry could not reach PhotoPrism through Tailscale. Check the address, that your server is " +
                    "online, and that your Tailscale access rules allow it."
                else -> "Scry could not reach PhotoPrism through your VPN. Check the address, and that your server and " +
                    "VPN are online."
            }
            else -> raw
        }
    }

    private fun noHandshakeOr(fallback: String): String {
        val status = JSONObject(Core.status())
        if (status.optLong("lastHandshake") != 0L) return fallback
        return when (status.optString("kind")) {
            "tailscale" -> "Tailscale is not connected. Check the auth key, and that this device may reach your server in your Tailscale access rules."
            "direct" -> "Could not reach PhotoPrism at that address. Check that this phone is on your home network and that the address is right."
            else -> "No WireGuard handshake. Check the endpoint, keys and that UDP is reachable."
        }
    }
}
