package app.scry

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * A one-time reminder, a few minutes after the setup wizard, to set up photo backup. Backup needs
 * an app password from PhotoPrism, so the person usually is not ready for it during setup. The
 * reminder is only shown if notifications are allowed, only once, and not if backup is already on.
 */
object BackupTip {
    private const val WORK = "scry-backup-tip"
    const val CHANNEL = "tips"
    private const val NOTE_ID = 4201

    fun schedule(ctx: Context) {
        val request = OneTimeWorkRequestBuilder<BackupTipWorker>().setInitialDelay(3, TimeUnit.MINUTES).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
    }

    fun show(ctx: Context) {
        val prefs = ctx.getSharedPreferences("ui", Context.MODE_PRIVATE)
        if (prefs.getBoolean("backup_tip_shown", false) || BackupPrefs(ctx).enabled) return
        val notes = ctx.getSystemService(NotificationManager::class.java)
        if (!notes.areNotificationsEnabled()) return
        notes.createNotificationChannel(NotificationChannel(CHANNEL, "Tips", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(
            ctx, NOTE_ID, Intent(ctx, BackupHelpActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        notes.notify(
            NOTE_ID,
            Notification.Builder(ctx, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_scry)
                .setContentTitle("Back up your photos")
                .setContentText("Tap to see how to send this phone's photos to PhotoPrism.")
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
        )
        prefs.edit().putBoolean("backup_tip_shown", true).apply()
    }
}

class BackupTipWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        BackupTip.show(ctx)
        return Result.success()
    }
}

/** What the reminder opens: the steps to set up backup, with the full instructions one tap away in the browser. */
class BackupHelpActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = Ui(this)
        val page = ui.page()

        val intro = ui.card(page, "Back up your photos")
        ui.body(intro, "Scry can send new photos and videos from this phone to your PhotoPrism library automatically.")

        val steps = ui.card(page, "How to set it up")
        ui.body(steps, "1. Open the instructions in your browser, and keep them open.")
        ui.body(steps, "2. Switch back to Scry. In PhotoPrism, open Settings, then Account, then Apps and Devices, and create an app password. Copy it.")
        ui.body(steps, "3. In Scry, open Settings, then Backup, paste the app password and turn backup on.")

        Links.BACKUP_GUIDE?.let { guide ->
            ui.add(page, ui.button("Open the instructions") { Links.open(this, guide) }, 16f)
        }
        ui.add(page, ui.button("Open Scry's backup settings", primary = Links.BACKUP_GUIDE == null) {
            startActivity(Intent(this, SettingsActivity::class.java).putExtra("tab", "backup"))
            finish()
        }, 10f)
        ui.add(page, ui.button("Not now", primary = false) { finish() }, 10f)

        val holder = FrameLayout(this)
        val max = ui.dp(720f)
        val width = resources.displayMetrics.widthPixels
        holder.addView(page, FrameLayout.LayoutParams(if (width > max) max else -1, -2).apply { gravity = Gravity.CENTER_HORIZONTAL })
        val scroll = ScrollView(this).apply {
            isFocusable = false
            isFocusableInTouchMode = false
            addView(holder)
        }
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Ui.BG)
            addView(scroll, LinearLayout.LayoutParams(-1, -1))
            setOnApplyWindowInsetsListener { v, insets ->
                val i = EdgeInsets.read(insets, ime = false)
                v.setPadding(i.left, i.top, i.right, i.bottom)
                EdgeInsets.consumed(insets)
            }
        })
    }
}
