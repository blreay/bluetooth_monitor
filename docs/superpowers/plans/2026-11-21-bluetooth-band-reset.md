# 蓝牙手环重连工具 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build an invisible Android app that, when triggered, BLE-scans for a Xiaomi Smart Band 8 and cycles Bluetooth off/on (3s apart) if the band is visible.

**Architecture:** One exported translucent `Activity` (launcher icon / Tasker / `am start`) and one exported `BroadcastReceiver` (`sendBroadcast`) both delegate to a single UI-free `BandResetController`. After `disable()` succeeds, re-enabling is arranged two ways at once — an in-process `Handler` delay and a system exact alarm — because MIUI may reclaim the process during the 3s wait. `enable()` is idempotent so whichever fires first wins.

**Tech Stack:** Kotlin 1.9.24, AGP 8.4.0, Gradle 8.7, JDK 17, compileSdk 34, **targetSdk 32** (deliberate — see below), minSdk 31, JUnit 4, GitHub Actions. Zero runtime dependencies (no androidx).

**Spec:** `docs/superpowers/specs/2026-11-20-bluetooth-band-reset-design.md`

---

## Read this before starting

### Why targetSdk is 32 and must not be raised

Android 13 made `BluetoothAdapter.enable()` / `disable()` return `false` for any app **targeting API 33 or higher**. The gate is on `targetSdkVersion`, not on the OS version, so `targetSdk 32` running on Android 13/14/15/16 still reaches the legacy path. This is exactly how Tasker's `TaskerSettings` and MacroDroid's Helper App keep working.

Raising `targetSdk` to satisfy a lint warning will **silently break the app's only function**. There is no alternative API: Android offers `ACTION_REQUEST_ENABLE` (a user-consent dialog) but no equivalent for turning Bluetooth *off*.

### Why minSdk is 31

On Android 12+, `BLUETOOTH_SCAN` declared with `android:usesPermissionFlags="neverForLocation"` needs **no location permission at all**. Setting `minSdk 31` deletes the entire `ACCESS_FINE_LOCATION` branch and the legacy `BLUETOOTH`/`BLUETOOTH_ADMIN` declarations. The target device is Android 13+, so nothing is lost.

### Why not a foreground Service

Android 12+ throws `ForegroundServiceStartNotAllowedException` when a background app starts a foreground Service. That would break the `sendBroadcast` entry point outright. A foreground Service would also show a notification, violating "no visible UI".

### Why not `Theme.NoDisplay`

`NoDisplay` requires `finish()` before `onResume()`. Our flow runs ~12s. It must be a translucent theme.

### Testing scope — be honest about this

Only `BandMatcher` is a pure function, so only it gets unit tests. Everything else is bound to the Android Bluetooth/AlarmManager framework; testing it would require Robolectric, which the spec explicitly excludes (zero third-party dependencies). For those classes the verification gate is: **it compiles, the release APK assembles, and `aapt2`/`apkanalyzer` confirms the manifest came out right.** Real behavioural verification happens on the user's phone via the checklist in Task 14. Do not claim the app "works" until that has happened.

### Toolchain reality on this machine

Already probed, do not re-litigate:

| Need | Status |
|---|---|
| `java` | present but **JRE only** — `javac` missing, cannot compile |
| `keytool` | present at `/usr/bin/keytool` — keystore generation works |
| `gradle`, `adb`, Android SDK | absent |
| `github.com` over HTTPS | **blocked** (42s for the root page) |
| `github.com` over **SSH** | works, authenticated as `blreay` |
| `services.gradle.org` | reachable but **19 KB/s** — 134 MB would take ~2h |
| `mirrors.aliyun.com/macports/distfiles/gradle/` | **5.8 MB/s** — use this |
| `mirrors.huaweicloud.com/openjdk/` | HTTP 200, 187 MB |
| `dl.google.com` (Android SDK + Google Maven) | HTTP 200 |
| `repo.maven.apache.org`, `plugins.gradle.org` | HTTP 200 |

Consequence: **build locally with the mirror-downloaded Gradle binary, but commit a wrapper whose `distributionUrl` points at the official `services.gradle.org`** (fast on GitHub runners). Task 3 verifies the wrapper itself end-to-end by temporarily swapping the URL.

All pushes go over SSH (`git@github.com:blreay/bluetooth_monitor`). Directly to `main` — the user approved this.

### File map

```
bluetooth_monitor/
├── .github/workflows/build.yml        CI: assembleRelease → artifact → rolling "latest" Release
├── .gitignore
├── README.md                          install, MIUI setup, trigger commands, log reference
├── build.gradle.kts                   root: plugin versions only
├── settings.gradle.kts                repositories + :app
├── gradle.properties                  jvmargs + keystore credentials
├── gradlew / gradlew.bat              generated in Task 3
├── gradle/wrapper/*                   generated in Task 3 (jar MUST be committed)
├── keystore/release.jks               committed on purpose — stable signature
└── app/
    ├── build.gradle.kts               sdk versions, signing, versionCode from CI
    └── src/
        ├── main/
        │   ├── AndroidManifest.xml    permissions + 1 activity + 2 receivers
        │   ├── java/com/blreay/bluetoothmonitor/
        │   │   ├── BandMatcher.kt         pure match rule          (unit tested)
        │   │   ├── BtPermissions.kt       runtime permission set
        │   │   ├── BluetoothSwitch.kt     ONLY place touching deprecated enable/disable
        │   │   ├── ReEnableScheduler.kt   in-process delay + exact alarm
        │   │   ├── BandResetController.kt the whole flow, UI-free
        │   │   ├── MainActivity.kt        invisible launcher/automation entry
        │   │   ├── TriggerReceiver.kt     sendBroadcast entry
        │   │   └── ReEnableReceiver.kt    alarm target, turns BT back on
        │   └── res/
        │       ├── drawable/ic_launcher_foreground.xml
        │       ├── mipmap-anydpi-v26/ic_launcher.xml
        │       ├── mipmap-anydpi-v26/ic_launcher_round.xml
        │       ├── values/colors.xml
        │       ├── values/strings.xml
        │       └── values/themes.xml
        └── test/java/com/blreay/bluetoothmonitor/
            └── BandMatcherTest.kt
```

Every file has one job. The spec named four classes; this plan splits out `BandMatcher` (so it is testable), `BtPermissions` and `BluetoothSwitch` (each used by two callers), and `ReEnableScheduler` (keeps the alarm/PendingIntent plumbing out of the controller). Same responsibilities, finer boundaries.

### Log tags — used by the README's logcat command, keep them in sync

| Tag | Owner |
|---|---|
| `BtReset.Main` | `MainActivity` |
| `BtReset.Trigger` | `TriggerReceiver` |
| `BtReset.Ctrl` | `BandResetController` |
| `BtReset.Switch` | `BluetoothSwitch` |
| `BtReset.Sched` | `ReEnableScheduler` |
| `BtReset.ReEnable` | `ReEnableReceiver` |

---

## Task 1: Bootstrap the local build toolchain

Nothing in the repo changes here. This exists so every later task can actually compile instead of pushing to CI and hoping.

**Files:**
- Create (outside the repo): `~/.cache/btreset-build/{jdk17,gradle-8.7,android-sdk,env.sh}`

- [ ] **Step 1: Create the toolchain directory**

```bash
mkdir -p ~/.cache/btreset-build && cd ~/.cache/btreset-build && pwd
```

Expected: `/home/admin/.cache/btreset-build`

- [ ] **Step 2: Download and unpack JDK 17**

```bash
cd ~/.cache/btreset-build
curl -fL --retry 3 --max-time 900 -o jdk17.tar.gz \
  https://mirrors.huaweicloud.com/openjdk/17.0.2/openjdk-17.0.2_linux-x64_bin.tar.gz
mkdir -p jdk17
tar -xzf jdk17.tar.gz -C jdk17 --strip-components=1
./jdk17/bin/javac -version
```

Expected: `javac 17.0.2`

If this fails, the machine has no usable JDK and **every later build step is blocked** — stop and report rather than continuing to write unverifiable code.

- [ ] **Step 3: Download and unpack Gradle 8.7**

```bash
cd ~/.cache/btreset-build
curl -fL --retry 3 --max-time 600 -o gradle.zip \
  https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.7-bin.zip
unzip -q gradle.zip
ls -d gradle-8.7
```

Expected: a directory named `gradle-8.7`

Do **not** use `https://services.gradle.org/distributions/gradle-8.7-bin.zip` here — it redirects to GitHub release assets which stream at ~19 KB/s from this machine.

- [ ] **Step 4: Download and unpack the Android command-line tools**

```bash
cd ~/.cache/btreset-build
curl -fL --retry 3 --max-time 900 -o cmdline-tools.zip \
  https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
mkdir -p android-sdk/cmdline-tools
unzip -q cmdline-tools.zip -d android-sdk/cmdline-tools
mv android-sdk/cmdline-tools/cmdline-tools android-sdk/cmdline-tools/latest
ls android-sdk/cmdline-tools/latest/bin
```

Expected: `sdkmanager` (and `avdmanager`, `lint`, `retrace`, ...) listed

- [ ] **Step 5: Write the shared environment file**

Create `~/.cache/btreset-build/env.sh` with exactly:

```bash
export JAVA_HOME="$HOME/.cache/btreset-build/jdk17"
export ANDROID_HOME="$HOME/.cache/btreset-build/android-sdk"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export BTRESET_GRADLE="$HOME/.cache/btreset-build/gradle-8.7/bin/gradle"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/cmdline-tools/latest/bin:$ANDROID_HOME/platform-tools:$PATH"
```

Shell state does not persist between commands, so **every later step must start with** `source ~/.cache/btreset-build/env.sh`.

- [ ] **Step 6: Accept SDK licences and install the needed packages**

```bash
source ~/.cache/btreset-build/env.sh
yes | sdkmanager --licenses > /dev/null 2>&1
sdkmanager "platform-tools" "platforms;android-34" "build-tools;34.0.0" 2>&1 | tail -5
```

Expected: lines ending in `done` / `100%`, no `Failed to find package` error

- [ ] **Step 7: Verify the whole toolchain**

```bash
source ~/.cache/btreset-build/env.sh
java -version 2>&1 | head -1
javac -version
"$BTRESET_GRADLE" --version 2>&1 | grep -E 'Gradle|JVM:'
sdkmanager --list_installed 2>/dev/null | grep -E 'android-34|build-tools;34'
```

Expected: `openjdk version "17.0.2"`, `javac 17.0.2`, `Gradle 8.7`, `JVM: 17.0.2`, and rows for `platforms;android-34` and `build-tools;34.0.0`

