# MediaCurator — Android

This app ships as **two separate repos**: `MediaCurator-android` (this one) and `MediaCurator-iOS`.
Shared behavior is specified **once** in [`docs/FUNCTIONAL_SPEC.md`](docs/FUNCTIONAL_SPEC.md) (with
inline `▶ iOS` notes for platform differences); shared pure logic (e.g. `CurationLogic`) is
**mirrored** per-platform, not shared as one module.

## Cross-platform coordination — read this on startup

**Read [`docs/PARITY.md`](docs/PARITY.md)** — the cross-platform feature-parity ledger. It is how the
Android and iOS Claudes hand work to each other:

- Rows where the **Android** cell is TODO / WIP are this side's backlog (usually something iOS shipped
  that Android still needs).
- When you ship a generic feature/fix, set the Android cell to DONE with its build stamp (e.g. `DONE a27`
  for versionCode 27), **in the same commit as the code**, and leave the iOS cell TODO so the iOS Claude
  picks it up.
- When you spot an issue or add a feature the iOS side will need, add/refresh its row.

Keep `docs/FUNCTIONAL_SPEC.md` and `docs/CURATION_REGRESSION_TESTS.md` updated alongside behavior
changes. Do **not** bracket the spec prose with per-OS markers — authorship lives in git; *status*
lives in `PARITY.md`.
