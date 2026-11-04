# TASK-013 — Application ID, signing strategy and versioning

Date: 2026-11-04 (Vietnam). Status: **PASS — local build and signature verification observed; CI rerun
and device install of the new ID pending**.
Scope: choose the permanent application identity, a stable release-signing strategy and a monotonic
version scheme. Runtime behavior, Kotlin packages, Compose UI and Spotify integration are unchanged.

## Decisions (owner-approved 2026-11-04)

| Area | Decision |
|---|---|
| Release application ID | `io.github.ntiendat01.replay` — tied to the owner's GitHub namespace, no "spotify" mark |
| Debug application ID | `io.github.ntiendat01.replay.debug` (`applicationIdSuffix`); installs side by side with release |
| Code namespace | Unchanged `com.spotifyautoplaylist.spike` (R class / Kotlin packages); renaming belongs to TASK-050A |
| Version source | `replay.versionName` in `gradle.properties`, strictly `MAJOR.MINOR.PATCH` |
| versionCode | `MAJOR * 10000 + MINOR * 100 + PATCH`; build fails if the format is wrong or MINOR/PATCH ≥ 100 |
| Current version | `0.4.0` → versionCode `400` (debug name `0.4.0-debug`) |
| Release key | Owner-held PKCS12 keystore outside the repository; v2/v3 signing, no v1 (minimum SDK 29) |
| Debug key | Android default debug key per machine/runner; debug builds are disposable |
| Minification | Off for now; enabling R8 needs keep-rule and device verification (TASK-054) |

The application ID is permanent: changing it later produces a different app with no upgrade path or
local data continuity. The previous probe `com.spotifyautoplaylist.spike` (versionCode 3) is a
different app; it is not upgraded or removed by the new builds and keeps its own recovery journal.
After installing the new ID, link the existing smoke playlist again instead of creating another.

## Signing strategy

`app/build.gradle.kts` resolves release key material in this order:

1. Environment variables `REPLAY_SIGNING_STOREFILE`, `REPLAY_SIGNING_STOREPASSWORD`,
   `REPLAY_SIGNING_KEYALIAS`, `REPLAY_SIGNING_KEYPASSWORD` (all four required; intended for CI).
2. A properties file with `storeFile`, `storePassword`, `keyAlias`, `keyPassword`. Default
   `~/.replay-signing/signing.properties`; override with `-Preplay.signingProperties=<path>`.
   A present but incomplete file fails the build.
3. Otherwise the release signing config is absent and AGP emits `app-release-unsigned.apk`
   (not installable). This is the current CI behavior.

Local key created on 2026-11-04 on the owner's Mac:

| Property | Value |
|---|---|
| Location | `~/.replay-signing/replay-release.p12` and `signing.properties` (directory 700, files 600) |
| Type / alias | PKCS12 / `replay-release` |
| Key | RSA 4096, SHA384withRSA, self-signed |
| Subject | `CN=replay., OU=Personal, O=ntiendat01` |
| Validity | 2026-11-04 until 2054-03-22 (10,000 days) |
| Certificate SHA-256 | `67:18:88:93:8C:FD:4D:C8:10:A6:67:16:07:B7:32:C5:29:DD:05:C5:2E:4B:E5:C7:BD:D8:5E:00:EF:AC:A1:51` |

The password is random and exists only in `signing.properties`; it was never printed or committed.
The certificate fingerprint is public and lets anyone check a release APK's signer.

**Owner action required:** back up both files to an offline/encrypted location (for example a password
manager attachment). Losing the key means installed release builds can never be updated in place;
reinstalling then loses local history (the Spotify playlist itself remains).

CI release signing is not enabled. To enable it later (TASK-054), store the keystore as a base64
GitHub Secret plus the alias/passwords, decode to `$RUNNER_TEMP`, export the four environment
variables only for the release step, then verify with `apksigner` and compare the certificate digest
above. Until then, sign releases locally.

## Local verification

Toolchain: `/private/tmp/spotify-autoplaylist-toolchain` (Temurin JDK 17, SDK 36, Build Tools 35.0.0).

```sh
./gradlew --offline --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease --rerun-tasks
```

Observed: **BUILD SUCCESSFUL in 1m 43s**.

| Check | Observed result |
|---|---|
| `:auth-core:test` | 61 tests; 0 failures, errors or skips |
| `:app:testDebugUnitTest` | 23 tests; 0 failures, errors or skips |
| `:app:lintDebug` | 15 informational hints; no warnings, errors or fatal issues |
| Debug APK | `io.github.ntiendat01.replay.debug`, versionCode 400 / `0.4.0-debug`, `debuggable=true`, v2, Android Debug cert |
| Release APK | `io.github.ntiendat01.replay`, versionCode 400 / `0.4.0`, not debuggable, `apksigner` verifies (v3), one signer |
| Release signer | Certificate SHA-256 `671888938cfd…efaca151` — matches the keystore above |
| Release reproducibility | Rebuild produced the identical SHA-256 |
| No key available | `-Preplay.signingProperties=/nonexistent/…` → build succeeds, `app-release-unsigned.apk` |
| Bad version | `0.4` and `0.100.0` fail configuration with explicit messages |

Release APK SHA-256: `229839c78fe0e0fd8599bd63a9e47556f3232409f29fa39414702e0d92f95861`
(local, not distributed; recorded for traceability only).
Debug APK SHA-256 (local key): `142b60e6ead555a3c1ab38ec2bcbec46c946985892ae46d4562fd439a3f8205f`.

Dependency locks: `app/gradle.lockfile` was rewritten with `--write-locks` so release configurations
are locked too. A scripted comparison confirmed no dependency coordinate or version was added,
removed or changed; only configuration names (release, metadata) were appended to existing entries.

CI workflow now also runs `:app:assembleRelease` (unsigned) to compile and lint-vital the release
variant. The uploaded artifact is still the debug APK, now with the `.debug` ID.

## Remaining limits

- No GitHub Actions run has been observed for these changes yet.
- No device was connected over ADB during this task; installing the new IDs on the OnePlus,
  launching, and an in-place update test (same key, higher versionCode) are still pending.
- Launcher label is unchanged (`replay. · Auth thử nghiệm`) for both variants, so the two icons look
  identical when installed together. Distinct labels belong with UI work.
- No UI, Compose or runtime behavior changed; no visual comparison applies.