---

## Task 2: Generate and commit the signing keystore

Committed on purpose. CI would otherwise mint a fresh random debug keystore per run, giving every APK a different signature — Android would then force a full uninstall/reinstall and the user would re-grant the Nearby-devices permission every single time.

**Files:**
- Create: `keystore/release.jks`
- Create: `gradle.properties`

- [ ] **Step 1: Generate the keystore**

```bash
cd /home/admin/git/bluetooth_monitor
mkdir -p keystore
keytool -genkeypair -v \
  -keystore keystore/release.jks \
  -storetype JKS \
  -alias btreset \
  -keyalg RSA -keysize 2048 \
  -validity 10950 \
  -storepass btreset-local-only \
  -keypass btreset-local-only \
  -dname "CN=blreay, OU=personal, O=blreay, L=local, ST=local, C=CN"
keytool -list -v -keystore keystore/release.jks -storepass btreset-local-only 2>&1 | grep -E 'Alias|SHA256:'
```

Expected: `Alias name: btreset` and a `SHA256:` fingerprint

30 years validity — this key must outlive the app or reinstalls lose permissions.

- [ ] **Step 2: Write `gradle.properties`**

Create `gradle.properties` at the repo root with exactly:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
android.useAndroidX=true
android.nonTransitiveRClass=true
kotlin.code.style=official

# Signing credentials for keystore/release.jks, which is committed to this
# repo on purpose. The key signs exactly one private utility and asserts no
# identity; committing it keeps the APK signature stable across builds. An
# unstable signature forces uninstall/reinstall, which loses the runtime
# Nearby-devices permission grant every time.
BT_RESET_STORE_FILE=keystore/release.jks
BT_RESET_STORE_PASSWORD=btreset-local-only
BT_RESET_KEY_ALIAS=btreset
BT_RESET_KEY_PASSWORD=btreset-local-only
```

`android.useAndroidX=true` is set even though there are no androidx dependencies — it is the AGP 8 default and avoids a warning.

- [ ] **Step 3: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add keystore/release.jks gradle.properties
git status --short
git commit -m "$(cat <<'EOF'
build: add stable release keystore and gradle properties

The keystore is committed deliberately. CI would otherwise generate a
fresh random debug keystore on every run, so each APK would carry a
different signature and Android would refuse to overwrite-install -
forcing a reinstall and a re-grant of the Nearby-devices permission
every time. This key asserts no identity; it signs one private utility.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: 2 files committed

---

## Task 3: Gradle skeleton and wrapper

**Files:**
- Create: `settings.gradle.kts`
- Create: `build.gradle.kts`
- Create: `app/build.gradle.kts`
- Create: `.gitignore`
- Create: `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`

- [ ] **Step 1: Write `.gitignore`**

Create `.gitignore` with exactly:

```
# Gradle
.gradle/
build/
*/build/

# Android
local.properties
*.apk.idsig

# IDE
.idea/
*.iml
.DS_Store

# Deliberately NOT ignored:
#   keystore/release.jks                  - stable signature, see gradle.properties
#   gradle/wrapper/gradle-wrapper.jar     - CI needs it to run ./gradlew
```

- [ ] **Step 2: Write `settings.gradle.kts`**

```kotlin
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "bluetooth_monitor"
include(":app")
```

- [ ] **Step 3: Write the root `build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application") version "8.4.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
```

- [ ] **Step 4: Write `app/build.gradle.kts`**

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// versionCode must rise on every CI build or Android refuses to
// overwrite-install the new APK. Locally there is no run number, so it is 1.
val ciRunNumber: Int? = providers.environmentVariable("GITHUB_RUN_NUMBER").orNull?.toIntOrNull()
val autoVersionCode: Int = (ciRunNumber ?: 0) + 1

fun signingProp(name: String): String = providers.gradleProperty(name).orNull ?: ""

android {
    namespace = "com.blreay.bluetoothmonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blreay.bluetoothmonitor"
        minSdk = 31
        // PINNED TO 32 ON PURPOSE.
        // Android 13 made BluetoothAdapter.enable()/disable() return false for
        // anything targeting API 33+. This app's only function depends on them.
        // Do not raise this without replacing the entire toggle mechanism.
        targetSdk = 32
        versionCode = autoVersionCode
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            storeFile = rootProject.file(signingProp("BT_RESET_STORE_FILE"))
            storePassword = signingProp("BT_RESET_STORE_PASSWORD")
            keyAlias = signingProp("BT_RESET_KEY_ALIAS")
            keyPassword = signingProp("BT_RESET_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            // R8 adds failure modes and buys nothing at this size.
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // lintVitalRelease runs as part of assembleRelease. With targetSdk
        // deliberately pinned to 32, ExpiredTargetSdkVersion would fire on
        // every single build and could fail the release assembly.
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
```

Note there are **no** `implementation` dependencies. The app uses only the Android SDK and the Kotlin standard library, per the spec.

- [ ] **Step 5: Generate the Gradle wrapper with the official distribution URL**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" wrapper --gradle-version 8.7 --distribution-type bin 2>&1 | tail -5
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
ls -la gradlew gradle/wrapper/gradle-wrapper.jar
```

Expected: `distributionUrl=https\://services.gradle.org/distributions/gradle-8.7-bin.zip`, and both `gradlew` and `gradle-wrapper.jar` present (jar is roughly 43 KB)

- [ ] **Step 6: Prove `./gradlew` actually works, without the 2-hour download**

`services.gradle.org` redirects to GitHub release assets, which stream at ~19 KB/s from this machine. Temporarily point the wrapper at the fast mirror, run it, then restore the official URL. The wrapper jar and scripts are identical either way — only the properties line differs.

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
cp gradle/wrapper/gradle-wrapper.properties /tmp/gwp.official
sed -i 's#https\\://services.gradle.org/distributions/gradle-8.7-bin.zip#https\\://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.7-bin.zip#' \
  gradle/wrapper/gradle-wrapper.properties
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
./gradlew --version 2>&1 | grep -E 'Gradle|JVM:'
cp /tmp/gwp.official gradle/wrapper/gradle-wrapper.properties
grep distributionUrl gradle/wrapper/gradle-wrapper.properties
```

Expected: the first `grep` shows the aliyun URL, `./gradlew --version` prints `Gradle 8.7` and `JVM: 17.0.2`, and the final `grep` shows `services.gradle.org` restored.

If `./gradlew --version` fails here, the wrapper is broken and CI will fail too — fix before continuing.

- [ ] **Step 7: Verify AGP and Kotlin resolve from the reachable Maven mirrors**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:tasks --console=plain 2>&1 | tail -20
```

Expected: a task list including `assembleRelease` and `testDebugUnitTest`. This is the step that downloads AGP 8.4.0 and Kotlin 1.9.24, so it is slow the first time.

A failure here means a repository is unreachable — check that `settings.gradle.kts` has both `google()` and `mavenCentral()`.

- [ ] **Step 8: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add .gitignore settings.gradle.kts build.gradle.kts app/build.gradle.kts gradlew gradlew.bat gradle/
git status --short
git commit -m "$(cat <<'EOF'
build: add Gradle skeleton pinned to targetSdk 32

targetSdk 32 is load-bearing, not stale: Android 13 made
BluetoothAdapter.enable()/disable() return false for apps targeting
API 33+, and cycling Bluetooth is this app's only function. The
constraint is documented at the declaration site so it survives a
well-meaning future bump.

lint.checkReleaseBuilds is disabled because lintVitalRelease runs
inside assembleRelease and ExpiredTargetSdkVersion would fire on
every build by design.

No implementation dependencies: Android SDK + Kotlin stdlib only.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

Expected: `gradle/wrapper/gradle-wrapper.jar` appears in the committed file list. **If it does not, CI cannot run `./gradlew` — stop and fix.**

---

## Task 4: Resources and the permissions-only manifest

Deliberately contains no `<activity>` or `<receiver>` yet — those are added in Task 11, once the classes they name exist. Building here proves the whole toolchain end-to-end before any real Kotlin is written.

**Files:**
- Create: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/res/values/strings.xml`
- Create: `app/src/main/res/values/colors.xml`
- Create: `app/src/main/res/values/themes.xml`
- Create: `app/src/main/res/drawable/ic_launcher_foreground.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`
- Create: `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`

- [ ] **Step 1: Write `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <!--
      neverForLocation is what removes the ACCESS_FINE_LOCATION requirement:
      we want the band's identity, not a location proxy. minSdk 31 means the
      legacy BLUETOOTH / BLUETOOTH_ADMIN install-time permissions are not
      needed either.
    -->
    <uses-permission
        android:name="android.permission.BLUETOOTH_SCAN"
        android:usesPermissionFlags="neverForLocation" />
    <uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
    <!--
      Granted by default at targetSdk 32. Android 13's deny-by-default only
      applies to apps targeting 33+. Checked at runtime anyway, with an
      in-process fallback if it is missing.
    -->
    <uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

    <uses-feature
        android:name="android.hardware.bluetooth_le"
        android:required="true" />

    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="false">

        <!-- Components are declared in Task 11, once the classes exist. -->

    </application>
</manifest>
```

`allowBackup="false"` also removes the need for `fullBackupContent` / `dataExtractionRules`.

- [ ] **Step 2: Write `app/src/main/res/values/strings.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">蓝牙重启</string>
</resources>
```

- [ ] **Step 3: Write `app/src/main/res/values/colors.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1565C0</color>
</resources>
```

- [ ] **Step 4: Write `app/src/main/res/values/themes.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <!--
      The invisible Activity theme.

      windowIsTranslucent is what suppresses the Android 12+ system splash
      icon; there is no supported way to disable that splash outright, so the
      residual effect is a ~100-300ms window in which the screen behind does
      not respond to touches. Nothing this app draws is ever visible.

      backgroundDimEnabled=false matters: a translucent Activity dims what is
      behind it by default, and that dim WOULD be visible.

      windowAnimationStyle=@null removes the Activity transition animation.
      Transparent system bars stop a flicker when the previous window used
      different bar colours.

      Theme.NoDisplay cannot be used here - it requires finish() before
      onResume(), which would abort the ~12s scan-and-cycle flow.
    -->
    <style name="Theme.BtReset.Invisible" parent="@android:style/Theme.Translucent.NoTitleBar">
        <item name="android:windowBackground">@android:color/transparent</item>
        <item name="android:windowIsTranslucent">true</item>
        <item name="android:windowNoTitle">true</item>
        <item name="android:windowContentOverlay">@null</item>
        <item name="android:windowAnimationStyle">@null</item>
        <item name="android:backgroundDimEnabled">false</item>
        <item name="android:statusBarColor">@android:color/transparent</item>
        <item name="android:navigationBarColor">@android:color/transparent</item>
    </style>
