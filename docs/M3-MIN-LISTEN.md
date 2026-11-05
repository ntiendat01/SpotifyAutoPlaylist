# M3 — Minimum-listen gate (TASK-055)

**Status: implemented locally on `Dev` (JVM tests).** Forward-looking counting only; no Room schema
change; no live Spotify verification in this task.

## Rule (BR-016)
A stored play counts toward daily (≥3 / VN day) or rolling (≥5 / 72h) **only if** the gap estimate says
it was listened for at least 30 seconds (or the full track when duration &lt; 30s).

Estimation (Spotify gives `played_at` end time + optional `duration_ms`, not listened duration):
- Order the account’s deduped plays by `played_at` (any track; tie-break `trackId`).
- For play P with chronological predecessor Q: `gap = P.played_at - Q.played_at`.
- Threshold = `min(30_000ms, duration_ms(P))` when duration is known and positive, else `30_000ms`.
- If `gap < threshold` → exclude P from counting (definitely too short). Else include.
- Oldest play has no predecessor → always counts.
- Predecessor is the previous play in full history even if that predecessor itself was excluded.

## Pipeline placement
**Applied in `QualificationEvaluator` via `MinListenFilter`**, before daily/rolling scans.

| Stage | Behaviour |
|---|---|
| `HistorySync` | Unchanged: still imports every valid play; BR-004 dedupe via insert-or-ignore. Short listens remain visible in History. |
| `QualificationRecorder` / Rules preview | Re-reads all Room events each pass → filter + evaluate. Idempotent. |
| Qualifications / outbox | Monotonic (`INSERT OR IGNORE`). Already-added tracks are **never** removed when a past play fails the new gate. |

Why not filter only inside `HistorySync`? Page-by-page import (TX-01) can miss the true predecessor until
an older page arrives; evaluation over the full stored history is deterministic across re-syncs.

## Room schema
**No change (version 1).** Duration is not persisted. Runtime counting therefore uses the 30s threshold
whenever `PlayRecord.durationMs` is null (the Room path). Short-track `min(30s, duration)` is implemented
in `MinListenFilter` and covered by unit tests when duration is supplied on the record.

## UI / docs
- `RulesScreen`: Vietnamese copy mentions *“chỉ tính lượt nghe từ 30 giây trở lên (ước lượng)”*.
- `docs/02-BUSINESS-RULES.md` BR-016 + EX-011..014; this file; M3-QUALIFICATION cross-link.

## Tests
`MinListenFilterTest`: gap &lt; 30s excluded; exactly 30s counts; first-of-batch; short-track;
cross-track predecessor; skipped play still precedes next; daily + rolling affected; dedupe + reshuffle
idempotency. Existing evaluator/recorder/library fixtures updated to ≥30s spacing.

## Known limits
- Gap ≥ threshold does **not** prove a full listen (user may skip then wait).
- Without stored duration, tracks shorter than 30s with gap in `[duration, 30s)` are over-excluded.
- Pre-existing qualifications stay even if some of their original plays would now fail BR-016.
- Client ID / Spotify user IDs must never appear in docs, commits, or reports.
