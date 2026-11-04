# TASK-050A / TASK-050B — early device checkpoint

Date: 2026-11-05 (Vietnam). Status: **implemented; local tests/build PASS; CI pending; device acceptance open**.

User requested 050A then 050B before M3/M4. These are sub-tasks of TASK-050, not all of M5.

## Scope and dependency decision

050A supplies Replay tokens, native Compose components, safe-area layout, four-tab Navigation Compose and
explicitly labeled empty preview. 050B replaces the launcher probe with production M2 PKCE/session
restore and Welcome → account verification → playlist setup → foundation shell. Preview remains
available without Spotify configuration; it never represents a connected account.

Playlist setup needs a narrow portion of FR-002 / TASK-040 before M4: create/link one owned private,
non-collaborative playlist and persist its mapping. This task includes that prerequisite **only**;
TASK-040's remaining integration, M3 ingestion/counting, M4 membership/outbox/additions and workers
remain open. There is no manual-sync control pretending to work, no Added state and no fabricated counts.

Creation uses a durable marker and attempt journal before POST. Once attempted, subsequent actions
recover by known ID or paginated own-playlist discovery; they do not repeat POST. Explicit link is the
fallback for missing description/API discovery access and existing playlists after reinstall. Owner,
privacy and snapshot are verified by GET before saving. Confirmation is required before create/link.
Journal and Room data stay account scoped and outside backups; sign-out retains data and binding.

## Acceptance additions for this early checkpoint

- 050A: four selected/labeled tabs; Settings secondary; Back returns to Home then exits preview;
  route scroll positions retained; loading/empty/error components; 48dp controls; native dark tokens.
- 050B / UI-TC-001..002: real auth, duplicate-connect suppression, browser failure/cancel/timeout,
  wrong account/insufficient scopes, expired session and session restoration; no tabs during setup.
- Setup prerequisite / TC-012-adjacent: confirmation; owned/private/non-collaborative checks;
  duplicate create/relaunch/ambiguous response recovery; persist before entering shell; no track writes.
- UI-TC-003/015/016: no example metrics/artwork, 320dp/font 1.3/landscape previews, no decorative motion.
- Unit tests with fake auth/API/persistence; local compile/lint/APK; GitHub Actions when available.
- Physical OnePlus inspection and sanitized visual comparison remain required before screen acceptance.

## Visual references and intended deviations

Inspected all eight tracked WebP references, with `00-welcome`, `01-playlist-setup` and `02-home` as the
primary targets (the repository has WebP, not PNG). Native dark surfaces, green accents, typography, rounded cards and static vector
curves follow these sources. Neutral music graphics replace synthetic album art; copy is Vietnamese;
no Spotify logo is invented; setup has no bottom tabs. Full Home/History/Collection/Rules/Settings
layouts remain TASK-050C..G. Build configuration details are kept out of the main user journey.
Welcome uses a fixed action area so Connect/Preview/Cancel stay visible with long copy and large type;
this deliberately differs from the reference's fully in-flow actions. Setup is scrollable so fields and
confirmation remain readable. Font sizes follow the native token scale rather than scaling the image.

