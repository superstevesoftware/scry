package app.scry

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import core.Core
import java.io.IOException
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * Uploads photos and videos added since the last run to PhotoPrism. It only ever adds files:
 * nothing is deleted on either side and edits are not reconciled. See [BackupTracker] for how
 * progress is tracked and why a failure costs nothing more than repeating unfinished files.
 */
class BackupWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val notes = ctx.getSystemService(NotificationManager::class.java)
    private val prefs = BackupPrefs(ctx)

    /** Ends the whole run; carries the reason to show the user. */
    private class Stop(message: String) : Exception(message)

    override fun doWork(): Result {
        // The scheduled job and "Back up now" can start together. Two runs at once would upload
        // the same files twice, so only one may run; a second one has nothing left to add anyway.
        if (!Backup.runLock.tryLock()) {
            Log.i(TAG, "backup: another run is already in progress, skipping this one")
            return Result.success()
        }
        try {
            return run()
        } finally {
            Backup.runLock.unlock()
        }
    }

    private fun run(): Result {
        ensureChannel()
        setForegroundAsync(foreground("Preparing backup…", 0, 0))

        if (!Backup.hasMediaAccess(ctx)) {
            return finish("Photo access is off. In Settings, allow access to all photos and videos for Scry.", notify = true)
        }
        val password = prefs.appPassword
            ?: return finish("Enter your PhotoPrism app password in Scry's settings to back up.", notify = true)
        val target = Connector.Target.fromStore(SecretStore(ctx))
            ?: return finish("Scry is not set up yet.", notify = true)

        var lease: String? = null
        val tracker = prefs.loadTracker()
        try {
            val route = Connector.open(ctx, target)
            lease = route.lease
            Log.i(TAG, "backup: ${Connector.describe(route)} in ${route.millis} ms")

            val client = PhotoPrismClient(password)
            var uid = prefs.userUid ?: client.userUid().also { prefs.userUid = it }

            val items = queryMedia(tracker.queryFloor()).filter { tracker.needs(it) }
            Log.i(TAG, "backup: ${items.size} item(s) to upload, watermark=${tracker.watermark}")
            if (items.isEmpty()) {
                prefs.saveTracker(tracker.also { it.prune() })
                return finish("Nothing new to back up.", notify = false)
            }

            val sender = Sender(client, tracker, items.size)
            val planner = BatchPlanner()
            try {
                for (item in items) {
                    if (isStopped) throw Stop("Backup was interrupted; it will continue next time.")
                    if (planner.mustFlushBefore(item)) {
                        uid = sender.sendBatch(uid, planner.items.toList())
                        planner.clear()
                        prefs.saveTracker(tracker) // progress survives a crash or a killed process
                    }
                    planner.add(item)
                }
                if (planner.items.isNotEmpty()) uid = sender.sendBatch(uid, planner.items.toList())
            } finally {
                tracker.prune()
                prefs.saveTracker(tracker)
            }
            return finish(summary(tracker), notify = true)
        } catch (e: Stop) {
            prefs.saveTracker(tracker)
            return finish("${e.message} ${summary(tracker)}".trim(), notify = true)
        } catch (e: PhotoPrismClient.ApiException) {
            Log.w(TAG, "backup failed: ${e.message}")
            return finish(e.message ?: "The server refused the request.", notify = true)
        } catch (e: IOException) {
            Log.w(TAG, "backup could not reach the server", e)
            // A flaky connection is worth another try later; a periodic job just runs again.
            if (runAttemptCount < 2 && !isStopped) return Result.retry()
            return finish("Could not reach the server: ${e.message}", notify = true)
        } finally {
            lease?.let { Core.release(it) }
        }
    }

    /** Uploads batches, falling back to one file at a time to find the one that fails. */
    private inner class Sender(val client: PhotoPrismClient, val tracker: BackupTracker, val total: Int) {
        private var handled = 0
        private var lastNote = 0L

        fun sendBatch(uid: String, all: List<MediaItem>): String {
            // A photo deleted since the list was made has nothing to upload; do not call that a failure.
            val (batch, gone) = all.partition { openOriginal(it)?.use { true } ?: false }
            gone.forEach { tracker.skipped(it); handled++ }
            if (batch.isEmpty()) return uid
            if (tryBatch(uid, batch)) {
                batch.forEach { tracker.succeeded(it) }
                handled += batch.size
                progress()
                return uid
            }
            if (batch.size == 1) {
                tracker.failed(batch[0])
                handled++
                progress()
                return uid
            }
            for (item in batch) { // isolate the bad file
                if (tryBatch(uid, listOf(item))) tracker.succeeded(item) else tracker.failed(item)
                handled++
                progress()
            }
            return uid
        }

        /** True if the whole batch was uploaded and imported. Throws [Stop] for errors that apply to every file. */
        private fun tryBatch(uid: String, batch: List<MediaItem>): Boolean {
            val afds = ArrayList<android.content.res.AssetFileDescriptor>()
            try {
                val parts = batch.map { item ->
                    val afd = openOriginal(item) ?: throw IOException("${item.name} is no longer available")
                    afds += afd
                    val size = if (afd.length >= 0) afd.length else item.size
                    MultipartBody.Part("files", item.name, item.mime, size) { afd.createInputStream() }
                }
                val body = MultipartBody(randomHex(16), parts)
                val token = randomHex(12)
                var attempt = 0
                while (true) {
                    try {
                        client.upload(uid, token, body) { }
                        client.process(uid, token)
                        return true
                    } catch (e: PhotoPrismClient.ApiException) {
                        when (e.code) {
                            401, 403 -> throw Stop(e.message ?: "The server refused the upload.")
                            507 -> throw Stop(e.message ?: "The server is out of space.")
                            503 -> if (++attempt < 4) { Thread.sleep(5_000L * attempt); continue }
                        }
                        Log.w(TAG, "batch of ${batch.size} failed: ${e.message}")
                        return false
                    }
                }
            } catch (e: Stop) {
                throw e
            } catch (e: IOException) {
                Log.w(TAG, "batch of ${batch.size} failed: ${e.message}")
                return false
            } finally {
                afds.forEach { runCatching { it.close() } }
            }
        }

        private fun progress() {
            val now = System.currentTimeMillis()
            if (now - lastNote < 800 && handled < total) return
            lastNote = now
            setForegroundAsync(foreground("Backing up photos and videos", handled, total))
        }
    }

    /** Opens the original file, including its GPS position, when the app is allowed to see it. */
    private fun openOriginal(item: MediaItem): android.content.res.AssetFileDescriptor? {
        val base = if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val uri = ContentUris.withAppendedId(base, item.id)
        val original: Uri = try {
            MediaStore.setRequireOriginal(uri) // without this the position is stripped from the copy we read
        } catch (e: Exception) {
            uri
        }
        return try {
            ctx.contentResolver.openAssetFileDescriptor(original, "r")
        } catch (e: Exception) {
            runCatching { ctx.contentResolver.openAssetFileDescriptor(uri, "r") }.getOrNull()
        }
    }

    private fun queryMedia(floor: Long): List<MediaItem> {
        val uri = MediaStore.Files.getContentUri("external")
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID, MediaStore.Files.FileColumns.DISPLAY_NAME, MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.SIZE, MediaStore.Files.FileColumns.DATE_ADDED, MediaStore.Files.FileColumns.MEDIA_TYPE,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
        )
        val selection = "${MediaStore.Files.FileColumns.MEDIA_TYPE} IN (?, ?) AND ${MediaStore.Files.FileColumns.DATE_ADDED} >= ?"
        val args = arrayOf(
            MediaStore.Files.FileColumns.MEDIA_TYPE_IMAGE.toString(), MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO.toString(), floor.toString(),
        )
        val out = ArrayList<MediaItem>()
        ctx.contentResolver.query(uri, projection, selection, args, "${MediaStore.Files.FileColumns.DATE_ADDED} ASC, ${MediaStore.Files.FileColumns._ID} ASC")?.use { c ->
            while (c.moveToNext()) {
                val rel = c.getString(6).orEmpty()
                // Files Scry downloaded from PhotoPrism must not be uploaded straight back.
                if (rel.startsWith("Pictures/PhotoPrism", true) || rel.startsWith("Movies/PhotoPrism", true)) continue
                val name = c.getString(1) ?: continue
                val mime = c.getString(2) ?: continue
                val size = c.getLong(3)
                if (size <= 0) continue
                out += MediaItem(c.getLong(0), c.getInt(5) == MediaStore.Files.FileColumns.MEDIA_TYPE_VIDEO, name, mime, size, c.getLong(4))
            }
        }
        return out
    }

    private fun summary(t: BackupTracker): String {
        val parts = ArrayList<String>()
        parts += "${t.uploaded} uploaded"
        if (t.failed > 0) parts += "${t.failed} failed (will retry)"
        if (t.abandoned > 0) parts += "${t.abandoned} given up on"
        return parts.joinToString(", ") + "."
    }

    private fun finish(message: String, notify: Boolean): Result {
        prefs.lastResult = message
        prefs.lastRunMillis = System.currentTimeMillis()
        Log.i(TAG, "backup finished: $message")
        if (notify && notes.areNotificationsEnabled()) {
            notes.notify(
                NOTE_DONE,
                Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_scry)
                    .setContentTitle("Backup").setContentText(message)
                    .setStyle(Notification.BigTextStyle().bigText(message)).setAutoCancel(true).build()
            )
        }
        return Result.success()
    }

    private fun foreground(text: String, done: Int, total: Int): ForegroundInfo {
        val b = Notification.Builder(ctx, CHANNEL).setSmallIcon(R.drawable.ic_stat_scry)
            .setContentTitle(text).setOngoing(true).setOnlyAlertOnce(true)
        if (total > 0) b.setContentText("$done of $total").setProgress(total, done, false) else b.setProgress(0, 0, true)
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(NOTE_RUN, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(NOTE_RUN, b.build())
    }

    private fun ensureChannel() {
        notes.createNotificationChannel(NotificationChannel(CHANNEL, "Backup", NotificationManager.IMPORTANCE_LOW))
    }

    private fun randomHex(bytes: Int): String =
        ByteArray(bytes).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    companion object {
        private const val TAG = "Scry"
        private const val CHANNEL = "backup"
        private const val NOTE_RUN = 4101
        private const val NOTE_DONE = 4102
    }
}

