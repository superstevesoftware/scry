package app.scry

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.graphics.Color
import android.os.Build
import android.window.OnBackInvokedCallback
import android.window.OnBackInvokedDispatcher
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.View
import android.webkit.DownloadListener
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import core.Core

/**
 * Hosts the PhotoPrism PWA. The WebView only ever loads the loopback proxy served by
 * the Go core; the tunnel is held by a lease for as long as this screen is in the foreground.
 */
class MainActivity : Activity() {
    private lateinit var store: SecretStore
    private lateinit var ui: Ui
    private lateinit var web: WebView
    private lateinit var statusPanel: SplashPanel
    private lateinit var webHolder: FrameLayout
    private lateinit var statusText: TextView
    private lateinit var retry: Button
    private lateinit var edit: Button
    private lateinit var revert: Button

    private lateinit var root: FrameLayout
    private var leaseId: String? = null
    private var loaded = false
    private var pageStarted = false
    @Volatile private var inForeground = false
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val uiHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val reconnect = Runnable {
        Log.i(TAG, "network changed, re-checking the route")
        connect(quiet = true, networkChanged = true)
    }
    private var pendingDownload: Triple<String, String?, String?>? = null
    private var barColor = Ui.BG
    private var fileCallback: ValueCallback<Array<Uri>>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        store = SecretStore(this)
        ui = Ui(this)
        Core.setDebugLogging(BuildConfig.DEBUG)
        buildUi()
        // Initialise the cookie store now: the first bootstrap response sets a cookie.
        android.webkit.CookieManager.getInstance().setAcceptCookie(true)
    }

    override fun onStart() {
        super.onStart()
        if (Connector.Target.fromStore(store) == null) {
            // First run goes to the setup wizard; a person who skipped it goes to the plain settings.
            val first = if (ConnectionSave.wizardDone(this)) SettingsActivity::class.java else WizardActivity::class.java
            startActivity(Intent(this, first))
            finish()
            return
        }
        inForeground = true
        // Coming back to a page that is already loaded should not cover it with a status screen.
        connect(quiet = loaded)
        watchNetwork()
    }

    override fun onStop() {
        super.onStop()
        inForeground = false
        unwatchNetwork()
        // The tunnel stays up for the grace period, so quick app switches don't thrash it.
        synchronized(this) {
            leaseId?.let { Core.release(it) }
            leaseId = null
        }
    }

    /**
     * Picks the route (a direct connection when the server answers locally, otherwise the VPN),
     * then loads the page or, if it is already loaded, just swaps the route underneath it.
     */
    private fun connect(quiet: Boolean = false, networkChanged: Boolean = false) {
        val target = Connector.Target.fromStore(store) ?: return
        if (!quiet) showStatus("Connecting…")
        Thread {
            try {
                if (networkChanged) {
                    Core.networkInfoChanged()
                    Core.networkChanged() // rebind the tunnel's sockets first
                }
                val route = Connector.open(this, target) { if (!quiet) ui(it) }
                Log.i(TAG, "connect: ${Connector.describe(route)} in ${route.millis} ms")
                val old: String?
                val stale: Boolean
                synchronized(this) {
                    stale = !inForeground // the user left while we were connecting
                    old = leaseId
                    leaseId = if (stale) null else route.lease
                }
                if (stale) Core.release(route.lease)
                old?.let { Core.release(it) }
                if (!route.direct || Connector.isNoVpn(target.vpnConfig)) {
                    // A config saved from settings is kept until it has worked once.
                    store.remove(SecretStore.VPN_PENDING)
                    store.remove(SecretStore.VPN_PREV)
                }
                runOnUiThread { if (!loaded) loadApp() else setPanelVisible(false) }
            } catch (e: Exception) {
                Log.w(TAG, "connect failed", e)
                runOnUiThread { showError(e.message ?: e.toString()) }
            }
        }.start()
    }

    /**
     * Re-checks the route when the device's network changes (for example Wi-Fi to cellular, or
     * leaving home), so a direct connection that stopped working falls back to the VPN and the
     * tunnel rebinds to the new network. This only reacts to a change; it never inspects what
     * kind of network it is.
     */
    private fun watchNetwork() {
        if (netCallback != null) return
        val cm = getSystemService(ConnectivityManager::class.java)
        val ignoreUntil = System.currentTimeMillis() + 3_000 // the callback fires once on registration
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = changed()
            override fun onLost(network: Network) = changed()
            private fun changed() {
                if (System.currentTimeMillis() < ignoreUntil) return
                uiHandler.removeCallbacks(reconnect)
                uiHandler.postDelayed(reconnect, 1_500) // changes tend to arrive in bursts
            }
        }
        netCallback = cb
        cm.registerDefaultNetworkCallback(cb)
    }

    private fun unwatchNetwork() {
        netCallback?.let { runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) } }
        netCallback = null
        uiHandler.removeCallbacks(reconnect)
    }

    private fun loadApp(attempt: Int = 1) {
        val port = Core.uiPort()
        Log.i(TAG, "loadApp port=$port attempt=$attempt")
        val secret = Core.uiBootstrapSecret()
        loaded = true
        pageStarted = false
        setPanelVisible(false)
        web.loadUrl("http://127.0.0.1:$port/__scry/bootstrap?s=$secret")
        // A navigation can occasionally be dropped in a freshly started WebView, which
        // leaves a blank page. If nothing has started shortly, retry with a new secret.
        web.postDelayed({
            if (pageStarted || isFinishing || isDestroyed) return@postDelayed
            if (attempt < 3) {
                Log.w(TAG, "no page started after bootstrap, retrying")
                loadApp(attempt + 1)
            } else {
                loaded = false
                showError("The page did not load. Check the connection and try again.")
            }
        }, 6_000)
    }

    private fun buildUi() {
        web = WebView(this).apply {
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                allowFileAccess = false
                allowContentAccess = false
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                setSupportMultipleWindows(false)
            }
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                    pageStarted = true
                    Log.i(TAG, "page started ${url.substringBefore('?')}")
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
                    updateBackHandling() // the page just moved, so whether "back" has somewhere to go may have changed
                }

                override fun onPageFinished(view: WebView, url: String) {
                    Log.i(TAG, "page finished ${url.substringBefore('?')} size=${view.width}x${view.height}")
                    // The SPA renders after load, so sample now and again shortly after.
                    updateBarColor()
                    view.postDelayed({ updateBarColor() }, 1_500)
                }

                override fun onReceivedError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceError) {
                    Log.w(TAG, "load error ${r.url.path} main=${r.isForMainFrame}: ${e.errorCode} ${e.description}")
                }

                override fun onReceivedHttpError(view: WebView, r: WebResourceRequest, e: android.webkit.WebResourceResponse) {
                    Log.w(TAG, "http ${e.statusCode} ${r.url.path} main=${r.isForMainFrame}")
                }

                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    Log.i(TAG, "navigate ${u.scheme}://${u.host}:${u.port}${u.path} redirect=${request.isRedirect}")
                    val ours = (u.host == "127.0.0.1" || u.host == "localhost") && u.port == Core.uiPort().toInt()
                    if (ours) return false
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, u)) } // other origins -> system browser
                    return true
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    Log.w(TAG, "permission request origin=${request.origin} resources=${request.resources.joinToString()}")
                    request.deny()
                }

                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    Log.i(TAG, "progress $newProgress ${view.url?.substringBefore('?')}")
                }

                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    Log.i(TAG, "console ${m.messageLevel()} ${m.message().take(200)}")
                    return true
                }

                override fun onShowFileChooser(
                    view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams
                ): Boolean {
                    fileCallback?.onReceiveValue(null)
                    fileCallback = callback
                    return try {
                        startActivityForResult(params.createIntent(), REQ_FILE)
                        true
                    } catch (e: Exception) {
                        fileCallback = null
                        false
                    }
                }
            }
            // Downloads are fetched by a background job through the tunnel, not by the WebView.
            setDownloadListener(DownloadListener { url, _, disposition, mime, length ->
                // The query string can carry PhotoPrism's download token, so log the path only.
                Log.i(TAG, "download path=${Uri.parse(url).path} scheme=${Uri.parse(url).scheme} disposition=$disposition mime=$mime length=$length")
                startDownload(url, disposition, mime)
            })
        }

        retry = ui.button("Retry") { onStart() }
        edit = ui.button("Edit connection settings", primary = false) {
            // With no VPN there is nothing to fix on the VPN tab; the address lives on the Connection tab.
            val noVpn = store.get(SecretStore.VPN_CONFIG)?.let { Connector.isNoVpn(it) } == true
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java).putExtra("tab", if (noVpn) "connection" else "vpn"))
        }
        revert = ui.button("Revert to previous VPN config", primary = false) {
            store.get(SecretStore.VPN_PREV)?.let { store.put(SecretStore.VPN_CONFIG, it) }
            store.remove(SecretStore.VPN_PREV)
            store.remove(SecretStore.VPN_PENDING)
            onStart()
        }
        // The splash: shown while connecting, and with the buttons below it when connecting fails.
        val splash = SplashPanel(this, ui)
        statusPanel = splash
        statusText = splash.status
        val pad = ui.dp(24f)
        // Buttons share one width so they line up, and stay readable on a wide screen or a TV.
        val w = minOf(resources.displayMetrics.widthPixels - 2 * pad, ui.dp(420f))
        ui.add(splash, retry, 20f, w)
        ui.add(splash, edit, 10f, w)
        ui.add(splash, revert, 10f, w)

        barColor = getSharedPreferences("ui", MODE_PRIVATE).getInt("bar", Ui.BG)
        webHolder = FrameLayout(this).apply { addView(web, FrameLayout.LayoutParams(-1, -1)) }
        root = FrameLayout(this)
        setContentView(root.apply {
            setBackgroundColor(Ui.BG)
            addView(webHolder, FrameLayout.LayoutParams(-1, -1))
            addView(statusPanel, FrameLayout.LayoutParams(-1, -1)) // full screen, over the system bars too
            // targetSdk 35 draws edge-to-edge. The page keeps clear of the status bar, navigation bar and
            // keyboard so PhotoPrism's top-left menu stays usable; the splash fills the screen and only
            // moves its content clear of them.
            setOnApplyWindowInsetsListener { _, insets ->
                val e = EdgeInsets.read(insets, ime = true)
                webHolder.setPadding(e.left, e.top, e.right, e.bottom)
                splash.applyInsets(e.left, e.top, e.right, e.bottom)
                EdgeInsets.consumed(insets)
            }
        })
        applyBarColor(barColor)
    }

    /**
     * Starts a download. The first time, explain why Scry wants to post notifications before
     * the system prompt appears, so the request comes with a reason at the moment it matters.
     */
    private fun startDownload(url: String, disposition: String?, mime: String?) {
        val needsPermission = Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("ui", MODE_PRIVATE)
        if (needsPermission && !prefs.getBoolean("asked_notifications", false)) {
            prefs.edit().putBoolean("asked_notifications", true).apply()
            android.app.AlertDialog.Builder(this)
                .setTitle("Show download progress?")
                .setMessage(
                    "Scry shows a notification while a photo or video downloads, and when it finishes, " +
                        "so you can open it. Downloads work without it, but you won't see progress."
                )
                .setPositiveButton("Continue") { _, _ ->
                    pendingDownload = Triple(url, disposition, mime)
                    requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFY)
                }
                .setNegativeButton("Not now") { _, _ -> enqueueDownload(url, disposition, mime) }
                .setCancelable(false)
                .show()
            return
        }
        enqueueDownload(url, disposition, mime)
    }

    private fun enqueueDownload(url: String, disposition: String?, mime: String?) {
        val problem = DownloadWorker.enqueue(this, url, disposition, mime, Core.uiPort().toInt())
        Toast.makeText(this, problem ?: "Downloading…", Toast.LENGTH_SHORT).show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        // Whatever the answer, the download the user asked for goes ahead.
        if (requestCode == REQ_NOTIFY) pendingDownload?.let { (u, d, m) -> enqueueDownload(u, d, m) }
        pendingDownload = null
    }

    /**
     * Picks the colour for the system-bar areas from the page: its theme-color meta tag if it
     * has one, otherwise the background colour of whatever is drawn at the top-left corner.
     */
    private fun updateBarColor() {
        val js = """(function(){var m=document.querySelector('meta[name=theme-color]');
            if(m&&m.content)return m.content;
            var e=document.elementFromPoint(4,4);
            while(e){var c=getComputedStyle(e).backgroundColor;
              if(c&&c!=='transparent'&&c.indexOf('rgba(0, 0, 0, 0)')<0)return c;e=e.parentElement}
            return ''})()"""
        web.evaluateJavascript(js) { raw ->
            val c = parseCssColor(raw.trim('"'))
            if (c != null && c != barColor) {
                getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("bar", c).apply()
                applyBarColor(c)
            }
        }
    }

    private fun parseCssColor(v: String): Int? {
        if (v.startsWith("#")) return runCatching { Color.parseColor(v) }.getOrNull()
        val n = Regex("""rgba?\((\d+),\s*(\d+),\s*(\d+)""").find(v)?.groupValues ?: return null
        return Color.rgb(n[1].toInt(), n[2].toInt(), n[3].toInt())
    }

    /** Tints the system-bar areas (and connect screen) to the page colour, with legible icons. */
    private fun applyBarColor(c: Int) {
        barColor = c
        webHolder.setBackgroundColor(c)
        // While the (dark) splash covers the screen the system-bar icons must be light, whatever the page colour.
        val light = statusPanel.visibility != View.VISIBLE && androidx_luminance(c) > 0.5
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            val mask = android.view.WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                android.view.WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = if (light)
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR else 0
        }
    }

    private fun androidx_luminance(c: Int): Double =
        (0.299 * Color.red(c) + 0.587 * Color.green(c) + 0.114 * Color.blue(c)) / 255.0

    private fun ui(msg: String) = runOnUiThread { showStatus(msg) }

    /** Shows or hides the splash; hiding fades it out so the page appears smoothly. */
    private fun setPanelVisible(visible: Boolean) {
        statusPanel.animate().cancel()
        if (visible) {
            statusPanel.alpha = 1f
            statusPanel.visibility = View.VISIBLE
            panelChanged()
        } else if (statusPanel.visibility == View.VISIBLE) {
            statusPanel.animate().alpha(0f).setDuration(250).withEndAction {
                statusPanel.visibility = View.GONE
                statusPanel.alpha = 1f
                panelChanged()
            }.start()
        }
    }

    private fun panelChanged() {
        updateBackHandling()
        applyBarColor(barColor) // re-picks the system-bar icon colour
    }

    private fun showStatus(msg: String) {
        setPanelVisible(true)
        statusText.text = msg
        retry.visibility = View.GONE
        edit.visibility = View.GONE
        revert.visibility = View.GONE
    }

    private fun showError(msg: String) {
        showStatus(msg)
        retry.visibility = View.VISIBLE
        edit.visibility = View.VISIBLE
        revert.visibility =
            if (store.get(SecretStore.VPN_PENDING) != null && store.get(SecretStore.VPN_PREV) != null) View.VISIBLE else View.GONE
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQ_FILE) {
            // The callback must always be completed or the page's file input stays stuck.
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // ---------------------------------------------------------------- back button and back gesture

    /** True when "back" should step through the page's own history, like a browser, instead of leaving the app. */
    private fun canGoBackInPage(): Boolean {
        if (statusPanel.visibility == View.VISIBLE) return false // connecting, or showing an error: back leaves
        val history = web.copyBackForwardList()
        if (history.currentIndex <= 0) return false
        // Never step back onto the internal hop that signed the web view in.
        return history.getItemAtIndex(history.currentIndex - 1).url?.contains("/__scry/bootstrap") != true
    }

    private var backCallback: OnBackInvokedCallback? = null

    /**
     * Android 13 and later send the back gesture and the back button to a registered callback, and
     * newer versions no longer call the older onBackPressed at all, which is how back came to close
     * the app. The manifest opts in to callbacks (enableOnBackInvokedCallback), and this one is
     * registered only while the page has history to go back through; otherwise the system's own
     * back behaviour runs, which leaves the app, as a browser would on its first page.
     */
    private fun updateBackHandling() {
        if (Build.VERSION.SDK_INT < 33) return
        val want = canGoBackInPage()
        if (want && backCallback == null) {
            val callback = OnBackInvokedCallback { if (canGoBackInPage()) web.goBack() }
            onBackInvokedDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback)
            backCallback = callback
        } else if (!want && backCallback != null) {
            onBackInvokedDispatcher.unregisterOnBackInvokedCallback(backCallback!!)
            backCallback = null
        }
    }

    /** Android 12 and earlier. */
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (canGoBackInPage()) web.goBack() else super.onBackPressed()
    }

    companion object {
        private const val TAG = "Scry"
        private const val REQ_FILE = 2
        private const val REQ_NOTIFY = 3
    }
}
