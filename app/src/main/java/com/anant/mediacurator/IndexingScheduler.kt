package com.anant.mediacurator

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

/**
 * Schedules [IndexingWorker] so photo hashing + geo indexing finish while the app is closed or
 * after an OEM lock-kill. Called whenever the foreground kicks off indexing — the job is unique
 * and KEEP, so this only ever ensures one is scheduled; it no-ops if nothing is left to do.
 */
object IndexingScheduler {

    private const val WORK_NAME = "mediacurator_background_indexing"

    fun enqueue(context: Context) {
        // Deferrable, considerate: run only while charging and not on a low battery. Finishing a
        // 16k-photo hash + EXIF scan is real CPU/IO, so we don't want it draining the battery
        // mid-day — it catches up whenever the phone is plugged in.
        val constraints = Constraints.Builder()
            .setRequiresCharging(true)
            .setRequiresBatteryNotLow(true)
            .build()

        val request = OneTimeWorkRequestBuilder<IndexingWorker>()
            .setConstraints(constraints)
            .build()

        // KEEP: if a job is already pending/running, leave it; otherwise schedule a fresh one. So
        // each app session guarantees a background finisher is queued without ever stacking dupes.
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.KEEP, request)
    }
}
