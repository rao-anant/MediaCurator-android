package com.anant.mediacurator

import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters

/**
 * Finishes photo hashing + geo indexing in the background, so they complete even when the app is
 * closed or the OEM kills it on lock. Scheduled by [IndexingScheduler] (charging + battery-not-low).
 *
 * Reuses the exact same kill-safe, idempotent code paths as the foreground — [PhotoHasher] and
 * [PlaceIndexer], writing to the same singleton stores — so a run cut short by the system's work
 * time limit just resumes from where it left off next time. Deliberately does NOT touch PDF
 * indexing: PDFBox is the OOM-prone path and stays foreground-only.
 */
class IndexingWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val ctx   = applicationContext
        val prefs = PreferencesManager(ctx)

        val wantHashing = prefs.isPhotoDuplicateDetectionEnabled()
        val wantPlaces  = prefs.isPlaceSearchEnabled()
        if (!wantHashing && !wantPlaces) return Result.success()

        return try {
            val repo  = MediaRepository(ctx)
            val media = MediaCache.get(repo)                 // shared with a live app; fetches if cold
            if (media.isEmpty()) return Result.success()     // no permission / nothing on device

            // Photos first (unlocks Duplicates), then videos — same order as the foreground.
            if (wantHashing) {
                val hashable = media.filter { it.type == MediaType.IMAGE || it.type == MediaType.VIDEO }
                val result = PhotoHasher.hashPhotos(ctx, hashable, PhotoHashStore.getInstance(ctx), repo)
                if (result == PhotoHasher.Result.OOM) {
                    // Same reaction as the foreground: stop trying rather than loop into OOM.
                    prefs.setPhotoDuplicateDetectionEnabled(false)
                }
            }

            // Geo needs ACCESS_MEDIA_LOCATION (to read un-redacted EXIF GPS on Q+).
            if (wantPlaces && hasMediaLocation(ctx)) {
                val images = media.filter { it.type == MediaType.IMAGE }
                PlaceIndexer.indexImages(ctx, images, PlaceStore.getInstance(ctx))
            }

            Result.success()
        } catch (e: kotlinx.coroutines.CancellationException) {
            // System reclaimed the worker (time limit / constraints lost). Progress is already
            // flushed by the stores; ask to run again to finish the rest.
            Result.retry()
        } catch (e: Exception) {
            DebugLog.e("worker", "background indexing failed", e)
            // Bounded retry so a persistent failure doesn't loop forever.
            if (runAttemptCount < 3) Result.retry() else Result.success()
        }
    }

    private fun hasMediaLocation(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.ACCESS_MEDIA_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
}
