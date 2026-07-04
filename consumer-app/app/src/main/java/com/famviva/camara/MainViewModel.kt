package com.famviva.camara

import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.famviva.camara.data.CameraHealth
import com.famviva.camara.data.Clip
import com.famviva.camara.data.ClipListCache
import com.famviva.camara.data.DriveClient
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
    var autoDownloadEnabled by mutableStateOf(offline.autoDownloadEnabled)
        private set

    // Bumped on every download/delete so Compose recomposes reads of offline state below — the
    // filesystem itself isn't observable, so this is the invalidation signal.
    private var offlineVersion by mutableStateOf(0)

    fun selectDateFilter(f: DateFilter) { dateFilter = f }

    fun isDownloaded(clip: Clip): Boolean { offlineVersion; return offline.isDownloaded(clip) }

    /** Local file to play from if it's already downloaded, or null to fall back to streaming. */
    fun localFileOrNull(clip: Clip): File? = offline.localFile(clip).takeIf { it.exists() && it.length() > 0L }

    fun offlineTotalBytes(): Long { offlineVersion; return offline.totalSizeBytes() }

    /** Offline (downloaded) bytes per day, most recent first, days with nothing cached omitted. */
    fun offlineSizeByDay(): List<Pair<String, Long>> {
        offlineVersion
        return clipsByDay()
            .map { (day, dayClips) -> day to dayClips.filter { offline.isDownloaded(it) }.sumOf { it.sizeBytes } }
            .filter { it.second > 0 }
    }

    /** Removes the offline (device-only) copies for that day; the Drive originals are untouched. */
    fun deleteOfflineDay(dateKey: String) {
        clipsOf(dateKey).forEach { if (offline.isDownloaded(it)) offline.delete(it) }
        offlineVersion++
    }

    /** Removes a single clip's offline copy; the Drive original is untouched. */
    fun deleteOfflineCopy(clip: Clip) {
        offline.delete(clip)
        offlineVersion++
    }

    /** Frees all offline copies (device only); the Drive originals are untouched. */
    fun deleteAllOffline() {
        offline.deleteAll()
        offlineVersion++
    }

    fun setAutoDownload(enabled: Boolean) {
        offline.autoDownloadEnabled = enabled
        autoDownloadEnabled = enabled
        if (enabled) downloadTodaysClips()
    }

    /** Auto-download is forward-looking, not a backfill of the whole history: bounded to today's
     *  clips (grows through the day as new ones land) and to Wi-Fi, so turning it on can't kick
     *  off downloading weeks of footage or burn mobile data. */
    private fun downloadTodaysClips() {
        if (!offline.isOnUnmeteredNetwork()) return
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
                loadedOnce = true
                if (autoDownloadEnabled) downloadTodaysClips()
            } catch (e: Exception) {
                error = e.message ?: e.javaClass.simpleName
            } finally {
                loading = false
            }
        }
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

    /** Days (YYYYMMDD) -> that day's clips, with the filter applied, most recent first. */
    fun clipsByDay(): List<Pair<String, List<Clip>>> =
        clips.filter { it.dateKey != null && passesDateFilter(it) }
            .groupBy { it.dateKey!! }
            .toSortedMap(compareByDescending { it })
            .map { it.key to it.value }

    fun clipsOf(dateKey: String): List<Clip> =
        clips.filter { it.dateKey == dateKey }

    fun find(id: String): Clip? = clips.firstOrNull { it.id == id }

    class Factory(
        private val drive: DriveClient,
        private val seen: SeenStore,
        private val offline: OfflineStore,
        private val cache: ClipListCache,
        private val tokenProvider: suspend () -> String,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            MainViewModel(drive, seen, offline, cache, tokenProvider) as T
    }
}
