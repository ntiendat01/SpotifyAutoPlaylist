# M3 test traceability (TASK-033)

Status: **PASS — 2026-11-05, JVM tests and local build only.** Nothing here ran against live Spotify,
a device/emulator, or CI. 238 JVM tests total (15 added by TASK-033), 0 failures/skipped.

Abbreviations: **QE** = `spotify-api/.../rules/QualificationEvaluatorTest`, **HS** = `spotify-api/.../sync/HistorySyncTest`,
**MP** = `spotify-api/.../sync/M3PipelineTest` (MockWebServer → real `SpotifyClient` → `HistorySync` → account-scoped
in-memory store → `QualificationEvaluator`), **RS** = `app/.../sync/RoomHistoryStoreTest`,
**MR** = `app/.../sync/M3RoomIntegrationTest` (`HistorySync` → `RoomHistoryStore` on in-memory Room via Robolectric →
`QualificationRecorder`), **QR** = `app/.../rules/QualificationRecorderTest`, **DB** = `data/.../local/ReplayDatabaseTest`.

| TC | Topic | Tests (class: method) |
|----|-------|-----------------------|
| TC-001 | 2 plays/day do not qualify | QE `tc001_twoPlaysInOneDayDoNotQualify`; MP `tc001 tc002 tc009 thresholds over synced history` |
| TC-002 | 3rd play/day qualifies at 3rd | QE `tc002_threePlaysInOneDayQualifyAtThird`; MP `tc001 tc002 tc009 …`; MR `exit criterion - repeated sync …` |
| TC-003 | VN midnight day boundary | QE `tc003_dayBoundaryAtVietnamMidnight` |
| TC-004 | 5 plays/72h rolling | QE `tc004_fivePlaysWithin72hAcrossDaysQualifyRolling`; MR `tc004 tc009 threshold changes …`; QR rolling tests |
| TC-005 | Duplicate identity counts once | QE `tc005_duplicateIdentitiesCountOnce`; DB `TC-005 TC-010 re-importing …`; QR re-import test; MR/MP repeated-sync tests |
| TC-006 | Different track IDs separate | QE `tc006_differentTrackIdsCountSeparately` |
| TC-007 | 72h window edges | QE `tc007_rollingWindowExcludesExactly72hAndIncludesJustInside` |
| TC-008 | Late history qualifies at original time | QE `tc008_lateImportedHistoryQualifiesForOriginalDay`; QR `tc008 late history …`; MR `tc026 gap then recovery …` |
| TC-009 | Threshold change never removes | QE `tc009_lowerDailyThresholdQualifiesMoreAndNeverFewer`; QR `tc009 lowering daily threshold …`; MP `tc001 tc002 tc009 …`; MR `tc004 tc009 threshold changes …` |
| TC-010 | Re-import / repeated sync stable | HS `tc010_tc011_overlappingWatermarkImportsEachEventOnce`; RS `tc010 tc011 …`; DB `TC-005 TC-010 …`; MP `tc010 tc011 multi-page baseline then repeated syncs …`; MR `exit criterion - repeated sync …` |
| TC-011 | Inclusive overlap at watermark | HS `tc010_tc011_…`; RS `tc010 tc011 …`; MP `tc011 overlap inclusive …` |
| TC-026 | Unbridged gap visible, then recovery | HS `tc026_unbridgedGapKeepsOldHighWaterAndIsVisible`; RS `tc026 gap is stored and accounts stay isolated`; MP `tc026 unbridged gap … then recovery …`; MR `tc026 gap then recovery …` |
| TC-027 | Invalid items filtered | HS `tc027_invalidItemsAreFilteredNotImported`; MP `tc027 local episode and null-id items are filtered over HTTP` |

Additional TASK-033 coverage (exit criterion and robustness):

| Scenario | Tests |
|----------|-------|
| Coverage UNKNOWN / PARTIAL_BOOTSTRAP / COMPLETE_SINCE_BASELINE / HISTORY_INCOMPLETE | MP `empty history is UNKNOWN …`; MR `page cap baseline …`; MR/MP gap and 429 tests |
| 429 mid-run: earlier pages kept, high-water held, HISTORY_INCOMPLETE + `RATE_LIMITED`, retry bridges | MP `429 mid-run …` (also asserts client fail-fast inside Retry-After sends no request); MR `rate limit mid-run …` |
| Quota exceeded / 503 on first page change nothing | MP `quota exceeded and server error …` |
| Account isolation (events, sync_state, qualifications, outbox) | MP `accounts are isolated end to end`; MR `accounts are isolated …`; RS `tc026 …` |
| Stable qualification + outbox rows across repeated sync/evaluate | MR `exit criterion - repeated sync …` |

## Limitations

- No single test spans HTTP and Room: MockWebServer is only on `:spotify-api`'s test classpath and
  `SpotifyClient.create(baseUrl…)` is `internal`. Adding it to `:app` would change dependencies/lockfiles, so
  HTTP→sync is covered in MP and sync→Room→outbox in MR; both use the same production `HistorySync`.
- Time is injected (fixed clock; MP injects `nanoTime` to step past Retry-After). No live Spotify, device or CI run.
- Not wired to workers/UI (M4/M5 out of scope).
