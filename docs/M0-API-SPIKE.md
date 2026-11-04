# TASK-004 — Recently Played / private playlist smoke test

Scope: controlled manual API gate on OnePlus. No rules engine, history database, worker or finished UI.
The user has reported OAuth + `/me` 200 in TASK-002A; wrong-account/device recovery cases remain
separate manual evidence. No API gate is marked passed without actual-device observations.

## Binding smoke-test behavior

- Same three scopes; OAuth and exact expected `/me.id` match remain mandatory before any other API.
- TASK-004 additionally requires `/me.account_id` for the account-scoped operation journal. Namespace is
  SHA-256 of Client ID + immutable account ID, never a fallback from `account_id` to username.
  This is a narrow operation journal, not the M2 account/history database; M2 schema reconciliation remains required.
- Keep the verified access token in memory for this manual session only; never in UiState, disk or logs.
  Close it on end/cancel/configuration change, ViewModel destruction, expiry or 401. No refresh yet.
- Recently Played fetches **one newest page**, limit 50, validates supported track IDs/timestamps, and
  displays aggregate counts only. No history persistence, completeness claim, artwork or listening-duration inference.
- User confirms playlist creation. Before POST, read all accessible own-playlist pages to find the stored
  marker or detect earlier replay smoke playlists. Persist creation intent **before** POST.
  Create `My Replays — AutoPlaylist` with `public:false`, `collaborative:false`, unique description marker.
- Persist returned playlist ID before readback. Verify owned/private/non-collaborative metadata via
  `GET /playlists/{id}` before use. If create outcome is unknown, rediscover the marker; if not found or
  ambiguous, require manual link/review, never blindly repeat create. After reinstall/another marker,
  link the existing owned private playlist explicitly rather than automatically create another.
- Manual link accepts only a validated playlist ID/Spotify URL and requires confirmation. No silent
  switch away from an already bound playlist; no remote deletion or privacy conversion.
- User pastes one track ID/URI/Spotify URL and confirms adding **one test track**. Freeze that track in
  the journal once tracked; changing/resetting it is outside this smoke test.
- Read **all** playlist item pages before adding, using `items[].item` (not deprecated `track`). Validate
  progress/total/next origin and path; compare playlist snapshots before/after traversal. Incomplete,
  changing, unknown/null track data or page-cap exhaustion blocks POST. Default budget: 20 pages/run.
- If present, record remotely confirmed membership and do not POST. Before POST, durably write
  `VERIFY_REMOTE`. After **every** POST, including 201, read back membership before reporting Added.
- A lost response/5xx may be reconciled by GET; a failed verification stays unresolved. A user retry
  always starts with remote reconciliation and only sends if definitely absent. 401/403/429 stop the
  current request chain; respect Retry-After, no automatic retry. Quota-exceeded closes the session.
- Once confirmed, a later remote absence becomes `REMOVED_EXTERNALLY`; no re-add even after another
  tap. No reset, removal, reorder, rule qualification or automatic sync in TASK-004.
- Serialize all operations. Cancellation/process death cannot erase a previously persisted write intent.
  A cancelled write may have completed remotely: reauthenticate and reconcile before doing anything else.
- Journal lives in app-private **no-backup** storage, with atomic file replacement and fsync before
  remote writes. Stores only marker, playlist ID, one explicitly selected track ID and recovery state.
  Corruption/storage failure blocks writes; never silently reset. No tokens, account IDs or listening events on disk.

Residual limit: Spotify has no guaranteed idempotency key. Snapshot checks and one writer cannot
eliminate a race with another client after reconciliation. No absolute exactly-once guarantee is claimed.
The writer lock is per immutable account within this app process; journal namespaces additionally
separate Client IDs. Reinstall loses old write/removal tracking: inspect/link the existing playlist and
do not treat a new journal as recovery of old removal history. A deleted bound playlist or corrupt
journal stops the probe for manual review; full reset/recreation/account migration belongs to later milestones.

## Automated acceptance

Use dummy-only MockWebServer and fake journals/sessions. Cover account mismatch/missing account_id,
namespace isolation, minimal Recent shape/filtering, empty history, private create payload and 201 readback,
create intent/lost response recovery/no duplicate create, owner/privacy rejection, explicit manual link,
multiple item pages, hostile/repeated/truncated cursors, snapshot change, already-present no-POST,
201 without membership, lost add response followed by remote presence, durable intent before send,
restart recovery, storage failure/no POST, external removal/no re-add, invalid track/locked test track,
cancellation, double tap, expired token, 401/403/429/quota stop, redacted results/diagnostics.
These are smoke-level subsets of TC-012..016, TC-025, TC-027..028, TC-031..033, not full M4 acceptance.

Observed local evidence: `./gradlew --offline --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
completed with `BUILD SUCCESSFUL`. **84 JVM tests** passed (61 auth-core + 23 ViewModel), zero failures,
errors or skips. Lint passed; dependency/target upgrade notices remain informational as already documented.
APK versionCode 3 / `0.3.0-api-spike`; debug signature verified with `apksigner`.
Local APK SHA-256: `7348ecbbc8b92dc0636b5361218b315c3a017942ca5980f303837ca710c93d6a`.
Remote CI evidence belongs to the TASK-004 commit's GitHub Actions run; live Spotify checks remain pending.

## Device checklist (pending)

1. Install current debug APK; enter Client ID and expected owner user ID; connect.
2. Confirm `/me` identity and required immutable ID, then read Recent. Record status/counts, not event history.
3. Confirm private playlist creation (or explicitly link an owned private playlist). Verify it in Spotify Library.
4. Copy a Spotify **track** link, paste into the test field, confirm adding, wait for remote verification.
5. Repeat: no second copy. Close/reopen/reconnect and reconcile saved intent.
6. Exercise timeout/cancel/recovery, private ownership mismatch, offline, 403/429 where feasible.
7. If a confirmed track is manually removed in Spotify, reconciliation must show external removal, never re-add.

Record commit/APK checksum, Android/OxygenOS version, sanitized endpoint statuses, expected/actual
and PASS/FAIL. Tokens, callback queries, raw profile/history/playlist IDs stay out of shared evidence.
Uninstall removes the local journal; real Spotify playlists survive. Link an existing smoke playlist after reinstall.

Visual scope: native diagnostic controls and confirmation dialogs using existing Replay tokens;
`01-playlist-setup.webp` inspected, exact original PNG absent. No reference background, sample art,
fake metrics, bottom tabs or automatic-rule claims. OnePlus screenshot/accessibility checks remain pending.

## Official API contracts checked

- [Recently Played](https://developer.spotify.com/documentation/web-api/reference/get-recently-played)
- [Create playlist](https://developer.spotify.com/documentation/web-api/reference/create-playlist)
- [Current user's playlists](https://developer.spotify.com/documentation/web-api/reference/get-a-list-of-current-users-playlists)
- [Playlist metadata](https://developer.spotify.com/documentation/web-api/reference/get-playlist)
- [Playlist items](https://developer.spotify.com/documentation/web-api/reference/get-playlists-items)
- [Add items](https://developer.spotify.com/documentation/web-api/reference/add-items-to-playlist)
