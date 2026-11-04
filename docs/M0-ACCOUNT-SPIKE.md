# TASK-002A — Verify the configured Spotify owner after OAuth

Scope: extend the M0 diagnostic APK with one `GET https://api.spotify.com/v1/me`.
Code/automated tests do not establish live account/Premium/app-owner/playlist ownership gates.

## Acceptance contract

- Require Client ID and expected developer-owner **Spotify user ID** before opening OAuth.
  The expected ID is supplied independently by the operator; never learn/accept whichever account signs in.
- Compare `/me.id` exactly and case-sensitively. No fallback to display name, email, `product`,
  profile URL or a matching `account_id` string. Missing/malformed ID fails closed.
- A match yields only HTTP status and masked ID; it is identity evidence, not proof of Premium or
  ownership of a Developer App/playlist. No authenticated app navigation/history/playlist calls yet.
- A mismatch discards the token and exposes no successful session. Cancel/HTTP/parse failures also
  discard the grant; editing either configuration field clears previous success.
- Access token is memory-only through `/me`, outside `UiState`, saved state, Room and DataStore.
  `TokenGrant.close()` releases the app's retained reference; this is not a claim of zeroing JVM strings.
  Refresh tokens are ignored. Cancellation closes HTTP calls and discards tokens on a canceled handoff.
- No added scopes. Profile identity is tested with only `{ "id": "dummy-owner" }`, independent of private
  fields. Actual Development Mode access remains to be proven; a 403 is a failed check, not a bypass.
- 401 asks for a fresh login (production refresh belongs to M2); 403 stops; 429 honors numeric/HTTP-date
  `Retry-After` with an in-memory manual-retry cooldown, default 60 seconds if missing/malformed.
  No automatic network retry, redirect following, profile dump or bearer logging.
- Restarting the process loses configuration, proof and cooldown. Durable auth/rate-limit scheduling
  belongs to M2/M5; the probe is for a controlled manual integration test.

## Current Spotify identity contract

Official `/me` and identifier documentation checked during implementation now distinguishes mutable
`id` (Spotify user ID) from immutable `account_id` and recommends `account_id` for persistent linking.
This M0 check follows AUTH-006's configured username/`id` comparison **without persistent linking**.
Do not reuse it as the production database account key by assumption: before M2, reconcile the
`account_id` contract with PRD/rules/schema/account-switch tests in the same change. Do not guess or
silently substitute one identifier for the other. API fixtures here deliberately test that distinction.

Sources: [Get Current User's Profile](https://developer.spotify.com/documentation/web-api/reference/get-current-users-profile),
[Spotify identifiers](https://developer.spotify.com/documentation/web-api/concepts/spotify-uris-ids).

## Run on OnePlus

1. Use the **new Premium developer-owner account**. Independently confirm that this account owns the
   Web API Developer App, not a Soloist API key. Registered redirect remains `http://127.0.0.1:8765/callback`.
2. Get that account's Spotify user ID: copy its profile link and take the path segment after `/user/`,
   excluding query parameters; alternatively copy the username from the account page if shown.
   Do not enter a display name, email, Client ID, complete URL or `account_id` into this field.
3. Install the `0.2.0-account-spike` debug APK. Builds from a different debug signing key may require
   uninstalling the previous disposable probe first. This probe has no saved account/history data.
4. Enter Client ID and expected owner ID directly on the phone. ID entry is masked; captures/task
   snapshots remain blocked. No personal IDs are needed in chat, logs or repository.
5. Connect and consent in the browser, return to replay. Verify **OAuth 200 + `/me` 200 + ID match**.
6. Intentionally use a different expected ID or another allowed Spotify account. Expect an explicit
   mismatch and no successful session. Keep the real configured owner ID; reconnect using the right
   browser account. Never change the expected owner to rubber-stamp an unknown login.
7. Test cancellation during `/me`, retry after failed login, rotation/process death and empty ID.

## Tests and observed evidence

| Cases | Automated assertions |
|---|---|
| TC-032 / SP-ACCOUNT-001 | Exact expected ID; wrong/case-mismatched ID rejected; no other API calls |
| SP-ACCOUNT-002 | Minimal/missing/null/numeric/malformed ID; display-name/email/product cannot authorize |
| SP-ACCOUNT-003 | GET-only `/me`, bearer in header only; safe result; redirects do not forward credentials |
| SP-ACCOUNT-004 | Every completion/error/cancel closes grant; closed grants cannot issue profile requests |
| SP-ACCOUNT-005 | 401/403/429/5xx/invalid JSON; no blind retry; Retry-After parsing and cooldown |
| SP-ACCOUNT-006 | Double tap; freeze input during verification; invalidating old success; cancel before success |

Only dummy account IDs and tokens in fixtures.

Local evidence: wrapper command
`./gradlew --offline --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`
observed `BUILD SUCCESSFUL`. 52 JVM tests passed (34 auth-core + 18 ViewModel), with zero
failures/errors/skips. Lint passed with version-update reminders informational as already documented.
APK: `app/build/outputs/apk/debug/app-debug.apk`, versionCode 2 / `0.2.0-account-spike`.
Local APK SHA-256: `901e847dedd4f6499188a4ab6400397b59145c90d3e0954a92b1b2e7e729ad3a`.
CI evidence is attached to the TASK-002A commit's GitHub Actions run; do not infer its result from local checks.

Manual status: user reported HTTP 200 in the prior OAuth-only probe. This is evidence of basic login,
not completion of TASK-003's full cancellation/timeout/collision checklist or TASK-002A.
Live `/me` correct/wrong-account checks for this APK remain **pending**.
GATE-ACCOUNT-01 additionally needs Premium/app-owner evidence and a real owned playlist in TASK-004.

## Visual differences

Uses the existing native dark/green diagnostic form; adds masked expected-ID input, `/me` progress,
masked match/error result and retry information. Supplied `00-welcome.webp` reviewed; original PNG
is absent. No artwork, metrics or reference-image background was added. This is not TASK-050B's
finished onboarding, and no OnePlus screenshot/accessibility PASS is claimed without device testing.
