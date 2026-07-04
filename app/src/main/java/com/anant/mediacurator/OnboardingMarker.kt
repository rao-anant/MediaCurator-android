package com.anant.mediacurator

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * A durable "the first-run demo has been shown" marker, stored as a tiny file in Downloads so it
 * survives uninstall/reinstall (SharedPreferences does not). Mirrors [HiddenMonthsBackup]: written
 * via MediaStore on Q+, read back via MediaStore (same install) or a direct-path fallback (needs
 * MANAGE_EXTERNAL_STORAGE, the only way to read the previous install's file after a reinstall).
 *
 * Call OFF the main thread.
 */
object OnboardingMarker {
    // NOTE: extension must match the text/plain MIME below, otherwise MediaStore appends ".txt"
    // to the display name on insert (and exists() would then miss the file).
    const val FILENAME = "mediacurator_onboarded.txt"

    /** True if the marker exists (demo already shown on this device at some point). */
    fun exists(context: Context): Boolean {
        @Suppress("DEPRECATION")
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FILENAME
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver   = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri("external")
                val fromMs = resolver.query(
                    collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILENAME),
                    null
                )?.use { it.moveToFirst() } ?: false
                fromMs || file.exists()   // direct-path fallback covers reinstall
            } else {
                file.exists()
            }
        } catch (e: Exception) { false }
    }

    /** Create the marker (idempotent). Safe to call repeatedly. */
    fun write(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver   = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri("external")
                // Avoid "(1)" duplicates from a prior install we still own.
                try {
                    resolver.delete(
                        collection,
                        "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILENAME)
                    )
                } catch (_: Exception) {}
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, FILENAME)
                    put(MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
                }
                val uri = resolver.insert(collection, values) ?: return
                resolver.openOutputStream(uri)?.use { it.write("1".toByteArray(Charsets.UTF_8)) }
            } else {
                @Suppress("DEPRECATION")
                val file = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                    FILENAME
                )
                file.parentFile?.mkdirs()
                file.writeText("1", Charsets.UTF_8)
            }
        } catch (e: Exception) {
            DebugLog.e("gallery", "Onboarding marker write failed", e)
        }
    }
}
