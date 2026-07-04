package com.anant.mediacurator

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fast JVM regression tests for the pure curation rules. Each test maps to a scenario documented in
 * docs/CURATION_REGRESSION_TESTS.md — keep the two in sync so the iOS port can rely on both.
 *
 * Position model used throughout: a month occupies a contiguous range of adapter indices
 * [headerPos … footerPos]. `first`/`last` are the first/last visible adapter positions. "Header at
 * top" ⇒ first <= headerPos. "Footer on screen" ⇒ last >= footerPos.
 */
class CurationLogicTest {

    // ── WalkLatch: the walk-through gate ─────────────────────────────────────────────

    /** WL-1: A month that fits on screen (footer visible when opened at top) reaches end at once. */
    @Test
    fun fittingMonth_reachesEndImmediately() {
        val walk = WalkLatch()
        // Header at index 10, footer at 14 (tiny month). Opened & scrolled to top.
        walk.onOpenedAtTop("2024-03", span = 4)
        // Settled viewport: whole month visible (top at header, bottom past footer).
        walk.onViewport("2024-03", headerPos = 10, footerPos = 14, first = 10, last = 20)
        assertTrue(walk.isReached("2024-03"))
    }

    /** WL-2: A long month is NOT reached on open; only after the footer is scrolled into view. */
    @Test
    fun longMonth_requiresScrollToEnd() {
        val walk = WalkLatch()
        walk.onOpenedAtTop("2024-01", span = 185)  // header 0, footer 185
        // Landed at top: footer far below, not visible.
        walk.onViewport("2024-01", headerPos = 0, footerPos = 185, first = 0, last = 12)
        assertFalse("must not be reached before scrolling", walk.isReached("2024-01"))

        // User scrolls to the very bottom: footer now visible.
        walk.onViewport("2024-01", headerPos = 0, footerPos = 185, first = 170, last = 186)
        assertTrue("reached once footer seen", walk.isReached("2024-01"))
    }

    /**
     * WL-3 (the headline regression): after reaching the end of month A, opening a similarly-sized
     * month B must NOT be pre-marked reached. B lands at its top with its footer far below.
     */
    @Test
    fun openingAnotherMonthAfterReached_doesNotPreLatch() {
        val walk = WalkLatch()
        // Month A: walked to the end (header seen at top, then footer seen).
        walk.onOpenedAtTop("2024-01", span = 185)
        walk.onViewport("2024-01", headerPos = 0, footerPos = 185, first = 0, last = 12)
        walk.onViewport("2024-01", headerPos = 0, footerPos = 185, first = 170, last = 186)
        assertTrue(walk.isReached("2024-01"))

        // Open month B (A collapses). B landed at top, footer far below.
        walk.onOpenedAtTop("2024-02", span = 185)
        walk.onViewport("2024-02", headerPos = 0, footerPos = 185, first = 0, last = 12)

        assertFalse("B must require its own scroll", walk.isReached("2024-02"))
        assertTrue("A stays reached independently", walk.isReached("2024-01"))
    }

    /**
     * WL-3b (the real-device regression): a SHORT month B opened after A was reached can land showing
     * its own footer (scroll position carried over from A) — header never at the top. It must NOT
     * count as reached; only a genuine top→bottom pass does. (Long months hid this because their
     * footer is never on screen without scrolling.)
     */
    @Test
    fun openingShortMonthThatLandsOnItsFooter_doesNotLatch() {
        val walk = WalkLatch()
        // Month A (Jun 2000): walked to the end.
        walk.onOpenedAtTop("2000-06", span = 13)
        walk.onViewport("2000-06", headerPos = 0, footerPos = 13, first = 0, last = 5)
        walk.onViewport("2000-06", headerPos = 0, footerPos = 13, first = 8, last = 14)
        assertTrue(walk.isReached("2000-06"))

        // Open B (Mar 2001). It lands showing its BOTTOM: footer visible, header not at the top.
        walk.onOpenedAtTop("2001-03", span = 13)
        walk.onViewport("2001-03", headerPos = 20, footerPos = 33, first = 22, last = 34)
        assertFalse("footer-only landing must not latch", walk.isReached("2001-03"))

        // Scroll-to-top lands: header now at the top, footer no longer visible.
        walk.onViewport("2001-03", headerPos = 20, footerPos = 33, first = 20, last = 26)
        assertFalse("still needs a scroll to the end", walk.isReached("2001-03"))

        // User scrolls to the end → now reached.
        walk.onViewport("2001-03", headerPos = 20, footerPos = 33, first = 28, last = 34)
        assertTrue(walk.isReached("2001-03"))
    }

    /**
     * WL-4: a same-month length change (sub-group expand/collapse inserts rows above) invalidates a
     * prior "reached" — the top must be re-seen before Hide is offered again.
     */
    @Test
    fun spanChange_invalidatesReached() {
        val walk = WalkLatch()
        walk.onOpenedAtTop("2024-01", span = 40)
        walk.onViewport("2024-01", headerPos = 0, footerPos = 40, first = 0, last = 10)  // header seen
        walk.onViewport("2024-01", headerPos = 0, footerPos = 40, first = 30, last = 41) // footer seen → reached
        assertTrue(walk.isReached("2024-01"))

        // Sub-group expands → month is longer now; viewport happens to sit mid-month (top not seen).
        walk.onViewport("2024-01", headerPos = 0, footerPos = 80, first = 20, last = 45)
        assertFalse("span change re-arms the gate", walk.isReached("2024-01"))
    }

