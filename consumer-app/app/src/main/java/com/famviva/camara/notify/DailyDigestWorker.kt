package com.famviva.camara.notify

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.famviva.camara.auth.headlessDriveToken
import com.famviva.camara.data.DriveClient
import java.time.Duration
import java.time.LocalDate
import java.time.ZonedDateTime
import java.util.concurrent.TimeUnit

/**
 * Posts a once-a-day "daily recap" of the day's motion activity (event count, peak hour, the
 * strongest clip + its thumbnail) in the evening. Tapping it opens that day's clip list. Runs
 * headlessly with the token the user already granted (like [NewClipsWorker]).
 *
 * Deliberately quiet: it skips days with no events, and a per-day guard in [NotifyStore] means a
 * re-run of the same day (WorkManager can retry) won't post the recap twice.
 */
class DailyDigestWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val token = headlessDriveToken(applicationContext) ?: return Result.success()
        val ctx = applicationContext
        val store = NotifyStore(ctx)

        val today = LocalDate.now()
        val dateKey = today.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE)
        if (store.lastDigestDay() == dateKey) return Result.success()   // already recapped today

        val drive = DriveClient(tokenProvider = { token }, onUnauthorized = {})
        val clips = runCatching { drive.listClips() }.getOrNull() ?: return Result.retry()
        val todays = clips.filter { it.localDate == today }
        if (todays.isEmpty()) {
            // Nothing happened today — record the day so we don't keep re-listing, but stay silent.
            store.setLastDigestDay(dateKey)
            return Result.success()
        }

        // Peak = the hour bucket with the most events; strongest = highest motion intensity.
        val peakHour = todays.mapNotNull { it.hour }
            .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
        val strongest = todays.filter { it.yavgMax != null }.maxByOrNull { it.yavgMax!! }
        val heroClip = strongest ?: todays.first()
        val thumb = runCatching { drive.fetchClipThumbnail(heroClip) }.getOrNull()

        Notifications.notifyDailyDigest(
            context = ctx,
            count = todays.size,
            peakHour = peakHour,
            strongestTime = strongest?.time?.take(5),   // "HH:MM"
            thumb = thumb,
            deepLinkRoute = "clips/$dateKey",
        )
        store.setLastDigestDay(dateKey)
        return Result.success()
    }

    companion object {
        private const val WORK_NAME = "daily-digest"
        private const val DIGEST_HOUR = 20   // 8pm local — evening recap of the day so far

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DailyDigestWorker>(24, TimeUnit.HOURS)
                .setInitialDelay(minutesUntilNextDigest(), TimeUnit.MINUTES)
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

        /** Minutes from now until the next [DIGEST_HOUR]:00 local time. */
        private fun minutesUntilNextDigest(): Long {
            val now = ZonedDateTime.now()
            var next = now.withHour(DIGEST_HOUR).withMinute(0).withSecond(0).withNano(0)
            if (!next.isAfter(now)) next = next.plusDays(1)
            return Duration.between(now, next).toMinutes().coerceAtLeast(1)
        }
    }
}
