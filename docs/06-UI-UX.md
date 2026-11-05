# 06 — UI/UX Specification: Spotify Neo × Replay Lab

> Version **1.2.0** · Status **Design baseline** · Updated 2026-11-04  
> Platform: OnePlus Ace 5 / OxygenOS 16 · Kotlin + Jetpack Compose / Material 3 · Language: Vietnamese  
> Owner of truth: this document owns visual, navigation, interaction and presentation rules. `02-BUSINESS-RULES.md` owns qualification semantics; `04-SPOTIFY-API.md` owns API availability.

## 0. Product and design intent

**Exact design references (v1.3):** see [`UI-VISUAL-REFERENCE.md`](UI-VISUAL-REFERENCE.md) and eight source PNGs in [`../design/reference/`](../design/reference/). All visual references must be reviewed alongside [`UI-VISUAL-DEVIATIONS.md`](UI-VISUAL-DEVIATIONS.md), which resolves conceptual-image vs. actual-MVP conflicts. For Codex use [`UI-CODEX-PROMPTS.md`](UI-CODEX-PROMPTS.md).

**Brand working name:** `replay.` (product/project remains **Spotify AutoPlaylist**). Independent personal utility; never imply an official Spotify app or use Spotify's logo as our own mark. Display Spotify attribution where required by Spotify platform/branding rules. No audio streaming or playback controls in MVP.

**Design direction:** Spotify Neo (AMOLED dark, prominent artwork, vivid green accents) + Replay Lab (compact, honest progress and listening analytics). Prioritize music first, diagnostics second. The interface must remain useful when no artwork, no history, offline, or with an API outage.

**Non-negotiable:** UI never invents plays, tracks, remote playlist state, coverage, or sync completion. All mock data in previews/tests is labeled demo and never appears in production without real backing data.

## 1. Information architecture

### 1.1 Routes

```text
AppStart
 ├─ Unauthenticated → Onboarding → Spotify OAuth browser → Account verification
 │                                    └─ Playlist setup (create/link) → Home
 └─ Authenticated → MainScaffold
       ├─ Home (dashboard)
       ├─ History (list) → Track Detail (drill-down)
       ├─ Collection (playlist entries) → Track Detail
       └─ Rules (thresholds)
 Settings (top-right on Home; secondary route, NOT a fifth bottom tab)
       ├─ Spotify account / playlist
       ├─ Sync health / diagnostics
       └─ Disconnect confirmation
```

Five main experiences: **Home, History, Collection, Rules, Settings**. Bottom navigation has **four** destinations (Home, History, Collection, Rules); Settings is accessed via gear icon in Home top bar and relevant context links. Onboarding is a separate unauthenticated flow. This prevents cramped five-item bottom navigation on phones.

### 1.2 Navigation behavior

- Default landing: Home after successful account and playlist setup. Unauthenticated: Onboarding.
- Preserve scroll position and filter state when switching tabs during same process session; process restoration uses `SavedStateHandle` where feasible.
- Android back: dismiss sheet/dialog → pop detail/settings → main tab → system exit; never unexpectedly disconnect or create playlist.
- External Spotify open: use playlist URL via Android intent; browser fallback if Spotify unavailable; show nonblocking error if both fail.
- Deep link after OAuth must validate state and account identity before entering authenticated graph; failed callback returns to onboarding with retry.

## 2. Design system / tokens

### 2.1 Color roles (hex)

| Token | Hex | Usage |
|---|---|---|
| `background` | `#090D0B` | AMOLED page background |
| `surface` | `#171F1A` | Cards / bottom navigation |
| `surfaceElevated` | `#223029` | Secondary cards / input backgrounds |
| `surfaceStroke` | `#34443A` | Subtle outlines / separators |
| `primary` | `#1ED760` | Primary CTAs, eligible progress, active tab |
| `primaryOn` | `#06140C` | Text/icons on primary |
| `accentSoft` | `#A6E9B7` | Supporting highlights |
| `textPrimary` | `#F1FFF5` | Titles, important values |
| `textSecondary` | `#A9B9AD` | Supporting copy |
| `textMuted` | `#83958A` | Timestamps, tertiary metadata |
| `warning` | `#F6BD60` | Partial coverage, delayed sync |
| `error` | `#FF7B80` | Sync failure, auth failure |
| `success` | `#1ED760` | Remote confirmed success |

