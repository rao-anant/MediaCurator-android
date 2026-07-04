# MediaCurator — Functional Specification

This document describes the **behavior** of the Android app, screen by screen, so the iOS
version can match it. It is deliberately UX-level: it captures what the user sees and does,
every dialog/toast/snackbar, button visibility rules, and edge cases — **not** the Kotlin
implementation.

Where a behavior depends on something iOS doesn't have (Android MediaStore, "All files
access", the OS recycle bin, `Intent` choosers), it's called out in a **▶ iOS note**.

---

## 0. The core concept

MediaCurator is a **photo/video/audio/PDF curation tool**, not just a gallery. Its purpose is
to help the user work through their library *once* and never re-review the same items:

- The library is grouped **Year → Month** (and within a month, optionally **Camera & Others**
  vs **WhatsApp** sub-groups).
- When the user finishes reviewing a month, they **Hide** it. Hidden months leave the app's
  view but **stay in the phone's real gallery** (nothing is deleted). Hidden state persists.
  This is the single most important concept users miss, so it is taught up front with a
  first-run **animated explainer** (see §13) and reinforced with an in-context coach-mark.
- **Wording note:** "gallery" in UI copy refers **only to the phone's own gallery** (where files
  truly remain). When describing what happens *inside MediaCurator*, copy says **"this app"** —
  never "gallery" — so users never think the app is touching/clearing their real gallery.
- Deletions are **soft** — items go to a recoverable **Trash** with multiple undo paths.
- Everything is **on-device**: no network, accounts, ads, or analytics. Search/PDF text/dup
  detection are all local.

Four media types throughout: **IMAGE, VIDEO, AUDIO, PDF**.

▶ **iOS note:** "WhatsApp" detection on Android is by file path containing `whatsapp`. On iOS,
WhatsApp media isn't in the Photos library by default, so the sub-group feature may be absent
or driven by a different signal (e.g. album name). PDFs/audio live outside the Photos library
on iOS (Files app), so the unified grid is the biggest architectural divergence — see §12.

---

## 1. Navigation map

```
HOME (hub, launcher)
 ├── Hero card "Resume / Start curating"  → GALLERY (scrolled to next un-curated month)
 ├── Card: Free up space                  → GALLERY (sorted Largest-overall)
 ├── Card: Find duplicates                → DUPLICATES
 ├── Card: Search                         → SEARCH
 ├── Card: Hidden months                  → HIDDEN MONTHS
 ├── Card: Trash  (dimmed when empty)     → TRASH
 ├── Toolbar ⓘ Stats                      → STATS dialog
 └── Overflow: Help, Settings            → HELP / SETTINGS

GALLERY (first entry ever) ──> FIRST-RUN ANIMATION (§13), once; also replayable from Help
GALLERY ──tap item──> VIEWER (full-screen pager)
SEARCH  ──tap item──> VIEWER
HIDDEN  ──tap item──> VIEWER

Every screen's overflow has Help + Settings. Most toolbars have a ⓘ Stats icon.
```

Back behavior: spokes (Duplicates, Search, Hidden, Trash, Settings, Help) finish back to Home.
Gallery shows an Up arrow back to Home only when opened from Home (always, in practice).

---

## 2. HOME (hub)

The launcher screen. It is the **only** screen that requests permissions and runs the
storage bootstrap.

### Permissions & first-run bootstrap (on every `onStart`)
1. If media-read permission isn't granted → request it. (Android 13+: images/video/audio
   read perms; older: read-external-storage.)
2. Once granted, **bootstrap storage** (once per session):
   - On Android 11+ without **All files access**, show a one-time rationale dialog:
     - Title **"Allow file access"**
     - Body explains it's needed to (a) show PDF files, (b) keep curation progress & history
       across reinstalls; "Nothing leaves your device without your permission."
     - Buttons **Allow** (→ system All-files settings) / **Not now**.
     - Shown at most once ever (a "prompt shown" flag).
   - Then **restore from Downloads backups**: the lifetime "cleaned up" counter and the
     hidden-months list. If a hidden-months backup is found on a fresh install and the user
     has no local hidden months yet, offer:
     - Dialog **"Restore your progress?"** — "Found a saved list of N hidden months from a
       previous install. Hide them again…?" Buttons **Restore** / **Not now**. Offered once.

▶ **iOS note:** iOS has no "All files access" or `MANAGE_EXTERNAL_STORAGE`. PDFs/audio come
from the Files app / document picker, not a global permission. The cross-reinstall backup
concept can map to iCloud/Documents, but the All-files rationale dialog is dropped entirely.
Photos permission maps to `PHPhotoLibrary` authorization (full vs limited — handle "limited").

