package com.anant.mediacurator

import org.junit.Assert.assertEquals
import org.junit.Test

/** Browse aggregation for both experiments: Option A (cities) and Option B (country→state→city). */
class PlaceSummaryTest {

    private val records = listOf(
        PlaceRecord("Bengaluru", "Karnataka", "India"),
        PlaceRecord("Bengaluru", "Karnataka", "India"),
        PlaceRecord("Mumbai", "Maharashtra", "India"),
        PlaceRecord("San Francisco", "California", "United States"),
        PlaceRecord("San Francisco", "California", "United States"),
        PlaceRecord("Los Angeles", "California", "United States"),
        PlaceRecord("Austin", "Texas", "United States"),
    )

    @Test fun optionA_citiesRankedByCount() {
        assertEquals(
            listOf(
                PlaceCount("Bengaluru", 2), PlaceCount("San Francisco", 2),
                PlaceCount("Austin", 1), PlaceCount("Los Angeles", 1), PlaceCount("Mumbai", 1),
            ),
            PlaceBrowse.cities(records)
        )
    }

    @Test fun optionB_countriesRankedByCount() {
        assertEquals(
            listOf(PlaceCount("United States", 4), PlaceCount("India", 3)),
            PlaceBrowse.countries(records)
        )
    }

    @Test fun optionB_statesWithinCountry() {
        assertEquals(
            listOf(PlaceCount("California", 3), PlaceCount("Texas", 1)),
            PlaceBrowse.states(records, "United States")
        )
    }

    @Test fun optionB_citiesWithinState() {
        assertEquals(
            listOf(PlaceCount("San Francisco", 2), PlaceCount("Los Angeles", 1)),
            PlaceBrowse.citiesIn(records, "United States", "California")
        )
    }

    @Test fun ignoresBlankLevels() {
        val recs = listOf(PlaceRecord("Paris", "", "France"), PlaceRecord("", "", ""))
        assertEquals(listOf(PlaceCount("Paris", 1)), PlaceBrowse.cities(recs))
        assertEquals(listOf(PlaceCount("France", 1)), PlaceBrowse.countries(recs))
    }

    @Test fun optionA_alphabeticalSort() {
        assertEquals(
            listOf("Austin", "Bengaluru", "Los Angeles", "Mumbai", "San Francisco"),
            PlaceBrowse.cities(records, PlaceSort.NAME).map { it.name }
        )
    }

    @Test fun citiesInCountry_ignoresState() {
        val recs = listOf(
            PlaceRecord("Singapore", "", "Singapore"),
            PlaceRecord("Jurong", "", "Singapore"),
            PlaceRecord("Singapore", "", "Singapore"),
        )
        assertEquals(
            listOf(PlaceCount("Singapore", 2), PlaceCount("Jurong", 1)),
            PlaceBrowse.citiesInCountry(recs, "Singapore")
        )
    }
}
