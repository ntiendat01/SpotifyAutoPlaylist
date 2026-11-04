# Spotify AutoPlaylist — MVP Specification Index

> Version: 1.3.0 · Status: **Baseline / implementation-ready with external integration gates** · Updated: 2026-11-04  
> Language: Vietnamese · Target: OnePlus Ace 5 / OxygenOS 16 · Owner: Personal project

## Product at a glance
Spotify AutoPlaylist is a personal, non-commercial Android application. It observes play events made available by Spotify's Recently Played API, applies repeat-listening rules, and **adds qualifying Spotify tracks to one real, private Spotify playlist**. The playlist persists after uninstall. The app never removes items automatically.

**Rules (inclusive):** >= 4 play events in a Vietnam calendar day **OR** >= 5 play events in any rolling 72-hour interval. A Spotify play event is not proof of a complete listen.

## Documentation map

| File | Purpose | Owner of truth |
|---|---|---|
| [01-PRD.md](01-PRD.md) | Scope, users, stories, requirements, success | Product behavior |
| [02-BUSINESS-RULES.md](02-BUSINESS-RULES.md) | Exact thresholds, temporal semantics, edge cases | Counting and qualification |
| [03-ARCHITECTURE.md](03-ARCHITECTURE.md) | Modules, flows, state machines, scheduling | System design |
| [04-SPOTIFY-API.md](04-SPOTIFY-API.md) | API contract, OAuth, pagination, quota | External integration |
| [05-DATABASE.md](05-DATABASE.md) | Entities, indexes, transactions, migrations | Persistent data |
| [06-UI-UX.md](06-UI-UX.md) | Spotify Neo × Replay Lab: five experiences, design tokens, navigation, states, accessibility | UI contract |
| [07-TEST-PLAN.md](07-TEST-PLAN.md) | Test matrix, fixtures, acceptance | Quality gate |
| [08-IMPLEMENTATION.md](08-IMPLEMENTATION.md) | Tasks, CI/APK, release checklist | Execution plan |

## Binding decisions
- One Spotify account and one managed playlist per installation in v1; no cloud backend, root, Accessibility or notification access.
- **AUTH-006 account policy:** use the same *new Premium Spotify account* as both Developer App owner and Spotify listener/login. Create a new developer app/client ID under that account. Never rely on the old account's developer client after its Premium expires.
- Switching accounts is an explicit disconnect/reconnect flow; no automatic migration of listening history, tokens or playlist ownership.
- Native Kotlin / Jetpack Compose / MVVM + repositories / Room / DataStore / WorkManager.
- Mac development without Android Studio: VS Code and GitHub Actions for building; physical OnePlus for testing.
- Spotify Premium Development Mode; OAuth Authorization Code + PKCE, **no client secret**.
- First successful sync establishes a baseline; history may be partial and must never be presented as complete without evidence.
- Scheduled sync is **best effort** (target 30 minutes); manual sync available.
- Play-event identity uses `(spotify_user_id, track_id, played_at_ms)` as a pragmatic approximation, with documented collision risk.
- Playlist write is reconciled against remote state before retry, because API calls are not inherently idempotent.

## Required integration gate before full implementation
**GATE-ACCOUNT-01:** Confirm the newly selected Spotify account owns the Developer App, has active Premium, and is the same user identity returned by `GET /me` after OAuth. Confirm that a playlist created via API belongs to this account.

**GATE-API-01:** On the user's actual Spotify Premium developer app, prove PKCE login, `GET /me/player/recently-played`, `POST /me/playlists`, `GET /playlists/{id}/items`, and `POST /playlists/{id}/items`. Record HTTP status, scopes and sanitized response shapes. Do not assume a documentation listing guarantees access for a particular Development Mode client.

**GATE-AUTH-01:** Prove the loopback OAuth redirect works on OnePlus/OxygenOS, with exact registered URI, before building the full UI. If blocked, adopt a verified HTTPS Android App Link under a domain the developer controls; do not silently switch to an unapproved custom scheme.

## Traceability and change control

Current implementation: TASK-003 OAuth spike is extended with TASK-002A `/me` owner-ID verification.
Local checks: 52 passing JVM tests, lint and debug APK build.
See [M0-ACCOUNT-SPIKE.md](M0-ACCOUNT-SPIKE.md) for current scope and pending OnePlus checks;
[M0-AUTH-SPIKE.md](M0-AUTH-SPIKE.md) retains the original 32-test baseline and build evidence.
GitHub Actions run/fix evidence is recorded in the spike report; live Spotify/device gates remain pending.
Document status remains Baseline until integration gates are verified.

- Requirements: `FR-*`, `NFR-*`; rules: `BR-*`; tests: `TC-*`; implementation tasks: `TASK-*`.
- A change to business logic must update rules, test fixtures and acceptance cases in the same pull request.
- Document status: `Baseline` (this version), `Verified` (after gates), `Implemented`, `Released`.
- All endpoint paths and policies must be checked against Spotify docs again when implementing.

## External references (verified 2026-11-04)
- https://developer.spotify.com/documentation/web-api/reference/get-recently-played
- https://developer.spotify.com/documentation/web-api/reference/create-playlist
- https://developer.spotify.com/documentation/web-api/reference/get-playlists-tracks
- https://developer.spotify.com/documentation/web-api/reference/add-items-to-playlist
- https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- https://developer.spotify.com/documentation/web-api/concepts/redirect_uri
- https://developer.spotify.com/documentation/web-api/references/changes/february-2026
- https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates

## v1.2 design change log
- Adopted **Spotify Neo × Replay Lab** design direction; upgraded `06-UI-UX.md` with full screen contracts and 18 UI tests.
- Five main experiences (Home, History, Collection, Rules, Settings); four bottom tabs and Settings via Home gear. Onboarding is separate.
- No changes to Spotify counting rules, API scopes, account ownership or remote playlist persistence.
- UI visual metrics use verified local/remote data; mock artwork and counts are forbidden in production.

## v1.3 visual handoff change log
- Included all **eight original** Spotify Neo × Replay Lab mockup PNGs: Welcome, Playlist Setup, Home, History, Collection, Rules Studio, Settings & Sync Health, Sync Complete.
- Added `AGENTS.md` and exact image-to-screen/task mapping for Codex; introduced screenshot review and visual DoD.
- Added explicit reconciliation for pictured non-MVP controls, synthetic counts, privacy conflicts and precise sync-time claims.
- **No change** to listening thresholds, real Spotify playlist requirements or Spotify integration gates.
