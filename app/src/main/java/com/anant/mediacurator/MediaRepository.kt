package com.anant.mediacurator

import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.database.Cursor
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.coroutines.resume

class MediaRepository(private val context: Context) {
    private val prefs = PreferencesManager(context)

    init {
        // PDFBox needs its font/resource loader initialised once per process.
        // Safe to call multiple times — it no-ops after the first call.
        PDFBoxResourceLoader.init(context.applicationContext)
    }
    
    private val labelFormat = SimpleDateFormat("MMMM yyyy", Locale.getDefault())
    private val filenameDateRegex = Regex("(\\d{4})[_-]?(\\d{2})[_-]?(\\d{2})")
    private val yearOnlyRegex = Regex("(19|20)\\d{2}")

    /**
     * Fetches all media items (Images, Videos, and PDFs).
     */
    fun fetchAllMedia(): List<MediaItem> {
        val mediaList = mutableListOf<MediaItem>()
        
        // Use "external" volume for broad search.
        val volume = "external"

        // 1. Fetch Images
        fetchFromCollection(
            MediaStore.Images.Media.getContentUri(volume),
            MediaType.IMAGE,
            mediaList
        )

        // 2. Fetch Videos
        fetchFromCollection(
            MediaStore.Video.Media.getContentUri(volume),
            MediaType.VIDEO,
            mediaList
        )

        // 3. Fetch PDFs (from Files and Downloads)
        fetchPdfs(volume, mediaList)

        // 4. Fetch Audio
        fetchFromCollection(
            MediaStore.Audio.Media.getContentUri(volume),
            MediaType.AUDIO,
            mediaList
        )

        // Deduplicate based on display name and size to handle files indexed in multiple collections.
        // This is more stable than URI which depends on the collection view (Files vs Images).
        val finalResult = mediaList.distinctBy { "${it.displayName}_${it.size}" }

        Log.d("MediaRepository", "Fetched total: ${finalResult.size} items. " +
                "Images: ${finalResult.count { it.type == MediaType.IMAGE }}, " +
                "Videos: ${finalResult.count { it.type == MediaType.VIDEO }}, " +
                "PDFs: ${finalResult.count { it.type == MediaType.PDF }}, " +
                "Audio: ${finalResult.count { it.type == MediaType.AUDIO }}")
        
        return finalResult
    }

