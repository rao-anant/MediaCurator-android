package com.anant.mediacurator

/**
 * Fuzzy search over filenames and PDF content (BM25).
 *
 * (On-device ML image labeling was removed — its generic labels were too noisy,
 * tagging unrelated images, and the feature was not advertised.)
 *
 * Algorithm
 * ---------
 * 1. Tokenise the query into words.
 * 2. For each media item, score it against every query token:
 *    - **Filename search** : the base-name (no extension) is split on
 *      non-alphanumeric characters; each part is matched against the token.
 *    - **PDF content search**: the BM25 inverted index is looked up for
 *      each token; a non-zero result means the term exists in the PDF.
 * 3. Scoring per (token, candidate) pair uses Optimal String Alignment (OSA)
 *    distance, which counts transpositions as a single edit — so "flwoer"
 *    (transposed 'w'/'o') is 1 edit away from "flower" and matches.
 * 4. An item passes if *every* query token has at least one match ≥ threshold,
 *    ensuring multi-word queries only return items matching both terms.
 *
 * Edit-distance budget by token length
 * -------------------------------------
 *  ≤ 3 chars  → exact match only     (avoids "dog" matching "log", "fog"…)
 *  4–6 chars  → 1 edit allowed
 *  ≥ 7 chars  → 2 edits allowed      ("flwoer" → "flower" ✓)
 *
 * PDF content search
 * ------------------
 * When a [PdfBm25Index] is provided, PDF items are additionally scored against
 * indexed word-frequency data using BM25 (Okapi BM25).  Each query token is
 * looked up independently in the inverted index; ALL tokens must appear in the
 * PDF for it to pass (AND logic matches the label/filename behaviour).
 * The final score is normalised to [0, 1] using a saturation constant.
 */
object SearchEngine {

    data class Result(
        val item: MediaItem,
        /** Short human-readable reason shown as badge on the thumbnail. */
        val matchReason: String,
        val score: Float
    )

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Minimum effective score for a result to be included.
     *
     * Effective score = fuzzyMatchScore  (for filename hits)
     *                 = 0.75f (fixed)    (for PDF content hits)
     *
     * Examples at this threshold (0.65):
     *   filename exact match      → 1.0          = 1.0  ✓ included
     *   filename fuzzy 1-edit     → 0.70          = 0.70 ✓ included
     *   PDF content match         → 0.75 (fixed)  = 0.75 ✓ included
     */
    private const val MIN_SCORE = 0.65f

    /**
     * BM25 saturation constant: a per-token raw BM25 score at or above this value
     * is mapped to a normalised score of 1.0.  Typical well-matching per-token
     * BM25 scores for a 1 000-document collection land in the 2–5 range; 3.0 is a
     * conservative saturation point that gives most matches a score in [0.4, 1.0].
     */
    private const val BM25_SCORE_SCALE = 3.0f

