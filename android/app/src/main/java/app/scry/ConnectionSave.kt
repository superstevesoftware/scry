package app.scry

import android.content.Context

/** Storing the connection a person chose, shared by the settings screen and the setup wizard. */
object ConnectionSave {
    /**
     * Saves [target] as the connection Scry uses from now on. The previous VPN config is kept
     * until the new one has connected once, so a mistake can be reverted from the connect screen.
     */
    fun save(ctx: Context, target: Connector.Target) {
        val store = SecretStore(ctx)
        store.get(SecretStore.VPN_CONFIG)?.let {
            if (it != target.vpnConfig) {
                store.put(SecretStore.VPN_PREV, it)
                store.put(SecretStore.VPN_PENDING, "1")
            }
        }
        store.put(SecretStore.VPN_CONFIG, target.vpnConfig)
        store.put(SecretStore.SERVER_URL, target.serverUrl)
        target.localUrl?.let { store.put(SecretStore.LOCAL_URL, it) } ?: store.remove(SecretStore.LOCAL_URL)
        store.put(SecretStore.TLS_MODE, "system")
    }

    /** True once the first-run wizard has been completed (or skipped). */
    fun wizardDone(ctx: Context): Boolean = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean("wizard_done", false)

    fun setWizardDone(ctx: Context) {
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).edit().putBoolean("wizard_done", true).apply()
    }
}
