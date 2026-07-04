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

    private companion object {
        const val KEY = "last_clip"
        const val KEY_HEALTH = "health_alert"
    }
}
