package com.anant.mediacurator

/**
 * Process-wide cache of the full media fetch, so the Home hub and the gallery share ONE
 * MediaStore scan instead of each scanning ~17k items independently.
 *
 * Whoever loads first (usually Home) pays for the scan; the other reuses it. A forced
 * refresh (e.g. after a delete, or pull-to-refresh) refetches and replaces the cache.
 */
object MediaCache {

    @Volatile private var cached: List<MediaItem>? = null

    /** Return the cached media, fetching once if absent or [forceRefresh] is set. */
    @Synchronized
    fun get(repo: MediaRepository, forceRefresh: Boolean = false): List<MediaItem> {
        val c = cached
        if (c != null && !forceRefresh) return c
        return repo.fetchAllMedia().also { cached = it }
    }

    /** Drop the cache so the next [get] refetches (call when the library changes). */
    @Synchronized
    fun invalidate() { cached = null }

    /** Cached item count without triggering a scan (-1 if nothing cached yet). */
    fun peekSize(): Int = cached?.size ?: -1
}