/** Scheduling and permissions for backup. */
object Backup {
    /** Held for the length of a backup run; see [BackupWorker.doWork]. */
    val runLock = java.util.concurrent.locks.ReentrantLock()

    private const val PERIODIC = "scry-backup-periodic"
    private const val NOW = "scry-backup-now"

    /** The permissions backup needs. Location access is required, or the GPS position is stripped from every photo. */
    fun requiredPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.READ_MEDIA_IMAGES, Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.ACCESS_MEDIA_LOCATION)
        else arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.ACCESS_MEDIA_LOCATION)

    fun hasMediaAccess(ctx: Context): Boolean =
        requiredPermissions().all { ctx.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    private fun constraints(prefs: BackupPrefs, scheduled: Boolean) = Constraints.Builder()
        .setRequiredNetworkType(if (prefs.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .setRequiresCharging(scheduled && prefs.chargingOnly)
        .build()

    /** Applies the current settings: schedules the periodic run, or cancels it when backup is off. */
    fun reschedule(ctx: Context) {
        val prefs = BackupPrefs(ctx)
        val wm = WorkManager.getInstance(ctx)
        if (!prefs.enabled) {
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val request = PeriodicWorkRequestBuilder<BackupWorker>(prefs.intervalHours.toLong(), TimeUnit.HOURS)
            .setConstraints(constraints(prefs, scheduled = true))
            // Without a delay WorkManager runs a periodic job at once, on top of "Back up now".
            .setInitialDelay(prefs.intervalHours.toLong(), TimeUnit.HOURS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 15, TimeUnit.MINUTES)
            .build()
        // UPDATE keeps the timing of an existing schedule while applying changed settings.
        wm.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, request)
        Log.i("Scry", "backup scheduled: every ${prefs.intervalHours} h, wifiOnly=${prefs.wifiOnly}, chargingOnly=${prefs.chargingOnly}")
    }

    /**
     * When Android expects to run the next scheduled backup, or null if none is scheduled. It is
     * the earliest time the job may start: Android can run it later to save battery.
     */
    fun nextRunMillis(ctx: Context): Long? {
        val infos = WorkManager.getInstance(ctx).getWorkInfosForUniqueWork(PERIODIC).get()
        val next = infos.firstOrNull { !it.state.isFinished }?.nextScheduleTimeMillis ?: return null
        return if (next == Long.MAX_VALUE) null else next
    }

    /** Runs a backup as soon as the network condition is met. */
    fun runNow(ctx: Context) {
        val prefs = BackupPrefs(ctx)
        val request = OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(constraints(prefs, scheduled = false)).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, request)
    }
}
