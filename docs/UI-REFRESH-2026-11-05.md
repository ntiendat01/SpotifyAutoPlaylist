# UI refresh — 2026-11-05

Owner request: apply `UI-REVIEW-2026-11-05.md`, **do not change workflows**.
Skills applied: ui-ux-pro-max and mobile-android-design. Base revision: `b76f67b`.

## Result and scope

- Emerald Material color roles replace the default purple selected containers. Dark Replay palette remains.
- Stored Spotify album artwork now reaches list/detail/playlist hero components. Missing or failed artwork
  uses the same neutral disc, at fixed bounds. Hero can compose up to three actual stored images.
- Artwork requests are anonymous HTTPS to Spotify CDN only, no bearer headers/redirects/logging/disk cache;
  8 MiB memory cache, 2 MiB response limit, four concurrent loads, decoded edge ≤512px, 5s connect/read timeouts.
- Home uses a clearer hero/count, quieter sync summary, lightweight empty states and one dominant
  near-threshold progress bar. The second exact count remains visible. Progress starts at its actual
  value; subsequent changes animate for 200ms, or snap with reduced motion.
- History/Collection use compact rows, status badges, artwork, stable-key LazyColumn and 8dp item gaps.
  Direct filter chips scroll horizontally when necessary rather than expanding the header to many rows.
  Search gets the correct search vector and a shorter label. Detail shows larger artwork.
- Collection uses the compact hero and short added dates in rows; full timestamps remain in detail.
- Rules enlarges threshold numbers and separates concise intro from explanatory footnotes.
- Settings surfaces playlist/connection first, separates sync count lines, and collapses technical
  diagnostics. Copy stays directly available whether details are open or closed.
- Buttons use action-specific vectors; busy spinner retains contrast and disabled semantics.
- Welcome/Setup use lighter benefit/privacy sections; obsolete “sync/add not ready” text is removed.
  Account-free preview remains clearly labeled and does not read account data.

The same four tabs, secondary Settings, search/filter selections, track-detail navigation and return,
OAuth, create/link/recover confirmations, sync/relink/disconnect callbacks and rule-save/discard remain.
Sync Now stays in Settings; Save stays inline; no filter sheet, new toggle, new worker state or new
remote action was introduced. Counting, account isolation, outbox, schema, permissions, version and
dependencies are unchanged. Read-model change is limited to exposing the existing stored `image_url`.

## Modified files

Production:

- `app/src/main/kotlin/com/spotifyautoplaylist/ui/ReplayTheme.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/ReplayComponents.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/ReplayIcons.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/TrackArtwork.kt` (new)
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/HomeScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/HistoryScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/CollectionScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/RulesScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/SettingsScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/ui/ReplayFoundation.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/onboarding/OnboardingScreen.kt`
- `app/src/main/kotlin/com/spotifyautoplaylist/library/LibraryModel.kt`

Tests:

- `app/src/test/kotlin/com/spotifyautoplaylist/ui/ArtworkImagesTest.kt` (new)
- `app/src/test/kotlin/com/spotifyautoplaylist/ui/UiRefreshTest.kt` (new)
- `app/src/test/kotlin/com/spotifyautoplaylist/ui/UiAcceptanceTest.kt`
- `app/src/test/kotlin/com/spotifyautoplaylist/ui/HistoryScreenTest.kt`
- `app/src/test/kotlin/com/spotifyautoplaylist/ui/CollectionScreenTest.kt`
- `app/src/test/kotlin/com/spotifyautoplaylist/ui/SettingsScreenTest.kt`

Requirements/acceptance were updated before implementation in `docs/06-UI-UX.md` and
`docs/07-TEST-PLAN.md`; visual changes are recorded in `docs/UI-VISUAL-DEVIATIONS.md`.
`docs/UI-REVIEW-2026-11-05.md` links this implementation follow-up; this report is new.
The pre-existing untracked `CODEBASE-REVIEW-2026-11-05.md` was left untouched.

## Verification

Local command, with JDK 17 and SDK/Gradle cache already installed under `/private/tmp/spotify-autoplaylist-toolchain`:

```sh
./gradlew --offline --no-daemon \
  :auth-core:test :spotify-api:test :data:testDebugUnitTest :data:lintDebug \
  :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease \
  -Preplay.screenshots=/private/tmp/replay-ui-refresh-2026-11-05
