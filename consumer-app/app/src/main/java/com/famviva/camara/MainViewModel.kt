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
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.SeenStore
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
) : ViewModel() {

    var clips by mutableStateOf<List<Clip>>(emptyList())
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

    fun selectDateFilter(f: DateFilter) { dateFilter = f }

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
                cameraHealth = runCatching { drive.fetchCameraHealth() }.getOrDefault(emptyList())
                loadedOnce = true
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
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = MainViewModel(drive, seen) as T
    }
}
