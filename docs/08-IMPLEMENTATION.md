# 08 — Implementation Plan, CI & Release Checklist

Version 1.3.0 · Status: Baseline

## Milestones and task dependencies

### M0 — Integration feasibility spike (mandatory before broad implementation)

- TASK-001 Create private GitHub repo; set README, `.gitignore`, license decision.
- TASK-002 Register a NEW Spotify Developer App from the NEW Premium account (not the old client ID); record new client ID, allowed account and scopes. Confirm account is both app owner and intended listener (AUTH-006).
- TASK-002A OAuth then call `GET /me`; verify user ID matches the expected new developer-owner account (GATE-ACCOUNT-01). Record sanitized proof.
  - Code and tests: [M0-ACCOUNT-SPIKE.md](M0-ACCOUNT-SPIKE.md). Live identity match and ownership evidence pending.
- TASK-003 Prototype OAuth PKCE + loopback callback on physical OnePlus; test login, cancellation and port collision.
  - Spike source, tests and minimal Android/CI harness: [M0-AUTH-SPIKE.md](M0-AUTH-SPIKE.md). Device gate pending; M1 and production auth remain separate.
- TASK-004 Smoke-test Recently Played and private playlist create/read/add endpoints; sanitize results.
  - **PASS — user-reported, 2026-11-04.** Native probe, recovery journal, tests and signoff limits:
    [M0-API-SPIKE.md](M0-API-SPIKE.md). User reports success after manually making the playlist private;
    individual endpoint evidence and automatic private creation are not separately verified.
- TASK-005 Record outcome in `docs/README.md` status and any API deviations.
  - Recorded user signoff, privacy observation, historical build evidence and remaining gate evidence gaps.
    This documentation-only task does not mark all of M0 Verified or complete the earlier device checklists.
    **Exit:** GATE-ACCOUNT-01, GATE-API-01 and GATE-AUTH-01 pass. If either fails, stop and revise architecture; do not fake success.

### M1 — Buildable Android shell

- TASK-010 Generate Kotlin/Compose Gradle project (Gradle wrapper, Android Gradle Plugin, Java toolchain, SDK versions pinned and verified).
  - **PASS — 2026-11-04, local verification.** Existing scaffold reused; SDK/Build Tools settings
    explicitly pinned through the catalog; wrapper checksum verified; 84 JVM tests, lint and debug APK
    passed. See [M1-ANDROID-SCAFFOLD.md](M1-ANDROID-SCAFFOLD.md) for evidence, vendor compatibility
    qualification and CI/device limits. CI verification is recorded under TASK-011; broad implementation
    still requires M0 exit.
- TASK-011 Build GitHub Actions `assembleDebug` workflow and upload APK artifact.
  - **PASS — 2026-11-04, observed CI and artifact verification.**
    [Run 41860277255](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41860277255)
    passed for commit `f7a1f01` on `codex/task-011-ci`, including TASK-010 catalog wiring. Downloaded
    APK checksum, source SHA, signature and metadata verified; 84 tests passed and lint passed.
    See [M1-ANDROID-CI.md](M1-ANDROID-CI.md) for artifact links and maintenance annotations. Next: TASK-012.
- TASK-012 Install APK on OnePlus, verify launch, navigation and network permissions.
  - **PASS — 2026-11-04, device installation and launch verified.**
    CI APK (`bd348c30…`, commit `f7a1f01`) installed on CPH2691 (OnePlus Ace 5),
    Android 16 / OxygenOS 16.0.2.400 via ADB. Cold launch 506 ms; UI automator dump
    confirmed all Compose elements rendered; FLAG\_SECURE blocks screenshots (correct).
    Prior local-signed APK required uninstall due to signature mismatch (expected for
    debug builds). See [M1-ANDROID-DEVICE.md](M1-ANDROID-DEVICE.md). Manual scroll,
    input and network acceptance deferred to user.
- TASK-013 Configure application ID, signing strategy, versionCode/versionName.
  - **PASS — 2026-11-04, local verification.** Release ID `io.github.ntiendat01.replay`, debug
    `.debug` suffix; version `0.4.0` / code 400 from `gradle.properties` (`MAJOR*10000+MINOR*100+PATCH`).
    Owner-held RSA 4096 release key outside the repo; release APK signed and verified, unsigned when
    no key (CI). 84 tests and lint passed; release locks added without version changes. CI rerun and
    device install of the new ID pending. See [M1-ANDROID-SIGNING.md](M1-ANDROID-SIGNING.md).
    **Exit:** clean CI build, app launches on device without Android Studio.

### M2 — Auth and persistence

- TASK-020 Implement production PKCE, callback listener, encrypted token storage, refresh serialization, account identity.
  - **PASS — 2026-11-04, JVM tests and local build.** `SpotifyAuthManager`, `AuthorizationFlow`,
    token/identity clients, AES-GCM encrypted atomic credential file, Keystore wiring and
    `spotify.local.properties` → `BuildConfig`. 108 JVM tests (24 new) and lint passed. Keystore path
    and live login/refresh not yet exercised on device; probe UI unchanged. See [M2-AUTH.md](M2-AUTH.md).
