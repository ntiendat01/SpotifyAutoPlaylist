# M6 Release runbook — TASK-054

**Status: PREPARED, NOT EXECUTED.** No signed release APK has been produced by this task. Nothing was installed on a device, and no version was tagged. The app is **not Released**. Only the owner can sign (their key lives outside the repo), install and tag.

## 1. Signing design (unchanged from TASK-013, now documented for release)

`app/build.gradle.kts` resolves release key material in this order:

1. **Environment variables:** `REPLAY_SIGNING_STOREFILE`, `REPLAY_SIGNING_STOREPASSWORD`, `REPLAY_SIGNING_KEYALIAS`, `REPLAY_SIGNING_KEYPASSWORD`. All four are required.
2. **A properties file** with `storeFile`, `storePassword`, `keyAlias` and `keyPassword`.
   - The default location is `~/.replay-signing/signing.properties`, outside the repo.
   - Override it with `-Preplay.signingProperties=<path>`.
   - An incomplete file fails the build.
3. **Neither present:** the release signing config is absent, and `app-release-unsigned.apk` is built. This is what CI does, so CI keeps passing.

Other settings:
- Signing uses v2 and v3 schemes, with no v1 (minSdk 29).
- `.gitignore` covers `*.jks`, `*.keystore`, `*.p12`, `*.pk8`, `keystore.properties`, `signing.properties`, `.replay-signing/` and `dist/`.
- The build does not print key values.

The **expected signer certificate SHA-256** is `67:18:88:93:8C:FD:4D:C8:10:A6:67:16:07:B7:32:C5:29:DD:05:C5:2E:4B:E5:C7:BD:D8:5E:00:EF:AC:A1:51` (M1-ANDROID-SIGNING.md). A release APK signed by any other key cannot update an installed release.

**Spotify config:**
- A release build needs the repository-root `spotify.local.properties` (`spotify.clientId`, `spotify.ownerUserId`), the same as debug. Without it, the release APK has login disabled, which is how CI builds.
- `scripts/release.sh` refuses to run without the file.
- The APK then contains the owner's Spotify user ID, so keep it private.

**Release variant checks** (observed on the unsigned local build, `aapt2`):
- applicationId is `io.github.ntiendat01.replay` with no `.debug` suffix.
- Version is `0.5.0` / code 500.
- It is not debuggable.
- `allowBackup=false`, and the backup and data-extraction rules exclude every domain. This matches the privacy notice ("không sao lưu lên đám mây").
- Cleartext traffic is allowed only for the `127.0.0.1` loopback (OAuth callback).

**R8 / minify stays off.**
- Retrofit/kotlinx-serialization, Room and WorkManager rely on reflection or generated classes. The repo has no instrumented or device tests of a minified build.
- Turning R8 on would need keep-rules plus a device E2E pass. The size saving isn't worth an untestable breakage risk for a sideloaded personal app.
- Revisit after TASK-053 has been run on a device.

## 2. Generate a keystore (only if you don't already have one)

You already own `~/.replay-signing/replay-release.p12` (TASK-013), so **reuse it**. A new key breaks in-place updates. To create one on your own machine anyway:

```sh
mkdir -p ~/.replay-signing && chmod 700 ~/.replay-signing
keytool -genkeypair -storetype PKCS12 -keystore ~/.replay-signing/replay-release.p12 \
  -alias replay-release -keyalg RSA -keysize 4096 -sigalg SHA384withRSA -validity 10000 \
  -dname "CN=replay., OU=Personal, O=ntiendat01"
cat > ~/.replay-signing/signing.properties <<'P'
storeFile=/Users/<you>/.replay-signing/replay-release.p12
storePassword=<typed by you>
keyAlias=replay-release
keyPassword=<typed by you>
P
chmod 600 ~/.replay-signing/*
```

Back up both files offline. If you lose them, installed releases can never be updated.

## 3. Build, verify, record

```sh
# repo root, clean tree, spotify.local.properties present, ANDROID_HOME set
bash scripts/release.sh            # or: GRADLE_ARGS="-Preplay.signingProperties=/path/signing.properties" bash scripts/release.sh
```

The script:
1. runs `assembleRelease`;
2. runs `apksigner verify --print-certs` and prints only the scheme, the signer count and the cert digest;
3. fails if the APK is debuggable;
4. writes `dist/replay-<ver>/` (ignored): the APK, `.sha256`, `badging.txt` and `source-commit.txt`.

After it finishes:
1. Compare the printed cert SHA-256 with §1.
2. Copy `docs/releases/TEMPLATE.md` to `docs/releases/v<ver>.md` and fill it in.
3. Tag the release yourself with `git tag v0.5.0 && git push origin v0.5.0`.

Manual equivalent:

```sh
./gradlew --no-daemon :app:assembleRelease
$ANDROID_HOME/build-tools/35.0.0/apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
sha256sum app/build/outputs/apk/release/app-release.apk
```

