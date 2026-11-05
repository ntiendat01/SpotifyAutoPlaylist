# Spotify AutoPlaylist — MVP Specification Index

> Version: 1.3.0 · Status: **Baseline / TASK-004 user signoff recorded; full gate evidence incomplete** · Updated: 2026-11-04
> Language: Vietnamese · Target: OnePlus Ace 5 / OxygenOS 16 · Owner: Personal project

## Product at a glance
Spotify AutoPlaylist is a personal, non-commercial Android application. It observes play events made available by Spotify's Recently Played API, applies repeat-listening rules, and **adds qualifying Spotify tracks to one real, private Spotify playlist**. The playlist persists after uninstall. The app never removes items automatically.

**Rules (inclusive):** >= 3 play events in a Vietnam calendar day **OR** >= 5 play events in any rolling 72-hour interval. A Spotify play event is not proof of a complete listen.

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

Current implementation: TASK-004 extends the OAuth/identity spike with manual Recent and private-playlist API checks.
**TASK-004: PASS — user-reported on 2026-11-04. TASK-005: outcome and deviation recorded.**
The user reports success after making the playlist private in Spotify, then signs off TASK-004.
Automatic private creation is not separately proven by that manual privacy change; no per-endpoint
HTTP statuses, installed APK checksum or exact device version were supplied with this signoff.
Historical local evidence: 84 passing JVM tests, lint and a signed debug APK (`0.3.0-api-spike`);
these checks were not rerun for the documentation-only TASK-005.
See [M0-API-SPIKE.md](M0-API-SPIKE.md) for the device outcome, privacy observation and gate evidence limits;
[M0-ACCOUNT-SPIKE.md](M0-ACCOUNT-SPIKE.md) retains the 52-test identity baseline;
[M0-AUTH-SPIKE.md](M0-AUTH-SPIKE.md) retains the original 32-test baseline and build evidence.
GitHub Actions run/fix evidence is recorded in the spike report; no new CI run is claimed for TASK-005.
Document status remains Baseline until full OAuth/account/API gate evidence is verified. Overall
TASK-004 signoff does not certify all device recovery cases, Premium/app ownership or automatic private
creation.

**TASK-010: PASS — local scaffold verification on 2026-11-04.** Reused the existing Android/Compose
project, moved SDK pins into the version catalog and explicitly pinned Build Tools 35.0.0. A fresh
offline rerun observed 84 passing JVM tests, lint and debug APK assembly; signature verified.
See [M1-ANDROID-SCAFFOLD.md](M1-ANDROID-SCAFFOLD.md) for wrapper hashes, artifact evidence and
compatibility limits. TASK-011 subsequently verified the TASK-010 build configuration on GitHub Actions.

**TASK-011: PASS — CI and downloaded artifacts verified on 2026-11-04.**
[Run 41860277255](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41860277255),
commit `f7a1f01` on `codex/task-011-ci`, passed 84 JVM tests, lint, APK assembly, signature verification
and artifact uploads. Downloaded APK checksum/source SHA/signature/metadata and uploaded JUnit/lint
XML were independently checked. See [M1-ANDROID-CI.md](M1-ANDROID-CI.md) for artifact links and
non-blocking action/runner maintenance notices. The CI branch has not been merged into `Dev`/`main`.
Next planned task: TASK-012, OnePlus installation/launch/navigation acceptance.

**TASK-012: PASS — installed and launched on OnePlus Ace 5 on 2026-11-04.**
CI APK from TASK-011 (commit `f7a1f01`, SHA-256 `bd348c30…`) installed via ADB after
uninstalling a signature-incompatible local build. Cold launch 506 ms, all UI elements
confirmed via UI automator dump, FLAG_SECURE verified. Device: CPH2691, Android 16
(API 36), OxygenOS 16.0.2.400. See [M1-ANDROID-DEVICE.md](M1-ANDROID-DEVICE.md).
Next planned task: TASK-013, application ID, signing strategy, versionCode/versionName.

**TASK-013: PASS — local build and signature verification on 2026-11-04.**
Application ID `io.github.ntiendat01.replay` (debug `.debug`), version `0.4.0` / versionCode 400
derived from `gradle.properties`. Release signed with an owner-held key outside the repository
(certificate SHA-256 `67:18:88:93…`); CI builds the release variant unsigned. 84 JVM tests and lint
passed locally. No CI run or device install of the new ID observed yet; M1 exit still needs both.
See [M1-ANDROID-SIGNING.md](M1-ANDROID-SIGNING.md).
CI [run 41867386428](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41867386428) later passed for
commit `76ea783`; device install of the new ID remains open.

