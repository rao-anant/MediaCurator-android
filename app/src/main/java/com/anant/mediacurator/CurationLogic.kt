package com.anant.mediacurator

/**
 * Pure, platform-independent rules for the curation "walk-through" gate and the pinned bottom Hide
 * bar. Deliberately free of any Android dependency so the exact same rules can be:
 *   • unit-tested fast on the JVM (see CurationLogicTest), and
 *   • re-implemented verbatim on iOS (see docs/CURATION_REGRESSION_TESTS.md).
 *
 * The Activity layer owns the RecyclerView plumbing (when it is safe to evaluate, mapping adapter
 * positions, showing views); all the *decisions* live here.
 */

/** The four mutually-exclusive states of the pinned bottom bar for the currently open month. */
enum class HideBarKind {
    /** Green, tappable "Hide {Month}" — fully reviewed AND walked to the end. */
    HIDE,
    /** Amber "Delete junk… hide {Month} at the end" — reviewed but not yet scrolled to the end. */
    SCROLL_TEASER,
    /** Amber nudge toward an unreviewed section ("Also open WhatsApp…", "Turn on all filters…"). */
    REVIEW_HINT,
    /** No bar. */
    NONE,
}

object HideBarDecision {
    /**
     * Decide which bar to show for the open month.
     *
     * @param showHideButton   the month is fully reviewed: every required section has been seen
     *                         with all type filters on (the review-key gate).
     * @param reachedEnd       the user has walked to the end this session, OR the month was walked
     *                         on a prior visit at the same-or-greater item count (revisit skip).
     * @param scrollHintRetired the user has hidden their first month or dismissed the teaser — after
     *                         that, reviewed months jump straight to HIDE at the end with no teaser.
     * @param hasReviewHint    there is a still-unreviewed section worth nudging toward.
     */
    fun decide(
        showHideButton: Boolean,
        reachedEnd: Boolean,
        scrollHintRetired: Boolean,
        hasReviewHint: Boolean,
    ): HideBarKind = when {
        showHideButton && reachedEnd                            -> HideBarKind.HIDE
        showHideButton && !scrollHintRetired                    -> HideBarKind.SCROLL_TEASER
        !showHideButton && !scrollHintRetired && hasReviewHint  -> HideBarKind.REVIEW_HINT
        else                                                    -> HideBarKind.NONE
    }
}

/**
 * Revisit-skip rule for a fully walked-through month. Once the user has walked a month at N items,
 * a later visit skips the forced scroll UNLESS new items were added. Deleting items (fewer than
 * before) still counts as fully seen.
 */
object WalkedMonthRule {
    fun stillWalked(currentCount: Int, walkedCount: Int): Boolean = currentCount <= walkedCount
}

/**
 * The walk-through latch for the currently open month. A month counts as "reached end" only once
 * BOTH its header (top) and footer (bottom) have been seen since it was opened — so a long month
 * demands a real top-to-bottom scroll, while a month that fits shows both at once and qualifies
 * immediately.
 *
 * **Ordering rule (critical):** the footer only counts *after* the header has been seen at the top
 * in the current walk. This is what makes a freshly opened month that happens to land showing its
 * *bottom* (footer visible, header never seen — e.g. scroll position carried over from the previous
 * month) NOT count as reached. Without this ordering a short month would jump straight to "Hide" on
 * open; a long month never exposed the flaw because its footer is never on screen without scrolling.
 * We therefore never *seed* "header seen" — it is only ever set by an actual viewport observation.
 *
 * Two entry points, both driven by the Activity:
 *   • [onOpenedAtTop] — the month was just opened: begin a fresh walk (nothing seen yet).
 *   • [onViewport]    — re-evaluate against the current viewport, called ONLY when the list is
 *                       settled (not animating / mid-rebuild); the caller enforces that guard.
 *
 * The latch resets when the open month changes, or when the open month's rendered length changes
 * (a sub-group expand/collapse can insert items above, so the top must be re-seen).
 */
class WalkLatch {
    var monthKey: String? = null
        private set
    var span: Int = -1
        private set
    var seenHeader: Boolean = false
        private set
    var seenFooter: Boolean = false
        private set

    private val reached = HashSet<String>()

    fun isReached(key: String): Boolean = reached.contains(key)

    /** True while the open month is neither header- nor footer-complete — i.e. still being walked. */
    fun openMonth(): String? = monthKey

    /**
     * The month was just opened: begin a fresh walk. Nothing is marked seen yet — the header is only
     * counted once an actual viewport observation confirms it at the top (see the ordering rule in
     * the class docs), so an open that lands showing the month's bottom can't shortcut the gate.
     */
    fun onOpenedAtTop(key: String, span: Int) {
        monthKey = key
        this.span = span
        seenHeader = false
        seenFooter = false
        reached.remove(key)
    }

    /**
     * Evaluate the settled viewport for [openMonth]. Positions are adapter indices; [first]/[last]
     * are the first/last visible adapter positions (both must be valid, i.e. >= 0 — the caller
     * skips the call otherwise).
     *
     * Edge comparisons (top seen when the viewport top is at/above the header; bottom seen when the
     * viewport bottom is at/below the footer) are robust to a sticky-header overlay hiding the real
     * header item after the first scroll.
     */
    fun onViewport(openMonth: String, headerPos: Int, footerPos: Int, first: Int, last: Int) {
        val newSpan = footerPos - headerPos
        val monthChanged = openMonth != monthKey
        if (monthChanged || newSpan != span) {
            // Fresh walk: nothing seen yet. Neither the top nor the bottom is credited until an
            // actual observation confirms it — and the footer only after the header (ordering rule).
            monthKey = openMonth
            span = newSpan
            seenHeader = false
            seenFooter = false
            reached.remove(openMonth)
        }
        if (reached.contains(openMonth)) return
        if (first <= headerPos) seenHeader = true
        // Bottom only counts once the top has been seen in this walk — so an open that lands on the
        // month's footer (header never at the top) does not shortcut the gate.
        if (last >= footerPos && seenHeader) seenFooter = true
        if (seenHeader && seenFooter) reached.add(openMonth)
    }

    /** Drop the "reached" latch for one month (e.g. its item count changed). */
    fun clearReached(key: String) { reached.remove(key) }

    /** Full reset — used when curation progress is reset. */
    fun reset() {
        monthKey = null
        span = -1
        seenHeader = false
        seenFooter = false
        reached.clear()
    }
}
