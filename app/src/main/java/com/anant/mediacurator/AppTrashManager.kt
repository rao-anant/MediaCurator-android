package com.anant.mediacurator

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.io.File

/**
 * Legacy (Android ≤10) trash — the platform offers no IS_TRASHED there, so we run our own:
 * copy each file into a hidden app-private trash folder, record where it came from, then remove
 * the original from MediaStore. Restore re-publishes it; purge just deletes the trashed copy.
 *
 * Quarantined from the 11+ path on purpose. NOTE: needs testing on real API 26–29 devices;
 * API 29 (scoped storage) is the awkward one — restore re-inserts via MediaStore there.
 * The trash folder lives in app-external storage (auto-hidden from galleries, cleared on uninstall).
 */
class AppTrashManager(private val app: Context) : TrashManager {

    private val resolver get() = app.contentResolver
    private val trashDir: File by lazy {
        File(app.getExternalFilesDir(null), "trash").also { it.mkdirs() }
    }
    private val indexFile: File get() = File(trashDir, "index.json")

    override fun trash(items: List<MediaItem>): TrashResult {
        val index = loadIndex()
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (item in items) {
            try {
                val src = Uri.parse(item.uri)
                val trashName = "${System.currentTimeMillis()}_${item.id}_${sanitize(item.displayName)}"
                val dst = File(trashDir, trashName)
                resolver.openInputStream(src)?.use { input -> dst.outputStream().use { input.copyTo(it) } }
                    ?: continue
                index.put(trashName, JSONObject().apply {
                    put("displayName", item.displayName)
                    put("relativePath", item.relativePath)
                    put("mime", mimeOf(src) ?: defaultMime(item.type))
                    put("type", item.type.name)
                    put("size", item.size)
                    put("date", item.dateTaken)
                    put("trashedAt", System.currentTimeMillis())   // for 30-day auto-purge
                })
                resolver.delete(src, null, null)   // remove original from the gallery
                count++; bytes += item.size; ok.add(dst.absolutePath to item.size)
            } catch (e: Exception) {
                DebugLog.e("trash", "app-trash failed: id=${item.id}", e)
            }
        }
        saveIndex(index)
        return TrashResult(count, bytes, ok)
    }

    override fun restore(uris: List<String>): TrashResult {
        val index = loadIndex()
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (path in uris) {
            val file = File(path)
            val name = file.name
            val meta = index.optJSONObject(name) ?: continue
            try {
                if (republish(file, meta)) {
                    file.delete()
                    index.remove(name)
                    val sz = meta.optLong("size")
                    count++; bytes += sz; ok.add(path to sz)
                }
            } catch (e: Exception) {
                DebugLog.e("trash", "app-restore failed: $name", e)
            }
        }
        saveIndex(index)
        return TrashResult(count, bytes, ok)
    }

    override fun stillTrashed(uris: List<String>): List<String> =
        uris.filter { File(it).exists() }

    /** Mirror the OS 30-day recycle-bin retention: drop trashed files older than [maxAgeDays]. */
    override fun purgeExpired() {
        val cutoff = System.currentTimeMillis() - MAX_AGE_DAYS * 24L * 60L * 60L * 1000L
        val index = loadIndex()
        val expired = index.keys().asSequence().filter { name ->
            val ts = index.optJSONObject(name)?.optLong("trashedAt") ?: 0L
            ts in 1 until cutoff
        }.toList()
        if (expired.isEmpty()) return
        for (name in expired) {
            try { File(trashDir, name).delete() } catch (_: Exception) {}
            index.remove(name)
        }
        saveIndex(index)
        DebugLog.i("trash", "app-trash auto-purged ${expired.size} expired item(s)")
    }

    override fun listTrashed(): List<MediaItem> {
        val index = loadIndex()
        val result = ArrayList<MediaItem>()
        for (name in index.keys()) {
            val meta = index.optJSONObject(name) ?: continue
            val file = File(trashDir, name)
            if (!file.exists()) continue
            val type = runCatching { MediaType.valueOf(meta.optString("type")) }.getOrNull() ?: continue
            result.add(
                MediaItem(
                    meta.optLong("size"),                 // synthetic id (size — unused for app trash)
                    file.absolutePath,                    // uri = trash file path (Glide loads it)
                    "external",
                    meta.optLong("date"),
                    meta.optString("displayName"),
                    meta.optLong("size"),
                    type,
                    0L,
                    meta.optString("relativePath")
                )
            )
        }
        return result.sortedByDescending { it.dateTaken }
    }

    override fun purge(uris: List<String>): TrashResult {
        val index = loadIndex()
        var count = 0; var bytes = 0L; val ok = ArrayList<Pair<String, Long>>()
        for (path in uris) {
            val file = File(path)
            val name = file.name
            val meta = index.optJSONObject(name)
            val size = meta?.optLong("size") ?: file.length()
            if (file.delete()) {
                index.remove(name)
                count++; bytes += size; ok.add(path to size)
            }
        }
        saveIndex(index)
        return TrashResult(count, bytes, ok)
    }

    // ── Re-publish a trashed file back into MediaStore ───────────────────────────

    private fun republish(file: File, meta: JSONObject): Boolean {
        val type = runCatching { MediaType.valueOf(meta.optString("type")) }.getOrNull() ?: return false
        val name = meta.optString("displayName").ifBlank { file.name }
        val mime = meta.optString("mime").ifBlank { defaultMime(type) }
        val relPath = meta.optString("relativePath")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                if (relPath.isNotBlank()) put(MediaStore.MediaColumns.RELATIVE_PATH, relPath.trimEnd('/'))
            }
            val uri = resolver.insert(collectionFor(type), values) ?: return false
            resolver.openOutputStream(uri)?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: return false
            return true
        } else {
            // API 26–28: write directly to the original public folder, then scan it in.
            @Suppress("DEPRECATION")
            val baseDir = Environment.getExternalStorageDirectory()
            val targetDir = if (relPath.isNotBlank()) File(baseDir, relPath) else File(baseDir, Environment.DIRECTORY_PICTURES)
            targetDir.mkdirs()
            val target = File(targetDir, name)
            file.inputStream().use { input -> target.outputStream().use { input.copyTo(it) } }
            MediaScannerConnection.scanFile(app, arrayOf(target.absolutePath), arrayOf(mime), null)
            return true
        }
    }

    private fun collectionFor(type: MediaType): Uri = when (type) {
        MediaType.IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        MediaType.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        MediaType.AUDIO -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        MediaType.PDF   -> MediaStore.Files.getContentUri("external")
    }

    private fun defaultMime(type: MediaType): String = when (type) {
        MediaType.IMAGE -> "image/jpeg"
        MediaType.VIDEO -> "video/mp4"
        MediaType.AUDIO -> "audio/mpeg"
        MediaType.PDF   -> "application/pdf"
    }

    private fun mimeOf(uri: Uri): String? = try { resolver.getType(uri) } catch (e: Exception) { null }

    private fun sanitize(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")

    // ── Index persistence ────────────────────────────────────────────────────────

    private fun loadIndex(): JSONObject = try {
        if (indexFile.exists()) JSONObject(indexFile.readText()) else JSONObject()
    } catch (e: Exception) { JSONObject() }

    private fun saveIndex(index: JSONObject) {
        try { indexFile.writeText(index.toString()) }
        catch (e: Exception) { DebugLog.e("trash", "save trash index failed", e) }
    }

    private companion object { const val MAX_AGE_DAYS = 30 }
}
