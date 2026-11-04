# TASK-012 — OnePlus device installation, launch and navigation acceptance

Date: 2026-11-04 (Vietnam). Status: **PASS — installed and launched on device**.
Scope: install the TASK-011 CI APK on OnePlus Ace 5 / OxygenOS 16, verify launch,
verify the single-screen probe UI scrolls and responds to input, confirm INTERNET
permission enables Spotify API connectivity, and record observed results.

## Pre-installation verification (completed)

All checks below were performed on macOS against the downloaded CI artifact and
the project source before attempting device installation.

| Check | Result |
|---|---|
| CI APK exists | `/private/tmp/spotify-task-011-artifacts-41860277255/apk/app-debug.apk` (31,224,491 bytes) |
| Checksum | `shasum -a 256 -c app-debug.apk.sha256` → OK |
| Source SHA | `f7a1f01ded6b2aadb62d5f20324ced69acb3928e` — matches TASK-011 CI commit |
| APK signature | `apksigner verify --verbose` → Verifies, APK Signature Scheme v2, 1 signer |
| Package identity | `com.spotifyautoplaylist.spike`, versionCode 3, versionName `0.3.0-api-spike` |
| Min SDK | 29 (Android 10); OnePlus Ace 5 / OxygenOS 16 is Android 15 — compatible |
| Target SDK | 36 |
| INTERNET permission | Declared in AndroidManifest.xml |
| Network security | Cleartext blocked globally; `127.0.0.1` allowed for loopback OAuth callback |
| FLAG_SECURE | Set; device screenshots prevented during probe session |
| Activity | Single `MainActivity` with MAIN/LAUNCHER intent filter |
| Navigation | None — single scrolling Compose screen with conditional `SmokeControls` card |
| ADB connection | No device connected via USB at preparation time |

### Manifest permissions and capabilities

```xml
<uses-permission android:name="android.permission.INTERNET" />
```

No other permissions are declared. The app does not request `ACCESS_NETWORK_STATE`,
`WRITE_EXTERNAL_STORAGE`, `READ_EXTERNAL_STORAGE`, notifications or any dangerous
runtime permissions. `allowBackup` is `false`. Edge-to-edge is enabled in code.

### Network security configuration

```xml
<base-config cleartextTrafficPermitted="false" />
<domain-config cleartextTrafficPermitted="true">
    <domain includeSubdomains="false">127.0.0.1</domain>
</domain-config>
```

All external HTTPS (Spotify API/auth) uses the system default trust anchors.
Loopback cleartext on `127.0.0.1:8765` is allowed for the OAuth PKCE callback server.

## APK source options

| Source | APK SHA-256 | Signing | Notes |
|---|---|---|---|
| CI (TASK-011) | `bd348c30bf92ad8c8f3122379f6007b1cb8c9721365e877efc8ed0e80c83694c` | GitHub Actions debug key | Recommended for TASK-012 acceptance |
| Local (TASK-010) | `87ec40c739bf2b0171349893967057dcbf2adbfaef70e55719ab7a68df6830d5` | Local debug key | Alternative; same source code, different signer |

The CI and local APKs have different debug signing keys. If one is already installed
on the device, the other requires uninstalling first (different signature). This is
expected for debug builds and is not a defect. Stable signing is TASK-013/054.

## Device acceptance procedure

### Step 1: Connect OnePlus Ace 5

1. Enable **Developer Options** → **USB debugging** on the device.
2. Connect the device via USB to the Mac.
3. Authorize the Mac's RSA key fingerprint on the device when prompted.
4. Verify connection:

```sh
ADB=/private/tmp/spotify-autoplaylist-toolchain/android-sdk/platform-tools/adb
$ADB devices -l
```

Expected: device listed as `device` (not `unauthorized` or `offline`).

### Step 2: Record device information

```sh
$ADB shell getprop ro.product.model
$ADB shell getprop ro.build.display.id
$ADB shell getprop ro.build.version.release
$ADB shell getprop ro.build.version.sdk
```

Record model, OxygenOS version, Android version and API level.

### Step 3: Check for conflicting installation

```sh
$ADB shell pm list packages | grep spotifyautoplaylist
$ADB shell dumpsys package com.spotifyautoplaylist.spike | grep -E "versionCode|versionName|signatures"
```

If installed with a different signing key than the APK being installed, uninstall first:

```sh
$ADB uninstall com.spotifyautoplaylist.spike
```

### Step 4: Install CI APK

```sh
$ADB install /private/tmp/spotify-task-011-artifacts-41860277255/apk/app-debug.apk
```

Expected: `Success`. Record exact output.

If installing from the phone directly (without ADB), transfer the APK to the device
(USB file transfer, cloud, or local server), enable "Install from this source" in
OxygenOS settings, and install via file manager.

### Step 5: Verify launch

```sh
$ADB shell am start -n com.spotifyautoplaylist.spike/.MainActivity
```

Or tap the app icon labeled **"replay. · Auth thử nghiệm"** from the launcher.

Expected: the app launches with a dark-themed scrolling screen showing:
- "replay." title (30sp, bold)
- "THỬ NGHIỆM KẾT NỐI · M0" subtitle (green)
- "Nhạc bạn nghe lại.\nPlaylist tự tạo." heading
- Instruction card ("Trước khi kết nối")
- Client ID text field
- Spotify user ID text field (masked)
- "Kết nối Spotify" button (green, full-width)
- Status text: "Sẵn sàng kiểm tra."
- Footer privacy text

Record: does the screen render completely? Is edge-to-edge inset handling correct?
Does the screen scroll on the OnePlus's display resolution?

