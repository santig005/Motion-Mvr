package com.famviva.camara.data

import com.famviva.camara.auth.UnauthorizedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import java.time.Instant
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Access to the Google Drive REST API v3: read (list/stream clips, read status/metrics) plus a
 * single small write — the `favorites.json` marker ([uploadFavorites]) the NVR reads to keep
 * starred clips past its retention purge.
 * [tokenProvider] yields an OAuth access token (drive.readonly + drive.file); [onUnauthorized] is
 * called on a 401 to invalidate the cached token.
 */
class DriveClient(
    private val tokenProvider: suspend () -> String,
    private val onUnauthorized: () -> Unit,
) {
    private val http = OkHttpClient()

    /**
     * Lists the motion clips (mp4 "mt_") and pairs each with its own thumbnail (mt_*.jpg the NVR
     * uploads next to the video), matching by base name.
     */
    suspend fun listClips(): List<Clip> = withContext(Dispatchers.IO) {
        val clips = mutableListOf<Clip>()           // the mp4 files
        val thumbs = HashMap<String, String>()      // base name (no extension) -> jpg id
        var pageToken: String? = null
        do {
            val token = tokenProvider()
            val urlBuilder = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
                .addQueryParameter(
                    "q",
                    "name contains 'mt_' and (mimeType = 'video/mp4' or mimeType = 'image/jpeg') and trashed = false",
                )
                .addQueryParameter("orderBy", "name desc")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter(
                    "fields",
                    "nextPageToken, files(id, name, size, mimeType, thumbnailLink, createdTime, modifiedTime, videoMediaMetadata(durationMillis))",
                )
                .addQueryParameter("spaces", "drive")
            pageToken?.let { urlBuilder.addQueryParameter("pageToken", it) }

            val req = Request.Builder()
                .url(urlBuilder.build())
                .header("Authorization", "Bearer $token")
                .get()
                .build()

            http.newCall(req).execute().use { resp ->
                if (resp.code == 401) { onUnauthorized(); throw UnauthorizedException("Token rejected by Drive") }
                if (!resp.isSuccessful) error("Drive API ${resp.code}: ${resp.body?.string()?.take(200)}")
                val json = JSONObject(resp.body?.string() ?: "{}")
                val files = json.optJSONArray("files")
                if (files != null) {
                    for (i in 0 until files.length()) {
                        val f = files.getJSONObject(i)
                        val name = f.getString("name")
                        if (name.endsWith(".jpg", ignoreCase = true)) {
                            thumbs[name.removeSuffix(".jpg").removeSuffix(".JPG")] = f.getString("id")
                        } else if (f.optString("mimeType") == "video/mp4") {
                            val durMs = f.optJSONObject("videoMediaMetadata")
                                ?.optString("durationMillis")?.toLongOrNull()
                            // modifiedTime == the local file mtime rclone preserved (= recording
                            // finished); createdTime == server-assigned upload time. The gap is the
                            // real "recording done -> visible on Drive" latency. See project memory.
                            val modified = f.optString("modifiedTime").ifBlank { null }
                                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            val created = f.optString("createdTime").ifBlank { null }
                                ?.let { runCatching { Instant.parse(it) }.getOrNull() }
                            clips += Clip(
                                id = f.getString("id"),
                                name = name,
                                sizeBytes = f.optString("size", "0").toLongOrNull() ?: 0L,
                                durationMillis = durMs,
                                thumbnailLink = f.optString("thumbnailLink").ifBlank { null },
                                driveModifiedTime = modified,
                                driveCreatedTime = created,
                            )
                        }
                    }
                }
                pageToken = json.optString("nextPageToken").ifBlank { null }
            }
        } while (pageToken != null)

        val metrics = runCatching { fetchMetrics(tokenProvider()) }.getOrDefault(emptyMap())

        clips
            .map { c ->
                val base = c.name.removeSuffix(".mp4")
                val m = metrics[base]
                c.copy(
                    thumbFileId = thumbs[base],
                    yavgMax = m?.yavgMax,
                    framesMov = m?.framesMov,
                    durationSec = m?.durSec,
                )
            }
            .sortedByDescending { it.name }   // most recent first
    }

    /** Lightweight query: the most recent mp4 clips (id + name + size only, no thumbnails/metrics).
     *  For the background poll: new-clip notifications and auto-download both just need enough to
     *  compare names and, for downloads, build the streaming URL from the id. */
    suspend fun recentClips(limit: Int = 20): List<Clip> = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val url = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name contains 'mt_' and mimeType = 'video/mp4' and trashed = false")
            .addQueryParameter("orderBy", "name desc")
            .addQueryParameter("pageSize", limit.toString())
            .addQueryParameter("fields", "files(id, name, size)")
            .build()
        val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 401) { onUnauthorized(); throw UnauthorizedException("Token rejected by Drive") }
            if (!resp.isSuccessful) error("Drive API ${resp.code}")
            val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                ?: return@withContext emptyList()
            (0 until files.length()).map { i ->
                val f = files.getJSONObject(i)
                Clip(id = f.getString("id"), name = f.getString("name"), sizeBytes = f.optString("size", "0").toLongOrNull() ?: 0L)
            }
        }
    }

    /**
     * Publishes the favorited clip basenames to a small `favorites.json` at the Drive root, so the
     * NVR's cloud-sync can exclude them from its 30-day purge (keeping the Drive original of a
     * starred clip). Creates the file on first use (drive.file scope: the app only ever sees the
     * files it created), then rewrites it in place. Best-effort — returns false on any failure.
     */
    suspend fun uploadFavorites(names: List<String>): Boolean = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val body = JSONObject().put("favorites", JSONArray(names)).toString()
        val jsonType = "application/json".toMediaType()

        // Locate our own favorites.json (with drive.file the listing only returns app-created files).
        val findUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = 'favorites.json' and trashed = false")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("fields", "files(id)")
            .build()
        var fileId: String? = null
        http.newCall(Request.Builder().url(findUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (resp.code == 401) { onUnauthorized(); throw UnauthorizedException("Token rejected by Drive") }
                if (resp.isSuccessful) {
                    JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                        ?.takeIf { it.length() > 0 }
                        ?.let { fileId = it.getJSONObject(0).getString("id") }
                }
            }

        // Create the metadata-only file first if it's missing, then upload the content via media PATCH.
        if (fileId == null) {
            val meta = JSONObject().put("name", "favorites.json").put("mimeType", "application/json")
            http.newCall(
                Request.Builder()
                    .url("https://www.googleapis.com/drive/v3/files")
                    .header("Authorization", "Bearer $token")
                    .post(meta.toString().toRequestBody(jsonType))
                    .build(),
            ).execute().use { resp ->
                if (!resp.isSuccessful) return@withContext false
                fileId = JSONObject(resp.body?.string() ?: "{}").optString("id").ifBlank { null }
            }
        }
        val id = fileId ?: return@withContext false
        http.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files/$id?uploadType=media")
                .header("Authorization", "Bearer $token")
                .patch(body.toRequestBody(jsonType))
                .build(),
        ).execute().use { resp -> resp.isSuccessful }
    }

    /** Downloads the NVR's own preview jpg (the `mt_*.jpg` sibling written next to the clip) and
     *  decodes it to a bitmap, for a rich (BigPicture) new-clip notification. Two light calls
     *  (locate + fetch); returns null if there's no sibling or anything fails. */
    suspend fun fetchClipThumbnail(clip: Clip): android.graphics.Bitmap? = withContext(Dispatchers.IO) {
        val base = clip.name.removeSuffix(".mp4")
        val token = tokenProvider()
        val listUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = '$base.jpg' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "1")
            .build()
        val id = http.newCall(Request.Builder().url(listUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                    ?.optJSONObject(0)?.optString("id")?.ifBlank { null }
            } ?: return@withContext null
        val mediaUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
        http.newCall(Request.Builder().url(mediaUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val bytes = resp.body?.bytes() ?: return@withContext null
                android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            }
    }

    /** Reads the status.json files across the tree and returns each camera's health. */
    suspend fun fetchCameraHealth(): List<CameraHealth> = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val listUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = 'status.json' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "100")
            .build()
        val ids = mutableListOf<String>()
        http.newCall(Request.Builder().url(listUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                    ?: return@withContext emptyList()
                for (i in 0 until files.length()) ids += files.getJSONObject(i).getString("id")
            }
        val out = mutableListOf<CameraHealth>()
        for (id in ids) {
            val mediaUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            http.newCall(Request.Builder().url(mediaUrl).header("Authorization", "Bearer $token").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    runCatching {
                        val j = JSONObject(resp.body?.string() ?: "{}")
                        out += CameraHealth(
                            camera = j.optString("camera", "cam"),
                            ok = j.optBoolean("recording_ok", j.optBoolean("ok", true)),
                            updated = j.optLong("updated", 0L),
                            battery = if (j.has("battery")) j.optInt("battery") else null,
                            charging = if (j.has("charging")) j.optBoolean("charging") else null,
                            dischargePctPerHour = if (j.has("discharge_pct_per_h")) j.optDouble("discharge_pct_per_h") else null,
                            etaMinutes = if (j.has("eta_minutes")) j.optInt("eta_minutes") else null,
                            recMode = if (j.has("rec_mode")) j.optString("rec_mode") else null,
                            rec2kDropsLastHour = if (j.has("rec_2k_drops_1h")) j.optInt("rec_2k_drops_1h") else null,
                            diskFreeMb = if (j.has("disk_free_mb")) j.optInt("disk_free_mb") else null,
                        )
                    }
                }
        }
        out
    }

    /** Reads the NVR's events.jsonl outage log(s) and returns every parseable line, merged across
     *  files. Absent file / 404 / any failure yields an empty list (the screen shows a "no data yet"
     *  state). Parsed defensively line-by-line so one bad line never drops the rest. */
    suspend fun fetchOutageEvents(): List<OutageEvent> = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val listUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = 'events.jsonl' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "100")
            .build()
        val ids = mutableListOf<String>()
        http.newCall(Request.Builder().url(listUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@withContext emptyList()
                val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                    ?: return@withContext emptyList()
                for (i in 0 until files.length()) ids += files.getJSONObject(i).getString("id")
            }
        val events = mutableListOf<OutageEvent>()
        for (id in ids) {
            val mediaUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            http.newCall(Request.Builder().url(mediaUrl).header("Authorization", "Bearer $token").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    resp.body?.string()?.lineSequence()?.forEach { line ->
                        parseOutageLine(line)?.let { events += it }
                    }
                }
        }
        events
    }

    /** Reads the NVR's sync_status.json (the cloud-sync pipeline heartbeat). If several exist the
     *  freshest (highest `updated`) wins. Null on absence / 404 / parse failure. */
    suspend fun fetchSyncStatus(): SyncStatus? = withContext(Dispatchers.IO) {
        val token = tokenProvider()
        val listUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = 'sync_status.json' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "10")
            .build()
        val ids = mutableListOf<String>()
        http.newCall(Request.Builder().url(listUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return@withContext null
                val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files")
                    ?: return@withContext null
                for (i in 0 until files.length()) ids += files.getJSONObject(i).getString("id")
            }
        var best: SyncStatus? = null
        for (id in ids) {
            val mediaUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            http.newCall(Request.Builder().url(mediaUrl).header("Authorization", "Bearer $token").get().build())
                .execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    parseSyncStatus(resp.body?.string().orEmpty())?.let { s ->
                        if (best == null || s.updated > best!!.updated) best = s
                    }
                }
        }
        best
    }

    /** Public wrapper for the background poll: clip base-name -> metrics, so the alert-gating can
     *  classify a new clip's motion intensity ([recentClips] omits metrics to stay lightweight). */
    suspend fun recentMetrics(): Map<String, ClipMetric> = withContext(Dispatchers.IO) {
        runCatching { fetchMetrics(tokenProvider()) }.getOrDefault(emptyMap())
    }

    /** Downloads the metrics.csv files across the tree and returns: clip_name -> metrics (yavg_max, frames, dur_s). */
    private fun fetchMetrics(token: String): Map<String, ClipMetric> {
        val out = HashMap<String, ClipMetric>()
        // Locate the metrics.csv files
        val listUrl = "https://www.googleapis.com/drive/v3/files".toHttpUrl().newBuilder()
            .addQueryParameter("q", "name = 'metrics.csv' and trashed = false")
            .addQueryParameter("fields", "files(id)")
            .addQueryParameter("pageSize", "100")
            .build()
        val ids = mutableListOf<String>()
        http.newCall(Request.Builder().url(listUrl).header("Authorization", "Bearer $token").get().build())
            .execute().use { resp ->
                if (!resp.isSuccessful) return emptyMap()
                val files = JSONObject(resp.body?.string() ?: "{}").optJSONArray("files") ?: return emptyMap()
                for (i in 0 until files.length()) ids += files.getJSONObject(i).getString("id")
            }
        for (id in ids) {
            val mediaUrl = "https://www.googleapis.com/drive/v3/files/$id?alt=media"
            http.newCall(Request.Builder().url(mediaUrl).header("Authorization", "Bearer $token").get().build())
                .execute().use { resp ->
                    if (resp.isSuccessful) parseMetricsCsv(resp.body?.string().orEmpty(), out)
                }
        }
        return out
    }

    private fun parseMetricsCsv(csv: String, into: MutableMap<String, ClipMetric>) {
        // Header: clip,datetime,dur_s,size_kb,yavg_max,yavg_mean,motion_frames
        csv.lineSequence().drop(1).forEach { line ->
            if (line.isBlank()) return@forEach
            val c = line.split(",")
            if (c.size >= 7) {
                val name = c[0].trim()
                val durSec = c[2].trim().toDoubleOrNull()
                val yavg = c[4].trim().toDoubleOrNull()
                val frames = c[6].trim().toIntOrNull()
                if (name.isNotEmpty() && yavg != null) {
                    into[name] = ClipMetric(yavgMax = yavg, framesMov = frames ?: 0, durSec = durSec)
                }
            }
        }
    }
}