Navigation Compose 2.9.8 is pinned in the catalog, retaining the project's Kotlin/Compose/Lifecycle
pins. It provides a real back stack and per-tab save/restore; transitions are disabled at this foundation
checkpoint. Source review: [Navigation release notes](https://developer.android.com/jetpack/androidx/releases/navigation#2.9.8),
[Compose state](https://developer.android.com/develop/ui/compose/state),
[Compose semantics](https://developer.android.com/develop/ui/compose/accessibility/semantics).
API shapes were rechecked against Spotify's [create](https://developer.spotify.com/documentation/web-api/reference/create-playlist),
[get playlist](https://developer.spotify.com/documentation/web-api/reference/get-playlist) and
[own playlists](https://developer.spotify.com/documentation/web-api/reference/get-a-list-of-current-users-playlists) docs.

Measured flat foreground/background contrast: primary text 16.32:1, secondary on elevated 6.72:1,
muted on surface 5.32:1, green on elevated 7.19:1, button label 9.83:1, warning 9.91:1, error 6.73:1.
These calculations do not certify TalkBack or physical-display testing.

## Verification

050A first compile: `:app:compileDebugKotlin` → **BUILD SUCCESSFUL in 38s**.
Integrated local verification before the final Navigation Compose wiring:

```sh
./gradlew --offline --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest \
  :data:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  --write-locks -Preplay.screenshots=/tmp/replay-050-ui
```

Observed **BUILD SUCCESSFUL in 1m 52s**, 179 tests (92 auth, 23 API, 11 data, 53 app),
0 failures/errors/skips. Final Navigation Compose wiring: `:app:testDebugUnitTest :app:lintDebug
:app:assembleDebug :app:assembleRelease --write-locks` with synthetic-render property →
**BUILD SUCCESSFUL in 3m 57s**; 53 app tests passed again, with no compiler warnings.
Both lint tasks passed with 0 errors/warnings. Committed dependency locks add Navigation 2.9.8
and test-only Compose/Robolectric dependencies; no pre-existing release-runtime version changed.
`data/gradle.lockfile` only refreshes empty configuration metadata, not coordinates/versions.

Debug APK: `io.github.ntiendat01.replay.debug`, `0.5.0-debug` / 500, min SDK 29.
`apksigner verify --verbose` observed a valid v2 signature (one signer). Local SHA-256:
`a8429b96c207c460f56d34e553341bed369ddba5bc43002cec901f3f42051314`.
This local APK differs from the CI-signed artifact; do not use this hash for CI downloads.
CI evidence will be appended after observation.
Robolectric native View renders were inspected for Welcome, Setup, empty Home, 320dp/font 1.3 and
landscape Setup. These are synthetic screen states, not OnePlus screenshots. CI uploads these renders
under the existing test-report artifact; no account or real listening history is rendered.

Verification found and fixed browser-launch failure propagation through a child deferred. The launcher
now acknowledges success/failure as a boolean; an unavailable browser yields a recoverable Welcome
error instead of an uncaught parent cancellation. UI queries distinguish tab semantics from same-name
headings. PixelCopy screenshot capture was replaced with native View drawing for Robolectric.

## Modified files

- `app/.../ui/ReplayTheme.kt`, `ReplayIcons.kt`, `ReplayComponents.kt`, `ReplayFoundation.kt`: tokens,
  original vectors, shared states/components, four routes and secondary Settings.
- `app/.../onboarding/OnboardingScreen.kt`, `OnboardingViewModel.kt`, `PlaylistSetup.kt`,
  `SetupPersistence.kt`: Welcome/setup, production auth adapter contract, confirmed setup/recovery,
  account-scoped Room mapping and durable no-backup creation journal.
- `app/.../spike/MainActivity.kt`, `AppContainer.kt`, `AndroidAuth.kt`, `ReplayApplication.kt`,
  `AndroidManifest.xml`: production launcher, one process graph, lifecycle browser handling, dark
  system bars, secure session surfaces, app label. The historical probe ViewModel/controls/tests remain.
- `app/.../test/.../onboarding/`: repository/journal, ViewModel, Compose and real-launcher tests;
  `app/src/test/resources/robolectric.properties`.
- `app/build.gradle.kts`, catalog/lockfiles, `gradle.properties`, CI workflow: pinned Navigation Compose,
  Compose/Robolectric tests, synthetic-render upload, version **0.5.0 / 500**.
- Root README, docs index, implementation/test plans and this report: early-order scope, install steps,
  acceptance/evidence and remaining gates. No Room schema, counting rule or OAuth scope change.

## Remaining acceptance / blockers

- ADB inventory observed **0 attached devices**; no installation, OnePlus screenshot, TalkBack or
  physical OxygenOS/font/reduced-motion check is claimed. Device checklist: install → Welcome/preview
  tabs/Back → configured OAuth/cancel/wrong account → private setup/link → restart/session restore.
- No ignored `spotify.local.properties` is present for this build. CI/local unconfigured APK supports
  preview and disables Connect honestly. For real OAuth, set the two public identity configuration
  fields described in README and rebuild privately; register the exact loopback URI.
- No live Spotify request was made for this checkpoint. Android Keystore/real token refresh, automatic
  private creation and account/app ownership gates retain their prior evidence limits.
- M3/M4, full TASK-050C..I, workers/error integration/device E2E/release acceptance remain open.
  Existing Room-corruption recovery limitations from M2 are not changed by this task.
- Ambiguous creation never auto-POSTs again. If Spotify cannot discover the marker/ID, the user must
  explicitly link a private owned playlist; if none exists, create it manually in Spotify and link it.
