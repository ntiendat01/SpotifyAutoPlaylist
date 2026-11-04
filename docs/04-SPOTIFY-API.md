# 04 — Spotify Web API & OAuth Contract

Version 1.1.0 · Status: **Implementation requires live API smoke tests**

## 1. Access assumptions
- **AUTH-006 (account ownership):** the newly subscribed Spotify Premium account is BOTH (a) owner of the Developer App/client ID created in Spotify Dashboard and (b) the Spotify listening account that signs in through OAuth. No old-account developer credentials or mixed-owner configuration.
- Spotify Premium developer account, Development Mode app, own account allowlisted as required. The owner must maintain Premium while Development Mode access is required; a different user's Premium does not substitute for the app owner's Premium.
- Development Mode may impose endpoint access and per-developer quota restrictions. An endpoint in docs is **not** proof of availability for this specific client ID.
- Register Spotify app via https://developer.spotify.com/dashboard; store `client_id` as build config (not secret); **never embed client_secret**.

### Account change and ownership contract (AUTH-006)
1. Create a **new Spotify Developer App** while signed in to the new Premium account; record its new `client_id` in local build configuration. Never reuse the previous account's client ID as a workaround.
2. On OAuth sign-in, fetch `GET /v1/me` and persist the returned Spotify user ID. Compare it to the expected developer-owner Spotify user ID verified during setup; if different, show **Wrong Spotify account**, withhold history sync and playlist writes, and require reconnect.
3. On account switch, require explicit user confirmation; stop background work, remove active credentials and account-specific sync pointers, and never attach old-account playlist IDs/events to the new identity. Existing playlists remain owned by their respective Spotify accounts.
4. No automatic migration of likes, history, playlists or counts from the old Spotify account. If a developer recreates a Spotify app, old `client_id`, refresh tokens, and registered redirects are not assumed portable.

## 2. OAuth Authorization Code + PKCE

TASK-002A's M0 identity probe uses a separately entered expected owner `id`, compares it exactly to
`GET /me.id`, and releases the memory-only token on match, mismatch, cancellation or error.
It cannot infer Developer App ownership or Premium from a successful `/me` response.
Current Spotify docs recommend immutable `account_id` for **persistent** account linking; reconcile
this with the database/account-isolation contract before M2. M0 performs no persistent linking and
does not silently substitute `account_id` for the configured user ID. See [M0-ACCOUNT-SPIKE.md](M0-ACCOUNT-SPIKE.md).

TASK-004 extends that baseline: verify `/me.id` exactly, additionally require immutable `account_id`
for a hashed account/client operation-journal namespace, and keep the token only for the current manual
session. The journal stores marker/playlist ID/one selected test-track ID/recovery state, never tokens
or history. See [M0-API-SPIKE.md](M0-API-SPIKE.md) for confirmation, creation recovery and readback contracts.

TASK-020 production identity decision: `/me.id` must equal the build-configured owner ID exactly
(AUTH-006), and `/me.account_id` is required and persisted with the grant as the immutable account key
for detecting account changes. Missing `account_id` rejects login. See [M2-AUTH.md](M2-AUTH.md).

- Authorization URL `https://accounts.spotify.com/authorize`.
- `response_type=code`, `client_id`, `redirect_uri`, `scope`, random `state`, `code_challenge_method=S256`, `code_challenge=BASE64URL(SHA256(verifier))`.
- Verifier: secure random, 43–128 chars. Check exact state match, one-time code, callback URI and timeout.
- Exchange at `POST https://accounts.spotify.com/api/token`, `application/x-www-form-urlencoded` with `grant_type=authorization_code`, `code`, `redirect_uri`, `client_id`, `code_verifier`.
- Refresh via `grant_type=refresh_token`, `refresh_token`, `client_id`; atomically replace rotated refresh token if returned. Avoid concurrent refresh stampede.

### Redirect decision — important
Spotify's current documented policy permits **HTTPS** redirects, with HTTP exception for **loopback IP literals**. `localhost` is not allowed. **Do not assume custom schemes such as `myapp://callback` are accepted.**

MVP spike: register exact `http://127.0.0.1:8765/callback` if accepted in dashboard; on Android start a temporary HTTP callback listener bound **only to 127.0.0.1** and fixed port while auth is in progress, open browser/custom tab, receive code and state, stop listener promptly. Reject non-GET, wrong path, wrong state; avoid logging query string. Handle port-in-use, app killed during auth and browser cancellation. Verify on OxygenOS 16 before proceeding. If loopback fails, switch only to a verified HTTPS Android App Link under a domain the developer controls and update design/test plan. **This is a gate, not a claim of proven compatibility.**

## 3. Minimal OAuth scopes
- `user-read-recently-played` — retrieve recent play records.
- `playlist-modify-private` — create/update private playlist.
- `playlist-read-private` — read private playlist contents where required for reconciliation (validate actual scope behavior in smoke test).
Avoid extra scopes by default. Test permission errors explicitly.

