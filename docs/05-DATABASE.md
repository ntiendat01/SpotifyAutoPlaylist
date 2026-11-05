# 05 — Database Schema & Persistence

Version 1.1.0 · Status: Baseline · Storage: Room/SQLite + DataStore + encrypted token storage

## 1. Relational schema (logical; implement with Room entities)

M0 TASK-004 prerequisite (not the Room implementation): an atomic app-private no-backup JSON operation
journal stores marker, playlist ID, one explicitly selected test track and write-recovery state. It is
namespaced by SHA-256(Client ID + immutable `/me.account_id`), contains no token or listening events,
and fails closed on corruption. This does not implement the schema below; reconcile production identity
keys before M2, and do not silently discard/recreate remote playlists when moving beyond the probe.
See [M0-API-SPIKE.md](M0-API-SPIKE.md).

### `account`
- `spotify_user_id TEXT PRIMARY KEY`
- `display_name TEXT NULL`, `connected_at_ms INTEGER NOT NULL`
- Account ID scopes every other row. Do not store access tokens in Room. On OAuth, compare `GET /me` identity against configured expected Spotify developer-owner ID (AUTH-006) before activating this account. Do not hard-code usernames or secrets in source.

### `tracks`
- `spotify_user_id TEXT NOT NULL`, `track_id TEXT NOT NULL`, `uri TEXT NOT NULL`
- `name TEXT NOT NULL`, `artist_names TEXT NOT NULL`, `album_name TEXT NULL`, `image_url TEXT NULL`
- `PRIMARY KEY (spotify_user_id, track_id)`; metadata can update on later sync.

### `play_events`
- `spotify_user_id TEXT NOT NULL`, `track_id TEXT NOT NULL`, `played_at_ms INTEGER NOT NULL`, `ingested_at_ms INTEGER NOT NULL`
- `PRIMARY KEY (spotify_user_id, track_id, played_at_ms)`; FK to tracks.
- Index `(spotify_user_id, played_at_ms)` and `(spotify_user_id, track_id, played_at_ms)` for temporal scans.
- The key is an approximation because Spotify does not expose an explicit unique play-event ID; document possible collisions.

### `qualification`
- `spotify_user_id TEXT NOT NULL`, `track_id TEXT NOT NULL`, `qualified_at_ms INTEGER NOT NULL`, `qualified_by TEXT NOT NULL` (`DAILY`, `ROLLING`, `BOTH`)
- `PRIMARY KEY (spotify_user_id, track_id)`; monotonic record; never delete on threshold change.

### `managed_playlist`
- `spotify_user_id TEXT PRIMARY KEY`, `playlist_id TEXT NOT NULL UNIQUE`, `playlist_url TEXT`, `name TEXT NOT NULL`, `created_at_ms INTEGER`, `ownership_verified INTEGER NOT NULL DEFAULT 0`
- Never silently create another playlist after a missing local ID.

### `playlist_outbox`
- `spotify_user_id TEXT NOT NULL`, `playlist_id TEXT NOT NULL`, `track_id TEXT NOT NULL`
- `state TEXT NOT NULL` in `PENDING`, `CHECKING_REMOTE`, `SENDING`, `VERIFY_REMOTE`, `RETRY_LATER`, `PRESENT`, `REMOVED_EXTERNALLY`, `BLOCKED`
- `attempt_count INTEGER NOT NULL DEFAULT 0`, `last_attempt_ms INTEGER NULL`, `added_at_ms INTEGER NULL`, `last_error_code TEXT NULL`
- `PRIMARY KEY (spotify_user_id, playlist_id, track_id)`.

