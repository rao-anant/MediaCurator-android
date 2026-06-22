package com.anant.mediacurator

import android.content.Context
import android.os.Build

/**
 * Outcome of a trash/restore/purge op. [entries] are the affected (uri, sizeBytes) pairs —
 * for OS trash the uri is a MediaStore content uri; for app trash it's the trash-file path.
 */
data class TrashResult(val count: Int, val bytes: Long, val entries: List<Pair<String, Long>>)

/**
 * Soft-delete ("recycle bin") abstraction.
 *
 * Two implementations keep the common, well-trodden Android 11+ path clean and quarantine the
 * fiddly legacy ≤10 path:
 *   • [OsTrashManager]  (API 30+) — flips MediaStore IS_TRASHED (the OS recycle bin; 30-day
 *     auto-purge; recoverable in our app AND the phone's own gallery). Dialog-free via All-files.
 *   • [AppTrashManager] (API ≤29) — moves files to a hidden app trash folder + metadata, since
 *     the platform offers no trash there.
 *
 * All methods run synchronous I/O — call them off the main thread.
 */
interface TrashManager {
    /** Move [items] to trash. Returns what actually got trashed. */
    fun trash(items: List<MediaItem>): TrashResult
    /** Restore the given trashed [uris] (best-effort; skips any no longer in trash). */
    fun restore(uris: List<String>): TrashResult
    /** Subset of [uris] that are STILL in trash (others were restored externally or purged). */
    fun stillTrashed(uris: List<String>): List<String>
    /** Everything currently in trash, newest first — for the Trash screen. */
    fun listTrashed(): List<MediaItem>
    /** Permanently delete the given trashed [uris] (Delete forever / Empty). */
    fun purge(uris: List<String>): TrashResult

    companion object {
        @Volatile private var instance: TrashManager? = null
        fun get(context: Context): TrashManager =
            instance ?: synchronized(this) {
                instance ?: (
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) OsTrashManager(context.applicationContext)
                    else AppTrashManager(context.applicationContext)
                ).also { instance = it }
            }
    }
}
