# Curation Regression Tests — Plain-English Spec

This document describes the behavioural rules of the curation "Hide month" flow in plain language,
so they can be re-implemented and re-tested on **any platform** (the Android tests live in
`app/src/test/java/com/anant/mediacurator/CurationLogicTest.kt`; this doc is the source of truth for
the iOS port).

Each rule has an ID (e.g. `WL-3`) that matches a test case. When you change behaviour, update **both**
this doc and the tests.

---

## 0. Background / mental model

The gallery is a tree: **Year → Month → items**. Only one month is expanded at a time (accordion).
The core action is **Hide month**: the user reviews a month, then hides it so it stops cluttering the
gallery (files are never deleted — hiding is app-only).

Before the app offers the green **"Hide {Month}"** button, the user must have:

1. **Reviewed** the month — seen every required section (e.g. Camera and WhatsApp sub-groups) with all
   media-type filters on. This is the *review gate* (`showHideButton`).
2. **Walked through** the month — actually scrolled from its top to its bottom, so they pass every
   item. This is the *walk gate* (`reachedEnd`). A month that fits entirely on screen satisfies this
   immediately; a long month requires a real scroll to the end.

The intent of the walk gate: by forcing the user to scroll the whole month, they're likely to spot and
delete junk along the way.

### Position model (used by all walk-gate rules)

A month occupies a contiguous range of list positions: `headerPos … footerPos` (header row at the top,
an invisible footer row at the bottom). The visible window is described by:

- `first` = index of the first visible row, `last` = index of the last visible row.
- **"Header is at the top"** ⇔ `first <= headerPos`.
- **"Footer is on screen"** ⇔ `last >= footerPos`.

Edge comparisons (`<=`, `>=`) are used rather than equality because a sticky/pinned header overlay can
hide the real header row after the first scroll.

---

## 1. Walk-through gate (`WalkLatch`)

State per open month: `seenHeader`, `seenFooter`, and a set of months already marked `reached`.

> **A month is `reached` (walked through) once BOTH its header and its footer have been seen since it
> was opened — and the footer only counts once the header has already been seen (ordering rule).**

**Ordering rule (critical — the real-device bug):** never *assume/seed* "header seen" when a month
opens. Credit the header only from an actual observation that the header is at the top, and credit the
footer only *after* the header has been credited in this walk. Why: a freshly opened month can land
showing its own **bottom** (footer visible, header never at the top) when the scroll position carries
over from the previous month. If opening seeded "header seen", that footer would instantly satisfy the
gate and jump to "Hide". Long months masked this (their footer is never on screen without scrolling);
short months exposed it.

Two events drive it:

- **Opened**: the month was just tapped open. Begin a fresh walk — **nothing** seen yet — and drop any
  old `reached` mark. The header becomes "seen" only when a later observation confirms it at the top.
- **Viewport-evaluated** (only when the list is *settled* — see §3): recompute from the current window:
  - header seen ⇔ (at any point in this walk) `first <= headerPos`;
  - footer seen ⇔ `last >= footerPos` **and** the header has already been seen;
  - `reached` ⇔ header seen **and** footer seen.

Reset conditions inside viewport evaluation:
- If the **open month changed**, or the **month's rendered length changed** (a sub-group expanded/
  collapsed, inserting rows), start a fresh walk (both flags cleared). The top must be (re-)seen from
  an actual observation before the footer can count — this also handles the sub-group case where new
  rows appear above and the user must scroll back up.

| ID | Scenario | Expected |
|----|----------|----------|
| **WL-1** | Month fits on screen: opened at top, footer already visible. | `reached` **immediately**. |
| **WL-2** | Long month: opened at top, footer far below. Then user scrolls until footer visible. | **Not** reached on open; reached **only after** the footer is scrolled into view. |
| **WL-3** | User reaches the end of month A, then opens a similar-sized long month B (A collapses, B lands at its top with footer far below). | B is **not** reached (needs its own scroll); A stays reached independently. |
| **WL-3b** | User reaches the end of month A, then opens a **short** month B that lands showing its own **footer** (header never at the top). | B is **not** reached from that footer-only landing; only a genuine top→bottom pass reaches it. ⚠️ *This is the real-device bug — B jumped straight to "Hide B".* |
| **WL-4** | A reached month's length changes (sub-group expand inserts rows), leaving the viewport mid-month. | `reached` is **cleared** — the gate re-arms. |
| **WL-5** | After a length change, only the footer is visible (top never re-seen). | **Not** reached — both edges must be seen. |
| **WL-6** | `reset()` (curation progress reset). | All walk state cleared; no month is `reached`. |

