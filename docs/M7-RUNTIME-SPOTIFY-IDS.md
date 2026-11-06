# TASK-061 — Runtime Spotify Client ID / Owner User ID

Date: 2026-11-06 (Vietnam). Status: **IMPLEMENTED (JVM tests)**.

## Goal

Switch Spotify Premium Developer accounts without rebuilding or reinstalling the APK. The public
Client ID and Developer App owner user ID are stored in an installation-scoped DataStore and can be
edited in onboarding (first-time) or Settings (change).

## Design

| Piece | Role |
|---|---|
| `SpotifyAuthConfigRepository` | DataStore under `noBackupFilesDir/datastore/spotify-auth-config.preferences_pb`. Not cleared by `LocalDataReset`. |
| BuildConfig seed | When the store is empty and `spotify.local.properties` was present at build time, values are copied once into DataStore. |
| `AndroidAuth` | Always constructed. `AuthConfig` lives in an `AtomicReference` read by `SpotifyAuthManager` / `AuthorizationFlow`. |
| `AppContainer.configureAuthIds` | Validates, and if IDs change requires `confirmed=true` then `resetAccount(confirmed=true)` before saving, so old tokens/bindings cannot mix. |
| Onboarding | When unconfigured, shows Client ID + Owner ID fields; saving enables **Kết nối Spotify**. |
| Settings → Developer App | Masked current IDs + edit form with explicit confirmation that local data will be wiped. |

Mismatch against the configured owner is unchanged (`ACCOUNT_MISMATCH`). Diagnostics still redact IDs.

## How the user switches Premium accounts

1. Create a Spotify Developer App on the **new** Premium account (Spotify requirement).
2. Register redirect `http://127.0.0.1:8765/callback`.
3. In replay. → **Cài đặt** → **Developer App**, paste the new Client ID and Owner User ID (`/user/<id>`).
4. Confirm the wipe, save, then sign in again and create/link the playlist.

No APK rebuild is required. A new Premium account still needs its own Developer App + Client ID from the
Spotify Dashboard — this feature only removes the rebuild/reinstall step.

Optional: keep `spotify.local.properties` for debug builds so the first launch is pre-seeded.

## Limits

- Spotify still ties a Developer App to one owner account; you cannot reuse another account’s Client ID.
- Changing IDs wipes **local** history/outbox/binding; Spotify playlists are not deleted.
- CI artifacts without BuildConfig seed show the ID form instead of a dead “login disabled” state.
