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
 * Option A semantics: selecting a hidden month UNHIDES it immediately (it rejoins the gallery)
 * and displays it here for one last review. Re-hiding it puts it back; leaving without re-hiding
 * keeps it unhidden (it's simply gone from the dropdowns on the next visit).
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

    /** Unhide [year]/[month] and show its items for review. */
    fun selectMonth(year: Int, month: Int) {
        val group = _hiddenMonths.value?.find { it.year == year && it.month == month } ?: return
        prefs.unmarkMonthDone(year, month)
        _shown.value = Shown(
            year, month, group.label,
            group.items.mapIndexed { i, item -> GalleryItem.Media(item, group.key, i) }
        )
        recomputeHidden()   // now excludes the month → it drops out of the dropdowns
    }

    /** Re-hide the month currently being shown. */
    fun hideShown() {
        val s = _shown.value ?: return
        prefs.markMonthDone(s.year, s.month)
        _shown.value = null
        recomputeHidden()
    }
}
