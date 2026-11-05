# M5 UI — TASK-050C..050I implementation log

Status vocabulary: **PASS (JVM)** = JVM + Robolectric tests and the full local CI-equivalent build
(`bash /workspace/tools/verify.sh`: tests, lint, debug and release assemble) passed on the box.
Nothing in this file was run against live Spotify, on a device or in GitHub CI.

## TASK-050C — Home dashboard

**Status: PASS (JVM), 2026-11-05.** 365 tests total (+14).

Files
- `app/.../library/LibraryModel.kt` — pure read model (`LibraryRows` → `LibrarySnapshot`): per-track Vietnam-day
  and rolling-72h counts, status by 05 priority, confirmed count, coverage, 7-day buckets, Almost There
  and Newly Collected selection, Vietnam-time formatters, `SyncCopy` (outcome code → 06 §9 copy).
- `app/.../library/LibraryViewModel.kt` — `LibrarySource` / `RoomLibrarySource` (signed-in account only) and
  `LibraryViewModel` (`Loading | NoAccount | Error | Ready`); reloads when the persisted sync status or the
  account's rules change.
- `data/.../local/Daos.kt` — two read-only queries (`HistoryDao.tracks`, `PlaylistDao.outboxAll`). **No schema change.**
- `app/.../ui/HomeScreen.kt` — `ReplayPage`, `LibraryGate`, `SyncStrip`, `CoverageNotice`, `HomeScreen`, `ListeningPulse`.
- `ReplayComponents.kt` — `TrackRow` click optional + extra slot; `PlaylistHero` footer slot.
- `ReplayFoundation.kt`, `MainActivity.kt`, `AppContainer.kt` — Home tab uses real data when READY; preview unchanged.
- Tests: `LibraryModelTest` (9, JVM), `HomeScreenTest` (5, Robolectric), synthetic `LibraryFixtures`.

State handling
- Hero count = PRESENT rows on the *current* playlist; `null` ("Chưa xác minh số bài trên Spotify") when no
  playlist or no successful pass and nothing PRESENT. Never derived from local qualification.
- "Kiểm tra playlist trên Spotify lần cuối" = last SUCCESS pass time (a SUCCESS pass reconciles the whole
  remote playlist, TASK-041); there is no per-row verification column.
- Almost There: unqualified, not in outbox, ≥1 play in 72h; closest to either threshold first, max 3; both
  `x/3 hôm nay` and `y/5 trong 72 giờ` shown as text + bar.
- Newly Collected: PRESENT only, latest remote `added_at` first, max 5.
- Listening Pulse: 7 Vietnam days, deduplicated events, exact count above each bar, per-bar
  contentDescription (`T5 05/11: 15 lượt`), "Lượt phát, không phải số phút nghe", partial/unknown coverage note.
- Loading, no account, error with retry, offline (strip warning, cached content kept), empty sections.

Deviations / limitations
- No Sync Now on Home; the status strip opens Settings (Sync Now lands there in 050G).
- No artwork collage / album images: no image-loading dependency (dependencies locked) → neutral placeholder.
- Progress shown as linear bars, not rings.
- Track rows are not tappable until Track Detail exists (050D).
- After a relink, outbox rows of the old playlist are ignored, so a track PRESENT only on the old
  playlist reads as "Đủ điều kiện" until re-added.
- Snapshot time is taken at load; the Vietnam day rolls over on the next reload, not live.
- Screenshots: `app/build/reports/ui/050C-home*.png` (Robolectric render, not a device).

## TASK-050D — History, search/filter and Track Detail

**Status: PASS (JVM), 2026-11-05.** 373 tests total (+8). Build 43 s.

Files
- `LibraryModel.kt` — `TimeFilter` (Hôm nay | 72 giờ | Tất cả), `StatusFilter` (Tất cả | Gần đạt | Đã thêm),
  `history()` (Room snapshot only, one row per track, latest play first, case- and accent-insensitive search
  on title/artist incl. `đ`), `plays()` summary.
- `ui/HistoryScreen.kt` — `ChoiceChips`, `HistoryScreen`, `TrackDetailScreen`.
- `ReplayFoundation.kt` — History tab on real data; `track/{id}` route (back arrow, no bottom bar) from Home and History.
- `MainActivity.kt` — `onOpenUrl` opens validated `open.spotify.com/track/…` links (error dialog if nothing handles it).
- Tests: `HistoryFilterTest` (4, JVM), `HistoryScreenTest` (4, Robolectric).

State handling
- Query and filters are `rememberSaveable` inside the tab destination; Navigation saves them across detail/back and tab switches.
- "Đã thêm" filter shows PRESENT only. Coverage notice shown when not complete. Empty history, no match, unknown track id.
- Detail: daily/rolling counters vs current thresholds, qualifying rule + time, remote added time (PRESENT only),
  up to 50 newest play timestamps in Vietnam time ("và N lượt cũ hơn"), disclaimer
  "Lượt phát Spotify ghi nhận không đồng nghĩa nghe hết bài".