    /** WL-5: after a span change, a footer glimpsed without the header being re-seen is not enough —
     *  the header must be seen at the top first (ordering rule). */
    @Test
    fun footerSeenButHeaderNot_isNotReached() {
        val walk = WalkLatch()
        walk.onViewport("2024-05", headerPos = 0, footerPos = 50, first = 0, last = 10)  // opens, header seen
        walk.onViewport("2024-05", headerPos = 0, footerPos = 60, first = 40, last = 61) // span change → re-armed; only footer seen
        assertFalse("header must be re-seen after a span change", walk.isReached("2024-05"))
    }

    /** WL-6: reset() clears everything (used when curation progress is reset from Settings). */
    @Test
    fun reset_clearsAllWalkState() {
        val walk = WalkLatch()
        walk.onOpenedAtTop("2024-01", span = 20)
        walk.onViewport("2024-01", headerPos = 0, footerPos = 20, first = 0, last = 25)
        assertTrue(walk.isReached("2024-01"))

        walk.reset()
        assertFalse(walk.isReached("2024-01"))
        assertEquals(null, walk.openMonth())
    }

    // ── HideBarDecision: which bar shows ─────────────────────────────────────────────

    /** HB-1: fully reviewed AND reached the end → the green Hide bar. */
    @Test
    fun reviewed_andReached_showsHide() {
        assertEquals(
            HideBarKind.HIDE,
            HideBarDecision.decide(showHideButton = true, reachedEnd = true, scrollHintRetired = false, hasReviewHint = false)
        )
    }

    /** HB-2: reviewed but not yet at the end (teaser not retired) → the scroll teaser. */
    @Test
    fun reviewed_notReached_showsTeaser() {
        assertEquals(
            HideBarKind.SCROLL_TEASER,
            HideBarDecision.decide(showHideButton = true, reachedEnd = false, scrollHintRetired = false, hasReviewHint = false)
        )
    }

    /** HB-3: once the teaser is retired, a reviewed-but-not-reached month shows nothing. */
    @Test
    fun reviewed_notReached_retired_showsNothing() {
        assertEquals(
            HideBarKind.NONE,
            HideBarDecision.decide(showHideButton = true, reachedEnd = false, scrollHintRetired = true, hasReviewHint = false)
        )
    }

    /** HB-4: not fully reviewed, with a section to nudge toward → the review hint. */
    @Test
    fun notReviewed_withHint_showsReviewHint() {
        assertEquals(
            HideBarKind.REVIEW_HINT,
            HideBarDecision.decide(showHideButton = false, reachedEnd = false, scrollHintRetired = false, hasReviewHint = true)
        )
    }

    /** HB-5: not reviewed and nothing to nudge toward → nothing. */
    @Test
    fun notReviewed_noHint_showsNothing() {
        assertEquals(
            HideBarKind.NONE,
            HideBarDecision.decide(showHideButton = false, reachedEnd = false, scrollHintRetired = false, hasReviewHint = false)
        )
    }

    /** HB-6: a retired teaser never suppresses the actual Hide once the end is reached. */
    @Test
    fun retired_stillShowsHideWhenReached() {
        assertEquals(
            HideBarKind.HIDE,
            HideBarDecision.decide(showHideButton = true, reachedEnd = true, scrollHintRetired = true, hasReviewHint = false)
        )
    }

    // ── WalkedMonthRule: revisit-skip by item count ──────────────────────────────────

    @Test
    fun walkedMonth_sameCount_staysWalked() {
        assertTrue(WalkedMonthRule.stillWalked(currentCount = 50, walkedCount = 50))
    }

    @Test
    fun walkedMonth_fewerItems_staysWalked() {
        // User deleted some items on the prior visit — still fully seen.
        assertTrue(WalkedMonthRule.stillWalked(currentCount = 47, walkedCount = 50))
    }

    @Test
    fun walkedMonth_moreItems_requiresRewalk() {
        // New photos arrived → must walk again.
        assertFalse(WalkedMonthRule.stillWalked(currentCount = 53, walkedCount = 50))
    }

    // ── Reset coverage: exactly the curation keys, nothing unrelated ─────────────────

    @Test
    fun resetKeys_coverAllCurationState() {
        val keys = PreferencesManager.CURATION_PROGRESS_KEYS
        listOf(
            "done_months", "seen_review_keys", "walked_months",
            "scroll_hint_retired", "seen_hide_coach", "seen_onboarding",
            "last_hidden_month", "last_viewed_month",
            "expanded_years", "expanded_months", "expanded_subgroups",
        ).forEach { assertTrue("reset must clear $it", keys.contains(it)) }
    }

    @Test
    fun resetKeys_leaveUnrelatedSettingsAlone() {
        val keys = PreferencesManager.CURATION_PROGRESS_KEYS
        listOf(
            "sort_mode", "include_photo", "include_video", "include_pdf", "include_audio",
            "pdf_content_search", "photo_duplicate_detection",
            "all_files_prompt_shown", "hidden_restore_offered", "last_deleted_batch",
        ).forEach { assertFalse("reset must NOT touch $it", keys.contains(it)) }
    }
}