Contrast must be tested to WCAG AA: ≥4.5:1 for normal text, ≥3:1 for large text and meaningful UI. Do not convey status only by color. Spotify album artwork may be used only when returned by permitted Spotify endpoints and in accordance with its display requirements; no generated fake cover art in production.

### 2.2 Layout, typography, components

- 8dp spacing grid; horizontal page padding 20dp; card inner padding 16–20dp; section gap 24dp; list row height ≥72dp; touch targets ≥48×48dp.
- Corner radii: 12dp (small), 18dp (cards), 24dp (hero), pill (buttons/chips). Shadows restrained; prefer elevation via surface tones.
- Typography: Android system sans; hero title 28sp/semibold, section title 20sp/semibold, card number 30sp/bold, body 14–16sp, caption 12–13sp. Support font scale ≥1.3 without clipped labels; use scrolling/wrapping.
- Native components: `ReplayScaffold`, `ReplayTopBar`, `PlaylistHero`, `TrackArtwork`, `TrackRow`, `PlayCountPill`, `ThresholdProgress`, `CoverageBanner`, `SyncStatusChip`, `MetricCard`, `EmptyState`, `ErrorState`, `PrimaryActionButton`, `ConfirmationSheet`, `ReplayBottomBar`.
- Image fallback: neutral disc icon with gradient based on design tokens, not fabricated album cover. Artwork loading asynchronous with placeholder and failure state.
- Dynamic artwork gradient: derive restrained 2-color overlay from actual album image on device, clamp brightness/contrast, fall back to fixed dark-green gradient. Never use an artwork gradient under essential text without scrim.
- Motion: 150–250ms transitions for chips/progress/card state; 250–350ms screen entrance where appropriate; no perpetual pulsing/rotating decorations. Honor Android reduced-motion/animator scale and power saver. Haptics only on explicit successful user actions, not background sync.
- AMOLED: avoid large white surfaces; avoid overusing saturated green text; ensure status bar/nav bar icon contrast and edge-to-edge insets.

### 2.3 Shared UI states

| State | Visual | Behavior |
|---|---|---|
| `Loading` | Skeleton rows / progress indicator | No fabricated values; keep navigation available |
| `Empty` | Music illustration/icon + explanation + CTA | State-specific CTA; no dead ends |
| `Content` | Real Room-backed values | Last sync and coverage visible where relevant |
| `OfflineCached` | Amber info strip + timestamp | Local browsing works; disable remote writes, allow queued retry |
| `PartialCoverage` | Warning strip `Lịch sử có thể chưa đầy đủ` | Counts are observed, never claim complete listening history |
| `AuthRequired` | Reconnect action | Never silently switch accounts |
| `SyncInProgress` | Determinate only if meaningful; otherwise spinner | Disable duplicate manual sync requests |
| `RemotePending` | `Đang thêm` or `Đang xác minh` | Do not show `Đã thêm` before remote confirmation |
| `Error` | Specific human-readable explanation + retry/details | Keep existing cached data intact |

All content surfaces must have accessibility semantics, TalkBack labels, and no essential gesture-only action.

## 3. Screen UI-00 — Onboarding & Spotify connection

**Purpose:** explain product, permissions, Premium and where playlist lives; complete secure connection. **Entry:** first install, disconnected state, invalid account.

**Layout top→bottom:** brand lockup `replay.`; hero tagline `Nhạc bạn nghe lại. Playlist tự tạo.`; short three-step illustration (Spotify plays → app counts → playlist saved on Spotify); compact rule preview `3 lượt/ngày HOẶC 5 lượt/72 giờ`; privacy note `Lịch sử lưu trên điện thoại`; Spotify connection button; link `Quyền truy cập & cách hoạt động`.

