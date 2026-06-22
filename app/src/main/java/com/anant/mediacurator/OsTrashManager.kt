package com.anant.mediacurator

import android.content.Context
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/**
 * Android 11+ trash via the MediaStore IS_TRASHED flag — the OS recycle bin.
 *
 * With All-files access (requested up front) we flip IS_TRASHED directly via
 * ContentResolver.update — no per-item system dialog. Trashed items leave our app AND the
 * phone's gallery, and the OS auto-purges them after ~30 days.
 *
 * Scope: we track the URIs WE trashed in a small prefs set, so the in-app Trash screen shows
 * only items deleted from Curator — not everything trashed on the device by other apps.
 */
@RequiresApi(Build.VERSION_CODES.R)
class OsTrashManager(private val app: Context) : TrashManager {

    private val resolver get() = app.contentResolver
    private val owned = app.getSharedPreferences("os_trash_owned", Context.MODE_PRIVATE)

    override fun trash(items: List<MediaItem>): TrashResult {
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (item in items) {
            if (setTrashed(Uri.parse(item.uri), true)) {
                count++; bytes += item.size; ok.add(item.uri to item.size)
            }
        }
        addOwned(ok.map { it.first })
        return TrashResult(count, bytes, ok)
    }

    override fun restore(uris: List<String>): TrashResult {
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (u in uris) {
            val uri = Uri.parse(u)
            val sz = sizeOf(uri)
            if (setTrashed(uri, false)) { count++; bytes += sz; ok.add(u to sz) }
        }
        removeOwned(ok.map { it.first })
        return TrashResult(count, bytes, ok)
    }

    override fun purge(uris: List<String>): TrashResult {
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (u in uris) {
            val uri = Uri.parse(u)
            val sz = sizeOf(uri)
            try {
                if (resolver.delete(uri, null, null) > 0) { count++; bytes += sz; ok.add(u to sz) }
            } catch (e: Exception) {
                DebugLog.e("trash", "purge failed: $u", e)
            }
        }
        removeOwned(ok.map { it.first })
        return TrashResult(count, bytes, ok)
    }

    override fun stillTrashed(uris: List<String>): List<String> = uris.filter { isTrashed(Uri.parse(it)) }

    override fun purgeExpired() { /* The OS auto-purges the recycle bin after ~30 days. */ }

    /** Only items WE trashed that are still in the OS bin; prune any restored/purged externally. */
    override fun listTrashed(): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val gone = HashSet<String>()
        for (u in ownedUris()) {
            val item = queryTrashedItem(Uri.parse(u))
            if (item != null) result.add(item) else gone.add(u)
        }
        if (gone.isNotEmpty()) removeOwned(gone)
        return result.sortedByDescending { it.dateTaken }
    }

    // ── MediaStore helpers ───────────────────────────────────────────────────────

    private fun setTrashed(uri: Uri, trashed: Boolean): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
        }
        resolver.update(uri, values, null, null) > 0
    } catch (e: Exception) {
        DebugLog.e("trash", "setTrashed($trashed) failed: $uri", e); false
    }

    private fun isTrashed(uri: Uri): Boolean = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.IS_TRASHED), matchAnyArgs(), null)
            ?.use { if (it.moveToFirst()) it.getInt(0) == 1 else false } ?: false
    } catch (e: Exception) { false }

    private fun sizeOf(uri: Uri): Long = try {
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), matchAnyArgs(), null)
            ?.use { if (it.moveToFirst()) it.getLong(0) else 0L } ?: 0L
    } catch (e: Exception) { 0L }

    /** Build a MediaItem for [uri] only if it still exists AND is trashed; else null. */
    private fun queryTrashedItem(uri: Uri): MediaItem? = try {
        val proj = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.RELATIVE_PATH,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.IS_TRASHED
        )
        resolver.query(uri, proj, matchAnyArgs(), null)?.use { c ->
            if (!c.moveToFirst()) return null
            if (c.getInt(c.getColumnIndexOrThrow(MediaStore.MediaColumns.IS_TRASHED)) != 1) return null
            val mime = c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)) ?: return null
            val type = typeOf(mime) ?: return null
            MediaItem(
                c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)),
                uri.toString(), "external",
                c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)) * 1000L,
                c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)) ?: "",
                c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)),
                type,
                if (type == MediaType.VIDEO || type == MediaType.AUDIO)
                    c.getLong(c.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)) else 0L,
                c.getString(c.getColumnIndexOrThrow(MediaStore.MediaColumns.RELATIVE_PATH)) ?: ""
            )
        }
    } catch (e: Exception) {
        DebugLog.e("trash", "queryTrashedItem failed: $uri", e); null
    }

    private fun matchAnyArgs() = Bundle().apply {
        putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
    }

    private fun typeOf(mime: String): MediaType? = when {
        mime.startsWith("image/")  -> MediaType.IMAGE
        mime.startsWith("video/")  -> MediaType.VIDEO
        mime.startsWith("audio/")  -> MediaType.AUDIO
        mime == "application/pdf"  -> MediaType.PDF
        else                       -> null
    }

    // ── Owned-set (URIs Curator trashed) ─────────────────────────────────────────

    @Synchronized private fun ownedUris(): MutableSet<String> =
        owned.getStringSet(KEY_OWNED, emptySet())!!.toMutableSet()

    @Synchronized private fun addOwned(uris: List<String>) {
        if (uris.isEmpty()) return
        owned.edit().putStringSet(KEY_OWNED, ownedUris().apply { addAll(uris) }).apply()
    }

    @Synchronized private fun removeOwned(uris: Collection<String>) {
        if (uris.isEmpty()) return
        owned.edit().putStringSet(KEY_OWNED, ownedUris().apply { removeAll(uris.toSet()) }).apply()
    }

    private companion object { const val KEY_OWNED = "uris" }
}