</resources>
```

- [ ] **Step 5: Write `app/src/main/res/drawable/ic_launcher_foreground.xml`**

Adaptive-icon foreground: 108×108 viewport, the 24-unit Bluetooth glyph scaled ×3 and centred, which puts it in [18, 90] — inside the 66dp safe zone.

```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <group
        android:scaleX="3"
        android:scaleY="3"
        android:translateX="18"
        android:translateY="18">
        <path
            android:fillColor="#FFFFFFFF"
            android:pathData="M17.71,7.71L12,2h-1v7.59L6.41,5L5,6.41L10.59,12L5,17.59L6.41,19L11,14.41V22h1l5.71,-5.71l-4.3,-4.29 4.3,-4.29zM13,5.83l1.88,1.88L13,9.59L13,11.41V5.83z" />
    </group>
</vector>
```

- [ ] **Step 6: Write `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

- [ ] **Step 7: Write `app/src/main/res/mipmap-anydpi-v26/ic_launcher_round.xml`**

Identical content, separate file because the manifest references both:

```xml
<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background" />
    <foreground android:drawable="@drawable/ic_launcher_foreground" />
</adaptive-icon>
```

No PNG fallbacks are needed: `minSdk 31` is above the `anydpi-v26` qualifier, so the adaptive icon always resolves.

- [ ] **Step 8: Build to prove the toolchain works end-to-end**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:assembleDebug --console=plain 2>&1 | tail -15
ls -la app/build/outputs/apk/debug/
```

Expected: `BUILD SUCCESSFUL`, and `app-debug.apk` present

This is the first real compile. It downloads the Android 34 platform metadata and compiles an (empty) app. If it fails, fix the toolchain now — do not write Kotlin on top of a broken build.

- [ ] **Step 9: Verify the manifest and icon landed correctly in the APK**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
"$AAPT2" dump badging app/build/outputs/apk/debug/app-debug.apk 2>&1 \
  | grep -E 'package:|sdkVersion|targetSdkVersion|uses-permission|application-label|launchable'
```

Expected output must show:
- `sdkVersion:'31'`
- `targetSdkVersion:'32'`  ← **the critical line; if this says 33 or higher the app cannot toggle Bluetooth**
- `uses-permission: name='android.permission.BLUETOOTH_SCAN'`
- `uses-permission: name='android.permission.BLUETOOTH_CONNECT'`
- `uses-permission: name='android.permission.SCHEDULE_EXACT_ALARM'`
- `application-label:'蓝牙重启'`

No `launchable-activity` yet — that appears after Task 11.

- [ ] **Step 10: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/AndroidManifest.xml app/src/main/res/
git commit -m "$(cat <<'EOF'
feat: add invisible-activity theme, adaptive icon and BT permissions

BLUETOOTH_SCAN carries neverForLocation, which together with minSdk 31
removes any need for ACCESS_FINE_LOCATION.

The theme is translucent rather than NoDisplay because NoDisplay
requires finish() before onResume and would abort the 12s flow.
backgroundDimEnabled=false is load-bearing: without it a translucent
activity visibly dims the screen behind it.

Icon is pure XML - minSdk 31 is above the anydpi-v26 qualifier so no
PNG fallbacks are required.

Components are not declared yet; that happens once the classes exist.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 5: `BandMatcher` — TDD

The only pure logic in the app, so the only part that gets real tests.

The match rule exists because `35BA` is the last two octets of the band's MAC address — Xiaomi bands append them to the advertised name. That makes the **MAC the more reliable key**: BLE advertising truncates long names and may put the name in the scan response instead of the primary advertisement.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/BandMatcher.kt`
- Test: `app/src/test/java/com/blreay/bluetoothmonitor/BandMatcherTest.kt`

- [ ] **Step 1: Write the failing test**

Create `app/src/test/java/com/blreay/bluetoothmonitor/BandMatcherTest.kt`:

```kotlin
package com.blreay.bluetoothmonitor

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The band's advertised name ends in 35BA, which is the last two octets of
 * its MAC address. That makes the MAC the more reliable key: BLE advertising
 * truncates long names and may carry the name in the scan response instead of
 * the primary advertisement.
 */
class BandMatcherTest {

    private val bandMac = "AA:BB:CC:DD:35:BA"
    private val unrelatedMac = "AA:BB:CC:DD:11:22"

    @Test
    fun `full advertised name matches`() {
        assertTrue(BandMatcher.isTarget(bandMac, "Xiaomi Smart Band 8 35BA"))
    }

    @Test
    fun `full name matches even when the mac is unrelated`() {
        assertTrue(BandMatcher.isTarget(unrelatedMac, "Xiaomi Smart Band 8 35BA"))
    }

    @Test
    fun `name truncated by advertising still matches via mac suffix`() {
        assertTrue(BandMatcher.isTarget(bandMac, "Xiaomi Smart Band 8"))
    }

    @Test
    fun `name carried only in scan response still matches via mac suffix`() {
        assertTrue(BandMatcher.isTarget(bandMac, null))
    }

    @Test
    fun `lowercase mac still matches`() {
        assertTrue(BandMatcher.isTarget("aa:bb:cc:dd:35:ba", "Xiaomi Smart Band 8"))
    }

    @Test
    fun `a different band does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, "Xiaomi Smart Band 7 1122"))
    }

    @Test
    fun `same mac suffix but a different product line does not match`() {
        assertFalse(BandMatcher.isTarget(bandMac, "Mi Wireless Earbuds"))
    }

    @Test
    fun `an unrelated named device does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, "Some Random Speaker"))
    }

    @Test
    fun `an unrelated anonymous device does not match`() {
        assertFalse(BandMatcher.isTarget(unrelatedMac, null))
    }

    @Test
    fun `no information at all does not match`() {
        assertFalse(BandMatcher.isTarget(null, null))
    }
}
```

- [ ] **Step 2: Run the test and confirm it fails for the right reason**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:testDebugUnitTest --console=plain 2>&1 | tail -20
```

Expected: **FAIL** — `unresolved reference: BandMatcher` (a compilation failure, not an assertion failure). That is correct at this point: the class does not exist yet.

- [ ] **Step 3: Write the implementation**

Create `app/src/main/java/com/blreay/bluetoothmonitor/BandMatcher.kt`:

```kotlin
package com.blreay.bluetoothmonitor

/**
 * Decides whether a scanned BLE device is the band this app cares about.
 *
 * Kept free of Android types so it can be unit tested on the JVM. Callers
 * pass in the two Strings they have already extracted from a ScanResult.
 */
object BandMatcher {

    /** Full advertised name of the target band. */
    const val TARGET_NAME = "Xiaomi Smart Band 8 35BA"

    /** Prefix shared by every Xiaomi Smart Band advertisement. */
    const val TARGET_NAME_PREFIX = "Xiaomi Smart Band"

    /**
     * Last two octets of the band's MAC address. Xiaomi bands append these to
     * the advertised name, so the MAC survives cases where the name is
     * truncated by advertising limits or moved to the scan response.
     */
    const val TARGET_MAC_SUFFIX = "35:BA"

    /**
     * @param mac  device address as reported by `BluetoothDevice.getAddress`,
     *             upper case and colon separated; may be null
     * @param name advertised name, or null when the advertisement carried none
     */
    fun isTarget(mac: String?, name: String?): Boolean {
        // A full name match is decisive on its own.
        if (name != null && name.contains(TARGET_NAME)) return true

        // Otherwise fall back to the MAC, which is the more stable key.
        if (mac == null || !mac.endsWith(TARGET_MAC_SUFFIX, ignoreCase = true)) return false

        // MAC matches. Only reject when a name is present and clearly belongs
        // to a different product line - a null name is still a hit.
        return name == null || name.startsWith(TARGET_NAME_PREFIX, ignoreCase = true)
    }
}
```

- [ ] **Step 4: Run the test and confirm it passes**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:testDebugUnitTest --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, 10 tests, 0 failures

If any test fails, fix `BandMatcher` — do **not** weaken the test. Each test encodes a real advertising behaviour.

- [ ] **Step 5: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/BandMatcher.kt \
        app/src/test/java/com/blreay/bluetoothmonitor/BandMatcherTest.kt
git commit -m "$(cat <<'EOF'
feat: match the band by MAC suffix, not just advertised name

35BA is the last two octets of the band's MAC address; Xiaomi bands
append them to the advertised name. The MAC is therefore the more
reliable key, because BLE advertising truncates long names and may
carry the name in the scan response rather than the primary
advertisement. A full-name match still wins outright.

Kept as a pure object with no Android types so it is unit testable on
the JVM - this is the only logic in the app that is.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 6: `BtPermissions` and `BluetoothSwitch`

`BluetoothSwitch` is the **only** file in the app that touches the deprecated `enable()`/`disable()`. Concentrating them means the `@Suppress("DEPRECATION")` and the "did the OEM refuse?" handling live in one place.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/BtPermissions.kt`
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt`

No unit tests: both are thin wrappers over framework APIs and would need Robolectric, which the spec excludes. The gate is that they compile.

- [ ] **Step 1: Write `BtPermissions.kt`**

```kotlin
package com.blreay.bluetoothmonitor

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager

/**
 * The Nearby-devices runtime permissions this app needs.
 *
 * minSdk is 31, so BLUETOOTH_SCAN (declared with neverForLocation) plus
 * BLUETOOTH_CONNECT is the complete set - ACCESS_FINE_LOCATION is never
 * required and the legacy BLUETOOTH / BLUETOOTH_ADMIN permissions do not
 * apply.
 *
 * These cannot be granted silently at install time. The launcher-icon path
 * prompts once; the broadcast path cannot prompt from the background and
 * simply gives up.
 */
object BtPermissions {

    val REQUIRED: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun allGranted(context: Context): Boolean = missing(context).isEmpty()

    fun missing(context: Context): List<String> = REQUIRED.filter {
        context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }
}
```

`Context.checkSelfPermission` is API 23+, so no androidx `ContextCompat` is needed.

- [ ] **Step 2: Write `BluetoothSwitch.kt`**

```kotlin
package com.blreay.bluetoothmonitor

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log

/**
 * The only place in the app that touches the deprecated
 * [BluetoothAdapter.enable] and [BluetoothAdapter.disable].
 *
 * Android 13 closed these to third-party apps targeting API 33+; this app
 * targets 32 specifically to keep them. They can still return false when the
 * OEM refuses, so every caller must check the result rather than assume the
 * radio actually changed state.
 */
