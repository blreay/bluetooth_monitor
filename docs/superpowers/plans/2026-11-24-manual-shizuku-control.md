# Manual Shizuku Bluetooth Control Implementation Plan

> **For agentic workers:** Use `superpowers:subagent-driven-development` or `superpowers:executing-plans`. Follow tests-first implementation and spec/quality review before final verification.

## Context

The original launcher scanned/reset immediately with no visible Activity. Replace that entry behavior with a manual control page while preserving the explicitly triggered legacy broadcast/recovery protocol. New manual toggles must operate as Shizuku shell/root, with no ordinary-app fallback or misleading success result.

**Goal:** Visible four-button page, explicit Shizuku grant, privileged on/off and discovery-only device list.
**Architecture:** Native Activity, lifecycle-aware Shizuku client and AIDL UserService, independent discovery controller/device store, generation interlock for legacy isolation.
**Tech Stack:** Kotlin 1.9.24, Android min31/compile34/target32, AGP8.4, Shizuku api/provider13.1.5, JUnit4.

Approved spec: `docs/superpowers/specs/2026-11-24-manual-shizuku-control-design.md`.

## Task 1: Tests and pure policies

**Files:** `app/src/test/java/com/blreay/bluetoothmonitor/`, pure helpers in the matching main package.

- [x] Test first: fixed `svc bluetooth enable/disable` argument lists and high-UID guard, without shell string interpolation.
- [x] Test grant/connection readiness independently: denial is not success, no grant/no service cannot toggle, binder death invalidates readiness.
- [x] Test discovery store: normalized MAC dedup, nonempty name retention, RSSI updates, BLE/classic merge and stable order.
- [x] Test interlock: manual acquisition invalidates prior legacy token, blocks new legacy resets, old releases cannot unlock a new manual operation.
- [x] Observe red, implement minimal pure helpers, observe green; retain existing matcher/re-enable/report regression tests. Implementer observed 62 tests / 14 expected assertion failures, then all 62 passed; five integration-boundary tests subsequently failed as expected before Android integration.

## Task 2: Official Shizuku privileged service

**Files:** `app/build.gradle.kts`, `AndroidManifest.xml`, new `ShizukuBluetoothClient.kt`, `ShizukuRegistrationBroker.kt`, `ShizukuUserServiceBroker.kt`, `PrivilegedBluetoothService.kt`, `PrivilegedBluetoothCommand.kt`, `BluetoothCommandRunner.kt`, `app/src/main/aidl/com/blreay/bluetoothmonitor/IPrivilegedBluetoothService.aidl`.

- [x] Add fixed official api/provider13.1.5 dependencies, protected Provider manifest and AIDL build feature.
- [x] Implement explicit permission-button flow and genuine grant-result status, listener lifecycle, connection preflight/timeouts, binder death/restart handling.
- [x] Use public zero-argument generated Stub and official UserService bind/unbind. All AIDL methods have explicit transaction IDs; destroy=16777114 must terminate dedicated process without waiting for long command locks.
- [x] Verify shell/root UID and execute only fixed ProcessBuilder args; asynchronously bound RPC/command/output draining and honest accepted-vs-confirmed state.
- [x] Never invoke ordinary toggles/confirmation intents/newProcess or use ordinary fallback.

## Task 3: Independent discovery and legacy interlock

**Files:** new `BluetoothDiscovery.kt`, `DiscoveredDevices.kt`, `ManualBluetoothInterlock.kt`; narrow edits to `BandResetController.kt` / `ReEnableReceiver.kt`.

- [x] Require explicit scan click, Nearby permission, ON adapter and no pending recovery; no implicit enable or paired-cache fake discoveries.
- [x] BLE8s then classic up to15s, realtime MAC-deduplicated results, phase-specific failure and bounded cleanup on page exit.
- [x] Do not match/reset/connect on discovered targets.
- [x] Capture/recheck legacy generation before match notification/reset; manual operations block new legacy reset and invalidate old in-flight scans.
- [x] After successful toggle preflight claim interlock, clear store/cancel old recovery; release all terminal paths with stale-token protection.

## Task 4: Visible page and removal of automatic startup

**Files:** `MainActivity.kt`, `AndroidManifest.xml`, native programmatic layout and themes; obsolete permission bridge made inert and unregistered.