### Content
- **Library summary** line (e.g. counts/size overview).
- **Hero card** — teaches the concept and resumes work:
  - Title + a "resume" label + caption + an action button (e.g. "Start curating" /
    "Continue curating"). Tapping the card or button opens the Gallery at the **resume target**.
  - **Resume target** = the **last month the user was viewing** when they left the gallery, if
    that month is still visible (exists + not hidden); otherwise the oldest un-curated month. The
    hero's "Pick up at <month>" label reflects this. The gallery refines it further on open
    (see §3 "Landing position"). This is "pick up where you left off."
  - Optional progress bar with a percentage label when curation progress is known.
- **Cards** (each: icon, title, subtitle hint):
  - **Free up space** — "Biggest files first" → Gallery in Largest-overall sort.
  - **Find duplicates** → Duplicates.
  - **Search** — "Name, content, PDF text" → Search.
  - **Hidden months** → Hidden.
  - **Trash** — subtitle shows count; **when trash is empty the card is dimmed (alpha 0.5),
    disabled, and not clickable.**
- **Toolbar:** ⓘ **Stats** (always visible icon). Overflow: **Help**, **Settings**.

Home reloads its data on every return (so hiding a month / deleting updates the stats).

---

## 3. GALLERY (main browsing & curation screen)

The heart of the app. A 4-column grid wrapped in an expandable Year→Month→(Sub-group)→items
tree, with a sticky header, filter chips, sort, search, and selection actions.

### Tree structure (top to bottom within the list)
- **Year header** row: ▶/▼ arrow, year, compact type breakdown (📷/🎬/🎵/📄 + size),
  "N% curated" (percent of that year's months hidden), and up to 2 preview thumbnails.
  Tap toggles year expand/collapse.
- **Month header** row (only when year expanded): ▶/▼, month label, type breakdown. Tap toggles.
  **Accordion: only one month is open at a time** — opening a month auto-collapses the previously
  open month (and all sub-group expansions). This keeps a single focus and a single, stable Hide
  bar. (It is *not* a forced decision: opening another month is allowed any time; it simply tidies
  the previous one back to a header. There is deliberately **no "keep unhidden" action** — months
  are visible by default and only leave via an explicit Hide.)
- When a month is expanded, its items appear. **Two layouts:**
  - **Flat** (month has no WhatsApp items): items shown directly.
  - **Sub-grouped** (month has WhatsApp items): a **"Camera & Others"** sub-header and a
    **"WhatsApp"** sub-header, each ▶/▼ collapsible with its own type breakdown. Items appear
    under whichever sub-group is expanded.
- **Footer** row at the end of each expanded month: a thin divider. (Historically this row held
  the "Hide Month" button; that button is now retired in favour of the **pinned Hide-month bar**
  below, which is reachable without scrolling to the month's end. The footer's eligibility flag
  still drives the bar.)

### Pinned "Hide month" bar  ⚠ important
Hiding a month is driven by a **bar pinned to the bottom of the gallery**, not an in-list button:

- The bar is **bound to the single open month** (see accordion above), and appears once that month
  is eligible. Eligibility requires **both**: (a) the per-(sub-group × type) review gate below is
  satisfied, **and** (b) the month has been **walked through** — defined as *both* its header (top)
  and its footer (bottom) having been on screen since the month was opened (or last changed length),
  **with the footer only counting once the header has already been seen** (ordering rule). A month
  that fully fits on screen satisfies this at once (no scroll demanded); a longer month requires an
  actual top-to-bottom pass. The ordering rule matters because "header seen" is **never assumed on
  open** — it is only credited from a real observation that the header is at the top. Otherwise a
  freshly opened month that happens to land showing its *own footer* (scroll position carried over
  from the previous month) would instantly satisfy the gate and jump straight to "Hide" without any
  scroll; it also covers the sub-group case (expanding a section *above* makes the footer incidentally
  visible without the user seeing the new content). (Rationale: the scroll-through is where users spot
  junk worth deleting, so it's a gentle nudge toward real curation rather than blind hiding.) The walk
  state is re-earned when the open month changes or its **rendered length** changes (a sub-group
  expand/collapse) — but *not* on incidental background refreshes (indexing/hashing), so progress
  isn't wiped mid-scroll. The pure rules live in `WalkLatch` (unit-tested; see
  `docs/CURATION_REGRESSION_TESTS.md`). Label is **"Hide {Month}"** (e.g. "Hide March 2024") with a ✓
  icon.
- It is **bound to the open month, not the scroll position** — once shown it stays put as the user
  scrolls anywhere in the list, and only changes/disappears when the user hides that month or opens
  (or collapses) another. This avoids the bar flip-flopping between months while scrolling.
- It is **hidden** during selection mode and on the search screen, and in flat size-sort mode
  (which has no month headers).
- **It never covers content:** while the bar (or its coach-mark/teaser) is shown, the gallery list
  is given matching bottom padding so the last months/year scroll clear of the bar instead of
  hiding behind it. The bar itself is also inset above the **system navigation bar** (edge-to-edge),
  so it never overlaps the nav buttons / gesture pill.
- **Guided hint states (for new users):** before the live button, the bar acts as a coach through
  the whole flow, all sharing an animated **amber** down-chevron "wave" (▼▼▼ that brighten top→bottom
  and gently bob — a tasteful marquee, honouring the system reduce-motion setting), **amber bold**
  text, and a dismiss ✕:
  - **Review-sections hint** — when the open month isn't fully reviewed yet because a section is
    still unseen: *"Open both sections to review {Month}"* or, once one is done, *"Also open WhatsApp
    to review {Month}"* / *"Also open Camera & Others to review {Month}"* (names whichever of Camera &
    Others / WhatsApp remains). This closes the gap where opening only one sub-group gave no feedback
    that more review was needed. The chevrons **point toward the section** — **up (▲)** when it sits
    in the upper half of / above the viewport (e.g. opening WhatsApp first leaves Camera above, or a
    WhatsApp-only month scrolled past), **down (▼)** when it's in the lower half / below.
    (Flat month not reviewed because a type filter is off → *"Turn on all type filters to review
    {Month}"*.)
  - **Scroll hint** — once reviewed but the end isn't reached (a long month): *"Delete junk as you
    scroll back and forth — hide {Month} at the end"*, nudging the user through every item (where the junk worth
    deleting is) before hiding.
  On reaching the end it becomes the live green **"Hide {Month}"** button. All these hints are
  **retired together** (the bar then just appears at the end, no coaching) once the user hides their
  first month *or* taps a hint's ✕ — tracked by a persisted flag.
