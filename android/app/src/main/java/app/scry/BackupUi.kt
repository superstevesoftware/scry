package app.scry

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.text.InputType
import android.text.format.DateUtils
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.Spinner
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Calendar

/** The Backup page of the settings screen. */
class BackupUi(private val activity: Activity, private val ui: Ui, private val page: LinearLayout) {
    private val prefs = BackupPrefs(activity)
    private lateinit var enable: Switch
    private lateinit var password: EditText
    private lateinit var status: TextView
    private lateinit var message: TextView
    private lateinit var dateButton: Button
    private var suppress = false // set while the UI is being filled from saved settings

    fun build() {
        val intro = ui.card(page, "Backup")
        ui.body(
            intro,
            "Sends new photos and videos from this phone to your PhotoPrism library. It only adds files: " +
                "deleting a photo on the phone never deletes it from PhotoPrism, and edits are not synced. " +
                "It is not a replacement for a second copy of your photos.",
            secondary = true,
        )
        enable = ui.switchRow(intro, "Back up photos and videos", false) { on -> if (!suppress) if (on) turnOn() else turnOff() }

        password = ui.field(
            intro, "PhotoPrism app password", "",
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        password.hint = if (prefs.appPassword != null) "Saved. Leave blank to keep it." else "Paste the app password"
        ui.body(
            intro,
            "Create one in PhotoPrism under Settings, Account, Apps and Devices. It is kept encrypted on this phone and used only for backup.",
            secondary = true, small = true,
        )

        val what = ui.card(page, "What to back up")
        ui.label(what, "Start from")
        val group = RadioGroup(activity).apply { orientation = RadioGroup.VERTICAL }
        val now = ui.radio("Now (only photos taken from here on)").apply { id = View.generateViewId() }
        val all = ui.radio("Everything on this phone").apply { id = View.generateViewId() }
        val date = ui.radio("A date").apply { id = View.generateViewId() }
        listOf(now, all, date).forEach { group.addView(it) }
        ui.add(what, group, 4f)
        dateButton = ui.button("", primary = false) { pickDate(group, date) }
        ui.add(what, dateButton, 6f)
        group.check(when (prefs.startMode) { "all" -> all.id; "date" -> date.id; else -> now.id })
        group.setOnCheckedChangeListener { _, id ->
            if (suppress) return@setOnCheckedChangeListener
            when (id) {
                now.id -> prefs.startMode = "now"
                all.id -> prefs.startMode = "all"
                date.id -> { prefs.startMode = "date"; if (prefs.startDate == 0L) pickDate(group, date) }
            }
            refreshDate()
        }
        if (prefs.started) {
            val apply = ui.button("Apply this start point now", primary = false) {
                AlertDialog.Builder(activity)
                    .setMessage("Restart backup from this point? Photos PhotoPrism already has are skipped, so nothing is duplicated.")
                    .setPositiveButton("Restart") { _, _ -> prefs.applyStartPoint(); toast("Backup will restart from this point.") }
                    .setNegativeButton("Cancel", null).show()
            }
            ui.add(what, apply, 10f)
        }

        val when_ = ui.card(page, "When")
        ui.switchRow(when_, "Only on Wi-Fi or another unmetered network", prefs.wifiOnly) { on ->
            prefs.wifiOnly = on; Backup.reschedule(activity)
        }
        ui.switchRow(when_, "Only while charging (scheduled runs)", prefs.chargingOnly) { on ->
            prefs.chargingOnly = on; Backup.reschedule(activity)
        }
        ui.label(when_, "How often")
        val choices = listOf("Every 6 hours" to 6, "Every 12 hours" to 12, "Daily" to 24, "Weekly" to 168)
        val spinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, choices.map { it.first })
            minimumHeight = ui.minTarget
            setSelection(choices.indexOfFirst { it.second == prefs.intervalHours }.coerceAtLeast(2))
            onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
                override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    if (choices[pos].second != prefs.intervalHours) {
                        prefs.intervalHours = choices[pos].second
                        Backup.reschedule(activity)
                    }
                }
                override fun onNothingSelected(p: AdapterView<*>?) {}
            }
        }
        ui.add(when_, spinner, 4f)
        ui.body(when_, "Android decides the exact time to save battery, so a run can happen later than the interval.", secondary = true, small = true)

        val run = ui.card(page, "Run")
        ui.add(run, ui.button("Back up now") { backUpNow() }, 8f)
        status = ui.body(run, "", secondary = true, topDp = 12f)
        message = ui.body(run, "", topDp = 6f)

        suppress = true
        enable.isChecked = prefs.enabled
        suppress = false
        refreshDate()
        refresh()
    }

    /** Called when the screen comes back to the front, to show the result of a background run. */
    fun refresh() {
        val last = if (prefs.lastRunMillis == 0L) "Backup has not run yet."
        else "Last run ${DateUtils.getRelativeTimeSpanString(prefs.lastRunMillis)}: ${prefs.lastResult}"
        status.text = last
        if (!prefs.enabled) return
        // Asking WorkManager can block briefly, so do it off the main thread.
        Thread {
            val next = runCatching { Backup.nextRunMillis(activity) }.getOrNull()
            val line = if (next == null) "No scheduled run is queued."
            else "Next scheduled run: " + DateUtils.formatDateTime(
                activity, next, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
            ) + " at the earliest. Android may run it later to save battery."
            activity.runOnUiThread { status.text = "$last\n$line" }
        }.start()
    }

    private fun turnOn() {
        val typed = password.text.toString().trim()
        if (typed.isEmpty() && prefs.appPassword == null) {
            fail("Paste your PhotoPrism app password first.")
            return
        }
        if (Backup.hasMediaAccess(activity)) {
            finishTurningOn(typed)
            return
        }
        AlertDialog.Builder(activity)
            .setTitle("Allow access to your photos?")
            .setMessage(
                "To back up, Scry needs to read all of your photos and videos, and to see where they were taken " +
                    "(otherwise Android removes the location from each copy). Choose \"Allow all\" when asked."
            )
            .setPositiveButton("Continue") { _, _ -> activity.requestPermissions(Backup.requiredPermissions(), REQ_BACKUP) }
            .setNegativeButton("Not now") { _, _ -> setSwitch(false) }
            .setCancelable(false)
            .show()
    }

    fun onPermissionResult(requestCode: Int, results: IntArray) {
        if (requestCode != REQ_BACKUP) return
        if (results.isNotEmpty() && results.all { it == PackageManager.PERMISSION_GRANTED } && Backup.hasMediaAccess(activity)) {
            finishTurningOn(password.text.toString().trim())
        } else {
            fail("Backup needs access to all photos and videos, including their location. Allow it in Android's app settings for Scry.")
        }
    }

    private fun finishTurningOn(typedPassword: String) {
        if (typedPassword.isNotEmpty()) {
            prefs.appPassword = typedPassword
            prefs.userUid = null // a new password may belong to a different user
            password.setText("")
            password.hint = "Saved. Leave blank to keep it."
        }
        prefs.enabled = true
        if (!prefs.started) prefs.applyStartPoint()
        Backup.reschedule(activity)
        ui.showResult(message, "Backup is on. Use Back up now to run it straight away.", true)
    }

    private fun turnOff() {
        prefs.enabled = false
        Backup.reschedule(activity)
        ui.showResult(message, "Backup is off.", null)
    }

    private fun backUpNow() {
        val typed = password.text.toString().trim()
        if (typed.isNotEmpty() && prefs.appPassword != typed) {
            prefs.appPassword = typed
            prefs.userUid = null
            password.setText("")
            password.hint = "Saved. Leave blank to keep it."
        }
        when {
            prefs.appPassword == null -> fail("Paste your PhotoPrism app password first.")
            !Backup.hasMediaAccess(activity) -> {
                setSwitch(false)
                turnOn() // explains and asks for the permissions
            }
            else -> {
                if (!prefs.started) prefs.applyStartPoint()
                Backup.runNow(activity)
                ui.showResult(message, if (prefs.wifiOnly) "Queued. It starts as soon as there is Wi-Fi." else "Queued. It starts now.", true)
            }
        }
    }

    private fun pickDate(group: RadioGroup, dateRadio: RadioButton) {
        val c = Calendar.getInstance().apply { if (prefs.startDate > 0) timeInMillis = prefs.startDate * 1000 }
        DatePickerDialog(activity, { _, y, m, d ->
            val chosen = Calendar.getInstance().apply { clear(); set(y, m, d, 0, 0, 0) }
            prefs.startDate = chosen.timeInMillis / 1000
            prefs.startMode = "date"
            suppress = true; group.check(dateRadio.id); suppress = false
            refreshDate()
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun refreshDate() {
        dateButton.visibility = if (prefs.startMode == "date") View.VISIBLE else View.GONE
        dateButton.text = if (prefs.startDate > 0)
            "From " + DateUtils.formatDateTime(activity, prefs.startDate * 1000, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_YEAR)
        else "Choose a date"
    }

    private fun setSwitch(on: Boolean) {
        suppress = true; enable.isChecked = on; suppress = false
    }

    private fun fail(why: String) {
        setSwitch(false)
        ui.showResult(message, why, false)
    }

    private fun toast(t: String) = Toast.makeText(activity, t, Toast.LENGTH_SHORT).show()

    companion object {
        const val REQ_BACKUP = 10
    }
}
