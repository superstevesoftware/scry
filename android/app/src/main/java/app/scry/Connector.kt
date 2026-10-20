package app.scry

import android.content.Context
import core.Core
import java.io.IOException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit

/**
 * Chooses how to reach PhotoPrism and brings that route up. Every caller (the main screen,
 * the settings test and download jobs) goes through here so they all behave the same.
 *
 * If a local address is configured and the app has already learned the server's identity,
 * the server is probed directly with a short time limit. A match means no tunnel is needed.
 * The VPN is started at the same moment, so its handshake is already under way if the
 * probe fails; the cost of checking is at most the probe limit, and only when the address
 * stays silent (for example away from home).
 */
object Connector {
    /** Longest the local check may take. A server on the same network answers in milliseconds. */
    private const val PROBE_MS = 400L
    private const val DIRECT_CONFIG = """{"kind":"direct"}"""

    class Route(val direct: Boolean, val lease: String, val millis: Long, val via: String = "the VPN")

    /** Everything needed to pick a route. Settings builds this from the form, others from storage. */
    class Target(
        val vpnConfig: String,
        val serverUrl: String,
        val tlsMode: String = "system",
        val localUrl: String? = null,
        val knownServerId: String? = null,
    ) {
        companion object {
            fun fromStore(s: SecretStore): Target? {
                val cfg = s.get(SecretStore.VPN_CONFIG) ?: return null
                val url = s.get(SecretStore.SERVER_URL) ?: return null
                return Target(
                    cfg, url, s.get(SecretStore.TLS_MODE) ?: "system",
                    s.get(SecretStore.LOCAL_URL)?.takeIf { it.isNotBlank() },
                    s.get(SecretStore.SERVER_ID),
                )
            }
        }
    }

    /**
     * Brings up the best route and returns it with a lease the caller must release.
     * Throws IOException with a specific cause when no route works.
     */
    fun open(ctx: Context, t: Target, onStatus: (String) -> Unit = {}): Route {
        val started = System.currentTimeMillis()
        val store = SecretStore(ctx)
        val stateDir = ctx.filesDir.resolve("core").absolutePath
        fun elapsed() = System.currentTimeMillis() - started

        val noVpn = isNoVpn(t.vpnConfig)
        Core.networkInfoChanged() // the default route and gateway, which Android hides from the core
        Core.setUpstream(t.serverUrl, t.tlsMode)
        var lease = Core.acquire(t.vpnConfig, stateDir) // returns at once; the tunnel starts behind it

        // The VPN side runs in the background. Fetching the identity through the tunnel
        // starts the handshake, proves the server is reachable, and learns who it is.
        fun lookup() = FutureTask<String> {
            Core.await(40_000) // a first Tailscale sign-in takes a few seconds
            android.util.Log.i("Scry", "route: tunnel device up after ${elapsed()} ms")
            Core.tunnelIdentity(if (noVpn) 8_000 else 30_000).also { android.util.Log.i("Scry", "route: server answered through the tunnel after ${elapsed()} ms") }
        }.also { Thread(it).start() }
        var viaVpn = lookup()
        if (BuildConfig.DEBUG) Thread { // timing only: when does the WireGuard handshake complete?
            repeat(300) {
                if (org.json.JSONObject(Core.status()).optLong("lastHandshake") != 0L) {
                    android.util.Log.i("Scry", "route: tunnel handshake/up completed after ${elapsed()} ms"); return@Thread
                }
                Thread.sleep(50)
            }
        }.start()

        if (!noVpn && t.localUrl != null && t.knownServerId != null) {
            onStatus("Looking for the server on this network…")
            val seen = try { Core.probeIdentity(t.localUrl, PROBE_MS) } catch (e: Exception) { null }
            if (seen != null && seen == t.knownServerId) {
                try {
                    Core.setUpstream(t.localUrl, "system")
                    val direct = Core.acquire(DIRECT_CONFIG, stateDir)
                    Core.release(lease)
                    Core.await(2_000)
                    return Route(true, direct, elapsed())
                } catch (e: Exception) {
                    // Could not switch; fall back to the VPN below.
                    Core.setUpstream(t.serverUrl, t.tlsMode)
                    lease = Core.acquire(t.vpnConfig, stateDir)
                    viaVpn = lookup() // the first lookup ran against the wrong route
                }
            }
        }

        onStatus("Connecting through the VPN…")
        val id = try {
            viaVpn.get(75, TimeUnit.SECONDS)
        } catch (e: Exception) {
            // The tunnel's own error is the most useful thing to show, so keep it. Only when it
            // has nothing to say, ask the server for a specific reason (no handshake, HTTP code).
            val own = (e.cause?.message ?: e.message)?.takeIf { it.isNotBlank() && !it.contains("timed out", true) }
            android.util.Log.w("Scry", "route failed: ${e.cause ?: e} | status=${Core.status()}")
            val why = own?.let { Health.explain(kindOf(t.vpnConfig), it) } ?: try { Health.probe().message } catch (e2: Exception) { "Could not connect" }
            Core.release(lease)
            throw IOException(why)
        }
        store.put(SecretStore.SERVER_ID, id) // learned over the authenticated tunnel
        dropUsedTailscaleKey(store, t.vpnConfig)
        return Route(noVpn, lease, elapsed(), vpnName(t.vpnConfig))
    }

    /**
     * Once a Tailscale node has signed in, its identity lives in the state directory and the
     * auth key is no longer needed, so it is removed from storage rather than kept around.
     */
    private fun dropUsedTailscaleKey(store: SecretStore, config: String) {
        try {
            val json = org.json.JSONObject(config)
            val ts = json.optJSONObject("tailscale") ?: return
            if (json.optString("kind") != "tailscale" || !ts.has("authKey")) return
            ts.remove("authKey")
            // Only touch the saved config if it is the one that just worked; a test of unsaved
            // settings (settings screen, wizard) must not replace what is saved.
            if (store.get(SecretStore.VPN_CONFIG) == config) store.put(SecretStore.VPN_CONFIG, json.toString())
        } catch (e: Exception) {
            // Leaving the key in place is harmless; try again next time.
        }
    }

    /** Names the VPN kind in a saved config, for messages and logs. */
    fun vpnName(config: String): String = try {
        when (org.json.JSONObject(config).optString("kind")) {
            "tailscale" -> "Tailscale"
            "direct" -> "No VPN (home network only)"
            else -> "WireGuard"
        }
    } catch (e: Exception) {
        "the VPN"
    }

    /** "wireguard", "tailscale" or "direct", from a saved config. */
    fun kindOf(config: String): String = try {
        org.json.JSONObject(config).optString("kind").ifEmpty { "wireguard" }
    } catch (e: Exception) {
        "wireguard"
    }

    /** True when the saved connection uses no VPN at all: the server is reached on the local network. */
    fun isNoVpn(config: String): Boolean = try {
        org.json.JSONObject(config).optString("kind") == "direct"
    } catch (e: Exception) {
        false
    }

    fun describe(r: Route) =
        if (r.direct) "directly on the local network" else "through ${r.via}"
}
