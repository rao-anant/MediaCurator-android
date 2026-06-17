package com.anant.mediacurator

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.util.concurrent.Executors

/**
 * Lifetime "how much have I cleaned up" counter: cumulative number of items deleted
 * through the app and the cumulative bytes those items occupied.
 *
 * Persistence (two layers)
 * ────────────────────────
 *  1. SharedPreferences — the live store; instant reads, no I/O on the hot path.
 *  2. mediacurator_stats.json in Downloads — a mirror that survives uninstall/reinstall
 *     (app-private prefs do not).  On a fresh install with empty prefs, [ensureRestored]
 *     reads this file back.  A single dedicated file (NOT folded into the hidden-months
 *     backup, which is deleted when no months are hidden) so the counter can't be wiped.
 *
 * Only count CONFIRMED deletions — callers invoke [record] from the post-deletion
 * completion path, never on a cancelled system delete dialog.
 */
class DeletionStatsStore private constructor(context: Context) {

    companion object {
        private const val PREFS      = "deletion_stats"
        private const val KEY_COUNT  = "deleted_count"
        private const val KEY_BYTES  = "deleted_bytes"
        const val BACKUP_FILENAME    = "mediacurator_stats.json"

        @Volatile private var instance: DeletionStatsStore? = null
        fun getInstance(context: Context): DeletionStatsStore =
            instance ?: synchronized(this) {
                instance ?: DeletionStatsStore(context.applicationContext).also { instance = it }
            }
    }

    private val app   = context.applicationContext
    private val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val io    = Executors.newSingleThreadExecutor()

    val deletedCount: Long get() = prefs.getLong(KEY_COUNT, 0L)
    val deletedBytes: Long get() = prefs.getLong(KEY_BYTES, 0L)

    /** Add a confirmed deletion of [count] items totalling [bytes]. */
    @Synchronized
    fun record(count: Int, bytes: Long) {
        if (count <= 0) return
        prefs.edit()
            .putLong(KEY_COUNT, deletedCount + count)
            .putLong(KEY_BYTES, deletedBytes + bytes.coerceAtLeast(0L))
            .apply()
        DebugLog.i("stats", "record +$count items, +${bytes}B -> total ${deletedCount} items / ${deletedBytes}B")
        io.execute { writeBackup() }   // mirror to Downloads off the main thread
    }

    /**
     * On a fresh install (prefs at zero) restore the counter from the Downloads mirror.
     * Safe to call on every launch — no-op once prefs hold a value.  Run off the main thread.
     */
    fun ensureRestored() {
        if (deletedCount > 0L || deletedBytes > 0L) return
        io.execute {
            val text = readBackup() ?: return@execute
            try {
                val obj   = org.json.JSONObject(text)
                val count = obj.optLong("deletedCount", 0L)
                val bytes = obj.optLong("deletedBytes", 0L)
                synchronized(this) {
                    // Re-check inside the lock: if a delete landed while we were reading,
                    // don't clobber it (record() is also synchronized on this).
                    if ((count > 0L || bytes > 0L) && deletedCount == 0L && deletedBytes == 0L) {
                        prefs.edit().putLong(KEY_COUNT, count).putLong(KEY_BYTES, bytes).apply()
                        Log.i("DeletionStats", "Restored: $count items, $bytes bytes")
                    }
                }
            } catch (e: Exception) {
                Log.e("DeletionStats", "restore parse failed", e)
            }
        }
    }

    // ── Downloads mirror I/O ────────────────────────────────────────────────────

    private fun json(): String =
        "{\"deletedCount\": $deletedCount, \"deletedBytes\": $deletedBytes}"

    private fun writeBackup() {
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FILENAME)
        try {
            // Pass 1: direct file write when MANAGE_EXTERNAL_STORAGE is granted.  This overwrites
            // the canonical Downloads/<name> in place — no MediaStore auto-numbering — so the
            // file the restore reads is always the latest, and it survives reinstall.  (MediaStore
            // insert would create "name (1).json" on reinstall, leaving the canonical file stale.)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && android.os.Environment.isExternalStorageManager()
            ) {
                file.parentFile?.mkdirs()
                file.writeText(json(), Charsets.UTF_8)
                // Clean up any numbered strays a previous (permission-less) run may have left.
                cleanupNumberedStrays(file.parentFile)
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Pass 2: no all-files permission — best-effort via MediaStore (own-package file).
                val resolver   = app.contentResolver
                val collection = MediaStore.Downloads.getContentUri("external")
                val existing = resolver.query(
                    collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(BACKUP_FILENAME), null
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
                }
                val uri = existing ?: resolver.insert(collection, ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, BACKUP_FILENAME)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }) ?: return
                resolver.openOutputStream(uri, "wt")?.use { it.write(json().toByteArray(Charsets.UTF_8)) }
            } else {
                file.parentFile?.mkdirs()
                file.writeText(json(), Charsets.UTF_8)
            }
        } catch (e: Exception) {
            Log.e("DeletionStats", "writeBackup failed", e)
        }
    }

    private fun readBackup(): String? {
        @Suppress("DEPRECATION")
        val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), BACKUP_FILENAME)
        return try {
            // Pass 1: direct file path when MANAGE_EXTERNAL_STORAGE is granted.  This is the
            // ONLY reliable path on reinstall — a fresh install can't read the previous
            // install's non-media Downloads file via MediaStore without that permission.
            // Pick the NEWEST of the canonical file + any numbered strays a previous
            // (permission-less) run may have created, so we never read stale data.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                && android.os.Environment.isExternalStorageManager()
            ) {
                val newest = newestStatsFile(file.parentFile)
                if (newest != null) return newest.readText(Charsets.UTF_8)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Pass 2: MediaStore (works for files this install created — same session).
                val resolver   = app.contentResolver
                val collection = MediaStore.Downloads.getContentUri("external")
                val uri = resolver.query(
                    collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(BACKUP_FILENAME),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { c ->
                    if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null
                }
                if (uri != null) resolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
                else if (file.exists()) file.readText(Charsets.UTF_8) else null
            } else {
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            }
        } catch (e: Exception) {
            Log.e("DeletionStats", "readBackup failed", e); null
        }
    }

    /** Base name without extension, e.g. "mediacurator_stats" from "mediacurator_stats.json". */
    private val baseName = BACKUP_FILENAME.removeSuffix(".json")

    /** Newest of the canonical stats file + any "name (1).json" numbered strays, or null. */
    private fun newestStatsFile(dir: File?): File? =
        dir?.listFiles { f -> f.name.startsWith(baseName) && f.name.endsWith(".json") }
            ?.maxByOrNull { it.lastModified() }

    /** Delete numbered stray copies, keeping only the canonical file. */
    private fun cleanupNumberedStrays(dir: File?) {
        dir?.listFiles { f ->
            f.name.startsWith(baseName) && f.name.endsWith(".json") && f.name != BACKUP_FILENAME
        }?.forEach { try { it.delete() } catch (_: Exception) {} }
    }
}
