package com.famviva.camara

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.famviva.camara.data.AutoDownloadMode
import com.famviva.camara.data.BatteryHistoryStore
import com.famviva.camara.data.BatterySample
import com.famviva.camara.data.CameraHealth
import com.famviva.camara.data.Clip
import com.famviva.camara.data.ClipListCache
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.FavoritesStore
import com.famviva.camara.data.OfflineStore
import com.famviva.camara.data.SeenStore
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.launch

/** Quick date-range filter on the Days screen. */
enum class DateFilter(@StringRes val labelRes: Int) {
    ALL(R.string.filter_all),
    TODAY(R.string.filter_today),
    YESTERDAY(R.string.filter_yesterday),
    WEEK(R.string.filter_week),
}

class MainViewModel(
    private val drive: DriveClient,
    private val seen: SeenStore,
    private val offline: OfflineStore,
    private val cache: ClipListCache,
    private val battery: BatteryHistoryStore,
    private val favorites: FavoritesStore,
    private val tokenProvider: suspend () -> String,
) : ViewModel() {

    // Seeded from the cache so Days/Clips show something immediately on a cold start, before (or
    // even without) a successful Drive round-trip.
    var clips by mutableStateOf<List<Clip>>(cache.load())
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var loadedOnce by mutableStateOf(false)
        private set
    var dateFilter by mutableStateOf(DateFilter.ALL)
        private set
    var seenIds by mutableStateOf(seen.all())
        private set
    var cameraHealth by mutableStateOf<List<CameraHealth>>(emptyList())
        private set
    var autoDownloadMode by mutableStateOf(offline.autoDownloadMode)
        private set
    var favoriteIds by mutableStateOf(favorites.all())
        private set

    // Bumped on every download/delete so Compose recomposes reads of offline state below — the
    // filesystem itself isn't observable, so this is the invalidation signal.
    private var offlineVersion by mutableStateOf(0)

    // Same invalidation trick for the battery history file (bumped after each recorded sample).
    private var batteryVersion by mutableStateOf(0)

    /** That camera's recorded battery time series, oldest first (for the battery graph). */
    fun batterySamples(camera: String): List<BatterySample> { batteryVersion; return battery.samples(camera) }

    fun selectDateFilter(f: DateFilter) { dateFilter = f }

    fun isDownloaded(clip: Clip): Boolean { offlineVersion; return offline.isDownloaded(clip) }

    /** Local file to play from if it's already downloaded, or null to fall back to streaming. */
    fun localFileOrNull(clip: Clip): File? { offlineVersion; return offline.localFile(clip).takeIf { it.exists() && it.length() > 0L } }

    fun offlineTotalBytes(): Long { offlineVersion; return offline.totalSizeBytes() }

    /** Total bytes stored on Drive across every clip (the full cloud footprint). */
    fun driveTotalBytes(): Long = clips.sumOf { it.sizeBytes }

    /** Average "recording ended -> visible on Drive" latency across clips that have both timestamps —
     *  the "how long until footage is safe in the cloud" margin. Null if no clip has the data yet.
     *  (The per-day view computes its own average straight from that day's clips.) */
    fun avgUploadDelaySeconds(): Long? {
        val delays = clips.mapNotNull { it.uploadDelaySeconds }
        return if (delays.isEmpty()) null else delays.sum() / delays.size
    }

    fun isFavorite(clip: Clip): Boolean = clip.id in favoriteIds

    /** Every starred clip, most recent first — for the Favorites screen. */
    fun favoriteClips(): List<Clip> =
        clips.filter { it.id in favoriteIds }.sortedByDescending { it.name }

    /** Toggles a clip's favorite star. Favorites are protected from batch deletes. */
    fun toggleFavorite(clip: Clip) {
        favorites.toggle(clip.id)
        favoriteIds = favorites.all()
    }

    /** Drive footprint per day (every clip's size), most recent day first, empty days omitted.
     *  Unlike [offlineSizeByDay] this counts the cloud originals, not the local cache. */
    fun driveSizeByDay(): List<Pair<String, Long>> =
        allClipsGroupedByDay()
            .map { (day, dayClips) -> day to dayClips.sumOf { it.sizeBytes } }
            .filter { it.second > 0 }

    /** Offline (downloaded) bytes per day, most recent first, days with nothing cached omitted.
     *  Deliberately independent of [dateFilter] (a Days-screen-only concern): the Storage screen
     *  must always account for every offline byte it might delete, not just whatever the Days
     *  screen's filter happens to be showing at the time. */
    fun offlineSizeByDay(): List<Pair<String, Long>> {
        offlineVersion
        return allClipsGroupedByDay()
            .map { (day, dayClips) -> day to dayClips.filter { offline.isDownloaded(it) }.sumOf { it.sizeBytes } }
            .filter { it.second > 0 }
    }

    /** Removes the offline (device-only) copies for that day; the Drive originals are untouched.
     *  Favorites (starred) are kept — batch deletes never touch them. */
    fun deleteOfflineDay(dateKey: String) {
        clipsOf(dateKey).forEach { if (offline.isDownloaded(it) && !isFavorite(it)) offline.delete(it) }
        offlineVersion++
    }

    /** Removes a single clip's offline copy; the Drive original is untouched. An explicit per-clip
     *  action, so it deletes even a favorite (only *batch* deletes protect favorites). */
    fun deleteOfflineCopy(clip: Clip) {
        offline.delete(clip)
        offlineVersion++
    }

    /** Frees all offline copies (device only) except favorites; the Drive originals are untouched. */
    fun deleteAllOffline() {
        clips.forEach { if (offline.isDownloaded(it) && !isFavorite(it)) offline.delete(it) }
        offlineVersion++
    }

    fun setAutoDownloadMode(mode: AutoDownloadMode) {
        offline.autoDownloadMode = mode
        autoDownloadMode = mode
        if (mode != AutoDownloadMode.OFF) downloadTodaysClips()
    }

    /** Auto-download is forward-looking, not a backfill of the whole history: bounded to today's
     *  clips (grows through the day as new ones land) and to the current network policy, so turning
     *  it on can't kick off downloading weeks of footage (or burn mobile data unless the user opted
     *  into the Wi-Fi + data mode). */
    private fun downloadTodaysClips() {
        if (!offline.shouldAutoDownloadNow()) return
        val today = LocalDate.now()
        val pending = clips.filter { it.localDate == today && !offline.isDownloaded(it) }
        if (pending.isEmpty()) return
        viewModelScope.launch {
            val token = runCatching { tokenProvider() }.getOrNull() ?: return@launch
            pending.forEach { clip ->
                if (runCatching { offline.download(clip, token) }.getOrDefault(false)) offlineVersion++
            }
        }
    }

    fun isNew(clip: Clip): Boolean = clip.id !in seenIds

    fun markSeen(id: String) {
        if (id in seenIds) return
        seen.markSeen(id)
        seenIds = seen.all()
    }

    /** How many clips from that day are still unseen. */
    fun newCountOf(dateKey: String): Int = clipsOf(dateKey).count { isNew(it) }

    fun load() {
        if (loading) return
        viewModelScope.launch {
            loading = true
            error = null
            try {
                clips = drive.listClips()
                cache.save(clips)
                cameraHealth = runCatching { drive.fetchCameraHealth() }.getOrDefault(emptyList())
                recordBatterySamples()
                loadedOnce = true
                if (autoDownloadMode != AutoDownloadMode.OFF) downloadTodaysClips()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
    }

    /** Persist a battery point for each reporting camera (deduped by heartbeat timestamp). */
    private fun recordBatterySamples() {
        cameraHealth.forEach { h ->
            val b = h.battery
            if (b != null && h.updated > 0) battery.record(h.camera, h.updated, b, h.charging == true, h.etaMinutes)
        }
        batteryVersion++
    }

    private fun passesDateFilter(clip: Clip): Boolean {
        val d = clip.localDate ?: return dateFilter == DateFilter.ALL
        val today = LocalDate.now()
        return when (dateFilter) {
            DateFilter.ALL -> true
            DateFilter.TODAY -> d == today
            DateFilter.YESTERDAY -> d == today.minusDays(1)
            DateFilter.WEEK -> !d.isBefore(today.minusDays(6))
        }
    }

    /** Every clip that has a day, grouped and sorted most-recent-first — unfiltered by [dateFilter]. */
    private fun allClipsGroupedByDay(): List<Pair<String, List<Clip>>> =
        clips.filter { it.dateKey != null }
            .groupBy { it.dateKey!! }
            .toSortedMap(compareByDescending { it })
            .map { it.key to it.value }

    /** Days (YYYYMMDD) -> that day's clips, with [dateFilter] applied, most recent first. */
    fun clipsByDay(): List<Pair<String, List<Clip>>> =
        allClipsGroupedByDay()
            .map { (day, dayClips) -> day to dayClips.filter { passesDateFilter(it) } }
            .filter { it.second.isNotEmpty() }

    fun clipsOf(dateKey: String): List<Clip> =
        clips.filter { it.dateKey == dateKey }

    fun find(id: String): Clip? = clips.firstOrNull { it.id == id }

    class Factory(
        private val drive: DriveClient,
        private val seen: SeenStore,
        private val offline: OfflineStore,
        private val cache: ClipListCache,
        private val battery: BatteryHistoryStore,
        private val favorites: FavoritesStore,
        private val tokenProvider: suspend () -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(drive, seen, offline, cache, battery, favorites, tokenProvider) as T
    }
}