```

First full local verification: **BUILD SUCCESSFUL, 3m57s**, 413 tests in JUnit reports, no failures,
errors or skips. Unchanged auth/API/data tests were UP-TO-DATE from the observed baseline run;
all 217 app tests reran. Debug/release variants compiled. A second app test/lint run after adding
font2/landscape checks passed in 1m18s with 219 app tests.

**Final verification: PASS — BUILD SUCCESSFUL, 2m9s.** All **220 app tests** reran after the busy-button
contrast change; no failures/errors/skips. Together with unchanged auth-core **92**, spotify-api **92**
and data **12**, current JUnit reports contain **416 passing tests**. App lint, debug/release compilation
and release vital lint succeeded. `git diff --check` passed; a separate diff check confirmed no changes
to auth-core, spotify-api, data/schema, sync/writer/onboarding ViewModel or MainActivity wiring.
Final artifacts contain **40 synthetic PNGs**; the active/busy/disabled button render was inspected too.
Debug and release APK signatures were verified locally; this is build evidence, not a release approval.

APK SHA-256:

- Debug: `ece79242b2adcb2bba5c226c7a8077440a9c02800dac57aae4f8847d5ba7ff6b` (v2 signature).
- Release: `f4fbacf05229fd7d0925fca9905bc5c4301b84b108f4f386c058b1907c6ad1dd` (v3 signature).

New checks cover CDN URL restrictions, response byte cap, invalid images and decoded dimension budget;
stored artwork mapping without changed counters/status; emerald selected roles; 100-track history
detail/back/tab scroll restoration; font2 at 320dp on all five screens; full-scaffold landscape navigation;
and button active/busy/disabled presentation. Existing workflow tests retain their behavioral intent;
selectors adapt only to the shorter search label, badges and scrollable chip row.
Accessibility action checks now verify both width and height ≥48dp.

Synthetic screenshots: `/private/tmp/replay-ui-refresh-2026-11-05/`.
Inspected default Home, History, Collection, Rules and Settings; onboarding/setup; 320dp/font1.3;
font2 Home/History/Rules/Settings; landscape History/Rules. Captures taken after test scrolling may
show partial content at the viewport edge. Only History/landscape navigation captures include the
full scaffold; content-only screen captures do not prove system-inset behavior on a physical device.

Default History shows its first song higher and more of the next row within the same full-scaffold
viewport than the earlier render. Selected chips
are emerald, counters/status are separate, and Settings no longer opens with a large raw diagnostic block.
At font2 the layouts grow/wrap and primary actions remain reachable by scrolling; no smaller text is
forced to fit. Long choice rows remain directly selectable with horizontal scroll.

## Evidence limits and visual differences

- The original PNGs requested by AGENTS are absent; all eight available WebP references were inspected
  during the review and left unchanged. No reference image was embedded in the app.
- Native layouts remain intentionally simpler than the concept: restrained gradients, progress bars,
  direct chips, and no unsupported playback, notifications, Premium badge or precise next-sync claims.
- Artwork is tested through URL/byte/decode/read-model paths. Synthetic screen fixtures have no real
  album URLs, so those screenshots intentionally show placeholders. Live CDN loading still needs a
  configured device pass; no real history/account screenshots were taken.
- No GitHub Actions run or device/TalkBack/live Spotify acceptance is claimed for this working tree.
  No commit, push, install or release publication was performed.
- Builds in this environment have no `spotify.local.properties`: login is unconfigured in these APKs.
  Existing installed app/configuration was not touched. Device testing should use the owner's configured
  build/signing setup. The earlier configured-launcher test environment limitation remains outside UI scope.
- Existing non-fatal LocalClipboardManager deprecation and native library strip notices remain.
  Lint XML has 24 informational Hints and no Warning/Error/Fatal; data lint has no issues.