- TASK-020A Enforce AUTH-006 matching account ID; on explicit account change reset active auth/session state and prevent reuse of previous account playlist/event mappings.
  - **PASS — 2026-11-04, JVM tests and local build.** Hashed `account_id` data-owner binding kept across
    sign-out; other accounts rejected at login/restore; `requireAccount()` key gate for repositories;
    confirmed, durable, resumable `resetAccount` with ordered reset steps. 115 JVM tests passed.
    Reset steps for Room/WorkManager are plugged in by TASK-021/051. See [M2-AUTH.md](M2-AUTH.md).
- TASK-021 Implement Room schema, indexes, DAO, migrations and DataStore settings.
  - **PASS — 2026-11-04, JVM (Robolectric) tests and local build.** `:data` module: Room v1 schema
    (7 tables, exported, cascading FKs), TX-01/TX-03 DAOs, account-scoped DataStore settings and
    `LocalDataReset` registered as an account reset step. 126 JVM tests passed. No migrations yet (v1);
    Room's default corruption deletion still conflicts with 05 §4. See [M2-DATA.md](M2-DATA.md).
- TASK-022 Implement Spotify Retrofit client with DTO tests, status handling, sanitized logs.
  - **PASS — 2026-11-05, JVM tests and local build.** `:spotify-api` module: Retrofit 3 service for
    API-01..05/07, DTOs, §8 status mapping (single 401 refresh-retry, `Retry-After` fail-fast,
    `QUOTA_EXCEEDED`, `outcomeUnknown` for ambiguous POSTs, no transparent retries/redirects) and
    sanitized per-attempt log events. 149 JVM tests passed. Not exercised against live Spotify.
    See [M2-SPOTIFY-API.md](M2-SPOTIFY-API.md).
    **Exit:** login persists safely, history schema tests pass.

### M3 — History sync and rules

- TASK-030 Implement newest-page backward pagination, watermark, overlap, gap detection and safe checkpoints.
  - **PASS — 2026-11-05, JVM tests and local build.** `HistorySync` + `RoomHistoryStore`; TC-010, TC-011,
    TC-026, TC-027 covered at sync level (15 new tests, 194 total). Not run against live Spotify; not wired
    to workers/UI. See [M3-HISTORY-SYNC.md](M3-HISTORY-SYNC.md).
- TASK-031 Implement pure daily and rolling qualification evaluator.
  - **PASS — 2026-11-05, JVM tests and local build.** `QualificationEvaluator` (daily >= 3 VN day OR
    rolling >= 5/72h); TC-001..009 covered at evaluator level (16 new tests, 210 total). Not persisted or
    wired to workers/UI (TASK-032). See [M3-QUALIFICATION.md](M3-QUALIFICATION.md).
- TASK-032 Implement transactional qualification/outbox and threshold re-evaluation.
  - **PASS — 2026-11-05, JVM tests and local build.** `qualifyBatch` (TX-03/TX-05) + `QualificationRecorder`;
    TC-004/005/008/009/010 at persistence level (13 new tests, 223 total). No schema change; not wired to
    workers/UI; no Spotify writes. See [M3-QUALIFICATION-OUTBOX.md](M3-QUALIFICATION-OUTBOX.md).
- TASK-033 Unit and integration tests TC-001..011, TC-026, TC-027.
  - **PASS — 2026-11-05, JVM tests and local build.** `M3PipelineTest` (MockWebServer → SpotifyClient →
    HistorySync → evaluator) and `M3RoomIntegrationTest` (HistorySync → Room → QualificationRecorder);
    15 new tests, 238 total; no production code change, no bugs found. No live Spotify/device/CI run.
    See [M3-TEST-TRACEABILITY.md](M3-TEST-TRACEABILITY.md).
  **Exit:** repeated sync produces stable counts and honest coverage status.

### M4 — Playlist writer

- TASK-040 Create/link private playlist with ownership check.
- TASK-041 Paginate remote playlist items and reconcile before write.
- TASK-042 Implement outbox state machine, ambiguous POST verification and external removal handling.
- TASK-043 Tests TC-012..017, TC-028.
  **Exit:** qualifying track appears exactly once in tested normal and recovery paths.

### M5 — UI, worker and release

**Early checkpoint approved (2026-11-05):** implement TASK-050A then TASK-050B before M3/M4 so the
user can install and inspect the app. This is not all of TASK-050 or M5. It includes the narrow
FR-002 playlist-setup prerequisite from TASK-040; it does not implement track membership/writes,
history ingestion, counting or workers. Scope, tests, visual deviations and evidence:
[M5-UI-FOUNDATION-ONBOARDING.md](M5-UI-FOUNDATION-ONBOARDING.md).

- TASK-050 Implement Compose onboarding, Dashboard, History, Rules, Settings with UI states.
- TASK-051 WorkManager 30-min periodic best-effort sync and manual sync serialization.
- TASK-052 Handle network/offline, 401/403/429, account mismatch and playlist deletion.
- TASK-053 Device E2E TC-018..025, TC-029..030; verify background delays on OxygenOS.
- TASK-054 Produce signed release APK and manual install/update test.
  **Exit:** all release blockers cleared, docs status updated to Released.