**TASK-020: PASS — JVM tests and local build on 2026-11-04.** Production auth core: PKCE flow with
listener lifecycle, refresh-token-retaining exchange, `/me` identity (`id` == owner, `account_id` required),
AES-GCM/Keystore encrypted grant file and mutex-serialized refresh with rotation and 401 handling.
108 JVM tests passed. Not yet wired into UI; Keystore and live refresh unverified on device.
See [M2-AUTH.md](M2-AUTH.md).

**TASK-020A: PASS — JVM tests and local build on 2026-11-04.** Local data is bound to a hashed
`account_id` that survives sign-out; another account is rejected until an explicitly confirmed,
crash-resumable account reset clears grant, account data (via reset steps) and binding. Repositories
obtain an `AccountKey` only for the bound signed-in account. 115 JVM tests passed; no UI or device check.
**TASK-021: PASS — JVM (Robolectric) tests and local build on 2026-11-04.** New `:data` module with the
Room v1 schema (exported to `data/schemas/`, CI checks it is committed), TX-01/TX-03 transactions,
account-scoped DataStore settings and a local-data reset step for account change. 126 JVM tests passed;
no UI wiring or device run. See [M2-DATA.md](M2-DATA.md).

**CI note (2026-11-05):** Android CI run #22 (TASK-040) failed while Robolectric downloaded its Android
runtime jar (network IOException, not a test assertion). The workflow now pre-fetches that jar with retries
and a checksum check; see [M1-ANDROID-CI.md](M1-ANDROID-CI.md). Not yet observed passing on CI.

**TASK-022: PASS — JVM tests and local build on 2026-11-05.** New `:spotify-api` module: Retrofit client
for recently played and playlist endpoints, DTOs, HTTP status/429/quota mapping, one 401 refresh-retry,
ambiguous POSTs flagged for remote verification, and logs limited to endpoint/status/duration.
149 JVM tests passed; no live API call with this client yet. See [M2-SPOTIFY-API.md](M2-SPOTIFY-API.md).

**TASK-030: PASS — JVM tests and local build on 2026-11-05.** Pure history-sync engine in `:spotify-api`
(newest-page backward pagination, inclusive overlap on the high-water, per-page TX-01 persistence,
high-water advanced only after bridging or a first baseline, gap → `HISTORY_INCOMPLETE`) and a Room
adapter in `:app`. 194 JVM tests passed; no live API call, worker or UI wiring yet.
See [M3-HISTORY-SYNC.md](M3-HISTORY-SYNC.md).

**TASK-031: PASS — JVM tests and local build on 2026-11-05.** Pure qualification evaluator in
`:spotify-api` (daily >= 3 per Vietnam day OR rolling >= 5 in `(t-72h, t]`, dedup, account filter,
configurable thresholds). 210 JVM tests passed; not persisted or wired yet (TASK-032).
See [M3-QUALIFICATION.md](M3-QUALIFICATION.md).

**TASK-032: PASS — JVM tests and local build on 2026-11-05.** `QualificationDao.qualifyBatch` (one Room
transaction: qualifications + PENDING outbox rows, backfill after playlist link, never delete/reset) and
`QualificationRecorder` in `:app` (evaluate, threshold change re-evaluation). No schema change. 223 JVM
tests passed; not wired to workers/UI; no Spotify writes. See [M3-QUALIFICATION-OUTBOX.md](M3-QUALIFICATION-OUTBOX.md).

**TASK-033: PASS — JVM tests and local build on 2026-11-05.** Integration tests for repeated-sync stability,
honest coverage status, gap/recovery, 429/quota, account isolation and threshold changes; TC traceability in
[M3-TEST-TRACEABILITY.md](M3-TEST-TRACEABILITY.md). 238 JVM tests passed. Not run against live Spotify,
a device or CI. HTTP and Room are covered in separate suites (see limitations there).

**TASK-040: PASS — JVM tests and local build on 2026-11-05.** Create/link was already done by TASK-050B;
added a read-only pre-write re-verification gate for the stored playlist mapping. 246 JVM tests passed; not
run against live Spotify, a device or CI. See [M4-PLAYLIST-SETUP.md](M4-PLAYLIST-SETUP.md).