object BluetoothSwitch {

    private const val TAG = "BtReset.Switch"

    fun adapter(context: Context): BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /**
     * Turns Bluetooth on if it is currently off. Idempotent, so the in-process
     * timer and the system alarm can both call it without coordinating.
     */
    fun enableIfOff(context: Context) {
        val adapter = adapter(context)
        if (adapter == null) {
            Log.e(TAG, "no BluetoothAdapter; cannot re-enable")
            return
        }
        if (adapter.isEnabled) {
            Log.i(TAG, "Bluetooth already on; nothing to do")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED
        ) {
            Log.e(TAG, "BLUETOOTH_CONNECT not granted; Bluetooth is OFF and needs a manual toggle")
            return
        }
        @Suppress("DEPRECATION")
        val ok = try {
            adapter.enable()
        } catch (t: Throwable) {
            Log.e(TAG, "enable() threw", t)
            false
        }
        if (ok) {
            Log.i(TAG, "Bluetooth turned back on")
        } else {
            Log.e(TAG, "enable() returned false; Bluetooth is OFF and needs a manual toggle")
        }
    }

    /**
     * @return true only if the system accepted the request. A false result
     *         means Bluetooth is still ON, so callers must not schedule a
     *         re-enable - that is the invariant which keeps this app from
     *         ever leaving the radio in the wrong state.
     */
    fun disable(adapter: BluetoothAdapter): Boolean {
        @Suppress("DEPRECATION")
        return try {
            adapter.disable()
        } catch (t: Throwable) {
            Log.e(TAG, "disable() threw", t)
            false
        }
    }
}
```

- [ ] **Step 3: Compile**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:compileDebugKotlin --console=plain 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`. A deprecation *warning* about `enable`/`disable` must **not** appear — it is suppressed at the call sites. If it does appear, the `@Suppress("DEPRECATION")` is misplaced; it must be on the statement, not the function.

- [ ] **Step 4: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/BtPermissions.kt \
        app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt
git commit -m "$(cat <<'EOF'
feat: isolate the deprecated Bluetooth enable/disable calls

BluetoothSwitch is the only file touching enable()/disable(), so the
deprecation suppression and the "did the OEM refuse?" handling live in
one place. disable() documents the invariant that callers must not
schedule a re-enable unless it returned true - that is what stops the
app from ever leaving the radio off.

enableIfOff() is idempotent so the in-process timer and the system
alarm can both call it without coordinating.

BtPermissions needs no androidx ContextCompat: minSdk 31 is well past
Context.checkSelfPermission (API 23).

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 7: `ReEnableScheduler` and `ReEnableReceiver`

Two independent re-enable mechanisms, on purpose:

- **in-process `Handler.postDelayed(3000)`** — precise, and it works because `MainActivity` stays alive for the whole flow
- **system exact alarm at +3500ms** — still fires when MIUI has reclaimed the process

`enable()` is idempotent, so whichever fires first wins and the other is a no-op. The broadcast path depends **entirely** on the alarm, because the process can be reclaimed the moment `goAsync()` ends.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/ReEnableScheduler.kt`
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/ReEnableReceiver.kt`

- [ ] **Step 1: Write `ReEnableReceiver.kt`**

Written first because the scheduler references its action constant.

```kotlin
package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fires from the alarm [ReEnableScheduler] set and makes sure Bluetooth is
 * back on.
 *
 * Declared in the manifest rather than registered at runtime, so the system
 * can start the process to deliver the alarm even after MIUI has reclaimed
 * it. Reached only by an explicit PendingIntent aimed at this class, which is
 * why exported=false is safe and why the Android 8+ implicit-broadcast ban
 * does not apply.
 */
class ReEnableReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REENABLE) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        Log.i(TAG, "alarm fired; ensuring Bluetooth is back on")
        BluetoothSwitch.enableIfOff(context)
    }

    companion object {
        const val ACTION_REENABLE = "com.blreay.bluetoothmonitor.action.REENABLE"
        private const val TAG = "BtReset.ReEnable"
    }
}
```

- [ ] **Step 2: Write `ReEnableScheduler.kt`**

```kotlin
package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.util.Log

/**
 * Arranges for Bluetooth to be switched back on after
 * [BandResetController] has turned it off.
 *
 * Two mechanisms are scheduled at once, deliberately:
 *
 *  - an in-process [Handler] delay, which is precise and works while the
 *    calling Activity is still alive;
 *  - an exact alarm delivered by the system, which still fires when MIUI has
 *    reclaimed the process during the wait.
 *
 * [BluetoothSwitch.enableIfOff] is idempotent, so whichever fires first wins
 * and the other is a harmless no-op.
 *
 * The broadcast path relies on the alarm alone: once goAsync() ends the
 * process can be reclaimed at any moment, taking the Handler with it.
 */
object ReEnableScheduler {

    /** Delay for the in-process fast path. This is the spec's "wait 3 seconds". */
    const val IN_PROCESS_DELAY_MS = 3_000L

    /**
     * Delay for the system alarm. Slightly later than the in-process path so
     * the precise one normally wins the race.
     */
    const val ALARM_DELAY_MS = 3_500L

    private const val TAG = "BtReset.Sched"
    private const val REQUEST_CODE = 1

    /**
     * @param context must be an application context - the PendingIntent and
     *                the posted Runnable both outlive the caller
     * @param handler main-looper handler owned by the caller's session
     */
    fun schedule(context: Context, handler: Handler) {
        handler.postDelayed({
            Log.i(TAG, "in-process re-enable firing")
            BluetoothSwitch.enableIfOff(context)
        }, IN_PROCESS_DELAY_MS)

        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            Log.w(TAG, "AlarmManager unavailable; only the in-process path can re-enable Bluetooth")
            return
        }

        val triggerAt = System.currentTimeMillis() + ALARM_DELAY_MS
        val pending = reEnablePendingIntent(context)

        if (am.canScheduleExactAlarms()) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.i(TAG, "exact alarm set for +${ALARM_DELAY_MS}ms")
        } else {
            // SCHEDULE_EXACT_ALARM is granted by default at targetSdk 32, so
            // this should not happen. If it does, the broadcast path is at
            // risk of leaving Bluetooth off, and the log says so loudly.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.e(
                TAG,
                "exact alarms not permitted; inexact alarm may fire minutes late - " +
                    "Bluetooth could stay OFF if this process is killed"
            )
        }
    }

    // No cancel() on purpose. The flow never schedules a re-enable unless
    // disable() returned true, and FLAG_UPDATE_CURRENT with a fixed
    // REQUEST_CODE means a second run simply replaces the first alarm.
    private fun reEnablePendingIntent(context: Context): PendingIntent {
        // Explicit intent to the class: exempt from the Android 8+ ban on
        // implicit broadcasts to manifest-declared receivers.
        val intent = Intent(context, ReEnableReceiver::class.java)
            .setAction(ReEnableReceiver.ACTION_REENABLE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_IMMUTABLE is mandatory on API 31+, which is our minSdk.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

`canScheduleExactAlarms()` is API 31+, matching `minSdk 31`, so no version guard is needed.

- [ ] **Step 3: Compile**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:compileDebugKotlin --console=plain 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/ReEnableReceiver.kt \
        app/src/main/java/com/blreay/bluetoothmonitor/ReEnableScheduler.kt
git commit -m "$(cat <<'EOF'
feat: schedule Bluetooth re-enable via handler delay and exact alarm

Both fire, on purpose. The in-process Handler is precise and works
while MainActivity is alive; the exact alarm still fires when MIUI has
reclaimed the process during the 3s wait. enableIfOff() is idempotent
so whichever lands first wins and the other is a no-op.

The broadcast path depends on the alarm alone - once goAsync() ends the
process can be reclaimed at any moment, taking the Handler with it.

The receiver is manifest-declared so the system can start the process
to deliver the alarm, and is reached only by an explicit PendingIntent
aimed at the class, which keeps exported=false safe and sidesteps the
Android 8+ implicit-broadcast ban.

Falls back to an inexact alarm if canScheduleExactAlarms() is false,
logging loudly because that leaves the broadcast path at risk.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 8: `BandResetController`

The whole flow, with no UI dependency, so both entry points share one implementation and cannot drift apart.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/BandResetController.kt`

- [ ] **Step 1: Write the controller**

