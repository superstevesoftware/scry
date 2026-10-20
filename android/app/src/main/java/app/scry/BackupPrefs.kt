package app.scry

import android.content.Context
import org.json.JSONObject

/**
 * Backup settings and progress. Settings and the progress marker are plain preferences; the
 * PhotoPrism app password is the only secret and lives in [SecretStore].
 */
class BackupPrefs(context: Context) {
    private val p = context.getSharedPreferences("backup", Context.MODE_PRIVATE)
    private val secrets = SecretStore(context)

    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    /** Scheduled runs only on an unmetered network (Wi-Fi). On by default. */
    var wifiOnly: Boolean
        get() = p.getBoolean("wifi_only", true)
        set(v) = p.edit().putBoolean("wifi_only", v).apply()

    var chargingOnly: Boolean
        get() = p.getBoolean("charging_only", false)
        set(v) = p.edit().putBoolean("charging_only", v).apply()

    var intervalHours: Int
        get() = p.getInt("interval_hours", 24)
        set(v) = p.edit().putInt("interval_hours", v).apply()

    /** Where a first run starts: "now" (default), "all", or "date". */
    var startMode: String
        get() = p.getString("start_mode", "now") ?: "now"
        set(v) = p.edit().putString("start_mode", v).apply()

    /** Start date in epoch seconds, for startMode "date". */
    var startDate: Long
        get() = p.getLong("start_date", 0)
        set(v) = p.edit().putLong("start_date", v).apply()

    var appPassword: String?
        get() = secrets.get(SecretStore.APP_PASSWORD)
        set(v) {
            if (v.isNullOrEmpty()) secrets.remove(SecretStore.APP_PASSWORD) else secrets.put(SecretStore.APP_PASSWORD, v)
        }

    var lastResult: String
        get() = p.getString("last_result", "") ?: ""
        set(v) = p.edit().putString("last_result", v).apply()

    var lastRunMillis: Long
        get() = p.getLong("last_run", 0)
        set(v) = p.edit().putLong("last_run", v).apply()

    var userUid: String?
        get() = p.getString("uid", null)
        set(v) = p.edit().putString("uid", v).apply()

    val started: Boolean get() = p.contains("watermark")

    /** Sets the progress marker from the chosen start point and forgets what was uploaded. */
    fun applyStartPoint(nowSeconds: Long = System.currentTimeMillis() / 1000) {
        val mark = when (startMode) {
            "all" -> 0L
            "date" -> startDate
            else -> nowSeconds
        }
        p.edit().putLong("watermark", mark).remove("done").remove("attempts").apply()
    }

    fun loadTracker(): BackupTracker {
        if (!started) applyStartPoint()
        val done = mutableMapOf<Long, Long>()
        val attempts = mutableMapOf<Long, Int>()
        runCatching {
            JSONObject(p.getString("done", "{}")!!).let { j -> j.keys().forEach { done[it.toLong()] = j.getLong(it) } }
            JSONObject(p.getString("attempts", "{}")!!).let { j -> j.keys().forEach { attempts[it.toLong()] = j.getInt(it) } }
        }
        return BackupTracker(p.getLong("watermark", 0), done, attempts)
    }

    fun saveTracker(t: BackupTracker) {
        val done = JSONObject().also { j -> t.doneSnapshot().forEach { (k, v) -> j.put(k.toString(), v) } }
        val attempts = JSONObject().also { j -> t.attemptsSnapshot().forEach { (k, v) -> j.put(k.toString(), v) } }
        p.edit().putLong("watermark", t.watermark).putString("done", done.toString()).putString("attempts", attempts.toString()).apply()
    }
}