    private fun fetchFromCollection(
        collectionUri: Uri,
        type: MediaType,
        mediaList: MutableList<MediaItem>
    ) {
        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE,
            "datetaken"
        )
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.DURATION)
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }

        try {
            context.contentResolver.query(collectionUri, projection.toTypedArray(), "${MediaStore.MediaColumns.SIZE} > 0", null, null)?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                val daCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                val dmCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                val dtCol = cursor.getColumnIndex("datetaken")
                val durCol     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1

                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val name = cursor.getString(nameCol) ?: ""
                    val size = cursor.getLong(sizeCol)
                    val dt = if (dtCol != -1) cursor.getLong(dtCol) else 0L
                    val da = cursor.getLong(daCol) * 1000
                    val dm = cursor.getLong(dmCol) * 1000
                    val dur     = if (durCol     != -1) cursor.getLong(durCol)    else 0L
                    val relPath = if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else ""

                    // Audio uses a dedicated resolver that prefers DATE_MODIFIED (filesystem
                    // mtime, preserved during rename) over DATE_ADDED (MediaStore index time,
                    // which resets to "now" every time the file is re-scanned).
                    val bestDate = if (type == MediaType.AUDIO)
                        resolveAudioDate(name, dt, dm, da)
                    else
                        resolveBestDate(name, dt, dm, da)
                    val uri = ContentUris.withAppendedId(collectionUri, id).toString()

                    mediaList.add(MediaItem(id, uri, "external", bestDate, name, size, type, dur, relPath))
                }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "Error fetching $type", e)
        }
    }

    private fun fetchPdfs(volume: String, mediaList: MutableList<MediaItem>) {
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            MediaStore.MediaColumns.MIME_TYPE
        )

        // Try both MediaStore.Downloads and MediaStore.Files
        val filesCollectionUri = MediaStore.Files.getContentUri(volume)
        val collections = mutableListOf<Uri>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collections.add(MediaStore.Downloads.getContentUri(volume))
        }
        collections.add(filesCollectionUri)

        val selection = "${MediaStore.MediaColumns.SIZE} > 0 AND (" +
                "${MediaStore.MediaColumns.MIME_TYPE} LIKE '%pdf%' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.pdf' OR " +
                "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE '%.PDF')"

        for (collectionUri in collections) {
            // We need to verify file accessibility only for the Files collection.
            // PDFs deleted via their Downloads URI leave behind orphaned Files entries
            // that persist in the MediaStore database across app reinstalls.
            val isFilesCollection = (collectionUri == filesCollectionUri)
            try {
                context.contentResolver.query(collectionUri, projection, selection, null, null)?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val daCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dmCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)

                    while (cursor.moveToNext()) {
                        val name = cursor.getString(nameCol) ?: ""
                        val mime = cursor.getString(mimeCol) ?: ""

                        if (!name.endsWith(".pdf", true) && !mime.contains("pdf", true)) {
                            continue
                        }

                        val id = cursor.getLong(idCol)
                        val size = cursor.getLong(sizeCol)
                        val da = cursor.getLong(daCol) * 1000
                        val dm = cursor.getLong(dmCol) * 1000

                        val fileUri = ContentUris.withAppendedId(collectionUri, id)

                        // For MediaStore.Files entries only: verify the file is actually
                        // accessible.  Stale orphan rows (left after deleting via a
                        // Downloads URI) will throw here and we silently skip them.
                        // This check is intentionally skipped for the Downloads collection
                        // because Downloads entries are always kept in sync with the file.
                        if (isFilesCollection) {
                            try {
                                context.contentResolver.openFileDescriptor(fileUri, "r")?.close()
                            } catch (_: Exception) {
                                continue  // file no longer exists — skip stale entry
                            }
                        }

                        val bestDate = resolvePdfDate(name, da, dm)
                        mediaList.add(MediaItem(id, fileUri.toString(), "external", bestDate, name, size, MediaType.PDF))
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "Error fetching PDFs from $collectionUri", e)
            }
        }
    }

    fun processAndGroupMedia(
        rawMedia: List<MediaItem>,
        sortMode: SortMode,
        doneMonthKeys: Set<String>
    ): Pair<List<MonthGroup>, List<MonthGroup>> {
        // Sort items before grouping
        val sortedList = when (sortMode) {
            SortMode.DATE_OLDEST       -> rawMedia.sortedWith(compareBy({ it.dateTaken }, { it.id }))
            SortMode.DATE_NEWEST       -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
            SortMode.SIZE_ABSOLUTE     -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken })
            SortMode.SIZE_WITHIN_MONTH -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.size }.thenByDescending { it.dateTaken })
            // Items within each month are shown newest-first; months are re-ordered after grouping.
            SortMode.COUNT_PER_MONTH   -> rawMedia.sortedWith(compareByDescending<MediaItem> { it.dateTaken }.thenByDescending { it.id })
        }

        val visibleGroups = mutableListOf<MonthGroup>()
        val doneGroups = mutableListOf<MonthGroup>()
        val groupMap = mutableMapOf<String, MonthGroup>()
        val calendar = Calendar.getInstance()

        for (item in sortedList) {
            calendar.timeInMillis = item.dateTaken
            val year = calendar.get(Calendar.YEAR)
            val month = calendar.get(Calendar.MONTH) + 1
            val key = prefs.monthKey(year, month)

            var group = groupMap[key]
            if (group == null) {
                val label = labelFormat.format(calendar.time)
                group = MonthGroup(year, month, key, label, mutableListOf())
                groupMap[key] = group
                if (doneMonthKeys.contains(key)) doneGroups.add(group) else visibleGroups.add(group)
            }
            group.items.add(item)
        }

        // Post-grouping month-level re-sorts:
        // SIZE_ABSOLUTE     — month order already follows the largest file encountered; no re-sort.
        // SIZE_WITHIN_MONTH — items are size-sorted; restore newest-month-first chronological order.
        // COUNT_PER_MONTH   — sort months so the one with the most items appears first.
        when (sortMode) {
            SortMode.SIZE_WITHIN_MONTH -> visibleGroups.sortByDescending { g -> g.year * 100 + g.month }
            SortMode.COUNT_PER_MONTH   -> visibleGroups.sortByDescending { it.items.size }
            else -> { /* order already correct */ }
        }

        return Pair(visibleGroups, doneGroups)
    }

    /**
     * Date resolver for audio files.
     *
     * Audio files are different from images/PDFs in one key way: every time MediaStore
     * re-scans an audio file (e.g. after a rename), it resets DATE_ADDED to the current
     * time.  DATE_MODIFIED (filesystem mtime) is more stable — and when we rename via
     * File.renameTo() we explicitly call setLastModified() to preserve the original mtime.
     *
     * Priority:
     *   1. Exact date in filename (e.g. AUD-2025-03-15)
     *   2. Year hint in filename
     *   3. DATE_TAKEN (actual recording time, if set — most accurate)
     *   4. DATE_MODIFIED (filesystem mtime — preserved during rename, stable across rescans)
     *   5. DATE_ADDED (last resort — unreliable after re-index)
     */
    private fun resolveAudioDate(name: String, dt: Long, dm: Long, da: Long): Long {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        fun isReasonable(ts: Long): Boolean {
            if (ts <= 1_000_000L || ts > now) return false
            val year = Calendar.getInstance().also { it.timeInMillis = ts }.get(Calendar.YEAR)
            return year in 1980..currentYear
        }

        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1_000_000L) return filenameDate

        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null && dt <= 0) {
            try {
                val year = yearMatch.value.toInt()
                if (year in 1980..currentYear) {
                    val cal = Calendar.getInstance()
                    cal.set(year, 0, 1, 12, 0, 0); cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {}
        }

        if (isReasonable(dt)) return dt   // DATE_TAKEN — recording date
        if (isReasonable(dm)) return dm   // DATE_MODIFIED — filesystem mtime (preserved on rename)
        if (isReasonable(da)) return da   // DATE_ADDED — last resort

        return now
    }

    private fun resolveBestDate(name: String, dt: Long, dm: Long, da: Long): Long {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        // A timestamp is only "reasonable" if it's in the past and from a plausible year (1980–now).
        fun isReasonable(ts: Long): Boolean {
            if (ts <= 1_000_000L || ts > now) return false
            val year = Calendar.getInstance().also { it.timeInMillis = ts }.get(Calendar.YEAR)
            return year in 1980..currentYear
        }

        // 1. Prefer exact YYYY-MM-DD extracted from filename (already has year range guard).
        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1_000_000L) return filenameDate

        // 2. Year-only hint from filename, but only for plausible past years.
        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null && dt <= 0) {
            try {
                val year = yearMatch.value.toInt()
                if (year in 1980..currentYear) {
                    val cal = Calendar.getInstance()
                    cal.set(year, 0, 1, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (e: Exception) {}
        }

        // 3. Use metadata timestamps — but only if they are reasonable (no future/garbage dates).
        if (isReasonable(dt)) return dt
        if (isReasonable(da)) return da
        if (isReasonable(dm)) return dm

        // 4. All metadata is garbage or in the future — clamp to now so the file
        //    doesn't pollute far-future month headers like "January 2099".
        val best = when {
            dt > 1_000_000L -> dt
            da > 1_000_000L -> da
            dm > 1_000_000L -> dm
            else -> now
        }
        return best.coerceAtMost(now)
    }

    /**
     * Date resolver specifically for PDFs.
     *
     * PDFs have no EXIF. Their DATE_MODIFIED in MediaStore is the file-system modification
     * time, which is often copied verbatim from the PDF's internal creation/modification
     * metadata — meaning a PDF of a 1987 paper downloaded yesterday will show DATE_MODIFIED
     * of January 1987. DATE_ADDED (when MediaStore indexed the file) is therefore the most
     * trustworthy anchor for "when did this PDF arrive on the device."
     *
     * Strategy (in priority order):
     *   1. Exact date embedded in the filename (e.g. report_2024-03-15.pdf)
     *   2. Year hint in the filename, if plausible (1993–now; PDF format was born in 1993)
     *   3. DATE_ADDED — when MediaStore first saw the file (reliable for downloads)
     *   4. DATE_MODIFIED — accepted only if it's ≥ year 2000 (pre-2000 values almost always
     *      reflect embedded document metadata, not when the file arrived on the phone)
     *   5. Fallback: current time, so the file lands in "this month" rather than creating
     *      phantom headers like "January 1987"
     */
    private fun resolvePdfDate(name: String, dateAdded: Long, dateModified: Long): Long {
        val now = System.currentTimeMillis()
        val currentYear = Calendar.getInstance().get(Calendar.YEAR)

        fun yearOf(ts: Long): Int =
            Calendar.getInstance().also { it.timeInMillis = ts }.get(Calendar.YEAR)

        fun isUsable(ts: Long, minYear: Int): Boolean {
            if (ts <= 1_000_000L || ts > now) return false
            return yearOf(ts) in minYear..currentYear
        }

        // 1. Exact date from filename
        val filenameDate = extractDateFromFilename(name)
        if (filenameDate > 1_000_000L) return filenameDate

        // 2. Year-only hint from filename (PDF era: 1993 onward)
        val yearMatch = yearOnlyRegex.find(name)
        if (yearMatch != null) {
            try {
                val year = yearMatch.value.toInt()
                if (year in 1993..currentYear) {
                    val cal = Calendar.getInstance()
                    cal.set(year, 0, 1, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (_: Exception) {}
        }

        // 3. DATE_ADDED — best proxy for "arrived on device"; accept back to 1993
        if (isUsable(dateAdded, 1993)) return dateAdded

        // 4. DATE_MODIFIED — only trust it from year 2000 onward; older values almost
        //    always come from the PDF's own embedded metadata, not the download date
        if (isUsable(dateModified, 2000)) return dateModified

        // 5. Cannot determine a reliable date → use now
        return now
    }

    private fun extractDateFromFilename(name: String): Long {
        val match = filenameDateRegex.find(name)
        if (match != null) {
            try {
                val year = match.groupValues[1].toInt()
                val month = match.groupValues[2].toInt() - 1
                val day = match.groupValues[3].toInt()
                val maxYear = Calendar.getInstance().get(Calendar.YEAR)
                if (year in 1980..maxYear && month in 0..11 && day in 1..31) {
                    val cal = Calendar.getInstance()
                    cal.set(year, month, day, 12, 0, 0)
                    cal.set(Calendar.MILLISECOND, 0)
                    return cal.timeInMillis
                }
            } catch (e: Exception) {}
        }
        return 0L
    }

    /**
     * Attempt to rename [item] to [newDisplayName] via ContentResolver.
     * Returns true if the MediaStore row was updated, false on any failure.
     * On Android 11+ without MANAGE_EXTERNAL_STORAGE this will throw a SecurityException
     * for files not owned by this app — the caller should catch that case and use
     * [createRenameWriteRequest] to obtain user permission first.
     */
    fun renameMedia(item: MediaItem, newDisplayName: String): Boolean {
        val uri = android.net.Uri.parse(item.uri)

        // ── Step 1: read the physical path BEFORE touching MediaStore ────────────
        // On many Samsung / Android devices, contentResolver.update(DISPLAY_NAME)
        // deletes the old MediaStore row entirely.  If we query DATA afterwards we
        // get an empty cursor, so we read it now while the row still exists.
        @Suppress("DEPRECATION")
        val oldPath: String? = context.contentResolver.query(
            uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

        Log.d("MediaRepository", "renameMedia: oldPath=$oldPath newName=$newDisplayName")

        // ── Step 2: attempt filesystem rename first ───────────────────────────────
        // With MANAGE_EXTERNAL_STORAGE we can rename directly on the filesystem,
        // which is more reliable than relying on MediaStore to handle it atomically.
        if (!oldPath.isNullOrEmpty()) {
            val oldFile = File(oldPath)
            val parent  = oldFile.parentFile
            if (parent != null && oldFile.exists()) {
                val originalMtime = oldFile.lastModified()   // snapshot before rename
                val newFile = File(parent, newDisplayName)
                if (oldFile.renameTo(newFile)) {
                    // Restore the original modification time so MediaStore's DATE_MODIFIED
                    // reflects the file's real age rather than the rename timestamp.
                    // resolveAudioDate() prefers DATE_MODIFIED over DATE_ADDED, so this
                    // keeps the file in its original year/month after re-indexing.
                    newFile.setLastModified(originalMtime)
                    Log.d("MediaRepository", "Filesystem rename OK → ${newFile.absolutePath}")
                    // Sync the display name in MediaStore (best-effort — the row may have
                    // already been deleted, or this may fail for permission reasons)
                    try {
                        context.contentResolver.update(uri, android.content.ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
                        }, null, null)
                    } catch (_: Exception) { /* ignore — physical file is already renamed */ }
                    // Re-index: scanning the old path removes the stale entry; the new path
                    // creates a fresh entry so the file reappears in MediaStore queries.
                    MediaScannerConnection.scanFile(
                        context,
                        arrayOf(oldFile.absolutePath, newFile.absolutePath),
                        null
                    ) { path, scannedUri ->
                        Log.d("MediaRepository", "Scan done: $path → $scannedUri")
                    }
                    return true
                }
            }
        }

        // ── Step 3: fallback — ContentResolver-only rename ────────────────────────
        // Used when we don't have a physical path (e.g. virtual/cloud entries) or
        // File.renameTo() failed.
        return try {
            val rows = context.contentResolver.update(uri, android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, newDisplayName)
            }, null, null)
            if (rows > 0) {
                // Scan the directory from what we know: old file path (if any) and the
                // computed new path from RELATIVE_PATH.
                triggerFallbackScan(oldPath, item, newDisplayName)
            }
            rows > 0
        } catch (e: Exception) {
            Log.e("MediaRepository", "Rename failed (fallback): ${item.displayName} → $newDisplayName", e)
            false
        }
    }

    /**
     * Last-resort scan when the filesystem rename path was not available.
     * Probes both the known old path (if any) and the path derived from RELATIVE_PATH.
     */
    @Suppress("DEPRECATION")
    private fun triggerFallbackScan(oldPath: String?, item: MediaItem, newDisplayName: String) {
        try {
            val pathsToScan = mutableListOf<String>()

            // Old path (file is now renamed on disk but stale entry may linger)
            if (!oldPath.isNullOrEmpty()) {
                pathsToScan.add(oldPath)
                // Compute new path from the old path's directory
                val newPath = File(oldPath).parentFile?.let { File(it, newDisplayName).absolutePath }
                if (newPath != null && !pathsToScan.contains(newPath)) pathsToScan.add(newPath)
            }

            // Independently computed path via RELATIVE_PATH (Android 10+)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && item.relativePath.isNotEmpty()) {
                val root = Environment.getExternalStorageDirectory().absolutePath
                val computed = File(root, item.relativePath + newDisplayName).absolutePath
                if (!pathsToScan.contains(computed)) pathsToScan.add(computed)
            }

            if (pathsToScan.isNotEmpty()) {
                Log.d("MediaRepository", "Fallback scan: $pathsToScan")
                MediaScannerConnection.scanFile(context, pathsToScan.toTypedArray(), null) { path, uri ->
                    Log.d("MediaRepository", "Fallback scan done: $path → $uri")
                }
            }
        } catch (e: Exception) {
            Log.e("MediaRepository", "triggerFallbackScan failed", e)
        }
    }

    /**
     * Returns an [IntentSender] that, when launched, asks the user to grant
     * write access to [item] so it can be renamed. Only available on Android 11+.
     * After the user approves, call [renameMedia] to perform the actual rename.
     */
    fun createRenameWriteRequest(item: MediaItem): IntentSender? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                MediaStore.createWriteRequest(
                    context.contentResolver,
                    listOf(android.net.Uri.parse(item.uri))
                ).intentSender
            } catch (e: Exception) {
                Log.e("MediaRepository", "createWriteRequest failed", e)
                null
            }
        } else null
    }

    fun createDeleteRequest(items: List<MediaItem>): IntentSender? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val uris = items.map { Uri.parse(it.uri) }
                return MediaStore.createDeleteRequest(context.contentResolver, uris).intentSender
            } catch (e: Exception) {
                Log.e("MediaRepository", "Delete request failed", e)
            }
        }
        return null
    }

    // ── Photo duplicate detection ─────────────────────────────────────────────

    /**
     * Compute the MD5 hash of a photo's raw file bytes by streaming through ContentResolver.
     * Peak memory = one 128 KB I/O buffer per concurrent worker — no image decode, no Bitmap.
     * 128 KB (vs 8 KB) cuts syscall count ~16x and lets flash storage stream at full bandwidth.
     * Returns an empty string on any error (the photo will simply not be indexed).
     */
    fun computeMd5(item: MediaItem): String {
        return try {
            val uri = android.net.Uri.parse(item.uri)
            val digest = java.security.MessageDigest.getInstance("MD5")
            val buffer = ByteArray(131_072)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                var read = stream.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = stream.read(buffer)
                }
            }
            digest.digest().joinToString("") { "%02x".format(it) }
        } catch (e: Throwable) {
            Log.e("MediaRepository", "computeMd5 failed for ${item.displayName}", e)
            ""
        }
    }

    /**
     * Fetch full [MediaItem] details for a set of image IDs from MediaStore.
     * Used by [DuplicatesViewModel] to hydrate duplicate groups with display data.
     * IDs that no longer exist in MediaStore (deleted files) are simply absent from the result.
     */
    fun fetchImagesByIds(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<MediaItem>()
        val collectionUri = MediaStore.Images.Media.getContentUri("external")

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            "datetaken"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }

        // SQLite IN clause limit is ~999 args — chunk to be safe
        for (chunk in ids.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selectionArgs = chunk.map { it.toString() }.toTypedArray()
            try {
                context.contentResolver.query(
                    collectionUri,
                    projection.toTypedArray(),
                    "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val daCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dmCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dtCol      = cursor.getColumnIndex("datetaken")
                    val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                        cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1

                    while (cursor.moveToNext()) {
                        val id      = cursor.getLong(idCol)
                        val name    = cursor.getString(nameCol) ?: ""
                        val size    = cursor.getLong(sizeCol)
                        val dt      = if (dtCol      != -1) cursor.getLong(dtCol)    else 0L
                        val da      = cursor.getLong(daCol) * 1000
                        val dm      = cursor.getLong(dmCol) * 1000
                        val relPath = if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else ""
                        val uri     = ContentUris.withAppendedId(collectionUri, id).toString()
                        result.add(MediaItem(id, uri, "external", resolveBestDate(name, dt, dm, da), name, size, MediaType.IMAGE, 0L, relPath))
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "fetchImagesByIds failed for chunk", e)
            }
        }
        return result
    }

    /**
     * Fetch full [MediaItem] details for a set of video IDs from MediaStore.
     * Mirror of [fetchImagesByIds] for the Video collection.
     */
    fun fetchVideosByIds(ids: List<Long>): List<MediaItem> {
        if (ids.isEmpty()) return emptyList()
        val result = mutableListOf<MediaItem>()
        val collectionUri = MediaStore.Video.Media.getContentUri("external")

        val projection = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.SIZE,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED,
            "datetaken"
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            projection.add(MediaStore.MediaColumns.DURATION)
            projection.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }

        for (chunk in ids.chunked(500)) {
            val placeholders = chunk.joinToString(",") { "?" }
            val selectionArgs = chunk.map { it.toString() }.toTypedArray()
            try {
                context.contentResolver.query(
                    collectionUri,
                    projection.toTypedArray(),
                    "${MediaStore.MediaColumns._ID} IN ($placeholders)",
                    selectionArgs,
                    null
                )?.use { cursor ->
                    val idCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val sizeCol    = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)
                    val daCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dmCol      = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val dtCol      = cursor.getColumnIndex("datetaken")
                    val durCol     = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.DURATION) else -1
                    val relPathCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1

                    while (cursor.moveToNext()) {
                        val id      = cursor.getLong(idCol)
                        val name    = cursor.getString(nameCol) ?: ""
                        val size    = cursor.getLong(sizeCol)
                        val dt      = if (dtCol      != -1) cursor.getLong(dtCol)    else 0L
                        val da      = cursor.getLong(daCol) * 1000
                        val dm      = cursor.getLong(dmCol) * 1000
                        val dur     = if (durCol     != -1) cursor.getLong(durCol)   else 0L
                        val relPath = if (relPathCol != -1) cursor.getString(relPathCol) ?: "" else ""
                        val uri     = ContentUris.withAppendedId(collectionUri, id).toString()
                        result.add(MediaItem(id, uri, "external", resolveBestDate(name, dt, dm, da), name, size, MediaType.VIDEO, dur, relPath))
                    }
                }
            } catch (e: Exception) {
                Log.e("MediaRepository", "fetchVideosByIds failed for chunk", e)
            }
        }
        return result
    }

    // ── Image labeling ────────────────────────────────────────────────────────

    /**
     * Lazy ML Kit labeler — created once and reused for the entire ViewModel lifetime.
     * Caller is responsible for calling [closeLabeler] in ViewModel.onCleared().
     *
     * Uses the bundled on-device model (no internet, no Play Services dependency).
     * Default confidence threshold is 0.5; we filter client-side to ≥ 0.65 to keep
     * only high-quality labels.
     */
    private val imageLabeler by lazy {
        ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS)
    }

    /**
     * Run on-device image labeling on a single IMAGE-type [MediaItem].
     *
     * Returns up to [maxLabels] labels with confidence ≥ [minConfidence], sorted by
     * confidence descending.  Returns an empty list for non-image items or on any error
     * (permission issue, corrupt file, etc.) — callers can safely ignore failures.
     *
     * Designed to be called from a background coroutine (IO dispatcher).
     */
    /**
     * Returns a label→confidence map for [item], e.g. {"Dog": 0.89, "Beach": 0.76}.
     * Only labels with confidence ≥ [minConfidence] are included.
     * Returns an empty map for non-image types or on any error.
     */
    suspend fun labelImage(
        item: MediaItem,
        minConfidence: Float = 0.50f,
        maxLabels: Int = 8
    ): Map<String, Float> {
        if (item.type != MediaType.IMAGE) return emptyMap()
        return try {
            val uri   = Uri.parse(item.uri)
            val image = InputImage.fromFilePath(context, uri)
            suspendCancellableCoroutine { cont ->
                imageLabeler.process(image)
                    .addOnSuccessListener { results ->
                        val labels = results
                            .filter { it.confidence >= minConfidence }
                            .sortedByDescending { it.confidence }
                            .take(maxLabels)
                            .associate { it.text to it.confidence }
                        cont.resume(labels)
                    }
                    .addOnFailureListener { e ->
                        Log.w("MediaRepository", "labelImage failed: ${item.displayName}", e)
                        cont.resume(emptyMap())
                    }
            }
        } catch (e: Exception) {
            Log.w("MediaRepository", "labelImage exception: ${item.displayName}", e)
            emptyMap()
        }
    }

    /** Release the ML Kit labeler. Call from ViewModel.onCleared(). */
    fun closeLabeler() {
        try { imageLabeler.close() } catch (_: Exception) {}
    }

    // ── PDF word extraction for BM25 index ───────────────────────────────────

    /**
     * Extract a word-frequency map from a PDF [item] using Apache PDFBox.
     *
     * Only the first [maxPages] pages are read (default: 5).  The user's own
     * PDF collection has a median of 3 pages, so this covers the majority of
     * documents completely while keeping extraction fast for long reports.
     *
     * Text is tokenised, stop words removed, and term frequencies counted.
     * Returns an empty map for scanned / encrypted PDFs or on any error — the
     * caller records this as a no-text sentinel so we never re-attempt extraction.
     */
    fun extractPdfWords(item: MediaItem, maxPages: Int = 5): Map<String, Int> {
        if (item.type != MediaType.PDF) return emptyMap()
        return try {
            val uri = Uri.parse(item.uri)
            context.contentResolver.openInputStream(uri)?.use { stream ->
                PDDocument.load(stream).use { doc ->
                    if (doc.isEncrypted) return emptyMap()
                    val stripper = PDFTextStripper().apply {
                        startPage = 1
                        endPage   = minOf(doc.numberOfPages, maxPages)
                    }
                    tokenizeAndCount(stripper.getText(doc))
                }
            } ?: emptyMap()
        } catch (e: Throwable) {
            // Catch Throwable (not just Exception) so OutOfMemoryError is handled
            // gracefully — the PDF is recorded as a no-text sentinel and indexing
            // continues rather than crashing the whole background job.
            Log.w("MediaRepository", "extractPdfWords failed: ${item.displayName} [${e.javaClass.simpleName}]", e)
            emptyMap()
        }
    }

    /**
     * Tokenise [text] into lowercase alphanumeric tokens, remove stop words,
     * and count term frequencies.
     *
     * Filters:
     *  - Tokens shorter than 2 characters (noise)
     *  - Tokens longer than 30 characters (URL fragments, base64, etc.)
     *  - Common English stop words
     */
    private fun tokenizeAndCount(text: String): Map<String, Int> {
        if (text.isBlank()) return emptyMap()
        val counts = HashMap<String, Int>()
        // Replace all non-alphanumeric runs with a space, then split
        val tokens = text.lowercase().replace(Regex("[^a-z0-9]+"), " ").split(' ')
        for (token in tokens) {
            if (token.length < 2 || token.length > 30) continue
            if (token in PDF_STOP_WORDS) continue
            counts[token] = (counts[token] ?: 0) + 1
        }
        return counts
    }

    companion object {
        /**
         * Common English stop words stripped from PDF text before indexing.
         * Kept intentionally broad to reduce index size without losing recall —
         * users searching for "invoice" or "contract" don't want matches on "the".
         */
        private val PDF_STOP_WORDS = setOf(
            "a", "an", "the", "and", "or", "but", "in", "on", "at", "to", "for",
            "of", "with", "by", "from", "as", "is", "was", "are", "were", "be",
            "been", "being", "have", "has", "had", "do", "does", "did", "will",
            "would", "could", "should", "may", "might", "shall", "can", "not",
            "no", "nor", "so", "yet", "both", "either", "neither", "each",
            "more", "most", "other", "some", "such", "than", "too", "very",
            "that", "this", "these", "those", "it", "its", "they", "their",
            "them", "he", "she", "his", "her", "we", "our", "you", "your",
            "i", "my", "me", "us", "who", "what", "which", "all", "any",
            "if", "then", "else", "when", "where", "how", "about", "after",
            "before", "during", "since", "until", "while", "into", "through",
            "over", "under", "up", "down", "out", "off", "again", "also",
            "just", "only", "even", "well", "back", "there", "here", "now",
            "still", "already", "much", "many", "same", "new", "first",
            "last", "long", "own", "right", "next", "never", "always"
        )
    }
}
