package app.scry

import android.app.Activity
import android.graphics.Typeface
import android.os.Bundle
import android.text.method.LinkMovementMethod
import android.view.WindowInsets
import android.widget.ScrollView
import android.widget.TextView

/**
 * Shows the notices and open-source licences bundled with the app (assets/licenses.txt, built by
 * tools/gen_licenses.py). Required by the licences of the libraries Scry includes.
 */
class LicensesActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = (16 * resources.displayMetrics.density).toInt()
        val text = TextView(this).apply {
            typeface = Typeface.MONOSPACE
            textSize = 11f
            setPadding(pad, pad, pad, pad)
            setTextIsSelectable(true)
            movementMethod = LinkMovementMethod.getInstance()
            this.text = "Loading…"
        }
        setContentView(ScrollView(this).apply {
            clipToPadding = false
            addView(text)
            // Edge-to-edge on targetSdk 35: keep the text clear of the system bars.
            setOnApplyWindowInsetsListener { v, insets ->
                val i = insets.getInsets(WindowInsets.Type.systemBars())
                v.setPadding(i.left, i.top, i.right, i.bottom)
                WindowInsets.CONSUMED
            }
        })
        // The file is about 120 KB, so read it off the main thread.
        Thread {
            val body = try {
                assets.open("licenses.txt").use { it.readBytes().toString(Charsets.UTF_8) }
            } catch (e: Exception) {
                "The licence file could not be read: ${e.message}"
            }
            runOnUiThread { text.text = body }
        }.start()
    }
}
