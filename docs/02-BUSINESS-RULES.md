# 02 — Business Rules & Temporal Semantics

Version 1.1.0 · Status: Baseline

## Canonical event
A valid event `PlayEvent(userId, trackId, playedAt: Instant)` is one item returned by Spotify Recently Played with non-null track ID, type `track`, `is_local != true`, valid `played_at` timestamp, and belonging to the authenticated account. **One record = one play event**; it does not imply completion of the song. Podcasts, local files and invalid IDs are excluded. A replay event on a different device may be included if Spotify returns it.

## Rules
| ID | Exact behavior |
|---|---|
| BR-001 | **Daily:** `count(track, localDate D, Asia/Ho_Chi_Minh) >= dailyThreshold` (default 4) qualifies the track. Calendar day is `[D 00:00, D+1 00:00)` local. |
| BR-002 | **Rolling:** any interval `(t - 72 hours, t]` ending at a recorded event time `t` containing >= rollingThreshold (default 5) events of same track qualifies. At exact `t - 72h` event is excluded; at `t` included. |
| BR-003 | Eligibility is BR-001 **OR** BR-002. Evaluate after ingesting a batch, including historical event times, so late sync does not discard a previously crossed threshold. |
| BR-004 | Deduplicate imported events using `(user_id, track_id, played_at_ms)` unique index. Acknowledge possible collisions if same track has identical millisecond timestamps; source lacks a guaranteed unique play ID. |
| BR-005 | Track identity = Spotify Track ID; different versions/album releases count separately. Do not merge on name or artist. |
| BR-006 | Once qualified, persist a monotonic `qualified_at` and create a pending playlist intention. Qualification never reverses in v1. |
| BR-007 | Before adding, reconcile current Spotify playlist items; if track ID exists, mark `PRESENT` and do not POST. |
| BR-008 | After successful add, mark `PRESENT`; if HTTP response lost/ambiguous, mark `VERIFY_REMOTE`, never blind-retry POST. |
| BR-009 | Never remove or reorder remote items automatically. If a user manually removes a previously added track, MVP **does not re-add it** unless the user explicitly resets its tracking state (not implemented in v1); local state becomes `REMOVED_EXTERNALLY`. |
| BR-010 | When thresholds change, re-evaluate all stored events; only newly qualifying tracks are queued; previously qualified/added remain. |
| BR-011 | Timezone fixed `Asia/Ho_Chi_Minh` for daily grouping, independent of travel or device timezone changes. |
| BR-012 | Store timestamps in UTC epoch milliseconds; reject invalid/future events beyond 5 minutes of observed server/device clock with diagnostic (do not silently count). |
| BR-013 | All state is scoped by Spotify user ID; switching accounts requires explicit disconnect/reset or a separately isolated dataset (multi-account out of scope). |
| BR-014 | A playlist is created private (`public:false`), non-collaborative, once; on lost local ID, search own playlists by a stored marker/ID when possible, require user confirmation before creating another to avoid duplicates. |
| BR-015 | If history completeness cannot be established, continue using available events but display partial coverage warning; do not infer missing plays. |

## Counting algorithm (pure domain function)
1. Group valid, deduplicated events by `trackId`; sort ascending by `playedAt`.
2. Daily: for each event, increment count of `(trackId, LocalDate(playedAt, Asia/Ho_Chi_Minh))`; first timestamp when count reaches threshold becomes daily qualification candidate.
3. Rolling: use two pointers on sorted times, advancing left while `time[left] <= time[right] - 72h`; when window count >= threshold, candidate is `time[right]`.
4. `qualifiedAt = min(dailyCandidate, rollingCandidate)` among available candidates; persist once (never overwrite an earlier existing qualification).
5. Emit one `PendingPlaylistAdd` per unique `(userId, playlistId, trackId)` if not previously tracked.

Complexity O(n log n) per track for sort; O(n) scan after sort. Use `java.time` (with compatible Android API/desugaring) rather than system-default time zone.

## Boundary examples
| Case | Event distribution | Expected |
|---|---|---|
| EX-001 | 3 events same VN day | Not qualified |
| EX-002 | 4 events same VN day | Qualified at fourth event |
| EX-003 | 3 events 23:59 day D, 1 event 00:01 day D+1 | Not daily-qualified (unless rolling >=5) |
| EX-004 | 2 events D-2, 2 events D-1, 1 event D within 72h | Rolling-qualified at fifth |
| EX-005 | 5th event exactly 72h after 1st, with only 4 in `(t-72h,t]` | Not rolling-qualified |
| EX-006 | Same event fetched twice | Count once |
| EX-007 | Four historical events imported after midnight | Still daily-qualified for original day |
| EX-008 | User changes 4/day to 3/day | Previously stored three-event day now qualifies; never remove existing |
| EX-009 | Track already in Spotify playlist manually | Mark PRESENT; no POST |
| EX-010 | POST succeeded but response timed out | Re-read remote before retry |

## Notes on limitations
- The API cannot prove the user listened for 30 seconds or completed the track. No duration-based rule in MVP.
- Track relinking and identical audio under multiple Spotify IDs are deliberately not merged.
- For `played_at` collision within the same track and same millisecond, dedup may undercount; log diagnostic only if detectable.
- Retention: keep event history while app installed; optional future pruning must preserve qualification and needed 72-hour calculations. No automatic pruning in MVP.
