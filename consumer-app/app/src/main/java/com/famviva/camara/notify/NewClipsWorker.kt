package com.famviva.camara.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famviva.camara.R
import com.famviva.camara.auth.headlessDriveToken
import com.famviva.camara.data.BatteryHistoryStore
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.OfflineStore
import java.util.concurrent.TimeUnit

/**
 * Polls Drive in the background and notifies if there are new clips since the last one announced.
 * Gets a UI-less token via [headlessDriveToken] (reuses the grant the user already gave).
 */
class NewClipsWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = headlessDriveToken(applicationContext) ?: return Result.success()
        val drive = DriveClient(tokenProvider = { token }, onUnauthorized = {})
        val store = NotifyStore(applicationContext)
        val offline = OfflineStore(applicationContext)
        val ctx = applicationContext

        // 1) New clips (+ auto-download them, Wi-Fi only, if the user turned that on)
        runCatching { drive.recentClips(20) }.onSuccess { recent ->
            if (recent.isNotEmpty()) {
                val newest = recent.first().name       // names in descending order
                val last = store.lastNotified()
                if (last == null) {                    // first run: set the baseline, don't announce history
                    store.setLastNotified(newest)
                } else {
                    val newOnes = recent.filter { it.name > last }
                    if (newOnes.isNotEmpty()) {
                        // Only alert on motion while Away. Still advance the baseline (so switching to
                        // Away later doesn't dump the backlog) and still auto-download regardless.
                        if (store.away) {
                            val newestClip = recent.first()   // newest overall = newest of the new ones
                            val thumb = runCatching { drive.fetchClipThumbnail(newestClip) }.getOrNull()
                            Notifications.notifyNewClips(ctx, newOnes.size, newestClip.time, thumb)
                        }
                        store.setLastNotified(newest)
                        if (offline.shouldAutoDownloadNow()) {
                            newOnes.forEach { clip -> runCatching { offline.download(clip, token) } }
                        }
                    }
                }
            }
        }

        // 2) NVR health (status.json): recording down / not reporting / low battery
        runCatching { drive.fetchCameraHealth() }.onSuccess { health ->
            // Record battery readings even while the app is closed, so the history (and the ETA fit)
            // stays dense. Deduped by the heartbeat's `updated`, so it won't double-count the app's
            // own foreground sampling.
            val batteryHistory = BatteryHistoryStore(ctx)
            health.forEach { h ->
                val b = h.battery
                if (b != null && h.updated > 0) batteryHistory.record(h.camera, h.updated, b, h.charging == true, h.etaMinutes)
            }
            val now = System.currentTimeMillis() / 1000
            val issues = health.mapNotNull { h ->
                when {
                    !h.ok -> ctx.getString(R.string.health_no_signal, h.camera)
                    h.isStale(now) -> ctx.getString(R.string.health_not_reporting, h.camera)
                    h.lowBattery -> ctx.getString(R.string.health_low_battery, h.camera, h.battery ?: 0)
                    else -> null
                }
            }.sorted()
            val sig = issues.joinToString(" | ")
            val prev = store.lastHealthAlert()
            if (issues.isNotEmpty() && sig != prev) {
                Notifications.notifyHealthIssues(ctx, issues)
                store.setHealthAlert(sig)
            } else if (issues.isEmpty() && prev != null) {
                store.setHealthAlert(null)             // all good: reset so we alert again if it recurs
            }
        }
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "new-clips-poll"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewClipsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