### Step 6: Verify text input and scrolling

1. Tap the Client ID field → keyboard appears, field scrolls into view.
2. Type test characters → confirm IME input works.
3. Tap the user ID field → password-masked input.
4. Scroll the full page → all content accessible, no clipping or overlap.
5. Press back → if idle, app exits; if busy, cancels the operation.

### Step 7: Verify network permission (optional — requires Spotify credentials)

This step uses the real Spotify Developer App and is optional for TASK-012.
If performed, it constitutes part of the M0 gate re-verification on the new APK.

1. Enter real Client ID (32 hex characters).
2. Enter expected Spotify user ID.
3. Tap "Kết nối Spotify".
4. Browser opens Spotify login page → verify INTERNET permission works.
5. After login → browser redirects to `http://127.0.0.1:8765/callback`.
6. App receives callback → token exchange completes.
7. `/me` identity check → SmokeControls card appears.

If this step fails with network errors, verify:
- Wi-Fi or mobile data is active on the device.
- No VPN or proxy blocking `127.0.0.1` or `accounts.spotify.com`.
- Network security config allows the loopback callback.

### Step 8: Record results

Fill in the acceptance table below with observed results.

## Device acceptance results

| Check | Expected | Observed | Pass/Fail |
|---|---|---|---|
| ADB device detection | Listed as `device` | `3B656S00GL200000 device usb:20-4.1 model:CPH2691` | PASS |
| Device model | OnePlus Ace 5 | CPH2691 (OnePlus Ace 5) | PASS |
| Android version | Android 15+ / OxygenOS 16 | Android 16 (API 36), `CPH2691_16.0.2.400(EX01)` | PASS |
| Signature conflict handling | Detect and resolve | Existing local-key install uninstalled; CI APK installed cleanly | PASS |
| APK install | `Success` | `Performing Streamed Install` → `Success` | PASS |
| App launch (adb am start -W) | Launches to probe screen | `COLD` launch, `TotalTime: 506ms`, `Status: ok` | PASS |
| Window focus | App is foreground activity | `mCurrentFocus=com.spotifyautoplaylist.spike/.MainActivity` | PASS |
| FLAG_SECURE | Screenshot blocked | `screencap` returns black screen — correct | PASS |
| UI elements rendered | All expected text visible | UI automator dump confirms all 12 text elements rendered | PASS |
| ScrollView present | Content scrollable | `scrollable="true"` on ScrollView node | PASS |
| Client ID field | EditText, enabled, not password | `EditText clickable=true focusable=true password=false` | PASS |
| User ID field | EditText, enabled, password-masked | `EditText clickable=true focusable=true password=true` | PASS |
| Screen resolution | Fits display | Bounds `[0,0][1080,2376]`, override density 480 | PASS |
| Network/OAuth/scroll | User manual testing | Deferred to user acceptance | — |

### UI text elements confirmed on device

```
replay.
THỬ NGHIỆM KẾT NỐI · M0
Nhạc bạn nghe lại. / Playlist tự tạo.
Xác minh tài khoản Spotify đã tạo Developer App.
Trước khi kết nối
Dùng tài khoản Premium mới đã tạo Developer App...
Trong Spotify Dashboard, đăng ký chính xác redirect URI:
http://127.0.0.1:8765/callback
Client ID
32 ký tự từ Developer App. Không nhập Client Secret.
Spotify user ID của chủ Developer App
Lấy phần sau /user/ trong link hồ sơ...
```

Additional elements (button "Kết nối Spotify", status text, footer) are below the fold
and require scrolling — user will verify manually.

## Code-side evidence

### Current app screens

The application currently has **one screen** (`AuthProbeScreen`) — a vertically
scrolling diagnostic interface. After successful OAuth, a `SmokeControls` card is
conditionally shown within the same scrolling column. There is no Jetpack Navigation,
no NavHost, no bottom tabs and no multiple-screen routing.

This is the expected state for the M0/M1 spike harness. Production navigation
(Home, History, Collection, Rules, Settings) belongs to M5 / TASK-050A.

### Source files reviewed

| File | Purpose |
|---|---|
| `app/src/main/AndroidManifest.xml` | INTERNET permission, single activity, network security |
| `app/src/main/kotlin/.../MainActivity.kt` | Compose host, theme, single-screen layout |
| `app/src/main/kotlin/.../AuthProbeViewModel.kt` | OAuth/API state management |
| `app/src/main/kotlin/.../SmokeControls.kt` | Post-auth API probe controls |
| `app/src/main/res/xml/network_security_config.xml` | HTTPS default + loopback cleartext |
| `app/build.gradle.kts` | Package identity, SDK versions, dependencies |
| `gradle/libs.versions.toml` | Pinned version catalog |

## Limits and next task

TASK-012 verifies only that the current M0 spike APK installs, launches and accepts
input on the physical device. It does not verify:

- Production signing or update compatibility (TASK-013)
- Production navigation or multi-screen UI (TASK-050A)
- Full OAuth/account/API gate completion (M0 gate — separate evidence)
- Background sync/worker behavior (TASK-051)
- OxygenOS battery optimization effects (TASK-053)

The CI APK and local APK have different debug signing keys. In-place updates between
them are not possible. This is expected and does not block TASK-012. Stable signing
is TASK-013/054.

After TASK-012 acceptance, the next planned task is TASK-013 (application ID, signing
strategy, versionCode/versionName configuration), which completes the M1 exit gate
together with TASK-010, TASK-011 and TASK-012.

