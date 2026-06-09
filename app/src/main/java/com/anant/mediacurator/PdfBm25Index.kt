package com.anant.mediacurator

import kotlin.math.ln

/**
 * In-memory BM25 index over the indexed PDF collection.
 *
 * Built once from the word-count files loaded by [PdfIndexStore.loadAll] and
 * cached in the ViewModel until the index changes (new PDF added, PDF deleted).
 *
 * BM25 formula per term t in document d
 * ──────────────────────────────────────
 *   IDF(t)      = ln((N − df(t) + 0.5) / (df(t) + 0.5) + 1)
 *   TF-norm(t,d) = tf(t,d) × (k1+1) / (tf(t,d) + k1 × (1 − b + b × |d|/avgdl))
 *   score(t,d)  = IDF(t) × TF-norm(t,d)
 *
 * Tuning parameters (standard Okapi BM25 defaults)
 *   k1 = 1.2  — controls term-frequency saturation
 *   b  = 0.75 — length normalisation strength
 *
 * @param entries  Map of mediaId → word-frequency map (from PdfIndexStore.loadAll).
 *                 Empty maps (scanned PDFs) contribute to [docCount] but not to the
 *                 inverted index — their IDF effect correctly inflates rare-term scores.
 */
class PdfBm25Index(entries: Map<Long, Map<String, Int>>) {

    companion object {
        private const val K1 = 1.2f
        private const val B  = 0.75f
    }

    /** word → (docId → termFreq) */
    private val invertedIndex = HashMap<String, HashMap<Long, Int>>()

    /** docId → total word count (= sum of all term frequencies for that document) */
    private val docLengths = HashMap<Long, Int>()

    /** Total number of indexed documents (including empty / scanned PDFs). */
    val docCount: Int = entries.size

    /** Average document length across all non-empty documents. */
    private val avgDocLength: Float

    /** True when no PDFs have been indexed yet. */
    val isEmpty: Boolean get() = docCount == 0

    init {
        var totalLength = 0L
        var nonEmptyCount = 0
        for ((docId, wordCounts) in entries) {
            val len = wordCounts.values.sum()
            docLengths[docId] = len
            if (len > 0) {
                totalLength += len
                nonEmptyCount++
            }
            for ((word, tf) in wordCounts) {
                invertedIndex.getOrPut(word) { HashMap() }[docId] = tf
            }
        }
        avgDocLength = if (nonEmptyCount > 0) totalLength.toFloat() / nonEmptyCount else 1f
    }

    /**
     * Compute per-token BM25 scores for all matching documents.
     *
     * Returns: token → (docId → BM25 component score)
     *
     * Only documents that contain the term have entries in the inner map (tf > 0).
     * A score of 0 for a token means that token is absent from the entire collection.
     *
     * Tokens that don't appear in any PDF map to an empty inner map, which the
     * caller uses to determine "no content match" for that token.
     */
    fun scorePerToken(tokens: List<String>): Map<String, Map<Long, Float>> {
        if (isEmpty) return tokens.associateWith { emptyMap() }

        val result = HashMap<String, Map<Long, Float>>(tokens.size)
        for (token in tokens) {
            val postings = invertedIndex[token.lowercase()]
            if (postings.isNullOrEmpty()) {
                result[token] = emptyMap()
                continue
            }

            val df  = postings.size
            val idf = ln((docCount - df + 0.5) / (df + 0.5) + 1.0).toFloat()

            val scores = HashMap<Long, Float>(postings.size)
            for ((docId, tf) in postings) {
                val dl     = docLengths[docId]?.toFloat() ?: avgDocLength
                val tfNorm = tf * (K1 + 1f) / (tf + K1 * (1f - B + B * dl / avgDocLength))
                scores[docId] = idf * tfNorm
            }
            result[token] = scores
        }
        return result
    }
}
