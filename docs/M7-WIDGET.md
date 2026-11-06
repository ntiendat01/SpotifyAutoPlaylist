# M7 — TASK-060 Home Screen Widget (restyled for the new UI)

**Status: implemented locally (JVM + Robolectric tests, debug APK). Not verified on a real device or launcher.**

## What it shows

- Branding `replay` + emerald `.` (same as `ReplayTopBar`)
- Songs automatically added: Room `playlist_outbox` rows in `PRESENT` on the managed playlist — the same
  number as the in-app `PlaylistHero` ("N bài đã xác minh trên Spotify"). Like `LibraryModel.confirmedCount`,
  0 with no successful pass is shown as "—" / "Chưa xác minh số bài trên Spotify", not as zero.
- Last successful sync (`SyncStatusRepository.stored().lastSuccessAtMs`, formatted by `LibraryModel.time`)
- Current sync status (exact `SyncCopy.headline` wording used by Home/Settings)
- Actions: **Mở trong Spotify** (medium tier: "Mở Spotify") → `https://open.spotify.com/playlist/<id>`;
  **Mở app** and tapping the card → `MainActivity`. The Spotify action is hidden when nothing is linked.

## Matching the new UI

RemoteViews cannot use Compose themes, so `res/values/colors.xml` mirrors `ReplayTokens`
(`ui/ReplayTheme.kt`); `ReplayWidgetViewsTest` fails if any widget color drifts from its token.

| New UI element | Widget equivalent |
|---|---|
| `ReplayCard(hero = true)` gradient + bounded emerald glow, Stroke border, 24dp `HeroRadius` | `drawable/widget_background.xml` (layer-list; API 31+ uses the launcher's system widget radius so clipping matches) |
| `PrimaryActionButton` (emerald pill, `OnGreen` text, `External` glyph) | `widget_button_green` + `widget_ic_external` |
| Outlined/secondary button | `widget_button_outline` (Elevated + Stroke pill) |
| `SyncStatusChip` (surfaceVariant pill + `Sync` glyph) | `widget_chip` + `widget_ic_sync` |
| Problem state (`SyncStrip`/`CoverageBanner` Warning + `Info` glyph) | `widget_chip_warning` (Warning @ ~9%) + `widget_ic_info`, Warning text |
| `PlaylistHero` label "PLAYLIST SPOTIFY" in `Soft`, name, big count | Large tier header block |
| `ReplayGlyph` vectors (stroke 1.7, round caps) | `drawable/widget_ic_*.xml` use the same path data |

No new fonts or dependencies; system sans / sans-serif-medium approximate SemiBold.

## Approach: classic AppWidget + RemoteViews (not Glance)

Glance would add a dependency and lockfile/CI churn for a read-only card; RemoteViews is enough.

### Size tiers

| Tier | Size map entry (dp) | Layout |
|------|---------------------|--------|
| COMPACT | ≥ 110×40 (2×1, 4×1, 2×2) | Count + status, round emerald "open in Spotify" button |
| MEDIUM | ≥ 220×130 (default 4×2) | Brand + "Thành công HH:mm", count + caption, status chip, two pill actions |
| LARGE | ≥ 300×240 (4×3 … 5×4+) | Hero card: status chip, PLAYLIST SPOTIFY + name, 40sp count, last success, 48dp actions |

- API 31+: `RemoteViews(Map<SizeF, RemoteViews>)` from `WidgetLayoutTier` thresholds; launcher picks the largest fitting entry.
- API 29–30: `onAppWidgetOptionsChanged` → `WidgetLayoutTier.select(minWidth, minHeight)`.
- `targetCell` 4×2 (API 31+), `minWidth/minHeight` 250×110 (older launchers), `minResize` 110×40, `maxResize` 1000dp.
- `updatePeriodMillis=0` — no periodic widget work.

Renders (Robolectric native graphics, synthetic data, not device captures) in `docs/widget-shots/`:
`widget-tier-compact-2x1.png`, `widget-tier-medium-4x2.png`, `widget-tier-medium-4x2-min.png` (250×130),
`widget-tier-large-5x4.png`, `widget-state-offline-4x2.png`, `widget-state-loggedout-5x4.png`.
Regenerate with `./gradlew :app:testDebugUnitTest --tests '*ReplayWidgetViewsTest*' -Preplay.screenshots=<dir>`.

## Data (persisted only)

Room (`account.firstOrNull`, `managed_playlist`, `countPresent`) + sync-status DataStore via
`SyncStatusRepository.stored()`. Never reads auth storage, tokens or the Client ID value
(only whether one is configured). Provider reads run off the main thread via `goAsync()`.

## Refresh triggers

1. End of every `SyncWorker` pass (periodic and "Đồng bộ ngay", success and failure)
2. Onboarding playlist ready (`schedule()`) and sign-out (`disconnect()`)
3. Settings relink result
4. System `onUpdate` (widget added, boot/restore, app update)

## States

| Phase | Count / caption | Status |
|-------|-----------------|--------|
| LOADING | … / Đang đọc dữ liệu trên máy | Đang tải… |
| NOT_CONFIGURED | — / Bản cài này chưa cấu hình đăng nhập Spotify | Chưa cấu hình Spotify (warning) |
| LOGGED_OUT | — / Mở app để kết nối Spotify | Chưa kết nối Spotify (warning) |
| NO_PLAYLIST | — / Mở app để thiết lập playlist | Chưa liên kết playlist (warning) |
| EMPTY_PLAYLIST | 0 (or — if never verified) | SyncCopy |
| READY | N / bài đã xác minh trên Spotify | SyncCopy |
| ERROR | cached N | SyncCopy, warning (auth/relink/forbidden/mismatch/quota/rate limit) |
| OFFLINE | cached N | Không có mạng · Hiển thị dữ liệu trên máy (warning) |

## Manual test (device)

1. Install the login-enabled debug APK (`io.github.ntiendat01.replay.debug`), open the app once.
2. Long-press home → Widgets → **replay. tổng quan** → add (default 4×2).
3. Resize down to 2×1/4×1 (compact), 4×2 (medium), 4×3–5×4 (large); layout should switch without clipping.
4. Compare count/time/status with Home and Settings; press "Đồng bộ ngay" and wait for the widget to update.
5. Tap **Mở trong Spotify / Mở Spotify** → playlist in Spotify (or browser). Tap **Mở app** / card → app.
6. Turn on airplane mode, sync → warning chip "Không có mạng…" with cached count.
7. Disconnect Spotify in Settings → "Chưa kết nối Spotify", Spotify action hidden.
8. Force-stop the app and reboot → widget still shows the last persisted data and its buttons work.

## Limits

- Not tested on a physical launcher/OEM skin (OxygenOS cell sizes may pick a different tier near thresholds).
- Medium tier actions are 32dp tall (large tier 48dp) to fit 4×2; whole card is also a tap target.
- No in-flight "đang đồng bộ" state: like Settings, status changes when the pass finishes.
- Renders in `docs/widget-shots/` are synthetic Robolectric images, not device screenshots.
