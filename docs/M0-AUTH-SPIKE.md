# TASK-003 — OAuth PKCE / OnePlus feasibility spike

Status: code implemented; local tests/lint/debug build **PASS**; device/live verification **pending**.
Scope: TASK-003, with minimal Android build/CI prerequisites required to run it.
This does not complete M1 or implement TASK-020 production authentication.

## Contract

- Fixed redirect `http://127.0.0.1:8765/callback`; bind IPv4 loopback before opening browser.
- PKCE S256, fresh secure-random verifier/state, one code per session, monotonic three-minute deadline.
- Exact path, method, Host/state validation; reject duplicate query parameters, absolute targets,
  fragments, oversized request lines/headers and code/error conflicts. Invalid callbacks never exchange tokens.
- Cancel/timeout/ViewModel destruction closes sockets; browser denial ends the attempt.
- Exchange code once at Spotify's HTTPS token endpoint, without client secret, redirect following or blind retry.
- Validate HTTP 200, token shape/type, positive expiry and all three required scopes.
- Sensitive values stay in memory; tokens discarded immediately. No raw exception/body/URL logging.
- No profile/history calls or playlist actions. `GET /me` belongs to TASK-002A next; playlist smoke tests
  belong to TASK-004. OAuth success alone leaves all integration gates unverified.

Spike-only deviations: Client ID entered in diagnostic UI rather than build configuration;
token persistence/refresh deferred to TASK-020. These choices do not change production requirements.
The screen uses native Compose and documented dark/green tokens. It is a diagnostic form,
not TASK-050B's finished Welcome: no artwork collage, full onboarding or bottom tabs.
Inspected supplied `design/reference/00-welcome.webp`; exact original PNG is absent.
Reference files remain unchanged. No original-PNG visual acceptance is claimed.
The spike blocks captures/task snapshots (`FLAG_SECURE`); use sanitized text for its device report.

## Automated coverage

| Group | Scenarios |
|---|---|
| SP-AUTH-001 | RFC 7636 S256 vector; fresh verifier/state; exact redirect and minimal scopes |
| SP-AUTH-002 / TC-019 | Valid/invalid/missing state; duplicate/malformed parameters; expired/replayed callbacks |
| SP-AUTH-003 | Loopback-only bind; invalid Host/method/path; port collision; bounded input; cleanup |
| SP-AUTH-004 | Dummy token endpoint; exact form/no secret; safe metadata; malformed/scope/HTTP/redirect failure |
| SP-AUTH-005 | ViewModel double tap, cancellation before browser/during exchange, timeout, denial, cleanup |

No live credentials in automated tests. Core tests run on JVM with localhost sockets/MockWebServer.
`TC-018` remains pending on-device; automated `TC-019` coverage does not imply complete production auth.

## Manual OnePlus checklist — not yet executed

Record build SHA or APK checksum, Android/OxygenOS version, test time, sanitized status,
actual vs expected, PASS/FAIL. Never record token, code, callback query, secret or personal history.

| Case | Expected | Observed |
|---|---|---|
| Correct redirect, allowlisted Premium owner, successful consent | HTTP 200 metadata only; no automatic account/API gate pass | Pending |
| Deny consent | Clear denial, listener closed, new login possible | Pending |
| Close browser and cancel from app | Cancellation, late callback rejected | Pending |
| Wait three minutes | Timeout, port released, fresh retry | Pending |
| Rotate app / background in browser | Same attempt where OS keeps process alive | Pending |
| Kill process during browser auth | Old attempt cannot authenticate; reopen and start fresh | Pending |
| Another process occupies port 8765 | Port-in-use error; browser not launched | Pending |
| Wrong/missing Dashboard redirect | No success; configuration error or timeout | Pending |
| No browser / offline / HTTP error | Safe failure, no secret/body displayed | Pending |
| Double tap / cancel during exchange | One attempt; cancellation cannot later show success | Pending |

Port-collision setup: trusted local test app or device Python command
`python -m http.server 8765 --bind 127.0.0.1` while tapping Connect. Stop it before retrying.
No Accessibility, notification listener, battery bypass, foreground service or custom URI scheme.
If loopback fails on OxygenOS, stop broad implementation and evaluate a verified HTTPS Android App Link.

## Build and gate evidence

- GitHub API confirmed repository private; default branch `main`.
- Gradle ZIP SHA-256: `20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.
- Wrapper JAR SHA-256: `81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
- Local wrapper build observed `BUILD SUCCESSFUL` (JDK 17 / macOS x64, temporary SDK):
  `./gradlew --no-daemon :app:clean :auth-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --write-locks`.
- 32 JVM tests: 23 auth-core + 9 ViewModel; zero failures/errors/skips. Reports are under
  `auth-core/build/reports/tests/test/` and `app/build/reports/tests/testDebugUnitTest/`.
- Android lint passed. Version-update notices are informational because the toolchain is intentionally pinned;
  correctness/security/resource checks still treat warnings as errors. Debug packaging retains
  `libandroidx.graphics.path.so` unstripped; the build succeeds (release packaging is outside this spike).
- Gradle dependency locks generated for settings and both modules.
- Standard command repeated with `--offline` and existing locks: `BUILD SUCCESSFUL`,
  same 32 passing tests and unchanged APK checksum.
- APK: `app/build/outputs/apk/debug/app-debug.apk`; Android debug signature verified with `apksigner`.
- APK SHA-256: `ad063f30bdd8300bac1e8b0ccfc9865b0eb5e1ed66ec99e6197c7896bcdb3eed`.
- GitHub Actions: workflow prepared; no remote run claimed before it actually executes.
- `GATE-AUTH-01`, `GATE-ACCOUNT-01`, `GATE-API-01`: **pending**.

## Official references checked during implementation

- [Spotify PKCE](https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow)
- [Spotify redirects](https://developer.spotify.com/documentation/web-api/concepts/redirect_uri)
- [AGP compatibility](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
- [Compose BOM](https://developer.android.com/develop/ui/compose/bom/bom-mapping)
- [Kotlin/Gradle](https://kotlinlang.org/docs/gradle-configure-project.html)
