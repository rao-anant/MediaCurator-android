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
            val resumeKey    = monthsOldest.firstOrNull { it !in done }
            val hiddenItems  = monthsOldest.filter { it in done }.sumOf { byMonth[it] ?: 0 }

            hashStore.ensureLoaded()
            val dupGroups = if (hashStore.countEntries() == 0) -1 else hashStore.findDuplicateGroups().size

            val state = buildState(media.size, totalSize, hiddenItems, totalMonths, doneMonths, resumeKey, dupGroups)
            DebugLog.i("home", "load: done media=${media.size} months=$totalMonths/$doneMonths dup=$dupGroups -> '${state.summary}'")
            _state.postValue(state)
        }
    }

    private fun buildState(
        total: Int, size: Long, hidden: Int,
        totalMonths: Int, doneMonths: Int, resumeKey: String?, dupGroups: Int
    ): HomeState {
        val cShort = { n: Int -> GalleryAdapter.fmtCountShort(n) }
        val summary =
            if (total == 0) "No media found"
            else "${cShort(total)} items · ${GalleryAdapter.fmtBytes(size)} · ${cShort(hidden)} reviewed"

        val pct = if (totalMonths > 0) doneMonths * 100 / totalMonths else 0

        var title = "Continue curating"; var progress = pct; var progressLabel = "$pct% curated"
        var caption = "Pick up at"; var resumeLabel = monthLabel(resumeKey); var button = "Resume"

        when {
            total == 0 -> {
                title = "No media yet"; progress = -1; progressLabel = ""
                caption = ""; resumeLabel = "Add photos to get started"; button = "Open gallery"
            }
            doneMonths == 0 -> {            // first run
                title = "Start curating"; progress = -1; progressLabel = ""
                caption = "Begin at"; resumeLabel = monthLabel(resumeKey); button = "Start"
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

        return HomeState(summary, title, progress, progressLabel, caption, resumeLabel, button,
            resumeKey, dupSub, hiddenSub)
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
