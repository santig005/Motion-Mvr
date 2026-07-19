package com.famviva.camara.data

import android.content.Context

/**
 * Tiny snapshot the home-screen widget renders, written by [com.famviva.camara.notify.NewClipsWorker]
 * at the end of every poll. The widget never touches the network itself — it only reads this store —
 * so the freshness it shows ("hace X min", from [Summary.updatedEpoch]) is the honest age of the
 * last successful poll, never a live claim over stale data.
 */
class WidgetSummaryStore(context: Context) {
    private val prefs = context.getSharedPreferences("widget_summary", Context.MODE_PRIVATE)

    /** [updatedEpoch] == 0 means "nothing written yet" (the widget then asks the user to open the app).
     *  [battery] is -1 when unknown; [heartbeatUpdated] is the NVR's last report time (0 if unknown). */
    data class Summary(
        val recordingOk: Boolean,
        val heartbeatUpdated: Long,
        val battery: Int,
        val charging: Boolean,
        val recMode: String?,
        val todayCount: Int,
        val newestClipTime: String?,
        val updatedEpoch: Long,
    ) {
        val hasData: Boolean get() = updatedEpoch > 0L
    }

    fun write(
        recordingOk: Boolean,
        heartbeatUpdated: Long,
        battery: Int?,
        charging: Boolean?,
        recMode: String?,
        todayCount: Int,
        newestClipTime: String?,
        updatedEpoch: Long,
    ) = prefs.edit()
        .putBoolean(KEY_REC_OK, recordingOk)
        .putLong(KEY_HEARTBEAT, heartbeatUpdated)
        .putInt(KEY_BATTERY, battery ?: -1)
        .putBoolean(KEY_CHARGING, charging == true)
        .putString(KEY_REC_MODE, recMode)
        .putInt(KEY_TODAY, todayCount)
        .putString(KEY_NEWEST, newestClipTime)
        .putLong(KEY_UPDATED, updatedEpoch)
        .apply()

    fun read(): Summary = Summary(
        recordingOk = prefs.getBoolean(KEY_REC_OK, false),
        heartbeatUpdated = prefs.getLong(KEY_HEARTBEAT, 0L),
        battery = prefs.getInt(KEY_BATTERY, -1),
        charging = prefs.getBoolean(KEY_CHARGING, false),
        recMode = prefs.getString(KEY_REC_MODE, null),
        todayCount = prefs.getInt(KEY_TODAY, 0),
        newestClipTime = prefs.getString(KEY_NEWEST, null),
        updatedEpoch = prefs.getLong(KEY_UPDATED, 0L),
    )

    private companion object {
        const val KEY_REC_OK = "rec_ok"
        const val KEY_HEARTBEAT = "heartbeat"
        const val KEY_BATTERY = "battery"
        const val KEY_CHARGING = "charging"
        const val KEY_REC_MODE = "rec_mode"
        const val KEY_TODAY = "today_count"
        const val KEY_NEWEST = "newest_time"
        const val KEY_UPDATED = "updated"
    }
}