- **Revisit doesn't re-demand the scroll:** when a month has been fully reviewed *and* walked to its
  end, that's remembered (persisted with the month's full item count). On a later visit the live
  **"Hide {Month}"** button appears immediately — no need to scroll through again — as long as the
  count hasn't **grown**. Deletions (count same or lower) still count as fully seen; only **new
  photos** (a higher count) re-require the walk-through.
- Because it's pinned, the action is always reachable — the user never has to scroll to the
  bottom of a long month to find it. (This solves the discoverability problem where, with both
  Camera and WhatsApp expanded, the old footer button sat far below the fold.)
- **First-appearance coach-mark:** the very first time the bar is ever shown, a one-shot tooltip
  bubble appears above it: *"You've reviewed this month — tap to hide it and remove it from this
  app. Your files are never deleted; they stay in your phone's gallery. Find it again under Hidden
  months."* It is dismissed on tap (its own or the bar's) and never shown again (a persisted flag).

### "Hide month" eligibility rule (gate)  ⚠ important
The bar (and the gate flag on the footer) only becomes available once the user has actually
**reviewed everything in the month** — every media type, in every sub-group. The gate is
**per (sub-group × type)**:

- A type counts as **"seen"** for a sub-group only when that sub-group is **expanded while that
  type's chip is on** (so the items are actually on screen). Expanding Camera with the Videos
  chip *off* does **not** count as having reviewed the videos.
- The button appears only when, for **every (sub-group, type) that actually exists in the month**
  — computed from the full library, **ignoring the current chip filter** — the user has seen it.
  So turning a chip off can never reveal the button early; the user must enable each type and
  view it in each sub-group that contains it.
- "Seen" keys are per `(<month>:<sub>:<type>)`, e.g. `2024-03:cam:video`, and are **persisted
  across launches** (review Camera/photos in one session and WhatsApp/videos in another and it
  still adds up).
- A (sub-group, type) combination that doesn't exist in the month is simply not required.
- **Flat month** (no WhatsApp items): the single implicit group still gates per type — e.g. a
  photos+videos month needs both chips on and the items viewed before Hide appears.

Examples: a month of photos only → expand it (photo chip on) and Hide appears. A month with
Camera photos + Camera videos + WhatsApp photos → you must view Camera with both photo and
video chips on **and** open WhatsApp with the photo chip on before Hide appears.

