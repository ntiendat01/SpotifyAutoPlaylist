# M3 — Transactional qualification/outbox and threshold re-evaluation (TASK-032)

**Status: PASS — JVM/Robolectric tests and local build on 2026-11-05.** Local persistence only: no Spotify
write (M4 TASK-040..043), no worker (TASK-051), no UI wiring.

## Implementation
- `QualificationDao.qualifyBatch(userId, items, playlistId)` (`:data`, one `@Transaction`, TX-03/TX-05):
  `INSERT OR IGNORE` each qualification; if the account's managed playlist is known, then
  `INSERT OR IGNORE INTO playlist_outbox … SELECT … FROM qualification` so **every** qualification of the
  account has exactly one outbox row for that playlist (new rows `PENDING`, attempt 0). Any failure rolls
  back the whole batch. Rows of another account are rejected (`IllegalArgumentException`).
- `QualificationDao.outboxInQualificationOrder(userId, playlistId)`: outbox rows ordered by
  `(qualified_at_ms, track_id)` — the add order for the future M4 writer.
- `app/.../rules/QualificationRecorder` (`:app`, which already depends on `:data` and `:spotify-api`):
  `evaluate(userId, Thresholds)` / `evaluate(AccountKey)` reads the account's stored events, runs
  `QualificationEvaluator`, persists via `qualifyBatch`; `changeThresholds(account, daily, rolling)` saves
  settings (validated) then re-evaluates (TX-05, BR-010).
- The existing single-row `qualify()` (TASK-021) is unchanged.

## Decisions
- **No Room schema change:** existing `qualification` / `playlist_outbox` tables suffice. Schema stays
  version 1; `data/schemas/.../1.json` unchanged; no migration needed. No module dependency or lockfile change.
- **Qualification before playlist link:** allowed (setup may not be done); no outbox row exists yet because
  `playlist_id` is NOT NULL. The next evaluation after linking backfills `PENDING` rows in the same
  transaction. Invariant: when a managed playlist exists, after any evaluation every qualification has an
  outbox row; an outbox row never exists without a qualification created by this path.
- **Monotonic / never remove:** existing qualifications keep their `qualified_at_ms`/`qualified_by`; existing
  outbox rows keep their state (`PRESENT`, `REMOVED_EXTERNALLY` etc. are never reset → BR-009 no re-add).
  Raising thresholds deletes nothing.
- **TX-05 is two steps:** thresholds live in DataStore, outside Room, so "update settings" and "insert new
  rows" cannot share one transaction. Settings are saved first; the evaluation is idempotent, so if the
  process dies in between the next evaluation completes it.
- **Read outside the write transaction:** events are read, evaluated in memory, then written in one
  transaction. Events imported concurrently are picked up by the next run; `INSERT OR IGNORE` keeps it safe.
- Evaluation is full-history (O(events)); incremental evaluation is a later optimisation.

## Tests (`QualificationRecorderTest`, 13, Robolectric in-memory Room)
TX-03 qualification + PENDING row together; idempotent repeats (incl. re-imported page, TC-005/TC-010) keep
outbox progress; `REMOVED_EXTERNALLY` never requeued; atomic rollback on FK failure (no qualification or
outbox row); account isolation + cross-account rejection; backfill after linking; every qualification has an
outbox row; TC-009 lowering daily 3→2 adds only new; raising to 20/30 removes nothing and blocks new
sub-threshold tracks; invalid threshold writes nothing; TC-008 late history keeps original time + ordering
with track-ID tiebreak; ROLLING and BOTH persisted (TC-004); empty input writes nothing.

## Verification
`./gradlew --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
→ BUILD SUCCESSFUL in 25 s; 223 JVM tests, 0 failures, 0 skipped.

## Limitations
Not called by sync/workers or UI yet; no live Spotify; outbox state transitions belong to M4.