```kotlin
package com.blreay.bluetoothmonitor

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * The entire point of the app: find the band, then cycle Bluetooth.
 *
 * Deliberately free of any UI dependency so [MainActivity] and
 * [TriggerReceiver] share one implementation and cannot drift apart. The
 * caller supplies the scan window: the Activity can afford 8s, the broadcast
 * receiver must fit inside goAsync()'s ~10s budget and uses 6s.
 *
 * Callback contract: [Callback.onResult] fires exactly once and means "the
 * decision phase is finished". For [Result.RESET_TRIGGERED] it does NOT mean
 * Bluetooth is back on yet - the caller must stay alive for
 * [ReEnableScheduler.IN_PROCESS_DELAY_MS] to let the in-process path run,
 * otherwise the process loses foreground priority and MIUI may reclaim it
 * mid-wait. [MainActivity] does this; [TriggerReceiver] cannot, and relies on
 * the alarm instead.
 */
// `context` must be an application context: the scan callback and the
// re-enable Runnable both outlive whichever caller supplied it.
class BandResetController(private val context: Context) {

    enum class Result {
        /** This device has no Bluetooth radio. */
        NO_ADAPTER,

        /** Bluetooth was already off. Per spec, do nothing and exit. */
        BLUETOOTH_OFF,

        /** A Nearby-devices runtime permission is not granted. */
        NEEDS_PERMISSION,

        /** The scan window elapsed without the band becoming visible. */
        NOT_FOUND,

        /**
         * The band was found but the system refused to turn Bluetooth off.
         * Bluetooth is still ON. No re-enable was scheduled.
         */
        DISABLE_FAILED,

        /** Band found, Bluetooth turned off, re-enable scheduled two ways. */
        RESET_TRIGGERED,

        /** Something unexpected threw. Details are in logcat. */
        ERROR,
    }

    fun interface Callback {
        fun onResult(result: Result)
    }

    /**
     * Starts the flow. Returns immediately; the outcome arrives on
     * [callback], on the main thread, exactly once.
     *
     * @param scanTimeoutMs how long to scan before reporting NOT_FOUND. The
     *        Activity can afford 8s; the broadcast receiver must fit inside
     *        goAsync()'s ~10s budget and uses 6s.
     */
    fun run(scanTimeoutMs: Long, callback: Callback) {
        try {
            val adapter = BluetoothSwitch.adapter(context)
            if (adapter == null) {
                Log.e(TAG, "no BluetoothAdapter; this device has no Bluetooth radio")
                callback.onResult(Result.NO_ADAPTER)
                return
            }
            if (!adapter.isEnabled) {
                Log.i(TAG, "Bluetooth is off; exiting without doing anything")
                callback.onResult(Result.BLUETOOTH_OFF)
                return
            }
            if (!BtPermissions.allGranted(context)) {
                Log.w(TAG, "missing permission(s) ${BtPermissions.missing(context)}; cannot scan")
                callback.onResult(Result.NEEDS_PERMISSION)
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null even though Bluetooth reports enabled")
                callback.onResult(Result.ERROR)
                return
            }
            Session(adapter, scanner, callback).start(scanTimeoutMs)
        } catch (t: Throwable) {
            Log.e(TAG, "controller failed before scanning", t)
            callback.onResult(Result.ERROR)
        }
    }

    /**
     * One scan-and-cycle attempt. Holds the mutable "have I already reported
     * a result" state so the timeout, the scan callback and a scan failure
     * cannot race into reporting twice.
     */
    private inner class Session(
        private val adapter: BluetoothAdapter,
        private val scanner: BluetoothLeScanner,
        private val callback: Callback,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var settled = false

        private val onTimeout = Runnable {
            if (settled) return@Runnable
            Log.i(TAG, "scan window elapsed; band is not visible")
            teardown()
            emit(Result.NOT_FOUND)
        }

        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (settled) return
                val mac = result.device?.address
                val name = advertisedName(result)
                Log.d(TAG, "seen $mac name=${name ?: "<none>"}")
                if (!BandMatcher.isTarget(mac, name)) return
                Log.i(TAG, "band is visible: $mac name=${name ?: "<none>"}")
                teardown()
                cycleBluetooth()
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                if (settled) return
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                if (settled) return
                Log.e(TAG, "BLE scan failed errorCode=$errorCode")
                if (errorCode == SCAN_FAILED_APPLICATION_REGISTRATION_FAILED) {
                    Log.e(TAG, "registration failed - Android allows only 5 scans per 30s; " +
                        "wait 30s and retry, this is throttling not a bug")
                }
                teardown()
                emit(Result.ERROR)
            }
        }

        fun start(scanTimeoutMs: Long) {
            try {
                val settings = ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                    .build()
                // No hardware ScanFilter on purpose: name filters are
                // unreliable when the name is truncated by advertising limits
                // or carried in the scan response instead.
                scanner.startScan(null, settings, scanCallback)
            } catch (t: Throwable) {
                Log.e(TAG, "startScan threw", t)
                emit(Result.ERROR)
                return
            }
            handler.postDelayed(onTimeout, scanTimeoutMs)
            Log.i(TAG, "BLE scan started, window=${scanTimeoutMs}ms")
        }

        /**
         * The advertised name, trying both sources. The scan record carries
         * what was actually broadcast; BluetoothDevice.getName() reads the
         * cache, needs BLUETOOTH_CONNECT, and can throw.
         */
        private fun advertisedName(result: ScanResult): String? {
            val fromRecord = try {
                result.scanRecord?.deviceName
            } catch (t: Throwable) {
                null
            }
            if (fromRecord != null) return fromRecord
            return try {
                (result.device as BluetoothDevice?)?.name
            } catch (t: Throwable) {
                null
            }
        }

        private fun teardown() {
            handler.removeCallbacks(onTimeout)
            try {
                scanner.stopScan(scanCallback)
            } catch (t: Throwable) {
                // Already-terminated scans throw here; harmless.
                Log.w(TAG, "stopScan threw", t)
            }
        }

        private fun cycleBluetooth() {
            if (!BluetoothSwitch.disable(adapter)) {
                // Bluetooth never went off, so nothing must be scheduled.
                // Scheduling a re-enable here would be a no-op at best and
                // would mask the real failure at worst.
                Log.e(TAG, "system refused to turn Bluetooth off; leaving it ON and exiting")
                emit(Result.DISABLE_FAILED)
                return
            }
            Log.i(TAG, "Bluetooth is now off; re-enable scheduled two ways")
            ReEnableScheduler.schedule(context, handler)
            emit(Result.RESET_TRIGGERED)
        }

        private fun emit(result: Result) {
            if (settled) return
            settled = true
            callback.onResult(result)
        }
    }

    companion object {
        private const val TAG = "BtReset.Ctrl"

        /** Mirrors ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED. */
        private const val SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2
    }
}
```

Note `Session` is an `inner` class so it can read the outer `context` for `ReEnableScheduler.schedule`. That context must be an application context — both callers pass one.

- [ ] **Step 2: Compile**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:compileDebugKotlin --console=plain 2>&1 | tail -20
```

Expected: `BUILD SUCCESSFUL`, no warnings about `enable`/`disable` deprecation (they are inside `BluetoothSwitch`)

- [ ] **Step 3: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/BandResetController.kt
git commit -m "$(cat <<'EOF'
feat: add the scan-and-cycle controller shared by both entry points

UI-free so MainActivity and TriggerReceiver cannot drift apart; the
caller supplies the scan window (8s for the Activity, 6s for the
receiver, which must fit goAsync's ~10s budget).

No hardware ScanFilter: name filters are unreliable when advertising
truncates the name or carries it in the scan response, which is exactly
why BandMatcher keys on the MAC suffix. Matching happens in software.

A settled flag makes the timeout, the scan callback and onScanFailed
unable to race into reporting a result twice.

The load-bearing branch is disable() returning false: Bluetooth never
went off, so no re-enable is scheduled. Scheduling one there would mask
an OEM refusal and could toggle a radio we did not turn off.

onScanFailed also spells out the 5-scans-per-30s throttle, because that
is the most likely cause of a confusing 'scan failed' during testing.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 9: `MainActivity`

The invisible entry point for the launcher icon, Tasker/MacroDroid/Automate, and `adb shell am start`.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/MainActivity.kt`

- [ ] **Step 1: Write `MainActivity.kt`**

```kotlin
package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast

/**
 * Invisible entry point: launcher icon, Tasker / MacroDroid / Automate, and
 * `adb shell am start`.
 *
 * The theme is translucent rather than NoDisplay because NoDisplay requires
 * finish() before onResume(), which would abort the ~12s flow.
 *
 * This Activity deliberately stays alive until Bluetooth has been turned back
 * on. Finishing immediately after disable() would drop the process out of the
 * foreground and let MIUI reclaim it during the 3s wait, taking the
 * in-process re-enable timer down with it and leaving Bluetooth off. The
 * exact alarm would still recover it, but the precise path is worth keeping.
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI, and
 * the app has no androidx dependencies at all.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private var wantsToast = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        wantsToast = intent?.getBooleanExtra(EXTRA_TOAST, false) ?: false
        if (!BtPermissions.allGranted(this)) {
            // Only ever happens on first run; the grant persists afterwards.
            Log.i(TAG, "requesting Nearby-devices permissions (first run only)")
            requestPermissions(BtPermissions.REQUIRED, REQUEST_CODE_PERMISSIONS)
            return
        }
        runController()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) return
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (!granted) {
            Log.e(TAG, "permission denied; cannot scan or toggle Bluetooth")
            report(BandResetController.Result.NEEDS_PERMISSION)
            return
        }
        Log.i(TAG, "permissions granted")
        runController()
    }

    private fun runController() {
        // applicationContext, not `this`: the scan callback and the re-enable
        // Runnable both outlive this Activity.
        BandResetController(applicationContext).run(
            ACTIVITY_SCAN_TIMEOUT_MS
        ) { result -> onControllerResult(result) }
    }

    private fun onControllerResult(result: BandResetController.Result) {
        if (result == BandResetController.Result.RESET_TRIGGERED) {
            // Hold foreground priority until the in-process re-enable has
            // fired. See the class comment for why this matters.
            handler.postDelayed(
                { report(result) },
                ReEnableScheduler.IN_PROCESS_DELAY_MS + FINISH_GRACE_MS
            )
        } else {
            report(result)
        }
    }

    private fun report(result: BandResetController.Result) {
        Log.i(TAG, "result=$result")
        if (wantsToast) {
            Toast.makeText(this, "蓝牙重启: $result", Toast.LENGTH_LONG).show()
            // Let the Toast be read before the process goes away.
            handler.postDelayed({ finish() }, TOAST_LINGER_MS)
        } else {
            finish()
        }
    }

    companion object {
        private const val TAG = "BtReset.Main"
        private const val REQUEST_CODE_PERMISSIONS = 1

        /**
         * Debug-only extra: `am start -n com.blreay.bluetoothmonitor/.MainActivity --ez toast true`
         * shows the outcome on screen so the phone can be tested without adb.
         */
        const val EXTRA_TOAST = "toast"

        // No ACTION_RUN constant here: that action is declared only in the
        // manifest's intent-filter and this Activity runs regardless of which
        // action launched it, so a constant would be dead code.

        const val ACTIVITY_SCAN_TIMEOUT_MS = 8_000L
        private const val FINISH_GRACE_MS = 500L
        private const val TOAST_LINGER_MS = 2_500L
    }
}
```

The `run(...) { result -> ... }` lambda works because `Callback` is declared `fun interface`.

- [ ] **Step 2: Compile**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:compileDebugKotlin --console=plain 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/MainActivity.kt
git commit -m "$(cat <<'EOF'
feat: add the invisible Activity entry point

Stays alive until Bluetooth is back on rather than finishing right after
disable(). Finishing early would drop the process out of the foreground
and let MIUI reclaim it during the 3s wait, killing the in-process
re-enable timer. The exact alarm would still recover, but the precise
path is worth keeping.

Extends android.app.Activity, not AppCompatActivity - there is no UI and
the app carries no androidx dependencies.

Passes applicationContext to the controller because both the scan
callback and the re-enable Runnable outlive the Activity.

Adds a debug-only --ez toast true extra so the outcome can be read on
the phone without adb.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 10: `TriggerReceiver`