**TASK-041: PASS — JVM tests and local build on 2026-11-05.** Paginated remote membership read and
reconcile-before-write (no Spotify writes, no schema change). 262 JVM tests passed; not run against live
Spotify, a device or CI. See [M4-PLAYLIST-RECONCILE.md](M4-PLAYLIST-RECONCILE.md).

**TASK-042: PASS — JVM tests and local build on 2026-11-05.** Outbox state machine, ambiguous POST
verification and external removal handling; no schema change. 284 JVM tests passed; not run against live
Spotify, a device or CI. See [M4-OUTBOX-WRITER.md](M4-OUTBOX-WRITER.md).

**TASK-043: PASS — JVM tests and local build on 2026-11-05.** M4 recovery-path integration tests and TC
traceability ([M4-TEST-TRACEABILITY.md](M4-TEST-TRACEABILITY.md)); no production code change. 306 JVM tests passed;
not run against live Spotify, a device or CI (CI not yet observed). M4 exit criterion is met at JVM level only;
device/live gates remain.

**TASK-051: PASS — JVM tests and local build on 2026-11-05.** WorkManager periodic and manual sync with
per-account serialization ([M5-SYNC-WORKER.md](M5-SYNC-WORKER.md)). 330 JVM tests passed; not run against live
Spotify, a device or CI. OxygenOS background reliability is unverified (TASK-053). No Sync Now button yet (TASK-050G).

**TASK-052: PASS — JVM tests and local build on 2026-11-05.** Failure handling and playlist relink
([M5-ERROR-HANDLING.md](M5-ERROR-HANDLING.md)). Not run against live Spotify, a device or CI. No relink UI yet.

**TASK-050C: PASS — JVM tests and local build on 2026-11-05.** Home dashboard on real Room data ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

**TASK-050D: PASS — JVM tests and local build on 2026-11-05.** History search/filter and Track Detail ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

**TASK-050E: PASS — JVM tests and local build on 2026-11-05.** Collection confirmed/pending/needs-review ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

**TASK-050F: PASS — JVM tests and local build on 2026-11-05.** Rules Studio draft/edit/save/discard with real-data preview ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

**TASK-050G: PASS — JVM tests and local build on 2026-11-05.** Settings/Sync Health with sanitized diagnostics, real Sync Now and relink entry ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

**TASK-050H: PASS — JVM tests and local build on 2026-11-05.** UI acceptance sweep: leak check, accessibility, large font, error/offline states ([M5-UI.md](M5-UI.md); UI-TC table in [M5-UI-ACCEPTANCE.md](M5-UI-ACCEPTANCE.md), device runs still required).
Not run against live Spotify, a device or CI.

**TASK-050I: PASS — JVM tests and local build on 2026-11-05.** Optional polish: bounded gradient and reduced-motion-aware progress ([M5-UI.md](M5-UI.md)).
Not run against live Spotify, a device or CI.

Next planned task: TASK-none in TASK-050 (M5 UI) / TASK-053, per docs/08.

**Early M5 UI checkpoint — TASK-050A/050B (2026-11-05):** user requested UI foundation and onboarding
before M3/M4 for device inspection. Native Replay components/four-tab preview, production M2 auth
and confirmed create/link setup are implemented; verification status and limitations are recorded in
[M5-UI-FOUNDATION-ONBOARDING.md](M5-UI-FOUNDATION-ONBOARDING.md). Full feature screens, history,
qualification, membership/outbox and background sync remain open. This does not mark M5 Released.

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

## 2026-11-05 rule change log
- **Owner decision:** daily threshold default changed from **>= 4** to **>= 3** play events per Vietnam
  calendar day (inclusive). Rolling **>= 5 in 72 h** unchanged; rules still combined with OR; Vietnam-day
  boundary semantics unchanged.
- Updated PRD, business rules (BR-001, EX-001..003, EX-007, EX-008), database, UI/UX, test plan
  (TC-001..003, TC-009), visual docs, `AccountSettings` default, onboarding/home UI strings and tests.
- Room schema reviewed: no change (thresholds are in DataStore). Stored DataStore thresholds are not
  rewritten; the new default applies only when none is stored.

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
