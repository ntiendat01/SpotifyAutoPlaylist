# TASK-011 — GitHub Actions and debug APK artifact

Date: 2026-11-04 (Vietnam). Status: **PASS — CI and downloaded artifacts verified**.
Scope: compile/test the existing Android scaffold on GitHub Actions and verify downloadable artifacts.
Runtime behavior, application identity, signing strategy and UI are unchanged.

## Workflow changes

Reuses `.github/workflows/android-ci.yml`: push, pull request and manual triggers; read-only workflow
permissions; branch-scoped concurrency; 30-minute timeout; SHA-pinned actions; JDK 17; Android SDK 36
and Build Tools 35.0.0. The Gradle step runs core JVM tests, Android ViewModel JVM tests, lint and
debug APK assembly. These are not device instrumentation tests.

New acceptance steps:

- Verify the built APK with Build Tools `apksigner` before upload.
- Generate and check `app-debug.apk.sha256`; record the checked-out source SHA in `source-commit.txt`.
- Include the APK, checksum, source SHA and Gradle output metadata in `replay-auth-spike-debug-apk`.
- Add JUnit and lint XML to `auth-spike-test-reports` alongside HTML reports, including available
  diagnostics on failure. Upload only the explicit report paths, not raw HTTP/session logs.
- Retain both artifacts for 14 days. APK upload fails if there are no files; signature/checksum
  failures stop the job before APK upload.

No Spotify secrets, real tokens or personal history are used by this workflow. The tests use dummy
fixtures, fake repositories/journals and localhost MockWebServer.

## Source and run

The active `Dev` working tree already contained the TASK-010 changes. A separate worktree/branch
`codex/task-011-ci` was created from `6337fbc`; only the workflow and the matching TASK-010
`app/build.gradle.kts` / `gradle/libs.versions.toml` changes were committed for CI. Unrelated local
documentation edits were not included. The three source files were compared byte-for-byte with
the active working tree, and the added shell block passed `bash -n`.

- CI commit: `f7a1f01ded6b2aadb62d5f20324ced69acb3928e`.
- [Android CI run 41860277255](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41860277255).
- Branch was pushed to the existing private repository; no merge or push to `Dev`/`main` was performed.
- This run verifies both TASK-010 catalog wiring and TASK-011 artifact changes. It replaces reliance
  on the earlier TASK-004 baseline run for those files.

## Observed acceptance

Run completed **successfully** on 2026-11-04, 22:46 Vietnam time (created at 22:41).
The Gradle test/lint/build step, APK verification and both artifact uploads all passed.
Both artifacts were downloaded to `/private/tmp/spotify-task-011-artifacts-41860277255/` and inspected.

| Check | Observed result |
|---|---|
| Core JUnit XML | 61 tests; 0 failures, errors or skips |
| Android ViewModel JUnit XML | 23 tests; 0 failures, errors or skips |
| Lint XML | 16 informational hints; no warnings, errors or fatal issues |
| Downloaded source SHA | Exact match to `f7a1f01ded6b2aadb62d5f20324ced69acb3928e` |
| Downloaded APK checksum | SHA-256 matches packaged checksum |
| Downloaded APK signature | `apksigner verify --verbose`: verifies, v2 signature, one signer |
| APK metadata / binary inspection | `com.spotifyautoplaylist.spike`, versionCode 3 / `0.3.0-api-spike`; compile/target SDK 36 |
| Build/workflow source parity | All three files in the CI commit match the active working tree byte-for-byte |

APK SHA-256: `bd348c30bf92ad8c8f3122379f6007b1cb8c9721365e877efc8ed0e80c83694c`.
This is the downloaded CI APK checksum, distinct from TASK-010's locally signed APK checksum.

| Artifact | ID | Archive size | Expiry reported by GitHub |
|---|---|---|---|
| [replay-auth-spike-debug-apk](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41860277255/artifacts/13339727564) | 13339727564 | 11,199,074 bytes | 2026-11-18, 22:46 Vietnam |
| [auth-spike-test-reports](https://github.com/ntiendat01/SpotifyAutoPlaylist/actions/runs/41860277255/artifacts/13339543432) | 13339543432 | 54,234 bytes | 2026-11-18, 22:46 Vietnam |

No additional local Gradle rerun was needed: CI compiled and tested the matching build sources.
No new unit tests were added for artifact packaging; the live run and downloaded artifact inspection
verify the workflow's actual outputs.

## Download and verification

Sign into GitHub with repository read access, open the run above and download
`replay-auth-spike-debug-apk` under **Artifacts**. Unzip before installing `app-debug.apk`.
From the extracted folder on macOS:

```sh
shasum -a 256 -c app-debug.apk.sha256
```

The file `source-commit.txt` must match the run's source SHA. Inspect `output-metadata.json` for
package/version identity. With Android Build Tools available, use `apksigner verify --verbose app-debug.apk`.
Download `auth-spike-test-reports` for test/lint evidence. For CLI download:

```sh
gh run download 41860277255 -n replay-auth-spike-debug-apk -D /private/tmp/replay-task-011-apk
gh run download 41860277255 -n auth-spike-test-reports -D /private/tmp/replay-task-011-reports
```

Official references: [GitHub artifact download](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts),
[upload-artifact](https://github.com/actions/upload-artifact),
[Android apksigner](https://developer.android.com/tools/apksigner).

## Limits and next task

No UI changes or visual comparison is required for this workflow-only task. Successful compilation
does not establish OnePlus install/launch/navigation acceptance (TASK-012), stable signing
(TASK-013/054), full M1 exit or live Spotify gate completion. A new runner's debug signing key may
differ from a previously installed probe; do not erase its recovery journal merely to retry a write.
TASK-012 should first check signature/update compatibility before changing the device installation.
The successful run also reports maintenance annotations: pinned Node 20 actions are being run with
Node 24, `setup-java` v4 is deprecated, and the `ubuntu-latest` runner label is migrating. These did
not fail this run. They remain follow-up maintenance; no action-version or runner-image upgrade is
silently included in this acceptance record. There is no remaining TASK-011 build/artifact blocker.
The Kotlin/AGP vendor-support qualification and M0 evidence limits remain in
[M1-ANDROID-SCAFFOLD.md](M1-ANDROID-SCAFFOLD.md) and [M0-API-SPIKE.md](M0-API-SPIKE.md).