The `sendBroadcast` entry point. Must fit inside `goAsync()`'s ~10s budget.

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/TriggerReceiver.kt`

- [ ] **Step 1: Write `TriggerReceiver.kt`**

```kotlin
package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Entry point for `sendBroadcast` callers.
 *
 * goAsync() only buys about ten seconds and the full flow can take fifteen,
 * so this receiver caps the scan at 6s and never waits for the re-enable
 * itself: [ReEnableScheduler] hands that to a system exact alarm which fires
 * even if the process is reclaimed the moment goAsync() ends.
 *
 * Cannot prompt for permissions - there is no UI and Android 10+ blocks
 * starting an Activity from the background. If the grant is missing it logs
 * and gives up; launching the icon once is the only way to grant it.
 *
 * Left exported without a signature permission on purpose, so Tasker,
 * MacroDroid and Termux can all trigger it. Worst case for an uninvited
 * caller is one extra Bluetooth cycle.
 */
class TriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_RESET_IF_PRESENT) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }
        if (!BtPermissions.allGranted(context)) {
            Log.e(TAG, "Nearby-devices permission missing; launch the app icon once to grant it")
            return
        }

        val pending = goAsync()
        val handler = Handler(Looper.getMainLooper())
        var finished = false

        fun finishOnce() {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            pending.finish()
        }

        // Backstop: never hold the goAsync() budget past its ~10s limit, even
        // if the controller somehow fails to call back. Exceeding it is an
        // ANR-grade failure, not a graceful one.
        handler.postDelayed({ finishOnce() }, GO_ASYNC_BACKSTOP_MS)

        // applicationContext: the scan callback outlives onReceive.
        BandResetController(context.applicationContext).run(RECEIVER_SCAN_TIMEOUT_MS) { result ->
            Log.i(TAG, "result=$result")
            if (result == BandResetController.Result.RESET_TRIGGERED) {
                Log.i(TAG, "Bluetooth is off; the exact alarm will turn it back on")
            }
            finishOnce()
        }
    }

    companion object {
        private const val TAG = "BtReset.Trigger"

        const val ACTION_RESET_IF_PRESENT =
            "com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT"

        /** Kept under the ~10s goAsync() budget with room to spare. */
        const val RECEIVER_SCAN_TIMEOUT_MS = 6_000L

        private const val GO_ASYNC_BACKSTOP_MS = 9_000L
    }
}
```

- [ ] **Step 2: Compile**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:compileDebugKotlin --console=plain 2>&1 | tail -15
```

Expected: `BUILD SUCCESSFUL`

- [ ] **Step 3: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/java/com/blreay/bluetoothmonitor/TriggerReceiver.kt
git commit -m "$(cat <<'EOF'
feat: add the sendBroadcast entry point

Caps the scan at 6s so the whole decision phase fits inside goAsync's
~10s budget, and never waits for the re-enable itself - the exact alarm
handles that, because the process can be reclaimed the moment goAsync
ends. A 9s backstop finishes the pending result even if the controller
fails to call back, since exceeding the budget is an ANR-grade failure.

Gives up with a log line when the permission is missing: there is no UI
to prompt from and Android 10+ blocks starting an Activity from the
background, so launching the icon once is the only way to grant it.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 11: Wire the components into the manifest and build the release APK

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`

- [ ] **Step 1: Replace the empty `<application>` body with the three components**

In `app/src/main/AndroidManifest.xml`, replace the `<application>` element (and its placeholder comment) with exactly:

```xml
    <application
        android:allowBackup="false"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="false">

        <!--
          Invisible. excludeFromRecents and an empty taskAffinity keep it out
          of the recents list and stop it creating a visible task.

          exported=true is required: it is the launcher target and the entry
          point for Tasker / MacroDroid / Automate / `am start`.
        -->
        <activity
            android:name=".MainActivity"
            android:excludeFromRecents="true"
            android:exported="true"
            android:launchMode="singleTop"
            android:taskAffinity=""
            android:theme="@style/Theme.BtReset.Invisible">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
            <!-- For automation tools that prefer an explicit action. -->
            <intent-filter>
                <action android:name="com.blreay.bluetoothmonitor.action.RUN" />
                <category android:name="android.intent.category.DEFAULT" />
            </intent-filter>
        </activity>

        <!--
          exported=true with no permission guard, on purpose, so Tasker /
          MacroDroid / Termux can trigger it. Worst case for an uninvited
          caller is one extra Bluetooth cycle.
        -->
        <receiver
            android:name=".TriggerReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT" />
            </intent-filter>
        </receiver>

        <!--
          exported=false: reached only by our own explicit PendingIntent.
          Manifest-declared (not runtime-registered) so the system can start
          the process to deliver the alarm after MIUI has reclaimed it.
          No intent-filter needed - the PendingIntent is explicit.
        -->
        <receiver
            android:name=".ReEnableReceiver"
            android:exported="false" />

    </application>
```

- [ ] **Step 2: Build the signed release APK**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:assembleRelease --console=plain 2>&1 | tail -20
ls -la app/build/outputs/apk/release/
```

Expected: `BUILD SUCCESSFUL`, and `app-release.apk` present

If the build reports a signing error, check that `gradle.properties` is at the **repo root** (not in `app/`) and that `BT_RESET_STORE_FILE=keystore/release.jks` resolves relative to `rootProject`.

- [ ] **Step 3: Verify the APK contents are exactly right**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
APK=app/build/outputs/apk/release/app-release.apk
"$AAPT2" dump badging "$APK" 2>&1 | grep -E "package:|sdkVersion|targetSdkVersion|uses-permission|application-label|launchable-activity"
```

Expected — check every line:
- `package: name='com.blreay.bluetoothmonitor'`
- `sdkVersion:'31'`
- `targetSdkVersion:'32'` ← **must not be 33+ or the app cannot toggle Bluetooth**
- `uses-permission: name='android.permission.BLUETOOTH_SCAN'`
- `uses-permission: name='android.permission.BLUETOOTH_CONNECT'`
- `uses-permission: name='android.permission.SCHEDULE_EXACT_ALARM'`
- `application-label:'蓝牙重启'`
- `launchable-activity: name='com.blreay.bluetoothmonitor.MainActivity'`

- [ ] **Step 4: Verify the manifest components and the exported flags**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
AAPT2="$ANDROID_HOME/build-tools/34.0.0/aapt2"
APK=app/build/outputs/apk/release/app-release.apk
"$AAPT2" dump xmltree --file AndroidManifest.xml "$APK" 2>&1 \
  | grep -E 'E: (activity|receiver)|A: android:name|A: android:exported|A: android:theme|A: android:usesPermissionFlags'
```

Expected:
- `E: activity` with `android:name="com.blreay.bluetoothmonitor.MainActivity"`, `android:exported=0xffffffff (true)`, `android:theme` referencing `Theme.BtReset.Invisible`
- `E: receiver` × 2 — `TriggerReceiver` with `exported=true`, `ReEnableReceiver` with `exported=0x0 (false)`
- `BLUETOOTH_SCAN` carrying `android:usesPermissionFlags` with the `neverForLocation` bit set

- [ ] **Step 5: Verify the APK is signed with the committed key**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
APKSIGNER="$ANDROID_HOME/build-tools/34.0.0/apksigner"
APK=app/build/outputs/apk/release/app-release.apk
"$APKSIGNER" verify --print-certs "$APK" 2>&1 | grep -E 'Signer|SHA-256|DN:'
keytool -list -v -keystore keystore/release.jks -storepass btreset-local-only 2>&1 | grep 'SHA256:'
```

Expected: the apksigner `SHA-256` fingerprint **matches** the keystore's `SHA256:` fingerprint. If they differ, the release build is not using the committed key and every future install will demand a reinstall.

- [ ] **Step 6: Re-run the unit tests against the release configuration**

```bash
cd /home/admin/git/bluetooth_monitor
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:testReleaseUnitTest --console=plain 2>&1 | tail -10
```

Expected: `BUILD SUCCESSFUL`, 10 tests passing

- [ ] **Step 7: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add app/src/main/AndroidManifest.xml
git commit -m "$(cat <<'EOF'
feat: declare the activity and both receivers in the manifest

MainActivity is exported with a LAUNCHER filter plus an explicit RUN
action for automation tools, and uses excludeFromRecents with an empty
taskAffinity so it never creates a visible task.

TriggerReceiver is exported with no permission guard on purpose so
Tasker, MacroDroid and Termux can trigger it; the worst case for an
uninvited caller is one extra Bluetooth cycle.

ReEnableReceiver is exported=false and manifest-declared so the system
can start the process to deliver the alarm after MIUI has reclaimed it.
It needs no intent-filter because the PendingIntent is explicit.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 12: README

**Files:**
- Create: `README.md`

- [ ] **Step 1: Write `README.md`**

````markdown
# 蓝牙重启 (Bluetooth Band Reset)

一个没有界面的 Android 小工具，只做一件事：

1. 蓝牙没开 → 直接退出
2. 蓝牙开着 → BLE 扫描 **8 秒**，找 `Xiaomi Smart Band 8 35BA`
3. 找到了 → 关闭蓝牙，等 3 秒，重新打开，然后退出
4. 没找到 → 直接退出

判定"找到"只看 **BLE 扫描结果**，不要求已连接。

## 下载

固定地址（每次 push 到 main 自动更新，签名不变，可直接覆盖安装）：

```
https://github.com/blreay/bluetooth_monitor/releases/download/latest/bt-reset.apk
```

安装时需要允许"未知来源"。因为签名固定，以后每次更新都是覆盖安装，**不会丢掉已授予的权限**。

## 首次使用（只需一次）

1. 点桌面图标 **蓝牙重启**
2. 系统弹出"允许访问附近的设备？" → **允许**
3. 之后每次运行都是完全静默的

## MIUI / HyperOS 必做配置

不做这几步，广播触发和闹钟兜底都可能失效：

| 设置项 | 路径 | 为什么 |
|---|---|---|
| **自启动** | 设置 → 应用设置 → 应用管理 → 蓝牙重启 → 自启动 → 开 | 关着的话系统闹钟无法拉起进程，蓝牙可能被留在关闭状态 |
| **省电策略** | 同上 → 省电策略 → 无限制 | MIUI 会在 3 秒等待期内杀掉后台进程 |
| **权限** | 同上 → 权限管理 → 附近的设备 → 允许 | 扫描和开关蓝牙都需要 |

## 四种触发方式

**1. 桌面图标** —— 直接点。

**2. Tasker / MacroDroid / Automate** —— 发一个 Intent：

```
Action:   com.blreay.bluetoothmonitor.action.RUN
Package:  com.blreay.bluetoothmonitor
Class:    com.blreay.bluetoothmonitor.MainActivity
```

这类工具有后台启动 Activity 的特权，所以能调起来。纯后台的普通应用不行（Android 10+ 限制）。

**3. adb / Termux**

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.MainActivity
```

**4. 广播**

```bash
adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT
```

