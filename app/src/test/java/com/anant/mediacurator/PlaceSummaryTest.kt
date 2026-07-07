package com.anant.mediacurator

import org.junit.Assert.assertEquals
import org.junit.Test

/** The browseable "places in your library" list ([PlaceStore.summarize]) — pure aggregation. */
class PlaceSummaryTest {

    @Test fun ranksByCountThenName() {
        // CSV values as PlaceStore stores them: "city,alias,...,region". Primary = first token.
        val values = listOf(
            "Bengaluru,Bangalore,Karnataka",
            "Bengaluru,Bangalore,Karnataka",
            "Mumbai,Bombay,Maharashtra",
            "Bengaluru,Bangalore,Karnataka",
            "Mumbai,Bombay,Maharashtra",
            "London,,England",
        )
        val summary = PlaceStore.summarize(values)
        assertEquals(
            listOf(PlaceCount("Bengaluru", 3), PlaceCount("Mumbai", 2), PlaceCount("London", 1)),
            summary
        )
    }

    @Test fun ignoresEmptyAndNoGpsMarkers() {
        val values = listOf("Paris,,Ile-de-France", "", "  ", "Paris,,Ile-de-France")
        assertEquals(listOf(PlaceCount("Paris", 2)), PlaceStore.summarize(values))
    }

    @Test fun tiesBrokenAlphabetically() {
        val values = listOf("Tokyo", "Delhi")   // both count 1
        assertEquals(listOf("Delhi", "Tokyo"), PlaceStore.summarize(values).map { it.name })
    }
}
