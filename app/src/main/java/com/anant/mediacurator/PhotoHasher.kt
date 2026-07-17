package com.anant.mediacurator

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext

/**
 * MD5-hashes photos & videos for duplicate detection. The single hashing implementation, shared by
 * [GalleryViewModel]'s foreground job and the background [IndexingWorker] — mirrors [PlaceIndexer].
 *
 * Kill-safe: hashes are flushed to disk every batch (survives OEM lock-kill) and the whole cache is
 * mirrored to Downloads at the end so a reinstall skips re-hashing. Cancellation is cooperative via
 * the caller's coroutine scope. MD5 is I/O-bound (flash read dominates), so a small parallel pool
 * gives ~3-4x throughput on UFS flash; on eMMC/low-RAM devices 2 workers is the sweet spot.
 */
object PhotoHasher {

    /** Outcome so callers can react (VM surfaces OOM to the user; the worker just retries later). */
    enum class Result { COMPLETED, CANCELLED, OOM }

    /**
     * Hash every item in [media] (photos + videos) not already in [store]. Restores from the
     * Downloads mirror first if the cache is empty. Reports progress as `(done, total-to-hash)`.
     *
     * Does NOT read or write the duplicate-detection preference — the caller decides whether to run
     * (check the pref first) and how to react to [Result.OOM] (e.g. auto-disable + notify).
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    suspend fun hashPhotos(
        context: Context,
        media: List<MediaItem>,
        store: PhotoHashStore,
        repo: MediaRepository,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> }
    ): Result {
        store.ensureLoaded()

        // Fresh install / reinstall: filesDir was wiped, so seed from the Downloads mirror (remapped
        // by displayName+size) before hashing — skips re-reading files we already know.
        if (store.countEntries() == 0 && media.isNotEmpty()) store.restoreFromDownloads(media)

        val unprocessed = media.filter { !store.hasValidEntry(it.id, it.size) }
        if (unprocessed.isEmpty()) { onProgress(0, 0); return Result.COMPLETED }

        val total   = unprocessed.size
        val counter = java.util.concurrent.atomic.AtomicInteger(0)
        onProgress(0, total)

        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val workers = if (am.isLowRamDevice || Runtime.getRuntime().availableProcessors() <= 4) 2 else 4
        val dispatcher = Dispatchers.IO.limitedParallelism(workers)

        try {
            // Outer batches of 50 = flush + progress checkpoints. Small so a run killed mid-way
            // (OEM lock-kill) keeps its progress and resumes instead of restarting.
            for (batch in unprocessed.chunked(50)) {
                if (!currentCoroutineContext().isActive) return Result.CANCELLED
                coroutineScope {
                    batch.map { item ->
                        async(dispatcher) {
                            try {
                                val md5 = repo.computeMd5(item)
                                if (md5.isNotEmpty()) store.saveHash(item.id, item.size, md5)
                            } catch (e: Throwable) {
                                if (e is OutOfMemoryError) throw e   // escalate to the outer handler
                                Log.w("PhotoHasher", "computeMd5 threw for ${item.displayName}", e)
                            }
                            val done = counter.incrementAndGet()
                            if (done % 25 == 0 || done == total) onProgress(done, total)
                        }
                    }.awaitAll()
                }
                store.flush()   // persist each batch — partial progress survives a crash
            }
        } catch (oom: OutOfMemoryError) {
            Log.e("PhotoHasher", "OOM after ${counter.get()}/$total — stopping", oom)
            store.flush()
            return Result.OOM
        }

        // Always persist partial progress, even if cancelled mid-run.
        store.flush()
        if (!currentCoroutineContext().isActive) return Result.CANCELLED

        // Only a completed run writes the backup (a cancelled one would overwrite a complete
        // mirror with a partial one). Save BEFORE reporting done so an uninstall right after
        // still has the mirror on disk.
        if (counter.get() > 0) store.backupToDownloads(media)
        onProgress(total, total)
        return Result.COMPLETED
    }
}