广播路径的扫描窗口是 6 秒（Activity 路径是 8 秒），因为 `goAsync()` 只有约 10 秒预算。

## 调试

**不想接电脑**：加一个 extra，结果会用 Toast 显示在屏幕上。

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.MainActivity --ez toast true
```

**接电脑看日志**：

```bash
adb logcat -s BtReset.Main BtReset.Trigger BtReset.Ctrl BtReset.Switch BtReset.Sched BtReset.ReEnable
```

日志含义：

| 日志 | 含义 |
|---|---|
| `Bluetooth is off; exiting without doing anything` | 蓝牙本来就没开，按设计直接退出 |
| `missing permission(s) [...]` | 权限没给，点一次图标授权 |
| `seen AA:BB:... name=...` | 扫到的每个设备（debug 级） |
| `band is visible: ...` | 命中手环 |
| `scan window elapsed; band is not visible` | 扫了 8 秒没看到手环 |
| `Bluetooth is now off; re-enable scheduled two ways` | 关闭成功，已安排重开 |
| `system refused to turn Bluetooth off` | MIUI 拒绝了 `disable()`，蓝牙仍是开着的 |
| `enable() returned false` | **蓝牙没打开成功，需要手动开** |
| `exact alarms not permitted` | 闹钟降级为非精确，蓝牙可能延迟恢复 |
| `BLE scan failed errorCode=2` | 触发了扫描节流，见下 |

## 三个已知限制

**1. 手环已连接时扫不到。** BLE 外设连接后就停止广播。所以手环正常连着的时候这个工具不会有任何动作 —— 这是设计如此。它的用途是手环掉线但在附近时，强制重启蓝牙让 Mi Fitness 重连。

**2. Android 限制每 30 秒最多 5 次 BLE 扫描。** 超了之后系统会静默禁扫 30 秒，日志里是 `BLE scan failed errorCode=2`。反复点图标测试时撞上"扫不到"，很可能不是 bug，等 30 秒再试。

**3. `targetSdk` 被故意钉在 32。** Android 13 起，targetSdk ≥ 33 的应用调用 `BluetoothAdapter.enable()/disable()` 一律返回 `false`。这个限制按 targetSdkVersion 而非系统版本判定，所以钉在 32 是让"程序化关蓝牙"在 Android 13/14/15/16 上继续可用的唯一办法 —— Tasker 的 `TaskerSettings` 和 MacroDroid 的 Helper App 用的是同一手法。

**不要为了消除 lint 警告而升级 targetSdk，那会静默废掉这个应用唯一的函数。** Android 没有替代 API：`ACTION_REQUEST_ENABLE` 是用户确认弹窗，而关蓝牙连这个都没有。

## 换手环 / 改目标设备

改 `app/src/main/java/com/blreay/bluetoothmonitor/BandMatcher.kt` 顶部三个常量：

```kotlin
const val TARGET_NAME = "Xiaomi Smart Band 8 35BA"
const val TARGET_NAME_PREFIX = "Xiaomi Smart Band"
const val TARGET_MAC_SUFFIX = "35:BA"
```

`35BA` 是手环 MAC 的后两个字节（小米手环把 MAC 尾字节附在广播名里）。查自己的 MAC：设置 → 蓝牙 → 已配对设备，或者跑一次工具看 `seen ...` 日志。

改完 push，GitHub Actions 会自动出新 APK。

## 自己构建

需要 JDK 17、Android SDK（platform 34 + build-tools 34.0.0）：

```bash
./gradlew assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
```

签名用的 `keystore/release.jks` 就在仓库里，凭据在 `gradle.properties`。这个密钥只用来签这一个私人工具，不代表任何身份，公开提交是故意的 —— 目的是让 APK 签名跨构建保持一致。

## 工作原理

```
点图标 / Tasker / am start / sendBroadcast
        ↓
MainActivity 或 TriggerReceiver
        ↓
BandResetController
  ├─ 蓝牙未开启         → 退出
  ├─ 权限缺失           → 弹框(仅 Activity) / 退出(Receiver)
  ├─ BLE 扫描 8s / 6s
  │    └─ 未命中        → 退出
  └─ 命中 → disable()
              ├─ false  → 退出（不安排重开）
              └─ true   → Handler(+3s) 和 精确闹钟(+3.5s) 同时安排
                          ↓ 进程此刻被杀也没关系
                       ReEnableReceiver → enable()
```

两个重开机制同时存在：进程内定时器是精确快路径，系统闹钟是抗进程死亡的兜底。`enable()` 幂等，先到的生效，后到的是无害的空操作。

**核心不变量：只有 `disable()` 返回 `true` 之后才安排重开。** 否则 MIUI 拦截时，我们会去开一个本来就开着的蓝牙。

设计文档：`docs/superpowers/specs/2026-11-20-bluetooth-band-reset-design.md`
````

- [ ] **Step 2: Verify every command in the README is real**

```bash
cd /home/admin/git/bluetooth_monitor
grep -oE 'com\.blreay\.bluetoothmonitor[.A-Za-z]*' README.md | sort -u
grep -rhoE '"com\.blreay\.bluetoothmonitor\.action\.[A-Z_]+"' app/src/main/java/ | sort -u
grep -rhoE 'BtReset\.[A-Za-z]+' app/src/main/java/ README.md | sort -u
```

Expected: every action string and every `BtReset.*` log tag in the README appears in the source, and vice versa. **A typo in the README's logcat command silently produces an empty log** — this check is the only thing catching it.

- [ ] **Step 3: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add README.md
git commit -m "$(cat <<'EOF'
docs: add README with MIUI setup, trigger commands and log reference

Documents the three things a future reader would otherwise get wrong:

- targetSdk 32 is load-bearing, not stale. Android 13 made
  enable()/disable() return false for apps targeting 33+, and there is
  no replacement API for turning Bluetooth off. Bumping it to silence a
  lint warning would silently delete the app's only function.
- A connected BLE peripheral stops advertising, so the band is
  invisible to the scan while happily connected. That is intended.
- Android throttles BLE scans to 5 per 30s, so a 'scan failed
  errorCode=2' during repeated testing is throttling, not a bug.

Also lists the MIUI autostart and battery-saver settings without which
the alarm fallback cannot wake the process.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 13: GitHub Actions workflow

**Files:**
- Create: `.github/workflows/build.yml`

- [ ] **Step 1: Write `.github/workflows/build.yml`**

```yaml
name: build-apk

on:
  push:
    branches: [main]
  workflow_dispatch:

# contents:write is required for softprops/action-gh-release to create and
# move the rolling "latest" tag.
permissions:
  contents: write

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4

      # The ubuntu-latest image ships the Android SDK with licences accepted.
      # AGP auto-downloads platforms;android-34 and build-tools;34.0.0 if they
      # are missing. Echoed so a failure here is diagnosable from the log.
      - name: Show Android SDK
        run: |
          echo "ANDROID_HOME=$ANDROID_HOME"
          ls "$ANDROID_HOME"

      - name: Run unit tests
        run: ./gradlew testReleaseUnitTest --stacktrace

      - name: Build signed release APK
        run: ./gradlew assembleRelease --stacktrace

      # Fixed filename on purpose: it makes the Release download URL permanent
      # and stops assets accumulating on the rolling "latest" release. The
      # commit is identified in the release body instead.
      - name: Stage APK
        run: |
          mkdir -p dist
          cp app/build/outputs/apk/release/app-release.apk dist/bt-reset.apk
          ls -la dist

      - name: Upload build artifact
        uses: actions/upload-artifact@v4
        with:
          name: bt-reset-${{ github.sha }}
          path: dist/bt-reset.apk

      - name: Publish to the rolling "latest" release
        uses: softprops/action-gh-release@v2
        with:
          tag_name: latest
          name: Latest build
          body: |
            Rolling build from `main`. Overwrites itself on every push.

            - Commit: `${{ github.sha }}`
            - Run: ${{ github.run_id }}

            手机直接下载下面的 `bt-reset.apk` 覆盖安装即可 —— 签名固定，不会丢权限。
          files: dist/bt-reset.apk
          make_latest: true
```

- [ ] **Step 2: Validate the YAML parses**

```bash
cd /home/admin/git/bluetooth_monitor
python3 -c "import yaml,sys; d=yaml.safe_load(open('.github/workflows/build.yml')); print('jobs:', list(d['jobs'])); print('steps:', len(d['jobs']['build']['steps'])); print('permissions:', d['permissions'])"
```

Expected: `jobs: ['build']`, `steps: 9`, `permissions: {'contents': 'write'}`

If `yaml` is not installed: `python3 -m pip install --user pyyaml`, or skip and rely on the CI run in Task 14 to catch syntax errors.

- [ ] **Step 3: Confirm the workflow's Gradle commands match what was verified locally**

```bash
cd /home/admin/git/bluetooth_monitor
grep -E '^\s+run: \./gradlew' .github/workflows/build.yml
```

Expected: `./gradlew testReleaseUnitTest --stacktrace` and `./gradlew assembleRelease --stacktrace` — both of which were run successfully in Task 11 Steps 2 and 6.

The workflow uses `./gradlew` (the committed wrapper, official `services.gradle.org` URL) rather than the local mirror-downloaded Gradle binary. That is intentional: GitHub runners reach `services.gradle.org` at full speed. Task 3 Step 6 already proved the wrapper itself works.

- [ ] **Step 4: Commit**

```bash
cd /home/admin/git/bluetooth_monitor
git add .github/workflows/build.yml
git commit -m "$(cat <<'EOF'
ci: build the signed release APK and publish a rolling latest release

Uses the committed Gradle wrapper against services.gradle.org, which is
fast from GitHub runners even though it crawls at 19KB/s from the
development machine.

The release APK keeps a fixed filename so the download URL is permanent
and assets do not accumulate on the rolling tag; the commit is
identified in the release body instead. Signing comes from the
committed keystore, so overwrite-installing never loses the granted
Nearby-devices permission.