## Build without Android Studio

- Mac editor: Antigravity. GitHub Actions hosted runner installs JDK, Android SDK command-line tooling and uses Gradle wrapper.
- Gradle wrapper files must be committed (`gradlew`, `gradle/wrapper/gradle-wrapper.jar`, `gradle-wrapper.properties`). Validate wrapper checksum and pin CI actions to trusted versions/SHAs where practical.
- Do **not** invent dependency versions in spec: choose a mutually compatible Kotlin, Compose BOM, AGP, Gradle, JDK and compile SDK set at scaffold time, record exact versions in root README and version catalog, and lock them.
- Minimal CI job: checkout → setup Java → setup Android SDK → `./gradlew --no-daemon testDebugUnitTest assembleDebug` → upload `app/build/outputs/apk/debug/app-debug.apk`.
- Android debug APK is installable for personal testing. For repeatable release upgrades, keep a stable signing key; store keystore and passwords as protected GitHub Secrets (not in repository), or sign locally. A debug key is not suitable for stable release distribution.
- Download artifact from GitHub Actions to phone, enable installation from that source, install APK. No Play Store publishing required.

## Example GitHub Actions workflow (template; adjust versions during scaffold)

```yaml
name: Android CI
on:
  push:
  workflow_dispatch:
jobs:
  build:
    runs-on: ubuntu-latest
    permissions:
      contents: read
    steps:
      - uses: actions/checkout@v4
      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17' # verify against selected AGP
      - uses: android-actions/setup-android@v3
      - name: Unit tests and APK
        run: ./gradlew --no-daemon testDebugUnitTest assembleDebug
      - uses: actions/upload-artifact@v4
        with:
          name: spotify-autoplaylist-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
```

Note: This template becomes executable only after the Android Gradle project is scaffolded and compatible versions/SDKs are pinned.

## Release checklist

- API + OAuth + account ownership feasibility gates passed on the new Premium account and actual device.
- All automated tests green, manual device test report attached.
- No credentials in code, APK config, logs, screenshots or CI output.
- App privacy notice describes local listening-history storage and Spotify authorization.
- Real playlist opens from Spotify Library on phone and desktop.
- Uninstall does not delete Spotify playlist; reinstall limitations disclosed.
- Version tagged, signed APK checksum recorded, rollback APK kept.
- Known limitations documented (best-effort sync, incomplete history, quota restrictions).

## Post-MVP candidates (not part of v1)

Android MediaSession-assisted observation, richer statistics, multiple playlists, rule presets, CSV export, optional HTTPS App Link auth, event source reconciliation, more granular battery optimization guidance.

## M5 UI breakdown (v1.2)

- TASK-050A Implement Replay design tokens, Compose components, insets and four-tab navigation; Settings is secondary route.
- TASK-050B Implement onboarding/auth states and playlist setup confirmation.
- TASK-050C Implement Home playlist hero, near-threshold list, newly collected and local 7-day chart with coverage warning.
- TASK-050D Implement History search/filter, Track Detail and timestamp disclaimer.
- TASK-050E Implement Collection confirmed/pending/needs-review tabs with remote verification timestamps.
- TASK-050F Implement Rules Studio draft/edit/save/discard and real-data preview.
- TASK-050G Implement Settings/Sync Health and sanitized diagnostics.
- TASK-050H Run `UI-TC-001..018` on OnePlus; verify accessibility, offline/error states and no fake production data.
- TASK-050I Optional polish: bounded artwork gradients and reduced-motion-aware animations.

## v1.3 — Visual implementation handoff (exact eight PNGs)

- Required folder: `design/reference/` contains **all eight unchanged concept images**, plus mapping in `docs/UI-VISUAL-REFERENCE.md`.
- Codex instructions at root `AGENTS.md`; task prompts at `docs/UI-CODEX-PROMPTS.md`; discrepancies at `docs/UI-VISUAL-DEVIATIONS.md`.
- TASK-050A visual foundation cross-check against `02-home.png`; reuse tokens across all 8 references.
- TASK-050B Welcome and Playlist Setup match `00-welcome.png` and `01-playlist-setup.png`; hide bottom navigation until setup complete.
- TASK-050C compare real Home screenshot to `02-home.png`.
- TASK-050D compare History screenshot to `03-listening-history.png`.
- TASK-050E compare Collection screenshot to `04-collection.png`; strict Added/Pending segregation.
- TASK-050F compare Rules screenshot to `05-rules-studio.png`; no unsupported auto-add toggle.
- TASK-050G compare Settings and optional manual-sync result state to `06-settings-sync-health.png` and `07-sync-complete.png`.
- TASK-050H check no example metrics, artist names or images leaked into production; accessibility, viewport scaling, errors.
- TASK-050I optional glow/gradients, no heavy continuous animations; screenshot-diff reviews only after functional acceptance.

**Important:** M0 Spotify API gate remains before broad investment; UI visuals do not override business logic. Sync result is a transient UI state, not a guarantee of an exact scheduled run.
