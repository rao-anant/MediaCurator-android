package com.anant.mediacurator

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import java.io.File

/**
 * Stores per-PDF word-frequency counts in internal app storage.
 *
 * Storage layout
 * ──────────────
 *   <filesDir>/pdf_word_index/<mediaId>.words  — one "word:count" line per indexed term
 *   SharedPreferences "pdf_index_meta"         — version key + "empty" sentinels for
 *                                                 scanned / encrypted PDFs
 *
 * File format (example)
 * ─────────────────────
 *   contract:5
 *   invoice:3
 *   payment:7
 *   annual:2
 *
 * No header line is stored. Document length used by BM25 is computed at load time
 * as the sum of all term frequencies.
 *
 * Empty-PDF sentinels
 * ────────────────────
 * PDFs that yield no extractable text (image-only, encrypted) are recorded in
 * SharedPreferences so [hasEntry] returns true and we never re-attempt extraction.
 * A sentinel is NOT written as a `.words` file (which would clutter the directory
 * and waste an inode for zero content).
 *
 * Cache versioning
 * ────────────────
 * Bump [CACHE_VERSION] to wipe the entire index and force a full re-scan.
 *   v1 — initial; BM25 word counts, first 5 pages, stop-word filtered
 */
class PdfIndexStore(private val context: Context) {

    private val indexDir: File =
        File(context.filesDir, "pdf_word_index").also { it.mkdirs() }

    private val meta: SharedPreferences =
        context.getSharedPreferences(PREFS_META, Context.MODE_PRIVATE)

    init {
        if (meta.getString(KEY_VERSION, null) != CACHE_VERSION) {
            indexDir.listFiles()?.forEach { it.delete() }
            meta.edit().clear().putString(KEY_VERSION, CACHE_VERSION).apply()
            Log.i("PdfIndexStore", "Index wiped for version $CACHE_VERSION")
        }
    }

    // ── Presence checks ───────────────────────────────────────────────────────

    /** True if this PDF has been processed (with or without extractable text). */
    fun hasEntry(id: Long): Boolean =
        meta.contains(sentinelKey(id)) || fileFor(id).exists()

    // ── Read ──────────────────────────────────────────────────────────────────

    /**
     * Load word counts for a single [id].
     * Returns null if not yet indexed; empty map for scanned/encrypted PDFs.
     */
    fun getWordCounts(id: Long): Map<String, Int>? {
        if (meta.contains(sentinelKey(id))) return emptyMap()
        val file = fileFor(id)
        if (!file.exists()) return null
        return parseWordsFile(file)
    }

    /**
     * Load ALL indexed PDFs into memory.
     *
     * Returns a map of mediaId → word-frequency map.
     * Empty maps are included for no-text sentinels (they affect BM25 document
     * count but contribute 0 to the inverted index, which is correct).
     * Called once per search when the in-memory BM25 index needs to be (re)built.
     */
    fun loadAll(): Map<Long, Map<String, Int>> {
        val result = mutableMapOf<Long, Map<String, Int>>()

        // Text PDFs: read .words files
        indexDir.listFiles()?.forEach { file ->
            val id = file.nameWithoutExtension.toLongOrNull() ?: return@forEach
            result[id] = parseWordsFile(file)
        }

        // No-text PDFs: sentinels in prefs → empty map
        for ((key, _) in meta.all) {
            if (key == KEY_VERSION) continue
            val id = key.removePrefix(SENTINEL_PREFIX).toLongOrNull() ?: continue
            result.getOrPut(id) { emptyMap() }
        }

        return result
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist [wordCounts] for [id].
     * Must not be called with an empty map — use [markEmpty] for no-text PDFs.
     */
    fun saveWordCounts(id: Long, wordCounts: Map<String, Int>) {
        require(wordCounts.isNotEmpty()) { "Use markEmpty() for PDFs with no text" }
        try {
            val sb = StringBuilder()
            for ((word, count) in wordCounts) {
                sb.append(word).append(':').append(count).append('\n')
            }
            fileFor(id).writeText(sb.toString(), Charsets.UTF_8)
        } catch (e: Exception) {
            Log.e("PdfIndexStore", "saveWordCounts failed for id=$id", e)
        }
    }

    /** Record that this PDF was processed but yielded no extractable text. */
    fun markEmpty(id: Long) {
        meta.edit().putBoolean(sentinelKey(id), true).apply()
    }

    /** Remove index entry for a deleted PDF. */
    fun deleteEntry(id: Long) {
        fileFor(id).delete()
        meta.edit().remove(sentinelKey(id)).apply()
    }

    /**
     * Cheap entry count — just counts files + sentinel prefs keys.
     * Does NOT load file contents, so it's O(N) on file-system metadata only.
     * Use this instead of loadAll().size whenever you only need to know
     * whether the index is empty.
     */
    fun countEntries(): Int {
        val fileCount     = indexDir.listFiles()?.size ?: 0
        val sentinelCount = meta.all.count { (key, _) -> key != KEY_VERSION }
        return fileCount + sentinelCount
    }

    /** Wipe everything and reset version (forces full re-index on next open). */
    fun clearAll() {
        indexDir.listFiles()?.forEach { it.delete() }
        meta.edit().clear().putString(KEY_VERSION, CACHE_VERSION).apply()
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun fileFor(id: Long) = File(indexDir, "$id.words")
    private fun sentinelKey(id: Long) = "$SENTINEL_PREFIX$id"

    private fun parseWordsFile(file: File): Map<String, Int> {
        val result = HashMap<String, Int>()
        try {
            file.forEachLine(Charsets.UTF_8) { line ->
                val colon = line.lastIndexOf(':')
                if (colon > 0) {
                    val word  = line.substring(0, colon)
                    val count = line.substring(colon + 1).toIntOrNull() ?: return@forEachLine
                    if (word.isNotEmpty() && count > 0) result[word] = count
                }
            }
        } catch (e: Exception) {
            Log.e("PdfIndexStore", "parseWordsFile failed: ${file.name}", e)
        }
        return result
    }

    companion object {
        private const val PREFS_META      = "pdf_index_meta"
        private const val KEY_VERSION     = "_version"
        private const val SENTINEL_PREFIX = "empty_"
        const val CACHE_VERSION           = "v1"
    }
}
