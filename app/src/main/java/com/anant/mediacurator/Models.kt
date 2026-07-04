package com.anant.mediacurator

import java.io.Serializable

enum class MediaType {
    IMAGE, VIDEO, PDF, AUDIO
}

enum class SortMode {
    DATE_NEWEST, DATE_OLDEST, SIZE_ABSOLUTE, SIZE_WITHIN_MONTH, COUNT_PER_MONTH
}

data class MediaStats(
    // Counts: visible = in non-hidden months (all types, ignores chip filter)
    val visiblePhotos: Int, val hiddenPhotos: Int, val totalPhotos: Int,
    val visibleVideos: Int, val hiddenVideos: Int, val totalVideos: Int,
    val visiblePdfs:   Int, val hiddenPdfs:   Int, val totalPdfs:   Int,
    val visibleAudios: Int, val hiddenAudios: Int, val totalAudios: Int,
    // Sizes in bytes
    val visiblePhotoBytes: Long, val hiddenPhotoBytes: Long,
    val visibleVideoBytes: Long, val hiddenVideoBytes: Long,
    val visiblePdfBytes:   Long, val hiddenPdfBytes:   Long,
    val visibleAudioBytes: Long, val hiddenAudioBytes: Long,
    // Integrity
    val integrityOk: Boolean,
    val integrityDetail: String
)

data class MediaItem(
    val id: Long,
    val uri: String,
    val volume: String,
    val dateTaken: Long,
    val displayName: String,
    val size: Long,
    val type: MediaType,
    val duration: Long = 0,
    val relativePath: String = ""
) : Serializable {
    val isWhatsApp: Boolean get() = relativePath.contains("whatsapp", ignoreCase = true)
}

/**
 * A set of photos that are exact duplicates (same MD5 hash).
 * [keepIndex] is the index of the copy the user wants to KEEP; all others are marked for deletion.
 * Mutable so the user can tap to change which copy to keep in the review UI.
 */
data class DuplicateGroup(
    val md5: String,
    val items: List<MediaItem>,
    var keepIndex: Int = 0
) {
    /** Bytes that would be freed by deleting all but the kept copy. */
    val reclaimableBytes: Long get() = items.sumOf { it.size } - items[keepIndex].size
}

data class MonthGroup(
    val year: Int,
    val month: Int,
    val key: String,
    val label: String,
    val items: MutableList<MediaItem> = mutableListOf()
) : Serializable

sealed class GalleryItem {
    // structuralVersion signals the Adapter that a full UI reset is needed (notifyDataSetChanged)
    // to bypass expensive O(N^2) DiffUtil move calculations during large list changes like Sorting.
    abstract val structuralVersion: Int

    data class YearHeader(
        val year: Int,
        val totalItems: Int,
        val totalBytes: Long,
        val isExpanded: Boolean,
        val photoCount: Int = 0,
        val videoCount: Int = 0,
        val pdfCount: Int = 0,
        override val structuralVersion: Int = 0,
        val previewUris: List<String> = emptyList(),
        val curatedPct: Int = 0,       // % of months hidden (0 = nothing curated yet)
        val audioCount: Int = 0
    ) : GalleryItem()

    data class Header(
        val monthKey: String,
        val label: String,
        val count: Int,
        val totalBytes: Long = 0L,
        val isExpanded: Boolean = false,
        val photoCount: Int = 0,
        val videoCount: Int = 0,
        val pdfCount: Int = 0,
        override val structuralVersion: Int = 0,
        val audioCount: Int = 0
    ) : GalleryItem()

    data class Media(
        val mediaItem: MediaItem,
        val monthKey: String,
        val indexInMonth: Int,
        // Non-null only in flat (SIZE_ABSOLUTE) mode — shown as a badge on the thumbnail
        // so the user knows which month each item belongs to without tree headers.
        val dateLabel: String? = null,
        override val structuralVersion: Int = 0
    ) : GalleryItem()

    data class SubHeader(
        val subKey: String,       // e.g. "2024-03:cam" or "2024-03:wa"
        val monthKey: String,
        val label: String,        // "Camera & Others" or "WhatsApp"
        val count: Int,
        val totalBytes: Long = 0L,
        val isExpanded: Boolean = false,
        val photoCount: Int = 0,
        val videoCount: Int = 0,
        val pdfCount: Int = 0,
        override val structuralVersion: Int = 0,
        val audioCount: Int = 0
    ) : GalleryItem()

    data class Footer(
        val monthKey: String,
        override val structuralVersion: Int = 0,
        val showHideButton: Boolean = false,
        // Per-sub-group review state, so the bottom hint can name what's left to review.
        val camPresent: Boolean = false,
        val camReviewed: Boolean = false,
        val waPresent: Boolean = false,
        val waReviewed: Boolean = false,
        // Full (unfiltered) item count — stable across chip toggles; used to detect whether new
        // photos were added since the month was last "walked through" (revisit-skips-scroll guard).
        val fullCount: Int = 0
    ) : GalleryItem()
}
