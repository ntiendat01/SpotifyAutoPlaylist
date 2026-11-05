# M2 — Spotify Web API client (TASK-022)

Status: **PASS — JVM tests and local build on 2026-11-05.** No live Spotify call, CI run or device run
observed for this client yet.

## Scope
New JVM module `:spotify-api` (package `com.spotifyautoplaylist.spotify`):
- Retrofit 3.0.0 service for API-01 recently played, API-02 create playlist, API-03 playlist items,
  API-04 add items, API-05 owned playlists and API-07 playlist metadata ([04-SPOTIFY-API.md](04-SPOTIFY-API.md) §4).
  API-06 `/me` stays in `auth-core` (`SpotifyIdentityClient`, TASK-020).
- kotlinx-serialization DTOs. Optional or removed (February 2026) fields are nullable; fields whose
  absence makes a response unusable (`items`, `played_at`, `total`, `offset`, `snapshot_id`, playlist `id`)
  are required, so a response without them is `MALFORMED_RESPONSE` instead of an empty page.
- `SpotifyClient`: the only entry point. `AppContainer.spotify` builds it on `SpotifyAuthManager`.

Not in scope: pagination loops, watermarks and coverage (TASK-030), outbox state machine and
reconciliation (TASK-032+), WorkManager backoff (TASK-051), diagnostics UI (TASK-050G).

## Behavior
| Situation | Client behavior |
| --- | --- |
| 2xx | Body decoded (≤ 1 MiB) and sanity-checked: page size ≤ limit, echoed offset, playlist ID matches, non-blank `snapshot_id`, valid created ID; otherwise `MALFORMED_RESPONSE` |
| 401 | `BearerTokenProvider.onUnauthorized` (one serialized refresh in `SpotifyAuthManager`), retry once; a second 401 calls it again (the manager then signs out with `REAUTH_REQUIRED`) and fails |
| 400 / 403 / 404 | `BAD_REQUEST` / `FORBIDDEN` / `NOT_FOUND`; not retryable |
| 429 | `RATE_LIMITED`, or `QUOTA_EXCEEDED` when the error `reason` says so; `Retry-After` (seconds or HTTP date, default 60 s). Every later call fails fast until it passes |
| 5xx, network/timeout, unreadable 2xx, other status (redirects are not followed) | Retryable for reads. For POST, `outcomeUnknown = true`: the caller must keep/mark `VERIFY_REMOTE` and read back before any retry |

- No automatic retries other than the single 401 retry. OkHttp `retryOnConnectionFailure` is off so a POST
  is never re-sent transparently, and redirects are off so a bearer token is never forwarded.
- Requests are built from validated IDs (22-character base62) and offsets; the client has no API that
  follows a server-provided `next` URL. `before`/`after` are mutually exclusive; limits 1..50, 1..100
  tracks per add, playlist name 1..100 characters. Created playlists are always private and not
  collaborative.
- Persistent quota pause and backoff schedule are caller policy; the client only prevents calls during
  `Retry-After`.

## Sanitized logs
`SpotifyHttpLogger` receives one `SpotifyHttpEvent` per HTTP attempt. Its text is limited to endpoint ID,
method, path template, attempt, status, error class and duration, for example
`API-04 POST /playlists/{id}/items attempt=1 status=500 result=SERVER ms=812`. No URL, ID, query value,
header, token, request/response body or exception text is included; `SpotifyApiException` messages
follow the same rule. The app logs these events to logcat only in debug builds. No OkHttp logging
interceptor is installed.

`AccessToken` now has a public constructor (non-blank) and `bearerHeader()`; `SpotifyAuthManager`
implements the new `BearerTokenProvider` interface.

## Tests (dummy data only)
`SpotifyDtoTest` (6): the 04 §5 example, unknown/removed/null fields, required fields, playlist `item`
field (legacy `track` ignored), playlist metadata, exact request bodies.
`SpotifyClientTest` (17, MockWebServer): request path/query/header, argument validation before network,
401 refresh-and-retry and repeated 401, the §8 status matrix without retries or redirects, `Retry-After`
fail-fast window, `QUOTA_EXCEEDED`, `Retry-After` parsing, malformed/oversized/over-limit responses,
API-04/API-02 bodies, ambiguous POST (5xx, empty snapshot, read timeout) marked `outcomeUnknown` and
sent once, rejected POST not ambiguous, API-03/05/07 parsing, and logs/errors free of tokens, IDs and queries.

## Evidence
`./gradlew --no-daemon :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug
:app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease` → BUILD SUCCESSFUL.
After a converter type fix, `:auth-core:test :spotify-api:test :app:compileDebugKotlin --rerun-tasks` →
BUILD SUCCESSFUL with no compiler warnings. Tests: auth-core 92, spotify-api 23, data 11, app 23
(149, 0 failures/errors/skips). `:spotify-api:test` passed on two further `--rerun-tasks` runs.

Dependencies: Retrofit 3.0.0 (requires OkHttp 4.12.0, already used, and kotlin-stdlib 2.1.21 ≤ 2.2.21)
and the Kotlin serialization compiler plugin 2.2.21. The app lockfile only gains
`com.squareup.retrofit2:retrofit:3.0.0` on runtime classpaths; no other resolved version changed.

## Known gaps
- Not exercised against the live API with this client (M0 smoke used the probe code path). Live checks
  in 04 §9 remain open.
- Release minification is off; enabling R8 later must be verified against this client (reflection-based Retrofit service, serializers).
- Remote membership verification and the `VERIFY_REMOTE` → `PRESENT` flow are not implemented here.