- [x] Native four-button UI, exact Chinese button labels, grant/operation status and scrolling device list. Programmatic layout accepted in independent spec review; no extra UI framework or layout XML required.
- [x] MAIN/LAUNCHER and action.RUN only open page, never auto scan/reset/request/toggle/finish.
- [x] Scan permission callback resumes only the explicit pending click; avoid repeated launch on recreation.
- [x] Serialize controls, stop discovery and detach listeners/callbacks on lifecycle cleanup.
- [x] Update stale permission/service comments and messages referring to invisible launcher/automatic permission scan. TriggerReceiver now explicitly points to 点击扫描蓝牙; the whole page, including long status/output and device rows, scrolls in short windows. Parent inspected the corrected source; final review/build remain below.

## Task 5: Review, verification and delivery

**Files:** `README.md`, this plan/spec, final test/build results.

- [x] Rewrite README to distinguish manual Shizuku controls from old broadcast automation and ordinary-interface limitations.
- [x] Spec compliance review first, then independent quality/correctness review; fix verified defects and re-review. Final stable-source spec review: PASS. Independent quality re-review: APPROVED after the status-read, runner, broker, UI/guidance and exact-binding reconfirmation corrections; no remaining Critical/Important correctness defects.
- [x] Run full cached build environment, forcing tasks to execute:

  ```bash
  source /home/admin/.cache/btreset-build/env.sh
  "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
    testReleaseUnitTest assembleDebug assembleRelease lintDebug --rerun-tasks --stacktrace
  ```

- [x] Count actual JUnit failures/tests, verify produced APK/signature and diff hygiene again after the quality corrections: 97 tests passed; debug/release APKs built; release signature verified. Any further source correction must repeat verification.
- [x] Check adb devices; if available validate actual UI, grant/binder lifecycle, radio ON/OFF without ordinary confirmation, discovery-only list and cleanup. No connected devices: real-device validation skipped, not passed.
- [x] Commit/push current `bluetooth_monitor_shizuku` branch after verification; implementation commit `ed2b8ba1c235b84bcef459d3d232f7e75a316209` pushed and independently matched to the remote branch ref. No main merge or rolling latest dispatch; branch push does not trigger existing CI.
- [x] Record actual build/test/device/CI outcomes, APK path and commit/branch in the delivery record below for the final user-facing handoff.

## Verification record (2026-11-25)

- First independent spec review found one gap: the lifecycle-only Shizuku grant read had no deadline or tracked Future. The correction adds a five-second watchdog, tracked/canceled Future, generation-safe completion and truthful unknown status. A process-wide single worker/one-slot queue caps noninterruptible passive Binder reads across Activity instances; cancellation does not claim to forcibly abort native Binder transactions. Focused independent spec re-review: PASS.
- Correction tests-first evidence from implementer: 80 tests / five expected assertion failures (four authorization status-generation/timeout behaviors and one source guard), then green.
- Parent post-spec-fix full command above: exit 0, BUILD SUCCESSFUL in 1m 14s, all 96 actionable tasks executed (not UP-TO-DATE). Debug/release APKs produced.
- Parent parsed JUnit XML: nine suites / 80 tests / zero failures, errors or skipped tests. The original 42 regressions remain; additions include 28 behavioral tests and 10 source-boundary checks. Android Binder/UI/radio behavior is not JVM-tested.
- Parent parsed lint XML: 21 issues, not a clean lint report. Five existing MissingPermission errors remain in the legacy controller/switch, one fatal retained-target32 finding and 15 warnings. Discovery has zero lint findings after precise method-level annotations; its permission checks and exception handling remain unchanged. No global permission-rule disable or SDK upgrade.
- Both APK metadata files: applicationId `com.blreay.bluetoothmonitor`, versionName 1.0, local versionCode 1, minSdk31. A phone with a higher installed versionCode may require an appropriate build-time `GITHUB_RUN_NUMBER` to cover-install; do not uninstall just for testing.
- Release `apksigner verify --verbose --print-certs`: verifies, v2 signature, existing RSA2048 personal certificate. Certificate SHA-256: `2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad`.
- `adb devices -l`: no connected devices. Actual authorization, Shizuku restart/death, OEM no-confirmation switching, physical ON/OFF, scan results and lifecycle behavior remain unverified on-device.
- `git diff --check`: clean at this checkpoint. Existing workflow accepts only main push/manual dispatch; no CI or latest publication was triggered for this feature branch.

