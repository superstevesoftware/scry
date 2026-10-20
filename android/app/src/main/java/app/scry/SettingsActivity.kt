package app.scry

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import core.Core
import org.json.JSONObject

/**
 * Scry's own settings, as a row of tabs over a page. The tab row works the same with a finger
 * and with a TV remote: left and right move between tabs, down enters the page, up comes back.
 *
 * Reached from the launcher icon's long-press menu, the system app-info page, or the
 * "Edit VPN settings" button on the connect error screen.
 */
class SettingsActivity : Activity() {
    private lateinit var ui: Ui
    private lateinit var store: SecretStore
    private lateinit var backupUi: BackupUi

    // Connection tab
    private lateinit var urlField: EditText
    private lateinit var localField: EditText
    private lateinit var statusView: TextView
    private lateinit var testResult: TextView
    private lateinit var testButton: Button

    // VPN tab: WireGuard
    private lateinit var endpoint: EditText
    private lateinit var addresses: EditText
    private lateinit var dns: EditText
    private lateinit var mtu: EditText
    private lateinit var peerKey: EditText
    private lateinit var allowedIps: EditText
    private lateinit var keepalive: EditText
    private lateinit var privateKey: EditText
    private lateinit var presharedKey: EditText

    // VPN tab: Tailscale
    private lateinit var tsAuthKey: EditText
    private lateinit var tsHost: EditText
    private lateinit var tsControl: EditText

    private lateinit var wgBox: LinearLayout
    private lateinit var tsBox: LinearLayout
    private lateinit var typeGroup: RadioGroup
    private lateinit var rbWireGuard: android.widget.RadioButton
    private lateinit var rbTailscale: android.widget.RadioButton
    private lateinit var rbNone: android.widget.RadioButton
    private lateinit var noneBox: LinearLayout

    // Frame
    private lateinit var scroll: ScrollView
    private lateinit var footer: LinearLayout
    private lateinit var messageView: TextView
    private val tabs = ArrayList<Tab>()
    private var selected = -1

    /** False while the screen opens: Android hands focus to the first tab, which must not override the one asked for. */
    private var focusReady = false

    private class Tab(val key: String, val title: String, val page: LinearLayout, val needsSave: Boolean) {
        lateinit var view: LinearLayout
    }

    /** Which kind of connection the form edits: "wireguard", "tailscale" or "direct" (no VPN). */
    private var kind = "wireguard"
    private var currentTs: JSONObject? = null

    /** The config currently shown. Its keys are never displayed; blank key fields keep these. */
    private var current: JSONObject? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        store = SecretStore(this)
        val saved = store.get(SecretStore.VPN_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() }
        kind = saved?.optString("kind")?.takeIf { it == "tailscale" || it == "direct" } ?: "wireguard"
        current = saved?.optJSONObject("wireguard")
        currentTs = saved?.optJSONObject("tailscale")

        val connection = buildConnectionPage()
        val vpn = buildVpnPage()
        val backupPage = ui.page().also { backupUi = BackupUi(this, ui, it); backupUi.build() }
        val downloads = buildDownloadsPage()
        val about = buildAboutPage()
        tabs += Tab("about", "About", about, needsSave = false)
        tabs += Tab("connection", "Connection", connection, needsSave = true)
        tabs += Tab("vpn", "VPN", vpn, needsSave = true)
        tabs += Tab("backup", "Backup", backupPage, needsSave = false)
        tabs += Tab("downloads", "Downloads", downloads, needsSave = false)