### Hiding a month
Tapping the pinned **"Hide {Month}"** bar immediately hides it and shows a **Snackbar**:
*"{Month} hidden from this app"* with an **Undo** action (LENGTH_LONG). Undo un-hides it. Hiding
writes to the persistent hidden-months set and triggers a Downloads backup. (Tapping the bar also
permanently dismisses the first-run coach-mark.)

### Filter chips (the "settings bar")
Four cards: **📷 Photos, 🎬 Videos, 🎵 Audio, 📄 PDFs**. Each shows a count (total, and
total/hidden if any are hidden) + size, and a green check (on) / red ✕ (off) state with a
colored stroke. Tap toggles that type in/out of the grid.
- **At least one filter must stay active**: tapping the last active one shows toast
  *"At least one filter must be active"* and is ignored.
- Audio and PDF cards are **hidden entirely** if the library has zero of that type.

### Sort
A **sort chip** (button) shows the current sort; tapping opens a popup with single-choice:
- **Newest first** (DATE_NEWEST)
- **Oldest first** (DATE_OLDEST) — default
- **Largest first (overall)** (SIZE_ABSOLUTE) — flat list, no month tree, each tile shows a
  date badge; **no sticky header** in this mode.
- **Largest first (per month)** (SIZE_WITHIN_MONTH)
- **Most items first** (COUNT_PER_MONTH)

### Landing position (Resume / re-entry)
When the gallery opens via Home's Resume/Start, it lands using these rules, in order:
1. **The last month you were viewing** (persisted when you leave the gallery) — if it's still a
   visible month, scroll to it.
2. Else **the topmost open (expanded) month** — scroll to it.
3. Else **just show the tree** from the top (no forced scroll).
"All caught up" is preserved (the hero still says so when every month is curated). The last-viewed
month is saved as the top-of-viewport month on leave (skipped in flat Largest-overall mode). Net
effect: leave mid-library, come back, and you're where you left off — even across Home round-trips.

**Opening a month lands at its top.** Tapping a month header to expand it scrolls that month's
header to the top so the user starts from its first photos — not left mid/end after the accordion
collapse + relayout shift the viewport. (The scroll runs after the list is laid out.)

### Sticky header
As the user scrolls, a sticky overlay shows the current Year (always), Month (when scrolled
into one), and Sub-group (when inside one) context rows. Tapping a sticky row collapses that
level. Hidden in Largest-overall flat mode.

### Scroll-to-top FAB & jump-swap FAB
- A **scroll-to-top FAB** appears after scrolling past ~15 items; tap returns to top and
  expands the app bar.
- A **jump-swap FAB** appears after a programmatic jump (e.g. resume/unhide scroll); tapping
  it bounces between the previous and jumped-to month positions.

### Toolbar
- ⓘ **Stats** (always). 
- **Refresh** (hidden during selection).
- **↶ Restore last deleted (N)** — see Trash/undo §8. Visible only when there's a validated
  recoverable last batch and not during selection; title shows the count.
- The **Search** lens is **hidden** in the gallery (search now lives on its own screen).
- Overflow: **Help**, **Settings**.
- The toolbar **subtitle** is used transiently for background progress: *"Indexing PDFs… i / n"*
  or *"Hashing photos… i / n"*.

### Selection mode & actions
- **Long-press** any item enters selection mode and selects it; tap more to add/remove.
- A **selection bar** appears at the bottom showing *"N selected · {size}"* with three buttons:
  - **Share** — opens the system share sheet (single → ACTION_SEND, multiple → SEND_MULTIPLE);
    MIME narrowed when all one type, else `*/*`. Selection is **kept** after sharing.
  - **Move** (album switch) — Android 10+ only (else toast *"Move requires Android 10+"*).
    Opens **"Switch Album"** list dialog of album names. Moving items already in the target is
    skipped; toast summarizes *"Switched M items to {album}"* or *"Switched M · K already in
    {album}"*. On 11+ a system write-consent dialog is shown first.
  - **Delete** — soft-deletes the selection (→ Trash). Exits selection mode immediately. **No
    confirmation dialog, no per-delete snackbar** (silent; undo via ↶ / Trash).
