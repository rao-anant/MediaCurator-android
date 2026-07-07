package com.anant.mediacurator

import android.content.Context
import android.util.Log
import java.io.File

/** A distinct place the user has photos in, with how many photos. */
data class PlaceCount(val name: String, val count: Int)

/**
 * Persistent cache of each photo's reverse-geocoded place (v1.1 Place search). Mirrors
 * [PhotoHashStore]: an in-memory map backed by a tab-separated file in filesDir, touched off the
 * main thread. Value is the comma-joined place names (city + aliases + region). An **empty** value
 * means "scanned, but has no GPS" — recorded so we never re-read that photo's EXIF on a later pass.
 */
class PlaceStore private constructor(context: Context) {

    companion object {
        @Volatile private var instance: PlaceStore? = null
        fun getInstance(context: Context): PlaceStore =
            instance ?: synchronized(this) {
                instance ?: PlaceStore(context.applicationContext).also { instance = it }
            }

        /**
         * Rank the distinct places (primary city = first CSV token) by photo count, then name.
         * Pure — the browseable "places in your library" list. Empty/no-GPS entries are ignored.
         */
        fun summarize(placeCsvValues: Collection<String>): List<PlaceCount> =
            placeCsvValues.asSequence()
                .filter { it.isNotEmpty() }
                .map { it.substringBefore(',').trim() }
                .filter { it.isNotEmpty() }
                .groupingBy { it }
                .eachCount()
                .map { PlaceCount(it.key, it.value) }
                .sortedWith(compareByDescending<PlaceCount> { it.count }.thenBy { it.name })
    }

    private val cacheFile = File(context.filesDir, "place_cache.txt")
    // id -> (size, csvNames).  csv "" = scanned, no location.
    private val cache = HashMap<Long, Pair<Long, String>>()
    private var loaded = false

    @Synchronized
    fun ensureLoaded() {
        if (loaded) return
        loaded = true
        try {
            if (cacheFile.exists()) cacheFile.forEachLine { line ->
                val p = line.split('\t')
                if (p.size >= 2) {
                    val id   = p[0].toLongOrNull() ?: return@forEachLine
                    val size = p[1].toLongOrNull() ?: return@forEachLine
                    cache[id] = size to (if (p.size >= 3) p[2] else "")
                }
            }
        } catch (e: Exception) { Log.e("PlaceStore", "load failed", e) }
    }

    /** True if this photo (matched by id + size) has already been scanned. */
    @Synchronized
    fun hasEntry(id: Long, size: Long): Boolean = cache[id]?.first == size

    @Synchronized
    fun save(id: Long, size: Long, names: List<String>) {
        cache[id] = size to names.joinToString(",") { it.replace(',', ' ').replace('\t', ' ').trim() }
    }

    @Synchronized
    fun deleteEntry(id: Long) { cache.remove(id) }

    /** Count of photos that resolved to a place (excludes the "no GPS" markers). */
    @Synchronized
    fun locatedCount(): Int = cache.count { it.value.second.isNotEmpty() }

    /** The browseable, ranked list of places in the user's library (city → photo count). */
    @Synchronized
    fun placeSummary(): List<PlaceCount> = summarize(cache.values.map { it.second })

    /** media id → place tokens (city + aliases + region); only entries that have a location. */
    @Synchronized
    fun toSearchIndex(): Map<Long, List<String>> =
        cache.asSequence()
            .filter { it.value.second.isNotEmpty() }
            .associate { it.key to it.value.second.split(',').filter { s -> s.isNotBlank() } }

    @Synchronized
    fun flush() {
        try {
            cacheFile.bufferedWriter().use { w ->
                for ((id, v) in cache) { w.write("$id\t${v.first}\t${v.second}"); w.newLine() }
            }
        } catch (e: Exception) { Log.e("PlaceStore", "flush failed", e) }
    }

    /** Wipe (e.g. when the user turns Place search off). */
    @Synchronized
    fun clear() {
        cache.clear()
        try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) {}
    }
}
