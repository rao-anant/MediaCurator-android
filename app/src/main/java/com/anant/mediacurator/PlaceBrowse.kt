package com.anant.mediacurator

/** A place bucket at some level (country / state / city) with its photo count. */
data class PlaceCount(val name: String, val count: Int)

/** How to order a browse list. */
enum class PlaceSort { COUNT, NAME }

/**
 * Pure aggregation of per-photo [PlaceRecord]s into ranked, browseable lists. Powers the browse
 * experiments: **A** (flat cities), **B** (country → state → city drill-down), **C** (tree).
 * Sorted by photo count (desc) or name (A–Z). JVM-unit-tested; mirrors to iOS.
 */
object PlaceBrowse {

    private fun rank(names: Sequence<String>, sort: PlaceSort): List<PlaceCount> {
        val counts = names.filter { it.isNotBlank() }
            .groupingBy { it }.eachCount()
            .map { PlaceCount(it.key, it.value) }
        return when (sort) {
            PlaceSort.COUNT -> counts.sortedWith(compareByDescending<PlaceCount> { it.count }.thenBy { it.name })
            PlaceSort.NAME  -> counts.sortedBy { it.name.lowercase() }
        }
    }

    /** Option A: every distinct city. */
    fun cities(records: List<PlaceRecord>, sort: PlaceSort = PlaceSort.COUNT): List<PlaceCount> =
        rank(records.asSequence().map { it.city }, sort)

    /** Level 1: distinct countries. */
    fun countries(records: List<PlaceRecord>, sort: PlaceSort = PlaceSort.COUNT): List<PlaceCount> =
        rank(records.asSequence().map { it.country }, sort)

    /** Level 2: states within [country]. */
    fun states(records: List<PlaceRecord>, country: String, sort: PlaceSort = PlaceSort.COUNT): List<PlaceCount> =
        rank(records.asSequence().filter { it.country == country }.map { it.state }, sort)

    /** Level 3: cities within [country]/[state]. */
    fun citiesIn(records: List<PlaceRecord>, country: String, state: String, sort: PlaceSort = PlaceSort.COUNT): List<PlaceCount> =
        rank(records.asSequence().filter { it.country == country && it.state == state }.map { it.city }, sort)

    /** All cities in [country] regardless of state — used when a country has no state level (city-states). */
    fun citiesInCountry(records: List<PlaceRecord>, country: String, sort: PlaceSort = PlaceSort.COUNT): List<PlaceCount> =
        rank(records.asSequence().filter { it.country == country }.map { it.city }, sort)
}
