package com.famviva.camara.notify

import android.content.Context

/** Remembers the most recent clip already notified, so alerts aren't repeated. */
class NotifyStore(context: Context) {
    private val prefs = context.getSharedPreferences("notify", Context.MODE_PRIVATE)

    fun lastNotified(): String? = prefs.getString(KEY, null)

    fun setLastNotified(name: String) = prefs.edit().putString(KEY, name).apply()

    /** Set of already-alerted down cameras (to avoid repeating the alert). */
    fun lastHealthAlert(): String? = prefs.getString(KEY_HEALTH, null)

    fun setHealthAlert(value: String?) =
        prefs.edit().apply { if (value == null) remove(KEY_HEALTH) else putString(KEY_HEALTH, value) }.apply()

    /**
     * "Away mode". When true (Away, the default) new-clip motion alerts are delivered; when false
     * (Home) they're suppressed — you're there, you don't need them. Only gates the *motion* alerts:
     * health warnings (battery / recording down) always fire. Later a geofence can flip this
     * automatically; for now it's a manual toggle. Defaults to Away so existing users keep their
     * alerts unless they opt into the quiet Home state.
     */
    var away: Boolean
        get() = prefs.getBoolean(KEY_AWAY, true)
        set(value) = prefs.edit().putBoolean(KEY_AWAY, value).apply()

    private companion object {
        const val KEY = "last_clip"
        const val KEY_HEALTH = "health_alert"
        const val KEY_AWAY = "away_mode"
    }
}
