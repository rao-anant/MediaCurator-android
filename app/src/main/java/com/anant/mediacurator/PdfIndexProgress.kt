package com.anant.mediacurator

/**
 * Progress snapshot for the background PDF indexing job.
 *
 * - [indexed]  — number of PDFs processed so far
 * - [total]    — total PDFs that need indexing this run
 * - [isDone]   — true when the job has completed (success or cancelled)
 *
 * A null value from the LiveData means "no indexing has started (or is needed)".
 */
data class PdfIndexProgress(
    val indexed: Int,
    val total: Int,
    val isDone: Boolean
) {
    /** True while actively indexing (total > 0 and not yet done). */
    val isActive: Boolean get() = !isDone && total > 0
}
