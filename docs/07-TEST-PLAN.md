# 07 — Test Plan, Test Cases & Acceptance Matrix

Version 1.1.0 · Status: Baseline

## 1. Test strategy
- **Unit:** pure rule evaluator and time-zone calculations; deterministic clock, no network.
- **DAO:** in-memory Room database, unique indexes, transactions, migrations.
- **Integration:** mock HTTP with fixed Spotify fixtures, token refresh, cursor paging, error mapping and outbox reconciliation.
- **E2E:** OnePlus Ace 5 / OxygenOS 16 with real Premium Spotify test account, actual private playlist and signed APK.
- Keep real tokens out of fixtures and CI. Do not rely on live Spotify in automated CI.

TASK-003 automated coverage and pending device checklist: [M0-AUTH-SPIKE.md](M0-AUTH-SPIKE.md).
Prototype tests do not mark live OAuth/account/API gates as passed.

TASK-002A/TC-032 profile verification coverage and device checklist: [M0-ACCOUNT-SPIKE.md](M0-ACCOUNT-SPIKE.md).
No actual account IDs or tokens in fixtures. TC-031/034's owned-playlist E2E portions remain outside this probe.

## 2. Core cases
| ID | Type | Scenario | Expected |
|---|---|---|---|
| TC-001 | Unit | 3 plays on one VN day | Not qualified |
| TC-002 | Unit | 4 plays on one VN day | Qualified at fourth |
| TC-003 | Unit | 3 plays at 23:59 D and 1 at 00:01 D+1 | No daily qualification |
| TC-004 | Unit | 5 events across days within 72h, max 2/day | Rolling qualification |
| TC-005 | Unit | Duplicate same track/timestamp imported twice | Count once |
| TC-006 | Unit | Same name but different Spotify track IDs | Separate counts |
| TC-007 | Unit | Fifth event exactly 72h after first | First excluded; if 4 remain, not qualified |
| TC-008 | Unit | Late sync after calendar day ends | Historic qualification retained |
| TC-009 | Unit | Change daily threshold 4→3 | Recompute, add new qualifying; no removal |
| TC-010 | Integration | Same 50-event page fetched twice | Unique event count unchanged |
| TC-011 | Integration | Multiple pages + overlapping watermark | All accessible events imported once |
| TC-012 | Integration | Track already present in remote playlist | No POST, state PRESENT |
| TC-013 | Integration | Qualified track absent remotely | One POST, then PRESENT |
| TC-014 | Integration | POST succeeds but response lost | VERIFY_REMOTE, remote read, no duplicate POST |
| TC-015 | Integration | User manually removes previously PRESENT track | REMOVED_EXTERNALLY; not re-added |
| TC-016 | Integration | Playlist deleted remotely | Pause writes; request relink/confirm new |
| TC-017 | Unit | Previously added track falls below current thresholds | Remains in Spotify; no removal call |
| TC-018 | E2E | OAuth PKCE connect, state valid | Tokens stored securely, account linked |
| TC-019 | Integration | OAuth state mismatch / expired callback | Reject, no token exchange |
| TC-020 | Integration | 401 refresh then success; refresh failure | Retry once or request re-auth |
| TC-021 | Integration | 429 with Retry-After | Pause and retry after limit |
| TC-022 | Integration | 429 QUOTA_EXCEEDED | Stop request loop; show quota status |
| TC-023 | E2E | OxygenOS delays periodic worker | UI shows last sync accurately; manual sync works |
| TC-024 | E2E | App force stop/restart during sync | No inflated counts or lost committed events |
| TC-025 | Integration | 403 endpoint inaccessible in Development Mode | Explicit unsupported status, no endless retry |
| TC-026 | Integration | Cannot bridge history gap after outage | HISTORY_INCOMPLETE visible, no invented plays |
| TC-027 | Unit | Local files, episodes, null IDs | Ignored, counted as filtered |
| TC-028 | Integration | Remote playlist > one page | All pages checked before writing |
| TC-029 | E2E | Uninstall app after playlist created | Playlist still in Spotify (verify before uninstall) |
| TC-030 | E2E | Offline then reconnect | Local view works, outbox eventually reconciles |
| TC-031 | E2E | Sign in with new Premium account owning Developer App | `GET /me` equals expected owner ID; playlist created under new account |
| TC-032 | Integration | OAuth returns old/different Spotify user ID | Reject account, no history writes, no playlist creation/update |
| TC-033 | Integration | Switch accounts after old account had stored history/playlist | Explicit confirmation; credentials/workers reset; no cross-account data or playlist reuse |
| TC-034 | E2E | Old Spotify account playlist remains after new account connection | Old playlist remains under old account; new playlist created only under new account |

