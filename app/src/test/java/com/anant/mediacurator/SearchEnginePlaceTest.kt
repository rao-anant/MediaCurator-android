package com.anant.mediacurator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Place-search matching in [SearchEngine] (v1.1): city name, aliases, and region all resolve. */
class SearchEnginePlaceTest {

    private fun img(id: Long, name: String) =
        MediaItem(id, "content://media/$id", "ext", 1_000L + id, name, 100L, MediaType.IMAGE)

    private val bengaluruPhoto = img(1, "IMG_20240101_120000.jpg")
    private val mumbaiPhoto    = img(2, "IMG_20240202_120000.jpg")
    private val nycPhoto       = img(3, "IMG_20240303_120000.jpg")
    private val media = listOf(bengaluruPhoto, mumbaiPhoto, nycPhoto)

    private val places = mapOf(
        1L to listOf("Bengaluru", "Bangalore", "Karnataka"),
        2L to listOf("Mumbai", "Bombay", "Maharashtra"),
        3L to listOf("New York", "New York"),
    )

    private fun ids(q: String) =
        SearchEngine.search(q, media, placeIndex = places).map { it.item.id }.toSet()

    @Test fun cityName_matches() {
        assertEquals(setOf(1L), ids("bengaluru"))
        assertEquals(setOf(2L), ids("mumbai"))
    }

    @Test fun alias_matches() {
        assertEquals(setOf(1L), ids("bangalore"))   // alias → Bengaluru
        assertEquals(setOf(2L), ids("bombay"))      // alias → Mumbai
    }

    @Test fun region_matches() {
        assertEquals(setOf(1L), ids("karnataka"))
    }

    @Test fun multiWordPlace_matchesEitherWord() {
        assertEquals(setOf(3L), ids("york"))
    }

    @Test fun reasonShowsCityPin() {
        val r = SearchEngine.search("bangalore", media, placeIndex = places).first { it.item.id == 1L }
        assertEquals("📍 Bengaluru", r.matchReason)   // pin + primary (city) name, not the alias
    }

    @Test fun typoTolerant() {
        // one-edit typo still resolves (fuzzy match, same as filenames)
        assertTrue(1L in ids("bengalru"))
    }

    @Test fun noPlaceIndex_noPlaceMatch() {
        assertTrue(SearchEngine.search("bengaluru", media).isEmpty())
    }

    @Test fun unknownPlace_noResults() {
        assertTrue(ids("london").isEmpty())
    }
}
