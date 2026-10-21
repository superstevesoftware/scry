package app.scry

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.webkit.MimeTypeMap
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import core.Core
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipFile

/**
 * Fetches one file from PhotoPrism through the tunnel and writes it into the shared gallery.
 *
 * The WebView's DownloadListener hands over a loopback URL; only its path and query are used,
 * and the request goes to the worker listener (which has no cookie requirement). Photos land
 * in Pictures/PhotoPrism, videos in Movies/PhotoPrism and everything else (such as the zip
 * from a multi-select) in Download. The file stays hidden (IS_PENDING) until it is complete,
 * so a failed or cancelled download never leaves a partial file in the gallery.
 */
class DownloadWorker(private val ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    private val notes = ctx.getSystemService(NotificationManager::class.java)

    override fun doWork(): Result {
        val target = inputData.getString(KEY_TARGET) ?: return Result.failure()
        val hintName = inputData.getString(KEY_NAME) ?: "download"
        val hintMime = inputData.getString(KEY_MIME) ?: ""
        val store = SecretStore(ctx)
        val conn0 = Connector.Target.fromStore(store) ?: return fail(hintName, "Scry is not set up yet.", retry = false)

        val noteId = id.hashCode()
        ensureChannel()
        setForegroundAsync(foreground(noteId, hintName, -1))

        var lease: String? = null
        var pending: Uri? = null
        try {
            // Same route choice as the main screen: direct when the server answers locally, else the VPN.
            val route = Connector.open(ctx, conn0)
            lease = route.lease

            val conn = URL("http://${Core.workerEndpoint()}$target").openConnection() as HttpURLConnection
            conn.setRequestProperty("X-Scry-Token", Core.workerToken())
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            try {
                val code = conn.responseCode
                Log.i(TAG, "download $hintName -> HTTP $code type=${conn.contentType} length=${conn.contentLengthLong}")
                if (code !in 200..299) {
                    // A 4xx will not fix itself; anything else (502, timeouts) is worth retrying.
                    return fail(hintName, "The server answered HTTP $code.", retry = code !in 400..499)
                }
                val name = fileName(conn.getHeaderField("Content-Disposition"), hintName)
                val mime = mimeFor(conn.contentType?.substringBefore(';')?.trim(), hintMime, name)
                val total = conn.contentLengthLong

                val (collection, folder) = destination(mime)
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(collection, values) ?: throw IOException("Could not create the file in the gallery")
                pending = uri

                var done = 0L
                var lastNote = 0L
                conn.inputStream.use { input ->
                    resolver.openOutputStream(uri)!!.use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            if (isStopped) throw IOException("Cancelled")
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            val now = System.currentTimeMillis()
                            if (now - lastNote > 500) {
                                lastNote = now
                                val pct = if (total > 0) (done * 100 / total).toInt() else -1
                                setForegroundAsync(foreground(noteId, name, pct))
                            }
                        }
                    }
                }
                if (total > 0 && done != total) throw IOException("Download ended early ($done of $total bytes)")

                resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
                pending = null
                if (isZip(mime, name) && ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getBoolean(PREF_UNZIP, true)) {
                    val r = extractZip(uri, noteId, name)
                    Log.i(TAG, "unzip $name: saved=${r.saved} skipped=${r.skipped} failed=${r.failed}")
                    // Remove the zip only when every file in it reached the gallery.
                    if (!r.failed && r.saved > 0 && r.skipped == 0) {
                        resolver.delete(uri, null, null)
                        notifyExtracted(noteId, r.saved)
                        return Result.success()
                    }
                }
                notifyDone(noteId, name, mime, uri)
                return Result.success()
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "download failed", e)
            pending?.let { runCatching { ctx.contentResolver.delete(it, null, null) } }
            return fail(hintName, e.message ?: e.toString(), retry = runAttemptCount < 2 && !isStopped)
        } finally {
            lease?.let { Core.release(it) }
        }
    }

    private class Extract(val saved: Int, val skipped: Int, val failed: Boolean)

    private fun isZip(mime: String, name: String) = mime == "application/zip" || name.endsWith(".zip", ignoreCase = true)

    /**
     * Unpacks a downloaded zip into the gallery: photos to Pictures/PhotoPrism, videos to
     * Movies/PhotoPrism. All-or-nothing: if anything fails, whatever was written is removed
     * and the zip is left alone. Only the file's base name is used, never an entry's path.
     */
    private fun extractZip(zip: Uri, noteId: Int, zipName: String): Extract {
        val resolver = ctx.contentResolver
        val created = mutableListOf<Uri>()
        var skipped = 0
        var tmp: File? = null
        try {
            setForegroundAsync(foreground(noteId, "Extracting $zipName", -1))
            // ZipFile reads the central directory, which has the real sizes. A streaming
            // ZipInputStream cannot read zips written with data descriptors (as PhotoPrism's are)
            // and silently returned empty entries.
            val pfd = resolver.openFileDescriptor(zip, "r") ?: throw IOException("Cannot open the zip")
            val zf = pfd.use {
                try {
                    ZipFile("/proc/self/fd/${it.fd}")
                } catch (e: IOException) {
                    // Some storage backends do not allow reopening the descriptor; use a temp copy.
                    val f = File.createTempFile("unzip", ".zip", ctx.cacheDir).also { f -> tmp = f }
                    resolver.openInputStream(zip)!!.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
                    ZipFile(f)
                }
            }
            zf.use {
                for (entry in zf.entries()) {
                    if (isStopped) throw IOException("Cancelled")
                    if (entry.isDirectory) continue
                    val base = sanitize(entry.name.substringAfterLast('/').substringAfterLast('\\'))
                    val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(base.substringAfterLast('.', "").lowercase())
                    if (mime == null || !(mime.startsWith("image/") || mime.startsWith("video/"))) {
                        skipped++
                        continue
                    }
                    created += writeToGallery(base, mime) { out ->
                        val n = zf.getInputStream(entry).use { it.copyTo(out) }
                        // Never trust a short write: a zip is only deleted if every file is complete.
                        if (entry.size >= 0 && n != entry.size) throw IOException("$base: wrote $n of ${entry.size} bytes")
                        if (n == 0L) throw IOException("$base is empty")
                    }
                }
            }
            return Extract(created.size, skipped, false)
        } catch (e: Exception) {
            Log.w(TAG, "unzip failed", e)
            created.forEach { runCatching { resolver.delete(it, null, null) } }
            return Extract(0, skipped, true)
        } finally {
            tmp?.delete()
        }
    }

    /** Writes one file into the gallery via a pending MediaStore row, removing it on failure. */
    private fun writeToGallery(name: String, mime: String, write: (OutputStream) -> Unit): Uri {
        val resolver = ctx.contentResolver
        val (collection, folder) = destination(mime)
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("Could not create $name in the gallery")
        try {
            resolver.openOutputStream(uri)!!.use(write)
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
        return uri
    }

    private fun sanitize(raw: String) = raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "file" }

    private fun notifyExtracted(noteId: Int, count: Int) {
        val text = if (count == 1) "1 item saved to your gallery" else "$count items saved to your gallery"
        if (!notes.areNotificationsEnabled()) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, text, android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scry)
            .setContentTitle("Download complete")
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        notes.notify(noteId + 1, n)
    }

    private fun fail(name: String, why: String, retry: Boolean): Result {
        if (retry) return Result.retry()
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scry)
            .setContentTitle("Download failed")
            .setContentText("$name: $why")
            .setStyle(Notification.BigTextStyle().bigText("$name: $why"))
            .setAutoCancel(true)
            .build()
        notes.notify(id.hashCode() + 1, n)
        return Result.failure()
    }

    private fun foreground(noteId: Int, name: String, percent: Int): ForegroundInfo {
        val b = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scry)
            .setContentTitle("Downloading")
            .setContentText(name)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent.coerceAtLeast(0), percent < 0)
        return ForegroundInfo(noteId, b.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    }

    private fun notifyDone(noteId: Int, name: String, mime: String, uri: Uri) {
        if (!notes.areNotificationsEnabled()) {
            // No notification to tell the user it finished, so say where it went.
            val where = when {
                mime.startsWith("image/") -> "Pictures/PhotoPrism"
                mime.startsWith("video/") -> "Movies/PhotoPrism"
                else -> "Downloads"
            }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(ctx, "Saved $name to $where", android.widget.Toast.LENGTH_LONG).show()
            }
            return
        }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val pi = PendingIntent.getActivity(ctx, noteId, view, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_scry)
            .setContentTitle("Download complete")
            .setContentText(name)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        notes.notify(noteId + 1, n)
    }

    private fun ensureChannel() {
        notes.createNotificationChannel(NotificationChannel(CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW))
    }

    private fun destination(mime: String): Pair<Uri, String> = when {
        mime.startsWith("image/") ->
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Pictures/PhotoPrism"
        mime.startsWith("video/") ->
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Movies/PhotoPrism"
        else -> MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) to "Download"
    }

    private fun mimeFor(headerMime: String?, hintMime: String, name: String): String {
        val fromName = MimeTypeMap.getSingleton().getMimeTypeFromExtension(name.substringAfterLast('.', "").lowercase())
        val generic = setOf("", "application/octet-stream", "binary/octet-stream")
        return when {
            headerMime != null && headerMime !in generic -> headerMime
            fromName != null -> fromName
            hintMime !in generic -> hintMime
            else -> "application/octet-stream"
        }
    }

    /** Prefers the server's Content-Disposition filename, then the URL's last segment. */
    private fun fileName(disposition: String?, hint: String): String {
        val m = disposition?.let { Regex("""filename\*?=(?:UTF-8'')?"?([^";]+)"?""", RegexOption.IGNORE_CASE).find(it) }
        val raw = m?.groupValues?.get(1)?.let { Uri.decode(it) } ?: hint
        return raw.replace(Regex("""[\\/:*?"<>|]"""), "_").trim().ifEmpty { "download" }
    }

    companion object {
        private const val TAG = "Scry"
        private const val CHANNEL = "downloads"
        const val KEY_TARGET = "target"
        const val KEY_NAME = "name"
        const val KEY_MIME = "mime"
        const val PREF_UNZIP = "unzip_downloads" // in the "ui" preferences; on by default

        /**
         * Queues a download for a URL the WebView reported. Only loopback URLs of the UI
         * proxy are accepted; they are rewritten to the worker listener by the job.
         */
        fun enqueue(ctx: Context, url: String, disposition: String?, mime: String?, uiPort: Int): String? {
            val u = Uri.parse(url)
            if (u.scheme == "blob" || u.scheme == "data") return "This download type is not supported yet."
            if ((u.host != "127.0.0.1" && u.host != "localhost") || u.port != uiPort) return "Not a PhotoPrism download."
            val target = (u.encodedPath ?: "/") + (u.encodedQuery?.let { "?$it" } ?: "")
            val hint = android.webkit.URLUtil.guessFileName(url, disposition, mime)
            val data = Data.Builder()
                .putString(KEY_TARGET, target)
                .putString(KEY_NAME, hint)
                .putString(KEY_MIME, mime ?: "")
                .build()
            WorkManager.getInstance(ctx).enqueue(OneTimeWorkRequestBuilder<DownloadWorker>().setInputData(data).build())
            return null
        }
    }
}
