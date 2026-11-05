# Spotify AutoPlaylist · replay.

Personal Android companion that will collect repeat-play tracks into a real private Spotify playlist.
Requirements: [docs/README.md](docs/README.md). Current runtime checkpoint: **TASK-050A/050B**
(early UI foundation and production onboarding before M3/M4). See [the checkpoint report](docs/M5-UI-FOUNDATION-ONBOARDING.md).

The native Compose launcher includes Welcome, a clearly labeled account-free UI preview with four tabs,
production PKCE login/session restore, account verification and private-playlist setup. Playlist create/link
requires confirmation and GET verification of ownership/privacy. A durable creation journal prevents blind
POST retries after process death or uncertain results. Local auth uses Android Keystore encryption (M2).
No client secret, token, authorization code or raw HTTP body is displayed or logged.

Background sync (TASK-051): a 30-minute WorkManager job imports history, records qualifications and adds tracks
to the managed playlist, serialized per account. It is tested on the JVM only; it has not run on live Spotify,
a device or CI, and background reliability on OxygenOS is unverified. See [docs/M5-SYNC-WORKER.md](docs/M5-SYNC-WORKER.md).

Failure handling (TASK-052): offline, 401/403/429, account mismatch and a confirmed playlist relink are handled
and tested on the JVM only (no UI, not run live/device/CI). See [docs/M5-ERROR-HANDLING.md](docs/M5-ERROR-HANDLING.md).

M5 UI screens (TASK-050C–050I complete locally): progress and limitations in [docs/M5-UI.md](docs/M5-UI.md). JVM/Robolectric only. Device E2E (TASK-053) runbook: [docs/M5-DEVICE-E2E.md](docs/M5-DEVICE-E2E.md), prepared but not executed. UI-TC-001..018 JVM-vs-device status: [docs/M5-UI-ACCEPTANCE.md](docs/M5-UI-ACCEPTANCE.md).

Qualification rule (owner, 2026-11-05): **>= 3 plays in one Vietnam day OR >= 5 plays in any rolling 72 h**.
The pure history-sync engine (TASK-030) and qualification evaluator (TASK-031) exist as tested JVM logic only.

History ingestion, qualification, remote membership reconciliation, track additions and background sync
are still M3/M4/TASK-051. The tab shell honestly shows unavailable/empty states, not sample tracks/counts.
Matching `/me.id` does **not** prove Premium or Developer App ownership. No live Spotify integration or
physical-device acceptance is implied by a successful build/test.

## Try on OnePlus

1. Build the APK below, or download `replay-auth-spike-debug-apk` from a successful CI run for the UI branch.
2. Install `app-debug.apk` and open **replay.**. Choose **Xem thử giao diện** to inspect the four-tab shell
   without signing in. A persistent preview banner distinguishes it from a connected account.
3. For real login, create the ignored repository-root `spotify.local.properties` **before building**:

   ```properties
   spotify.clientId=<public Client ID of the new Premium account's Developer App>
   spotify.ownerUserId=<Spotify user ID of that same Developer App owner>
   ```

   Never enter a client secret. Owner ID is the `/user/<id>` profile value, not email/display name.
   Configured APKs contain that personal owner identifier; keep them private. CI builds without this file
   intentionally disable login but retain the UI preview. Do not commit this file.
4. Register exactly `http://127.0.0.1:8765/callback` in the same Spotify Developer App. Tap **Kết nối Spotify**,
   complete browser login/consent and return to replay. Account mismatch/insufficient scope blocks setup.
5. After verified login, confirm creating a private playlist or paste a link to one you own. If a request
   has an uncertain outcome, use **Xác minh lại playlist**; app will not blindly create another. If Spotify
   reports public/unknown privacy, make the playlist private in Spotify and verify/link it again.
6. Setup completion enters the tab shell. **Mở trong Spotify** opens the actual playlist. This checkpoint
   does not add tracks or sync history. Settings can disconnect while keeping local data for the same account.
7. Restart to check persisted sign-in and playlist mapping. Closing the browser cannot be detected
   automatically: return and choose **Hủy đăng nhập**, or wait for the three-minute timeout. Process death
   during OAuth loses the verifier and requires a fresh login.

Version **0.5.0 / 500**. Application ID: `io.github.ntiendat01.replay` (debug: `.debug`).
Account/session/setup surfaces block screen capture; public Welcome and the account-free preview permit
visual QA. Never capture browser callback URLs. An incompatible debug signature can prevent in-place
installation; do not uninstall without understanding that local credentials/history/recovery state are lost.
Real Spotify playlists survive uninstall; explicitly link the existing playlist after reinstall.

The earlier M0 probe implementation/tests remain as historical integration evidence; its old launcher
was replaced. See [M0-API-SPIKE.md](docs/M0-API-SPIKE.md) for its signoff and limitations.

## Pinned toolchain

