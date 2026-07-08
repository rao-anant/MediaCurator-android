package com.anant.mediacurator

import android.content.Context
import android.util.Log
import java.io.File

/** A photo's place split into browseable levels; blank fields where unknown. */
data class PlaceRecord(val city: String, val state: String, val country: String)

/**
 * Persistent cache of each photo's reverse-geocoded place (v1.1 Place search). Mirrors
 * [PhotoHashStore]. File line: `id \t size \t value`, where value is `city|state|country|alias1,alias2`
 * (or **empty** = scanned but no GPS — so it isn't re-read). city/state/country drive browse; those
 * plus aliases drive search. Touched off the main thread.
 */
class PlaceStore private constructor(context: Context) {

    companion object {
        @Volatile private var instance: PlaceStore? = null
        fun getInstance(context: Context): PlaceStore =
            instance ?: synchronized(this) {
                instance ?: PlaceStore(context.applicationContext).also { instance = it }
            }
    }

    // v2 = structured "city|state|country|aliases" (v1 stored a flat name CSV); new name forces a
    // clean re-index rather than mis-parsing old rows.
    private val cacheFile = File(context.filesDir, "place_cache_v2.txt")
    private val cache = HashMap<Long, Pair<Long, String>>()   // id -> (size, value)
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

    @Synchronized
    fun hasEntry(id: Long, size: Long): Boolean = cache[id]?.first == size

    @Synchronized
    fun save(id: Long, size: Long, city: GeoCity?) { cache[id] = size to encode(city) }

    @Synchronized
    fun deleteEntry(id: Long) { cache.remove(id) }

    /** Count of photos that resolved to a place (excludes the "no GPS" markers). */
    @Synchronized
    fun locatedCount(): Int = cache.count { it.value.second.isNotEmpty() }

    /** media id → search tokens (city, aliases, state, country); only located photos. */
    @Synchronized
    fun toSearchIndex(): Map<Long, List<String>> =
        cache.asSequence()
            .filter { it.value.second.isNotEmpty() }
            .associate { it.key to searchTokens(it.value.second) }

    /**
     * One [PlaceRecord] per located photo — the raw input for browse aggregation ([PlaceBrowse]).
     * Pass [validIds] (live media ids) to exclude stale entries for photos that were deleted, so
     * browse counts match what search can actually return.
     */
    @Synchronized
    fun records(validIds: Set<Long>? = null): List<PlaceRecord> =
        cache.asSequence()
            .filter { validIds == null || it.key in validIds }
            .map { it.value.second }.filter { it.isNotEmpty() }
            .mapNotNull { decode(it) }
            .toList()

    @Synchronized
    fun flush() {
        try {
            cacheFile.bufferedWriter().use { w ->
                for ((id, v) in cache) { w.write("$id\t${v.first}\t${v.second}"); w.newLine() }
            }
        } catch (e: Exception) { Log.e("PlaceStore", "flush failed", e) }
    }

    @Synchronized
    fun clear() {
        cache.clear()
        try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) {}
    }

    // ── value codec: "city|state|country|alias1,alias2" ──────────────────────────
    private fun encode(city: GeoCity?): String {
        if (city == null) return ""
        fun clean(v: String) = v.replace('|', ' ').replace('\t', ' ').trim()
        val aliases = city.altNames.joinToString(",") { clean(it.replace(',', ' ')) }
        return "${clean(city.name)}|${clean(city.admin1)}|${clean(city.country)}|$aliases"
    }

    private fun decode(value: String): PlaceRecord? {
        val p = value.split('|')
        if (p.isEmpty() || p[0].isBlank()) return null
        return PlaceRecord(p[0], p.getOrElse(1) { "" }, p.getOrElse(2) { "" })
    }

    private fun searchTokens(value: String): List<String> {
        val p = value.split('|')
        val city    = p.getOrElse(0) { "" }
        val state   = p.getOrElse(1) { "" }
        val country = p.getOrElse(2) { "" }
        val aliases = p.getOrElse(3) { "" }.split(',').filter { it.isNotBlank() }
        return (listOf(city) + aliases + listOf(state, country)).filter { it.isNotBlank() }.distinct()
    }
}
