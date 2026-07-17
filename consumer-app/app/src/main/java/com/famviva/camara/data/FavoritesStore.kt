package com.famviva.camara.data

import android.content.Context
import org.json.JSONObject

/**
 * Persists the user's starred clips — not just their ids but enough metadata to render and play
 * them even after the Drive original is gone. The NVR's cloud-sync purges Drive clips older than
 * ~30 days regardless of favorites, so a star is only a real promise if the app keeps the clip out
 * of Drive's reach: a permanent local copy (see [OfflineStore] / MainViewModel) plus this metadata,
 * so a favorited moment survives independently of the live Drive listing.
 */
class FavoritesStore(context: Context) {
    private val prefs = context.getSharedPreferences("favorite_clips", Context.MODE_PRIVATE)

    /** Set of favorited clip ids (for fast membership checks). */
    fun all(): Set<String> = meta().keys().asSequence().toSet()

    fun isFavorite(id: String): Boolean = id in all()

    /** Every favorite, reconstructed from stored metadata (independent of the live Drive listing),
     *  so purged clips still show and play from their local copy. */
    fun favorites(): List<Clip> {
        val m = meta()
        return m.keys().asSequence().mapNotNull { id ->
            m.optJSONObject(id)?.let { o ->
                Clip(
                    id = id,
                    name = o.optString("name"),
                    sizeBytes = o.optLong("size"),
                    thumbnailLink = o.optString("thumbLink").ifBlank { null },
                    thumbFileId = o.optString("thumbId").ifBlank { null },
                    yavgMax = if (o.has("yavg")) o.optDouble("yavg") else null,
                    framesMov = if (o.has("frames")) o.optInt("frames") else null,
                    durationSec = if (o.has("dur")) o.optDouble("dur") else null,
                )
            }
        }.toList()
    }

    /** Favorited clip basenames without extension (e.g. "mt_20260601_143200"), for the Drive marker
     *  the NVR reads. Skips any favorite whose name hasn't been backfilled yet. */
    fun favoriteNames(): List<String> =
        favorites().map { it.name.removeSuffix(".mp4") }.filter { it.isNotBlank() }

    /** Toggles the star, persisting the clip's metadata when adding. Returns true if now a favorite. */
    fun toggle(clip: Clip): Boolean {
        val m = meta()
        val nowFavorite: Boolean
        if (m.has(clip.id)) {
            m.remove(clip.id); nowFavorite = false
        } else {
            m.put(clip.id, metaOf(clip)); nowFavorite = true
        }
        save(m)
        return nowFavorite
    }

    fun remove(id: String) {
        val m = meta()
        if (m.has(id)) { m.remove(id); save(m) }
    }

    /** Enrich stored favorite metadata from a fresh Drive listing — backfills id-only favorites
     *  migrated from the old format, so they keep full data once the Drive original is purged. */
    fun backfill(clips: List<Clip>) {
        val m = meta()
        var changed = false
        clips.forEach { clip ->
            if (m.has(clip.id) && m.optJSONObject(clip.id)?.optString("name").isNullOrBlank()) {
                m.put(clip.id, metaOf(clip)); changed = true
            }
        }
        if (changed) save(m)
    }

    private fun metaOf(clip: Clip): JSONObject = JSONObject().apply {
        put("name", clip.name)
        put("size", clip.sizeBytes)
        clip.thumbnailLink?.let { put("thumbLink", it) }
        clip.thumbFileId?.let { put("thumbId", it) }
        clip.yavgMax?.let { put("yavg", it) }
        clip.framesMov?.let { put("frames", it) }
        clip.durationSec?.let { put("dur", it) }
    }

    private fun save(m: JSONObject) = prefs.edit().putString(KEY_META, m.toString()).apply()

    /** The metadata map (id -> fields). Migrates the old id-only string-set on first read: stars are
     *  kept (empty objects) and [backfill] fills in the details from the next Drive listing. */
    private fun meta(): JSONObject {
        prefs.getString(KEY_META, null)?.let { return runCatching { JSONObject(it) }.getOrDefault(JSONObject()) }
        val migrated = JSONObject()
        (prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()).forEach { migrated.put(it, JSONObject()) }
        if (migrated.length() > 0) save(migrated)
        return migrated
    }

    private companion object {
        const val KEY_IDS = "ids"       // legacy (pre-metadata) id-only set, migrated on read
        const val KEY_META = "meta"
    }
}
