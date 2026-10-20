package app.scry

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast

/** Web addresses the app points people to. Change them here, in one place. */
object Links {
    const val DONATE = "https://buymeacoffee.com/supersteve"

    /** Where a person creates a Tailscale auth key. */
    const val TAILSCALE_KEYS = "https://login.tailscale.com/admin/settings/keys"

    /**
     * The project's GitHub page, for example "https://github.com/owner/scry". It is null until
     * the repository exists, and the buttons that need it are hidden until then so the app never
     * points anyone at a wrong address.
     */
    val REPO: String? = "https://github.com/superstevesoftware/scry"

    /** The step-by-step user guide in the repository. */
    val USER_GUIDE: String? get() = REPO?.let { "$it/blob/main/docs/USER_GUIDE.md" }

    /** The part of the guide about backing up photos. */
    val BACKUP_GUIDE: String? get() = USER_GUIDE?.let { "$it#back-up-your-photos" }

    /**
     * Opens [url] in the person's regular browser, so they can switch between it and Scry. If
     * the device has no browser (some televisions), the address is copied and shown instead.
     */
    fun open(ctx: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            if (ctx !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Link", url))
            Toast.makeText(ctx, "No browser found. The address was copied: $url", Toast.LENGTH_LONG).show()
        }
    }
}
