# M4 — TASK-041: Remote playlist pagination and reconcile

Status: **PASS — JVM tests and local build on 2026-11-05.** Not run against live Spotify, a device or CI.

## Scope
- `PlaylistMembershipReader` (`:spotify-api`, pure): reads all items of a playlist by offset pages (limit 50),
  returning `RemoteMembership.Complete` or `Incomplete(API_ERROR | INCONSISTENT_PAGINATION | PAGE_CAP)`.
- `QualificationDao.reconcileOutbox` + pure `reconcileRow` (`:data`): one Room transaction, scoped to
  (account, playlist).
- `PlaylistReconciler` (`:app`): `ManagedPlaylistVerifier` gate → full read → account re-check → reconcile.
- Out of scope: POST/state-machine execution, ambiguous-POST verification (TASK-042), TASK-043, workers, UI.

## Decisions
- Pagination by offset only; the `next` URL is never followed (only checked for consistency with total).
- Fail closed (no state change) on: API error (429/5xx/quota, Retry-After surfaced), offset/total mismatch,
  changing total, total > 10,000, offset > total, empty non-final page, next/last-page disagreement, page cap (201).
- Ignored entries: local files, episodes/non-track types, null items, invalid IDs. Duplicates collapse; both
  `item.id` and `linked_from.id` count; earliest `added_at` kept.
- Transitions: present → PRESENT (from PENDING/CHECKING_REMOTE/RETRY_LATER/SENDING/VERIFY_REMOTE, `added_at_ms`
  from remote); absent: CHECKING_REMOTE/RETRY_LATER → PENDING, PENDING unchanged; PRESENT absent →
  REMOVED_EXTERNALLY; REMOVED_EXTERNALLY and BLOCKED never change. Nothing is removed or written to Spotify.
- Account switch during the read → `NotWritable(RelinkRequired(OWNER))`, nothing applied.
- **No schema change**: reuses `added_at_ms` / `last_attempt_ms`; snapshot id is returned, not persisted.

## Traceability
| TC | Test |
|---|---|
| TC-012 already present | PlaylistReconcilerTest "TC-012 …", idempotent repeat |
| TC-015 manual removal | PlaylistReconcilerTest "TC-015 …" (re-add stays REMOVED_EXTERNALLY) |
| TC-016 playlist deleted | PlaylistReconcilerTest "TC-016 gate …" |
| TC-028 > one page | PlaylistMembershipReaderTest multi-page (MockWebServer) |
| Partial failure | Reader 429/5xx/malformed/repeated offset/inconsistent/page cap; Reconciler "incomplete read …" |
| Isolation | PlaylistReconcilerTest "account isolation …" |

## Ambiguities / limitations
- REMOVED_EXTERNALLY seen present again stays REMOVED_EXTERNALLY (no documented transition).
- SENDING/VERIFY_REMOTE absent is left unresolved for TASK-042.
- No `market` parameter, so `linked_from` may not be returned by Spotify in practice.
- Not wired to workers/UI; no live Spotify/device/CI evidence. 262 JVM tests total.
