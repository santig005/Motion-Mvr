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
import com.famviva.camara.data.CameraHealth
import com.famviva.camara.data.Clip
import com.famviva.camara.data.DriveClient
import com.famviva.camara.data.LocationProvider
import com.famviva.camara.data.OfflineStore
import com.famviva.camara.data.WidgetSummaryStore
import com.famviva.camara.data.dateKeyOf
import com.famviva.camara.data.motionIntensityLevel
import com.famviva.camara.ui.widget.CameraWidget
import androidx.glance.appwidget.updateAll
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

        // Captured for the home-screen widget summary written at the end of this poll. Null means the
        // corresponding fetch failed this run, so we keep the previous value instead of clobbering it.
        var widgetRecent: List<Clip>? = null
        var widgetHealth: List<CameraHealth>? = null

        // 1) New clips (+ auto-download them, Wi-Fi only, if the user turned that on)
        runCatching { drive.recentClips(20) }.onSuccess { recent ->
            widgetRecent = recent
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

        // 2) NVR health (status.json): recording down / not reporting / low battery — plus the sync
        //    pipeline (sync_status.json). Fetched up front so it can join the same alert/dedup path.
        val syncStatus = runCatching { drive.fetchSyncStatus() }.getOrNull()
        runCatching { drive.fetchCameraHealth() }.onSuccess { health ->
            widgetHealth = health
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
            }.toMutableList()
            // Sync pipeline: a dead uploader also stops status.json reaching Drive (caught above as
            // "not reporting"), but an alive-but-not-uploading uploader is invisible there — recording
            // looks fine while clips never leave the phone. Alert on a stale heartbeat OR a fast lane
            // that hasn't succeeded in a while. Only when the uploader's own heartbeat is fresh do we
            // trust "not uploading", so a total outage doesn't double-fire with "not reporting".
            syncStatus?.let { s ->
                when {
                    s.isStale(now) -> ctx.getString(R.string.health_sync_down)
                    now - s.lastFastOk > SYNC_LANE_STALE_SEC -> ctx.getString(R.string.health_sync_failing)
                    else -> null
                }?.let { issues += it }
            }
            issues.sort()
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

        // 3) Persist the home-screen widget summary and refresh any placed widgets. The widget does
        //    no network work of its own — it only renders this snapshot — and stamps it with `now` so
        //    it can show its own age honestly ("hace X min") rather than passing stale data off as live.
        updateWidget(ctx, widgetRecent, widgetHealth)

        // R3: if this poll saw a problem, poll again soon (one-shot) instead of waiting the full
        // 15-min tick; cancel the follow-up once things are healthy again (back off).
        if (problem) scheduleFollowup(applicationContext) else cancelFollowup(applicationContext)
        return Result.success()
    }

    /** Merge this poll's fetches into the persisted widget summary (keeping the previous value for any
     *  fetch that failed this run), then push it to the widget. */
    private suspend fun updateWidget(ctx: Context, recent: List<Clip>?, health: List<CameraHealth>?) {
        val store = WidgetSummaryStore(ctx)
        val prev = store.read()
        val now = System.currentTimeMillis() / 1000
        val today = dateKeyOf(now)

        val cam = health?.firstOrNull()
        store.write(
            recordingOk = cam?.ok ?: prev.recordingOk,
            heartbeatUpdated = cam?.updated ?: prev.heartbeatUpdated,
            battery = cam?.battery ?: prev.battery.takeIf { it >= 0 },
            charging = cam?.charging ?: prev.charging,
            recMode = cam?.recMode ?: prev.recMode,
            todayCount = recent?.count { it.dateKey == today } ?: prev.todayCount,
            newestClipTime = recent?.firstOrNull()?.time ?: prev.newestClipTime,
            updatedEpoch = now,
        )
        // No-op if the user hasn't placed the widget; safe to always call.
        runCatching { CameraWidget().updateAll(ctx) }
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

        // The fast lane runs every ~25 s and stamps last_fast_ok on every success (an empty upload
        // still counts), so ~30 min without one means uploads are genuinely failing, not just idle.
        private const val SYNC_LANE_STALE_SEC = 1800L

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