## 4. API endpoints
| ID | Method and path | Use | Key contract |
|---|---|---|---|
| API-01 | `GET /v1/me/player/recently-played?limit=50` | Fetch events | `items[].played_at`, `items[].track.id/uri`, `next`, `cursors`; max 50/page |
| API-02 | `POST /v1/me/playlists` | Create playlist | JSON `{ "name": "My Replays — AutoPlaylist", "public": false, "collaborative": false, "description": "Auto-collected repeat plays" }` |
| API-03 | `GET /v1/playlists/{id}/items` | Remote dedup | Paginate; inspect `items[].item.id` (new field) and `is_local` |
| API-04 | `POST /v1/playlists/{id}/items` | Append tracks | JSON `{ "uris": ["spotify:track:<id>"] }`; max 100/request; expect 201 with `snapshot_id` |
| API-05 | `GET /v1/me/playlists` | Discover owned playlist | Use if local playlist ID missing; paginate; verify owner |
| API-06 | `GET /v1/me` | Verify user identity | Confirm stable Spotify user ID; do not assume fields removed in 2026 exist |
| API-07 | `GET /v1/playlists/{id}` | Ownership/privacy/snapshot verification | Require owner.id match, public:false, collaborative:false and valid snapshot before writes |

**Use `/items`, not deprecated `/tracks`, for playlist item operations.** `GET /playlists/{id}/items` returns contents for playlists owned by or collaborative with the user; use only owned playlists in v1.

## 5. Request/response examples (illustrative; not real tokens)
```http
GET /v1/me/player/recently-played?limit=50 HTTP/1.1
Host: api.spotify.com
Authorization: Bearer <redacted>
```
```json
{
  "items": [{
    "played_at": "2026-11-04T07:00:00.000Z",
    "track": {"id":"TRACK_ID","uri":"spotify:track:TRACK_ID","type":"track","name":"Example","artists":[{"name":"Artist"}],"is_local":false}
  }],
  "next": null,
  "cursors": {"after":"1793775600000","before":"1793775600000"}
}
```
Do not hard-code illustrative cursor values; use returned cursors and test pagination behavior.

## 6. History retrieval contract
- Max `limit=50` per request. `after` and `before` are mutually exclusive. `next` and cursors are available but the accessible history may be bounded.
- Use newest-page backward traversal with overlap and high-water checkpoint per `03-ARCHITECTURE.md`. Stop on repeated cursors. Persist each page transactionally.
- Track `coverage_status`: `UNKNOWN`, `COMPLETE_SINCE_BASELINE`, `PARTIAL_BOOTSTRAP`, `HISTORY_INCOMPLETE`.
- A successfully fetched recent page alone **does not establish** complete coverage after a long gap.

## 7. Playlist creation and idempotency
- Create one **private** playlist; save returned playlist ID and URL. Playlist survives uninstall because it belongs to Spotify.
- Before POST, read remote playlist items (all pages) and check track ID. Cache may optimize but cannot replace reconciliation after uncertain result.
- If POST times out, **never blindly POST again**. Mark `VERIFY_REMOTE`, fetch remote, only retry if definitely absent.
- Spotify API does not provide a guaranteed idempotency key for this operation; even remote-check-before-write cannot eliminate races with another client. Limit to one writer, serialize calls, and document residual risk.
- Do not delete or reorder remote items. Manual removal → `REMOVED_EXTERNALLY` and no auto-re-add.

## 8. HTTP errors / retry matrix
| Status | Behavior |
|---|---|
| 200/201 | Parse and commit after validation |
| 400 | Programmer/config error; stop and show diagnostics |
| 401 | Refresh once; if refresh fails, re-auth |
| 403 | Scope, quota mode, or account restriction; stop repeated calls |
| 404 | Missing playlist or track; ask for relink, do not recreate silently |
| 429 + `reason=QUOTA_EXCEEDED` | Stop automatic requests until quota recovers; report status |
| 429 otherwise | Respect `Retry-After` and backoff |
| 5xx / timeout | Retry GET; POST enters `VERIFY_REMOTE` first |

## 9. Live integration gate checklist
- [ ] AUTH-006/GATE-ACCOUNT-01: new Spotify account has Premium, owns developer app, signs in through OAuth, and matches `GET /me` user ID.
- [ ] A different Spotify user ID is rejected before reads/writes; the old account's playlist ID is never reused.
- [ ] Premium owner and allowlisted user confirmed.
- [ ] Registered exact loopback redirect accepted; auth callback works on OnePlus.
- [ ] Token refresh tested without exposing token in logs.
- [ ] API-01 returns play records with timestamps and IDs.
- [ ] API-02 creates **private** playlist.
- [ ] API-03 returns playlist items and correct `item` field.
- [ ] API-04 adds a test track; Spotify UI shows it.
- [ ] Re-running reconciliation does not duplicate the track.
- [ ] Permission denial and 429 simulation produce useful errors.

## 10. Official references
- https://developer.spotify.com/documentation/web-api/tutorials/code-pkce-flow
- https://developer.spotify.com/documentation/web-api/concepts/redirect_uri
- https://developer.spotify.com/documentation/web-api/reference/get-recently-played
- https://developer.spotify.com/documentation/web-api/reference/create-playlist
- https://developer.spotify.com/documentation/web-api/reference/get-playlists-tracks
- https://developer.spotify.com/documentation/web-api/reference/add-items-to-playlist
- https://developer.spotify.com/documentation/web-api/references/changes/february-2026
- https://developer.spotify.com/documentation/web-api/concepts/quota-modes
- https://developer.spotify.com/blog/2026-07-23-web-api-quota-updates
