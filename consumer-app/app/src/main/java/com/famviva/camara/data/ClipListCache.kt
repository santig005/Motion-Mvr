package com.famviva.camara.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the last successful clip listing to app-private storage, so the Days/Clips screens have
 * something to show immediately on a cold start — before the Drive round-trip finishes, or if it
 * fails outright (no connectivity).
 */
class ClipListCache(context: Context) {
    private val file = File(context.filesDir, "clips_cache.json")

    fun load(): List<Clip> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Clip(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    sizeBytes = o.optLong("sizeBytes", 0L),
                    durationMillis = if (o.has("durationMillis")) o.optLong("durationMillis") else null,
                    thumbnailLink = o.optString("thumbnailLink").ifBlank { null },
                    thumbFileId = o.optString("thumbFileId").ifBlank { null },
                    yavgMax = if (o.has("yavgMax")) o.optDouble("yavgMax") else null,
                    framesMov = if (o.has("framesMov")) o.optInt("framesMov") else null,
                    durationSec = if (o.has("durationSec")) o.optDouble("durationSec") else null,
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(clips: List<Clip>) {
        val arr = JSONArray()
        clips.forEach { c ->
            arr.put(
                JSONObject().apply {
                    put("id", c.id)
                    put("name", c.name)
                    put("sizeBytes", c.sizeBytes)
                    c.durationMillis?.let { put("durationMillis", it) }
                    c.thumbnailLink?.let { put("thumbnailLink", it) }
                    c.thumbFileId?.let { put("thumbFileId", it) }
                    c.yavgMax?.let { put("yavgMax", it) }
                    c.framesMov?.let { put("framesMov", it) }
                    c.durationSec?.let { put("durationSec", it) }
                },
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }
}
