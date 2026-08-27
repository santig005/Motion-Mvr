package com.famviva.camara.notify

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.famviva.camara.data.ClipClassifier
import com.famviva.camara.data.ClipFrames
import com.famviva.camara.data.ClipLabel
import com.famviva.camara.data.LabelStore
import com.famviva.camara.data.OfflineStore
import com.famviva.camara.data.ThumbArchive
import java.io.File
import kotlinx.coroutines.flow.Flow

/**
 * Backfills [ClipLabel]s over the thumbnails already sitting in the local archive, so the history
 * becomes searchable/badged retroactively rather than only new clips getting a 👤. It is the third
 * step of the Phase-1 sequencing (classify → badge → backfill), and it needs no network: every
 * thumbnail the app ever downloaded is already on the phone (`ThumbArchive`), so this is pure local
 * inference — a few dozen ms per clip on the Tensor NPU.
 *
 * Idempotent and cheap to re-run: it skips any clip already in [LabelStore], so a second pass only
 * labels thumbnails that arrived since the first. If there's no model on the device it's a clean no-op
 * (people-detection stays dormant until `download-model.sh` has run).
 *
 * NEWEST FIRST: it processes the most recent clips before older ones, so the badges you're most
 * likely to be looking at (today, yesterday) appear first while the long tail fills in behind them.
 * It reports [KEY_DONE]/[KEY_TOTAL] progress the whole way so the UI can show a real bar — with a few
 * hundred thumbnails this is not instant.
 */
class LabelBackfillWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        val classifier = ClipClassifier(ctx)
        if (!classifier.available) return Result.success()   // no model -> nothing to backfill

        classifier.use { c ->
            val thumbs = ThumbArchive(ctx)
            val store = LabelStore(ctx)
            val offline = OfflineStore(ctx)
            val frameSamples = NotifyStore(ctx).detectionFrames
            // Base names are mt_YYYYMMDD_HHMMSS, so a plain descending sort is newest-first.
            val pending = thumbs.archivedBaseNames().filterNot { store.has(it) }.sortedDescending()
            val total = pending.size
            setProgress(workDataOf(KEY_TOTAL to total, KEY_DONE to 0))

            val batch = HashMap<String, ClipLabel>()
            var done = 0
            for (name in pending) {
                // Multi-frame over the mp4 when a copy is already on the phone (offline-downloaded) —
                // same accuracy win as new clips, at zero network cost. This pass stays network-free:
                // clips without a local mp4 fall back to the single archived thumbnail.
                val label = offline.downloadedPathForName(name)?.let { path ->
                    ClipFrames.classifyFile(File(path), c, frameSamples)
                } ?: thumbs.decode(name)?.let { bmp ->
                    c.classify(bmp).also { bmp.recycle() }
                }
                label?.let { batch[name] = it }
                done++
                // Flush periodically so a long backfill persists progress instead of risking it all on
                // one final write (a kill mid-run still leaves what it managed labelled), and publish
                // progress on the same cadence so the bar advances smoothly without spamming updates.
                if (batch.size >= FLUSH_EVERY) { store.putAll(batch); batch.clear() }
                if (done % PROGRESS_EVERY == 0 || done == total) {
                    setProgress(workDataOf(KEY_TOTAL to total, KEY_DONE to done))
                }
            }
            store.putAll(batch)
        }
        return Result.success()
    }

    companion object {
        const val WORK_NAME = "label-backfill"
        const val KEY_DONE = "done"
        const val KEY_TOTAL = "total"
        private const val FLUSH_EVERY = 25
        private const val PROGRESS_EVERY = 5

        /** Enqueue a single backfill pass; REPLACE so tapping the menu twice doesn't stack runs. */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<LabelBackfillWorker>().build(),
            )
        }

        /** Live [WorkInfo] for the backfill so the UI can render a progress bar while it runs. */
        fun progressFlow(context: Context): Flow<List<WorkInfo>> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(WORK_NAME)
    }
}
