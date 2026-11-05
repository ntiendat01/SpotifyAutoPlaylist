# M5 — TASK-051 WorkManager sync worker

**Status: PASS — JVM tests and local build on 2026-11-05.** Not run against live Spotify, on a device or in CI.
Background reliability on OxygenOS (Doze, battery optimisation, OEM task killing) is **unverified**; that is
TASK-053.

## Architecture

```
SyncScheduler (WorkManager unique work)
  ├─ "spotify-autoplaylist-periodic-sync"  PeriodicWork 30 min, NetworkType.CONNECTED, EXPONENTIAL 5 min, UPDATE
  └─ "spotify-autoplaylist-manual-sync"    OneTimeWork, NetworkType.CONNECTED, EXPONENTIAL 5 min, KEEP
        │ input: trigger (PERIODIC|MANUAL), account_hash (salted hash, never the raw ID)
        ▼
SyncWorker (CoroutineWorker) ── Application as SyncOrchestratorProvider ── AppContainer.syncOrchestrator
        ▼
SyncOrchestrator.run(trigger, expectedAccountHash)       single-flight per account (Mutex + in-flight Deferred)
  1. account check (restore → SignedIn / SignedOut(reason) → AUTH_REQUIRED / null → NO_ACCOUNT)
  2. bound-account check (scheduled hash ≠ current → ACCOUNT_CHANGED)
  3. persisted Retry-After / quota window still open → DEFERRED (no Spotify call)
  4. periodic + auto-sync off → DISABLED
  5. HistorySync.sync → QualificationRecorder.evaluate → OutboxWriter.run (verifier + reconciler gate)
  6. SyncRunStatus persisted (DataStore, account-tagged)
SyncRepository (app API for TASK-050G): requestManualSync, ensureScheduled, cancel, status flow
```

- Worker injection: the default WorkManager initializer is kept; the worker reads the orchestrator from
  `ReplayApplication` (`SyncOrchestratorProvider`). No manifest change, no custom `WorkerFactory`.
- Lifecycle hooks (minimal): `OnboardingViewModel` calls `ensureScheduled()` when it reaches READY (restore with a
  linked playlist = app start, create, link, recover). `ensureScheduled` enqueues the periodic work only when the
  managed playlist is `ownership_verified`, and a one-time run when the last successful pass is older than 10 min
  (03 §6 freshness). Sign-out cancels both unique works before the grant is cleared; account reset adds a cancel
  step before `LocalDataReset` (which now also clears sync status).
- No screen, no button. TASK-050G binds the Sync Now button and diagnostics to `SyncRepository`.
- Logs: debug builds only, tag `ReplaySync`, enum names and counts only (tested: no user ID, hash, playlist or
  track ID).

## Outcome table

| SyncOutcome | Cause | Persisted | Worker result |
|---|---|---|---|
| SUCCESS | writer DONE, or no playlist linked yet | last_success_at | success |
| PARTIAL | READ_INCOMPLETE / AMBIGUOUS_VERIFIED / VERIFY_PENDING | — | success (next period re-verifies) |
| NO_ACCOUNT | nobody signed in | — | failure |
| AUTH_REQUIRED | SignedOut with reason, 401 after refresh | outcome | failure |
| RELINK_REQUIRED | verifier gate (unverified/deleted/foreign/public), 404 on POST | outcome | failure |
| FORBIDDEN | 403, 400 rejection | outcome | failure |
| RATE_LIMITED | 429 with Retry-After (60 s if absent) | blocked_until = now + Retry-After | success |
| QUOTA_EXCEEDED | 429 quota | blocked_until = now + Retry-After or 6 h | success |
| NETWORK_ERROR | network, 5xx, malformed | outcome | retry (exponential), failure after 5 attempts |
| DEFERRED | blocked_until not yet reached | unchanged | success |
| DISABLED | periodic while auto-sync off | — | success |
| ACCOUNT_CHANGED | scheduled account ≠ current, or writer saw a change | — | failure |

A failed periodic run does not cancel the schedule; the next one runs after 30 min.

## Decisions

- **Dependency:** `androidx.work:work-runtime-ktx` and `work-testing` 2.11.2 (latest 2.11.x stable; pinned in
  `libs.versions.toml`). Locks added in `app/gradle.lockfile`: work-runtime, work-runtime-ktx, work-testing 2.11.2,
  lifecycle-service 2.9.4, concurrent-futures-ktx 1.1.0, tracing-ktx 1.2.0. No existing version changed.
- **No Room schema change.** History cursors/coverage stay in `sync_state`. Pass outcome and the blocked-until
  time live in a separate account-tagged DataStore file (`noBackupFilesDir/datastore/sync-status.preferences_pb`),
  like settings. No migration needed.
- **Serialization:** in-process single-flight per account hash. A second caller while a pass runs joins it
  (`coalesced = true`) instead of starting another. Manual work uses KEEP, so repeated requests coalesce in
  WorkManager too. One process in v1, so no DB lease.
- **No hot retry for 429:** WorkManager's minimum backoff is 10 s and cannot be set per result, so 429/quota return
  success and the persisted window makes every run inside it (periodic or manual) skip Spotify.
- **Not expedited:** on API < 31 expedited work needs a foreground notification, which v1 does not have.
- **Periodic UPDATE** keeps the existing schedule but refreshes the bound account hash.

## TC traceability

| Requirement | JVM test (SyncWorkerTest) | Device (TASK-053) |
|---|---|---|
| 30 min, network, unique name, policy | periodic request / manual request | real scheduling, Doze, OxygenOS |
| no overlap, coalescing | manual during periodic coalesces; accounts independent | — |
| success/retry/failure | result table, worker maps | — |
| Retry-After / quota | Retry-After defers; quota pauses; history 429 | real 429 |
| no-account/auth/relink stop, no POST | three tests | TC-019/020 live |
| sign-out cancels | sign-out cancel; repository cancel | real sign-out |
| exactly once, idempotent | one pass + repeat runs | live playlist |
| crash/restart | crash after POST; crash before POST | process kill on device |
| account isolation | account isolation | — |

## Limitations

- Nothing run on live Spotify, a device or CI; OxygenOS background delays unverified.
- The onboarding hook (`SyncScheduleHook`) is wired but has no dedicated ViewModel test; `SyncRepository` is tested.
- The 6 h quota pause and the 60 s default Retry-After are local choices; docs give no number.
- `QualificationRecorder.evaluate` still re-reads all events each pass.
- No relink flow (unchanged since TASK-043); RELINK_REQUIRED waits for the user.
- Single-flight is in-process only.
