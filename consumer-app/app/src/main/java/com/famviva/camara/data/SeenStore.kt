package com.famviva.camara.data

import android.content.Context

/** Persists which clips have already been seen (by id) in SharedPreferences. */
class SeenStore(context: Context) {
    private val prefs = context.getSharedPreferences("seen_clips", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun markSeen(id: String) {
        val cur = all().toMutableSet()
        if (cur.add(id)) prefs.edit().putStringSet(KEY, cur).apply()
    }

    private companion object {
        const val KEY = "ids"
    }
}