Deviations / limitations
- Filter chips (FilterChip) instead of segmented buttons; neutral artwork placeholder.
- No pagination: the whole account's history is loaded into memory per refresh (fine for a personal app; unmeasured on device).
- Screenshots: `app/build/reports/ui/050D-history.png`, `050D-track-detail.png`.

## TASK-050E — Collection

**Status: PASS (JVM), 2026-11-05.** 377 tests total (+4). Build 51 s.

Files
- `LibraryModel.kt` — `Segment` (Đã thêm | Đang chờ | Cần kiểm tra), `collection()`, `reason()`; `TrackUiModel.errorCode`.
- `ui/CollectionScreen.kt`; `ReplayFoundation.kt` routes the Collection tab to it (rows open Track Detail).
- Tests: `CollectionModelTest` (2, JVM), `CollectionScreenTest` (2, Robolectric).

State handling
- Đã thêm = PRESENT on the current playlist only, with remote added time. Đang chờ = PENDING / SENDING /
  RETRY_LATER / CHECKING_REMOTE / VERIFY_REMOTE and qualified-without-outbox. Cần kiểm tra = BLOCKED and
  REMOVED_EXTERNALLY. Segments are disjoint (tested). Counts shown in each chip.
- Header "Xác minh trên Spotify lần cuối" = last SUCCESS pass (full remote reconcile); "Chưa kiểm tra playlist trên Spotify" otherwise.
- Reasons are fixed Vietnamese copy from state/attempts/known codes (`ATTEMPTS_EXHAUSTED`); no raw error text.
- Empty copy per segment; loading/error/no-account via `LibraryGate`.

Deviations / limitations
- No per-row "verified at" (no column; no schema change made). No artwork collage, no weekly analytics chart.
- No "retry now" / "re-add" actions for review rows (no supported use case; would be scope expansion).
- Selected chip colour comes from the Material3 scheme (not Replay green).
- Screenshots: `app/build/reports/ui/050E-collection.png`, `050E-collection-pending.png`.

Rules Studio (`ui/RulesScreen.kt`) replaces the Rules placeholder.
- Draft steppers: daily 2..20 and rolling 2..30, "HOẶC" between them, explained in Vietnam-day / 72h terms. The steppers disable at their bounds, are 48dp, and have accessible labels; the values are in a live region.
- Real-data preview: `LibraryModel.rulesPreview` runs the same `QualificationEvaluator` over the stored events, so it uses any past day or window. It lists only additions; raising a threshold never removes anything.
- Save calls `QualificationRecorder.changeThresholds` through `AppContainer.saveRules`, then refreshes the library and shows "Đã lưu. Bài trong playlist không bị xóa." New tracks are added on the next sync.
- Also: discard, restore defaults (3/5, applied as a draft), a "Bỏ thay đổi?" dialog on back while a draft exists, and a sanitized error on save failure that keeps the draft.
- There is no auto-add or notification toggle, no Room schema change, and the business rules are unchanged.
- Foundation's BackHandler is now registered before the screen content, so a screen-level handler takes precedence.
- Tests: RulesPreviewTest (4), RulesScreenTest (4). Screenshot: 050F-rules. JVM/Robolectric only; nothing was run on a device or against live Spotify.

Settings / Sync Health (`ui/SettingsScreen.kt`) replaces the settings placeholder for the real app. The preview build keeps the placeholder.
- **Health card:** sync headline (offline and error copy come from SyncCopy), last success, blocked-until, last-run imported/qualified counts, and queue counts split strictly into Added (verified) / Pending / Verifying / Review. Shows a coverage notice when coverage is not complete.
- **Sync Now:** calls the real `SyncRepository.requestManualSync()` through `SettingsActions`. Since that call only enqueues, the screen shows "Đang chờ kết quả…" until the status flow reports a pass that finished after the request, then shows that result. Shows an error if there is no account or the request fails. It is disabled, with an explanation, when the build has no Spotify configuration.
- **Diagnostics:** `Diagnostics.text` contains only enum codes (anything that isn't `[A-Z_]` becomes OTHER), counts and times; no IDs, names, URLs or tokens. The text is selectable and can be copied to the clipboard.
- **Relink:** appears only when the status outcome or errorCode is RELINK_REQUIRED. You paste a link and tick a confirmation checkbox, which calls `SyncRepository.relinkPlaylist`, the TASK-052 serialized PlaylistRelinker path; every RelinkResult reason has Vietnamese copy. The app never creates a playlist.
- **Deliberately left out:** the last-run "added" count is not shown, because Added is only claimed from PRESENT rows. There is no auto-sync or notification toggle.
- No Room change.
- Tests: DiagnosticsTest (2), SettingsScreenTest (3). Screenshots: 050G-settings, 050G-sync-result, 050G-relink. JVM/Robolectric only; WorkManager enqueue and relink have not been run on a device or against live Spotify.
