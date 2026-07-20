package com.famviva.camara.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Tier/availability of a catalog entry — the 3-tier retention model made visible:
 *  - [CLOUD]         ☁️ on Drive, no local video yet → stream it.
 *  - [ARCHIVED]      ⬇️ full video present on this phone → play offline (may also still be on Drive).
 *  - [METADATA_ONLY] 📊 only metrics (+ maybe a thumbnail) survive; the video is gone everywhere → it
 *                    happened, but it can't be played.
 */
enum class ClipState { CLOUD, ARCHIVED, METADATA_ONLY }

/**
 * One row of the persistent local catalog: the UNION of the Drive listing, the local archive and
 * metadata-only entries (parsed from the NVR's permanent `metrics.csv`, for clips whose video is gone
 * everywhere). Keyed by the NVR base name (`mt_YYYYMMDD_HHMMSS`, no extension); date, time and
 * intensity derive from that name exactly as [Clip] does — no extra Drive metadata needed.
 *
 * Backed by JSON ([CatalogStore]), matching the app's other stores; there is deliberately no Room DB.
 */
data class ClipRecord(
    /** Base name, no extension — the primary key. */
    val name: String,
    val sizeBytes: Long = 0L,
    val durationSec: Double? = null,
    val yavgMax: Double? = null,
    val framesMov: Int? = null,
    /** Still present in the Drive listing (the off-site copy). */
    val onDrive: Boolean = false,
    /** The mp4's Drive id — streamable while [onDrive]. */
    val driveFileId: String? = null,
    /** The sibling `mt_*.jpg`'s Drive id — for the thumbnail archive and the Drive preview. */
    val thumbFileId: String? = null,
    /** Absolute path of the archived full video on this phone, if any (Phase B fills this widely; in
     *  Phase A it reflects whatever the offline auto-download already cached). */
    val videoLocalPath: String? = null,
    /** Absolute path of the archived thumbnail jpg on this phone, if any. */
    val thumbLocalPath: String? = null,
    val favorite: Boolean = false,
) {
    private val stamp: String? =
        Regex("""mt_(\d{8})_(\d{6})""").find(name)?.let { "${it.groupValues[1]}_${it.groupValues[2]}" }

    /** "YYYYMMDD" to group by day; null if the name doesn't match. */
    val dateKey: String? get() = stamp?.substring(0, 8)

    val localDate: LocalDate?
        get() = dateKey?.let { runCatching { LocalDate.parse(it, DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull() }

    val localDateTime: LocalDateTime?
        get() = stamp?.let {
            runCatching { LocalDateTime.parse(it, DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) }.getOrNull()
        }

    /** "HH:MM:SS" event time. */
    val time: String
        get() = stamp?.let { "${it.substring(9, 11)}:${it.substring(11, 13)}:${it.substring(13, 15)}" } ?: name

    val hour: Int? get() = stamp?.substring(9, 11)?.toIntOrNull()

    val period: DayPeriod? get() = hour?.let { DayPeriod.of(it) }

    /** Motion intensity 1..5 from yavg_max (null if there's no metric), same thresholds as [Clip]. */
    val intensityLevel: Int? get() = motionIntensityLevel(yavgMax)

    /** Availability tier — drives the ☁️/⬇️/📊 badge and whether the entry can be played. */
    val state: ClipState
        get() = when {
            videoLocalPath != null -> ClipState.ARCHIVED
            onDrive -> ClipState.CLOUD
            else -> ClipState.METADATA_ONLY
        }

    /** The video exists somewhere we can reach (a local file or the Drive stream). */
    val playable: Boolean get() = state != ClipState.METADATA_ONLY

    /** Drive URL of the sibling jpg (needs the Authorization header); for Coil while still [onDrive]. */
    val driveThumbUrl: String? get() = thumbFileId?.let { "https://www.googleapis.com/drive/v3/files/$it?alt=media" }

    /** Readable duration: "15 s" if <1 min; "m:ss" otherwise. Null if no duration is known. */
    val durationLabel: String?
        get() {
            val s = durationSec?.toInt() ?: return null
            return if (s < 60) "$s s" else "%d:%02d".format(s / 60, s % 60)
        }

    val sizeMb: String get() = "%.1f MB".format(sizeBytes / 1_048_576.0)
}
