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
import com.famviva.camara.R
import com.famviva.camara.auth.headlessDriveToken
import com.famviva.camara.data.AwayMode
import com.famviva.camara.data.AwayModeStore
import com.famviva.camara.data.BatteryHistoryStore
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.LocationProvider
import com.famviva.camara.data.OfflineStore
import com.famviva.camara.data.motionIntensityLevel
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
        val awayStore = AwayModeStore(applicationContext)
        val offline = OfflineStore(applicationContext)
        val ctx = applicationContext

        var problem = false                            // a poll that saw an issue arms a faster re-check (R3)

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
                        // Only alert on motion while Away — and not during quiet-hours (kills 3am
                        // shadow/bug spam). Still advance the baseline (so switching to Away later
                        // doesn't dump the backlog) and still auto-download regardless.
                        if (awayEffective(ctx, awayStore)) {
                            problem = true             // an intrusion while away: re-check sooner
                            if (!store.inQuietHours()) {
                                // Min-intensity gate: only clips at/above the chosen level fire an
                                // alert (they all still appear in the app). recentClips() carries no
                                // metrics, so fetch them only when a filter is active — and let a
                                // clip with unknown intensity through (fail-open).
                                val minLevel = store.minAlertLevel.minLevel
                                val alertable = if (minLevel <= AlertIntensity.ALL.minLevel) {
                                    newOnes
                                } else {
                                    val metrics = drive.recentMetrics()
                                    newOnes.filter { clip ->
                                        val lvl = motionIntensityLevel(metrics[clip.name.removeSuffix(".mp4")]?.yavgMax)
                                        lvl == null || lvl >= minLevel
                                    }
                                }
                                if (alertable.isNotEmpty()) {
                                    val newestClip = alertable.first()   // list stays newest-first
                                    val thumb = runCatching { drive.fetchClipThumbnail(newestClip) }.getOrNull()
                                    Notifications.notifyNewClips(
                                        ctx, alertable.size, newestClip.time, thumb,
                                        Notifications.clipRoute(newestClip.id),
                                    )
                                }
                            }
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
                    h.diskLow() -> ctx.getString(R.string.health_disk_low, h.camera, (h.diskFreeMb ?: 0) / 1024.0)
                    else -> null
                }
            }.sorted()
            if (issues.isNotEmpty()) problem = true
            val sig = issues.joinToString(" | ")
            val prev = store.lastHealthAlert()
            if (issues.isNotEmpty() && sig != prev) {
                Notifications.notifyHealthIssues(ctx, issues)
                store.setHealthAlert(sig)
            } else if (issues.isEmpty() && prev != null) {
                store.setHealthAlert(null)             // all good: reset so we alert again if it recurs
            }
        }

        // R3: if this poll saw a problem, poll again soon (one-shot) instead of waiting the full
        // 15-min tick; cancel the follow-up once things are healthy again (back off).
        if (problem) scheduleFollowup(applicationContext) else cancelFollowup(applicationContext)
        return Result.success()
    }

    /**
     * Are motion alerts enabled right now? MANUAL uses the user's toggle; AUTO compares the current
     * location against the saved home radius (Away when outside). When AUTO can't get a fix (no
     * permission / no cached location yet) it keeps the previous decision, and with no home saved it
     * assumes Away so alerts aren't silently lost.
     */
    private suspend fun awayEffective(context: Context, store: AwayModeStore): Boolean =
        when (store.mode) {
            AwayMode.MANUAL -> store.manualAway
            AwayMode.AUTO -> {
                if (!store.hasHome()) {
                    true
                } else {
                    val dist = LocationProvider.lastLocation(context)?.let { store.distanceFromHome(it) }
                    if (dist == null) store.lastAutoAway
                    else (dist > store.homeRadiusM).also { store.lastAutoAway = it }
                }
            }
        }

    companion object {
        private const val WORK_NAME = "new-clips-poll"
        private const val FOLLOWUP_NAME = "new-clips-followup"

        private fun connectedConstraints() =
            Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<NewClipsWorker>(15, TimeUnit.MINUTES)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }

        /** One-shot re-check a few minutes out, re-armed on each problematic poll (Doze may stretch
         *  it, but it still beats the 15-min periodic). Replaced each time so at most one is queued. */
        private fun scheduleFollowup(context: Context) {
            val request = OneTimeWorkRequestBuilder<NewClipsWorker>()
                .setInitialDelay(5, TimeUnit.MINUTES)
                .setConstraints(connectedConstraints())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(FOLLOWUP_NAME, ExistingWorkPolicy.REPLACE, request)
        }

        private fun cancelFollowup(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(FOLLOWUP_NAME)
        }
    }
}
