package com.famviva.camara.data

import android.content.Context

/**
 * Persists which clips the user starred as favorites (by id), like starred messages in WhatsApp.
 * Favorites are protected from batch deletes (a whole day / everything) so memorable moments survive
 * the routine culling of junk clips.
 */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorite_clips", Context.MODE_PRIVATE)

    fun all(): Set<String> = prefs.getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(id: String): Boolean = id in all()

    /** Toggles the star and returns the new state (true = now a favorite). */
    fun toggle(id: String): Boolean {
        val cur = all().toMutableSet()
        val nowFavorite = if (cur.contains(id)) { cur.remove(id); false } else { cur.add(id); true }
        prefs.edit().putStringSet(KEY, cur).apply()
        return nowFavorite
    }

    private companion object {
        const val KEY = "ids"
    }
}
