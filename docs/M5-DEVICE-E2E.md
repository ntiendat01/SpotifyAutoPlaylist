# M5 Device E2E runbook — TASK-053

**Status: PREPARED, NOT EXECUTED.** This runbook has never been run on a device, on live Spotify or in CI. Nothing here is a PASS. Record results only from your own runs on the OnePlus (OxygenOS).

Scope (docs/08): TC-018..025 and TC-029..030 from [07-TEST-PLAN.md](07-TEST-PLAN.md), plus OxygenOS background delays. UI-TC device checks are covered separately in [M5-UI-ACCEPTANCE.md](M5-UI-ACCEPTANCE.md).

## 1. Prerequisites

1. **APK.** CI artifacts are built without `spotify.local.properties`, so login is disabled in them (preview only). **E2E needs a local build.**
   - Create the ignored repository-root `spotify.local.properties` with `spotify.clientId=<32-hex public Client ID>` and `spotify.ownerUserId=<Developer App owner user ID>` (root README §Try on OnePlus). Never add a client secret.
   - Build with `./gradlew :app:assembleDebug`, then `adb install -r app/build/outputs/apk/debug/app-debug.apk`.
   - Package: `io.github.ntiendat01.replay.debug`. Debug builds only log sanitized lines (tags `ReplaySync`, `SpotifyApi`).
2. **Spotify app and accounts.**
   - The Spotify app is installed and logged in to the **same Premium account that owns the Developer App**.
   - The phone browser can log in to accounts.spotify.com.
3. **Spotify Developer Dashboard** (same app as the Client ID):
   - Redirect URI is exactly `http://127.0.0.1:8765/callback`.
   - The app is in Development Mode, and the test account is added under *User Management* (allowed users).
   - Web API is enabled.
   - For TC-025 you also need a second Spotify account that is **not** in User Management.
4. **OxygenOS battery settings** for replay. (debug). Set all three before the background tests, and record which ones you changed:
   - Settings → Battery → (App battery management) → replay. → **Unrestricted / Allow background activity**.
   - Settings → Apps → replay. → **Auto-launch: on** (OxygenOS may call this "Allow auto launch").
   - Battery → Advanced → turn off **deep / intelligent optimisation** for the app or globally (wording varies by build).
   - Note the OxygenOS build (Settings → About device) in every result row.
5. A PC with `adb` (USB debugging on), and the phone clock on automatic time.

## 2. Screen map (actual implemented copy)

- **Onboarding:**
  - **Kết nối Spotify** opens the browser login.
  - **Quyền truy cập & cách hoạt động** explains the scopes.
  - **Xem thử giao diện** is the preview without login.
- **Playlist setup:** **Tạo playlist riêng tư** (then confirm "Tạo playlist trên Spotify?"), or paste in **Link playlist Spotify**. **Xác minh lại playlist** appears after an uncertain outcome.
- **Tabs:** Home · Lịch sử · Bộ sưu tập · Quy tắc. Settings opens from the gear icon (**Mở cài đặt**) in the top bar.
- **Home hero:** **Mở trong Spotify**.
- **Settings** (TASK-050G) has:
  - the sync headline;
  - "Lần thành công gần nhất: …";
  - "Lần chạy gần nhất: nhập N lượt phát · M bài mới đủ điều kiện";
  - "Đã thêm (đã xác minh) … · Đang chờ … · Đang xác minh … · Cần kiểm tra …";
  - **Đồng bộ ngay** (shows "Đang chờ kết quả…" and then the result);
  - **Sao chép chẩn đoán**;
  - **Ngắt kết nối Spotify**.