**Actions:** `Kết nối Spotify` opens system browser/Custom Tab; `Thử lại` on error; `Tiếp tục` only after account validation. Playlist setup bottom sheet: create private `My Replays — AutoPlaylist` (default), or link owned playlist only if API/ownership verification supports it. Show confirmation before creation; never create on every login.

**States:** idle, browser launching, callback waiting, token exchange, verifying `GET /me`, wrong account, insufficient scope, Premium/API access failure, playlist setup, ready. Spinner must not conceal a failure indefinitely; provide cancel/retry. Account mismatch must block sync and playlist writes.

**Copy:** `Đăng nhập trên trang Spotify. Ứng dụng không nhận mật khẩu của bạn.` / `Playlist được lưu trực tiếp trong thư viện Spotify.`

## 4. Screen UI-01 — Home / Music Dashboard

**Purpose:** show collection value, near-qualification, recent activity and sync truth at a glance.

**Visual hierarchy:**

1. Top bar: brand `replay.` + short subtitle; right gear opens Settings. Small account avatar only if permitted profile image available.
2. Status strip: `Đã đồng bộ lúc HH:mm` / `Đang đồng bộ…` / `Cần đăng nhập lại` / `Có thể thiếu lịch sử`. Tap opens Sync Health.
3. `PlaylistHero`: dark gradient + actual album collage if artwork exists; title `Your Repeat Universe`; count of **remotely confirmed playlist items**; CTA `Mở trong Spotify`; secondary `Đồng bộ ngay` (shows spinner and disables duplicate taps). If no playlist yet: setup CTA, not fake count.
4. `Almost There` section: up to 3 unique tracks **not remotely confirmed**, sorted by closeness to either threshold then most recent play; row includes cover, name, artist, `2/3 hôm nay` and/or `4/5 trong 72 giờ`, accessible progress. If none: `Chưa có bài nào gần đạt ngưỡng`.
5. `Newly Collected`: up to 5 latest **remote confirmed** additions, with real artwork and added timestamp. If empty: helpful empty state.
6. `Listening Pulse`: 7-day bars from **locally recorded play events**, grouped by VN calendar day. Axis labels and exact counts on tap; label `Lượt phát được Spotify ghi nhận`, not minutes listened. If coverage partial, annotate chart as partial. No chart when no events; show empty explanation.
7. Four-tab bottom bar: Home / History / Collection / Rules.

**Metric definitions:** `Collected` counts unique managed playlist track IDs verified remotely, not queued entries. `Almost There` excludes already-present remote tracks. Chart counts deduplicated play events, not distinct songs.

**Interactions:** tap track → Track Detail; tap chart bar → accessible tooltip/day count; tap warning → Settings/Sync Health; manual sync invokes existing sync use case (no new API pathway).

## 5. Screen UI-02 — Listening History

**Purpose:** inspect what Spotify reported and why a track is/has not been collected.

**Layout:** title `Listening History`; search by track/artist; segmented time filter `Hôm nay | 72 giờ | Tất cả`; secondary status chips `Tất cả | Gần đạt | Đã thêm`; count summary with coverage notice; vertical artwork rows. Each row shows title, artist, `Hôm nay N` and `72 giờ M` plus status chip. Search and filter operate on Room data only; no API calls per keystroke.

**Sort:** default latest observed play descending. Search is case-insensitive; if track is repeated in events, list it once per Spotify Track ID. History detail page: album art, song/artist, daily and rolling counters, qualifying rule (if any), chronological event timestamps in VN time, Spotify link if known, and explanatory disclaimer `Lượt phát Spotify ghi nhận không đồng nghĩa nghe hết bài`.

**Status priority:** `Đã thêm` (remote confirmed) > `Đang xác minh` > `Đang thêm` > `Đủ điều kiện` > `Gần đạt` > `Đang theo dõi`; show `Dữ liệu có thể thiếu` independently if coverage is partial. A track with one fewer play than either threshold qualifies as `Gần đạt` (presentation only; no effect on rule engine).

