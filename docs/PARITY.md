# Cross-platform parity ledger

**Single source of truth for "which platform has shipped what."** MediaCurator is two repos —
`MediaCurator-android` and `MediaCurator-iOS` — that share behavior (specified once in
[`FUNCTIONAL_SPEC.md`](FUNCTIONAL_SPEC.md)) but not code. Either side may build a generic feature or
find a fix the other needs; this table tracks who has it.

This file lives in the **Android** repo and is the authoritative parity view. The iOS repo keeps a
richer narrative in its own `PORTING_NOTES.md`; keep the two consistent (this table wins for status).

## How to use it (both Claudes)

- **On startup, read this file.** Rows where **your** platform's cell is ⬜ (or 🚧) are your backlog.
- **When you ship an item,** flip your cell to ✅ and stamp the build it landed in (e.g. `✅ a27` for
  Android versionCode 27; iOS: `✅ b42` for build, or a date). Commit in the same change as the code.
- **When you build something generic the other side will need,** add/refresh its row with your cell ✅
  and theirs ⬜, so the other Claude picks it up. Same for a bug/feature you spot *for* the other side.
- Keep rows at feature granularity, newest at the top of each section. Legend below.

**Legend:** ✅ done (with build) · 🚧 partial / in progress · ⬜ not started · N/A not applicable ·
`?` unknown — the owning side should confirm.

## Handoff message — paste to the other Claude

When the *other* platform's Claude starts a session, paste the block below (fill in the last blank).
It's direction-agnostic — works whether Android is handing off to iOS or vice-versa.

> We coordinate cross-platform work through a shared parity ledger in the **MediaCurator-android** repo
> at `docs/PARITY.md` (from the iOS repo it's likely `../MediaCurator/docs/PARITY.md`). Clone that repo
> alongside yours so you can read and edit it.
>
> 1. Add a startup hook to your repo's `CLAUDE.md`: "On startup, read PARITY.md. Rows where my
>    platform's cell is ⬜ / 🚧 / ? are my backlog. When I ship a generic feature/fix, I flip my cell to
>    ✅ with a build stamp (Android: `a<versionCode>`; iOS: `b<build>` or a date), in the **same commit**
>    as the code."
> 2. Reconcile any `?` cells for your platform against your own porting notes.
> 3. Your backlog = every row where your column is ⬜ or 🚧. Work them, then flip the cell.
> 4. When you find an issue, or build something generic the other platform will need, **add/refresh its
>    row** so the other Claude picks it up.
> 5. Specific hand-off right now: ______________________________________________
>    (e.g. "In place-search, deleting photos must make them leave the grid and not resurface — Android
>    fixed it with a session-delete guard; check whether your platform has the same bug.")

## v1.1 — Place search & recent work

| Feature | Spec § | Android | iOS | Origin / notes |
|---|---|---|---|---|
| Deleted photos leave the place-search grid | §7 | ✅ a28 | ⬜ | session-delete guard; re-query can briefly resurrect trashed items |
| First-run place intro banner | §7 | ✅ a27 | ⬜ | one-time; `place_intro_shown` |
| Place-search accuracy note (Settings) | §7 | ✅ a27 | ⬜ | "approximate / only located photos" |
| Reinstall-safe place index | §7 | ✅ a27 | ⬜ | gzipped Downloads mirror, remap by name+size |
| Exact-place fast path + warm-up | §7 | ✅ a27 | ⬜ | `placeExact` lookup |
| Act-on-results / unified icon action bar | §3, §7 | ✅ a27 | ⬜ | Share·Rename·Switch Album·Show-in-gallery·Delete; single vs multi rule |
| "By Country" browse (was "Drill down") | §7 | ✅ a26 | ⬜ | naming only |
| Offline place search (search + browse) | §7 | ✅ a25 | ⬜ | GeoNames k-d tree; no network |
| Coach hints persist until ✕ (not on hide) | §3 | ✅ a27 | ✅ | **ported FROM iOS** |

## v1.0 — Baseline (Android shipped; iOS porting)

> iOS is mid-port (`ios-port-gallery-slice`; gallery slice compiles). iOS cells here are `?` until the
> iOS Claude reconciles them against `PORTING_NOTES.md`.

| Feature | Spec § | Android | iOS | Notes |
|---|---|---|---|---|
| Gallery timeline (year/month, type chips, sort) | §2 | ✅ a1.0 | 🚧 | first vertical slice done on iOS |
| Curation walk-gate + Hide month | §1–3 | ✅ a1.0 | ? | pure `CurationLogic` mirrored + JVM tests |
| Full-screen viewer (share/rename/move/delete) | §4 | ✅ a1.0 | ? | |
| Trash / soft-delete + undo | §8 | ✅ a1.0 | ? | |
| Duplicate detection | §? | ✅ a1.0 | ? | |
| PDF content search (BM25) | §7 | ✅ a1.0 | ? | |
| Search (filenames, fuzzy) | §7 | ✅ a1.0 | ? | |
