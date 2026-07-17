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

        // Reinstall-survival mirror in Downloads (gzipped). Keyed by displayName+size so it can be
        // remapped to fresh MediaStore ids after an uninstall/reinstall wipes filesDir. Mirrors
        // PlaceStore.BACKUP_FILENAME.
        private const val BACKUP_FILENAME = "mediacurator_photo_hashes.txt.gz"
    }

    private val appContext = context
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

    // ── Reinstall survival: mirror hashes to Downloads, keyed by displayName+size ──────────
    // filesDir is wiped on reinstall AND MediaStore ids change, so the mirror is keyed by a stable
    // fingerprint and remapped to current ids on restore — letting a reinstall skip re-hashing
    // thousands of photos. Structurally identical to PlaceStore's mirror. Moved here (was in
    // GalleryViewModel) so the photo-hashing worker can back up/restore without the ViewModel.

    /** Write every cached hash to the Downloads mirror, line: `displayName \t size \t md5hex`. */
    fun backupToDownloads(media: List<MediaItem>) {
        ensureLoaded()
        val nameById = media.associate { it.id to it.displayName }
        val resolver = appContext.contentResolver

        fun writeTo(out: java.io.OutputStream) {
            GZIPOutputStream(out).use { gzip ->
                gzip.bufferedWriter(Charsets.UTF_8).use { w ->
                    val snapshot = synchronized(this) { cache.toMap() }
                    var written = 0
                    for ((id, entry) in snapshot) {
                        val dn = nameById[id] ?: continue          // only entries we can re-key
                        if (dn.contains('\t')) continue            // guard the delimiter
                        w.write("$dn\t${entry.first}\t${entry.second}\n")
                        written++
                    }
                    Log.d("PhotoHashStore", "backup: wrote $written entries")
                }
            }
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = BACKUP_FILENAME.removeSuffix(".txt.gz")
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val collection = MediaStore.Downloads.getContentUri("external")
                // Write the new backup first — if the app dies mid-write the previous one is intact.
                // MediaStore auto-numbers ("name.txt (1).gz") if old copies exist; restore picks the
                // newest by DATE_MODIFIED, so coexistence is harmless.
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/gzip")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(collection, values) ?: throw Exception("insert null")
                resolver.openOutputStream(uri)?.use { writeTo(it) } ?: throw Exception("openOutputStream null")

                // New backup is safely on disk — clean up older copies (not the one we just wrote).
                // Resolve OUR name first (may be auto-numbered) so we don't delete it. Match on
                // prefix + ".gz" (numbered variants are "name.txt (1).gz", not "*.txt.gz").
                val newName = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: BACKUP_FILENAME
                try {
                    resolver.delete(collection,
                        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ? AND ${MediaStore.Downloads.DISPLAY_NAME} != ?",
                        arrayOf("$base%.gz", newName))
                } catch (_: Exception) {}

                // Direct-path cleanup for stale files a previous install owns that MediaStore can't touch.
                if (Environment.isExternalStorageManager()) {
                    downloadsDir.listFiles { f ->
                        f.name.startsWith(base) && f.name.endsWith(".gz") && f.name != newName
                    }?.forEach { try { it.delete() } catch (_: Exception) {} }
                }

                // Rename an auto-numbered new file back to the plain name so numbering can't creep up.
                if (newName != BACKUP_FILENAME) {
                    try {
                        resolver.update(uri, ContentValues().apply {
                            put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                        }, null, null)
                    } catch (_: Exception) {}
                }
            } else {
                // Pre-Q: write to temp, then atomically swap into place.
                val tmp = java.io.File(downloadsDir, "$BACKUP_FILENAME.tmp")
                tmp.parentFile?.mkdirs()
                tmp.outputStream().use { writeTo(it) }
                if (!tmp.renameTo(java.io.File(downloadsDir, BACKUP_FILENAME))) {
                    tmp.copyTo(java.io.File(downloadsDir, BACKUP_FILENAME), overwrite = true)
                    tmp.delete()
                }
            }
            Log.d("PhotoHashStore", "backup saved to Downloads")
        } catch (e: Exception) { Log.e("PhotoHashStore", "backup failed", e) }
    }

    /**
     * Seed the cache from the newest Downloads mirror, remapping each entry to the current media id
     * by displayName+size. Returns the number restored. Call when the cache is empty (fresh
     * install / reinstall); flushes to filesDir so later launches need no restore.
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
                    val dn   = line.substring(0, t1)
                    val size = line.substring(t1 + 1, t2).toLongOrNull() ?: return@forEachLine
                    val md5  = line.substring(t2 + 1).trim()
                    if (md5.length != 32) return@forEachLine
                    val item = fpToItem["${dn}_${size}"] ?: return@forEachLine
                    synchronized(this) { cache[item.id] = Pair(item.size, md5); dirty = true }
                    restored++
                }
            }
            return restored
        }

        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = BACKUP_FILENAME.removeSuffix(".txt.gz")
        val inputStream: java.io.InputStream? = run {
            // Pass 1: direct path via MANAGE_EXTERNAL_STORAGE — finds the original regardless of
            // which install created it. Most reliable on reinstall.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && Environment.isExternalStorageManager()) {
                val newest = downloadsDir.listFiles { f -> f.name.startsWith(base) && f.name.endsWith(".gz") }
                    ?.maxByOrNull { it.lastModified() }
                if (newest != null) try { return@run newest.inputStream() } catch (_: Exception) {}
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Pass 2: MediaStore LIKE query — catches (1)/(2) variants this install created.
                val collection = MediaStore.Downloads.getContentUri("external")
                try {
                    resolver.query(collection, arrayOf(MediaStore.Downloads._ID),
                        "${MediaStore.Downloads.DISPLAY_NAME} LIKE ?", arrayOf("$base%.gz"),
                        "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
                        if (!c.moveToFirst()) return@use null
                        val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                        resolver.openInputStream(ContentUris.withAppendedId(collection, id))
                    }
                } catch (e: Exception) { Log.w("PhotoHashStore", "restore query failed", e); null }
            } else {
                val file = java.io.File(downloadsDir, BACKUP_FILENAME)
                if (file.exists()) try { file.inputStream() } catch (_: Exception) { null } else null
            }
        }

        if (inputStream == null) {
            Log.d("PhotoHashStore", "no backup file found — will hash from scratch")
            return 0
        }
        return try {
            val n = inputStream.use { readFrom(it) }
            flush()
            Log.d("PhotoHashStore", "restored $n / ${media.size} hashes from Downloads")
            n
        } catch (e: Exception) { Log.e("PhotoHashStore", "restore failed", e); 0 }
    }
}
