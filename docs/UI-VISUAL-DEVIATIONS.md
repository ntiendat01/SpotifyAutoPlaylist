# Visual Reference vs. Actual MVP — Scope Guardrails

> Version 1.3.0. Images convey design appearance; their example data and some features do **not** define new product requirements.

| Depicted in images | MVP implementation decision |
|---|---|
| Sample tracks/artists, user handles, arbitrary dates (some show January 2025), `24` collected, `3` new tracks, sample charts | **Fixtures only.** Production UI uses permitted Spotify/Room data or explicit empty/unknown state. |
| Welcome `Explore preview` | **Optional** static demo, behind explicit demo banner, no Spotify or fake account linkage. Can omit from MVP. |
| Setup `Use existing playlist` | **Conditional**: only allow user-owned playlist with verified API support and ownership; otherwise show only create-private-playlist flow. |
| Setup screen with bottom tabs active before login | **Corrected:** hide app bottom tabs until authenticated and setup completed. |
| Settings screenshot labels playlist `Public` | **Corrected:** default is **Private**; show real privacy only after API confirms. |
| Settings screenshot `Premium` badge | Only show if actually verified. Never infer access from UI or assume another account's Premium qualifies app owner. |
| Rules screen plus/minus and auto-add toggle | Thresholds are editable in v1.2 (default 3/day and 5/72h); **auto-add toggle is NOT an independent control in v1.2**. Either remove or replace with explanatory info. Auto-sync toggle belongs in Settings. |
| Rules screen progress rings 4/4 and 4/5 | Rings represent a **specific track's** actual recent events, not global threshold progress. Use per-track data or sample explicitly labelled demo. |
| Settings screenshot notification toggle | **Not in MVP** per UI scope boundaries; omit or mark future-only, not a working fake switch. |
| Sync Complete shows exact next sync `Tomorrow, 10:00 AM` | **Corrected:** WorkManager is best-effort; don't promise an exact run time. Display `Tự động đồng bộ định kỳ (không bảo đảm thời điểm)` or similar. |
| Sync Complete shows play buttons on track rows | **Not in MVP:** app does not play music; clicking an item may deep-link to Spotify when a verified URL is available, without pretending to play it in-app. |
| `No data gaps detected` | Only if coverage verification provides evidence; otherwise show `Chưa xác định` or explicit coverage warning. |
| `Last Sync 2 min 14 sec` / `Last sync 8 min ago` | Use real timestamps or durations, never fixed fixture copy. |
| Collection shows one `Adding...` row while Added filter selected | **Corrected:** when Added selected, display only confirmed rows; pending rows belong to Pending segment. |
| Collection `+100% vs last week` / 7-day charts | Optional lightweight analytics from **locally observed events only**, never imply global Spotify history or missing data completeness; check relevant Spotify policies before distribution. |
| Branding and album covers in artwork collage | UI reference artwork is synthetic; production needs licensed/permitted album images returned by Spotify with attribution and policy compliance. Use a neutral placeholder otherwise. |
| Spotify-looking logo/assets | Respect Spotify brand guidelines; the companion app cannot imply official Spotify affiliation. Prefer original `replay.` identity and approved Spotify branding in supported contexts. |

## Business invariants (source of truth)

- `>=3` distinct play events in Vietnam calendar day **OR** `>=5` in rolling 72h; inclusion at boundary, no false complete-play claims.
- Once added, never auto-delete the track from the playlist. Never confirm `Added` merely from local pending status.
- External playlist is the real product. Never use the rendered visual reference file as a UI screenshot background.
- Account isolation and Premium/Developer App gates remain governed by `AUTH-006` and actual Spotify API feasibility.
- Do not invent functions from an illustration; update PRD, rules, tests and tasks *first* if adopting extras.

## Implementation deviations log (TASK-050C..I)

| Task | Reference | Deviation | Reason |
|---|---|---|---|
| 050C | `02-home.png` | Hero shows neutral placeholder, no album collage | No image loader; dependencies locked; artwork policy |
| 050C | `02-home.png` | Linear threshold bars instead of rings | Reuses `ThresholdProgress`; text carries exact counts |
| 050C | `02-home.png` | No Sync Now/last-sync card on Home; status strip links to Settings | Sync Now is in Settings (050G) |
| 050C | `02-home.png` | Pulse counts are locally recorded play events, partial-coverage note when not complete | Honest coverage |
| 050D | `03-listening-history.png` | Chips instead of segmented control; placeholder artwork; no per-row chart | Material3 stable API, no image loader |
| 050E | `04-collection.png` | No `+100% vs last week` analytics; no artwork; chips not segmented control | Honest data only, no image loader |
| 050F | Rules Studio | Placeholder artwork-free cards; preview lists up to 3 tracks + count; no auto-add/notification toggles (unsupported) |
| 050G | Settings / Sync Health | Text-only health card and monospace diagnostics; relink card shown only on RELINK_REQUIRED; no toggles |
| 050H | All data tabs | Rules stepper shows number over unit for large fonts; no other visual change |
| 050I | Hero card glow and progress fill animation (not in concept) | Small polish allowed by 050I; bounded to the card; disabled under reduced motion |

## Owner-authorized UI refresh — 2026-11-05

The owner requested the UI review improvements with **no workflow changes**. These entries supersede
the presentation limitations above where noted; the original milestone entries remain historical.

| Surface | Implemented presentation | Workflow guard |
|---|---|---|
| Shared theme | Complete emerald Material container/surface roles; no default purple selected chips | Dark identity retained; no dynamic color setting |
| Artwork | Stored `image_url` exposed to UI; anonymous HTTPS Spotify CDN images with 8 MiB memory-only cache, 2 MiB response cap, ≤512px decode; fallback disc | No auth/scopes, database migration or new Spotify API calls; no reference artwork in production |
| Home | Real-artwork hero collage when available; dominant nearest-rule progress; supporting second count; quieter sync summary/empty states | Same sections/actions/routes; Sync Now remains in Settings |
| History / Collection | Compact artwork rows, status pills, stable-key lazy lists; direct chips scroll horizontally at narrow widths instead of increasing header height | Same filters/segments/search and track-detail navigation; no filter sheet or extra selection confirmation |
| Rules | Larger threshold values, concise intro and footnotes | Same inline Save, draft/default/discard/back behavior; no pinned action or rule changes |
| Settings | Playlist/connection summary first; readable sync counts; diagnostics collapsed, Copy always available | Same sync/relink/disconnect callbacks and confirmations; no invented worker running state |
| Welcome / Setup / Preview | Lighter benefit rows and concise privacy/rule notes; stale “sync not implemented” copy removed | Same OAuth/create/link/recovery/preview flow and confirmations; existing link form remains inline |

Only WebP references are present in this checkout. The original PNG requirement could not be verified;
existing reference files were inspected and left unchanged. Device screenshot/TalkBack/live artwork
acceptance remains separate from synthetic Robolectric evidence in `UI-REFRESH-2026-11-05.md`.
