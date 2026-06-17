package com.anant.mediacurator

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Reads the hidden-months auto-backup (mediacurator_hidden.json) from Downloads.
 *
 * Shared so the bootstrap can restore the hidden-months list early from Home (before the
 * gallery is ever opened) — the gallery's [GalleryViewModel.checkAndAutoRestore] uses the
 * same file. Direct-path reads need MANAGE_EXTERNAL_STORAGE (granted on Home), which is the
 * only reliable path on a reinstall (a fresh install can't read the previous install's file
 * via MediaStore). Call OFF the main thread.
 */
object HiddenMonthsBackup {
    const val FILENAME = "mediacurator_hidden.json"

    /** The hidden-month keys ("YYYY-MM") from the backup, or null if none / unreadable. */
    fun read(context: Context): Set<String>? {
        val json = readJson(context) ?: return null
        return try {
            val arr = org.json.JSONObject(json).getJSONArray("hiddenMonths")
            (0 until arr.length()).map { arr.getString(it) }.toSet().takeIf { it.isNotEmpty() }
        } catch (e: Exception) { null }
    }

    private fun readJson(context: Context): String? {
        @Suppress("DEPRECATION")
        val file = File(
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
            FILENAME
        )
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver   = context.contentResolver
                val collection = MediaStore.Downloads.getContentUri("external")
                // Pass 1: MediaStore (own-package files, same install).
                val fromMs = resolver.query(
                    collection, arrayOf(MediaStore.Downloads._ID),
                    "${MediaStore.Downloads.DISPLAY_NAME} = ?", arrayOf(FILENAME),
                    "${MediaStore.Downloads.DATE_MODIFIED} DESC"
                )?.use { c ->
                    if (!c.moveToFirst()) return@use null
                    val id = c.getLong(c.getColumnIndexOrThrow(MediaStore.Downloads._ID))
                    resolver.openInputStream(ContentUris.withAppendedId(collection, id))
                        ?.use { it.readBytes().toString(Charsets.UTF_8) }
                }
                // Pass 2: direct path (needs MANAGE_EXTERNAL_STORAGE; covers reinstall).
                fromMs ?: if (file.exists()) file.readText(Charsets.UTF_8) else null
            } else {
                if (file.exists()) file.readText(Charsets.UTF_8) else null
            }
        } catch (e: Exception) { null }
    }
}
