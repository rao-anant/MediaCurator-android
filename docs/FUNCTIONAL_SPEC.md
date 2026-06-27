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
- When a month is expanded, its items appear. **Two layouts:**
  - **Flat** (month has no WhatsApp items): items shown directly.
  - **Sub-grouped** (month has WhatsApp items): a **"Camera & Others"** sub-header and a
    **"WhatsApp"** sub-header, each ▶/▼ collapsible with its own type breakdown. Items appear
    under whichever sub-group is expanded.
- **Footer** row at the end of each expanded month: the **"Hide Month from this app"** button
  (see visibility rule below) + a thin divider.

### "Hide Month" button visibility rule  ⚠ important
The footer's Hide button only appears once the user has actually **reviewed everything in the
month** — every media type, in every sub-group. The gate is **per (sub-group × type)**:

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
Tapping "Hide Month" immediately hides it and shows a **Snackbar**: *"{Month} hidden from this
app"* with an **Undo** action (LENGTH_LONG). Undo un-hides it. Hiding writes to the persistent
hidden-months set and triggers a Downloads backup.

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
  on each outcome. Turning on toasts *"…indexing resumes in the gallery"*.
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

Static "How it works" screen: a scrollable list of feature cards (icon + title + description)
covering: Browse/filter/sort, Hide a month, Find duplicates, Search, Select & act, Stats
anywhere, Private by design. Footer shows *"Version X (build)"*.

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
