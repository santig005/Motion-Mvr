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
import com.famviva.camara.data.DriveClient
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
        val ctx = applicationContext

        // 1) New clips
        runCatching { drive.recentClipNames(20) }.onSuccess { names ->
            if (names.isNotEmpty()) {
                val newest = names.first()             // names in descending order
                val last = store.lastNotified()
                if (last == null) {                    // first run: set the baseline, don't announce history
                    store.setLastNotified(newest)
                } else {
                    val newCount = names.count { it > last }
                    if (newCount > 0) {
                        Notifications.notifyNewClips(ctx, newCount)
                        store.setLastNotified(newest)
                    }
                }
            }
        }

        // 2) NVR health (status.json): recording down / not reporting / low battery
        runCatching { drive.fetchCameraHealth() }.onSuccess { health ->
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
