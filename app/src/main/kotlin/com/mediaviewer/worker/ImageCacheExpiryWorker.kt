package com.mediaviewer.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.mediaviewer.util.ImageLoading
import java.util.concurrent.TimeUnit

/** Once a day, even when the app isn't opened: wipes the image cache if
 *  it's due (every 30 days — see ImageLoading.wipeIfDue for why). */
class ImageCacheExpiryWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        ImageLoading.wipeIfDue(applicationContext)
        return Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "image_cache_expiry"

        /** Idempotent — called on every app start; KEEP leaves an already
         *  scheduled job alone. */
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ImageCacheExpiryWorker>(1, TimeUnit.DAYS).build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(UNIQUE_WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
