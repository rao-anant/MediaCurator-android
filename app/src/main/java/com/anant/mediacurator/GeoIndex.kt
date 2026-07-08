package com.anant.mediacurator

import kotlin.math.cos
import kotlin.math.sin

/**
 * Offline reverse-geocoding for **Place search** (v1.1). Pure, platform-independent — no Android
 * imports — so it unit-tests fast on the JVM and re-implements verbatim on iOS (mirrors the
 * [CurationLogic] pattern; see docs/CURATION_REGRESSION_TESTS.md and FUNCTIONAL_SPEC §7).
 *
 * Given a photo's EXIF lat/long, [nearest] returns the closest city from a bundled GeoNames dataset
 * (`cities15000`, trimmed to `name|alt|lat|lon|country|admin`; see scripts/trim_geonames.ps1). No
 * network — the OS reverse geocoders (`android.location.Geocoder`, `CLGeocoder`) are network-backed
 * and would break the "no internet, ever" promise.
 *
 * Nearest-neighbour runs in **3-D on the unit sphere** (each city → x,y,z), so Euclidean chord
 * distance is monotonic with great-circle distance. That makes it correct across the antimeridian
 * (lon +179 vs −179 are neighbours) and near the poles, which a naïve 2-D lat/long tree gets wrong.
 */
data class GeoCity(
    val name: String,
    val altNames: List<String>,
    val lat: Double,
    val lon: Double,
    val country: String = "",
    val admin1: String = ""
) {
    /** Every token worth feeding into the search index for this place (city, aliases, state, country). */
    val searchNames: List<String>
        get() = (listOf(name) + altNames +
                 listOfNotNull(admin1.ifBlank { null }) +
                 listOfNotNull(country.ifBlank { null })).distinct()

    /** e.g. "Bengaluru, Karnataka" — for display. */
    val label: String get() = if (admin1.isBlank()) name else "$name, $admin1"
}

class GeoIndex private constructor(private val cities: List<GeoCity>) {

    val size: Int get() = cities.size

    // Each city projected onto the unit sphere, indexed in lockstep with [cities].
    private val pts: Array<DoubleArray> = Array(cities.size) { toXyz(cities[it].lat, cities[it].lon) }

    private class Node(val idx: Int, val axis: Int, var left: Node?, var right: Node?)

    private val root: Node? = build(cities.indices.toMutableList(), 0)

    private fun build(idxs: MutableList<Int>, depth: Int): Node? {
        if (idxs.isEmpty()) return null
        val axis = depth % 3
        idxs.sortBy { pts[it][axis] }
        val mid = idxs.size / 2
        return Node(idxs[mid], axis, null, null).apply {
            left  = build(ArrayList(idxs.subList(0, mid)), depth + 1)
            right = build(ArrayList(idxs.subList(mid + 1, idxs.size)), depth + 1)
        }
    }

    /** The city nearest to ([lat], [lon]), or null if the dataset is empty. */
    fun nearest(lat: Double, lon: Double): GeoCity? {
        val start = root ?: return null
        val target = toXyz(lat, lon)
        var bestIdx = start.idx
        var bestDist = dist2(pts[start.idx], target)

        fun search(node: Node?) {
            if (node == null) return
            val d = dist2(pts[node.idx], target)
            if (d < bestDist) { bestDist = d; bestIdx = node.idx }
            val diff = target[node.axis] - pts[node.idx][node.axis]
            val near = if (diff < 0) node.left else node.right
            val far  = if (diff < 0) node.right else node.left
            search(near)
            // The splitting plane is `diff` away on this axis; only cross it if a closer point could
            // live on the far side. Axis gap is a lower bound on the true 3-D distance.
            if (diff * diff < bestDist) search(far)
        }
        search(start)
        return cities[bestIdx]
    }

    companion object {
        fun fromCities(cities: List<GeoCity>): GeoIndex = GeoIndex(cities)

        /** Build from trimmed `name|alt,alt|lat|lon|country|admin` lines (blank/`#` lines skipped). */
        fun fromLines(lines: Sequence<String>): GeoIndex =
            GeoIndex(lines.mapNotNull { parseLine(it) }.toList())

        /** Parse one trimmed dataset line; null if malformed. */
        fun parseLine(line: String): GeoCity? {
            val s = line.trim()
            if (s.isEmpty() || s.startsWith("#")) return null
            val p = s.split('|')
            if (p.size < 4) return null
            val lat = p[2].toDoubleOrNull() ?: return null
            val lon = p[3].toDoubleOrNull() ?: return null
            val alts = if (p[1].isBlank()) emptyList()
                       else p[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            return GeoCity(
                name = p[0].trim(),
                altNames = alts,
                lat = lat,
                lon = lon,
                country = p.getOrElse(4) { "" }.trim(),
                admin1 = p.getOrElse(5) { "" }.trim()
            )
        }

        private fun toXyz(lat: Double, lon: Double): DoubleArray {
            val latR = Math.toRadians(lat)
            val lonR = Math.toRadians(lon)
            val cl = cos(latR)
            return doubleArrayOf(cl * cos(lonR), cl * sin(lonR), sin(latR))
        }

        private fun dist2(a: DoubleArray, b: DoubleArray): Double {
            val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
            return dx * dx + dy * dy + dz * dz
        }
    }
}
