package com.famviva.camara.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the local clip catalog — the UNION of Drive, the local archive and metadata-only history —
 * to app-private JSON, matching the app's other stores ([ClipListCache], [FavoritesStore]); the app
 * deliberately has no Room DB. Cached so the History view shows the full timeline instantly on a cold
 * start, then rebuilt from fresh sources on each successful load via [merge].
 */
class CatalogStore(context: Context) {
    private val file = File(context.filesDir, "clip_catalog.json")

    fun load(): List<ClipRecord> {
        if (!file.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                ClipRecord(
                    name = o.getString("name"),
                    sizeBytes = o.optLong("size", 0L),
                    durationSec = if (o.has("dur")) o.optDouble("dur") else null,
                    yavgMax = if (o.has("yavg")) o.optDouble("yavg") else null,
                    framesMov = if (o.has("frames")) o.optInt("frames") else null,
                    onDrive = o.optBoolean("onDrive", false),
                    driveFileId = o.optString("driveId").ifBlank { null },
                    thumbFileId = o.optString("thumbId").ifBlank { null },
                    videoLocalPath = o.optString("videoPath").ifBlank { null },
                    thumbLocalPath = o.optString("thumbPath").ifBlank { null },
                    favorite = o.optBoolean("fav", false),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun save(records: List<ClipRecord>) {
        val arr = JSONArray()
        records.forEach { r ->
            arr.put(
                JSONObject().apply {
                    put("name", r.name)
                    put("size", r.sizeBytes)
                    r.durationSec?.let { put("dur", it) }
                    r.yavgMax?.let { put("yavg", it) }
                    r.framesMov?.let { put("frames", it) }
                    put("onDrive", r.onDrive)
                    r.driveFileId?.let { put("driveId", it) }
                    r.thumbFileId?.let { put("thumbId", it) }
                    r.videoLocalPath?.let { put("videoPath", it) }
                    r.thumbLocalPath?.let { put("thumbPath", it) }
                    put("fav", r.favorite)
                },
            )
        }
        runCatching { file.writeText(arr.toString()) }
    }

    companion object {
        /**
         * Rebuilds the catalog as the union of every source, keyed by base name, most-recent-first:
         *  - the fresh Drive listing (marks [ClipRecord.onDrive], carries the mp4 + thumb Drive ids and
         *    size/metrics),
         *  - every row of the permanent `metrics.csv` (a row with no Drive/local presence → METADATA_ONLY,
         *    and backfills size/metrics onto Drive entries that lack them),
         *  - the local offline cache (video present → ARCHIVED) and the thumbnail archive,
         *  - the favorites store (by base name, so a purged favorite still reads as starred).
         */
        fun merge(
            driveClips: List<Clip>,
            metrics: Map<String, ClipMetric>,
            offline: OfflineStore,
            favorites: FavoritesStore,
            thumbs: ThumbArchive,
        ): List<ClipRecord> {
            val favNames = favorites.favoriteNames().toSet()   // base names, no extension
            val byName = LinkedHashMap<String, ClipRecord>()

            // Drive listing first — the authoritative "still in the cloud" set.
            driveClips.forEach { c ->
                val base = c.name.removeSuffix(".mp4")
                byName[base] = ClipRecord(
                    name = base,
                    sizeBytes = c.sizeBytes,
                    durationSec = c.durationSec,
                    yavgMax = c.yavgMax,
                    framesMov = c.framesMov,
                    onDrive = true,
                    driveFileId = c.id,
                    thumbFileId = c.thumbFileId,
                )
            }

            // metrics.csv: add clips Drive no longer has (→ metadata-only), backfill the rest.
            metrics.forEach { (base, m) ->
                val existing = byName[base]
                if (existing == null) {
                    byName[base] = ClipRecord(
                        name = base,
                        sizeBytes = (m.sizeKb ?: 0L) * 1024L,
                        durationSec = m.durSec,
                        yavgMax = m.yavgMax,
                        framesMov = m.framesMov,
                        onDrive = false,
                    )
                } else {
                    byName[base] = existing.copy(
                        sizeBytes = if (existing.sizeBytes > 0L) existing.sizeBytes else (m.sizeKb ?: 0L) * 1024L,
                        durationSec = existing.durationSec ?: m.durSec,
                        yavgMax = existing.yavgMax ?: m.yavgMax,
                        framesMov = existing.framesMov ?: m.framesMov,
                    )
                }
            }

            // Local presence (archived video / thumbnail) + favorite flag, then most-recent-first.
            return byName.values
                .map { r ->
                    r.copy(
                        videoLocalPath = offline.downloadedPathForName(r.name),
                        thumbLocalPath = thumbs.localPathOrNull(r.name),
                        favorite = r.name in favNames,
                    )
                }
                .sortedByDescending { it.name }
        }
    }
}