**Empty:** first sync `Chưa có lịch sử. Hãy nghe nhạc trên Spotify rồi đồng bộ.`; search no result `Không tìm thấy bài hát`; offline shows cached data.

## 6. Screen UI-03 — Collection / Real Spotify playlist

**Purpose:** make the actual Spotify playlist the star, and distinguish local intent from remote confirmation.

**Layout:** hero with playlist title, cover/collage if available, count `N bài trên Spotify`, CTA `Nghe trên Spotify`; below: segmented `Đã thêm | Đang chờ | Cần kiểm tra`; search and sort `Mới thêm | Tên bài`. Each confirmed row shows artwork, song/artist, `Đã thêm DD/MM`, and `Vì 3 lượt/ngày` / `Vì 5 lượt/72 giờ` / `Cả hai` if recorded. Pending rows show last attempt and retry status; ambiguous write shows `Đang xác minh trên Spotify`.

**Data source:** remote playlist membership is authoritative; Room cache displays last verified state with verification timestamp. Never say `Đã thêm` based solely on local outbox. If user manually removes a track from Spotify, next reconciliation must reflect remote state; do not silently re-add without explicit documented policy in business rules. MVP does not delete or reorder remote items.

**Empty:** no confirmed songs: `Playlist đang chờ những bài bạn nghe nhiều lần.`; pending only: `Đang chờ Spotify xác nhận`; playlist missing: recovery action `Liên kết hoặc tạo playlist mới` with confirmation.

## 7. Screen UI-04 — Rules Studio

**Purpose:** make the two-rule OR logic comprehensible and adjustable without technical jargon.

**Layout:** title `Rules Studio`; intro `Một bài chỉ cần đạt một điều kiện`; two large cards: `Trong một ngày` numeric stepper default **3** range **2..20** and `Trong 72 giờ` numeric stepper default **5** range **2..30**. Each card has explanatory example. Between cards prominent `HOẶC`. Read-only notes: `Ngày tính theo giờ Việt Nam` and `72 giờ là khoảng trượt`.

**Preview module:** shows 2–3 **real local tracks** closest to current thresholds with progress bars; when editing, compute preview in memory without mutating database. If no local tracks, show clearly labeled hypothetical sample only in a separate `Ví dụ minh họa` panel; no fake counts presented as user's history.

**Actions:** `Lưu quy tắc` enabled only when values changed and valid; `Khôi phục mặc định` resets editor to 3/5 but still requires Save; back with unsaved changes prompts `Bỏ thay đổi?`. Save writes DataStore transactionally, invokes rule re-evaluation on existing local events and updates candidates/outbox; existing playlist entries remain. Show `Đã lưu. Bài trong playlist không bị xóa.`

**MVP scope:** fixed timezone and 72-hour window; no rule scheduling, AI, or per-artist exceptions. Auto sync toggle lives in Settings, not Rules.

## 8. Screen UI-05 — Settings & Sync Health

**Purpose:** transparency, recovery and account safety, not decoration.

**Groups:**

1. **Spotify account:** connected display name and masked account ID, Premium/API access state only if verified, `Kết nối lại`, `Ngắt kết nối` with explicit local-data retention choice. Warn that changing account never migrates playlist/history.
2. **Managed playlist:** name, ownership check, last remote verification, `Mở trong Spotify`, `Liên kết lại` or `Tạo mới` only when appropriate and confirmed.
3. **Synchronization:** `Tự động đồng bộ` toggle; description `Khoảng 30 phút; Android có thể trì hoãn`; `Đồng bộ ngay`; last attempt/success, events imported, pages fetched, coverage status, next retry if known. Never promise a precise next run time.
4. **Diagnostics:** collapsible technical details: last sanitized HTTP code, error category, quota/backoff, worker outcome, build version; `Sao chép chẩn đoán` must redact account identifiers, tokens, URLs containing authorization codes, playlist IDs if sensitive.
5. **About & privacy:** app version, local storage explanation, no affiliation disclaimer.

