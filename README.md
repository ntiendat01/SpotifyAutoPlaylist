# Spotify AutoPlaylist · replay.

Personal Android companion that will collect repeat-play tracks into a real private Spotify playlist.
Requirements: [docs/README.md](docs/README.md). Current scope: **TASK-003 OAuth feasibility spike**.

This test harness opens browser authorization, receives a loopback callback, validates PKCE/state,
and exchanges the one-time code. It validates response shape/scopes, then discards all tokens.
No tokens, codes or raw HTTP bodies are logged, displayed or persisted. No client secret is used.
History/profile calls, playlist writes, persistent login and workers are not implemented yet.
OAuth success does **not** prove Premium, account identity/ownership or playlist endpoint access.

## Try on OnePlus

1. In the **new Premium account's** Spotify Developer App, register exactly
   `http://127.0.0.1:8765/callback`. Ensure the same account is allowed to use the app as required.
2. Build the APK below, or download `replay-auth-spike-debug-apk` from a successful GitHub Actions run.
3. Install `app-debug.apk`, open **replay. · Auth thử nghiệm**, and enter the app's public **Client ID**.
   Never enter Client Secret. Client ID is runtime-only in this spike, not saved or built into the APK.
4. Tap **Kết nối Spotify**, complete login/consent in the browser, then manually return to replay.
5. Record only the safe outcome/HTTP status using [the M0 checklist](docs/M0-AUTH-SPIKE.md).
   Never copy callback URLs or capture the browser address bar: it can contain an authorization code.
6. Test deny, cancel, retry, rotation, timeout, port collision and process death on the real device.

Callback waits at most three minutes. Closing the browser cannot be detected automatically;
return and tap **Hủy đăng nhập**, or wait for timeout. Process death loses the attempt: start a new login.
This separate application ID is `com.spotifyautoplaylist.spike`. No account/token data is saved.
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
| SDK build tools | 35.0.0; AGP default |

Dependency locks are committed per module; version catalog: `gradle/libs.versions.toml`.
No dynamic versions. CI actions use commit SHAs. Compatibility references:
[AGP](https://developer.android.com/build/releases/agp-8-13-0-release-notes),
[Kotlin](https://kotlinlang.org/docs/gradle-configure-project.html),
[Compose](https://developer.android.com/develop/ui/compose/bom/bom-mapping).

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

## Code boundaries

- `auth-core/`: Android-independent PKCE, bounded loopback listener, cancellable token probe;
  JVM tests using dummy data, localhost sockets and MockWebServer.
- `app/`: native Compose diagnostic screen, lifecycle-aware browser launch and ViewModel.
- `.github/workflows/android-ci.yml`: JVM/Android unit tests, lint and debug APK.

Working name `replay.` is independent of Spotify. No open-source license is granted yet;
keep this repository private until the owner chooses one. No production signing key exists in this task.
