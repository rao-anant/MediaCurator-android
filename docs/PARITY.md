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
| Expand/collapse lands the row clear of the sticky header | §2, §3 | DONE | DONE b23-b24 | Verified on Android: month-*open* already scrolls with `scrollToPositionWithOffset(pos, stickyHeaderOffset())` (MainActivity) so the sub-header + first row land BELOW the sticky bar, not behind it; year-open lands at the true top (offset 0). Collapse doesn't force a scroll — RecyclerView anchors the acted-on row in place — and `updateHideBar()` hides the "Hide {month}" bar when the open month isn't present, so a year-collapse can't leave a stale bar. iOS b23-b24 reached the same behavior in SwiftUI |
| Collapsible header rows: whole row taps, incl. disclosure chevron | §2 | DONE (itemView click) | DONE b24 | iOS wrapped the year + sub-group headers in a SwiftUI Button whose hit area missed the thin leading chevron, so tapping the arrow did nothing — only the text expanded. Fixed to a whole-row contentShape + tap. Android is already correct — `itemView.setOnClickListener` covers the whole row (`GalleryAdapter.kt:267/280/293`); logged for parity, no action needed |
| Video-only gallery uses larger tiles | §2 | TODO | DONE b23 | When only videos are shown, iOS drops 4 -> 3 columns (videos are fewer and read better larger; a 4-wide video grid looked busy). Android has a user-set global spanCount (`GallerySpanSizeLookup`), so this is optional there, but auto-enlarging for a video-only filter is a nice touch |
| Stats "cleaned up (lifetime)" must not flash 0 while loading | §9 | N/A | DONE b22 | Can't happen on Android: `StatsDialog` is a modal built once and shown only after all values are gathered (`present()` computes on IO, then `show()` — the lifetime total from `DeletionStatsStore` is passed into that single `show()` call). There's no live view that renders 0 first. iOS b22 fixed it by publishing the prefs total before the async scan |
| Duplicate finder must not pre-dedupe library by (name, size) | §8 | DONE a32 | DONE b23 | Real bug, confirmed + fixed on Android (a32): the full-library fetch deduped by `"(displayName)_(size)"`, silently dropping one of every exact-duplicate pair BEFORE the duplicate finder saw it (and hiding one copy from the gallery too). Fixed to `distinctBy { it.id }` — the MediaStore `_ID` is globally unique across Images/Video/Audio/Files views and identical for the same physical file, so it still removes the cross-collection artifact but keeps genuine duplicates. iOS b23: dedupe by asset identity (`localIdentifier`) |
| Indexing resumable on OEM lock-kill + fast geo scan | §7 | DONE a32 | DONE b18 | Android: hashing flush every 50 + don't-restart-running-job shipped in a31; the fast geo scan (parallel EXIF readers 2–4 workers, per-photo append-journal persistence — O(1), zero loss on kill — and camera-roll-first ordering so cities appear immediately) ships in a32. iOS b18: batches the hash store to every 25 (was every item — O(n²) writes) + final flush; both stores skip already-cached items. iOS b19: reads all GPS in ONE PHAsset fetch — a bulk Photos-DB read, so parallel EXIF workers are N/A (no per-file I/O to parallelise); progress throttled to every 25. Camera-first ordering is marginal on iOS since the bulk read is already fast |
| Background-finish indexing (deferrable, charging + idle) | §7 | TODO | DONE b21 | Let hashing/geo finish while the app is closed. iOS: BGProcessingTask (needs a Background-Modes Info.plist entry; no signing/App-ID change). Android analog: WorkManager with charging/idle constraints — would also survive the OEM lock-kill. Neither shipped yet; idea flagged for both |
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