**Revisit shortcut** (`WalkedMonthRule`): once a month has been fully walked, its item count is
remembered. On a later visit the walk is skipped **unless new items appeared**:

| ID | Prior walked count | Current count | Still counts as walked? |
|----|--------------------|---------------|-------------------------|
| — | 50 | 50 (unchanged) | **Yes** |
| — | 50 | 47 (user deleted some) | **Yes** (fewer is still "fully seen") |
| — | 50 | 53 (new photos added) | **No** — must walk again |

Rule: `stillWalked = currentCount <= walkedCount`.

---

## 2. Bottom-bar decision (`HideBarDecision`)

Given four booleans, exactly one bar state is shown:

- `showHideButton` — the month is fully **reviewed**.
- `reachedEnd` — the month is **walked** (this session) **or** the revisit shortcut applies.
- `scrollHintRetired` — the user has hidden their first month or dismissed the teaser (after that,
  reviewed months jump straight to Hide at the end, no teaser).
- `hasReviewHint` — there's a still-unreviewed section worth nudging toward.

Decision table (first matching row wins):

| ID | showHideButton | reachedEnd | scrollHintRetired | hasReviewHint | Result |
|----|:--:|:--:|:--:|:--:|--------|
| **HB-1** | ✓ | ✓ | – | – | **HIDE** (green "Hide {Month}") |
| **HB-2** | ✓ | ✗ | ✗ | – | **SCROLL_TEASER** ("Delete junk… hide {Month} at the end") |
| **HB-3** | ✓ | ✗ | ✓ | – | **NONE** |
| **HB-4** | ✗ | – | ✗ | ✓ | **REVIEW_HINT** ("Also open WhatsApp…" / "Turn on all filters…") |
| **HB-5** | ✗ | – | ✗ | ✗ | **NONE** |
| **HB-6** | ✓ | ✓ | ✓ | – | **HIDE** (a retired teaser never blocks the real Hide) |

---

## 3. UI-layer guards (must be re-implemented, can't be a pure function)

These aren't pure logic but are **essential** — every curation bug this session came from getting one
of them wrong. The iOS list view must honour them:

- **G-1 — Never evaluate the walk gate against a list that is animating or mid-rebuild.**
  When a month opens, the accordion animates rows in; for a few frames the new month's footer sits
  right below its header (only a few rows laid out), which would falsely satisfy "footer on screen"
  and latch `reached`. Only run the viewport evaluation once item animations have finished and layout
  is settled. *(This is what makes WL-3 hold in practice.)*

- **G-2 — Scroll a freshly opened month to its top using a layout callback, not a fixed delay.**
  After opening a month, wait for the next layout pass (Android: `doOnPreDraw`), then scroll its header
  to the top. A blind timer (e.g. "120 ms") lands on the wrong position on a slow frame. Re-establish
  the walk from the top at that point (opened-at-top event).

- **G-3 — Refresh the bar after animations settle.**
  After each list rebuild, re-run the bar decision once animations complete (Android:
  `itemAnimator.isRunning { … }`). A month that genuinely fits still gets Hide right away; a long month
  correctly shows the teaser until the user actually scrolls.

- **G-4 — Resetting progress must also drop in-memory state, not just persisted state.**
  "Reset progress" is reachable from the gallery's own menu, so the gallery (and its in-memory review /
  expansion / walk state) may still be alive. On return, re-seed all in-memory sets from the now-empty
  store and clear the walk latch — otherwise the stale in-memory "reviewed" marks reappear **and get
  re-saved**, silently undoing the reset. (Android uses a process-wide `CurationResetSignal` epoch the
  gallery consumes on resume.)

---

## 4. Reset coverage

"Reset curation progress" must clear exactly the curation keys and **nothing else**.

**Must clear:** hidden months, reviewed markers, walked markers, the scroll-hint-retired flag, the
Hide coach-mark flag, the onboarding/demo opt-out flag, last-hidden-month, last-viewed-month, and the
expanded years/months/sub-groups.

**Must NOT touch:** sort mode, media-type filters (photo/video/pdf/audio), PDF-content-search and
duplicate-detection toggles, the all-files/hidden-restore prompt flags, and the last-deleted-batch
(quick-undo).

After a reset, a previously hidden month reappears un-hidden, and a previously reviewed/walked month
requires reviewing and scrolling again (subject to §1 — a small month that fits still qualifies at
once).

---

## 5. First-run access + demo ordering (flow regression)

On the very first launch the order must be: **media-permission request → all-files-access request →
mandatory demo → home/restore**. The demo must never appear before the access prompts. The demo is
mandatory (not dismissable) until the user ticks "Don't show again"; that opt-out is cleared by a
data-clear/reinstall so a fresh install shows the demo again. Reset progress also re-enables the demo.
