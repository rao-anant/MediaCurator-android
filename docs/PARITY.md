# Cross-platform parity ledger

**Single source of truth for "which platform has shipped what."** MediaCurator is two repos —
`MediaCurator-android` and `MediaCurator-iOS` — that share behavior (specified once in
[`FUNCTIONAL_SPEC.md`](FUNCTIONAL_SPEC.md)) but not code. Either side may build a generic feature or
find a fix the other needs; this table tracks who has it.

This file lives in the **Android** repo and is the authoritative parity view. The iOS repo keeps a
richer narrative in its own `PORTING_NOTES.md`; keep the two consistent (this table wins for status).

## How to use it (both Claudes)

- **On startup, read this file.** Rows where **your** platform's cell is TODO (or WIP) are your backlog.
- **When you ship an item,** set your cell to DONE and stamp the build it landed in (e.g. `DONE a27` for
  Android versionCode 27; iOS: `DONE b42` for build, or a date). Commit in the same change as the code.
- **When you build something generic the other side will need,** add/refresh its row with your cell
  DONE and theirs TODO, so the other Claude picks it up. Same for a bug/feature you spot *for* the other
  side.
- Keep rows at feature granularity, newest at the top of each section. Legend below.

**Legend (plain text, no emojis):** `DONE` (with build stamp) · `WIP` partial / in progress ·
`TODO` not started · `N/A` not applicable · `?` unknown — the owning side should confirm.

## Design debates

See [`DESIGN_DEBATES.md`](DESIGN_DEBATES.md). **Topic 1 (Previous explored month) — RESOLVED**
2026-07-17: shared semantics agreed (single-slot "month you left, however you left it"; tap re-expands
→ A/B bounce; session-only; month-only; pointer clears when its target leaves the visible list via
hide or type-filter, survives a sort). Presentation differs: iOS pill, Android upgrades its `jumpSwap`
FAB; **not** system Back on either.

**Topic 1 settled at phase 1 on BOTH platforms (2026-07-18).** Android amended the RESOLUTION to
ship **informational only** (plain `Came from Jul 2022`, no tap); iOS matched, same copy, and reduced
its already-built tappable pill. Resolution point 3 (tap => A/B bounce) is **deferred, not
rejected**. Consequence worth knowing: point 3's hide-vs-target and hide->Undo edges were deferred to
"whoever ships tappable first" — since neither platform now does, they are **unowned and still open**,
to be settled by whoever builds phase 2. One deliberate divergence: iOS keeps a stricter invalidation
gate (label also clears when its month is hidden/filtered), Android keeps the simple version.

## Handoff message — paste to the other Claude

When the *other* platform's Claude starts a session, paste the block below (fill in the last blank).
It's direction-agnostic — works whether Android is handing off to iOS or vice-versa.