Runs the unit tests before assembling, so a broken match rule cannot
ship.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
EOF
)"
```

---

## Task 14: Push, verify CI, and confirm on the phone

**This task is not complete until the CI run is green AND the user has confirmed behaviour on their phone.** Do not report success on a green build alone — a green build only proves it compiles.

- [ ] **Step 1: Final pre-push check**

```bash
cd /home/admin/git/bluetooth_monitor
git status --short
git log --oneline origin/main..HEAD
source ~/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" :app:assembleRelease :app:testReleaseUnitTest --console=plain 2>&1 | tail -8
```

Expected: clean working tree, the list of local commits ahead of `origin/main`, and `BUILD SUCCESSFUL`

`git status --short` must be empty. In particular confirm no `local.properties`, no `.gradle/`, and no `app/build/` slipped in — `.gitignore` should have caught them.

- [ ] **Step 2: Push to main over SSH**

```bash
cd /home/admin/git/bluetooth_monitor
timeout 180 git push origin main 2>&1 | tail -5
timeout 60 git ls-remote origin refs/heads/main
```

Expected: `main -> main` with no rejection, and the remote SHA matching `git rev-parse HEAD`

HTTPS to github.com is blocked on this machine; the remote is already `git@github.com:...`, so this goes over SSH.

- [ ] **Step 3: Watch the CI run**

`gh` is not installed and github.com HTTPS is blocked, so the run cannot be polled from here. Ask the user to open:

```
https://github.com/blreay/bluetooth_monitor/actions
```

and report whether the `build-apk` run went green.

If it fails, the two likely causes, in order:

1. **Android SDK package missing on the runner.** Symptom: `Failed to find target with hash string 'android-34'` or a licence error. Fix: add an explicit install step before the build —
   ```yaml
   - name: Install Android SDK packages
     run: |
       yes | "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" --licenses > /dev/null
       "$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager" \
         "platform-tools" "platforms;android-34" "build-tools;34.0.0"
   ```
2. **The wrapper could not download Gradle.** Symptom: `Could not get resource ... gradle-8.7-bin.zip`. Fix: confirm `gradle/wrapper/gradle-wrapper.jar` is actually committed (`git ls-files gradle/wrapper/`) — if it is missing, re-run Task 3 Step 5.

Read the real log before changing anything. Do not guess.

- [ ] **Step 4: Confirm the Release asset is downloadable**

Ask the user to check that this URL serves an APK:

```
https://github.com/blreay/bluetooth_monitor/releases/download/latest/bt-reset.apk
```

If the repo is private this needs a GitHub login on the phone — tell the user that, and offer the Actions artifact as the alternative.

- [ ] **Step 5: Hand the user the on-device test checklist**

This is the only real verification of behaviour. Give it to the user verbatim:

> 装好之后，按顺序试这几个场景，每个都告诉我结果：
>
> 1. **首次授权** —— 点图标，应该弹一次"允许访问附近的设备？"。允许。屏幕上不应该出现任何应用窗口。
> 2. **肉眼检查** —— 再点一次图标。有没有看到任何窗口、白屏、黑屏或者闪一下应用图标？（预期：只有 MIUI 桌面自己的图标动画，最多有 100~300 毫秒桌面不响应触摸的顿挫感）
> 3. **手环不在附近** —— 把手环放远或者关机，点图标。预期：约 8 秒后静默退出，蓝牙状态不变。
> 4. **手环在附近且未连接** —— 这是主要场景。先在 Mi Fitness 里断开手环（或关掉 Mi Fitness），点图标。预期：蓝牙关闭，约 3 秒后自动重新打开。
> 5. **手环已连接** —— 预期：扫不到（已连接的 BLE 设备不广播），静默退出，蓝牙不变。这是设计如此。
> 6. **MIUI 有没有弹确认框** —— 关蓝牙的瞬间，MIUI 有没有弹出"蓝牙重启正在尝试更改蓝牙"之类的对话框？这个我们没法预先消除，只能实测。
> 7. **广播触发** —— 如果能接电脑：`adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT`
> 8. **日志** —— 如果能接电脑，开着这个再跑一遍场景 4：
>    `adb logcat -s BtReset.Main BtReset.Trigger BtReset.Ctrl BtReset.Switch BtReset.Sched BtReset.ReEnable`
>    把输出贴给我。
>
> 最重要的一条：**如果蓝牙被关掉后没有自动打开**，立刻告诉我 —— 那是 MIUI 拦了闹钟，需要去开"自启动"权限，我会据此调整设计。

- [ ] **Step 6: Report honestly**

When reporting completion, state separately:

- what is **verified**: compiles, unit tests pass, release APK assembles and is signed with the committed key, manifest inspected with `aapt2`, CI green, APK downloadable
- what is **NOT verified**: actual on-device behaviour. Specifically whether MIUI permits `disable()` at `targetSdk 32`, whether it adds its own confirmation dialog, and whether the exact alarm survives MIUI's process management.

Never collapse those into "it works".

---

## Contingencies

| Symptom | Cause | Fix |
|---|---|---|
| `targetSdkVersion` reads 33+ in `aapt2 dump badging` | Someone bumped it | Set back to 32 in `app/build.gradle.kts`. The app cannot function at 33+. |
| `disable()` returns false on the phone | MIUI refuses the legacy path | Report it. There is no API workaround; the only options are a rooted/ADB-driven toggle or accepting a manual toggle. The app already fails safe — Bluetooth stays ON. |
| Bluetooth stays OFF after the cycle | Exact alarm did not fire | Enable MIUI autostart + battery-saver exemption. If already enabled, the fallback is to keep `MainActivity` alive longer, or use a foreground Service and accept a notification. |
| `BLE scan failed errorCode=2` | 5-scans-per-30s throttle | Wait 30s. Not a bug. |
| Band never appears in `seen ...` logs | It is connected, so it is not advertising | Expected. Disconnect it in Mi Fitness first. |
| CI: `Failed to find target 'android-34'` | Runner SDK incomplete | Add the explicit `sdkmanager` step from Task 14 Step 3. |
| `./gradlew` fails in CI | Wrapper jar not committed | `git ls-files gradle/wrapper/`; re-run Task 3 Step 5 if empty. |

---

## Execution record (2026-11-21)

All 14 tasks executed. Two contingencies fired, and one environment problem
was not anticipated by the plan at all. Recorded here because each one would
otherwise be rediscovered from scratch.

### 1. Unanticipated: a TLS-intercepting proxy breaks every Java download

`sdkmanager` failed with `Failed to download any source lists! / IO exception
while downloading manifest`, while `curl` reached the *same* URLs with HTTP
200. Cause: the machine sits behind a mitmproxy (`NODE_EXTRA_CA_CERTS` points
at `/opt/opensandbox/mitmproxy-ca-cert.pem`). `curl` trusts it via the system
CA store; a freshly downloaded JDK trusts only its own
`lib/security/cacerts`, which does not contain it.

**Fix — required before any Java-based download will work here:**

```bash
keytool -importcert -noprompt -trustcacerts \
  -alias opensandbox-mitmproxy \
  -file /opt/opensandbox/mitmproxy-ca-cert.pem \
  -keystore "$JAVA_HOME/lib/security/cacerts" -storepass changeit
```

This also covers Gradle's dependency resolution, since Gradle runs on the same
JDK. Without it, `sdkmanager` and Maven Central both fail while `curl` looks
perfectly healthy — a genuinely misleading symptom.

Second, smaller trap: `sdkmanager` needs `yes |` piped into the *install*
command, not just into `--licenses`. Running `--licenses` first while the
network is still broken silently accepts nothing, and the later install then
stops at the prompt while still exiting 0 in some paths. Verify by listing
`$ANDROID_HOME/platforms`, not by trusting the exit code.

### 2. Contingency #1 fired: the runner has no usable Android platform

CI run #1 (`c5ea777`) failed at the first `./gradlew` invocation. Steps 1-5,
including the SDK echo, all passed; `testReleaseUnitTest` died with exit code
1. The plan's assumption that "AGP auto-downloads `platforms;android-34`" is
**wrong on this runner image**.

The job log was unreadable from here — the Actions logs endpoint returns
`403 Must have admin rights to Repository` for unauthenticated callers, even
on a public repo, and `github.com` HTML times out (though `api.github.com`
responds normally in ~1s, which is what made diagnosis possible at all).

Rather than guess, the cause was confirmed from evidence inside this same
repo: a sibling branch `gpt` (a separate, earlier attempt at this task, not
part of this work) had hit the identical failure and fixed it in a commit
titled `ci: use preinstalled hosted Android SDK for APK builds`. Its diff
drops `android-actions/setup-android@v3` and instead invokes
`"$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"` **by absolute path** —
`sdkmanager` is not on `PATH` on the hosted runner.

Fix applied in `247fde1`: explicit `Verify hosted Android SDK` +
`Install Android platform and build-tools` steps using that absolute path,
installing `platform-tools`, `platforms;android-34`, `build-tools;34.0.0`.
Also moved to `actions/setup-java@v5` (the runner annotated v4 as
deprecated), added `timeout-minutes: 30`, and set `if-no-files-found: error`
on the artifact upload so a missing APK fails loudly instead of publishing an
empty artifact. Workflow is now 10 steps, not the 9 the plan predicted.

CI run #2 (`247fde1`) passed all 10 steps.

### 3. Local reproduction could not reproduce the CI failure

A fresh clone into `/tmp` with a cold `GRADLE_USER_HOME` ran the exact CI
command `testReleaseUnitTest` to `BUILD SUCCESSFUL in 3m`. So the failure was
never about the cold cache or a missing committed file — `git ls-tree -r HEAD`
confirmed the keystore, wrapper jar and `gradle.properties` are all tracked.
The difference was purely the runner's SDK contents. Worth remembering: a
green local build says nothing about the hosted SDK.

### 4. Verified properties of the published artifact

The APK was downloaded back out of the Release via the asset API and inspected
with `aapt2`/`apksigner`, rather than trusting the build log:

| Property | Value |
|---|---|
| `package` | `com.blreay.bluetoothmonitor` |
| `versionCode` | `3` — proves the `GITHUB_RUN_NUMBER` derivation (run #2 → 2+1) |
| `sdkVersion` / `targetSdkVersion` | `31` / **`32`** ← the load-bearing one |
| `BLUETOOTH_SCAN` flags | `neverForLocation` present |
| `launchable-activity` | `com.blreay.bluetoothmonitor.MainActivity` |
| Signer DN | `CN=blreay, OU=personal, O=blreay, L=local, ST=local, C=CN` |
| Signer SHA-256 | `2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad` — **identical to `keystore/release.jks`** |
| Signature scheme | v2 verified |

The CI APK's sha256 differs from the local build's only because `versionCode`
differs (3 vs 1), which changes the encoded manifest. The *signature* is what
must match across builds, and it does — that is the property that keeps
overwrite-install from discarding the granted Nearby-devices permission.

### 5. Still unverified

On-device behaviour. Nothing here proves that MIUI permits `disable()` at
`targetSdk 32`, that it does not add its own confirmation dialog, or that the
exact alarm survives MIUI process management. Those need the phone checklist
in Task 14 Step 5.

