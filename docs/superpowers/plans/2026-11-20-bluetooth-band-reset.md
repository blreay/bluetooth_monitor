# Bluetooth Band Reset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Create a sideloadable, icon-triggered Android APK that resets enabled Bluetooth only after a live advertisement named `Xiaomi Smart Band 8 35BA`, then publish sources and an Actions APK build.

**Architecture:** A transparent permission/launch Activity hands work to a bounded foreground Service. A Java state machine drives an Android BLE adapter through narrow interfaces; scan, state-transition and delay callbacks execute on the main thread. Explicit exported Activity invocation is the only external control surface.

**Tech Stack:** Java 17 source, Android SDK 35 / target 32 / minimum 26, AGP 8.7.3, Gradle 8.9, JUnit 4.13.2, GitHub Actions Java 17 and Android tooling.

---

## Files and responsibilities

- `settings.gradle`, `build.gradle`, `gradle.properties`, `app/build.gradle`: pinned build configuration.
- `gradlew`, `gradlew.bat`, `gradle/wrapper/*`: generated official Gradle wrapper, verified checksum.
- `app/src/main/java/com/blreay/bluetoothmonitor/ResetController.java`: Android-free single-run state machine.
- `app/src/test/java/com/blreay/bluetoothmonitor/ResetControllerTest.java`: fake-port and fake-clock event tests.
- `app/src/main/java/com/blreay/bluetoothmonitor/AndroidBluetoothSession.java`: BLE scan, adapter toggles, broadcast receiver, timers, cleanup.
- `app/src/main/java/com/blreay/bluetoothmonitor/TriggerActivity.java`: transparent lifecycle, runtime permissions, service startup acknowledgement.
- `app/src/main/java/com/blreay/bluetoothmonitor/ResetService.java`: notification, wakelock, single-run service ownership.
- `app/src/main/AndroidManifest.xml`, `app/src/main/res/values/*`, `app/src/main/res/drawable/*`, `app/src/main/res/mipmap-anydpi-v26/*`: permissions, entry point, theme, launcher icon.
- `.github/workflows/build-apk.yml`: checks and installable APK artifact on every push/PR, manual runs.
- `README.md`, `.gitignore`: honest compatibility constraints, permissions, invocation, CI and phone verification.

## Task 1: Build skeleton and executable test harness

- [ ] Create AGP root and Android module configuration. The core Android config is:

```groovy
android {
    namespace 'com.blreay.bluetoothmonitor'
    compileSdk 35
    defaultConfig {
        applicationId 'com.blreay.bluetoothmonitor'
        minSdk 26
        targetSdk 32
        versionCode 1
        versionName '1.0'
    }
    compileOptions {
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
dependencies { testImplementation 'junit:junit:4.13.2' }
```

- [ ] Download Gradle 8.9 and generate official wrapper with SHA-256 pinned in properties. Download Android command-line tools into `/tmp` or a user cache, not committed project files; install SDK 35/build-tools 35.0.0. Do not overwrite existing SDKs or credentials.
- [ ] Create the controller tests first. Compile/run JVM tests independently if Android dependency downloads are blocked; then run `./gradlew :app:testDebugUnitTest` once the SDK is installed.

## Task 2: Test-first deterministic reset controller

- [ ] Define the exact port contract and constants, used unchanged by the Android session:

```java
public enum BluetoothState { ON, OFF, TURNING_ON, TURNING_OFF, UNKNOWN }
public interface Port {
    BluetoothState bluetoothState();
    boolean startScan();
    void stopScan();
    boolean disable();
    boolean enable();
    void schedule(Runnable task, long delayMillis);
    void cancelScheduled();
    void finish(String outcome);
}
public static final String TARGET_NAME = "Xiaomi Smart Band 8 35BA";
public static final long SCAN_TIMEOUT_MS = 15_000;
public static final long TRANSITION_TIMEOUT_MS = 15_000;
public static final long OFF_DELAY_MS = 3_000;
```

- [ ] Write fake-port tests for the initial-off branch and wrong/cached-name exclusion. Representative acceptance assertions:

```java
controller.start();
controller.onAdvertisement("different band");
assertEquals(0, port.disableCalls);
controller.onAdvertisement(ResetController.TARGET_NAME);
assertEquals(1, port.disableCalls);
assertEquals(0, port.enableCalls);
controller.onBluetoothOff();
assertEquals(ResetController.OFF_DELAY_MS, port.delayMillis);
port.runScheduled();
assertEquals(1, port.enableCalls);
controller.onBluetoothOn();
assertEquals("RESET_COMPLETE", port.outcome);
```

