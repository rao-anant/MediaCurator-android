package com.anant.mediacurator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

/** Everything the Home hub renders, computed once per load. */
data class HomeState(
    val summary: String,
    val heroTitle: String,
    val heroProgress: Int,          // 0..100, or -1 to hide the bar
    val heroProgressLabel: String,  // "" hides
    val heroCaption: String,        // "" hides
    val resumeLabel: String,
    val heroButton: String,
    val resumeMonthKey: String?,    // for the Stage 3 deep-link
    val dupSub: String,
    val hiddenSub: String,
    val trashSub: String,
    val trashEmpty: Boolean,
)

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val repo      = MediaRepository(app)
    private val prefs     = PreferencesManager(app)
    private val hashStore = PhotoHashStore.getInstance(app)

    private val _state = MutableLiveData<HomeState>()
    val state: LiveData<HomeState> = _state

    fun load() {
        viewModelScope.launch(Dispatchers.IO) {
            DebugLog.i("home", "load: start")
            val media = MediaCache.get(repo)
            val done  = prefs.getDoneMonths()

            // Group by month (same key logic as the gallery: from dateTaken).
            val cal = Calendar.getInstance()
            val byMonth = HashMap<String, Int>()
            var totalSize = 0L
            for (item in media) {
                cal.timeInMillis = item.dateTaken
                val key = prefs.monthKey(cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1)
                byMonth[key] = (byMonth[key] ?: 0) + 1
                totalSize += item.size
            }
            val monthsOldest = byMonth.keys.sorted()              // "YYYY-MM" sorts chronologically
            val totalMonths  = monthsOldest.size
            val doneMonths   = monthsOldest.count { it in done }
            val resumeKey    = monthsOldest.firstOrNull { it !in done }   // oldest un-curated (state driver)
            val hiddenItems  = monthsOldest.filter { it in done }.sumOf { byMonth[it] ?: 0 }

            // Where "Resume" sends the gallery: the raw last-viewed month if we have one (the
            // gallery resolves it — lands there if still visible, else the top open month, else
            // just shows the tree), otherwise the oldest un-curated month.
            val lastViewed = prefs.getLastViewedMonth()
            val deepLink = lastViewed ?: resumeKey
            // What the hero LABEL shows ("Pick up at …"): the last-viewed month only while it's
            // still visible (exists + not hidden); otherwise the oldest un-curated month.
            val lastViewedVisible = lastViewed != null && byMonth.containsKey(lastViewed) && lastViewed !in done
            val labelTarget = if (lastViewedVisible) lastViewed else resumeKey

            hashStore.ensureLoaded()
            val dupGroups = if (hashStore.countEntries() == 0) -1 else hashStore.findDuplicateGroups().size

            // In-Trash is derived from the actual trash (ground truth) — a prefs counter would
            // drift whenever the trash changes outside our app (external restore, OS auto-purge).
            val trashed = TrashManager.get(getApplication()).listTrashed()
            val state = buildState(media.size, totalSize, hiddenItems, totalMonths, doneMonths, resumeKey, labelTarget, deepLink, dupGroups,
                trashed.size.toLong(), trashed.sumOf { it.size })
            DebugLog.i("home", "load: done media=${media.size} months=$totalMonths/$doneMonths dup=$dupGroups -> '${state.summary}'")
            _state.postValue(state)
        }
    }

    private fun buildState(
        total: Int, size: Long, hidden: Int,
        totalMonths: Int, doneMonths: Int, resumeKey: String?, labelTarget: String?, deepLink: String?, dupGroups: Int,
        trashCount: Long, trashBytes: Long
    ): HomeState {
        val cShort = { n: Int -> GalleryAdapter.fmtCountShort(n) }
        val summary =
            if (total == 0) "No media found"
            else "${cShort(total)} items · ${GalleryAdapter.fmtBytes(size)} · ${cShort(hidden)} reviewed"

        val pct = if (totalMonths > 0) doneMonths * 100 / totalMonths else 0

        // resumeKey drives WHICH state we're in; labelTarget is the month shown in the hero,
        // deepLink is where "Resume" sends the gallery (the gallery resolves it further).
        var title = "Continue curating"; var progress = pct; var progressLabel = "$pct% curated"
        var caption = "Pick up at"; var resumeLabel = monthLabel(labelTarget); var button = "Resume"
        var deepLinkOut = deepLink

        when {
            total == 0 -> {
                title = "No media yet"; progress = -1; progressLabel = ""
                caption = ""; resumeLabel = "Add photos to get started"; button = "Open gallery"; deepLinkOut = null
            }
            doneMonths == 0 -> {            // first run
                title = "Start curating"; progress = -1; progressLabel = ""
                caption = "Begin at"; button = "Start"
            }
            resumeKey == null -> {          // all caught up
                title = "All caught up"; progress = 100; progressLabel = "100% curated"
                caption = ""; resumeLabel = "Everything reviewed"; button = "Browse all"
            }
        }

        val dupSub = when {
            dupGroups < 0  -> "Not scanned yet"
            dupGroups == 0 -> "None found"
            else           -> "$dupGroups ${if (dupGroups == 1) "group" else "groups"}"
        }
        val hiddenSub = if (hidden == 0) "Nothing hidden yet" else "${cShort(hidden)} hidden"
        val trashEmpty = trashCount <= 0L
        val trashSub = if (trashEmpty) "0 items"
                       else "${cShort(trashCount.toInt())} · ${GalleryAdapter.fmtBytes(trashBytes)}"

        return HomeState(summary, title, progress, progressLabel, caption, resumeLabel, button,
            deepLinkOut, dupSub, hiddenSub, trashSub, trashEmpty)
    }

    private val monthNames = arrayOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December"
    )

    /** "2024-10" -> "October 2024". */
    private fun monthLabel(key: String?): String {
        if (key == null) return ""
        val parts = key.split("-")
        val year = parts.getOrNull(0) ?: return key
        val month = parts.getOrNull(1)?.toIntOrNull() ?: return key
        if (month !in 1..12) return key
        return "${monthNames[month - 1]} $year"
    }
}
