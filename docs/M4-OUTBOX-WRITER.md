# M4 — Outbox writer (TASK-042)

Status: **PASS — 2026-11-05, JVM tests and local build only.** Not run against live Spotify, a device or CI.
Not wired to workers or UI (TASK-050/051). Broad TC suite is TASK-043.

## Scope
- `AddItemsOutcome` (spotify-api): classifies an add-items POST as `Added`, `Ambiguous`, `Throttled`, `Rejected`.
- `OutboxTransitions` + `QualificationDao.claimForSend / settleSent / resumeInterrupted` (data): transactional,
  account-scoped outbox transitions.
- `OutboxWriter` (app): resume → verifier gate + reconcile → POST batches of ≤100 in qualification order
  (qualifiedAt, then track id) → settle. Account re-checked before every batch.

## State table
| From | Event | To |
|---|---|---|
| PENDING | claim (attempts < 5), committed before POST | SENDING (attempt+1) |
| PENDING | claim at attempt cap | BLOCKED (`ATTEMPTS_EXHAUSTED`) |
| SENDING | 201 | PRESENT (addedAt = now) |
| SENDING | 5xx / timeout / network / malformed | VERIFY_REMOTE |
| SENDING | 401 after refresh, 403, 404, 429/quota, 4xx | RETRY_LATER + error code |
| SENDING (left over after crash) | next run start | VERIFY_REMOTE (`INTERRUPTED`) |
| VERIFY_REMOTE / SENDING | re-read: present | PRESENT |
| VERIFY_REMOTE / SENDING | re-read: absent | RETRY_LATER (`VERIFY_ABSENT`), or BLOCKED at cap |
| RETRY_LATER | next reconcile, absent remotely | PENDING |
| PRESENT | absent remotely | REMOVED_EXTERNALLY (terminal, never re-added) |

## Decisions
- No schema change; no new dependencies.
- 03 §5 vs 05 TX-04 mismatch: SENDING is the durable pre-POST marker; leftover SENDING rows are treated as
  VERIFY_REMOTE on resume, which satisfies TX-04's intent.
- After an ambiguous POST the writer re-reads remote membership and stops; it never re-POSTs in the same run.
  An incomplete re-read leaves rows in VERIFY_REMOTE (`VERIFY_PENDING`).
- 403 → `FORBIDDEN`; 404 → `RELINK_REQUIRED(DELETED)` (pause); 429 → `THROTTLED` with Retry-After, remaining
  batches stay PENDING; 401 twice → `AUTH_REQUIRED`. These rows go to RETRY_LATER, not BLOCKED, so they recover.
- Attempt cap `MAX_SEND_ATTEMPTS = 5` is an internal constant (docs give no number).

## TC traceability (OutboxWriterTest / AddItemsOutcomeTest)
TC-012 (external removal), TC-013 (happy path + idempotent rerun), TC-014 (ambiguous applied / failed →
retry next run), TC-015, TC-016 (404 relink), TC-025 (403), TC-028 (205 rows → 100/100/4 in order),
429 Retry-After, crash-resume of SENDING, account isolation, account switch mid-run, attempt cap.

## Limitations
- Writer must be serialized by the caller (TASK-051); backoff scheduling is TASK-051.
- Fakes + MockWebServer only; real Spotify eventual consistency not exercised.
