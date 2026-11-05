# M4 — TASK-040 private playlist create/link with ownership verification

Status: **PASS — 2026-11-05, JVM tests and local build only.** Nothing ran against live Spotify, a
device/emulator or CI.

## Audit: TASK-050B vs TASK-040

| Requirement (source) | Before TASK-040 (050B) | TASK-040 |
|---|---|---|
| Create private, non-collaborative, once (BR-014, API-02) | `PlaylistSetupRepository.create`, journal before POST | unchanged |
| Ambiguous create recovery by marker, no blind retry (BR-014) | journal + bounded own-playlist search; `PlaylistSetupTest` | unchanged |
| Link by URL/URI/ID, reject foreign hosts | `SpotifyResourceId.parse`; tested | unchanged |
| GET verify owner = bound account, public:false, collaborative:false, snapshot (API-07) | at create/link/recover time only | — |
| **Re-verify persisted mapping before writes on later runs (API-07 "before writes")** | **missing**: `load()` returned stored row without GET | `ManagedPlaylistVerifier.verify()` |
| **Deleted playlist → ask relink, never recreate silently (04 §8 404, TC-016 gate)** | **missing** | `RelinkRequired(DELETED)`, mapping kept |
| **Playlist later made public/collaborative or owner changed** | **missing** | `RelinkRequired(PRIVACY/OWNER)` |
| Changed snapshot / renamed playlist | n/a | `Ready` with current snapshot/name (not persisted) |
| Transient/unsupported errors block writes | n/a | `Unavailable(kind, retryable)` |
| Account scoping (AUTH-006, TC-033/034) | per-account mapping + journal; switch during request rejected | verifier reads only the bound account's row; switch mid-GET rejected |
| Mapping persisted for the M4 writer (05 `managed_playlist`) | `RoomSetupPersistence` (`ownership_verified=1`) | consumed by verifier; unverified row → `RelinkRequired(UNVERIFIED)` |

## Scope and decisions

- New `app/.../playlist/ManagedPlaylistVerifier.kt` — a read-only pre-write gate returning
  `WriteTarget` (`NotLinked`, `Ready`, `RelinkRequired(reason)`, `Unavailable`). It never creates,
  recreates, relinks or mutates the mapping; the relink UX/pausing workers belong to later tasks.
- No schema change/migration: snapshot is returned per run, not stored (05 has no snapshot column).
- Not wired into workers/UI (no M4 writer exists yet). Not done: TASK-041 pagination/reconcile,
  TASK-042 outbox state machine/ambiguous POST/external removal, TASK-043 tests.
- 403 is treated as non-retryable `Unavailable`, not relink (could be Development-Mode access, TC-025).

## Tests observed

`ManagedPlaylistVerifierTest` (Robolectric + in-memory Room, fake GET), 8 tests: NotLinked; re-verify on
every run with changed snapshot/name; TC-016 deleted → relink, mapping kept; foreign/null owner,
public/null, collaborative/null; malformed/429/503/403; unverified row; TC-033/034 account scoping and
switch mid-GET; onboarding link → Room → verifier accepts. Existing 050B coverage in `PlaylistSetupTest`
(12 tests) is unchanged. Full suite: 246 JVM tests, 0 failures.

## Limitations

- HTTP mapping of 404 → `NOT_FOUND` relies on existing `:spotify-api` client tests; no MockWebServer in `:app`.
- TC-016 "pause writes" and the relink screen are only the verification gate here; TC-029/031/034 E2E open.
