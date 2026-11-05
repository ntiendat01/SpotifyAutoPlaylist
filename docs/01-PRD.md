# 01 — Product Requirements Document

Version 1.1.0 · Status: Baseline · 2026-11-04

## 1. Problem and outcome
Spotify recommendations do not directly implement the user's personal repeat-listening thresholds. Manually curating playlists is inconvenient. The application detects repeat plays and automatically maintains a real Spotify playlist accessible from **Spotify → Your Library → Playlists** on any device signed into the same account.

## 2. User / platform
- Single personal user with Spotify Premium, registered as an allowed Development Mode user. For MVP the **new Premium Spotify account** must both own the Spotify Developer App and be the listening/login account; the previous account is not used for API operations.
- Primary device: OnePlus Ace 5 / OxygenOS 16; Android API target/minimum selected and recorded in implementation (minimum recommended API 29 subject to build compatibility).
- Development: macOS, VS Code, GitHub Actions, no Android Studio requirement.
- UI language: Vietnamese; time semantics `Asia/Ho_Chi_Minh`.

## 3. Functional requirements
| ID | Priority | Requirement | Acceptance |
|---|---|---|---|
| FR-001 | P0 | Spotify OAuth PKCE connect/disconnect | Connect, refresh and revoke locally without embedded secret |
| FR-002 | P0 | Create private playlist `My Replays — AutoPlaylist` or link an existing owned playlist | Actual Spotify playlist ID persisted; openable in Spotify |
| FR-003 | P0 | Read Recently Played and persist supported track events | Restart/retry does not inflate counts |
| FR-004 | P0 | Qualify with BR-001/BR-002 | Unit tests at boundaries pass |
| FR-005 | P0 | Queue qualified tracks, reconcile, append to Spotify playlist | No app-caused duplicate on normal retry |
| FR-006 | P0 | Never automatically remove playlist items | Existing remote entries remain unchanged |
| FR-007 | P0 | Manual Sync Now and status display | Outcome and time visible |
| FR-008 | P0 | Best-effort periodic sync | Worker configured; deferred execution reported honestly |
| FR-009 | P0 | Persist data across process death/reboot | No lost committed events or write intentions |
| FR-010 | P1 | Dashboard, history and candidate statuses | Empty/loading/error and partial-data states |
| FR-011 | P1 | User-adjustable thresholds and sync enable toggle | Re-evaluation of stored history; no deletion |
| FR-012 | P1 | Diagnostics without tokens/PII | Safe last-run logs and gap warnings |
| FR-013 | P1 | Open managed playlist via Spotify URL | Works even when local app has no playback features |
| FR-014 | P0 | Verify account identity and prevent cross-account reuse (AUTH-006) | OAuth `GET /me` ID equals configured developer-owner account; mismatches stop sync and playlist writes |

## 4. Non-functional requirements
| ID | Requirement |
|---|---|
| NFR-001 | No root, AccessibilityService, notification listener, or overlay permission required |
| NFR-002 | No backend; local Room DB; tokens in encrypted app-private storage backed by Android Keystore |
| NFR-003 | No guaranteed real-time behavior: WorkManager periodic execution is best effort |
| NFR-004 | Network calls timeout, retry transient failures, honor Retry-After and quota limits |
| NFR-005 | No secrets in Git, GitHub Actions logs, crash logs or APK source constants |
| NFR-006 | Accessibility: labels, scalable text, high contrast, clear status descriptions |
| NFR-007 | Application should work offline for history viewing; playlist writes require network |
| NFR-008 | Account isolation: never combine events or playlists across Spotify user IDs |
| NFR-009 | No fabricated listening history, fake counts or implied guaranteed completeness |

## 5. User stories
- US-001: As the user, I connect Spotify once so the app can manage my playlist.
- US-002: As the user, when I replay a song 3 times in a Vietnam calendar day, it is added automatically after a successful sync.
- US-003: As the user, when I replay a song 5 times within 72 hours, it is added even without 3 plays in any one day.
- US-004: As the user, a song remains in my Spotify playlist after it stops qualifying or after I uninstall the app.
- US-005: As the user, I can see the last successful sync and whether history is incomplete.
- US-006: As the user, I can choose Sync Now to catch up after background execution is delayed.
- US-007: As the user, I connect my newly subscribed Premium account and keep its history and Spotify playlist separate from my previous account.

## 6. Out of scope
- AI recommendations; audio streaming, playback control, downloaded audio, AOD and lock-screen customization.
- Listening-history recovery beyond what Spotify returns; guarantee of capturing every play on every device.
- Duplicate matching by song title/ISRC across different Spotify releases.
- Multi-account support, public playlist publishing, cloud hosting, push notifications.
- Automatic removal, playlist reorder, external user sharing.

## 7. Success criteria / Definition of MVP
1. Real account integration gates succeed on device.
2. Rules evaluate accurately on fixed fixtures and pass TC-001 through TC-024 (see test plan).
3. A qualifying track is added to the real Spotify playlist; no unintended second copy during tested retries.
4. Playlist survives app removal, since it is Spotify-owned; app does not remove tracks.
5. Offline and token expiry recover without corrupting local data.
6. Signed installable APK is produced by GitHub Actions and manually installed on OnePlus.

## 8. Risks and acceptance of limitations
- Spotify Recently Played returns **at most 50 per request** and has an API-limited accessible history. Some gaps cannot be recovered; show `HISTORY_INCOMPLETE` when evidence is insufficient.
- The endpoint reports play records, not full listen duration. Rule naming in UI must say `lượt phát được Spotify ghi nhận`.
- Android Doze/OxygenOS may delay periodic workers; manual sync is fallback.
- Development Mode permissions and quotas can change; fail gracefully and stop after persistent quota errors.
- Local DB loss or app reinstall loses the application's counting history, while Spotify playlist remains.
- A different Spotify account has a different music library and play history; no migration from the old Spotify account is part of MVP.

## 9. Requirement traceability
FR-003 → BR-003/004 → TC-005/006/010/011. FR-004 → BR-001/002/005 → TC-001..004/007..009. FR-005/006 → BR-006..009 → TC-012..017. FR-001 → TC-018..020. FR-008 → TC-021..024.

## UI baseline v1.2 (FR-010/011/012/013)
- Adopt Spotify Neo × Replay Lab design system as defined in `06-UI-UX.md`.
- Five main experiences: Home, Listening History, Collection, Rules Studio, Settings & Sync Health; onboarding is a separate route.
- Collection must distinguish remotely confirmed playlist membership from queued/ambiguous local writes.
- Home shows truthful progress and a 7-day play-event chart only when supported by local data; incomplete coverage is clearly labeled.
- UI polish does not introduce additional Spotify scopes, playback features or server dependencies.
