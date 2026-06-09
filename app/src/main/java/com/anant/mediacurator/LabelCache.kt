package com.anant.mediacurator

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

/**
 * Persists ML-Kit image labels **with confidence scores** keyed by MediaItem.id.
 *
 * Storage format: one SharedPreferences file ("label_cache").
 *   key  = "<mediaId>"  (Long as string)
 *   value = JSON array of [label, confidence] pairs:
 *           e.g.  [["Dog",0.89],["Beach",0.76],["Sky",0.71]]
 *
 * Storing confidence lets the search engine weight results by how certain
 * ML Kit was — a "Sunglasses" label at 0.51 confidence scores lower than
 * a "Dog" label at 0.89, so weak/ambiguous predictions rank last and can
 * be cut off by MIN_SCORE in SearchEngine.
 *
 * Cache versioning
 * ----------------
 * Bump [CACHE_VERSION] whenever inference settings change (threshold, max-labels).
 * On first open after a bump every photo is re-labeled with the new settings.
 *
 *   v1 — initial (minConfidence 0.65, maxLabels 5, labels only)
 *   v2 — lowered to minConfidence 0.50, maxLabels 8 (labels only)
 *   v3 — stores [label, confidence] pairs so search can weight by confidence
 */
class LabelCache(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    init {
        if (prefs.getString(KEY_VERSION, null) != CACHE_VERSION) {
            prefs.edit().clear().putString(KEY_VERSION, CACHE_VERSION).apply()
        }
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns label→confidence map for [id], or null if not yet labeled. */
    fun getLabels(id: Long): Map<String, Float>? {
        val raw = prefs.getString(id.toString(), null) ?: return null
        return parseEntry(raw)
    }

    /** True if this id has been through the labeler (even if the result was empty). */
    fun hasEntry(id: Long): Boolean = prefs.contains(id.toString())

    /**
     * Load every cached entry.  Called once at startup to pre-populate the
     * ViewModel's label map before the background labeling job runs.
     * Only entries with at least one label are included.
     */
    fun loadAll(): Map<Long, Map<String, Float>> {
        val result = mutableMapOf<Long, Map<String, Float>>()
        for ((key, value) in prefs.all) {
            if (key == KEY_VERSION) continue
            val id     = key.toLongOrNull() ?: continue
            val labels = parseEntry(value as? String ?: continue)
            if (labels != null && labels.isNotEmpty()) result[id] = labels
        }
        return result
    }

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist [labels] (label→confidence map) for [id].
     * An empty map is stored intentionally — it means "labeled, nothing found"
     * and prevents repeated inference on un-labelable images.
     */
    fun saveLabels(id: Long, labels: Map<String, Float>) {
        val arr = JSONArray()
        for ((label, conf) in labels) {
            arr.put(JSONArray().put(label).put(conf.toDouble()))
        }
        prefs.edit().putString(id.toString(), arr.toString()).apply()
    }

    /** Wipe all labels and reset version (forces full re-scan on next open). */
    fun clearAll() = prefs.edit().clear().putString(KEY_VERSION, CACHE_VERSION).apply()

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun parseEntry(raw: String): Map<String, Float>? {
        return try {
            val arr    = JSONArray(raw)
            val result = mutableMapOf<String, Float>()
            for (i in 0 until arr.length()) {
                val pair  = arr.getJSONArray(i)
                val label = pair.getString(0)
                val conf  = pair.getDouble(1).toFloat()
                result[label] = conf
            }
            result
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        private const val PREFS_NAME  = "label_cache"
        private const val KEY_VERSION = "_version"
        const val CACHE_VERSION       = "v3"
    }
}
