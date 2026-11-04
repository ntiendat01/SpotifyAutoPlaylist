# Spotify AutoPlaylist · replay.

Personal Android companion that will collect repeat-play tracks into a real private Spotify playlist.
Requirements: [docs/README.md](docs/README.md). Current runtime scope: **TASK-004 manual API smoke test**;
TASK-010 audits and reuses this Android/Compose scaffold. See [the scaffold report](docs/M1-ANDROID-SCAFFOLD.md).

This test harness opens browser authorization, receives a loopback callback, validates PKCE/state,
and exchanges the one-time code. It validates response shape/scopes, calls `GET /v1/me`, compares
the returned `id` to a separately entered expected developer-owner user ID, then keeps the verified token
in memory for manual API checks. TASK-004 also requires immutable `/me.account_id` to isolate recovery state.
No tokens, codes or raw HTTP bodies are logged, displayed or persisted. No client secret is used.
It can read one Recent page, create/link a real owned private playlist, and append one explicitly chosen
test track after confirmation. Every write is reconciled; Added requires a remote readback.
Persistent sign-in, full history sync, counting rules and workers are not implemented yet.
Matching `/me.id` does **not** prove Premium or Developer App ownership.

## Try on OnePlus

1. In the **new Premium account's** Spotify Developer App, register exactly
   `http://127.0.0.1:8765/callback`. Ensure the same account is allowed to use the app as required.
2. Build the APK below, or download `replay-auth-spike-debug-apk` from a successful GitHub Actions run.
3. Install `app-debug.apk`, open **replay. · Auth thử nghiệm**, and enter the app's public **Client ID**.
   Never enter Client Secret. Client ID is runtime-only in this spike, not saved or built into the APK.
4. Enter the **Spotify user ID of the Developer App owner**, independently obtained from that account's
   profile link (`/user/<id>`, excluding query parameters) or username on the account page. This is not
   display name, email, Client ID, full profile URL or `account_id`. The field is masked and memory-only.
5. Tap **Kết nối Spotify**, complete login/consent in the browser, then manually return to replay.
   Only OAuth 200 **and** `/me` 200 **and** exact expected-ID match produce success. Wrong account is rejected.
6. Read Recently Played, then confirm creating/finding a private playlist or explicitly link one you own.
   Paste a **track** link from Spotify and confirm adding one test track. Use **Đối soát lại Spotify**
   after uncertain results or a reconnect. Never assume a POST 201 alone means the track is present.
7. Record only safe outcome/status using [the API checklist](docs/M0-API-SPIKE.md).
   Never copy callback URLs or capture the browser address bar: it can contain an authorization code.
8. Test repeat/reconnect (no duplicate), cancel during writes, and external track removal (no re-add).
   Original auth/account checklists still apply.

Callback waits at most three minutes. Closing the browser cannot be detected automatically;
return and tap **Hủy đăng nhập**, or wait for timeout. Process death loses the attempt: start a new login.
This separate application ID is `com.spotifyautoplaylist.spike`. Tokens and listening events are not saved.
Version: `0.3.0-api-spike` (versionCode 3). App-private no-backup journal retains only marker,
playlist ID, one selected test-track ID and recovery state, keyed by hashed account ID + Client ID.
Do not delete that journal to retry a write. Corruption/storage failure stops the session.
A different debug signing key may require removing the previous probe before installing. Uninstall
loses local recovery state; real playlists remain. Link the existing smoke playlist after reinstall.
End/cancel/expiry/process death loses the memory-only token and requires a fresh login.
Screen capture/recent-task snapshots are blocked in this spike; product visual QA is a later task.

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
| Coroutines | 1.10.2 |
| OkHttp | 4.12.0 |
| Kotlin serialization JSON | 1.9.0 |
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
./gradlew --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`.
JVM HTML reports: `auth-core/build/reports/tests/test/index.html` and
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
may use a different debug signing key, so in-place device updates are not guaranteed. Stable signing
is TASK-013/054. Device installation and launch acceptance remain TASK-012.

TASK-011 workflow/artifact evidence: [M1-ANDROID-CI.md](docs/M1-ANDROID-CI.md).

## Code boundaries

- `auth-core/`: Android-independent PKCE, bounded loopback listener, token/profile/API probes and atomic recovery journal;
  JVM tests using dummy data, localhost sockets and MockWebServer.
- `app/`: native Compose diagnostic screen, lifecycle-aware browser launch and ViewModel.
- `.github/workflows/android-ci.yml`: JVM/Android unit tests, lint and debug APK.

Working name `replay.` is independent of Spotify. No open-source license is granted yet;
keep this repository private until the owner chooses one. No production signing key exists in this task.
