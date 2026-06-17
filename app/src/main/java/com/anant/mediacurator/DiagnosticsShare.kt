package com.anant.mediacurator

import android.content.Intent
import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * Builds and shares the diagnostics report. App-level (reads shared singletons), so it can be
 * invoked from any screen — now hosted in Settings rather than only the gallery.
 */
object DiagnosticsShare {

    fun present(activity: AppCompatActivity) {
        AlertDialog.Builder(activity)
            .setTitle("Share diagnostics?")
            .setMessage(
                "The report contains your device model, app settings, and a log of recent " +
                "app activity (indexing results, errors). No photos or file contents are " +
                "included. You can review the full text before sending."
            )
            .setPositiveButton("Continue") { _, _ -> doShare(activity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun doShare(activity: AppCompatActivity) {
        val prefs = PreferencesManager(activity)
        val stats = DeletionStatsStore.getInstance(activity)
        val state = mutableListOf<String>()
        state += "All-files access : ${if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) android.os.Environment.isExternalStorageManager() else "n/a"}"
        state += "PDF search       : ${prefs.isPdfContentSearchEnabled()}"
        state += "Dup detection    : ${prefs.isPhotoDuplicateDetectionEnabled()}"
        state += "Hidden months    : ${prefs.getDoneMonths().size}"
        state += "Hashes cached    : ${PhotoHashStore.getInstance(activity).countEntries()}"
        state += "Media items      : ${MediaCache.peekSize().let { if (it < 0) "not loaded" else it.toString() }}"
        state += "Cleaned up       : ${stats.deletedCount} items / ${stats.deletedBytes} bytes"

        val report = DebugLog.buildDiagnosticsReport(activity, state)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Media Curator diagnostics")
            putExtra(Intent.EXTRA_TEXT, report)
        }
        activity.startActivity(Intent.createChooser(intent, "Share diagnostics via"))
    }
}