## 3. Fixture requirements
Create deterministic JSON fixtures:
- `recently-played-page-1.json`, `recently-played-page-2.json` with overlapping event and cursor progression.
- `playlist-items-page-1.json`, `playlist-items-page-2.json` using **`item`**, not deprecated `track`.
- `oauth-token-success.json` using dummy tokens only; `quota-exceeded.json`, `permission-denied.json`.
- Time test instants around `2026-11-04T17:00:00Z` (00:00 VN next day), and exact 72-hour boundaries.

## 4. Acceptance traceability
- FR-001 → TC-018..020
- FR-002 → TC-016, TC-018, TC-029
- FR-003 → TC-005, TC-010, TC-011, TC-026, TC-027
- FR-004 → TC-001..009
- FR-005 → TC-012..016, TC-028
- FR-006 → TC-015, TC-017, TC-029
- FR-007/008 → TC-021..024, TC-030
- FR-014 / AUTH-006 → TC-031..034

## 5. CI gates
1. Gradle compile and Kotlin lint/static checks (configured when scaffolded).
2. Unit tests all green; Room DAO tests green.
3. Integration mock tests green.
4. Assemble debug APK and publish artifact.
5. Real E2E gate requires manual signoff on device; never run real Spotify tokens in public CI.

## 6. Release blockers
- AUTH-006 / GATE-ACCOUNT-01 fails (developer owner and authenticated Spotify user mismatch or Premium owner not confirmed).
- Auth callback fails on actual OnePlus.
- Any core endpoint inaccessible for the user's Development Mode app.
- Track qualification off-by-one or timezone boundary failure.
- POST retry can duplicate under tested ambiguous responses.
- Background job falsely claims full history coverage.
- APK leaks tokens or cannot be installed on device.

## 7. E2E evidence template
Record test ID, build SHA, Android/OxygenOS version, timestamp, sanitized HTTP status, observed Spotify playlist ID (mask if shared), actual vs expected, pass/fail, screenshots (no tokens), and any API quota issue.

## UI acceptance suite (v1.2)
Execute `UI-TC-001` through `UI-TC-018` defined in `06-UI-UX.md` as part of M5 acceptance. Include screenshot/manual review on OnePlus Ace 5 OxygenOS 16 for narrow width, 1.3x font scale, TalkBack, reduced motion, empty/offline/partial coverage and remote-pending states. A visually successful UI cannot override a failed domain/API test.

## v1.3 visual reference acceptance (UI-VR-001..010)

| ID | Test | Expected |
|---|---|---|
| UI-VR-001 | All concept assets exist | Eight PNGs present with exact names in `design/reference/` |
| UI-VR-002 | Welcome/Setup | `00`/`01` images matched; no bottom tabs before login |
| UI-VR-003 | Home reference | Screenshot matches major layout hierarchy of `02-home.png`; no fabricated production count |
| UI-VR-004 | History | Search, filter, detail and coverage match spec and `03-listening-history.png` |
| UI-VR-005 | Collection | Remote confirmed and pending are separated as defined, regardless of visual example |
| UI-VR-006 | Rules | OR logic remains correct; no extra unimplemented toggle, thresholds editable and persisted |
| UI-VR-007 | Settings | No invented Premium/public privacy status; no unsupported notification feature |
| UI-VR-008 | Manual sync result | 0/positive/error outcomes accurate; never invent exact next WorkManager time |
| UI-VR-009 | Production-fixture boundary | Album covers, dates, tracks, play counts, usernames, sync state from mocks never appear in production without real data |
| UI-VR-010 | Native and accessible | Compose interactive controls, scroll/insets, TalkBack, text scale, 48dp hit targets, reduced-motion |