## Final correction-round verification (2026-11-25)

- Quality review found two important defects: child creation could race publication/destruction, and SDK13.1.5 callback/cache mutations could race across threads. Corrections use terminal short spawn/publication coordination and a process-wide UUID one-slot broker, with serialized off-main management and both management completion plus the posted SDK-cleanup barrier required for new admission. Never-connected ambiguous slots remain detached quarantine; exact late endpoints are cleanup-only. Whole-page scrolling and explicit scan-button permission guidance also corrected.
- Normal repeated authorization now freshly checks the grant and reconfirms the exact current live slot/Binder before teardown. It does not unnecessarily retire a healthy connection, bind again, or erase generation identity.
- Implementer meaningful tests-first reds: 85 tests / five assertion failures for runner/UI/guidance, 93 / ten for the broker, 94 / one for persistent quarantine guidance, 97 / three for exact-binding reconfirmation. Three new runner concurrency tests use injected WaitingProcess/latches; three existing tests exercise real child-JVM output/exit/timeout. These are not Android Binder or hardware tests.
- Final stable-source spec re-review: PASS, with correction-source/test hashes unchanged during review. Independent quality re-review: APPROVED; both prior Important and both Minor defects resolved, no remaining Critical/Important findings. Optional nonblocking follow-up: cap active authorization workers process-wide to limit accumulation if explicit retries repeatedly hit a stalled Binder before any slot is acquired; passive reads and UserService registrations are already capped. No stale command authority is introduced by that deferred hardening.
- Parent reran the full command above with `--rerun-tasks --stacktrace --console=plain`: exit 0, BUILD SUCCESSFUL in 25s, 96 actionable tasks executed. After both final reviews passed, the same full command was repeated: exit 0, BUILD SUCCESSFUL in 24s, all 96 tasks executed again. Both debug and release APKs rebuilt.
- Parent parsed final JUnit XML: ten suites / 97 tests / zero failures, errors or skipped tests. Original 42 regressions retained; new coverage consists of 41 behavioral tests and 14 source-boundary checks.
- Parent parsed final lint XML: 21 issues (five Error MissingPermission in the legacy controller/switch, one Fatal ExpiredTargetSdkVersion for retained target32, 15 warnings). No Shizuku/discovery/runner/privileged-command source findings. Lint task exit 0 does not mean a clean lint report; no SDK upgrade or global rule disable.
- Final APK metadata remains applicationId `com.blreay.bluetoothmonitor`, versionName 1.0, versionCode 1, minSdk31. Release `apksigner verify --verbose --print-certs` verifies v2 with the same RSA2048 certificate SHA-256 recorded above. APKs: `app/build/outputs/apk/debug/app-debug.apk` and `app/build/outputs/apk/release/app-release.apk`.
- Final `adb devices -l`: no connected devices; real-device/OEM UI, authorization, actual ON/OFF and no-confirmation behavior not executed. `git diff --check`: clean. CI/signing/version settings/wrapper/root build scope unchanged. This verification checkpoint preceded the commit/push recorded below.

## Delivery record (2026-11-25)

- Implementation commit: `ed2b8ba1c235b84bcef459d3d232f7e75a316209` (`feat: add visible manual Shizuku Bluetooth controls`).
- Pushed `bluetooth_monitor_shizuku` to `origin` and verified its remote ref exactly matched the implementation commit. The working tree was clean after that push; this documentation-only follow-up records the actual outcome.
- Final verification: spec PASS, quality APPROVED, 97 JVM tests passed, debug/release built, release signature verified. Existing lint findings remain as detailed above; no device testing performed.
- Release APK: `app/build/outputs/apk/release/app-release.apk`; local versionCode 1. A higher installed versionCode requires an appropriate build-time run number for cover-install, not uninstalling the app.
- No main merge, PR, workflow dispatch or rolling latest publication. Existing CI does not trigger on this feature branch. Worktree preserved at `/home/admin/git/bluetooth_monitor_shizuku`.
