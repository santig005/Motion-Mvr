package com.famviva.camara.data

import android.content.Context
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The local-archive retention policy: how many days of full video to keep on this phone, independent
 * of Drive's own `CLOUD_KEEP_DAYS` (30). `0` = archiving off (the default — bulk downloads are
 * opt-in). Prefs-backed like the app's other small stores; read directly by the Storage screen and
 * by [com.famviva.camara.notify.ArchiveWorker], so no ViewModel plumbing is needed.
 *
 * Note the source is bounded by Drive's 30-day window: the archiver can only download clips still on
 * Drive, so a 360-day horizon means "download everything Drive has, and keep it locally for 360 days
 * before pruning" — not "reach back 360 days" (older footage is already gone from Drive).
 */
class ArchiveStore(context: Context) {
    private val prefs = context.getSharedPreferences("archive_store", Context.MODE_PRIVATE)

    /** Days of video kept locally; 0 = off. Only values in [HORIZONS] (or 0) are ever stored. */
    var horizonDays: Int
        get() = prefs.getInt(KEY_HORIZON, 0)
        set(value) = prefs.edit().putInt(KEY_HORIZON, value).apply()

    val enabled: Boolean get() = horizonDays > 0

    /** The oldest day (YYYYMMDD) still within the horizon; local videos strictly before it are pruned. */
    fun cutoffDateKey(today: LocalDate = LocalDate.now()): String =
        today.minusDays(horizonDays.toLong()).format(DateTimeFormatter.BASIC_ISO_DATE)

    companion object {
        /** Selectable horizons (days). 0 (off) is handled separately in the UI. */
        val HORIZONS = listOf(30, 45, 60, 90, 180, 270, 360)
        private const val KEY_HORIZON = "horizon_days"
    }
}
