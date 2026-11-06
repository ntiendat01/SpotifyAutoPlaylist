# TASK-020 — Production PKCE, token storage, refresh serialization and account identity

Date: 2026-11-04 (Vietnam). Status: **PASS — JVM tests and local build observed; Android Keystore path
and live Spotify login/refresh not yet exercised on device**.
Scope (owner-approved): production auth core in `:auth-core`, Android Keystore wiring and build
configuration in `:app`. The M0 probe screen is unchanged and does not use the new code; onboarding UI
is TASK-050B. Account-switch reset and data isolation are TASK-020A.

## Components

| Component | File | Responsibility |
|---|---|---|
| `AuthConfig` | `auth-core/.../AuthModels.kt` | Client ID + Developer App owner user ID from build config; null when absent, rejects partial values |
| `AuthorizationFlow` | `.../SpotifyAuthManager.kt` | New `PkceSession` (S256, random state, 3-minute deadline); binds `127.0.0.1:8765` **before** opening the browser; closes the listener on success, failure, browser error and cancellation |
| `SpotifyTokenClient` | `.../SpotifyTokenClient.kt` | Code exchange (requires refresh token and all three scopes) and `refresh_token` grant; no client secret |
| `SpotifyIdentityClient` | `.../SpotifyTokenClient.kt` | `GET /me`: exact `id` match with the configured owner and a required immutable `account_id` |
| `AesGcmTokenCipher` | `.../CredentialStore.kt` | AES-256-GCM, fresh IV per write, versioned envelope with associated data |
| `EncryptedCredentialStore` | `.../CredentialStore.kt` | Encrypted JSON file; fsync + atomic rename; fails closed with `STORAGE` |
| `SpotifyAuthManager` | `.../SpotifyAuthManager.kt` | Single owner of the grant: restore, login, `accessToken()`, `onUnauthorized()`, sign-out, `StateFlow<AuthState>` |
| `AndroidAuth` | `app/.../AndroidAuth.kt` | Keystore key `replay.credentials.v1` (AES-256 GCM, randomized encryption); file `noBackupFilesDir/auth/credentials.bin` |

The existing `PkceSession` and `LoopbackCallbackServer` are reused unchanged: state is compared in
constant time, non-GET/wrong path/wrong Host are rejected, query strings are never logged, and the
callback is single-use.

## Contracts

**Login:** exchange code → verify `/me` → only then save. A wrong account, missing `account_id`, partial
scopes or missing refresh token persist nothing. A different `account_id` than the active grant or the
local data binding is rejected (`ACCOUNT_MISMATCH`) instead of silently switching; see TASK-020A below.

**Restore:** a stored grant is activated only when its Client ID and user ID match the current build
config. Otherwise the state is `SignedOut(ACCOUNT_MISMATCH)` and the grant is left untouched for
TASK-020A. An unreadable file (for example, a Keystore key invalidated by the OS) is deleted, and the state becomes
`SignedOut(STORAGE)`, which means reconnect.

**Refresh serialization:** one `Mutex` guards all grant access.
- `accessToken()` refreshes when the token expires within 60 seconds; concurrent callers wait and
  reuse the single result.
- A returned refresh token replaces the old one; if none is returned the previous one is kept.
- The refresh request and saving its result run in `NonCancellable`, so a cancelled caller cannot
  drop a rotated refresh token. OkHttp's 30-second call timeout bounds this section.
- Refresh `400`/`401` means the grant is rejected: it is cleared and the state becomes
  `SignedOut(REAUTH_REQUIRED)`. A refresh response with an explicit narrower scope does the same.
  `429`, `5xx` and network errors keep the grant.

**API 401 (for TASK-022):** call `onUnauthorized(rejectedToken)` once. If another caller has already
refreshed, it returns the newer token without a new request. Otherwise it refreshes once. A second 401
for the token issued by that refresh signs out (`REAUTH_REQUIRED`), matching "one refresh, then re-auth".

**Sign-out:** deletes the file before reporting `SignedOut`; a failed delete reports
`SignedOut(STORAGE)` instead of claiming success. Spotify provides no PKCE token-revocation endpoint;
remote access is removed in Spotify account settings (Apps).

**Secrets:** `AccessToken`, `StoredCredentials`, `TokenResponse`, `VerifiedIdentity` and `AuthConfig`
redact `toString()`; token values are `internal`. `AuthException` carries only a category, HTTP status
and retry hint—no body, URL or cause. Backups already exclude every app data domain.

## Build configuration

Create the ignored `spotify.local.properties` at the repository root:

```properties
spotify.clientId=<32-hex Client ID of the new Premium account's Developer App>
spotify.ownerUserId=<Spotify user ID of that account, from /user/<id>>
```

Both values are validated at configuration time and become `BuildConfig.SPOTIFY_CLIENT_ID` /
`SPOTIFY_OWNER_USER_ID`. Without the file (CI), both are empty and `AndroidAuth.create()` returns null.
The Client ID is public by design; the owner user ID is a personal identifier embedded in locally built
APKs—do not share those APKs. No client secret exists anywhere.

## Verification

