# M5 UI — TASK-050C..050I implementation log

Status vocabulary: **PASS (JVM)** = JVM + Robolectric tests and the full local CI-equivalent build
(`bash /workspace/tools/verify.sh`: tests, lint, debug and release assemble) passed on the box.
Nothing in this file was run against live Spotify, on a device or in GitHub CI.

## TASK-050C — Home dashboard

**Status: PASS (JVM), 2026-11-05.** 365 tests total (+14).

Files
- `app/.../library/LibraryModel.kt` — pure read model (`LibraryRows` → `LibrarySnapshot`): per-track Vietnam-day
  and rolling-72h counts, status by 05 priority, confirmed count, coverage, 7-day buckets, Almost There
  and Newly Collected selection, Vietnam-time formatters, `SyncCopy` (outcome code → 06 §9 copy).
- `app/.../library/LibraryViewModel.kt` — `LibrarySource` / `RoomLibrarySource` (signed-in account only) and
  `LibraryViewModel` (`Loading | NoAccount | Error | Ready`); reloads when the persisted sync status or the
  account's rules change.
- `data/.../local/Daos.kt` — two read-only queries (`HistoryDao.tracks`, `PlaylistDao.outboxAll`). **No schema change.**
- `app/.../ui/HomeScreen.kt` — `ReplayPage`, `LibraryGate`, `SyncStrip`, `CoverageNotice`, `HomeScreen`, `ListeningPulse`.
- `ReplayComponents.kt` — `TrackRow` click optional + extra slot; `PlaylistHero` footer slot.
- `ReplayFoundation.kt`, `MainActivity.kt`, `AppContainer.kt` — Home tab uses real data when READY; preview unchanged.
- Tests: `LibraryModelTest` (9, JVM), `HomeScreenTest` (5, Robolectric), synthetic `LibraryFixtures`.

State handling
- Hero count = PRESENT rows on the *current* playlist; `null` ("Chưa xác minh số bài trên Spotify") when no
  playlist or no successful pass and nothing PRESENT. Never derived from local qualification.
- "Kiểm tra playlist trên Spotify lần cuối" = last SUCCESS pass time (a SUCCESS pass reconciles the whole
  remote playlist, TASK-041); there is no per-row verification column.
- Almost There: unqualified, not in outbox, ≥1 play in 72h; closest to either threshold first, max 3; both
  `x/3 hôm nay` and `y/5 trong 72 giờ` shown as text + bar.
- Newly Collected: PRESENT only, latest remote `added_at` first, max 5.
- Listening Pulse: 7 Vietnam days, deduplicated events, exact count above each bar, per-bar
  contentDescription (`T5 05/11: 15 lượt`), "Lượt phát, không phải số phút nghe", partial/unknown coverage note.
- Loading, no account, error with retry, offline (strip warning, cached content kept), empty sections.

Deviations / limitations
- No Sync Now on Home; the status strip opens Settings (Sync Now lands there in 050G).
- No artwork collage / album images: no image-loading dependency (dependencies locked) → neutral placeholder.
- Progress shown as linear bars, not rings.
- Track rows are not tappable until Track Detail exists (050D).
- After a relink, outbox rows of the old playlist are ignored, so a track PRESENT only on the old
  playlist reads as "Đủ điều kiện" until re-added.
- Snapshot time is taken at load; the Vietnam day rolls over on the next reload, not live.
- Screenshots: `app/build/reports/ui/050C-home*.png` (Robolectric render, not a device).
