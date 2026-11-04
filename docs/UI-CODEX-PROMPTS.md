# Codex Implementation Guide — Exact UI Mockups

> v1.3.0. Reference images live under `design/reference/`, one image per route. The actual requirements remain in `docs/06-UI-UX.md` and `docs/UI-VISUAL-DEVIATIONS.md`.

## Initial audit (no coding)

```text
Read AGENTS.md and docs/01-PRD.md, docs/02-BUSINESS-RULES.md,
docs/06-UI-UX.md, docs/08-IMPLEMENTATION.md,
docs/UI-VISUAL-REFERENCE.md and docs/UI-VISUAL-DEVIATIONS.md.
Inspect all eight WebP files in design/reference/.

Create an implementation map: screen → reference PNG → Compose components
→ UiState/ViewModel → tests → TASK ID. Identify image/spec mismatches.
Do not edit code yet. Do not treat screenshot placeholder data as real.
```

## Shared UI foundation — TASK-050A

```text
Implement TASK-050A only. Build an Android Jetpack Compose design system
matching design/reference/02-home.webp and shared styling in the other 7 PNGs.
Use docs/06-UI-UX.md for color and spacing tokens. Create ReplayScaffold,
ReplayTopBar, ReplayBottomBar, ReplayCard, PlaylistHero, TrackRow,
ThresholdProgress, CoverageBanner, SyncStatusChip and loading/empty/error states.
Respect TalkBack, 48dp targets, safe-area insets, text scale and dark AMOLED.
Do not display a full-screen reference image in the app. Compose must render
real accessible elements. Mock fixtures are only for tests/preview.
Run applicable tests/compile and list incomplete visual differences.
```

## One-screen execution template

```text
Implement ONLY TASK-050C (Home Dashboard).
Read design/reference/02-home.webp; docs/06-UI-UX.md;
docs/UI-VISUAL-REFERENCE.md; docs/UI-VISUAL-DEVIATIONS.md.
Build native Jetpack Compose controls closely matching the reference:
playlist hero, verified count, near-threshold tracks, newly collected,
7-day observed-play chart, sync status and four-tab navigation.
Use ViewModel/UiState; preview fixtures must be isolated from production.
Handle loading, empty, offline, partial coverage, error and pending states.
No Spotify API calls from composables. No fake counts or fake album artwork.
Test, compile, report screenshots/visual differences and touched files.
Do not implement other routes in the same task.
```

Replace `TASK-050C` and `02-home.png` with one of:
- `TASK-050B`: `00-welcome.png` and `01-playlist-setup.png` (two screens, can split into separate commits)
- `TASK-050D`: `03-listening-history.png`
- `TASK-050E`: `04-collection.png`
- `TASK-050F`: `05-rules-studio.png`
- `TASK-050G`: `06-settings-sync-health.png` and `07-sync-complete.png` (result state is shown only when meaningful)

## Screenshot review loop

1. Build debug APK via GitHub Actions when local Android SDK unavailable.
2. Install at checkpoint, take real OnePlus screenshot (no embedded reference image).
3. Give Codex the screenshot along with its matching `design/reference/*.png` file.
4. Ask it to compare hierarchy, spacing, typography, gradient, icon shape, responsiveness and legibility; patch *only* the current task's UI files.
5. Capture new screenshot and review; do not claim pixel-perfect equivalence.
6. Confirm no dummy/sample data appears when connected to production Spotify account.

## First implementation order

1. M0 real Spotify API/OAuth feasibility gates remain mandatory, before broad app investment.
2. M1 Android shell + GitHub Actions, then `TASK-050A` visual foundation and a mock Home preview.
3. Implement M2–M4 (auth/database/history/playlist) with tests, then wire and finish `TASK-050B..050G`.
4. `TASK-050H` UI tests; `TASK-050I` optional restrained polish; device checkpoints and release.

## Definition of Done per screen

Native components are interactive; no dead controls; actual screenshot compared to original PNG; all states and accessibility covered; fake data confined to preview/test; business rules unchanged; CI build green when executed; any screenshot mismatch documented.
