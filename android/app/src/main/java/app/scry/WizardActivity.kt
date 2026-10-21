package app.scry

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import core.Core
import org.json.JSONObject

/**
 * The first-run setup wizard, in everyday words. It asks how the person reaches their library,
 * gathers just what that needs, checks that it works, saves it, and opens PhotoPrism. It can be
 * run again later from the About tab.
 *
 * It never saves anything until the check has passed (or the person chooses to save anyway),
 * so backing out leaves the existing settings untouched.
 */
class WizardActivity : Activity() {
    private lateinit var ui: Ui

    private enum class Way { WIREGUARD, TAILSCALE, HOME }
    private enum class Step { WELCOME, WAY, DETAILS, HOME_ADDRESS, CHECK, DONE }

    private var way: Way? = null
    private var step = Step.WELCOME

    // What the person has told us so far; kept so Back never loses it.
    private var wgJson: String? = null
    private var wgSummary: String = ""
    private var serverUrl = ""
    private var homeUrl = ""
    private var tsKey = ""
    private var tsHost = "scry-phone"

    // The check
    private var checkState = CheckState.IDLE
    private var checkMessage = ""
    private var checkedTarget: Connector.Target? = null

    private enum class CheckState { IDLE, RUNNING, OK, FAILED }

    private var remind = false