| Component | Version |
|---|---|
| JDK | 17 |
| Gradle | 8.13; wrapper distribution SHA-256 verified |
| Android Gradle Plugin | 8.13.2 |
| Kotlin + Compose compiler plugin | 2.2.21 |
| Compose BOM | 2025.12.00 |
| Activity Compose | 1.12.2 |
| Lifecycle | 2.9.4 |
| Navigation Compose | 2.9.8 |
| Coroutines | 1.10.2 |
| OkHttp | 4.12.0 |
| Kotlin serialization JSON | 1.9.0 |
| Retrofit | 3.0.0 |
| Room / KSP | 2.8.5 / 2.2.21-2.0.5 |
| DataStore Preferences | 1.2.1 |
| Robolectric (tests; runs SDK 34) | 4.16.1 |
| SDK compile / target / minimum | 36 / 36 / 29 |
| SDK build tools | 35.0.0; explicitly pinned |

Dependency locks are committed per module; version catalog: `gradle/libs.versions.toml`, including
compile/target/minimum SDK and Build Tools pins consumed by the app build script.
No dynamic versions. CI actions use commit SHAs. Compatibility references:
[AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose](https://developer.android.com/develop/ui/compose/bom/bom-mapping).

Compatibility qualification: Kotlin's published table lists AGP through 8.11.1 as fully supported for
Kotlin 2.2.21. This project retains AGP 8.13.2 and verifies the combination with its own compile/test/lint
checks; that evidence does not extend the vendor's fully supported range. See the scaffold report
for observed results and remaining device/CI acceptance.

## Build without Android Studio

Install JDK 17 and Android SDK command-line tools, accept SDK licenses, then install
`platforms;android-36` and `build-tools;35.0.0`. Set `JAVA_HOME` and `ANDROID_HOME`
(or SDK path in ignored `local.properties`).

```sh
./gradlew --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

The first `:data` test run downloads Robolectric's Android runtime jar.

APK: `app/build/outputs/apk/debug/app-debug.apk`.
Production onboarding (TASK-050B) reads build config from the ignored
`spotify.local.properties` at the repository root: `spotify.clientId=<32-hex Client ID>` and
`spotify.ownerUserId=<owner /user/<id>>`. Without it the build still succeeds with auth unconfigured.
Never put a client secret there. See [the auth report](docs/M2-AUTH.md).

Bump the version only via `replay.versionName` (`MAJOR.MINOR.PATCH`) in `gradle.properties`;
versionCode is derived as `MAJOR*10000 + MINOR*100 + PATCH`.

Release: `./gradlew --no-daemon :app:assembleRelease` signs with the owner key from
`~/.replay-signing/signing.properties` (or `REPLAY_SIGNING_*` environment variables) and writes
`app/build/outputs/apk/release/app-release.apk`; without a key it writes an unsigned, non-installable APK.
Never commit key material. Details and certificate fingerprint: [signing report](docs/M1-ANDROID-SIGNING.md).
JVM HTML reports: `auth-core/build/reports/tests/test/index.html`,
`spotify-api/build/reports/tests/test/index.html`,
`data/build/reports/tests/testDebugUnitTest/index.html` and
`app/build/reports/tests/testDebugUnitTest/index.html`.
GitHub Actions runs the same checks and uploads APK/reports without Spotify credentials.
Device/live integration gates cannot be replaced by CI tests.

## Download a CI build

Open the successful **Android CI** run for the source commit you want to test. Under **Artifacts**,
download `replay-auth-spike-debug-apk` and unzip it. The artifact contains `app-debug.apk`,
`app-debug.apk.sha256`, `output-metadata.json` and `source-commit.txt`. On macOS, check the downloaded
APK from that folder with `shasum -a 256 -c app-debug.apk.sha256`; compare `source-commit.txt` with
the run's source SHA. GitHub login and repository read access are required. Artifacts are retained
for 14 days by this workflow.

`auth-spike-test-reports` contains HTML reports plus JUnit/lint XML for inspecting actual test totals.
The APK's signature is verified before upload. These are disposable debug builds; a new CI runner
may use a different debug signing key, so in-place device updates are not guaranteed. Stable updates
use the locally signed release build (TASK-013); CI release signing is TASK-054.

TASK-011 workflow/artifact evidence: [M1-ANDROID-CI.md](docs/M1-ANDROID-CI.md).

## Code boundaries

- `auth-core/`: Android-independent PKCE, bounded loopback listener, token/profile/API probes and atomic recovery journal;
  JVM tests using dummy data, localhost sockets and MockWebServer.
- `spotify-api/`: Retrofit Spotify Web API client, DTOs, HTTP status mapping and sanitized request events;
  MockWebServer tests.
- `data/`: Room v1 schema (exported to `data/schemas/`, must be committed), DAOs, account-scoped DataStore
  settings and local-data reset; Robolectric tests with in-memory Room.
- `app/`: native Replay design system, four-tab preview, production onboarding ViewModel, lifecycle-aware browser launch and private-playlist setup. Compose UI tests use Robolectric with synthetic states only.
- `.github/workflows/android-ci.yml`: JVM/Android unit tests, lint and debug APK.

Working name `replay.` is independent of Spotify. No open-source license is granted yet;
keep this repository private until the owner chooses one. The release signing key is held by the owner
outside the repository.
