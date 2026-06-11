package com.anant.mediacurator

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Stores MD5 hashes for photos in a single flat file.
 *
 * Storage layout
 * ──────────────
 *   <filesDir>/photo_hash_cache.txt — one line per photo: id:size:md5hex
 *
 * Cache key: (id, size).  If a photo's size changes (rare but possible after editing),
 * [hasValidEntry] returns false and the photo is re-hashed on the next indexing run.
 *
 * Singleton + thread safety
 * ─────────────────────────
 * A single process-wide instance (via [getInstance]) is shared by GalleryViewModel's
 * hashing job, DuplicatesViewModel, and any parallel hashing workers.  All mutating
 * and reading methods are synchronized on the instance — the critical sections are
 * tiny (HashMap ops) except [flush]/[ensureLoaded] which do file I/O and should only
 * be called from background threads.
 */
class PhotoHashStore private constructor(context: Context) {

    companion object {
        @Volatile private var instance: PhotoHashStore? = null

        fun getInstance(context: Context): PhotoHashStore =
            instance ?: synchronized(this) {
                instance ?: PhotoHashStore(context.applicationContext).also { instance = it }
            }
    }

    private val cacheFile = File(context.filesDir, "photo_hash_cache.txt")

    // id → (size, md5hex)
    private val cache = HashMap<Long, Pair<Long, String>>()
    private var loaded = false
    private var dirty  = false

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Load from disk into memory.  Safe to call multiple times — skips the reload if
     * data is already loaded.  Call from a background thread (file I/O).
     */
    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        if (!cacheFile.exists()) return
        try {
            cacheFile.bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    val a = line.indexOf(':')
                    if (a <= 0) return@forEachLine
                    val b = line.indexOf(':', a + 1)
                    if (b <= a) return@forEachLine
                    val id  = line.substring(0, a).toLongOrNull()  ?: return@forEachLine
                    val sz  = line.substring(a + 1, b).toLongOrNull() ?: return@forEachLine
                    val md5 = line.substring(b + 1)
                    if (md5.length == 32) cache[id] = Pair(sz, md5)
                }
            }
            Log.d("PhotoHashStore", "Loaded ${cache.size} cached hashes")
        } catch (e: Exception) {
            Log.e("PhotoHashStore", "load failed", e)
        }
    }

    /**
     * Force a fresh reload from disk.
     *
     * NOTE: with the singleton there is normally no need for this — all writers share
     * the same in-memory cache.  Kept for safety (e.g. external file changes).
     */
    @Synchronized
    fun reload() {
        loaded = false
        dirty  = false
        cache.clear()
        ensureLoaded()
    }

    // ── Presence / lookup ─────────────────────────────────────────────────────

    /** True if a hash is cached for [id] and its [size] matches (still valid). */
    @Synchronized
    fun hasValidEntry(id: Long, size: Long): Boolean {
        val entry = cache[id] ?: return false
        return entry.first == size
    }

    /** Returns the cached MD5 for [id], or null if not present. */
    @Synchronized
    fun getHash(id: Long): String? = cache[id]?.second

    /** Number of entries currently in the in-memory cache. */
    @Synchronized
    fun countEntries(): Int = cache.size

    /** True if the on-disk cache file exists (even if not yet loaded into memory). */
    fun cacheFileExists(): Boolean = cacheFile.exists()

    // ── Write ─────────────────────────────────────────────────────────────────

    @Synchronized
    fun saveHash(id: Long, size: Long, md5: String) {
        cache[id] = Pair(size, md5)
        dirty = true
    }

    @Synchronized
    fun deleteEntry(id: Long) {
        if (cache.remove(id) != null) dirty = true
    }

    // ── Duplicate detection ───────────────────────────────────────────────────

    /**
     * Group all cached photos by MD5 hash.
     * Returns only groups with 2 or more photos (i.e. actual duplicates).
     * Result: md5hex → list of photo IDs.
     */
    @Synchronized
    fun findDuplicateGroups(): Map<String, List<Long>> {
        val md5ToIds = HashMap<String, MutableList<Long>>()
        for ((id, entry) in cache) {
            md5ToIds.getOrPut(entry.second) { mutableListOf() }.add(id)
        }
        return md5ToIds.filter { it.value.size >= 2 }
    }

    // ── Persistence ───────────────────────────────────────────────────────────

    /**
     * Flush the in-memory cache to disk.  No-op if nothing has changed.
     * Builds the content inside the lock, writes the file inside the lock too —
     * the write is a single sequential dump (<2 MB for ~17k entries), fast enough
     * that holding the lock is fine and guarantees writers never interleave.
     * Call from a background thread.
     */
    @Synchronized
    fun flush() {
        if (!dirty) return
        try {
            val sb = StringBuilder(cache.size * 60)
            for ((id, entry) in cache) {
                sb.append(id).append(':').append(entry.first).append(':').append(entry.second).append('\n')
            }
            // Write to temp + rename so a crash mid-write can't corrupt the cache.
            val tmp = File(cacheFile.parentFile, cacheFile.name + ".tmp")
            tmp.writeText(sb.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(cacheFile)) {
                cacheFile.writeText(sb.toString(), Charsets.UTF_8)
                tmp.delete()
            }
            dirty = false
        } catch (e: Exception) {
            Log.e("PhotoHashStore", "flush failed", e)
        }
    }
}
