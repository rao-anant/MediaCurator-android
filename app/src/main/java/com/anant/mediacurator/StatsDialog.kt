package com.anant.mediacurator

import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Shared media-stats dialog, callable from any screen (Home, Gallery, Duplicates, Search)
 * via the ⓘ in the toolbar. Computes the breakdown from the shared [MediaCache] so it works
 * the same everywhere, then shows counts/sizes (visible + hidden = total) plus the lifetime
 * "cleaned up" totals.
 */
object StatsDialog {

    fun present(activity: AppCompatActivity) {
        val app   = activity.applicationContext
        val repo  = MediaRepository(app)
        val prefs = PreferencesManager(app)
        activity.lifecycleScope.launch {
            val stats = withContext(Dispatchers.IO) {
                compute(repo, MediaCache.get(repo), prefs.getDoneMonths())
            }
            // In-Trash from the actual trash (exact; the prefs counter drifts on external changes).
            val trashed = withContext(Dispatchers.IO) { TrashManager.get(app).listTrashed() }
            if (!activity.isFinishing) {
                val ds = DeletionStatsStore.getInstance(app)
                show(activity, stats, ds.deletedCount, ds.deletedBytes,
                    trashed.size.toLong(), trashed.sumOf { it.size })
            }
        }
    }

    private fun compute(repo: MediaRepository, media: List<MediaItem>, done: Set<String>): MediaStats {
        val (vis, hid) = repo.processAndGroupMedia(media, SortMode.DATE_OLDEST, done)
        fun c(g: List<MonthGroup>, t: MediaType) = g.sumOf { grp -> grp.items.count { it.type == t } }
        fun b(g: List<MonthGroup>, t: MediaType) = g.sumOf { grp -> grp.items.filter { it.type == t }.sumOf { it.size } }
        val checkV = vis.sumOf { it.items.size }
        val checkH = hid.sumOf { it.items.size }
        val ok = checkV + checkH == media.size
        return MediaStats(
            c(vis, MediaType.IMAGE), c(hid, MediaType.IMAGE), media.count { it.type == MediaType.IMAGE },
            c(vis, MediaType.VIDEO), c(hid, MediaType.VIDEO), media.count { it.type == MediaType.VIDEO },
            c(vis, MediaType.PDF),   c(hid, MediaType.PDF),   media.count { it.type == MediaType.PDF },
            c(vis, MediaType.AUDIO), c(hid, MediaType.AUDIO), media.count { it.type == MediaType.AUDIO },
            b(vis, MediaType.IMAGE), b(hid, MediaType.IMAGE),
            b(vis, MediaType.VIDEO), b(hid, MediaType.VIDEO),
            b(vis, MediaType.PDF),   b(hid, MediaType.PDF),
            b(vis, MediaType.AUDIO), b(hid, MediaType.AUDIO),
            ok,
            if (ok) "✓ All counts match"
            else "⚠ visible($checkV) + hidden($checkH) = ${checkV + checkH} ≠ total(${media.size})"
        )
    }

    private fun show(
        activity: AppCompatActivity, s: MediaStats,
        deletedCount: Long, deletedBytes: Long,
        inTrashCount: Long, inTrashBytes: Long
    ) {
        fun row(label: String, vis: Int, hid: Int, tot: Int) =
            "%-8s %5d + %5d = %5d".format(label, vis, hid, tot)
        fun rowB(label: String, vb: Long, hb: Long) =
            "%-8s %s + %s = %s".format(label, fmtBytes(vb), fmtBytes(hb), fmtBytes(vb + hb))

        val vAll = s.visiblePhotos + s.visibleVideos + s.visibleAudios + s.visiblePdfs
        val hAll = s.hiddenPhotos  + s.hiddenVideos  + s.hiddenAudios  + s.hiddenPdfs
        val tAll = s.totalPhotos   + s.totalVideos   + s.totalAudios   + s.totalPdfs
        val vbAll = s.visiblePhotoBytes + s.visibleVideoBytes + s.visibleAudioBytes + s.visiblePdfBytes
        val hbAll = s.hiddenPhotoBytes  + s.hiddenVideoBytes  + s.hiddenAudioBytes  + s.hiddenPdfBytes

        val msg = buildString {
            appendLine("COUNTS  (visible + hidden = total)")
            appendLine(row("Photos", s.visiblePhotos, s.hiddenPhotos, s.totalPhotos))
            appendLine(row("Videos", s.visibleVideos, s.hiddenVideos, s.totalVideos))
            if (s.totalAudios > 0) appendLine(row("Audio", s.visibleAudios, s.hiddenAudios, s.totalAudios))
            if (s.totalPdfs > 0)   appendLine(row("PDFs",  s.visiblePdfs,   s.hiddenPdfs,   s.totalPdfs))
            appendLine(row("All", vAll, hAll, tAll))
            appendLine()
            appendLine("SIZES   (visible + hidden = total)")
            appendLine(rowB("Photos", s.visiblePhotoBytes, s.hiddenPhotoBytes))
            appendLine(rowB("Videos", s.visibleVideoBytes, s.hiddenVideoBytes))
            if (s.totalAudios > 0) appendLine(rowB("Audio", s.visibleAudioBytes, s.hiddenAudioBytes))
            if (s.totalPdfs > 0)   appendLine(rowB("PDFs",  s.visiblePdfBytes,   s.hiddenPdfBytes))
            appendLine(rowB("All", vbAll, hbAll))
            appendLine()
            appendLine("CLEANED UP (lifetime)")
            appendLine("Removed   %,d items".format(deletedCount))
            appendLine("Size      %s".format(fmtBytes(deletedBytes)))
            appendLine()
            appendLine("IN TRASH (recoverable)")
            appendLine("Items     %,d".format(inTrashCount))
            appendLine("Size      %s".format(fmtBytes(inTrashBytes)))
            appendLine()
            append(s.integrityDetail)
        }

        val tv = TextView(activity).apply {
            text = msg
            typeface = Typeface.MONOSPACE
            textSize = 12f
            setPadding(48, 32, 48, 16)
        }
        AlertDialog.Builder(activity)
            .setTitle("Media Stats")
            .setView(ScrollView(activity).apply { addView(tv) })
            .setPositiveButton("OK", null)
            .show()
    }

    private fun fmtBytes(b: Long): String = when {
        b >= 1_073_741_824L -> "%.1f GB".format(b / 1_073_741_824.0)
        b >= 1_048_576L     -> "%.1f MB".format(b / 1_048_576.0)
        b >= 1_024L         -> "%.1f KB".format(b / 1_024.0)
        else                -> "$b B"
    }
}
