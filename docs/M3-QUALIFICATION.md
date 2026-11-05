# M3 — Qualification evaluator (TASK-031)

**Status: PASS — JVM tests and local build on 2026-11-05.** Pure logic only; not yet wired to Room,
workers, outbox (TASK-032) or UI.

## Rule (owner change 2026-11-05)
A track qualifies when **either** (OR):
- **Daily:** >= **3** distinct play events in one Vietnam calendar day (`Asia/Ho_Chi_Minh`,
  `[D 00:00, D+1 00:00)`), inclusive. Previously >= 4.
- **Rolling:** >= **5** distinct play events in any rolling 72 h interval `(t - 72h, t]` ending at an
  event time `t`, inclusive. Unchanged.

## Implementation
`spotify-api/src/main/kotlin/com/spotifyautoplaylist/spotify/rules/QualificationEvaluator.kt`
- `QualificationEvaluator.evaluate(userId, events, Thresholds)` → `Map<trackId, Qualification>`.
- Filters to `userId`; deduplicates by `(userId, trackId, playedAtMs)`; sorts per track (input order irrelevant).
- Daily candidate = first event at which its VN date reaches the threshold; rolling candidate = two-pointer
  scan, left advances while `time[left] <= time[right] - 72h` (event exactly 72 h earlier is excluded).
- `qualifiedAtMs = min(daily, rolling)`; `qualifiedBy` = the rule whose candidate equals that time,
  `BOTH` only when both candidates are the same instant.
- `Thresholds(daily = 3, rolling = 5)` validated against 2..20 and 2..30.

## Decisions
- **Module:** `:spotify-api` (pure JVM, already has JUnit) — no new module, dependency or lockfile change.
  The rule is domain, not API; it can move into a dedicated domain module later without behaviour change.
- **BOTH semantics:** simultaneous candidates only (documented above); a later second rule does not change
  the first qualification (monotonic, BR-005).
- **Default duplication:** `Thresholds.DEFAULT_DAILY` (spotify-api) and `AccountSettings.dailyThreshold`
  (data) both = 3; `:data` does not depend on `:spotify-api`, so the constant is mirrored.
- **Stored settings:** DataStore writes all keys on `update()`, so a stored 4 cannot be distinguished from
  an explicit user choice. Stored values are **not** rewritten; the new default applies when nothing is
  stored. The current app never calls `settings.update`, so no device has a stored value from app flow.
- **Room schema:** reviewed — thresholds live in DataStore, not Room; schema JSON has no threshold default.
  No migration or schema version change.
- Persisting qualifications, never-remove semantics across re-evaluation and the outbox remain TASK-032.

## Tests
`QualificationEvaluatorTest` (16): defaults 3/5; TC-001 (2 plays not qualified); TC-002 (3 qualify at third);
TC-003 (00:00 VN boundary, 23:59:59.999 vs 00:00); VN day ≠ UTC day; TC-004 rolling across days; TC-007
72 h exclusive boundary, 72h−1ms inside, 72h+1ms outside; TC-005 duplicates; TC-006 separate tracks;
other accounts ignored; unsorted + empty input; TC-008 late imported history; DAILY vs BOTH; TC-009
lowered threshold only adds; configurable rolling and range validation; helper empties.
Data tests updated for default 3.

## Verification
`./gradlew --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
→ BUILD SUCCESSFUL in ~34 s; 210 JVM tests, 0 failures, 0 skipped.


## TASK-055 follow-up (minimum listen)
Counting now runs `MinListenFilter` (BR-016) before daily/rolling scans. See [M3-MIN-LISTEN.md](M3-MIN-LISTEN.md).
History sync storage and qualification monotonicity are unchanged.