    fun search(
        query: String,
        allMedia: List<MediaItem>,
        bm25Index: PdfBm25Index? = null,
        // media id → offline reverse-geocoded place names (city + aliases + region) for Place search.
        placeIndex: Map<Long, List<String>>? = null
    ): List<Result> {
        val tokens = tokenise(query)
        if (tokens.isEmpty()) return emptyList()

        // Pre-compute BM25 scores once for all tokens (avoids repeated index traversal)
        val pdfTokenBm25: Map<String, Map<Long, Float>> =
            if (bm25Index != null && !bm25Index.isEmpty) bm25Index.scorePerToken(tokens)
            else emptyMap()

        val results = mutableListOf<Result>()

        for (item in allMedia) {
            val fileTokens = filenameTokens(item.displayName)
            val placeNames = placeIndex?.get(item.id).orEmpty()   // reverse-geocoded city + aliases
            val placeWords = placeWordTokens(placeNames)

            val matchedFileparts = mutableListOf<String>()
            var totalScore       = 0f
            var allTokensMatched = true
            var pdfBm25Total     = 0f   // accumulated BM25 contribution across tokens
            var placeMatched     = false

            for (token in tokens) {
                var bestScore    = 0f
                var bestFilePart = ""
                var bestIsPlace  = false

                // --- filename token candidates: no confidence weight ---
                // Skip purely-numeric query tokens for photos/videos — they match the long
                // digit runs in auto-generated camera names (IMG_105734018575) as pure noise.
                // PDFs still allow numeric filename matches (e.g. "invoice_401.pdf"), and PDF
                // content is matched separately via BM25 below.
                val numericToken = token.all { it.isDigit() }
                if (item.type == MediaType.PDF || !numericToken) {
                    for (part in fileTokens) {
                        val s = fuzzyScore(token, part)
                        if (s > bestScore) { bestScore = s; bestFilePart = part; bestIsPlace = false }
                    }
                }

                // --- place candidates: offline reverse-geocoded city name + aliases + region ---
                // Matched like filename words, so "bengaluru"/"bangalore"/"karnataka" all hit.
                for (w in placeWords) {
                    val s = fuzzyScore(token, w)
                    if (s > bestScore) { bestScore = s; bestFilePart = ""; bestIsPlace = true }
                }

                // --- PDF content: BM25 inverted-index lookup ---
                // Only checked when label/filename/place didn't already clear the threshold.
                if (bestScore < MIN_SCORE && item.type == MediaType.PDF) {
                    val bm25Component = pdfTokenBm25[token]?.get(item.id) ?: 0f
                    if (bm25Component > 0f) {
                        // Term present in the BM25 index for this PDF → content match
                        bestScore    = 0.75f   // passes MIN_SCORE; final rank from pdfBm25Total
                        bestFilePart = ""
                        bestIsPlace  = false
                        pdfBm25Total += bm25Component
                    }
                }

                // Every query token must clear the threshold independently (AND logic)
                if (bestScore < MIN_SCORE) { allTokensMatched = false; break }

                totalScore += bestScore
                when {
                    bestIsPlace               -> placeMatched = true
                    bestFilePart.isNotEmpty() -> matchedFileparts.add(bestFilePart)
                }
            }

            if (!allTokensMatched) continue

            // Use normalised BM25 total as the score for pure content matches so that
            // PDFs with more / rarer term occurrences rank above weaker matches.
            val avgScore = if (pdfBm25Total > 0f && matchedFileparts.isEmpty() && !placeMatched) {
                minOf(pdfBm25Total / (tokens.size * BM25_SCORE_SCALE), 1.0f)
            } else {
                totalScore / tokens.size
            }

            val pdfContentMatch = pdfBm25Total > 0f
            val reason = buildReason(
                matchedFileparts, item.displayName, pdfContentMatch,
                if (placeMatched) placeNames.firstOrNull() else null
            )
            results.add(Result(item, reason, avgScore))
        }

        return results.sortedWith(compareByDescending<Result> { it.score }.thenByDescending { it.item.dateTaken })
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Split query on whitespace; lowercase; drop blanks and single-character noise. */
    private fun tokenise(query: String): List<String> =
        query.trim().lowercase().split(Regex("\\s+")).filter { it.length >= 2 }

    /**
     * Generic auto-generated filename tokens that carry no search meaning and would
     * otherwise match thousands of files (every camera photo, every video).  Searching
     * "IMG" or "jpg" should not return the whole library.  (Meaningful words like
     * "screenshot" are deliberately NOT here — users may want to find those.)
     */
    private val FILENAME_STOPWORDS = setOf(
        "img", "image", "vid", "video", "dsc", "dscn", "pxl", "mvimg",
        "jpg", "jpeg", "png", "gif", "heic", "heif", "webp",
        "mp4", "mov", "3gp", "mpeg", "mkv", "wa", "dcim"
    )

    /**
     * Split a filename into searchable parts, dropping generic auto-generated tokens.
     * "IMG_20230415_beach_trip.jpg" → ["20230415", "beach", "trip"]  ("img" dropped)
     */
    private fun filenameTokens(displayName: String): List<String> {
        val base = displayName.substringBeforeLast('.').lowercase()
        return base.split(Regex("[^a-z0-9]+"))
            .filter { it.length >= 2 && it !in FILENAME_STOPWORDS }
    }

    /**
     * Score [token] against [candidate]; returns 0 if no match, >0 if a match.
     *
     * Score bands:
     *  1.00 — exact match
     *  0.90 — candidate contains token as a substring
     *  0.85 — candidate starts with token (prefix)
     *  0.70 — 1-edit OSA distance  (length ≥ 4)
     *  0.55 — 2-edit OSA distance  (length ≥ 7)
     *  0.00 — no match
     */
    fun fuzzyScore(token: String, candidate: String): Float {
        val t = token.lowercase()
        val c = candidate.lowercase()

        if (t == c)           return 1.00f
        if (c.contains(t))    return 0.90f
        if (c.startsWith(t))  return 0.85f

        val budget = editBudget(t.length)
        if (budget == 0) return 0f

        val dist = osaDistance(t, c)
        return when {
            dist <= budget && dist == 1 -> 0.70f
            dist <= budget && dist == 2 -> 0.55f
            else                        -> 0f
        }
    }

    private fun editBudget(tokenLen: Int) = when {
        tokenLen <= 3 -> 0   // "dog", "cat" — exact match only; 1-edit opens too many false positives
        tokenLen <= 6 -> 1   // "flwoer" → "flower" ✓  (OSA distance 1)
        else          -> 2   // longer words tolerate 2 edits
    }

    /**
     * Optimal String Alignment distance (bounded).
     * Handles transpositions (e.g. "flwoer" ↔ "flower" = 1).
     * Returns early once the distance exceeds [maxDist] to stay fast.
     */
    private fun osaDistance(a: String, b: String, maxDist: Int = 2): Int {
        val m = a.length; val n = b.length
        // Quick bound: length difference alone can exceed budget
        if (kotlin.math.abs(m - n) > maxDist) return maxDist + 1

        val d = Array(m + 1) { IntArray(n + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..n) d[0][j] = j

        for (i in 1..m) {
            var rowMin = Int.MAX_VALUE
            for (j in 1..n) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                d[i][j] = minOf(
                    d[i - 1][j] + 1,        // deletion
                    d[i][j - 1] + 1,        // insertion
                    d[i - 1][j - 1] + cost  // substitution
                )
                // Transposition
                if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                    d[i][j] = minOf(d[i][j], d[i - 2][j - 2] + cost)
                }
                if (d[i][j] < rowMin) rowMin = d[i][j]
            }
            if (rowMin > maxDist) return maxDist + 1  // prune
        }
        return d[m][n]
    }

    private fun buildReason(
        fileParts: List<String>,
        fullFilename: String,
        pdfContentMatch: Boolean = false,
        place: String? = null
    ): String {
        return when {
            fileParts.isNotEmpty() ->
                "≈ ${fullFilename.substringBeforeLast('.').take(20)}"
            pdfContentMatch ->
                "📄 content (first 5 pg)"
            place != null ->
                "📍 $place"
            else -> ""
        }
    }

    /** Lowercased word tokens from a place's names (city + aliases + region), for fuzzy matching. */
    private fun placeWordTokens(names: List<String>): List<String> =
        names.flatMap { it.lowercase().split(Regex("[^\\p{L}\\p{Nd}]+")) }
             .filter { it.length >= 2 }
             .distinct()
}