### `sync_state`
- `spotify_user_id TEXT PRIMARY KEY`, `last_success_at_ms INTEGER NULL`, `high_water_played_at_ms INTEGER NULL`, `last_attempt_at_ms INTEGER NULL`, `coverage_status TEXT NOT NULL`, `last_error_code TEXT NULL`, `last_page_cursor TEXT NULL`, `last_gap_start_ms INTEGER NULL`, `last_gap_end_ms INTEGER NULL`
- `coverage_status` enum: `UNKNOWN`, `COMPLETE_SINCE_BASELINE`, `PARTIAL_BOOTSTRAP`, `HISTORY_INCOMPLETE`.
- Cursor is resumable page progress, not proof of complete history.

### `settings` (DataStore, account scoped)
- `daily_threshold INT = 3`, `rolling_threshold INT = 5`, `rolling_window_hours INT = 72` (fixed in v1)
- `auto_sync_enabled BOOLEAN = true`, `playlist_name STRING`, `timezone STRING = Asia/Ho_Chi_Minh` (fixed v1)
- Validate thresholds in UI and domain: daily 2..20, rolling 2..30; reject invalid values. Thresholds are inclusive.

## 2. Transaction contracts
- **TX-01 Import page:** upsert tracks + `INSERT OR IGNORE` play_events + save page progress in one Room transaction.
- **TX-02 Commit high-water:** only after full bridge to prior watermark or clear initial baseline; otherwise retain old high-water and mark gap.
- **TX-03 Qualify:** insert qualification if absent + outbox `PENDING` atomically; no duplicate intention.
- **TX-04 Remote write:** mark `VERIFY_REMOTE` durably before network POST; after confirmed success or remote presence mark `PRESENT`. Network I/O must not occur inside Room transaction.
- **TX-05 Threshold change:** update settings, re-run pure evaluator over all stored events, insert only new qualifications/outbox rows.

## 3. Data ownership / privacy
- Spotify account IDs and listening history are personal data: store app-private, no analytics SDK, no export in MVP.
- Encrypt tokens separately with Keystore-backed storage, avoid plaintext SharedPreferences and GitHub secrets. Exclude token store from backups.
  TASK-020: one AES-256-GCM encrypted file `noBackupFilesDir/auth/credentials.bin` (AndroidKeyStore key
  `replay.credentials.v1`) holds Client ID, user ID, `account_id`, tokens, expiry and scopes; atomic writes,
  fail closed. Not in Room or DataStore. See [M2-AUTH.md](M2-AUTH.md).
- On Disconnect: revoke local credentials (remote revoke if supported), stop worker; user chooses **Keep local history** or **Delete local history** (default delete), never delete Spotify playlist.
- On uninstall: local Room DB normally removed, remote Spotify playlist persists.

## 4. Migrations / corruption
- Schema version starts at 1. Every change requires an explicit Room migration and migration test; no destructive fallback in production.
- On corruption: present recovery choice, never silently claim counts are complete.
- TASK-020A: every account-scoped table/DataStore key must use `SpotifyAuthManager.requireAccount()`;
  Room/DataStore deletion must be registered as an idempotent `AccountResetStep`. The data-owner binding
  (`auth/account-binding.json`, hashed `account_id`) is outside Room.
- TASK-021: implemented in `:data` (Room 2.8.5, schema JSON in `data/schemas/`, committed and checked by
  CI). The second `play_events` index above is the primary key; foreign keys cascade from `account` and
  `tracks`; writes use upsert, not `REPLACE`. Room's default helper still deletes a corrupted file — the
  recovery choice above is not implemented yet. See [M2-DATA.md](M2-DATA.md).
- Account change (AUTH-006): reject cross-account reuse of playlist/outbox, counters, tokens and cursors; stop workers and require explicit confirmation before disconnect/reconnect. MVP does not migrate history or playlist ownership between Spotify accounts.

## 5. Sample invariant queries
```sql
SELECT COUNT(*) FROM play_events
WHERE spotify_user_id = :userId AND track_id = :trackId
  AND played_at_ms >= :startUtc AND played_at_ms < :endUtc;
```
Rolling evaluation must be done on event-time ordered lists (or equivalent SQL window logic) and must respect `(t - 72h, t]`, **not** merely `NOW() - 72h` at sync time.