```sh
./gradlew --offline --no-daemon :auth-core:test :app:testDebugUnitTest :app:lintDebug \
  :app:assembleDebug :app:assembleRelease --rerun-tasks
```

Observed: **BUILD SUCCESSFUL in 1m 35s** without `spotify.local.properties`.

| Check | Observed result |
|---|---|
| `:auth-core:test` | 85 tests (61 existing + 24 new); 0 failures, errors or skips |
| `:app:testDebugUnitTest` | 23 tests; 0 failures |
| `:app:lintDebug` | 15 informational hints; no warnings or errors |
| Debug + release assembly | Passed; no Kotlin warnings in the new files |
| Concurrency/cancellation stability | `SpotifyAuthManagerTest` rerun 5× with `--rerun-tasks`: all passed |
| Config present (dummy values) | `BuildConfig` fields generated; invalid Client ID fails with explicit message; dummy file removed |

New tests: `SpotifyTokenClientTest` (5), `EncryptedCredentialStoreTest` (4), `SpotifyAuthManagerTest` (15).
They cover TC-019-adjacent flow behavior and TC-020 (401 → one refresh → re-auth; refresh failure),
the TC-032 wrong-account rule at login, rotation, the 20-caller single refresh, cancellation during refresh,
fail-closed storage, and listener closure on cancel/browser failure. All use dummy tokens, a software AES key and MockWebServer.

A real bug was found and fixed during verification: when authorization was cancelled, the closed
listener's `CANCELLED` failure replaced the coroutine's cancellation, so a cancelled `viewModelScope`
job would have ended with an uncaught exception. Cancellation now wins.

## TASK-020A — AUTH-006 enforcement and explicit account change

Status: **PASS — JVM tests and local build observed (2026-11-04)**. No UI wiring; device unverified.

**Data owner binding.** `FileAccountBindingStore` (`noBackupFilesDir/auth/account-binding.json`) records
which account owns local data as `SHA-256("replay.account.v1\0" + account_id)`; it never stores the raw ID.
It is independent of the grant: sign-out ("Giữ lịch sử trên máy") removes only the grant and keeps the
binding, so only the same account can reconnect onto existing history/playlist/outbox data (BR-013).

| Situation | Result |
|---|---|
| First login | Binding written before the grant |
| Login as an account other than the binding (TC-032) | `ACCOUNT_MISMATCH`; nothing saved, binding unchanged |
| Stored grant of another Client ID, owner or bound account | Not activated; `accessToken()`/`requireAccount()` throw `ACCOUNT_MISMATCH` |
| Grant exists without binding | Bound to that grant's account on restore |
| Binding unreadable | `SignedOut(STORAGE)`; login blocked; binding is **not** cleared |
| Reset interrupted | `SignedOut(RESET_PENDING)` across restarts; login and data access blocked until reset is retried |

**Account key gate.** `requireAccount()` returns an opaque `AccountKey` (Spotify user ID + account hash)
only for the signed-in, bound account. Account-scoped repositories (TASK-021+) must key every row and
read through it, so mappings for a previous account cannot be read or written by another one.

**Explicit reset (TC-033).** `resetAccount(confirmed = true)` is the "Xóa dữ liệu tài khoản khỏi máy"
and switch-account path; without confirmation it throws `CONFIRMATION_REQUIRED` and changes nothing.
Under the auth mutex and `NonCancellable`: mark `resetPending` durably → delete the grant → run the
ordered `AccountResetStep`s (stop workers, then delete account-scoped data; must be idempotent) → delete
the binding → `SignedOut()`. Any failure leaves the pending marker, so the old account is never resumed.
Remote Spotify playlists are never touched. `AndroidAuth.create(context, resetSteps)` accepts the steps;
currently none exist because WorkManager (TASK-051) and Room/DataStore (TASK-021) are not implemented.

Verification: same command as above, `BUILD SUCCESSFUL in 1m 24s`. `:auth-core:test` 92 tests
(7 new: TC-032 binding, mismatched restore, binding adoption, TC-033 ordered reset, interrupted reset,
unreadable binding, binding file), `:app:testDebugUnitTest` 23, lint 15 hints, no Kotlin warnings;
`SpotifyAuthManagerTest` rerun 3× stable.

## Not verified / remaining

- `AndroidAuth` (AndroidKeyStore key creation, encrypt/decrypt on device) has no automated test: JVM
  tests cannot load AndroidKeyStore and no instrumentation tests exist. Verify on the OnePlus when the
  UI uses it (TASK-050B) or with an instrumentation test.
- No live Spotify login, refresh or rotation was performed; the refresh-token rotation behavior is
  covered only by fixtures. The token-refresh item in [04-SPOTIFY-API.md](04-SPOTIFY-API.md) §9 stays open.
- No CI run observed for these changes yet.
- Process death during browser login loses the in-memory PKCE verifier; the user restarts login.
- No UI or visual changes.


## TASK-061 — Runtime Client ID / Owner User ID

See [M7-RUNTIME-SPOTIFY-IDS.md](M7-RUNTIME-SPOTIFY-IDS.md). `AuthConfig` may come from DataStore
(runtime) with an optional BuildConfig seed; changing IDs requires confirmation and `resetAccount`.