- [ ] Run tests before implementation and capture expected missing-class compilation failure. Implement `start`, `onAdvertisement`, `onBluetoothOff`, `onBluetoothOn`, `onFailure`, `cancel` with states NEW, SCANNING, WAITING_OFF, OFF_DELAY, WAITING_ON, FINISHED.
- [ ] Add and run tests for scan timeout; exact case-sensitive name; null name; duplicate advertisement; duplicate start; transition timeout; disable/enable rejection; initially off; unexpected off while scanning; cleanup; cancellation after app-requested disable; recovery attempted no more than once; stale timer callbacks ignored. Keep a single additional 15-second late-OFF window after the first disable timeout; only a precise OFF snapshot or OFF event begins the three-second delay, never TURNING_OFF or UNKNOWN.
- [ ] Ensure scheduling cancels old tasks and callbacks check both phase and a generation counter. Set state before any adapter command to tolerate synchronous fake events.

## Task 3: Android adapter, launch and cleanup

- [ ] Implement the port with a Handler on the main Looper. Read only `result.getScanRecord().getDeviceName()` for live matching. Handle batch results, scan failures and null scan records. Register the Bluetooth state receiver before running the controller. Feed OFF and ON events to the controller. Catch permission and adapter runtime failures and report them to the state machine.
- [ ] Request fine-location and Nearby-device permissions as appropriate to OS version; never ask to enable Bluetooth. Check initially disabled Bluetooth before asking scan/location permissions when connect access is available. Do not declare `neverForLocation` (avoid filtering advertisements). On Android 12+, request approximate and precise location together, but require fine location for the chosen unfiltered scan.
- [ ] Use native transparent Activity with `windowDisablePreview`, non-floating/non-translucent-background dim disabled settings, excluding recent tasks. Start work from resumed foreground lifecycle, receive ResultReceiver service-ready callback, then finish. Do not use Theme.NoDisplay for asynchronous work. Finish promptly on permission denial or service-start denial, logging a reason.
- [ ] Use a private `connectedDevice|location` foreground Service and low-importance notification, call `startForeground` promptly with both types, reject repeated starts, and return `START_NOT_STICKY`. The location type keeps foreground location AppOps available for unfiltered scans when the transparent Activity ends; do not request background location. Acquire a time-bounded partial wakelock and release on completion/destruction. Stop scan, remove Handler callbacks, unregister receiver, remove foreground notification and stop service on terminal outcome. Do not force-kill the process.
- [ ] Expose only TriggerActivity, with MAIN/LAUNCHER and action `com.blreay.bluetoothmonitor.RUN`; no arbitrary command/name extras. Declare required permissions and BLE hardware availability.

## Task 4: Actions and documentation

- [ ] Add push/pull_request/workflow_dispatch workflow with `contents: read`, Java17, SDK35/build-tools35, verified wrapper and `./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`. Upload named `bluetooth-monitor-apk` artifact containing `bluetooth-monitor.apk`, SHA256 and test/lint reports. Only upload APK after a successful build.
- [ ] Document artifact installation, target32 sideload rationale, OEM unknowns, first-run permission dialogs, transparent transition/system notification limits and scan visibility/name requirements. Warn debug CI keys vary so uninstall may be required on upgrade; offer fixed signing only as future configuration, never commit a private key.
- [ ] Include external call examples with exact package/class/action:

```java
Intent intent = new Intent("com.blreay.bluetoothmonitor.RUN");
intent.setClassName("com.blreay.bluetoothmonitor", "com.blreay.bluetoothmonitor.TriggerActivity");
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);
```

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.TriggerActivity
adb logcat -s BluetoothMonitor
```

- [ ] Add phone acceptance checklist from the design. State that app cannot guarantee restoration after force-stop/process kill during the off interval, and cannot guarantee arbitrary background apps may launch it.

## Task 5: Verify, review, publish

- [ ] Run controller JVM tests, complete Gradle tests/lint/APK packaging, `git diff --check`, and independent source review. Fix reproducible issues with regression tests before publication.
- [ ] Commit sources and workflow on existing `gpt` branch, no force-push, no main merge. Push `git push origin gpt`, explicitly authorized by user.
- [ ] Read GitHub Actions run status and job steps through `gh`. If unavailable install a standalone GitHub CLI in a user cache; public REST API may be used to observe status if CLI cannot be installed/authenticated. Never expose credentials.
- [ ] Confirm the actual remote SHA and whether CI succeeded, failed or remains running. If failed, inspect build logs, fix, retest and push; report any environmental blocker faithfully. Final answer supplies repo and Actions links, package/invocation info, and notes no phone was attached for real BLE verification.

## Self-review

All confirmed behavior is assigned above: initial-off (Task2/3), live exact name (Task2/3), 15-second scan (Task2), off-confirmed three seconds (Task2), restoration/error/duplicates/cleanup (Task2/3), no ordinary page with first-run/system boundaries (Task3/4), callable icon (Task3/4), APK Actions and push (Task4/5). Port names and values are consistent across tests and adapter. No device-specific compatibility is represented as verified.
