package com.anant.mediacurator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class PreferencesManager(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun markMonthDone(year: Int, month: Int) {
        val current = getDoneMonths().toMutableSet()
        current.add(monthKey(year, month))
        prefs.edit()
            .putStringSet(KEY_DONE_MONTHS, current)
            .putString(KEY_LAST_HIDDEN_MONTH, monthKey(year, month))   // remember the most recent hide
            .apply()
    }

    fun unmarkMonthDone(year: Int, month: Int) {
        val current = getDoneMonths().toMutableSet()
        current.remove(monthKey(year, month))
        val editor = prefs.edit().putStringSet(KEY_DONE_MONTHS, current)
        // If we just unhid the month we were pointing at, forget it so the Hidden screen
        // doesn't try to auto-reopen a month that's no longer hidden.
        if (getLastHiddenMonth() == monthKey(year, month)) editor.remove(KEY_LAST_HIDDEN_MONTH)
        editor.apply()
    }

    /** Month key ("YYYY-MM") of the most recently hidden month, or null. */
    fun getLastHiddenMonth(): String? = prefs.getString(KEY_LAST_HIDDEN_MONTH, null)

    /** Month key ("YYYY-MM") at the top of the gallery when the user last left it — for Resume. */
    fun setLastViewedMonth(key: String) { prefs.edit().putString(KEY_LAST_VIEWED_MONTH, key).apply() }
    fun getLastViewedMonth(): String? = prefs.getString(KEY_LAST_VIEWED_MONTH, null)

    fun getDoneMonths(): Set<String> =
        prefs.getStringSet(KEY_DONE_MONTHS, emptySet()) ?: emptySet()

    fun setDoneMonths(months: Set<String>) {
        prefs.edit().putStringSet(KEY_DONE_MONTHS, months).apply()
    }

    fun isMonthDone(year: Int, month: Int) = getDoneMonths().contains(monthKey(year, month))

    fun saveSortMode(mode: SortMode) {
        prefs.edit().putString(KEY_SORT_MODE, mode.name).apply()
    }

    fun getSortMode(): SortMode {
        val saved = prefs.getString(KEY_SORT_MODE, null)
        if (saved != null) {
            return try { SortMode.valueOf(saved) } catch (e: Exception) { SortMode.SIZE_ABSOLUTE } // SIZE_LARGEST → SIZE_ABSOLUTE
        }
        // Migrate from old boolean pref (default is DATE_OLDEST)
        return if (prefs.getBoolean(KEY_SORT_ASC, true)) SortMode.DATE_OLDEST else SortMode.DATE_NEWEST
    }

    fun saveExpandedYears(years: Set<Int>) {
        prefs.edit().putStringSet(KEY_EXPANDED_YEARS, years.map { it.toString() }.toSet()).apply()
    }
    fun getExpandedYears(): Set<Int> =
        (prefs.getStringSet(KEY_EXPANDED_YEARS, emptySet()) ?: emptySet())
            .mapNotNull { it.toIntOrNull() }.toSet()

    fun saveExpandedMonths(months: Set<String>) {
        prefs.edit().putStringSet(KEY_EXPANDED_MONTHS, months).apply()
    }
    fun getExpandedMonths(): Set<String> =
        prefs.getStringSet(KEY_EXPANDED_MONTHS, emptySet()) ?: emptySet()

    fun saveExpandedSubGroups(subGroups: Set<String>) {
        prefs.edit().putStringSet(KEY_EXPANDED_SUBGROUPS, subGroups).apply()
    }
    fun getExpandedSubGroups(): Set<String> =
        prefs.getStringSet(KEY_EXPANDED_SUBGROUPS, emptySet()) ?: emptySet()

    /**
     * "Seen" review keys for the Hide-Month gate, one per (month, sub-group, type) the user has
     * actually viewed on screen — e.g. "2024-03:cam:video".  See GalleryViewModel for the rule.
     */
    fun saveSeenReviewKeys(keys: Set<String>) {
        prefs.edit().putStringSet(KEY_SEEN_REVIEW_KEYS, keys).apply()
    }
    fun getSeenReviewKeys(): Set<String> =
        prefs.getStringSet(KEY_SEEN_REVIEW_KEYS, emptySet()) ?: emptySet()

    fun saveIncludePhoto(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PHOTO, include).apply()
    }

    fun isIncludePhoto(): Boolean = prefs.getBoolean(KEY_INCLUDE_PHOTO, true)

    fun saveIncludeVideo(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_VIDEO, include).apply()
    }

    fun isIncludeVideo(): Boolean = prefs.getBoolean(KEY_INCLUDE_VIDEO, true)

    fun saveIncludePdf(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_PDF, include).apply()
    }

    fun isIncludePdf(): Boolean = prefs.getBoolean(KEY_INCLUDE_PDF, true)

    fun saveIncludeAudio(include: Boolean) {
        prefs.edit().putBoolean(KEY_INCLUDE_AUDIO, include).apply()
    }

    fun isIncludeAudio(): Boolean = prefs.getBoolean(KEY_INCLUDE_AUDIO, true)

    /** When false, PDF content indexing and BM25 search are completely disabled. */
    fun isPdfContentSearchEnabled(): Boolean = prefs.getBoolean(KEY_PDF_CONTENT_SEARCH, true)
    fun setPdfContentSearchEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PDF_CONTENT_SEARCH, enabled).apply()
    }

    /** When false, photo MD5 hashing and duplicate detection are completely disabled. */
    fun isPhotoDuplicateDetectionEnabled(): Boolean = prefs.getBoolean(KEY_PHOTO_DUPLICATE_DETECTION, true)
    fun setPhotoDuplicateDetectionEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PHOTO_DUPLICATE_DETECTION, enabled).apply()
    }

    /** True once the All-files-access rationale has been shown (so we ask only once, up front). */
    fun wasAllFilesPromptShown(): Boolean = prefs.getBoolean(KEY_ALL_FILES_PROMPT_SHOWN, false)
    fun setAllFilesPromptShown() {
        prefs.edit().putBoolean(KEY_ALL_FILES_PROMPT_SHOWN, true).apply()
    }

    /** True once we've offered to restore the hidden-months backup (prevents a second prompt). */
    fun wasHiddenRestoreOffered(): Boolean = prefs.getBoolean(KEY_HIDDEN_RESTORE_OFFERED, false)
    fun setHiddenRestoreOffered() {
        prefs.edit().putBoolean(KEY_HIDDEN_RESTORE_OFFERED, true).apply()
    }

    /**
     * The most recent delete batch, as (contentUri, sizeBytes) pairs, for the persistent
     * "Restore last deleted" quick-undo. Replaced on each delete; cleared once used, emptied,
     * or purged. Survives app restart.
     */
    fun setLastDeletedBatch(items: List<Pair<String, Long>>) {
        if (items.isEmpty()) { clearLastDeletedBatch(); return }
        val arr = JSONArray()
        items.forEach { (uri, size) -> arr.put(JSONObject().put("uri", uri).put("size", size)) }
        prefs.edit().putString(KEY_LAST_BATCH, arr.toString()).apply()
    }
    fun getLastDeletedBatch(): List<Pair<String, Long>> {
        val s = prefs.getString(KEY_LAST_BATCH, null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it); o.getString("uri") to o.getLong("size")
            }
        } catch (e: Exception) { emptyList() }
    }
    fun clearLastDeletedBatch() { prefs.edit().remove(KEY_LAST_BATCH).apply() }

    /** First-run onboarding: true once the user has seen the "how curation works" intro. */
    fun hasSeenOnboarding(): Boolean = prefs.getBoolean(KEY_SEEN_ONBOARDING, false)
    fun setSeenOnboarding() {
        prefs.edit().putBoolean(KEY_SEEN_ONBOARDING, true).apply()
    }

    fun monthKey(year: Int, month: Int): String {
        val m = if (month < 10) "0$month" else month.toString()
        return "$year-$m"
    }

    companion object {
        private const val PREFS_NAME = "photo_curator_prefs"
        private const val KEY_DONE_MONTHS = "done_months"
        private const val KEY_LAST_HIDDEN_MONTH = "last_hidden_month"
        private const val KEY_LAST_VIEWED_MONTH = "last_viewed_month"
        private const val KEY_SORT_ASC = "sort_ascending"   // kept for migration read
        private const val KEY_SORT_MODE = "sort_mode"
        private const val KEY_INCLUDE_PHOTO = "include_photo"
        private const val KEY_INCLUDE_VIDEO = "include_video"
        private const val KEY_INCLUDE_PDF = "include_pdf"
        private const val KEY_INCLUDE_AUDIO = "include_audio"
        private const val KEY_EXPANDED_YEARS       = "expanded_years"
        private const val KEY_EXPANDED_MONTHS      = "expanded_months"
        private const val KEY_EXPANDED_SUBGROUPS   = "expanded_subgroups"
        // Per-(month, sub-group, type) review keys. Replaces the older coarse "seen_subgroups"
        // set (which only tracked whether a sub-group was expanded, ignoring chip filters).
        private const val KEY_SEEN_REVIEW_KEYS     = "seen_review_keys"
        private const val KEY_PDF_CONTENT_SEARCH        = "pdf_content_search"
        private const val KEY_PHOTO_DUPLICATE_DETECTION = "photo_duplicate_detection"
        private const val KEY_SEEN_ONBOARDING           = "seen_onboarding"
        private const val KEY_LAST_BATCH                 = "last_deleted_batch"
        private const val KEY_ALL_FILES_PROMPT_SHOWN    = "all_files_prompt_shown"
        private const val KEY_HIDDEN_RESTORE_OFFERED     = "hidden_restore_offered"
    }
}