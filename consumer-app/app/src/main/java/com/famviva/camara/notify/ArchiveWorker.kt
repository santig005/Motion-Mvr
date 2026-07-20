package com.famviva.camara.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.famviva.camara.auth.headlessDriveToken
import com.famviva.camara.data.ArchiveStore
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.FavoritesStore
import com.famviva.camara.data.OfflineStore
import java.util.concurrent.TimeUnit

/**
 * Keeps the local video archive in sync with the chosen retention horizon ([ArchiveStore]):
 *  1. **Prune** local videos older than the horizon (except favorites) — frees space first.
 *  2. **Backfill** every clip still on Drive within the horizon that isn't already downloaded,
 *     capped per run so a first pass over the whole Drive window trickles in over several runs
 *     instead of one huge burst.
 *
 * Wi-Fi (unmetered) + battery-not-low only, so a bulk archive never burns mobile data or drains the
 * phone. Reuses the per-clip download lock in [OfflineStore], so it never collides with the
 * new-clip poll's own today-download. No-op when archiving is off (`horizonDays == 0`).
 */
class ArchiveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val store = ArchiveStore(applicationContext)
        val horizon = store.horizonDays
        if (horizon <= 0) return Result.success()            // archiving off — nothing to do

        val token = headlessDriveToken(applicationContext) ?: return Result.retry()
        val offline = OfflineStore(applicationContext)
        val favorites = FavoritesStore(applicationContext)
        val cutoff = store.cutoffDateKey()

        // 1) Prune past-horizon local videos first (favorites are spared).
        offline.purgeVideosOlderThan(cutoff, favorites.favoriteNames().toSet())

        // 2) Backfill within the horizon (bounded by what Drive still has, i.e. its 30-day window).
        val drive = DriveClient(tokenProvider = { token }, onUnauthorized = {})
        val clips = runCatching { drive.listClips() }.getOrDefault(emptyList())
        val within = clips.filter { (it.dateKey ?: "") >= cutoff }
        val total = within.size                              // clips in the horizon that Drive still has
        var done = within.count { offline.isDownloaded(it) } // how many are already archived locally
        // Progress is reported as "done / total within the horizon" — a stable overall figure that
        // climbs across runs (each run downloads at most MAX_PER_RUN), so the app's progress bar
        // reflects the whole archive filling, not just this run.
        setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
        val pending = within.filter { !offline.isDownloaded(it) }
            .sortedByDescending { it.name }                  // newest first — most likely wanted soonest
            .take(MAX_PER_RUN)
        for (clip in pending) {
            setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total, KEY_LABEL to clip.time))
            if (runCatching { offline.download(clip, token) }.getOrDefault(false)) {
                done++
                setProgress(workDataOf(KEY_DONE to done, KEY_TOTAL to total))
            }
        }
        // More still pending beyond this run's cap? A follow-up continues; the periodic pass also catches up.
        if (within.count { !offline.isDownloaded(it) } > 0) scheduleFollowup(applicationContext)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "archive-poll"
        private const val FOLLOWUP_NAME = "archive-followup"
        private const val MAX_PER_RUN = 150

        /** Tag on every archive request, so the UI can observe progress across both the periodic pass
         *  and the one-shot ("Archive now") via one [WorkManager.getWorkInfosByTagFlow]. */
        const val TAG = "archive"

        /** setProgress keys the app's archive progress bar reads. */
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        const val KEY_LABEL = "label"

        private fun constraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.UNMETERED)   // Wi-Fi only — never mobile data
            .setRequiresBatteryNotLow(true)
            .build()

        /** Periodic catch-up (every 6 h) — enqueued while a horizon is set. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ArchiveWorker>(6, TimeUnit.HOURS)
                .setConstraints(constraints())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }

        /** Immediate one-shot, e.g. right after the user picks/raises a horizon or taps "Archive now". */
        fun runNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ArchiveWorker>()
                .setConstraints(constraints())
                .addTag(TAG)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(FOLLOWUP_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun scheduleFollowup(context: Context) = runNow(context)

        /** Stops the periodic + any pending catch-up when the user turns archiving off. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            WorkManager.getInstance(context).cancelUniqueWork(FOLLOWUP_NAME)
        }
    }
}