    private lateinit var content: ScrollView
    private lateinit var progress: TextView
    private lateinit var back: Button
    private lateinit var next: Button
    private var hasExisting = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ui = Ui(this)
        hasExisting = Connector.Target.fromStore(SecretStore(this)) != null
        val fromSettings = intent.getBooleanExtra("from_settings", false)
        val frame = buildFrame()
        if (fromSettings || hasExisting) {
            setContentView(frame)
        } else {
            // The very first time the app opens, the splash shows for a moment before the wizard.
            val splash = SplashPanel(this, ui).apply {
                status.text = "Welcome"
                setOnApplyWindowInsetsListener { _, insets ->
                    val i = insets.getInsets(WindowInsets.Type.systemBars())
                    applyInsets(i.left, i.top, i.right, i.bottom)
                    insets
                }
            }
            val holder = FrameLayout(this).apply {
                addView(frame, FrameLayout.LayoutParams(-1, -1))
                addView(splash, FrameLayout.LayoutParams(-1, -1))
            }
            setContentView(holder)
            splash.postDelayed({
                if (!isFinishing) splash.animate().alpha(0f).setDuration(350).withEndAction { holder.removeView(splash) }.start()
            }, 1400)
        }
        render()
    }

    // ---------------------------------------------------------------- frame

    private fun buildFrame(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        }
        progress = TextView(this).apply {
            textSize = ui.sp(14f)
            setTextColor(Ui.TEXT_SECONDARY)
            setPadding(ui.dp(20f), ui.dp(16f), ui.dp(20f), ui.dp(4f))
        }
        root.addView(progress)
        content = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            defaultFocusHighlightEnabled = false
        }
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val side = maxOf(ui.dp(16f), (resources.displayMetrics.widthPixels - ui.dp(720f)) / 2 + ui.dp(16f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(Ui.SURFACE)
            setPadding(side, ui.dp(10f), side, ui.dp(12f))
        }
        back = ui.button("Back", primary = false) { goBack() }
        next = ui.button("Next") { goNext() }
        bar.addView(back, LinearLayout.LayoutParams(0, -2, 1f).apply { rightMargin = ui.dp(8f) })
        bar.addView(next, LinearLayout.LayoutParams(0, -2, 2f))
        root.addView(bar, LinearLayout.LayoutParams(-1, -2))
        return root
    }

    /** The steps this person will see, which depend on how they reach their library. */
    private fun steps(): List<Step> = buildList {
        add(Step.WELCOME); add(Step.WAY)
        if (way != null) add(Step.DETAILS)
        if (way == Way.WIREGUARD || way == Way.TAILSCALE) add(Step.HOME_ADDRESS)
        if (way != null) { add(Step.CHECK); add(Step.DONE) }
    }

    private fun render() {
        val all = steps()
        // Until the choice is made the length is not known; the longest path has six steps.
        progress.text = "Step ${all.indexOf(step) + 1} of ${if (way == null) 6 else all.size}"
        val page = ui.page()
        when (step) {
            Step.WELCOME -> welcome(page)
            Step.WAY -> chooseWay(page)
            Step.DETAILS -> details(page)
            Step.HOME_ADDRESS -> homeAddress(page)
            Step.CHECK -> check(page)
            Step.DONE -> done(page)
        }
        val holder = FrameLayout(this)
        val max = ui.dp(720f)
        val width = resources.displayMetrics.widthPixels
        holder.addView(page, FrameLayout.LayoutParams(if (width > max) max else -1, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        content.removeAllViews()
        content.addView(holder, FrameLayout.LayoutParams(-1, -2))
        content.scrollTo(0, 0)

        back.visibility = if (step == Step.WELCOME) View.INVISIBLE else View.VISIBLE
        next.text = when (step) {
            Step.WELCOME -> "Let's start"
            Step.CHECK -> if (checkState == CheckState.OK) "Next" else "Check again"
            Step.DONE -> "Open my library"
            else -> "Next"
        }
        next.isEnabled = canContinue()
        next.alpha = if (next.isEnabled) 1f else 0.4f
        next.requestFocus()
    }

    private fun canContinue(): Boolean = when (step) {
        Step.WAY -> way != null
        Step.CHECK -> checkState != CheckState.RUNNING
        else -> true
    }

    private fun goBack() {
        val all = steps()
        val i = all.indexOf(step)
        if (i <= 0) { finish(); return }
        step = all[i - 1]
        if (step == Step.CHECK) checkState = CheckState.IDLE
        render()
    }

    private fun goNext() {
        when (step) {
            Step.WAY -> {}
            Step.DETAILS -> if (!validateDetails()) return
            Step.HOME_ADDRESS -> if (!validateHome()) return
            Step.CHECK -> {
                if (checkState != CheckState.OK) { startCheck(); return }
            }
            Step.DONE -> { finishWizard(); return }
            else -> {}
        }
        val all = steps()
        step = all[minOf(all.indexOf(step) + 1, all.size - 1)]
        render()
        if (step == Step.CHECK && checkState == CheckState.IDLE) startCheck()
    }

    // ---------------------------------------------------------------- steps

    private fun welcome(page: LinearLayout) {
        val c = ui.card(page, "Welcome to Scry")
        ui.body(
            c,
            "Scry shows your own PhotoPrism photo library on your phone, even when you are away from home. " +
                "It makes its own private connection to your server, so you do not need a separate VPN app.",
        )
        ui.body(
            c,
            "This takes about two minutes. Have your PhotoPrism address ready, and a WireGuard file or a " +
                "Tailscale key if you want to reach your library away from home.",
            secondary = true,
        )
        if (hasExisting) {
            ui.body(c, "You already have a connection saved. Finishing this wizard replaces it.", secondary = true, small = true, topDp = 14f)
        }
        ui.add(c, ui.button("Skip the wizard, I'll use the settings", primary = false) {
            ConnectionSave.setWizardDone(this)
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", "connection"))
            finish()
        }, 18f)
    }

    private fun chooseWay(page: LinearLayout) {
        val c = ui.card(page, "How do you reach your library?")
        val group = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        val options = listOf(
            Triple(Way.WIREGUARD, "Away from home, with WireGuard", "Your server or router gave you a WireGuard file (it ends in .conf)."),
            Triple(Way.TAILSCALE, "Away from home, with Tailscale", "Your server is on your Tailscale network."),
            Triple(Way.HOME, "Only at home", "Use Scry on your home Wi-Fi, with no private connection."),
        )
        val ids = HashMap<Int, Way>()
        options.forEach { (w, title, hint) ->
            val rb = ui.radio("$title\n$hint").apply { id = View.generateViewId(); isChecked = way == w }
            ids[rb.id] = w
            group.addView(rb, RadioGroup.LayoutParams(-1, -2).apply { topMargin = ui.dp(6f) })
        }
        group.setOnCheckedChangeListener { _, id ->
            way = ids[id]
            checkState = CheckState.IDLE
            next.isEnabled = true
            next.alpha = 1f
        }
        ui.add(c, group, 8f)
    }

    private fun details(page: LinearLayout) {
        when (way) {
            Way.WIREGUARD -> {
                val c = ui.card(page, "Add your WireGuard file")
                ui.body(c, "Choose the file your server or router gave you. Scry keeps it private on this phone.", secondary = true)
                ui.add(c, ui.button("Choose the file", primary = false) {
                    startActivityForResult(
                        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), REQ_IMPORT
                    )
                }, 12f)
                importResult = ui.body(c, "", topDp = 10f)
                showImport()
                val a = ui.card(page, "Your library's address")
                urlField = ui.field(a, "PhotoPrism address inside the private network", "http://10.0.0.1:2342", InputType.TYPE_TEXT_VARIATION_URI)
                urlField.setText(serverUrl)
                ui.body(a, "This is the address you would type into a browser while connected to your VPN, with the port (usually :2342).", secondary = true, small = true)
                detailError = ui.body(a, "", topDp = 10f); detailError.visibility = View.GONE
            }
            Way.TAILSCALE -> {
                val c = ui.card(page, "Connect with Tailscale")
                ui.body(
                    c,
                    "In the Tailscale admin page, create an auth key: Settings, then Keys, then Generate auth key. " +
                        "A one-off key is fine. Copy it, then paste it below.",
                    secondary = true,
                )
                ui.add(c, ui.button("Open the Tailscale keys page", primary = false) { Links.open(this, Links.TAILSCALE_KEYS) }, 12f)
                keyField = ui.field(c, "Auth key", "tskey-auth-…", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
                keyField.setText(tsKey)
                hostField = ui.field(c, "Name for this phone (shown in Tailscale)", "scry-phone")
                hostField.setText(tsHost)
                val a = ui.card(page, "Your library's address")
                urlField = ui.field(a, "PhotoPrism address on your Tailscale network", "http://100.x.y.z:2342", InputType.TYPE_TEXT_VARIATION_URI)
                urlField.setText(serverUrl)
                ui.body(a, "Use your server's Tailscale address (it starts with 100.) or its name, with the port (usually :2342).", secondary = true, small = true)
                detailError = ui.body(a, "", topDp = 10f); detailError.visibility = View.GONE
            }
            else -> {
                val a = ui.card(page, "Your home address")
                urlField = ui.field(a, "PhotoPrism address at home", "http://192.168.1.20:2342", InputType.TYPE_TEXT_VARIATION_URI)
                urlField.setText(serverUrl)
                ui.body(a, "This is the address you type into a browser on your home Wi-Fi, with the port (usually :2342).", secondary = true, small = true)
                detailError = ui.body(a, "", topDp = 10f); detailError.visibility = View.GONE
            }
        }
    }

    private fun homeAddress(page: LinearLayout) {
        val c = ui.card(page, "At home too?")
        ui.body(
            c,
            "When you are at home, Scry can skip the private connection and go straight to your server. " +
                "That is faster and easier on the battery. Enter the address you use at home, or leave it blank.",
        )
        homeField = ui.field(c, "PhotoPrism address at home (optional)", "http://192.168.1.20:2342", InputType.TYPE_TEXT_VARIATION_URI)
        homeField.setText(homeUrl)
        detailError = ui.body(c, "", topDp = 10f); detailError.visibility = View.GONE
    }

    private fun check(page: LinearLayout) {
        val c = ui.card(page, "Checking the connection")
        checkView = ui.body(c, "", topDp = 10f)
        showCheck()
        if (checkState == CheckState.FAILED && checkedTarget != null) {
            ui.add(c, ui.button("Save these settings anyway", primary = false) {
                checkState = CheckState.OK
                step = Step.DONE
                render()
            }, 14f)
        }
    }

    private fun done(page: LinearLayout) {
        val c = ui.card(page, "You're all set")
        ui.body(
            c,
            "Scry will open your library now. Sign in with your usual PhotoPrism username and password. " +
                "Your phone can remember them for you.",
        )
        val r = ui.card(page, "Back up your photos?")
        ui.body(
            r,
            "Scry can send new photos and videos from this phone to PhotoPrism automatically. It needs a little " +
                "setup after you have signed in. Allow notifications and Scry will remind you in a few minutes, with simple instructions.",
            secondary = true,
        )
        reminderView = ui.body(r, "", topDp = 10f)
        reminderButton = ui.button("Remind me about backup", primary = false) { askForReminder() }
        ui.add(r, reminderButton, 12f)
        showReminder()
    }

    // ---------------------------------------------------------------- step helpers

    private lateinit var urlField: EditText
    private lateinit var keyField: EditText
    private lateinit var hostField: EditText
    private lateinit var homeField: EditText
    private lateinit var detailError: TextView
    private lateinit var importResult: TextView
    private lateinit var checkView: TextView
    private lateinit var reminderView: TextView
    private lateinit var reminderButton: Button

    private fun showError(msg: String) {
        detailError.visibility = View.VISIBLE
        ui.showResult(detailError, msg, false)
    }

    private fun showImport() {
        if (wgJson != null) ui.showResult(importResult, wgSummary, true)
        else importResult.visibility = View.GONE
    }

    private fun validateUrl(raw: String, what: String): String? {
        val url = raw.trim()
        if (url.isEmpty()) { showError("Please enter $what."); return null }
        return try {
            Core.setUpstream(url, "system") // checks the address is a proper http(s) address
            url
        } catch (e: Exception) {
            showError("That does not look like a web address. It should start with http:// or https://")
            null
        }
    }

    private fun validateDetails(): Boolean {
        val url = validateUrl(urlField.text.toString(), "your PhotoPrism address") ?: return false
        when (way) {
            Way.WIREGUARD -> if (wgJson == null) { showError("Please choose your WireGuard file first."); return false }
            Way.TAILSCALE -> {
                tsKey = keyField.text.toString().trim()
                tsHost = hostField.text.toString().trim().ifEmpty { "scry-phone" }
                try {
                    Core.validateConfig(tailscaleJson(), filesDir.resolve("core").absolutePath)
                } catch (e: Exception) {
                    showError(e.message ?: "Please check the auth key.")
                    return false
                }
            }
            else -> {}
        }
        serverUrl = url
        checkState = CheckState.IDLE
        return true
    }

    private fun validateHome(): Boolean {
        val raw = homeField.text.toString().trim()
        if (raw.isEmpty()) { homeUrl = ""; checkState = CheckState.IDLE; return true }
        validateUrl(raw, "your home address") ?: return false
        Core.setUpstream(serverUrl, "system") // validateUrl set the home address; put the real one back
        homeUrl = raw
        checkState = CheckState.IDLE
        return true
    }

    private fun tailscaleJson(): String {
        val ts = JSONObject().put("hostname", tsHost.ifEmpty { "scry-phone" })
        if (tsKey.isNotEmpty()) ts.put("authKey", tsKey)
        return JSONObject().put("kind", "tailscale").put("tailscale", ts).toString()
    }

    private fun buildTarget(): Connector.Target {
        val cfg = when (way) {
            Way.WIREGUARD -> wgJson ?: error("no WireGuard file")
            Way.TAILSCALE -> tailscaleJson()
            else -> """{"kind":"direct"}"""
        }
        val local = if (way == Way.HOME) null else homeUrl.ifEmpty { null }
        return Connector.Target(cfg, serverUrl, "system", local, SecretStore(this).get(SecretStore.SERVER_ID))
    }

    private fun startCheck() {
        val target = try { buildTarget() } catch (e: Exception) {
            checkState = CheckState.FAILED; checkMessage = e.message ?: "Something is missing."; render(); return
        }
        checkedTarget = target
        checkState = CheckState.RUNNING
        checkMessage = "Connecting… the first time this can take up to a minute."
        render()
        Thread {
            val r = try {
                Health.test(applicationContext, target)
            } catch (e: Exception) {
                Health.Result(false, null, e.message ?: e.toString(), 0)
            }
            runOnUiThread {
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (r.ok) {
                    checkState = CheckState.OK
                    checkMessage = "Connected! PhotoPrism answered ${r.route} in ${r.millis} ms."
                } else {
                    checkState = CheckState.FAILED
                    checkMessage = friendly(r)
                }
                if (step == Step.CHECK) render()
            }
        }.start()
    }

    /** Turns a technical result into something a person can act on. */
    private fun friendly(r: Health.Result): String {
        if (r.code == 401 || r.code == 403) return "PhotoPrism answered, but refused the request."
        val kind = when (way) { Way.TAILSCALE -> "tailscale"; Way.HOME -> "direct"; else -> "wireguard" }
        val text = Health.explain(kind, r.message)
        // If the translator had nothing to add, show the raw reason so it can be reported.
        return if (text == r.message) "Scry could not reach PhotoPrism.\n\nDetails: ${r.message}" else text
    }

    private fun showCheck() {
        val ok = when (checkState) {
            CheckState.OK -> true
            CheckState.FAILED -> false
            else -> null
        }
        ui.showResult(checkView, checkMessage.ifEmpty { "Press Next to check." }, ok)
    }

    private fun askForReminder() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
        } else {
            remind = true
            showReminder()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIFY) {
            remind = grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
            if (step == Step.DONE) showReminder()
        }
    }

    private fun showReminder() {
        if (remind) {
            ui.showResult(reminderView, "Okay. Scry will remind you in a few minutes.", true)
            reminderButton.visibility = View.GONE
        } else {
            ui.showResult(reminderView, "", null)
        }
    }

    // ---------------------------------------------------------------- finishing

    private fun finishWizard() {
        val target = checkedTarget ?: run { render(); return }
        ConnectionSave.save(this, target)
        ConnectionSave.setWizardDone(this)
        if (remind) BackupTip.schedule(this)
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMPORT || resultCode != RESULT_OK) return
        val uri: Uri = data?.data ?: return
        try {
            val text = contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            val json = Core.parseWireGuardConf(text) // validates, and never echoes keys in its errors
            val wg = JSONObject(json).getJSONObject("wireguard")
            wgJson = json
            wgSummary = "Got it. Your VPN server is ${wg.optString("endpoint")}."
            if (step == Step.DETAILS) showImport()
        } catch (e: Exception) {
            wgJson = null
            importResult.visibility = View.VISIBLE
            ui.showResult(importResult, "That file could not be used: ${e.message}", false)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() = goBack()

    companion object {
        private const val REQ_IMPORT = 1
        private const val REQ_NOTIFY = 2
    }
}
