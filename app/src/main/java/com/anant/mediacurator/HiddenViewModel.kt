package com.anant.mediacurator

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Backing for the dedicated [HiddenActivity] — a sparse screen for bringing hidden months back.
 *
 * Preview semantics: selecting a hidden month shows its contents but leaves it HIDDEN. Months
 * stay hidden until the user explicitly taps "Unhide this month". No silent unhides — switching
 * months or leaving the screen never changes curation.
 */
class HiddenViewModel(app: Application) : AndroidViewModel(app) {

    private val repo  = MediaRepository(app)
    private val prefs = PreferencesManager(app)
    private var media: List<MediaItem> = emptyList()

    data class Shown(val year: Int, val month: Int, val label: String, val items: List<GalleryItem.Media>)

    private val _hiddenMonths = MutableLiveData<List<MonthGroup>>(emptyList())
    val hiddenMonths: LiveData<List<MonthGroup>> = _hiddenMonths

    private val _shown = MutableLiveData<Shown?>(null)
    val shown: LiveData<Shown?> = _shown

    fun load() {
        viewModelScope.launch {
            if (media.isEmpty()) withContext(Dispatchers.IO) { media = MediaCache.get(repo) }
            recomputeHidden()
        }
    }

    private fun recomputeHidden() {
        val (_, doneGroups) = repo.processAndGroupMedia(media, prefs.getSortMode(), prefs.getDoneMonths())
        _hiddenMonths.value = doneGroups.sortedWith(
            compareByDescending<MonthGroup> { it.year }.thenByDescending { it.month }
        )
    }

    /** Month key ("YYYY-MM") of the most recently hidden month, or null — for auto-reopen. */
    fun lastHiddenMonthKey(): String? = prefs.getLastHiddenMonth()

    /**
     * Preview [year]/[month] — show its items WITHOUT unhiding it. The month stays hidden (and in
     * the dropdowns) until the user explicitly taps "Unhide this month" ([unhideShown]). This is a
     * read-only review, so switching months or leaving the screen never silently changes curation.
     */
    fun selectMonth(year: Int, month: Int) {
        val group = _hiddenMonths.value?.find { it.year == year && it.month == month } ?: return
        _shown.value = Shown(
            year, month, group.label,
            group.items.mapIndexed { i, item -> GalleryItem.Media(item, group.key, i) }
        )
    }

    /** Explicitly unhide the month currently being previewed — the only way a month leaves Hidden. */
    fun unhideShown() {
        val s = _shown.value ?: return
        prefs.unmarkMonthDone(s.year, s.month)
        _shown.value = null
        recomputeHidden()   // now excludes the month → it drops out of the dropdowns
    }
}