**Optional CI signing:** `.github/workflows/release.yml` runs only on `workflow_dispatch` (by hand).
- It uses the same pinned action SHAs as android-ci.yml.
- It needs these secrets: `REPLAY_KEYSTORE_B64` (`base64 -i replay-release.p12`), `REPLAY_STORE_PASSWORD`, `REPLAY_KEY_ALIAS`, `REPLAY_KEY_PASSWORD`, `SPOTIFY_CLIENT_ID` and `SPOTIFY_OWNER_USER_ID`. Put them in a `release` environment.
- It uploads the APK, its `.sha256` and the source commit as an artifact (30 days).
- It has never been run. The artifact contains your owner ID, so anyone with read access to Actions artifacts can download it. Prefer local signing if the repo is public.

## 4. Rollback APK

- Keep every previous `dist/replay-<ver>/` folder (APK and sha256) outside git, for example in your backup.
- Android refuses a **lower versionCode** over an installed app. To roll back, `adb install -r -d <old>.apk` works only for debuggable builds, so release rollback means uninstalling (local history is lost; the playlist stays) and then installing the old APK.
- Record the rollback APK and its SHA-256 in the release record.

## 5. Manual install and update test

Use `pkg=io.github.ntiendat01.replay`. The release and debug apps install side by side.

1. **Fresh install:**
   - `adb install dist/replay-0.5.0/replay-0.5.0.apk`.
   - Open **replay.** → **Quyền truy cập & cách hoạt động**. The privacy notice should be visible.
   - Tap **Kết nối Spotify**, then set up or link the playlist.
   - The Home hero **Mở trong Spotify** opens the playlist. Also confirm it appears in the Spotify Library on your phone and desktop.
2. **Update over previous version:**
   - Install release N (for example 0.5.0) and sync once.
   - Record the diagnostics (Settings → **Sao chép chẩn đoán**).
   - Bump `replay.versionName` (for example 0.5.1) and build with `scripts/release.sh`.
   - Run `adb install -r` with the new APK.
   - Expected: still logged in, the same diagnostics counts, the outbox `pending` values unchanged or reconciled, no onboarding shown, and **Đồng bộ ngay** works.
   - If install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, the APK was signed with a different key: **FAIL**.
3. **Uninstall:**
   - Run `adb uninstall $pkg`.
   - The Spotify playlist must still exist with the same tracks (TC-029).
   - Local history, rules and login are deleted, and there is no backup.
   - After reinstalling, you must log in again and link the existing playlist (not create a new one), and history before the reinstall can't be recovered beyond what Spotify still returns.
4. **Rollback:** follow §4 and confirm the old version launches.

## 6. Results template (NOT EXECUTED)

| Step | Date (UTC+7) | Device / OxygenOS | APK SHA-256 | Result PASS/FAIL/BLOCKED | Evidence | Notes |
|---|---|---|---|---|---|---|
| Fresh install | | | | | | |
| Update N→N+1 | | | | | | |
| Uninstall keeps playlist | | | | | | |
| Rollback | | | | | | |

## 7. Release checklist (docs/08) → status

| Exit criterion | Where | Status |
|---|---|---|
| API/OAuth/ownership gates on device | M0, M5-DEVICE-E2E | Pending device run |
| All automated tests green, device report attached | verify.sh 397 tests; M5-DEVICE-E2E | Tests green locally; device report missing |
| No credentials in code/APK config/logs/CI | gitignore, sanitized logs | Owner ID in APK by design (keep private) |
| Privacy notice: local history and Spotify authorization | Onboarding → Quyền truy cập & cách hoạt động (expanded in TASK-054, ReplayUiTest) | Done in code; device check pending |
| Playlist opens from Spotify Library on phone and desktop | §5.1 | NOT EXECUTED |
| Uninstall doesn't delete playlist; reinstall limits disclosed | §5.3, privacy notice | NOT EXECUTED |
| Version tagged, signed checksum recorded, rollback kept | §3, §4, docs/releases | NOT EXECUTED (owner) |
| Known limitations documented | §8 | Done |

## 8. Known limitations

- Sync is best-effort. Periodic sync runs every 30 min at best, and OxygenOS may delay or kill it. Nothing has been verified on a device.
- History can be incomplete. Spotify may expose only part of it, a lost gap stays `HISTORY_INCOMPLETE`, and there are no invented plays.
- Quota: a 429 or quota-exceeded response pauses sync (6 h quota pause, 60 s default Retry-After, both local choices). A 403 in Development Mode is terminal, not retried.
- Single-flight is per process only. Room corruption recovery is not implemented.
- Relink only links an existing private playlist you own. `linked_from` may be missing because no `market` parameter is sent.
- Reinstalling loses local history. A changed signing key forces an uninstall.
- R8 is off, so the APK is larger (about 35 MB). Nothing has been tested on live Spotify, a device or in CI release signing.

## 9. Observed during TASK-054

`verify.sh` (the CI-equivalent) passes with 397 tests **without** `spotify.local.properties`, as in CI. With the file present (a login-enabled build), `ReplayLauncherTest > fresh real launcher…` fails with `IllegalStateException` at `WorkManagerImpl`: a configured build touches WorkManager, and the test doesn't initialise it. This is a test-environment issue that existed before TASK-054, not a change made here. Run tests without the file, or use `-x testDebugUnitTest` only for a release build whose tests already passed.
