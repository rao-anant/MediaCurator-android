package com.anant.mediacurator

/**
 * A process-wide "curation progress was reset" signal.
 *
 * The Settings "Reset progress" action clears SharedPreferences, but the gallery ([MainActivity]
 * and its [GalleryViewModel]) may still be alive in the back stack with in-memory review/expansion
 * state seeded once at ViewModel init — and Settings is reachable straight from the gallery menu.
 * Without this signal, that stale state would survive the reset and even re-persist itself on the
 * next load, silently undoing it. The gallery consumes this on resume to drop and re-seed.
 */
object CurationResetSignal {
    @Volatile var epoch: Long = 0L
        private set

    fun bump() { epoch++ }
}
