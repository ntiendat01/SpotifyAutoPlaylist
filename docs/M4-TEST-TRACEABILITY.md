# M4 test traceability (TASK-043)

Status: **PASS — 2026-11-05, JVM tests and local build only.** Nothing here ran against live Spotify,
a device/emulator, or CI (CI not yet observed for this commit). 306 JVM tests total (22 added by TASK-043),
0 failures/skipped.

Abbreviations: **RI** = `app/.../playlist/M4RecoveryIntegrationTest` (play events → `QualificationRecorder` →
outbox → `OutboxWriter` → `PlaylistReconciler` → `ManagedPlaylistVerifier` on in-memory Room via Robolectric,
against a stateful fake remote playlist with failure injection; a fresh writer stack per run models process
restart), **HB** = `spotify-api/.../playlist/AddItemsReadBackHttpTest` (stateful MockWebServer dispatcher →
real `SpotifyClient` → `PlaylistMembershipReader` + `addItemsOutcome`, with a test driver mirroring the writer
loop), **OW** = `app/.../playlist/OutboxWriterTest`, **PR** = `app/.../playlist/PlaylistReconcilerTest`,
**MV** = `app/.../playlist/ManagedPlaylistVerifierTest`, **MB** = `spotify-api/.../playlist/PlaylistMembershipReaderTest`,
**AO** = `spotify-api/.../playlist/AddItemsOutcomeTest`, **QR** = `app/.../rules/QualificationRecorderTest`.

Every RI scenario ends with `assertExactlyOnce` (each qualifying track exactly once remotely; exactly one
qualification and one outbox row, state PRESENT; every PRESENT row is remote; the fake records no blind re-POST
of a present track, no batch > 100, no duplicate ID in a POST) and, where the scenario converges, `assertStable`
(3 more evaluate + write runs send nothing and change nothing locally or remotely).

| TC | Definition (07) | Tests (class: method) |
|----|-----------------|-----------------------|
| TC-012 | Already present remotely → no POST, PRESENT | PR `TC-012 present remotely …`; OW `TC-012 already present …`; RI `TC-012 tracks already in the remote playlist (beyond page 1) …` |
| TC-013 | Absent remotely → one POST, then PRESENT | OW `TC-013 happy path …`; RI `TC-013 TC-028 batch boundaries 0 1 99 100 101 250 …`; HB `batch boundaries …` |
| TC-014 | POST succeeded, response lost → VERIFY_REMOTE, remote read, no duplicate POST | OW `TC-014 …`, `ambiguous POST that failed …`, `… incomplete verification read …`, `crash with SENDING rows …`; RI `TC-014 timeout/5xx where the add applied …` (×2), `TC-014 timeout/5xx … did not apply …` (×2), `TC-014 … verification read fails …`, `process death after the durable claim …`, `process death after the POST applied …`, `repeated crashes and faults …`; HB `TC-014 …` (×2); AO 5xx/timeout/malformed |
| TC-015 | Manual removal of PRESENT track → REMOVED_EXTERNALLY, not re-added | PR `TC-015 …`; OW `TC-015 …`; RI `TC-015 manual removal … never re-added, even after crashes and re-evaluation` |
| TC-016 | Playlist deleted → pause writes; request relink | MV `TC-016 deleted playlist requires relink …`; PR `TC-016 gate …`; OW `TC-016 404 on POST …`; RI `TC-016 playlist deleted remotely …` (pause + RelinkRequired only); HB `TC-025 TC-016 403 and 404 …` |
| TC-017 | Added track falls below thresholds → stays, no removal call | RI `TC-017 TC-009 threshold changes feed the writer and never remove anything`; QR `tc009 …` (local); no removal endpoint exists in `SpotifyClient` |
| TC-028 | Remote playlist > one page → all pages checked before writing | MB `TC-028 all pages are read by offset …`; OW `TC-028 batches …`; RI `TC-012 … beyond page 1`, batch boundaries; HB batch boundaries (551 items, 12 pages) |

Other M4-relevant cases exercised by the same suites:

| Case | Tests |
|------|-------|
| TC-020 401 after refresh → re-auth, then sent once | AO 401 tests; RI `TC-020 401 after refresh …` |
| TC-021/022 429 Retry-After / quota | AO `429 …`; OW `429 honours Retry-After …`; RI `TC-021 TC-022 …`; HB `TC-021 …` |
| TC-025 403 explicit stop | OW `TC-025 …`; RI `TC-025 …`; HB `TC-025 TC-016 …` |
| Account isolation / switch mid-run | MV `TC-033 TC-034 …`; PR, OW account tests; RI `accounts are isolated …` |
| Attempt cap → BLOCKED | OW `attempt cap …` |

## Exit criterion

"Qualifying track appears exactly once in tested normal and recovery paths": **met at JVM level only**, for the
scenarios above, against fakes/MockWebServer. Device/live-Spotify gates remain open (no live run, no device run).

## Bugs found

None. No production code changed in TASK-043.

## Limitations

- RI and HB are separate: MockWebServer is not on `:app`'s test classpath (no new dependency added), so no
  single test spans real HTTP and Room. HB's driver mirrors, but is not, `OutboxWriter`.
- Process death is modelled by an exception thrown from the POST lambda plus a fresh writer stack over the same
  Room instance; a real kill (e.g. mid-transaction) is not simulated. Room transactions are trusted.
- The fake remote is consistent immediately; Spotify read-after-write lag is not modelled.
- TC-016 relink to a new playlist: closed by TASK-052 ([M5-ERROR-HANDLING.md](M5-ERROR-HANDLING.md)); previously there was no flow that re-points outbox rows to a newly confirmed playlist
  (TASK-040..042 only pause and report `RelinkRequired`); tests assert the pause only.
- RETRY_LATER rows are re-sent in the next run after a complete read; there is no backoff (TASK-051).