> We coordinate cross-platform work through a shared parity ledger in the **MediaCurator-android** repo
> at `docs/PARITY.md` (from the iOS repo it's likely `../MediaCurator/docs/PARITY.md`). Clone that repo
> alongside yours so you can read and edit it.
>
> 1. Add a startup hook to your repo's `CLAUDE.md`: "On startup, read PARITY.md. Rows where my
>    platform's cell is TODO or WIP are my backlog. When I ship a generic feature/fix, set my cell to
>    DONE with a build stamp (Android: `a<versionCode>`; iOS: `b<build>` or a date), in the **same
>    commit** as the code."
> 2. Reconcile any `?` cells for your platform against your own porting notes.
> 3. Your backlog = every row where your column is TODO or WIP. Work them, then set the cell to DONE.
> 4. When you find an issue, or build something generic the other platform will need, **add/refresh its
>    row** so the other Claude picks it up.
> 5. Specific hand-off right now: ______________________________________________
>    (e.g. "In place-search, deleting photos must make them leave the grid and not resurface — Android
>    fixed it with a session-delete guard; check whether your platform has the same bug.")

## v1.1 — Place search & recent work

| Feature | Spec § | Android | iOS | Origin / notes |
|---|---|---|---|---|
| Previous-explored-month indicator | §3 | WIP (phase 1) | DONE b32 (phase 1) | **Android amendment (see the [Android] turn after the RESOLUTION): shipping phase 1 = INFORMATIONAL ONLY.** Plain secondary text beside the sort chips reading `Came from Jul 2022` — no tap, no jump; resolution point 3 (tap → A/B bounce) deferred to phase 2, not rejected. Rationale: the tap would re-expand previous + collapse current through the same `galleryItems` observer that a33 just added `pendingAnchorMonthKey` to (two scroll-anchoring mechanisms racing = the shape of the a33 collapse bug), and would touch the curation walk latch (`walk.onOpenedAtTop`), whose failure silently breaks Hide eligibility. Estimated ~75% chance of a bug tappable vs ~25% informational. Non-interactivity also means NO arrow glyph / pill / chip background (false affordance), and it makes point 6 invalidation cosmetic rather than correctness-critical — stale text is harmless where a stale tappable pointer is a dead link. Retains semantics 1, 2, 4, 5, 7. In progress on branch `previous-month-indicator`. **iOS matched phase 1 (2026-07-18, shipped in b32):** plain secondary text `Came from Feb 2024` beside the sort order, no arrow/pill/tint, hit-testing off. iOS did NOT share Android's risk rationale — its tap was a one-liner calling `toggleMonthExpansion`, the same function a month-header tap already calls, so no new code path — but that path's open-branch runs `requestScroll(belowSticky:)`, the anchoring machinery behind the b26/b28/b29 regressions where p2 is still unreproduced, so the tap added a fresh way to reach a live bug with submission pending. Same call, different reason. iOS KEEPS the stricter point-6 gate (`visibleMonthKeys`: label also clears when its month is hidden/type-filtered) rather than Android's simplification — already written, off the scroll path, and never names a month the user can't see. Layout note for Android: the label beside a long sort name (`Most items per month`) overflows a narrow phone and truncates to `Came from…`, hiding the month — iOS uses `ViewThatFits` to drop it to a second line instead. iOS is free to keep the tappable pill; if so, iOS hits the hide-vs-target + hide→Undo edge first (still unsettled). Design RESOLVED in DESIGN_DEBATES.md (Topic 1). Single-slot pointer to "the month you left, however you left it" (manual open OR programmatic jump); tap re-expands it (collapsing current) => A/B bounce between the two most-recent months; session-only, gallery-only, month-only; cleared when its target leaves the visible list (hide / type-filter), survives a sort. Presentation per-idiom, NOT system Back: **iOS** = slim `↩ <month>` pill under the sort bar (build fresh); **Android** = upgrade the existing `jumpSwap` FAB (arm on manual opens + label with target month). Origin: iOS user idea |
| Persistent (sticky) collapse control when scrolled deep | §2, §3 | DONE | DONE b25 | iOS pins a sticky year/month context bar with a collapse chevron, so you can close a deeply-expanded month/year WITHOUT scrolling back up to find its in-list tree chevron (which has scrolled off the top). The pinned chevron matches the tree (left-side `chevron.down`) and collapses ONE level at a time — month -> that year's month list -> all-years — like the geo drill (close Fremont -> California, not -> US). **Android already ships this** — `updateStickyHeader()` (MainActivity.kt) pins a `stickyHeader` bar once you scroll past a year: a year row (always), a month row, and a sub-group row, each with a left-side `▼`/`▶` arrow before its label (matches the tree). Tapping collapses ONE level at a time — month row → `toggleMonthExpansion` (month → that year's month list), then the year row → `toggleYearExpansion` (→ all-years); sub row → `toggleSubGroupExpansion`. Same design. **But testing found a real bug in it:** the sticky rows only toggled the model and did no scroll anchoring, so a collapse deleted every row the user had scrolled past, the RecyclerView lost its anchor item, fell back to a raw pixel offset and dumped the user on an unrelated month (collapse WhatsApp inside Jul 2019 -> landed on Dec 2019). Fix (a33): each sticky row now sets a pending anchor before toggling — sub-group -> parent month header, month -> that month header in the year's list, year -> year header — consumed in the `galleryItems` observer via `scrollToPositionWithOffset(pos, stickyHeaderOffset())`. Deliberately NOT the existing `pendingScrollToTopMonth`, which re-arms the curation walk latch (`walk.onOpenedAtTop`) and must never fire on a collapse. **iOS: worth checking whether your pinned collapse keeps scroll context after the rows disappear** |
| Duplicate hashing: photos-first, with a gated Duplicates entry point | §8 | DONE a33 | DONE b25 | iOS now hashes the library continuously in the background, PHOTOS FIRST (unlocks Duplicates) then videos, and disables the Home "Find duplicates" card with "Hashing photos N/M" until photos are done (videos fill in after). Serial + cancel-on-background = watchdog-safe. Android: **photos-first ordering IS done** — hashing runs on `images + videos` in that order (GalleryViewModel.kt:692), so photos complete before videos. The **hard gate now matches iOS (a33)**, at the user's request: the Home "Find duplicates" card shows "Hashing photos N/M" and is dimmed + unclickable until every photo is hashed. Android needs no cross-activity progress plumbing — `HomeViewModel` already loads the media list and the same `PhotoHashStore` singleton the hashing writes into, so it just counts `photos.count { hashStore.hasValidEntry(it.id, it.size) }`. Gate is skipped when duplicate detection is switched off in Settings (hashing never runs then, so gating would strand the card forever). The Duplicates screen keeps its non-blocking indexed-count subtitle as a second signal |
| Expand/collapse lands the row clear of the sticky header | §2, §3 | DONE | DONE b23-b24 | Verified on Android: month-*open* already scrolls with `scrollToPositionWithOffset(pos, stickyHeaderOffset())` (MainActivity) so the sub-header + first row land BELOW the sticky bar, not behind it; year-open lands at the true top (offset 0). An *in-list* collapse doesn't force a scroll and doesn't need to — the tapped header is on screen, so RecyclerView anchors it in place. A *sticky* collapse is the opposite case and did need one (see the sticky-collapse row above: fixed in a33). `updateHideBar()` hides the "Hide {month}" bar when the open month isn't present, so a year-collapse can't leave a stale bar. iOS b23-b24 reached the same behavior in SwiftUI |
| Collapsible header rows: whole row taps, incl. disclosure chevron | §2 | DONE (itemView click) | DONE b24 | iOS wrapped the year + sub-group headers in a SwiftUI Button whose hit area missed the thin leading chevron, so tapping the arrow did nothing — only the text expanded. Fixed to a whole-row contentShape + tap. Android is already correct — `itemView.setOnClickListener` covers the whole row (`GalleryAdapter.kt:267/280/293`); logged for parity, no action needed |
| Video-only gallery uses larger tiles | §2 | TODO | DONE b23 | When only videos are shown, iOS drops 4 -> 3 columns (videos are fewer and read better larger; a 4-wide video grid looked busy). Android has a user-set global spanCount (`GallerySpanSizeLookup`), so this is optional there, but auto-enlarging for a video-only filter is a nice touch |
| Stats "cleaned up (lifetime)" must not flash 0 while loading | §9 | N/A | DONE b22 | Can't happen on Android: `StatsDialog` is a modal built once and shown only after all values are gathered (`present()` computes on IO, then `show()` — the lifetime total from `DeletionStatsStore` is passed into that single `show()` call). There's no live view that renders 0 first. iOS b22 fixed it by publishing the prefs total before the async scan |
| Duplicate finder must not pre-dedupe library by (name, size) | §8 | DONE a33 | DONE b23 | Real bug, confirmed + fixed on Android (a33 — landed after a32 was already on Play, so it ships in a33): the full-library fetch deduped by `"(displayName)_(size)"`, silently dropping one of every exact-duplicate pair BEFORE the duplicate finder saw it (and hiding one copy from the gallery too). Fixed to `distinctBy { it.id }` — the MediaStore `_ID` is globally unique across Images/Video/Audio/Files views and identical for the same physical file, so it still removes the cross-collection artifact but keeps genuine duplicates. iOS b23: dedupe by asset identity (`localIdentifier`) |
| Indexing resumable on OEM lock-kill + fast geo scan | §7 | DONE a32 | DONE b18 | Android: hashing flush every 50 + don't-restart-running-job shipped in a31; the fast geo scan (parallel EXIF readers 2–4 workers, per-photo append-journal persistence — O(1), zero loss on kill — and camera-roll-first ordering so cities appear immediately) ships in a32. iOS b18: batches the hash store to every 25 (was every item — O(n²) writes) + final flush; both stores skip already-cached items. iOS b19: reads all GPS in ONE PHAsset fetch — a bulk Photos-DB read, so parallel EXIF workers are N/A (no per-file I/O to parallelise); progress throttled to every 25. Camera-first ordering is marginal on iOS since the bulk read is already fast |
| Background-finish indexing (deferrable, charging + idle) | §7 | DONE a34 | DONE b21 | Let hashing/geo finish while the app is closed. iOS: BGProcessingTask (needs a Background-Modes Info.plist entry; no signing/App-ID change). Android (a34): `IndexingWorker` (WorkManager CoroutineWorker), constraints **charging + battery-not-low**, enqueued (unique + KEEP) whenever the foreground starts indexing — so it finishes hashing + geo even after the OEM lock-kill. To make hashing worker-callable without duplicating code, the hash backup/restore moved from GalleryViewModel into `PhotoHashStore` (now symmetric with PlaceStore) and the hashing loop was extracted to `PhotoHasher.hashPhotos()` — the ONE implementation the foreground job and the worker share. The worker deliberately skips PDF indexing (PDFBox is the OOM-prone path; stays foreground-only). Same kill-safe/idempotent stores, so a run cut short by the system's work-time limit just resumes. NOTE (a34): constraint is charging (not idle) — finishes when plugged in rather than requiring full device-idle; relax to battery-not-low-only if we want it to run off-charger |
| Onboarding: self-paced slide deck | §13 | DONE a30 | DONE b16 | replaces the timed animation; Next/Back + dots, one animated slide, dashed "Hidden" shelf so the return never looks resurrected, recap |
| Selection bar must not hide bottom photos | §3, §7 | DONE a29 | DONE b13 | iOS: place/hidden grid already used safeAreaInset; moved the gallery multi-select bar from a bottom overlay to safeAreaInset so it insets the grid instead of occluding the last row |
| Deleted photos leave the place-search grid | §7 | DONE a28 | DONE b12 | iOS: PlaceBrowse now excludes the staged-for-deletion set from media/records/photos, and keeps the open city across the refresh |
| First-run place intro banner | §7 | DONE a27 | DONE b13 | one-time; `place_intro_shown`, dismissible ✕ banner in PlaceBrowseView |
| Place-search accuracy note (Settings) | §7 | DONE a27 | DONE b13 | "approximate / only located photos" in the Settings place-search footer |
| Reinstall-safe durable state (hidden months, Trash, review progress, flags, cleaned-up totals) | §4, §7 | DONE a27 | DONE b15 | iOS: keychain (survives uninstall on the same device, no iCloud entitlement); includes staged Trash + walked/seen curation progress. Android: gzipped Downloads mirror |
| Reinstall-safe place index | §7 | DONE a27 | N/A | iOS deliberately re-scans the place index from EXIF on reinstall (privacy + avoids iCloud quota) — only the small durable state above is backed up |
| Exact-place fast path + warm-up | §7 | DONE a27 | TODO | `placeExact` lookup |
| Act-on-results / unified icon action bar | §3, §7 | DONE a27 | TODO | Share/Rename/Switch Album/Show-in-gallery/Delete; single vs multi rule |
| "By Country" browse (was "Drill down") | §7 | DONE a26 | DONE b7 | naming only |
| Offline place search (search + browse) | §7 | DONE a25 | DONE b1 | GeoNames k-d tree; no network |
| Coach hints persist until dismissed (not on hide) | §3 | DONE a27 | DONE | ported FROM iOS |

## v1.0 — Baseline (Android shipped; iOS porting)

> iOS is mid-port (`ios-port-gallery-slice`; gallery slice compiles). iOS cells here are `?` until the
> iOS Claude reconciles them against `PORTING_NOTES.md`.

| Feature | Spec § | Android | iOS | Notes |
|---|---|---|---|---|
| Gallery timeline (year/month, type chips, sort) | §2 | DONE a1.0 | DONE b1 | full gallery shipped; sticky header, uniform square tiles, scroll-to-top |
| Curation walk-gate + Hide month | §1–3 | DONE a1.0 | DONE b1 | pure `CurationLogic` + tests; G-1/G-2/G-3 settle-guards approximated via onAppear |
| Full-screen viewer (share/rename/move/delete) | §4 | DONE a1.0 | DONE b1 | share + delete/undo; Rename & Move are N/A on iOS (no PHAsset API) |
| Trash / soft-delete + undo | §8 | DONE a1.0 | DONE b1 | staged-for-deletion (app trash) + 6s undo; iOS can't restore committed deletes |
| Duplicate detection | §? | DONE a1.0 | DONE b1 | MD5 `PhotoHashStore` + DuplicatesView |
| PDF content search (BM25) | §7 | DONE a1.0 | TODO | Phase 2 (Option A) — feasible via PDFKit + document picker; deferred |
| Search (filenames, fuzzy) | §7 | DONE a1.0 | TODO | Phase 2 (Option A) — deferred |
