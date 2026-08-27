package com.famviva.camara.notify

import android.content.Context
import java.time.LocalTime

/**
 * Minimum motion intensity a new clip must reach to fire its alert. Levels map to
 * [com.famviva.camara.data.motionIntensityLevel] (1..5): STRONG mirrors the clip-list "strong only"
 * chip (>=4); MEDIUM_PLUS is moderate and up (>=3). ALL keeps the original behaviour.
 */
enum class AlertIntensity(val minLevel: Int) {
    ALL(1),
    MEDIUM_PLUS(3),
    STRONG(4),
}

/** Remembers the most recent clip already notified, so alerts aren't repeated. */
class NotifyStore(context: Context) {
    private val prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun lastNotified(): String? = prefs.getString(KEY, null)

    fun setLastNotified(name: String) = prefs.edit().putString(KEY, name).apply()

    /** When on, motion alerts are muted during the night window [QUIET_START, QUIET_END) to kill
     *  3 a.m. shadow/bug spam. Health warnings are never muted. Off by default. */
    var quietHours: Boolean
        get() = prefs.getBoolean(KEY_QUIET, false)
        set(value) = prefs.edit().putBoolean(KEY_QUIET, value).apply()

    /** Minimum motion intensity a new clip must reach to fire its alert (ALL by default = current
     *  behaviour). Read by the background poll; a clip whose intensity is unknown always passes
     *  (fail-open — better to over-notify than silently drop a real event). */
    var minAlertLevel: AlertIntensity
        get() = runCatching { AlertIntensity.valueOf(prefs.getString(KEY_ALERT_LEVEL, null) ?: "") }
            .getOrDefault(AlertIntensity.ALL)
        set(value) = prefs.edit().putString(KEY_ALERT_LEVEL, value.name).apply()

    /** When on, only clips an on-device classifier reads as a PERSON fire an alert (Phase-1 people
     *  detection). Off by default = unchanged behaviour. Fails OPEN: if no model is on the device the
     *  classifier is unavailable, every clip's label is unknown, and this filter passes everything —
     *  so turning it on without the model simply behaves like off, never silently drops an event. */
    var peopleOnly: Boolean
        get() = prefs.getBoolean(KEY_PEOPLE_ONLY, false)
        set(value) = prefs.edit().putBoolean(KEY_PEOPLE_ONLY, value).apply()

    /** How many frames the on-device classifier samples per clip. Drive's thumbnail is a single frame
     *  Google picks, so a person present in only part of a clip is the dominant false negative;
     *  sampling several frames evenly across the clip catches them. 1 keeps the old single-frame cost
     *  (and skips the mp4 fetch — falls back to the thumbnail). Higher = better recall at the cost of
     *  one small mp4 fetch + N inferences per new clip. Clamped so a stray value can't ask for hundreds
     *  of decodes. Exposed as a picker so the frame/cost trade-off can be tuned on-device. */
    var detectionFrames: Int
        get() = prefs.getInt(KEY_DET_FRAMES, DETECTION_FRAMES_DEFAULT)
            .coerceIn(DETECTION_FRAMES_MIN, DETECTION_FRAMES_MAX)
        set(value) = prefs.edit()
            .putInt(KEY_DET_FRAMES, value.coerceIn(DETECTION_FRAMES_MIN, DETECTION_FRAMES_MAX)).apply()

    /** True if quiet-hours is enabled and [now] falls in the (possibly midnight-spanning) window. */
    fun inQuietHours(now: LocalTime = LocalTime.now()): Boolean {
        if (!quietHours) return false
        return if (QUIET_START <= QUIET_END) now >= QUIET_START && now < QUIET_END
        else now >= QUIET_START || now < QUIET_END      // window wraps past midnight
    }

    /** Set of already-alerted down cameras (to avoid repeating the alert). */
    fun lastHealthAlert(): String? = prefs.getString(KEY_HEALTH, null)

    fun setHealthAlert(value: String?) =
        prefs.edit().apply { if (value == null) remove(KEY_HEALTH) else putString(KEY_HEALTH, value) }.apply()

    /** Whether a camera was last seen DOWN (no signal / not reporting). Tracked apart from the general
     *  alert so the "recovered / recording again" green-light fires only for the down->up transition the
     *  user acts on (a reboot), not when a battery/sync/disk advisory clears. */
    fun cameraWasDown(): Boolean = prefs.getBoolean(KEY_CAM_DOWN, false)

    fun setCameraDown(down: Boolean) = prefs.edit().putBoolean(KEY_CAM_DOWN, down).apply()

    /** "YYYYMMDD" of the last day the daily recap was posted, so it isn't posted twice for one day. */
    fun lastDigestDay(): String? = prefs.getString(KEY_DIGEST, null)

    fun setLastDigestDay(day: String) = prefs.edit().putString(KEY_DIGEST, day).apply()

    // The away/home state moved to AwayModeStore (which also migrates the old "away_mode" key here).

    private companion object {
        const val KEY = "last_clip"
        const val KEY_HEALTH = "health_alert"
        const val KEY_CAM_DOWN = "camera_down"
        const val KEY_DIGEST = "digest_day"
        const val KEY_QUIET = "quiet_hours"
        const val KEY_ALERT_LEVEL = "alert_level"
        const val KEY_PEOPLE_ONLY = "people_only"
        const val KEY_DET_FRAMES = "detection_frames"
        const val DETECTION_FRAMES_DEFAULT = 4
        const val DETECTION_FRAMES_MIN = 1
        const val DETECTION_FRAMES_MAX = 32
        val QUIET_START: LocalTime = LocalTime.of(23, 0)   // 11 p.m.
        val QUIET_END: LocalTime = LocalTime.of(7, 0)      // 7 a.m.
    }
}
