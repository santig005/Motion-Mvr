package com.famviva.camara.data

import android.content.Context
import android.location.Location

/** How the "away" state (which decides whether motion alerts fire) is determined. */
enum class AwayMode {
    /** The user picks Home/Away explicitly. */
    MANUAL,

    /** Derived from location: Away when outside the saved home radius, Home when inside. Evaluated
     *  on each background poll (not OS geofencing) — the alerts are already poll-driven, so instant
     *  transitions would add nothing. */
    AUTO,
}

/**
 * Persisted state for the away/home feature. Owns the mode, the manual choice, and (for AUTO) the
 * saved home location + radius, plus the last evaluated auto result so the UI can show it and the
 * worker has a fallback when it can't get a fresh fix.
 *
 * "Away" means motion alerts are delivered; "Home" means they're muted (health alerts are never
 * gated by this — see [com.famviva.camara.notify.NewClipsWorker]).
 */
class AwayModeStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        // One-time migration of the pre-AUTO manual toggle (was NotifyStore's "notify"/"away_mode").
        if (!prefs.contains(KEY_MANUAL_AWAY)) {
            val legacy = context.getSharedPreferences("notify", Context.MODE_PRIVATE)
            if (legacy.contains(LEGACY_AWAY)) {
                prefs.edit().putBoolean(KEY_MANUAL_AWAY, legacy.getBoolean(LEGACY_AWAY, true)).apply()
            }
        }
    }

    var mode: AwayMode
        get() = runCatching { AwayMode.valueOf(prefs.getString(KEY_MODE, null) ?: "") }
            .getOrDefault(AwayMode.MANUAL)
        set(value) = prefs.edit().putString(KEY_MODE, value.name).apply()

    /** The manual Home/Away choice (used when [mode] is MANUAL). Defaults to Away so alerts keep
     *  firing unless the user opts into the quiet Home state. */
    var manualAway: Boolean
        get() = prefs.getBoolean(KEY_MANUAL_AWAY, true)
        set(value) = prefs.edit().putBoolean(KEY_MANUAL_AWAY, value).apply()

    /** Saved home location (null when not set). Latitude/longitude in degrees. */
    val homeLat: Double? get() = if (prefs.contains(KEY_HOME_LAT)) prefs.getFloat(KEY_HOME_LAT, 0f).toDouble() else null
    val homeLng: Double? get() = if (prefs.contains(KEY_HOME_LNG)) prefs.getFloat(KEY_HOME_LNG, 0f).toDouble() else null

    /** Radius (metres) around home considered "at home". */
    var homeRadiusM: Float
        get() = prefs.getFloat(KEY_HOME_RADIUS, DEFAULT_RADIUS_M)
        set(value) = prefs.edit().putFloat(KEY_HOME_RADIUS, value).apply()

    fun hasHome(): Boolean = homeLat != null && homeLng != null

    fun setHome(lat: Double, lng: Double) {
        prefs.edit()
            .putFloat(KEY_HOME_LAT, lat.toFloat())
            .putFloat(KEY_HOME_LNG, lng.toFloat())
            .apply()
    }

    fun clearHome() {
        prefs.edit().remove(KEY_HOME_LAT).remove(KEY_HOME_LNG).apply()
    }

    /** Last computed AUTO result (true = away). Persisted so the worker keeps the previous decision
     *  when it can't obtain a fresh fix, and the UI can show the current state. */
    var lastAutoAway: Boolean
        get() = prefs.getBoolean(KEY_LAST_AUTO, true)
        set(value) = prefs.edit().putBoolean(KEY_LAST_AUTO, value).apply()

    /** Distance in metres from [location] to the saved home, or null if home isn't set. */
    fun distanceFromHome(location: Location): Float? {
        val lat = homeLat ?: return null
        val lng = homeLng ?: return null
        val out = FloatArray(1)
        Location.distanceBetween(location.latitude, location.longitude, lat, lng, out)
        return out[0]
    }

    private companion object {
        const val PREFS = "away_mode"
        const val KEY_MODE = "mode"
        const val KEY_MANUAL_AWAY = "manual_away"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LNG = "home_lng"
        const val KEY_HOME_RADIUS = "home_radius_m"
        const val KEY_LAST_AUTO = "last_auto_away"
        const val DEFAULT_RADIUS_M = 150f
        const val LEGACY_AWAY = "away_mode"   // old key in the "notify" prefs
    }
}
