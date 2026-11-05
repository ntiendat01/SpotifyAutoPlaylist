# M5 — TASK-052 failure handling

Status: JVM tests and local build only. Nothing here has run against live Spotify, a device or CI.

## Audit (pre-existing M3–M5 behaviour)

- Token refresh: 400/401 → `REAUTH_REQUIRED`, grant discarded; network/5xx/429 → `NETWORK`/`TOKEN_HTTP`, grant kept.
- `SpotifyClient` refreshes once after a 401, then surfaces it; no POST loop.
- 403 / 429 / quota: already handled by `OutboxWriter` and the TASK-051 persisted Retry-After/quota window (verified, not duplicated).
- Restore returns `SignedOut(ACCOUNT_MISMATCH | RESET_PENDING)` on grant/binding mismatch.
- Gaps closed: (1) every pre-key `AuthException` was reported as `AUTH_REQUIRED`, so offline-during-refresh looked like
  sign-in required; (2) a pass writer used the live session, not the pass account; (3) no relink flow (TC-016);
  (4) `insertMissingOutbox` would have created new PENDING rows for PRESENT/REMOVED_EXTERNALLY tracks on a new playlist.

## Failure matrix

| Condition | Detection | State / outcome | Persisted status (`errorCode`) | Retry |
|---|---|---|---|---|
| Offline / DNS / timeout during history | IOException in HistorySync | NETWORK_ERROR; cursors/events not advanced | `NETWORK` | WorkManager backoff (bounded), next period |
| Offline / 5xx / malformed during token refresh | `AuthException(NETWORK, TOKEN_HTTP≥500, TOKEN_RESPONSE)` | NETWORK_ERROR, grant kept | reason name | backoff |
| 429 on refresh | `TOKEN_HTTP 429` | RATE_LIMITED | `TOKEN_HTTP` | next period |
| Refresh token revoked / 400/401 on refresh | `REAUTH_REQUIRED` | AUTH_REQUIRED, no POST | `REAUTH_REQUIRED` | none until user signs in |
| 401 on API after one refresh | `SpotifyApiException` 401 | AUTH_REQUIRED; row stays RETRY_LATER, added once after reconnect | kind name | none until re-auth |
| 403 | API 403 | FORBIDDEN, writes stop | kind name | next period / manual (no backoff) |
| 429 / quota | Retry-After | RATE_LIMITED / QUOTA_EXCEEDED, window persisted | kind name | after window |
| Account mismatch / reset pending at restore | SignedOut reason | ACCOUNT_MISMATCH, no history, no writes | reason (only when an account key exists) | none |
| Session switched mid-pass | `boundAccount` / writer key check | ACCOUNT_MISMATCH or ACCOUNT_CHANGED; no writes for the other account | reason | none |
| Playlist deleted / owner changed / public / collaborative | `ManagedPlaylistVerifier` | RELINK_REQUIRED, writes paused | `RELINK_<reason>` | after relink |

## Relink (TC-016)

`SyncRepository.relinkPlaylist(link, confirmed)` → `PlaylistRelinker`, run under the per-account `exclusive` lock
(waits for a running pass). It requires explicit confirmation, a current mapping that the verifier reports as
RelinkRequired, and a candidate that exists, is owned by the signed-in account, private and not collaborative.
It never creates a playlist or POSTs. In one Room transaction (`QualificationDao.relink`) it re-checks the expected
old id, updates the managed row and moves open rows (PENDING, CHECKING_REMOTE, SENDING, VERIFY_REMOTE,
RETRY_LATER, BLOCKED) to the new id as PENDING with attempts reset. PRESENT and REMOVED_EXTERNALLY rows stay on
the old id; `insertMissingOutbox` never creates a new intention for a track that has a row on any playlist, so
REMOVED_EXTERNALLY is never re-added and exactly-once holds. A conflict with an existing row on the new playlist
keeps that row. On success a manual sync is requested. No UI (Settings is out of scope); state is exposed only.

No Room schema change.

## TC traceability

Covered here (JVM, `SyncFailureHandlingTest`, `SyncWorkerTest`): TC-016 relink, TC-019/020 (401/refresh/revoked),
TC-021/022 (429/quota), TC-025 (403), offline/no-data-loss, account mismatch/isolation, sanitized logs.
Left for TASK-053: the same cases on a device and live Spotify (TC-018..025, TC-029..030), OxygenOS background delays.

## Limitations / ambiguities

- Relink links an existing owned private playlist only; no create-on-relink.
- Tracks already PRESENT on the deleted playlist are not re-added to the new one (interpretation of "still eligible").
- 403 is not retried by backoff.
- A mismatch before any account key exists records no status.
- Single-flight / exclusive locks are in-process only.
- Not run on live Spotify, a device or CI.