- **Back** while selecting exits selection mode (doesn't leave the screen).
- Empty-selection taps on any action show toast *"No items selected"*.

### Tapping an item (not selecting)
- **PDF** → opens an external PDF viewer via chooser ("Open PDF with"); toast *"No PDF viewer
  found"* if none.
- **Photo/Video/Audio** → opens the **Viewer** (§4), paging through the tapped item's month in
  the grid's exact rendered order (or the whole flat list in Largest-overall mode).

### Empty states (centered message)
- Filter hiding everything but items exist: *"No items match the current filter. Tap the chips
  above to show more types."*
- All months hidden: *"All months are hidden! Use 'Hidden months' on the Home screen to bring
  them back."*
- No media at all: *"No media found on this device."*
- Searching with no results: *"No results — try different keywords or check for typos"*.

### PDF / duplicate background work (surfaced here)
- PDF content indexing and photo-hash (duplicate) computation run in the background, sequenced
  (PDF indexing first, then hashing). Progress shows in the toolbar subtitle.
- If memory runs out: a snackbar *"PDF content search disabled — not enough memory. File name
  search still works."* with a **Re-enable** action; or *"Duplicate detection disabled — not
  enough memory."*

▶ **iOS note:** Move-to-album → `PHAssetCollectionChangeRequest`. Share → `UIActivityViewController`.
External-PDF-open → `UIDocumentInteractionController` / Quick Look. Delete → `PHAssetChangeRequest`
deletion (which itself shows a system confirm and routes to the iOS "Recently Deleted" album —
see §8 for how this changes the Trash design).

---

## 4. VIEWER (full-screen media pager)

Opened from Gallery/Search/Hidden. A horizontal **pager** over an explicit, ordered id list
passed in by the caller. Black full-screen.

### Per type
- **Image** — zoomable/pannable (PhotoView). Single tap toggles UI (short-press callback).
- **Video** — auto-plays on prepare; tap toggles play/pause (shows a play button when paused);
  a **scrubber** at the bottom appears for videos and updates ~2×/sec; seek by dragging.
- **Audio** — shows *"Tap to play audio"*; tapping opens an external audio player.
- **PDF** — renders page 1 as an image with a persistent **"Tap to open PDF"** hint; tapping
  opens an external PDF viewer.

### Bottom toolbar buttons
- **Open in gallery** (hidden for PDFs) — opens the item in the system gallery app by MIME.
- **Share** — share sheet for the current item.
- **Delete** — soft-deletes the current item (→ Trash). **No confirmation, no snackbar.** The
  pager advances to the next item; when the list becomes empty the viewer closes.
- **Rename** — opens a **"Rename"** dialog: a text field pre-filled with the base name (no
  extension), all-text selected, keyboard auto-shown; a hint *"Extension '{.ext}' will be
  preserved"*. **Rename**/**Cancel**. On success toast *"Renamed to '{name}'"*, else *"Rename
  failed"*. (On 11+ may show a system write-consent dialog.) The current page is preserved by
  id across the re-index.
- **↶ Undo delete** — appears whenever there is a recoverable last batch; one tap restores it.

▶ **iOS note:** Pager → `UIPageViewController` or a paged `UICollectionView`. Zoom → `UIScrollView`
+ `UIImageView`. Video → `AVPlayer`/`AVPlayerViewController`. Rename: Photos assets don't expose a
user-facing filename to rename the way MediaStore does — renaming a `PHAsset`'s displayed name
isn't supported. **Rename likely doesn't apply to Photos-library items on iOS** (only to
Files-app documents). Flag this divergence.

---

## 5. HIDDEN MONTHS

A **preview** screen for hidden months. Sparse: two dropdowns + a review grid. **Previewing a
month never unhides it** — months stay hidden until the user explicitly taps "Unhide this month".
There are **no confirmation dialogs**: switching months or leaving the screen changes nothing.

### Layout & flow
- Two dropdown boxes: **Year** and **Month**. Disabled until data loads.
- **Auto-preview the last-hidden month:** on first entry, if the most recently hidden month is
  still hidden, the screen automatically previews it (shown expanded, but still hidden), so the
  user lands on what they last curated instead of a blank screen. The most-recent-hide is
  recorded whenever a month is hidden (gallery footer Hide button) and cleared if that month is
  later unhidden. Because previewing keeps the month hidden, this is **reliable on every visit**
  (the month doesn't vanish after a glance). One-shot per visit — it doesn't fight manual
  navigation afterward.
- If there's no recorded/still-hidden last month, **nothing is shown until a month is picked.**
  Empty-state text:
  - If hidden months exist: *"Pick a year and month above to view it."*
  - If none: *"You haven't hidden any months yet. Months you hide will appear here to bring
    back."*
- Year dropdown shows `year (count)`. Picking a year populates the Month dropdown
  (`MonthName · N items`) and auto-opens it.
- **Picking a month PREVIEWS it** — shows its items in a 3-column grid while it stays hidden. A
  **"shown bar"** appears: *"{Month} · still hidden"* with an **"Unhide this month"** button.

### Unhiding
- **"Unhide this month"** is the *only* action that unhides — it brings the previewed month back
  to the gallery, drops it from the dropdowns, and clears the "last hidden" pointer. No dialog.
- Switching to another month, or leaving the screen (back/up), simply changes the preview /
  exits — the hidden set is untouched.

### Selection actions (same engine as gallery)
Long-press → selection bar with **Share / Move / Delete** (same semantics as §3, including
Move's Android-10+ gate and the Switch-Album dialog). Deleting shows toast *"Deleted"* on
completion (this screen *does* toast, unlike the gallery). If a month becomes empty after
deletes: *"This month is now empty."*

Tapping an item opens the Viewer (PDFs open externally).

Overflow: Help, Settings (shared app menu).

---

## 6. DUPLICATES

Finds **exact** duplicate photos and videos (identical content) so copies can be deleted.

- Title **"Duplicate Photos & Videos"**, subtitle *"N files indexed"*.
- While computing: progress spinner; list hidden.
- Each **group** card: header *"N copies · {size} reclaimable"* and a horizontal row of
  thumbnails. Each thumbnail shows size + folder name, and a badge: **✓** (keep, green) or
  **✕** (delete, red, dimmed to 0.5 alpha). Exactly one item per group is the "keep"; the best
  copy is pre-selected. **Tap a non-kept thumbnail to make it the kept one** (can't un-keep the
  only kept item).
- **Bottom action bar** (hidden if nothing to delete): *"K to delete · {size} reclaimable"* and
  a **Delete** button.
- Delete shows a confirm dialog **"Delete duplicates?"** — "K files ({size}) will be permanently
  deleted. The kept copy in each group will not be affected." **Delete** / **Cancel**.
  - (Deletion is soft → Trash; recoverable. Only the no-op case toasts *"Nothing deleted"*.)
- Empty states: if nothing indexed yet *"No files indexed yet. Hashing runs after PDF indexing
  completes."*; else *"No exact duplicates found in N indexed files 🎉"*.
- Toolbar: ⓘ Stats, **Refresh** (re-run). Overflow: Help, Settings.

▶ **iOS note:** "exact duplicate" = same content hash (Android uses MD5 of bytes). Reproduce
with a content hash of `PHAsset` data. Folder labels come from MediaStore relative paths; on
iOS use album/collection names if available.

---

## 7. SEARCH

Dedicated standalone screen (no gallery behind it; Back → Home).

- A search field, auto-focused with keyboard up on open. Hint about labels/filenames.
- **Before typing:** prompt *"Search file names and text inside PDFs"*.
- Matches **file names** and **text inside PDFs** (local BM25 index). Typo-tolerant;
  multi-word queries require all terms to match.
- **No results:** *"No results — try different keywords or check for typos"*.
- Results in a 3-column grid; tapping opens the Viewer over the result set (PDFs open externally).
- A **Stats** button and overflow Help/Settings.

▶ **iOS note:** PDF text search needs an on-device text index (PDFKit can extract text). File-name
search over Photos assets is limited (assets don't have meaningful filenames); scope search to
PDFs/Files-app docs + any available metadata.

---

## 8. TRASH (recycle bin) & the undo system

Deletions are **soft** and recoverable through several paths.

### What "delete" does
Selecting Delete (gallery/viewer/hidden/duplicates) moves items to **Trash** silently — no
confirm (except Duplicates' batch confirm), no per-delete snackbar. It records a **"last
deleted batch"** for quick-undo and updates lifetime "cleaned up" + "in trash" stats.

### Undo paths
1. **Viewer ↶ button** — appears whenever a recoverable batch exists; one tap restores it.
2. **Gallery toolbar ↶ "Restore last deleted (N)"** — same, with a count; validated on resume
   (hidden until confirmed still recoverable, so it never flashes a stale count). Tapping shows
   toast *"Restoring last deleted…"*.
3. **Trash screen** — full management (below).

### Trash screen
- Title **"Trash"**. A note *"Items here are deleted automatically after 30 days."* (shown only
  when non-empty).
- 3-column grid of trashed items. Empty-state *"Trash is empty."*
- **Long-press to multi-select** → selection bar *"N selected · {size}"* with:
  - **Restore** — un-trashes the selection; toast *"Restoring N…"*.
  - **Delete forever** — confirm dialog **"Delete forever?"** ("N items will be permanently
    deleted and cannot be recovered.") **Delete forever** / **Cancel**.
- Toolbar overflow **"Empty Trash"** — visible only when non-empty **and** not selecting;
  confirms via the same "Delete forever?" dialog for the whole bin.
- Auto-purge: items older than ~30 days are purged automatically.

### Android implementation context (for porting decisions)
- On Android 11+ the app uses the **OS recycle bin** (the same bin the system gallery uses),
  but **scoped** to only items *this app* trashed (it tracks the URIs it trashed). On Android
  ≤10 it uses an **app-private trash folder** with a 30-day timestamp purge.

▶ **iOS note — this is a major divergence.** iOS Photos deletion routes to the system
**"Recently Deleted"** album (30-day retention, Face-ID gated), and the app **cannot** build its
own in-app trash over the Photos library the way Android does — `PHAsset` deletion is permanent-
to-the-app and always shows Apple's system confirm. Options:
  1. Lean on **Recently Deleted** as the trash (but you can't list/restore it programmatically,
     and every delete prompts) — loses the silent-delete + custom Trash UX.
  2. Keep deleted assets in a **hidden app-managed album** instead of deleting, and only really
     delete on "Delete forever". This preserves the silent-soft-delete + Trash + undo UX, at the
     cost of items still counting against the user's library until purged.
  Recommend option 2 to match the Android feel. Decide before building delete.

---

## 9. STATS dialog (shared, from ⓘ on most screens)

A monospace dialog **"Media Stats"** with:
- **COUNTS (visible + hidden = total)** per type (Photos, Videos, Audio*, PDFs*) and an "All"
  row. (*Audio/PDF rows only if any exist.)
- **SIZES (visible + hidden = total)** per type + All.
- **CLEANED UP (lifetime)** — items removed + size freed (persists across reinstalls via a
  Downloads backup).
- **IN TRASH (recoverable)** — count + size (read from the actual trash, not a counter, to avoid
  drift).
- An integrity line *"✓ All counts match"* (or a mismatch diagnostic).
- **OK** button.

"Visible" = items in non-hidden months; "hidden" = items in hidden months. Chip filters do
**not** affect these numbers.

---

## 10. SETTINGS

Reachable from every screen's overflow.

- **PDF content search** toggle. Turning **off** shows a confirm dialog **"Disable PDF content
  search?"** ("Background indexing will stop and PDF results will be matched by file name only…
  existing index files are kept…"). **Disable** / **Cancel** (Cancel reverts the switch). Toasts
  on each outcome. Turning on toasts *"…indexing resumes in the app"*.
- **Export hidden months** — writes a timestamped JSON to Downloads
  (`mediacurator_hidden_<stamp>.json`); toast with the path. If none: *"No hidden months to
  export"*.
- **Import hidden months** — opens a document picker (JSON); merges into the hidden set; toast
  *"Import done — K new months added (T total hidden)"*.
- **Share diagnostics** card — assembles device info + app state + a rolling debug log and opens
  a share sheet (with a consent step). No filenames are included (privacy).

▶ **iOS note:** Export/Import → Files app document picker. Diagnostics → share sheet. PDF index
toggle is the same concept.

---

## 11. HELP

Static "How it works" screen: a hero card ("Curate, don't just scroll" — three-step
Review → Hide → Continue), a **"Watch how curating works"** button that replays the first-run
animation (see §13, opened in replay mode → its primary button reads "Done"), then a scrollable
list of feature cards (icon + title + description) covering: Browse/filter/sort, Hide a month,
Find duplicates, Search, Select & act, Stats anywhere, Private by design. Footer shows
*"Version X (build)"*.

---

## 12. Cross-cutting behaviors & conventions

- **Soft delete everywhere**: no destructive action is immediate/irreversible except "Delete
  forever" / "Empty Trash" (both confirmed).
- **No per-delete snackbars** (deliberately removed — testers found them slow); undo is the ↶
  buttons + Trash.
- **Toasts** are used for lightweight confirmations (moves, renames, restores, import/export,
  "No items selected", filter-floor).
- **Snackbars** are used for: month-hide (with Undo), and background-work failures (with an
  action).
- **Dialogs** are used for: destructive confirms (duplicates delete, delete-forever, empty
  trash), the hidden "keep unhidden?" guard, permission rationales, restore-progress offers,
  rename, album picker.
- **"At least one filter active"** invariant in the gallery.
- **Hidden state, last-deleted batch, lifetime stats, "seen sub-groups", expanded years/months/
  sub-groups, sort mode, filter toggles, PDF-search toggle** are all persisted (key-value prefs).
  Hidden-months + lifetime "cleaned up" counter additionally back up to Downloads so they
  survive reinstall.
- **Privacy**: fully offline. No network calls, accounts, ads, analytics.

---

## 13. FIRST-RUN ANIMATION (onboarding explainer)

A full-screen animated explainer that teaches the core Hide-month loop.

**When it shows — mandatory until opted out.** On **every app launch** (cold start, once per
process), *before* the Home screen and its permission/restore bootstrap, the demo auto-plays. It is
**mandatory**: no transport controls, Back is blocked, and it must play through to the end. This
repeats on every launch **until the user opts out**.

**Opting out.** In place of the old "Get started" button, the bottom shows a **"Don't show again"**
checkbox. A **✕** (top-right) appears only when the demo finishes. At completion:
- if "Don't show again" is ticked → the window closes itself and the demo never auto-shows again;
- otherwise → the ✕ lets the user close it (and it auto-shows again next launch).

State is a single prefs flag (`demo opted out`). Cleared on data-clear / reinstall, so a **fresh
install shows the demo again** (it's tied to the install). Also **replayable** any time from Help's
"Watch how curating works" button — replay mode is *not* mandatory (Rewind/Play/Pause + ✕ always
available, no checkbox).

### Layout
- Title **"How curating works"** + subtitle ("Review a month, hide it, and it steps out of your
  way — so you never scroll past the same photos again").
- A faux phone-screen surface containing:
  - A **progress bar** with a **"{N}% curated"** label to its right (starts "0% curated").
  - A **caption** line (updates per step).
  - A **stack of month cards** (March / April / June 2024). Each card has a ▸/▾ arrow + month
    label + a (hidden) **"Hide month"** pill, and a collapsible grid of **colored tiles, each
    showing a small picture/emoji** (so it reads as varied content, not blank boxes).
- Transport controls: **Rewind / Play / Pause**. A primary button reads **"Get started"** on
  first run, **"Done"** in replay. **Auto-plays on open** (both modes).

### Animation sequence (paced ~1.5–2.4 s per beat)
1. All three months **collapsed** (headers only) — "Here are your months, waiting to be reviewed."
   The **middle** month is opened first, then the two ends in random order (so it never runs
   strictly top→bottom or bottom→top; all three are always opened).
2. **Open** the first month — the finger taps its header, tiles slide down, card highlights —
   "Open one and look through its photos."
3. **Delete a few tiles like the real app**: the counts **{1, 2, 3}** are spread across the three
   months (which month gets which is random each run), at **random positions** — selected with a
   **red wash + a white ✓ badge** on each — and a red **"🗑 Delete"** button appears — "Pick the
   ones you don't want and Delete." Then the tiles are removed and a floating **"Deleted N photos
   you didn't want to keep"** confirmation pops up. This is deliberate: it shows curation is
   *prune, then hide*, not just hide.
4. Show its **Hide month** pill — "Then hide the whole month."
5. Card **collapses away**; progress → **34% curated** — "It steps out of your way — this app gets cleaner."
6. Open month 2 → **delete some** → pill → collapse; progress → **67%** ("Next month — delete the junk… then hide it").
7. Open month 3 → **delete some** → pill → collapse; progress → **100%**.
8. End state: full bar, **"100% curated"**, caption "All caught up — clean and curated." (green).

A floating **finger** (👆) drives everything: it **taps each month's header to open it**, **taps
each photo to select it** (the check
pops on tap, the Delete button appears after the first), then taps **Delete**, then taps **Hide
month** — synced with each button's own press — so the interaction reads as real taps.

Rewind resets to the all-collapsed 0% state (paused). Pause halts between beats; Play resumes.

▶ **iOS note:** reproduce as a native animated view (e.g. SwiftUI timeline / `withAnimation`),
auto-playing on first run with the same Rewind/Play/Pause + replay-from-Help affordances.

---

### Biggest Android→iOS divergences to resolve up front
1. **Unified grid across Photos + PDFs + audio.** Android MediaStore returns all of these in
   one query. On iOS, photos/videos come from `PHPhotoLibrary`; PDFs/audio live in the Files app.
   Decide whether iOS unifies them (custom index over both) or scopes the grid to the Photos
   library + a separate Files section.
2. **Trash / soft-delete** (§8) — design the in-app trash before building delete.
3. **Rename** (§4) — likely not applicable to Photos assets.
4. **WhatsApp sub-grouping** (§3) — path-based on Android; may not exist on iOS.
5. **All-files-access / reinstall backups** — replace with Photos auth + iCloud/Documents.
6. **External open via Intent choosers** — replace with `UIActivityViewController` / Quick Look.

---

*Generated from the Android source as the behavioral source of truth. When in doubt about a
detail, the Android app's actual behavior wins — ask for the specific screen and it can be
re-derived.*
