# M2 — Local data (TASK-021)

Status: **PASS — JVM (Robolectric) tests and local build on 2026-11-04.** No CI run, device install or
UI wiring observed yet.

## Scope
New Android library module `:data` (namespace `com.spotifyautoplaylist.data`) with:
- Room database `replay.db`, schema version 1, seven tables from [05-DATABASE.md](05-DATABASE.md) §1.
- DAOs for the TX-01 (import page) and TX-03 (qualify + outbox) transactions, half-open event counts,
  managed playlist and durable outbox state, sync state.
- DataStore Preferences settings (`noBackupFilesDir/datastore/settings.preferences_pb`).
- `LocalDataReset`, an `AccountResetStep` that clears every Room table and the settings file; the app
  registers it with `SpotifyAuthManager` through `AppContainer`.

Not in scope: sync/evaluator logic (TASK-030..032), TX-02/04/05 orchestration, Spotify client
(TASK-022), WorkManager reset step (TASK-051), UI.

## Versions
| Component | Version |
| --- | --- |
| Room (runtime, compiler, Gradle plugin) | 2.8.5 |
| KSP | 2.2.21-2.0.5 |
| DataStore preferences-core | 1.2.1 |
| Robolectric (tests only) | 4.16.1, runs `sdk=34` |
| androidx.test core (tests only) | 1.7.0 |

Robolectric is pinned to SDK 34 because its SDK 35+ runtimes need JDK 21 and the project uses JDK 17.
It downloads the `android-all` runtime jar on first run (cached afterwards).

App runtime classpath changes from the lockfile: new Room/SQLite/DataStore artifacts; Okio 3.6.0 →
3.9.1 (required by DataStore); `annotation-experimental` 1.4.1 → 1.5.0; `kotlin-stdlib-jdk7/jdk8`
resolve to the 1.8.21 empty compatibility shells and `kotlin-stdlib-common` is dropped (contents are in
`kotlin-stdlib` 2.2.21). `auth-core` resolved versions are unchanged.

## Schema decisions
- Exported schema: `data/schemas/com.spotifyautoplaylist.data.local.ReplayDatabase/1.json` (must be
  committed). CI fails if the build regenerates it differently, so an entity change requires a version bump,
  a migration and a migration test (05 §4). No migrations exist yet; no destructive fallback is configured.
- Every table is keyed by `spotify_user_id`. Foreign keys cascade: `account` → `tracks`,
  `managed_playlist`, `sync_state`; `tracks` → `play_events`, `qualification`, `playlist_outbox`.
  Writes use `@Upsert` (UPDATE then INSERT), never `REPLACE`, so updating a track or account cannot
  cascade-delete its events.
- The 05 index `(spotify_user_id, track_id, played_at_ms)` is not created separately; it is the
  `play_events` primary key. `(spotify_user_id, played_at_ms)` is created.
- `managed_playlist.playlist_id` is unique and inserted with `ABORT`, so a second playlist for the same
  account or a reused remote ID fails instead of replacing the row.
- Enums (`qualified_by`, outbox `state`, `coverage_status`) are stored as their names.
- DAO methods take a `userId`; callers must obtain it from `SpotifyAuthManager.requireAccount()`
  (`AccountKey` constructor is now public with validation, for tests and repositories).
  `importPage` rejects rows from another account before writing.

## Settings
`AccountSettings` defaults: daily 4, rolling 5, auto sync on, playlist name "My Replays — AutoPlaylist".
Validation: daily 2..20, rolling 2..30, name non-blank and ≤ 100 characters; invalid values throw and
nothing is written. Rolling window 72 h and time zone `Asia/Ho_Chi_Minh` are constants (fixed in v1).
Values are stored with the owning account hash; another account reads defaults, never the previous values.

## Tests (dummy data only)
`ReplayDatabaseTest` (7) and `SettingsRepositoryTest` (4), in-memory Room under Robolectric:
- Schema contract: tables, primary keys, indexes, FK cascade, `foreign_keys=1`.
- TC-005/TC-010: re-importing the same page inserts no duplicate events.
- TX-01 rolls back the whole page on a foreign-key failure; cross-account rows are rejected.
- Counts are per account and half-open `[start, end)`.
- TX-03: qualification is monotonic; the `PENDING` outbox row is created once and only with a playlist ID.
- Managed playlist uniqueness; outbox state survives updates.
- `LocalDataReset` clears all tables and settings and is safe to repeat (TC-033 local data part).
- Settings defaults, boundaries, invalid input rejection, account isolation.

## Evidence
`./gradlew --offline --no-daemon :auth-core:test :data:testDebugUnitTest :data:lintDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease --rerun-tasks` →
BUILD SUCCESSFUL. Tests: auth-core 92, data 11, app 23 (126 total, 0 failures/errors/skips).
`:data` tests passed on two further `--rerun-tasks` runs. `:data` lint: no issues. `:app` lint: 18
hints (dependency-version hints only). No Room/KSP warnings. Release APK `io.github.ntiendat01.replay`
0.4.0 (400) verifies with `apksigner`.

## Known gaps
- Room's default SQLite open helper deletes a corrupted database file; 05 §4 requires a recovery
  choice. Corruption handling must be added before history is shown as complete (sync/UI tasks).
- `AppContainer` is not yet used by any screen; no device run of Room or DataStore.
- WorkManager cancellation still needs its own reset step (TASK-051).