**States:** offline, 401 refresh needed, 403 account/API access unavailable, 429 retry, `QUOTA_EXCEEDED`, missing playlist, partial history, account mismatch, unknown. Each state has a user action or clear non-action explanation.

**Disconnect:** confirmation sheet `Ngắt kết nối Spotify?`; separate choice `Giữ lịch sử trên máy` vs `Xóa dữ liệu tài khoản khỏi máy` (destructive option requires second confirmation). Stop workers and clear credentials. Do not delete Spotify playlist remotely.

## 9. Cross-screen copy / language rules

| Internal state | User-facing Vietnamese |
|---|---|
| Authenticated | `Đã kết nối Spotify` |
| Sync running | `Đang đồng bộ lịch sử…` |
| Remote confirmed | `Đã thêm vào Spotify` |
| Qualified, pending | `Đủ điều kiện · Chờ thêm` |
| Ambiguous POST | `Đang xác minh kết quả thêm bài` |
| Partial history | `Lịch sử có thể chưa đầy đủ` |
| Rate limited | `Spotify đang giới hạn yêu cầu. Sẽ thử lại.` |
| Quota exhausted | `Đã hết hạn mức Spotify API. Tạm dừng đồng bộ.` |
| Account mismatch | `Tài khoản Spotify không khớp với tài khoản đã thiết lập.` |
| No events | `Chưa có lượt phát được ghi nhận.` |

Never display `Đang nghe`, `Nghe trọn bài`, `Đã thêm` or `Đồng bộ đầy đủ` unless backed by the specific verified source state.

## 10. Compose architecture & data contracts

- Single-activity Compose app, Navigation Compose, unidirectional data flow: `ViewModel` exposes immutable `StateFlow<ScreenUiState>`; UI sends intents, ViewModel calls use cases/repositories. UI must not call Retrofit or Room DAO directly.
- Screen states include `isLoading`, `isSyncing`, `error`, `coverage`, `lastSyncAt`, and domain DTOs; use sealed states where transitions are mutually exclusive.
- Shared `TrackUiModel`: `trackId`, `name`, `artists`, `artworkUrl?`, `todayCount`, `rolling72hCount`, `qualificationReason?`, `playlistStatus`, `lastPlayedAt?`, `lastVerifiedAt?`. Never treat nullable remote data as verified.
- `HomeUiState`: `playlistName?`, `confirmedCount?`, `nearThresholdTracks`, `newlyCollected`, `dailyPlayBuckets`, `syncSummary`, `coverage`. `confirmedCount=null` means unknown, not zero.
- `HistoryUiState`: `query`, `timeFilter`, `statusFilter`, `tracks`, `coverage`, `isLoading`. `CollectionUiState`: `remoteVerificationAt?`, `confirmed`, `pending`, `needsReview`, `playlistUrl?`.
- `RulesUiState`: `persistedDaily`, `persistedRolling`, `draftDaily`, `draftRolling`, `isSaving`, `preview`, `error`. `SettingsUiState`: connection and diagnostic models with sanitized values.
- Format timestamps using `Asia/Ho_Chi_Minh`; expose UTC epoch in domain layer. Use locale `vi-VN` for UI. No time-zone or counting logic inside Composables.
- Analytics chart derives 7 local-day buckets from deduplicated events; if incomplete, show explicit coverage warning. Charts require TalkBack textual summaries.
- Persist user choices in DataStore; no separate backend. Artwork cache must have bounded memory/disk usage and no unnecessary persistent personal-data export.

## 11. Accessibility, responsive and performance requirements

