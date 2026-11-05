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
