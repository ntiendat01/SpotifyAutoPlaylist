# M3 — History sync (TASK-030)

Status: **PASS — JVM tests and local build on 2026-11-05.** No live Spotify call, worker, CI run or
device run with this code yet.

## Scope
Newest-page backward pagination of API-01 with an inclusive overlap on the committed high-water,
per-page transactional persistence, safe checkpoints and gap detection (03 §4, 04 §6, 05 TX-01/TX-02).
Not in scope: qualification/outbox (TASK-031/032), WorkManager and sync serialization (TASK-051),
error/backoff policy UI (TASK-052), UI. `RoomHistoryStore` is not yet wired into `AppContainer`.

## Files
- `spotify-api/.../spotify/sync/HistorySync.kt` — pure engine (`HistorySync`), ports `RecentlyPlayedSource`
  (adapter `SpotifyClient.asRecentlyPlayedSource()`) and `HistoryStore`, `SyncCheckpoint`, `Coverage`,
  `ValidPlay`, `StopReason`, `SyncResult`. No Android dependency.
- `app/.../sync/RoomHistoryStore.kt` — `HistoryStore` on the TASK-021 Room schema: `importPage` →
  `HistoryDao.importPage` (TX-01), `commit` → `sync_state` upsert (TX-02). Creates the `account` row if missing.
- Tests: `spotify-api/.../sync/HistorySyncTest.kt` (13), `app/.../sync/RoomHistoryStoreTest.kt` (2, Robolectric).

The engine lives in `:spotify-api` and the Room adapter in `:app` so no module dependency or lockfile
changes were needed (`:data` does not depend on `:spotify-api`).

## Algorithm
1. Read the checkpoint. Request the newest page (`limit=50`, no cursor).
2. Validate every item (02 canonical event): parseable `played_at` not later than now + 5 min (BR-012),
   non-null 22-char base62 track ID, `type == "track"`, `is_local != true`. Others are counted as filtered
   (TC-027) and never persisted. Event identity is `(spotify_user_id, track_id, played_at_ms)`; duplicates
   are ignored by the store (BR-004).
3. Persist the page (TX-01) with `last_page_cursor` progress; the high-water is **not** changed here.
4. Stop when: any item's time `<=` the previous high-water (overlap inclusive, `BRIDGED`); empty page,
   `next == null` or no cursor (`END_OF_HISTORY`); page cap 20 (`PAGE_CAP`); the `before` cursor did not
   move strictly backwards (`NO_PROGRESS`). Otherwise request `before = cursors.before` (falls back to
   the page's oldest `played_at`). Server `next` URLs are never followed.
5. Final commit (TX-02):
   - No previous high-water (baseline): `END_OF_HISTORY` → `COMPLETE_SINCE_BASELINE`; truncated →
     `PARTIAL_BOOTSTRAP`; high-water = newest event. No events at all → `UNKNOWN`, no high-water.
   - `BRIDGED`: high-water = max(old, newest); gap cleared; coverage stays `COMPLETE_SINCE_BASELINE` if it
     was, otherwise becomes `PARTIAL_BOOTSTRAP`.
   - Not bridged: `HISTORY_INCOMPLETE`, **old high-water retained**, gap = (old high-water, oldest fetched
     event or now if nothing was fetched).
   - `SpotifyApiException` mid-run: pages already committed stay, high-water retained, `last_error_code`
     = error kind; with an existing baseline coverage becomes `HISTORY_INCOMPLETE` with gap bounds;
     the exception is rethrown for caller policy. `last_success_at_ms` is not updated.

## Decisions on ambiguous points (conservative readings)
- "Complete" is only ever relative to the baseline: a first bootstrap that reaches the end of accessible
  history is `COMPLETE_SINCE_BASELINE`; nothing is claimed about plays before the oldest returned event.
- An empty newest page when a high-water exists is treated as "unexpected empty page" (03 §4) →
  `HISTORY_INCOMPLETE`.
- Any failure to bridge (cap, repeated cursor, quota/rate/network error) is `HISTORY_INCOMPLETE`, as 03 §4
  lists. Because the high-water is retained, a later run that reaches it proves contiguity and clears
  the gap; since the single `coverage_status` column cannot remember whether the original baseline was
  complete, the restored status is `PARTIAL_BOOTSTRAP`, never `COMPLETE_SINCE_BASELINE`.
- Items with a valid time but filtered type (e.g. episode) still count for bridging and cursor fallback.
- Future-dated/invalid timestamps are rejected silently in persistence and only reported as the
  `filtered` count; a separate diagnostic channel is not implemented.

## Tests observed (dummy data only)
`HistorySyncTest` (13): baseline walk with `before` cursors → `COMPLETE_SINCE_BASELINE`; high-water only in
final commit; TC-010/TC-011 overlapping watermark, equality boundary, re-fetch imports once; multi-page
bridge; bootstrap page cap → `PARTIAL_BOOTSTRAP`; TC-026 unbridged gap keeps high-water, gap bounds, later
bridge closes it; page cap and repeated cursor with a baseline; empty pages; TC-027 filtering (local,
episode, null/invalid ID, unparseable/future time, null track); same time different tracks; mid-run 500
keeps committed pages; MockWebServer pagination through `SpotifyClient` (paths `?limit=50`,
`&before=…`) and 429 `QUOTA_EXCEEDED` stopping the run without advancing.
`RoomHistoryStoreTest` (2): engine + Room end-to-end — repeated syncs keep 4 unique events and the
checkpoint; gap stored in `sync_state`; accounts isolated.

## Evidence
`./gradlew --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug` → BUILD SUCCESSFUL in 2m 6s (JDK 17 Temurin,
local SDK). Tests: auth-core 92, spotify-api 36, data 11, app 55 (194 total, 0 failures/errors/skips).
Lint (warnings as errors) passed for `:data` and `:app`. No dependency or lockfile changes.

## Known limitations
- Not exercised against live Spotify; cursor semantics and history retention remain an external unknown (04 §9).
- A permanently lost gap keeps the old high-water, so later runs keep walking back up to the page cap
  and stay `HISTORY_INCOMPLETE`; quota cost/policy belongs to TASK-051/052.
- Runs must be serialized per account by the caller (TASK-051); the engine has no mutex.
- Room corruption recovery (05 §4) is still not implemented.