- 360dp and larger layouts supported; 320dp should remain navigable with wrapping/scrolling. Test landscape and Android display/font size adjustments.
- Touch targets ≥48dp; meaningful content descriptions, selected states and progress semantics for TalkBack; progress text e.g. `Hai trên ba lượt hôm nay`.
- Dynamic color is **disabled** by default to preserve dark Replay identity; user system high-contrast and reduced motion still honored.
- Screen content should render from cached Room data without waiting for network; loading only for truly unavailable fields. No synchronous artwork decoding on main thread.
- 7-day chart and lists should not trigger network per row; paginate/virtualize long lists with `LazyColumn`. Do not run continuous animations in background.
- Offline first-run must show onboarding/API requirement; offline returning user can inspect cached history.

## 12. UI test matrix / acceptance

| ID | Screen | Scenario | Expected |
|---|---|---|---|
| UI-TC-001 | Onboarding | First launch | Rules, privacy, login CTA and playlist destination clearly explained |
| UI-TC-002 | Onboarding | OAuth denied / wrong account | Clear error and retry; no data cross-contamination |
| UI-TC-003 | Home | Zero history and no playlist items | Honest empty state, no invented counts or artwork |
| UI-TC-004 | Home | 2/3 daily; 4/5 rolling | Accurate progress and `Almost There` |
| UI-TC-005 | Home | Partial coverage | Warning visible alongside all relevant metrics/chart |
| UI-TC-006 | Home | Tap sync twice | One in-flight sync; feedback and truthful completion |
| UI-TC-007 | History | Search/filter + back | Correct local results, state restored |
| UI-TC-008 | History | Track detail | Accurate local timestamps and disclaimer |
| UI-TC-009 | Collection | Remote write pending/ambiguous | No premature `Đã thêm` |
| UI-TC-010 | Collection | Open Spotify unavailable | Browser fallback or actionable error |
| UI-TC-011 | Rules | Change 3→2 then save | Preview updates, persisted rule reevaluated, remote entries retained |
| UI-TC-012 | Rules | Unsaved edits + back | Confirmation, no silent save |
| UI-TC-013 | Settings | Offline, 401, 429, quota | Correct distinct messaging and recovery |
| UI-TC-014 | Settings | Disconnect / switch account | Confirmation, credentials cleared, account isolation preserved |
| UI-TC-015 | Global | TalkBack / font 1.3x / narrow screen | No clipped primary actions; progress spoken |
| UI-TC-016 | Global | Reduce motion / power saver | No essential information lost, decorative motion suppressed |
| UI-TC-017 | Collection | Manual Spotify deletion discovered | Remote membership reflected; no fabricated confirmed state |
| UI-TC-018 | Settings | Diagnostics copied | Tokens, auth codes and sensitive identifiers redacted |

**Design Definition of Done:** every route implements content/loading/empty/offline/error as applicable; navigation and back work; screen-reader labels and touch targets pass; no mock content in production; screenshot review on OnePlus Ace 5; UI-TC-001..018 pass; no new Spotify API scopes introduced solely for visual polish.

## 13. Scope boundaries and implementation sequencing

**Must for MVP:** five experiences, playlist hero, progress, local history, collection confirmation, editable rules, truthful sync diagnostics, coherent dark theme. **Nice-to-have if time permits:** dynamic artwork gradient, subtle animation, seven-day chart (chart may ship as a simple accessible bar chart). **Not in MVP:** live player, playback control, AI recommendations, push notifications, music previews, AOD, home-screen widget, cross-device live sync, complex analytics.

Suggested order: design tokens/components → onboarding + Home states → History/detail → Collection → Rules → Settings/diagnostics → animation/polish → accessibility and device QA. Never block API integration gates on visual polish.

## 14. Traceability

- Product: `FR-001`, `FR-002`, `FR-007`, `FR-010`..`FR-014`; `NFR-006`, `NFR-009`.
- Business: `BR-001`..`BR-009`, account isolation `AUTH-006`.
- Engineering: `03-ARCHITECTURE.md` state machine, `05-DATABASE.md` remote verification/outbox.
- Testing: `07-TEST-PLAN.md` TC-001..034 and `UI-TC-001..018` herein.
- Build: `08-IMPLEMENTATION.md` M5/TASK-050 and UI QA tasks.
