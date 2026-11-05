# M5 UI acceptance: UI-TC-001..018 (TASK-050H)

**Status:** JVM evidence only (Robolectric + Compose, `bash tools/verify.sh`). Nothing has been run on the OnePlus Ace 5, with TalkBack, against live Spotify, or in CI for this table.
- "Covered on JVM" means an automated test asserts the behaviour against fixtures, not real account data.
- "REQUIRES DEVICE" means the case needs a physical run before M5 acceptance. Steps are given below.
- Screenshots are Robolectric renders in `app/build/reports/ui/` (copies in `/workspace/ui-shots/`). They do not replace the device review.

| ID | Area | JVM status | Evidence | Device still needed |
|---|---|---|---|---|
| UI-TC-001 | Onboarding first launch | Covered on JVM | 050B onboarding tests | REQUIRES DEVICE: real browser OAuth |
| UI-TC-002 | OAuth denied / wrong account | Covered on JVM (state) | 050B onboarding/auth tests | REQUIRES DEVICE: live denial and wrong-account flow |
| UI-TC-003 | Home zero data | Covered on JVM | HomeScreenTest empty; UiAcceptanceTest `zero data` (all 5 screens); LeakSweepTest | Visual review |
| UI-TC-004 | Home 2/3, 4/5 | Covered on JVM | LibraryModelTest, HomeScreenTest | No |
| UI-TC-005 | Partial coverage | Covered on JVM | HomeScreenTest coverage; Rules/Settings show CoverageNotice | No |
| UI-TC-006 | Tap sync twice | Partially covered | Sync Now is disabled while its request is in flight (SettingsScreen). Dedup relies on WorkManager unique work (TASK-051 tests) | REQUIRES DEVICE: double tap, one pass in the logs, truthful result |
| UI-TC-007 | History search/filter + back | Covered on JVM | HistoryFilterTest, HistoryScreenTest | Back-stack state on device |
| UI-TC-008 | Track detail | Covered on JVM | HistoryScreenTest detail | No |
| UI-TC-009 | No premature Đã thêm | Covered on JVM | CollectionModelTest/ScreenTest, DiagnosticsTest queue split | REQUIRES DEVICE: live write |
| UI-TC-010 | Open Spotify unavailable | Covered on JVM (error dialog path) | MainActivity opener + 050B tests | REQUIRES DEVICE: uninstall or disable Spotify |
| UI-TC-011 | Rules 3→2 save | Covered on JVM | RulesPreviewTest, RulesScreenTest; QualificationRecorder tests (never removes) | REQUIRES DEVICE: persisted plus remote retained |
| UI-TC-012 | Unsaved edits + back | Covered on JVM | RulesScreenTest `back with draft asks to discard` | Gesture back on device |
| UI-TC-013 | Offline / 401 / 429 / quota | Covered on JVM | SyncCopy distinct copy; UiAcceptanceTest offline; SettingsScreenTest | REQUIRES DEVICE: airplane mode, revoked token |
| UI-TC-014 | Disconnect / switch account | Covered on JVM (050B) | Foundation disconnect dialog + reset tests | REQUIRES DEVICE |
| UI-TC-015 | TalkBack / font 1.3 / narrow | Partially covered | UiAcceptanceTest: 320dp at font 1.3, labelled actions ≥48dp on all 5 screens | REQUIRES DEVICE: TalkBack pass |
| UI-TC-016 | Reduced motion | Pass (Robolectric) | MotionPolishTest: animator scale 0 ⇒ reducedMotion(); progress fill snaps to final value; exact value always in text + semantics | REQUIRES DEVICE: power saver |
| UI-TC-017 | Manual Spotify deletion | Covered on JVM (model) | REMOVED_EXTERNALLY status; reconcile tests (M4) | REQUIRES DEVICE: delete a track in Spotify, then sync |
| UI-TC-018 | Diagnostics copied | Covered on JVM | DiagnosticsTest (no IDs, names, URLs or tokens; unknown codes become OTHER) | Paste check on device |

## Manual device steps (OnePlus Ace 5, OxygenOS 16)
1. Install the debug build with the developer's own Spotify app registration (never commit `spotify.local.properties`). Connect, then check UI-TC-001/002 (also deny once, and try another account).
2. Listen to a track 3× today, open Settings, then tap **Đồng bộ ngay** twice quickly. Expect one pass, "Đang chờ kết quả…", and then a truthful result (UI-TC-006). Open Collection: the track appears under Đang chờ until it is verified, then under Đã thêm (UI-TC-009).
3. Rules: change daily 3→2, press back, confirm the dialog, change it again and save. Check that the preview count matches, and that nothing is removed from the Spotify playlist (UI-TC-011/012).
4. Airplane mode, then sync: expect "Không có mạng"; revoke app access at spotify.com, then sync: expect "Cần đăng nhập lại" (UI-TC-013).
5. Settings: copy the diagnostics and paste them into a note. There should be no IDs or names (UI-TC-018).
6. Font size max plus display size large, with TalkBack on: walk every tab. Primary actions should be reachable and progress values spoken (UI-TC-015). Turn on power saver / remove animations (UI-TC-016).
7. Delete a collected track in Spotify, then sync. Collection should show "Đã gỡ khỏi playlist trên Spotify" (UI-TC-017). Disable Spotify, then tap Open (UI-TC-010). Disconnect (UI-TC-014).
