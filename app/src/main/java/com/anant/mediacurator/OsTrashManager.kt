package com.anant.mediacurator

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import androidx.annotation.RequiresApi

/**
 * Android 11+ trash via the MediaStore IS_TRASHED flag — the OS recycle bin.
 *
 * With All-files access (which the app requests up front) we can flip IS_TRASHED directly with
 * ContentResolver.update, no per-item system dialog — same as the app's existing direct delete.
 * Trashed items leave our app AND the phone's gallery, and the OS auto-purges them after ~30 days.
 */
@RequiresApi(Build.VERSION_CODES.R)
class OsTrashManager(private val app: Context) : TrashManager {

    private val resolver get() = app.contentResolver

    override fun trash(items: List<MediaItem>): TrashResult {
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (item in items) {
            if (setTrashed(Uri.parse(item.uri), true)) {
                count++; bytes += item.size; ok.add(item.uri to item.size)
            }
        }
        return TrashResult(count, bytes, ok)
    }

    override fun restore(uris: List<String>): TrashResult {
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (u in uris) {
            val uri = Uri.parse(u)
            val sz = sizeOf(uri)
            if (setTrashed(uri, false)) { count++; bytes += sz; ok.add(u to sz) }
        }
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
        return TrashResult(count, bytes, ok)
    }

    override fun stillTrashed(uris: List<String>): List<String> = uris.filter { u ->
        try {
            // A direct id-uri query ignores MATCH_ONLY (it returns the addressed row regardless),
            // so read the IS_TRASHED column directly. MATCH_INCLUDE makes a trashed row visible;
            // a purged row yields no result → treated as not-trashed.
            val args = Bundle().apply {
                putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
            }
            resolver.query(Uri.parse(u), arrayOf(MediaStore.MediaColumns.IS_TRASHED), args, null)
                ?.use { if (it.moveToFirst()) it.getInt(0) == 1 else false } ?: false
        } catch (e: Exception) { false }
    }

    override fun listTrashed(): List<MediaItem> {
        val result = ArrayList<MediaItem>()
        val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL)
        val projection = arrayOf(
            MediaStore.Files.FileColumns._ID,
            MediaStore.Files.FileColumns.DISPLAY_NAME,
            MediaStore.Files.FileColumns.SIZE,
            MediaStore.Files.FileColumns.MIME_TYPE,
            MediaStore.Files.FileColumns.DATE_MODIFIED,
            MediaStore.Files.FileColumns.RELATIVE_PATH,
            MediaStore.Files.FileColumns.DURATION
        )
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_ONLY)
            putString(
                android.content.ContentResolver.QUERY_ARG_SQL_SORT_ORDER,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )
        }
        try {
            resolver.query(collection, projection, args, null)?.use { c ->
                val idC   = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns._ID)
                val nameC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DISPLAY_NAME)
                val sizeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.SIZE)
                val mimeC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.MIME_TYPE)
                val dateC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DATE_MODIFIED)
                val pathC = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.RELATIVE_PATH)
                val durC  = c.getColumnIndexOrThrow(MediaStore.Files.FileColumns.DURATION)
                while (c.moveToNext()) {
                    val mime = c.getString(mimeC) ?: continue
                    val type = typeOf(mime) ?: continue
                    val id   = c.getLong(idC)
                    val uri  = ContentUris.withAppendedId(collection, id).toString()
                    result.add(
                        MediaItem(
                            id, uri, "external",
                            c.getLong(dateC) * 1000L,
                            c.getString(nameC) ?: "",
                            c.getLong(sizeC),
                            type,
                            if (type == MediaType.VIDEO || type == MediaType.AUDIO) c.getLong(durC) else 0L,
                            c.getString(pathC) ?: ""
                        )
                    )
                }
            }
        } catch (e: Exception) {
            DebugLog.e("trash", "listTrashed failed", e)
        }
        return result
    }

    private fun setTrashed(uri: Uri, trashed: Boolean): Boolean = try {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.IS_TRASHED, if (trashed) 1 else 0)
        }
        resolver.update(uri, values, null, null) > 0
    } catch (e: Exception) {
        DebugLog.e("trash", "setTrashed($trashed) failed: $uri", e); false
    }

    /** Size of a (possibly trashed) item; needs MATCH_INCLUDE so trashed rows are visible. */
    private fun sizeOf(uri: Uri): Long = try {
        val args = Bundle().apply {
            putInt(MediaStore.QUERY_ARG_MATCH_TRASHED, MediaStore.MATCH_INCLUDE)
        }
        resolver.query(uri, arrayOf(MediaStore.MediaColumns.SIZE), args, null)?.use {
            if (it.moveToFirst()) it.getLong(0) else 0L
        } ?: 0L
    } catch (e: Exception) { 0L }

    private fun typeOf(mime: String): MediaType? = when {
        mime.startsWith("image/")      -> MediaType.IMAGE
        mime.startsWith("video/")      -> MediaType.VIDEO
        mime.startsWith("audio/")      -> MediaType.AUDIO
        mime == "application/pdf"      -> MediaType.PDF
        else                           -> null
    }
}
