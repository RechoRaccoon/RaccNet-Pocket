package com.mediaviewer.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mediaviewer.util.LiveLinkManager
import java.util.concurrent.TimeUnit

/** Periodically re-checks whether the account's active Live Link stream
 *  (Twitch/YouTube) is actually still up, per the feature request ("check
 *  periodically if the specific live is still up or has ended, so that it
 *  can automatically end the Bluesky live status"). All the actual liveness-
 *  check/renew/end logic lives in LiveLinkManager — this is just the
 *  WorkManager scheduling shell around it, same split as DownloadWorker vs.
 *  MainViewModel's own download-trigger call sites. */
class LiveLinkCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        LiveLinkManager.checkAndMaybeRenew(applicationContext)
        return Result.success()
    }
}

/** Enqueue/cancel helpers, kept separate from the Worker class itself so
 *  every Live Link toggle site (Settings, widget, Hub row — all funneled
 *  through LiveLinkManager) can schedule/unschedule without importing
 *  WorkManager types directly. */
object LiveLinkScheduler {
    private const val UNIQUE_WORK_NAME = "live_link_check"

    fun schedule(context: Context) {
        // 15 minutes is WorkManager's own floor for PeriodicWorkRequest — see
        // LiveLinkManager.CHECK_INTERVAL_MINUTES's comment.
        val request = PeriodicWorkRequestBuilder<LiveLinkCheckWorker>(
            LiveLinkManager.CHECK_INTERVAL_MINUTES, TimeUnit.MINUTES
        ).build()
        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }

    fun cancel(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
    }
}