- **Sync headlines** (`SyncCopy`):

  | Status code | Headline |
  |---|---|
  | SUCCESS | "Đã đồng bộ lúc HH:mm" |
  | PARTIAL | "Đồng bộ một phần lúc …" |
  | NETWORK_ERROR | "Không có mạng · Hiển thị dữ liệu trên máy" |
  | AUTH_REQUIRED | "Cần đăng nhập lại Spotify" |
  | ACCOUNT_MISMATCH / ACCOUNT_CHANGED | "Tài khoản Spotify không khớp với tài khoản đã thiết lập." |
  | RELINK_REQUIRED | "Playlist cần liên kết lại" |
  | FORBIDDEN | "Spotify từ chối quyền truy cập" |
  | RATE_LIMITED | "Spotify đang giới hạn yêu cầu. Sẽ thử lại." |
  | QUOTA_EXCEEDED | "Đã hết hạn mức Spotify API. Tạm dừng đồng bộ." |
  | DEFERRED | "Đồng bộ tạm hoãn" |

## 3. Test procedures

Pass criteria are copied verbatim from 07-TEST-PLAN.md. Before and after each TC, capture **Sao chép chẩn đoán** (§5).

### TC-018 — E2E — OAuth PKCE connect, state valid
**Pass criteria:** *Tokens stored securely, account linked*
1. Do a fresh install (or clear the app's data). Open replay. and tap **Kết nối Spotify**.
2. In the browser, log in as the allowed owner account and accept the consent screen. The browser returns to `127.0.0.1:8765/callback` and then to the app.
3. Expected: the playlist setup screen appears with no error banner. Tap **Tạo playlist riêng tư**, then confirm. Expected: the tab shell, and the Home hero names the playlist.
4. Settings shows **Đã kết nối Spotify…**.
5. The "stored securely" part cannot be inspected without root. Evidence is the linked state plus `adb logcat -s ReplaySync SpotifyApi` showing no token or code strings. If anything token-like ever appears, record **FAIL**.

### TC-019 — Integration — OAuth state mismatch / expired callback
**Pass criteria:** *Reject, no token exchange*
1. Starting disconnected, tap **Kết nối Spotify**. Leave the Spotify login page open and don't finish it.
2. In a new browser tab on the phone, open `http://127.0.0.1:8765/callback?code=dummy&state=wrong`.
3. Expected: the app shows an error ("Chưa xác minh được kết nối Spotify…" or similar) and stays unlinked. Logcat shows no `SpotifyApi` line for the accounts/token endpoint.
4. Expired callback: finish a login, then reload the old callback URL from browser history. Expected: no change to the linked account.
5. Exact copy for the mismatch case isn't guaranteed. Record what you saw.

### TC-020 — Integration — 401 refresh then success; refresh failure
**Pass criteria:** *Retry once or request re-auth*
1. Refresh success: stay linked for more than 60 min (the access token expires), then tap **Đồng bộ ngay**. Expected: "Đã đồng bộ lúc …". Logcat may show a refresh followed by success. There is no way to force an exact 401 by hand.
2. Refresh failure: at spotify.com → Account → Apps → **Remove access** for the Developer App, then tap **Đồng bộ ngay**. Expected: "Cần đăng nhập lại Spotify", and diagnostics show `outcome=AUTH_REQUIRED`.

### TC-021 — Integration — 429 with Retry-After
**Pass criteria:** *Pause and retry after limit*
- **Not reliably reproducible by hand.** Spotify decides when to rate-limit.
- Opportunistic: if you ever see "Spotify đang giới hạn yêu cầu. Sẽ thử lại.", record `blockedUntil` from diagnostics and confirm a later run succeeds after that time.
- Otherwise record **BLOCKED (cannot induce)**. JVM coverage exists (M5-ERROR-HANDLING).

### TC-022 — Integration — 429 QUOTA_EXCEEDED
**Pass criteria:** *Stop request loop; show quota status*
- Same as TC-021: it cannot be induced safely. Hammering the API is against the rules.
- If you see "Đã hết hạn mức Spotify API. Tạm dừng đồng bộ.", confirm logcat stops showing `SpotifyApi` lines until `blockedUntil`.
- Otherwise record **BLOCKED**.

### TC-023 — E2E — OxygenOS delays periodic worker
**Pass criteria:** *UI shows last sync accurately; manual sync works*
1. Follow §4 for at least 24 h. Run it twice: once with the §1.4 battery settings, and once with the defaults (optional).
2. Each time you check, compare the Settings "Lần thành công gần nhất" time with the latest actual run in `dumpsys`/logcat. They must match to the minute.
3. Tap **Đồng bộ ngay**. Expected: "Đang chờ kết quả…", then a result headline with a new time. With nothing new, expect "Đồng bộ thành công — không có bài mới".

### TC-024 — E2E — App force stop/restart during sync
**Pass criteria:** *No inflated counts or lost committed events*
1. Record diagnostics (`added=`, `tracksTracked=`, `lastRun=`). Play a few songs in Spotify first so there is something to import.
2. Tap **Đồng bộ ngay**. While it shows "Đang chờ kết quả…", run `adb shell am force-stop io.github.ntiendat01.replay.debug`.
3. Reopen the app and tap **Đồng bộ ngay** again.
4. Expected:
   - the counts only grow by the songs actually played;
   - the playlist in Spotify has no duplicate tracks;
   - Lịch sử shows each play once.

   Repeat 3×.

### TC-025 — Integration — 403 endpoint inaccessible in Development Mode
**Pass criteria:** *Explicit unsupported status, no endless retry*
- Log in with an account **not** in User Management. Spotify usually rejects this at login or at `/me` with a 403.
- Expected: an explicit error, either the onboarding Spotify-permission message ("Spotify chưa cho phép…" / "Chưa xác minh được kết nối…") or the Settings headline "Spotify từ chối quyền truy cập".
- Watch logcat for 10 min. There should be no repeating `status=403` lines.
- Because the account isn't allowed, setup can't be completed. Record the screen you actually saw.

### TC-029 — E2E — Uninstall app after playlist created
**Pass criteria:** *Playlist still in Spotify (verify before uninstall)*
1. With the app linked, tap **Mở trong Spotify** and note the playlist name and its track count in the Spotify app (take a screenshot).
2. Run `adb uninstall io.github.ntiendat01.replay.debug`.
3. In the Spotify app, the playlist must still exist with the same tracks.

### TC-030 — E2E — Offline then reconnect
**Pass criteria:** *Local view works, outbox eventually reconciles*
1. Play 3 times a song that isn't in the playlist yet (so it qualifies under the default "3 lượt trong một ngày").
2. Turn on airplane mode and open the app. Expected: all tabs show local data, and **Đồng bộ ngay** ends with "Không có mạng · Hiển thị dữ liệu trên máy".
3. Turn airplane mode off and tap **Đồng bộ ngay**, or wait for the periodic run.
4. Expected: diagnostics `pending` drops to 0, `added` grows, and the song appears once in the Spotify playlist.
5. Note: plays made while offline can only be imported once Spotify has recorded them.

## 4. Observing background delays on OxygenOS

The periodic work is a WorkManager unique job `spotify-autoplaylist-periodic-sync`: every 30 min, network required, exponential backoff starting at 5 min. Manual runs use `spotify-autoplaylist-manual-sync`. Both carry the tag `spotify-autoplaylist-sync`.

- **Record:** for each run, the expected time (previous run + 30 min) and the actual time from logcat/diagnostics. Then compute the delay and note the screen/charging/Doze state. Over 24 h that is about 48 expected runs.
- **Logcat (debug build, sanitized):** `adb logcat -v time -s ReplaySync:I SpotifyApi:I WM-WorkerWrapper:I WM-SystemJobScheduler:I`. `ReplaySync` lines look like `sync PERIODIC SUCCESS write=… imported=… qualified=… added=…`. Use `adb logcat -b all -c` before starting, and save with `adb logcat -v time -s ReplaySync:I > sync-24h.txt` while USB is connected. A long logcat may lose early lines, so also check diagnostics.
- **JobScheduler state:** run `adb shell dumpsys jobscheduler io.github.ntiendat01.replay.debug`. It shows the job id (`JOB #u0a…/<id>`), constraints, and the "Last run"/"Next run" information. Doze/standby state is shown by `adb shell dumpsys deviceidle` and `adb shell am get-standby-bucket io.github.ntiendat01.replay.debug`.
- **Force a run:** use `adb shell cmd jobscheduler run -f io.github.ntiendat01.replay.debug <job-id>`, with the id from `dumpsys`. This triggers WorkManager's SystemJobService, which runs eligible work. The in-app equivalent is **Đồng bộ ngay**. There is no WorkManager diagnostics UI or debug broadcast in this app.
- **Simulate Doze:** `adb shell dumpsys deviceidle force-idle`, then `adb shell dumpsys deviceidle unforce`. This is Doze only; OxygenOS's own app killer can't be simulated this way, so you need the real 24 h observation.
- Don't share full `dumpsys` output. Copy only the lines for this package.

## 5. Copyable diagnostics checklist (existing 050G feature)

Open Settings → **Sao chép chẩn đoán**. The clipboard text contains only these keys (codes, counts, times): `app`, `outcome`, `error`, `finished`, `lastSuccess`, `blockedUntil`, `coverage`, `playlistLinked`, `thresholds`, `lastRun`, `added/pending/verifying/review`, `tracksTracked`.

Before sending:
- [ ] it contains no track name, artist, playlist ID or URL, token, `code=` or `state=`;
- [ ] the `outcome` and `error` values are uppercase codes (anything unknown shows `OTHER`);
- [ ] add the device model and OxygenOS build by hand.

## 6. Results template

| TC id | Date (UTC+7) | Device / OS build | Result (PASS/FAIL/BLOCKED) | Evidence file | Notes |
|---|---|---|---|---|---|
| TC-018 | | OnePlus … / OxygenOS … | | | |
| TC-019 | | | | | |
| TC-020 | | | | | |
| TC-021 | | | | | |
| TC-022 | | | | | |
| TC-023 | | | | | |
| TC-024 | | | | | |
| TC-025 | | | | | |
| TC-029 | | | | | |
| TC-030 | | | | | |

Delay log (TC-023):

| Expected run | Actual run | Delay (min) | Screen/charging | Battery setting | Outcome |
|---|---|---|---|---|---|

## 7. What to send back

- **Send:** the filled tables, diagnostics text (§5), screenshots of app screens, and `ReplaySync`/`SpotifyApi` logcat lines only.
- **Never send:**
  - `spotify.local.properties`;
  - tokens, auth codes, `state` values or callback URLs with query strings;
  - the Spotify user ID or email;
  - track/playlist names if you consider them private (crop them out);
  - full bug reports or `dumpsys` output.
- Mark a TC **BLOCKED** when it couldn't be run as written, and say why. Don't mark PASS based on JVM tests.

## 8. Known risks to watch

- **History API:** cursor semantics and history retention were never exercised live. A permanently lost history gap leaves the app `HISTORY_INCOMPLETE` and re-walking pages on each run (M3-HISTORY-SYNC).
- **Single-flight:** it is in-process only. Watch for overlapping periodic and manual runs after a process restart (M5-SYNC-WORKER, M5-ERROR-HANDLING).
- **Read-after-write lag:** Spotify's lag and real eventual consistency are not modelled. Check for duplicates or tracks stuck in `verifying` (M4-OUTBOX-WRITER, M4-TEST-TRACEABILITY).
- **Process death:** it was only simulated, never a real kill mid-transaction. TC-024 is the first real check.
- **Local constants:** the 6 h quota pause and the 60 s default Retry-After are local choices. 403 is not retried by backoff.
- **`linked_from`:** it may be missing because no `market` parameter is sent, so relinked/regional track variants could look absent (M4-PLAYLIST-RECONCILE).
- **OxygenOS:** background killing and delays are entirely unverified. Periodic runs may be far apart, and manual sync is the fallback.
- **Room corruption:** recovery is not implemented.
