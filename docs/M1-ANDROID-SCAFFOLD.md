# TASK-010 — Android / Compose scaffold audit

Date: 2026-11-04 (Vietnam). Source baseline: `6337fbc`, with the TASK-010 working-tree changes.
Status: **PASS — local scaffold verification observed**.
Scope: reuse the buildable M0 scaffold, pin Android SDK settings explicitly and verify it locally.
The runtime remains the manual API probe. Production auth, persistence, navigation and finished
screens belong to later tasks; this audit does not mark all of M1 or the Spotify gates complete.

## Configuration audited

| Area | Result |
|---|---|
| Modules | `:app` (native Compose host) and `:auth-core` (Android-independent JVM logic) |
| Gradle | 8.13 wrapper; Unix executable and Windows launcher tracked |
| Android Gradle Plugin | 8.13.2 |
| Kotlin / Compose compiler plugin | Both 2.2.21, using the same catalog version |
| JDK | Toolchain 17 in both modules; app Java source/target 17 |
| Android SDK | Compile 36 / target 36 / minimum 29; now consumed from the version catalog |
| Build Tools | 35.0.0; now explicitly consumed from the catalog instead of relying on AGP's default |
| Compose | BOM 2025.12.00; resolved dependencies remain locked |
| Dependency sources | Settings-level Google / Maven Central; project repositories rejected |
| Locks | Settings, app and auth-core lockfiles tracked; no dependency upgrades or lock rewrites |
| Packaging | Existing debug probe ID/version retained; production signing is TASK-013/054 |
| Credentials | Client ID entered at runtime; no client secret or real Spotify token used for this audit |

Wrapper JAR SHA-256 matches the [official Gradle checksum](https://services.gradle.org/distributions/gradle-8.13-wrapper.jar.sha256):
`81a82aaea5abcc8ff68b3dfcb58b3c3c429378efd98e7433460610fecd7ae45f`.
Cached distribution ZIP SHA-256 matches `distributionSha256Sum` in wrapper properties:
`20f1b1176237254a6fc204d8434196fa11a4cfb387567519c61556e8710aed78`.

## Compatibility review

[AGP's compatibility table](https://developer.android.com/build/releases/agp-8-13-0-release-notes)
specifies Gradle 8.13, JDK 17 and Build Tools 35.0.0 for the 8.13 line; API 36 is within its supported
SDK range. The [Compose compiler setup](https://developer.android.com/develop/ui/compose/setup-compose-dependencies-and-compiler)
uses the Kotlin version for the Compose compiler plugin, as this project already does.

Qualification: [Kotlin's fully supported compatibility table](https://kotlinlang.org/docs/gradle-configure-project.html)
lists Gradle through 8.14 and AGP through 8.11.1 for Kotlin 2.2.20–2.2.21. It allows later AGP versions
with possible warnings/feature limits. The retained AGP 8.13.2 pairing therefore needs the project's
own verification; a successful build is not a claim that JetBrains fully supports this pairing.
No Kotlin/AGP/Compose upgrade or SDK retargeting is included in this task.

## Local verification

Uses the existing temporary toolchain at `/private/tmp/spotify-autoplaylist-toolchain`:
Temurin JDK 17.0.21+9, Android SDK 36, Build Tools 35.0.0 and cached Gradle dependencies.
Set `JAVA_HOME`, `ANDROID_HOME` and `GRADLE_USER_HOME` to that toolchain's JDK, SDK and Gradle cache,
or use any equivalent locally configured installation described in the root README.

```sh
./gradlew --offline --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug --rerun-tasks
```

The initial sandboxed attempt could not start Gradle's internal lock socket (`Operation not permitted`).
The same offline verification was restarted outside that restriction. No live Spotify credentials
or API calls are involved; HTTP fixtures use dummy data and localhost MockWebServer.

Observed result: **BUILD SUCCESSFUL in 2m 16s**, 57 actionable tasks executed (`--rerun-tasks`).

| Check | Observed result |
|---|---|
| `:auth-core:test` | 61 tests; 0 failures, errors or skips |
| `:app:testDebugUnitTest` | 23 tests; 0 failures, errors or skips |
| `:app:lintDebug` | Passed; XML contains 15 informational hints, no warnings/errors/fatal issues |
| `:app:assembleDebug` | Passed; app and core sources compiled again |
| APK metadata | `com.spotifyautoplaylist.spike`, versionCode 3 / `0.3.0-api-spike`, minimum SDK 29 |
| Debug signature | `apksigner verify --verbose`: verifies, APK Signature Scheme v2 |

APK: `app/build/outputs/apk/debug/app-debug.apk`.
SHA-256: `87ec40c739bf2b0171349893967057dcbf2adbfaef70e55719ab7a68df6830d5`.
JUnit XML reports: `auth-core/build/test-results/test/` and
`app/build/test-results/testDebugUnitTest/`; lint report: `app/build/reports/lint-results-debug.html`.
The existing unstripped `libandroidx.graphics.path.so` packaging notice remains; debug packaging succeeds.
Dependency locks are unchanged. No new unit test is added for catalog indirection; recompilation,
the existing tests and APK/lint inspection verify the actual build configuration.

## GitHub Actions evidence

Read-only GitHub inspection confirmed [run 41853383634](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41853383634)
completed successfully for TASK-004 commit `8eaaceb50d61e5b4426a5e447650a954b9afa422`, including
SDK setup, JVM/Android tests, lint, APK assembly and artifact uploads. This is baseline evidence;
it does not test the new catalog wiring. At TASK-010 completion, the changes had not run in CI.
Subsequent TASK-011 verification compiled/tested the matching build files on GitHub Actions;
see [M1-ANDROID-CI.md](M1-ANDROID-CI.md) for the successful run and downloaded-artifact evidence.

## Visual differences and remaining limits

No Compose screen, resources or interactions changed; no new visual acceptance is claimed.
No device install/navigation check was performed for TASK-010 (TASK-012).
The runtime remains `com.spotifyautoplaylist.spike` / `0.3.0-api-spike`; do not replace its journal or
assume this audit implements migration to a production application ID.
Spotify gate evidence limits and the manual privacy-change observation remain recorded in
[M0-API-SPIKE.md](M0-API-SPIKE.md).
