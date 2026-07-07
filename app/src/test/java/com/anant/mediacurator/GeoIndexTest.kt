package com.anant.mediacurator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast JVM tests for the offline reverse-geocoder ([GeoIndex]) behind Place search (v1.1).
 * Uses a tiny hand-built city set; the shipped dataset is GeoNames `cities15000` (see
 * scripts/trim_geonames.ps1). Mirrors to iOS alongside the curation rules.
 */
class GeoIndexTest {

    private val cities = listOf(
        GeoCity("Bengaluru", listOf("Bangalore", "Bengaluru"), 12.9716, 77.5946, "IN", "Karnataka"),
        GeoCity("Mumbai",    listOf("Bombay"),                 19.0760, 72.8777, "IN", "Maharashtra"),
        GeoCity("London",    emptyList(),                      51.5074, -0.1278, "GB", "England"),
        GeoCity("New York",  listOf("NYC"),                    40.7128, -74.0060, "US", "New York"),
        GeoCity("Tokyo",     emptyList(),                      35.6762, 139.6503, "JP", "Tokyo"),
        GeoCity("Sydney",    emptyList(),                     -33.8688, 151.2093, "AU", "New South Wales"),
    )
    private val index = GeoIndex.fromCities(cities)

    @Test fun nearest_pointNearBengaluru_returnsBengaluru() {
        assertEquals("Bengaluru", index.nearest(12.95, 77.60)?.name)
    }

    @Test fun nearest_handlesNegativeLongitude() {
        assertEquals("New York", index.nearest(40.70, -74.01)?.name)
        assertEquals("London",   index.nearest(51.50, -0.12)?.name)
    }

    @Test fun nearest_handlesSouthernHemisphere() {
        assertEquals("Sydney", index.nearest(-33.87, 151.21)?.name)
    }

    @Test fun nearest_picksTrulyClosest_notJustSameHemisphere() {
        // Halfway-ish between Mumbai and Bengaluru but closer to Mumbai.
        assertEquals("Mumbai", index.nearest(18.5, 73.0)?.name)
    }

    @Test fun nearest_isCorrectAcrossTheAntimeridian() {
        // Two cities straddling the dateline; a query just east of +179 must pick the +179 one,
        // not the −179 one — the bug a naive 2-D lon tree hits.
        val dateline = GeoIndex.fromCities(listOf(
            GeoCity("EastEdge", emptyList(), 0.0,  179.0),
            GeoCity("WestEdge", emptyList(), 0.0, -179.0),
        ))
        assertEquals("EastEdge", dateline.nearest(0.0, 179.5)?.name)
        assertEquals("WestEdge", dateline.nearest(0.0, -179.5)?.name)
        // A point at exactly 180° is equidistant-ish; must still resolve to one of the two.
        assertTrue(dateline.nearest(0.0, 180.0)?.name in setOf("EastEdge", "WestEdge"))
    }

    @Test fun emptyIndex_returnsNull() {
        assertNull(GeoIndex.fromCities(emptyList()).nearest(0.0, 0.0))
    }

    // ── Parsing the trimmed dataset format ──────────────────────────────────────────

    @Test fun parseLine_readsAllFieldsAndAltNames() {
        val c = GeoIndex.parseLine("Bengaluru|Bangalore,Bengaluru|12.9716|77.5946|IN|Karnataka")!!
        assertEquals("Bengaluru", c.name)
        assertEquals(listOf("Bangalore", "Bengaluru"), c.altNames)
        assertEquals(12.9716, c.lat, 1e-6)
        assertEquals(77.5946, c.lon, 1e-6)
        assertEquals("IN", c.country)
        assertEquals("Karnataka", c.admin1)
        // Alt name is searchable so "Bombay"→Mumbai / "Bangalore"→Bengaluru resolve.
        assertTrue("Bangalore" in c.searchNames)
    }

    @Test fun parseLine_handlesMissingAltsAndTrailingFields() {
        val c = GeoIndex.parseLine("London||51.5074|-0.1278")!!
        assertEquals("London", c.name)
        assertTrue(c.altNames.isEmpty())
        assertEquals("", c.country)
        assertEquals("London", c.label)   // no admin1 → just the name
    }

    @Test fun parseLine_rejectsMalformedOrCommentLines() {
        assertNull(GeoIndex.parseLine(""))
        assertNull(GeoIndex.parseLine("# comment"))
        assertNull(GeoIndex.parseLine("OnlyName|alt"))            // no lat/lon
        assertNull(GeoIndex.parseLine("Bad|alt|not-a-number|10")) // bad lat
    }

    @Test fun fromLines_skipsBadRowsAndBuildsIndex() {
        val idx = GeoIndex.fromLines(sequenceOf(
            "# cities",
            "Paris||48.8566|2.3522|FR|Ile-de-France",
            "garbage line",
            "Berlin||52.5200|13.4050|DE|Berlin",
        ))
        assertEquals(2, idx.size)
        assertEquals("Paris", idx.nearest(48.86, 2.35)?.name)
    }
}