        setContentView(buildFrame())
        val start = intent.getStringExtra("tab")?.let { k -> tabs.indexOfFirst { it.key == k } }?.takeIf { it >= 0 } ?: 0
        select(start)
        window.decorView.post {
            focusReady = true
            tabs[selected].view.requestFocus() // so a remote starts on the tab row, on the right tab
        }
    }

    // ---------------------------------------------------------------- frame: title, tabs, page, save bar

    private fun buildFrame(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            // Edge-to-edge on targetSdk 35: keep everything clear of the system bars and the keyboard.
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        root.addView(TextView(this).apply {
            text = "Scry settings"
            textSize = ui.sp(22f)
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Ui.TEXT)
            setPadding(ui.dp(20f), ui.dp(16f), ui.dp(20f), ui.dp(4f))
        })

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(8f), 0, ui.dp(8f), 0) }
        tabs.forEachIndexed { i, t ->
            t.view = ui.tab(t.title, focusSelects = { focusReady }) { select(i) }
            row.addView(t.view)
        }
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            noOwnFocus()
            addView(row)
        })
        root.addView(View(this).apply { setBackgroundColor(Ui.DIVIDER) }, LinearLayout.LayoutParams(-1, ui.dp(1f)))

        // The page area: a centred column that stops growing on wide screens (tablets, TVs).
        scroll = ScrollView(this).apply { isFillViewport = false; clipToPadding = false; noOwnFocus() }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        // On wide screens keep the save bar's contents in the same column as the page above it.
        val side = maxOf(ui.dp(16f), (resources.displayMetrics.widthPixels - ui.dp(720f)) / 2 + ui.dp(16f))
        footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(side, ui.dp(10f), side, ui.dp(12f))
        }
        messageView = TextView(this).apply { textSize = ui.sp(14f); setTextColor(Ui.TEXT_SECONDARY) }
        footer.addView(messageView)
        ui.add(footer, ui.button("Save and connect") { save() }, 6f)
        root.addView(footer, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    /**
     * Scroll containers are focusable by default and would grab focus (and Android's default
     * highlight) when the screen opens. Only the controls inside them should take focus.
     */
    private fun View.noOwnFocus() {
        isFocusable = false
        isFocusableInTouchMode = false
        defaultFocusHighlightEnabled = false
    }

    private fun select(i: Int) {
        if (i == selected) return
        selected = i
        tabs.forEachIndexed { n, t -> ui.setTabSelected(t.view, n == i) }
        val page = tabs[i].page
        (page.parent as? ViewGroup)?.removeView(page)
        val holder = FrameLayout(this)
        val width = resources.displayMetrics.widthPixels
        val max = ui.dp(720f)
        holder.addView(page, FrameLayout.LayoutParams(if (width > max) max else -1, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        scroll.removeAllViews()
        scroll.addView(holder, FrameLayout.LayoutParams(-1, -2))
        scroll.scrollTo(0, 0)
        footer.visibility = if (tabs[i].needsSave) View.VISIBLE else View.GONE
        message("")
    }

    // ---------------------------------------------------------------- pages

    private fun buildConnectionPage(): LinearLayout {
        val page = ui.page()
        val status = ui.card(page, "Status")
        statusView = ui.body(status, "", secondary = true)
        testButton = ui.button("Test connection", primary = false) { test() }
        ui.add(status, testButton, 12f)
        testResult = ui.body(status, "", topDp = 10f)
        testResult.visibility = View.GONE

        val addr = ui.card(page, "Addresses")
        urlField = ui.field(addr, "PhotoPrism address (as seen from inside the VPN)", "http://10.0.0.1:2342", InputType.TYPE_TEXT_VARIATION_URI)
        urlField.setText(store.get(SecretStore.SERVER_URL) ?: "")
        localField = ui.field(addr, "Local address (optional)", "http://192.168.1.20:2342", InputType.TYPE_TEXT_VARIATION_URI)
        localField.setText(store.get(SecretStore.LOCAL_URL) ?: "")
        ui.body(
            addr,
            "If this address answers and turns out to be your server, Scry skips the VPN. It is checked against " +
                "your server's identity, which Scry learns the first time you connect through the VPN.",
            secondary = true, small = true,
        )
        return page
    }

    private fun buildVpnPage(): LinearLayout {
        val page = ui.page()
        val type = ui.card(page, "VPN type")
        typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        rbWireGuard = ui.radio("WireGuard").apply { id = View.generateViewId() }
        rbTailscale = ui.radio("Tailscale").apply { id = View.generateViewId() }
        rbNone = ui.radio("None (home network only)").apply { id = View.generateViewId() }
        typeGroup.addView(rbWireGuard)
        typeGroup.addView(rbTailscale)
        typeGroup.addView(rbNone)
        typeGroup.setOnCheckedChangeListener { _, checked ->
            kind = when (checked) {
                rbTailscale.id -> "tailscale"
                rbNone.id -> "direct"
                else -> "wireguard"
            }
            showKind()
        }
        ui.add(type, typeGroup, 8f)

        noneBox = ui.card(page, "Home network only")
        ui.body(
            noneBox,
            "Scry connects straight to your server, with no private connection. This only works while this " +
                "phone is on the same network as PhotoPrism, so your library is out of reach away from home. " +
                "Put your server's home address in the address field on the Connection tab.",
            secondary = true,
        )

        wgBox = ui.card(page, "WireGuard")
        ui.add(wgBox, ui.button("Import .conf file", primary = false) {
            startActivityForResult(
                Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT
            )
        }, 10f)
        ui.body(wgBox, "…or enter the values by hand:", secondary = true, topDp = 12f)
        endpoint = ui.field(wgBox, "Endpoint", "vpn.example.com:51820")
        addresses = ui.field(wgBox, "Address (comma separated)", "10.0.0.2/32")
        dns = ui.field(wgBox, "DNS (optional)", "10.0.0.1")
        mtu = ui.field(wgBox, "MTU (default 1280)", "1280", InputType.TYPE_CLASS_NUMBER)
        peerKey = ui.field(wgBox, "Peer public key")
        allowedIps = ui.field(wgBox, "Allowed IPs (comma separated)", "10.0.0.0/24")
        keepalive = ui.field(wgBox, "Persistent keepalive, seconds (optional)", "25", InputType.TYPE_CLASS_NUMBER)
        privateKey = ui.field(wgBox, "Private key", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        presharedKey = ui.field(wgBox, "Preshared key (optional)", "", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        fillFields()

        tsBox = ui.card(page, "Tailscale")
        ui.body(
            tsBox,
            "In the Tailscale admin console open Settings, then Keys, then Generate auth key. A one-off key is " +
                "enough: Scry keeps this device's identity afterwards, so the key is only needed once. Your server's " +
                "tailnet address (its 100.x.y.z address or MagicDNS name) goes in the address field on the Connection tab.",
            secondary = true, small = true,
        )
        tsAuthKey = ui.field(tsBox, "Auth key", "tskey-auth-…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        tsHost = ui.field(tsBox, "Node name (shown in your Tailscale admin console)", "scry-phone")
        tsHost.setText(currentTs?.optString("hostname")?.ifEmpty { null } ?: "scry-phone")
        tsControl = ui.field(tsBox, "Control server URL (optional, only for Headscale)", "https://", InputType.TYPE_TEXT_VARIATION_URI)
        tsControl.setText(currentTs?.optString("controlUrl") ?: "")
        if (Core.hasTailscaleState(filesDir.resolve("core").absolutePath)) {
            tsAuthKey.hint = "Signed in. Leave blank to keep this device's identity."
        }
        showKind()
        return page
    }

    private fun buildDownloadsPage(): LinearLayout {
        val page = ui.page()
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        val dl = ui.card(page, "Downloads")
        ui.switchRow(dl, "Unpack multi-select downloads into the gallery", prefs.getBoolean(DownloadWorker.PREF_UNZIP, true)) { on ->
            prefs.edit().putBoolean(DownloadWorker.PREF_UNZIP, on).apply()
        }
        ui.body(
            dl,
            "PhotoPrism sends several selected photos as one zip. When this is on, Scry saves the photos and videos " +
                "into the gallery and removes the zip. If the zip holds anything else, such as RAW files, it is kept as it is.",
            secondary = true, small = true,
        )
        val notes = ui.card(page, "Notifications")
        ui.body(
            notes,
            "Scry shows a notification while a download or a backup runs and when it finishes, so you can open the " +
                "file. Both still work with notifications off.",
            secondary = true,
        )
        ui.add(notes, ui.button("Notification settings", primary = false) {
            startActivity(
                Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, packageName)
            )
        }, 12f)
        return page
    }

    private fun buildAboutPage(): LinearLayout {
        val page = ui.page()
        val about = ui.card(page, "Scry ${BuildConfig.VERSION_NAME}")
        ui.body(
            about,
            "An independent app for viewing a PhotoPrism library you host yourself, from anywhere. It is not " +
                "affiliated with PhotoPrism, WireGuard or Tailscale.",
        )
        ui.add(about, ui.button("Run the setup wizard") {
            startActivity(Intent(this, WizardActivity::class.java).putExtra("from_settings", true))
        }, 12f)

        val support = ui.card(page, "Support Scry")
        ui.body(
            support,
            "Scry is free and open source. If it is useful to you, a coffee helps cover the cost of developing and maintaining it. Thank you!",
            secondary = true,
        )
        ui.add(support, ui.button("Buy me a coffee") { Links.open(this, Links.DONATE) }, 12f)

        val help = ui.card(page, "Help and source code")
        Links.USER_GUIDE?.let { guide ->
            ui.add(help, ui.button("Read the user guide", primary = false) { Links.open(this, guide) }, 4f)
        }
        Links.REPO?.let { repo ->
            ui.add(help, ui.button("Source code on GitHub", primary = false) { Links.open(this, repo) }, 8f)
        }
        if (Links.REPO == null) ui.body(help, "Scry's source code and guide will be linked here.", secondary = true)
        ui.add(help, ui.button("Notices and open-source licences", primary = false) {
            startActivity(Intent(this, LicensesActivity::class.java))
        }, 8f)
        ui.body(help, "Tip: long-press the Scry icon on your home screen to open these settings again.", secondary = true, small = true, topDp = 14f)
        return page
    }

    // ---------------------------------------------------------------- behaviour

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        backupUi.onPermissionResult(requestCode, grantResults)
    }

    override fun onResume() {
        super.onResume()
        if (::backupUi.isInitialized) backupUi.refresh()
        val s = runCatching { JSONObject(Core.status()) }.getOrNull()
        val savedVpn = store.get(SecretStore.VPN_CONFIG)?.let { Connector.vpnName(it) } ?: "none"
        statusView.text = "Saved VPN: $savedVpn\n" + when (s?.optString("state")) {
            "up" -> when (s.optString("kind")) {
                "direct" -> "Connected directly (no VPN needed)."
                "tailscale" -> "Tailscale is up."
                else -> "Tunnel is up."
            }
            "connecting" -> "Tunnel is connecting…"
            "error" -> "Tunnel error: ${s.optString("error")}"
            else -> "Tunnel is idle. It starts when you open PhotoPrism and stops shortly after you leave."
        }
    }

    private fun fillFields() {
        val c = current
        val list = { k: String -> c?.optJSONArray(k)?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } } ?: "" }
        endpoint.setText(c?.optString("endpoint") ?: "")
        addresses.setText(list("addresses"))
        dns.setText(list("dns"))
        mtu.setText(c?.optInt("mtu")?.takeIf { it > 0 }?.toString() ?: "")
        peerKey.setText(c?.optString("peerPublicKey") ?: "")
        allowedIps.setText(list("allowedIPs"))
        keepalive.setText(c?.optInt("keepalive")?.takeIf { it > 0 }?.toString() ?: "")
        privateKey.setText("")
        presharedKey.setText("")
        privateKey.hint = if (c != null) "Saved. Leave blank to keep it." else ""
        presharedKey.hint = if (c?.optString("presharedKey").isNullOrEmpty()) "" else "Saved. Leave blank to keep it."
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            current = JSONObject(Core.parseWireGuardConf(text)).getJSONObject("wireguard") // validates
            kind = "wireguard"
            showKind()
            fillFields()
            message("Imported. Review the values, then save.", ok = true)
        } catch (e: Exception) {
            message("Could not import: ${e.message}", ok = false)
        }
    }

    /** Builds a wg-quick document from the form so the Go parser does all validation. */
    private fun buildConfigJson(): String {
        if (kind == "tailscale") return buildTailscaleJson()
        if (kind == "direct") return """{"kind":"direct"}""".also { Core.validateConfig(it, filesDir.resolve("core").absolutePath) }
        fun clean(v: EditText) = v.text.toString().replace('\n', ' ').replace('\r', ' ').trim()
        val priv = clean(privateKey).ifEmpty { current?.optString("privateKey") ?: "" }
        val psk = clean(presharedKey).ifEmpty { current?.optString("presharedKey") ?: "" }
        val sb = StringBuilder("[Interface]\nPrivateKey = $priv\nAddress = ${clean(addresses)}\n")
        if (clean(dns).isNotEmpty()) sb.append("DNS = ${clean(dns)}\n")
        if (clean(mtu).isNotEmpty()) sb.append("MTU = ${clean(mtu)}\n")
        sb.append("\n[Peer]\nPublicKey = ${clean(peerKey)}\n")
        if (psk.isNotEmpty()) sb.append("PresharedKey = $psk\n")
        sb.append("Endpoint = ${clean(endpoint)}\nAllowedIPs = ${clean(allowedIps)}\n")
        if (clean(keepalive).isNotEmpty()) sb.append("PersistentKeepalive = ${clean(keepalive)}\n")
        return Core.parseWireGuardConf(sb.toString())
    }

    private fun buildTailscaleJson(): String {
        fun text(v: EditText) = v.text.toString().replace('\n', ' ').replace('\r', ' ').trim()
        val key = text(tsAuthKey).ifEmpty { currentTs?.optString("authKey") ?: "" }
        val ts = JSONObject().put("hostname", text(tsHost).ifEmpty { "scry-phone" })
        if (key.isNotEmpty()) ts.put("authKey", key)
        if (text(tsControl).isNotEmpty()) ts.put("controlUrl", text(tsControl))
        val json = JSONObject().put("kind", "tailscale").put("tailscale", ts).toString()
        Core.validateConfig(json, filesDir.resolve("core").absolutePath) // throws with a readable reason
        return json
    }

    private fun showKind() {
        val ts = kind == "tailscale"
        val wg = kind == "wireguard"
        val none = kind == "direct"
        wgBox.visibility = if (wg) View.VISIBLE else View.GONE
        tsBox.visibility = if (ts) View.VISIBLE else View.GONE
        noneBox.visibility = if (none) View.VISIBLE else View.GONE
        if (rbWireGuard.isChecked != wg) rbWireGuard.isChecked = wg
        if (rbTailscale.isChecked != ts) rbTailscale.isChecked = ts
        if (rbNone.isChecked != none) rbNone.isChecked = none
    }

    /** Validates the form and returns what a connection needs, or null after showing the error. */
    private fun validated(): Connector.Target? {
        val url = urlField.text.toString().trim()
        try {
            Core.setUpstream(url, "system")
        } catch (e: Exception) {
            select(0)
            urlField.error = e.message
            urlField.requestFocus()
            return null
        }
        // With no VPN the one address is already the local one, so a second address means nothing.
        val local = if (kind == "direct") "" else localField.text.toString().trim()
        if (local.isNotEmpty()) {
            try {
                Core.setUpstream(local, "system") // validates only; the real upstream is set when connecting
                Core.setUpstream(url, "system")
            } catch (e: Exception) {
                select(0)
                localField.error = e.message
                localField.requestFocus()
                return null
            }
        }
        val cfg = try {
            buildConfigJson()
        } catch (e: Exception) {
            select(1) // show the page the problem is on
            message("Invalid VPN settings: ${e.message}", ok = false)
            return null
        }
        return Connector.Target(cfg, url, "system", local.ifEmpty { null }, store.get(SecretStore.SERVER_ID))
    }

    private fun test() {
        val target = validated() ?: return
        testButton.isEnabled = false
        ui.showResult(testResult, "Testing… looking for the server.", null)
        Thread {
            val r = try {
                Health.test(applicationContext, target)
            } catch (e: Exception) {
                Health.Result(false, null, e.message ?: e.toString(), 0)
            }
            runOnUiThread {
                testButton.isEnabled = true
                val code = r.code?.let { "HTTP $it" } ?: "no response"
                if (r.ok) ui.showResult(testResult, "Connected ${r.route}. ${r.message} ($code), ready in ${r.millis} ms.", true)
                else ui.showResult(testResult, "Failed: ${r.message}\nResult: $code after ${r.millis} ms.", false)
            }
        }.start()
    }

    private fun save() {
        val target = validated() ?: return
        ConnectionSave.save(this, target)
        ConnectionSave.setWizardDone(this) // someone who set things up by hand needs no wizard
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }

    /** Shows a short note in the save bar: green when it went well, red when it did not. */
    private fun message(t: String, ok: Boolean? = null) {
        messageView.visibility = if (t.isEmpty()) View.GONE else View.VISIBLE
        ui.showResult(messageView, t, ok)
    }

    companion object {
        private const val REQ_IMPORT = 1
    }
}
