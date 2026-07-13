package com.anant.mediacurator

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

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

        // Reinstall-survival mirror in Downloads (gzipped). Keyed by displayName+size so it can be
        // remapped to fresh MediaStore ids after an uninstall/reinstall wipes filesDir.
        private const val BACKUP_FILENAME = "mediacurator_places.txt.gz"
    }

    private val appContext = context

    // v2 = structured "city|state|country|aliases" (v1 stored a flat name CSV); new name forces a
    // clean re-index rather than mis-parsing old rows.
    private val cacheFile = File(context.filesDir, "place_cache_v2.txt")
    private val cache = HashMap<Long, Pair<Long, String>>()   // id -> (size, value)
    private var loaded = false
    // Append-mode journal writer, lazily opened by save(). Each save appends ONE line and flushes to
    // the OS, so every scanned photo survives a process kill (Samsung kills on lock) — without ever
    // rewriting the whole file mid-run. Duplicate lines are fine: on load, later lines win; flush()
    // closes the journal and compacts the file.
    private var journal: java.io.BufferedWriter? = null

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
    fun save(id: Long, size: Long, city: GeoCity?) {
        val value = encode(city)
        cache[id] = size to value
        // Journal the entry immediately (O(1) append; write() reaches the kernel, so it survives an
        // app kill — only power loss could drop it, and a re-scan of a few photos is fine then).
        try {
            val w = journal ?: java.io.BufferedWriter(java.io.FileWriter(cacheFile, true)).also { journal = it }
            w.write("$id\t$size\t$value"); w.newLine(); w.flush()
        } catch (e: Exception) { Log.e("PlaceStore", "journal append failed", e) }
    }

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

    /** Close the journal and compact the file (dedupe appended lines). Call at end-of-run. */
    @Synchronized
    fun flush() {
        try { journal?.close() } catch (_: Exception) {}
        journal = null
        try {
            cacheFile.bufferedWriter().use { w ->
                for ((id, v) in cache) { w.write("$id\t${v.first}\t${v.second}"); w.newLine() }
            }
        } catch (e: Exception) { Log.e("PlaceStore", "flush failed", e) }
    }

    @Synchronized
    fun isEmpty(): Boolean = cache.isEmpty()

    @Synchronized
    fun clear() {
        cache.clear()
        try { journal?.close() } catch (_: Exception) {}
        journal = null
        try { if (cacheFile.exists()) cacheFile.delete() } catch (_: Exception) {}
    }

    // ── Reinstall survival: mirror the index to Downloads, keyed by displayName+size ──────
    // Mirrors the photo-hash backup. filesDir is wiped on reinstall AND MediaStore ids change,
    // so the mirror is keyed by a stable fingerprint and remapped to current ids on restore.
    // This lets a reinstall skip the slow EXIF re-scan of thousands of photos.

    /** Write the whole index (located photos AND "no GPS" markers) to the Downloads mirror. */
    fun backupToDownloads(media: List<MediaItem>) {
        ensureLoaded()
        val nameById = media.associate { it.id to it.displayName }
        val resolver = appContext.contentResolver

        fun writeTo(out: java.io.OutputStream) {
            GZIPOutputStream(out).use { gzip ->
                gzip.bufferedWriter(Charsets.UTF_8).use { w ->
                    val snapshot = synchronized(this) { cache.toMap() }
                    for ((id, v) in snapshot) {
                        val dn = nameById[id] ?: continue          // only entries we can re-key
                        if (dn.contains('\t')) continue            // guard the delimiter
                        w.write("$dn\t${v.first}\t${v.second}\n")
                    }
                }
            }
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = BACKUP_FILENAME.removeSuffix(".txt.gz")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri("external")
                // Write the new backup first; MediaStore auto-numbers if older copies exist.
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(collection, values) ?: throw Exception("insert null")
                resolver.openOutputStream(uri)?.use { writeTo(it) } ?: throw Exception("openOutputStream null")
                // New backup is safe — delete older copies (but not the one we just wrote).
                val newName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: BACKUP_FILENAME
                try {
                    resolver.delete(collection,
                        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} != ?",
                        arrayOf("$base%.gz", newName))
                } catch (_: Exception) {}
            } else {
                java.io.File(downloadsDir, BACKUP_FILENAME).outputStream().use { writeTo(it) }
            }
        } catch (e: Exception) { Log.e("PlaceStore", "backup failed", e) }
    }

    /**
     * Seed the (empty) cache from the newest Downloads mirror, remapping each backed-up entry to the
     * current media id by displayName+size. Returns the number of entries restored. Call only when
     * [isEmpty]; flushes to filesDir so subsequent launches need no restore.
     */
    fun restoreFromDownloads(media: List<MediaItem>): Int {
        val resolver = appContext.contentResolver
        val fpToItem = media.associateBy { "${it.displayName}_${it.size}" }

        fun readFrom(inp: java.io.InputStream): Int {
            var restored = 0
            GZIPInputStream(inp).bufferedReader(Charsets.UTF_8).use { reader ->
                reader.forEachLine { line ->
                    val t1 = line.indexOf('\t'); if (t1 < 0) return@forEachLine
                    val t2 = line.indexOf('\t', t1 + 1); if (t2 < 0) return@forEachLine
                    val dn = line.substring(0, t1)
                    val size = line.substring(t1 + 1, t2).toLongOrNull() ?: return@forEachLine
                    val value = line.substring(t2 + 1)                 // "" = scanned, no GPS
                    val item = fpToItem["${dn}_${size}"] ?: return@forEachLine
                    synchronized(this) { cache[item.id] = item.size to value }
                    restored++
                }
            }
            return restored
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = BACKUP_FILENAME.removeSuffix(".txt.gz")
        val inputStream: java.io.InputStream? = run {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                val newest = downloadsDir.listFiles { f -> f.name.startsWith(base) && f.name.endsWith(".gz") }
                    ?.maxByOrNull { it.lastModified() }
                if (newest != null) try { return@run newest.inputStream() } catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri("external")
                try {
                    resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$base%.gz"),
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        resolver.openInputStream(ContentUris.withAppendedId(collection, id))
                    }
                } catch (e: Exception) { Log.w("PlaceStore", "restore query failed", e); null }
            } else {
                val file = java.io.File(downloadsDir, BACKUP_FILENAME)
                if (file.exists()) try { file.inputStream() } catch (_: Exception) { null } else null
            }
        }

        if (inputStream == null) return 0
        return try {
            val n = inputStream.use { readFrom(it) }
            if (n > 0) { loaded = true; flush() }
            Log.d("PlaceStore", "restored $n place entries from Downloads")
            n
        } catch (e: Exception) { Log.e("PlaceStore", "restore failed", e); 0 }
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
