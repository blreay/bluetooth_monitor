# 蓝牙重启 v2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 消除隐形 Activity 造成的 3.5~8 秒桌面失焦卡顿，并把"关蓝牙后自动重开"从必然失败的固定定时器改成由 `getState()` 驱动的闹钟状态机。

**Architecture:** 流程切成两段。快段（扫描→命中→Toast→`disable()`→布置闹钟链，≤8 秒，有界）跑在一个 started Service 里（图标路径）或 `goAsync()` Receiver 里（广播路径），入口 Activity 用 `Theme.NoDisplay` 且在 `onCreate` 内立刻 `finish()`，因此永不创建窗口、永不拿焦点。慢段（等真关完→`enable()`→等真开完，时长不可预测）完全交给每秒一跳的 AlarmManager 链 + SharedPreferences 里的持久状态，进程被杀也能自行恢复。慢段的决策是一个零 Android import 的纯函数 `ReEnablePolicy.decide(state, phase, attempts)`。

**Tech Stack:** Kotlin，AGP 8.4.0，Gradle 8.7（wrapper），compileSdk 34 / minSdk 31 / **targetSdk 32（钉死，见 `app/build.gradle.kts:20-24`）**，JUnit 4.13.2，无 androidx 依赖，`android.app.*` 原生组件。

**Spec:** `docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md`

---

## 执行前必读

**每条 Bash 命令都要先 source 构建环境。** 这个会话的 shell 状态不跨调用保留，JDK 和 Android SDK 都不在默认 PATH 上：

```bash
source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" <task>
```

`env.sh` 导出 `JAVA_HOME`（jdk17）、`ANDROID_HOME` / `ANDROID_SDK_ROOT`（platform 34 + build-tools 34.0.0）、`BTRESET_GRADLE`、`PATH`。

**用 `"$BTRESET_GRADLE"`，不要用 `./gradlew`。** 已实测踩过：`./gradlew` 会去校验 wrapper 发行版，而 `~/.gradle/wrapper/dists/gradle-8.7-bin/` 下有两个条目 —— `anc4mv9t277pws4qhs0gnw9pj` 是好的（有 `.ok` 标记），`bhs2wmbdwecv87pi65oeuq5iu` 是一次失败下载留下的（只有 `.zip.part` 和 `.lck`）。wrapper 挑中了坏的那个，然后去 `services.gradle.org` 重新下载，本机无外网，直接 `BUILD FAILED` 在 `org.gradle.wrapper.Install.forceFetch`。

`$BTRESET_GRADLE` 指向 `~/.cache/btreset-build/gradle-8.7/bin/gradle`，版本与 `gradle-wrapper.properties` 里的 `gradle-8.7-bin.zip` 完全一致，所以构建产物没有区别。

**不要为此去改 `gradle/wrapper/gradle-wrapper.properties` 或 `gradlew`。** CI 用的就是 `./gradlew`，GitHub runner 有外网，已经连绿三次。改坏了就是拿本地便利换线上失败。

**Kotlin 的 TDD "RED" 是编译失败，不是断言失败。** 测试引用一个还不存在的类时，Gradle 报的是 `unresolved reference: Xxx` 然后 `BUILD FAILED`。这是预期的 RED，不要误当成环境坏了。

**不要中途 push。** CI 一 push 就重建滚动 Release，会把 `releases/download/latest/bt-reset.apk` 换成半成品。全部任务做完、Task 16 才 push。

**每个 commit 都必须能编译。** 任务顺序是特意排的：先加新代码（Task 1-5），再一次性切换调用方（Task 6-7），最后删旧代码（Task 8）。`BluetoothSwitch.enableIfOff()` 在 Task 8 之前一直保留，即使它带着 bug —— 期间不 push，所以不影响任何人。

## 与 spec 的两处偏离

写计划时发现 spec 有两个更好的做法，Task 1 会顺手把 spec 改掉，保持一致：

1. **`AdapterState` 不做 int→枚举映射，`AdapterStateTest` 取消。** spec §5.1 让 `AdapterState.fromFramework(int)` 持有映射，§9 给它配了约 6 条测试。但那样就得把 `STATE_OFF=10 / TURNING_ON=11 / ON=12 / TURNING_OFF=13` 四个字面量手抄进代码 —— 抄错一个（比如 `TURNING_ON` 和 `TURNING_OFF` 对调）就会重现我们正在修的那个 bug，而测试本身也只能验证"抄的和抄的一致"。改成把映射放进 `BluetoothSwitch.state()`，直接用 `BluetoothAdapter.STATE_*` 真常量，编译器保证左边不会错。`AdapterState` 退化成一个裸枚举，没有逻辑可测，测试取消。

2. **`wantsToast` 存进 `PendingResetStore`。** spec §6 已经说了要落盘，这里只是把它写进 `arm()` / `write()` / `Pending` 的签名，明确慢段每一跳都从 store 读，不从 Intent 读。

## 文件结构

**新建（6 个 main + 1 个 test）**

| 文件 | 职责 | 能单测吗 |
|---|---|---|
| `app/src/main/java/com/blreay/bluetoothmonitor/AdapterState.kt` | 蓝牙适配器状态的裸枚举，零 Android import | 无逻辑，不测 |
| `app/src/main/java/com/blreay/bluetoothmonitor/ReEnablePolicy.kt` | 慢段决策纯函数 `decide(state, phase, attempts)` | **是，18 条** |
| `app/src/main/java/com/blreay/bluetoothmonitor/PendingResetStore.kt` | SharedPreferences 持久化 `{armed, phase, attempts, wantsToast}` | 否（需 Robolectric，不引入） |
| `app/src/main/java/com/blreay/bluetoothmonitor/Notifier.kt` | 全项目唯一碰 Toast 的地方 | 否 |
| `app/src/main/java/com/blreay/bluetoothmonitor/ResetService.kt` | started Service，跑快段 | 否 |
| `app/src/main/java/com/blreay/bluetoothmonitor/PermissionActivity.kt` | 全项目唯一会有窗口的地方，仅首次授权 | 否 |
| `app/src/test/java/com/blreay/bluetoothmonitor/ReEnablePolicyTest.kt` | 上述 18 条 | — |

**重写（4 个）**

`MainActivity.kt`、`ReEnableReceiver.kt`、`ReEnableScheduler.kt`、`BluetoothSwitch.kt`

**修改（6 个）**

`BandResetController.kt`、`TriggerReceiver.kt`、`AndroidManifest.xml`、`themes.xml`、`strings.xml`、`README.md`

**不动**

`BandMatcher.kt`、`BandMatcherTest.kt`（10 条测试原样保留）、`BtPermissions.kt`、`app/build.gradle.kts`、`gradle.properties`、`keystore/release.jks`、`.github/workflows/build.yml`、图标资源

---

### Task 1: AdapterState 枚举

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/AdapterState.kt`
- Modify: `docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md`（§5.1 的 `AdapterState.kt` 行、§9 的 `AdapterStateTest` 行）

这个枚举存在的唯一理由是让 `ReEnablePolicy` 不 import 任何 `android.*`，从而能在纯 JVM 上单测。框架的 `getState()` 返回 int，映射放在 Task 3 的 `BluetoothSwitch` 里，用真常量。

- [ ] **Step 1: 写 AdapterState**

```kotlin
package com.blreay.bluetoothmonitor

/**
 * The Bluetooth adapter's state, without the framework's int encoding.
 *
 * This enum exists so [ReEnablePolicy] can decide what to do next without
 * importing anything from android.* - which is what makes the slow-phase
 * logic unit-testable on a plain JVM. v1 had no tests at all for its
 * re-enable path, and that is precisely the part that was broken.
 *
 * The int -> enum mapping deliberately lives in [BluetoothSwitch.state], next
 * to the real BluetoothAdapter.STATE_* constants, rather than here. Copying
 * 10/11/12/13 into this file would mean a test could only ever verify that
 * the copy agrees with itself, and transposing TURNING_ON with TURNING_OFF
 * would reproduce the exact bug v2 exists to fix.
 *
 * UNKNOWN covers both "no BluetoothAdapter on this device" and "getState()
 * returned something outside the four documented values or threw".
 */
enum class AdapterState {
    OFF,
    TURNING_ON,
    ON,
    TURNING_OFF,
    UNKNOWN,
}
```

- [ ] **Step 2: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning

- [ ] **Step 3: 把 spec 改成一致**

在 `docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md` 的 §5.1 表格里，把这一行：

```
| `AdapterState.kt` | 枚举 + `fromFramework(int)` 映射 |
```

替换成：

```
| `AdapterState.kt` | 蓝牙状态的裸枚举，零 Android import。int→枚举的映射不放在这里，而是放在 `BluetoothSwitch.state()` 里直接用 `BluetoothAdapter.STATE_*` 真常量 —— 手抄 10/11/12/13 只会让测试验证"抄的和抄的一致"，而抄错 `TURNING_ON`/`TURNING_OFF` 就会重现本设计要修的 bug |
```

再把 §4.1 里这句：

```
`UNKNOWN` 覆盖两种情况：拿不到 `BluetoothAdapter`，以及 `getState()` 返回了四个已知值之外的东西。
```

替换成：

```
`UNKNOWN` 覆盖三种情况：拿不到 `BluetoothAdapter`、`getState()` 返回了四个已知值之外的东西、以及 `getState()` 抛异常（缺 `BLUETOOTH_CONNECT` 时可能）。映射由 `BluetoothSwitch.state()` 用真常量完成。
```

再把 §9 表格里这一整行删掉：

```
| `AdapterStateTest`（新，约 6 条） | 四个框架 int（10/11/12/13）正确映射；垃圾值（0、-1、99）→ `UNKNOWN` |
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/AdapterState.kt docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md
git commit -m "feat: add a framework-free adapter state enum

ReEnablePolicy needs to reason about the adapter state without importing
android.*, otherwise it cannot be unit-tested on a plain JVM. The int ->
enum mapping stays in BluetoothSwitch next to the real STATE_* constants;
copying 10/11/12/13 into the enum would make a test verify only that the
copy agrees with itself.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 2: ReEnablePolicy 决策纯函数（TDD）

**Files:**
- Create: `app/src/test/java/com/blreay/bluetoothmonitor/ReEnablePolicyTest.kt`
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/ReEnablePolicy.kt`

这是整个 v2 的核心。18 条测试覆盖 (phase × state) 每个格子、上限两侧、以及两条求值顺序规则。

**求值顺序是有意义的，两条都必须被测到：**

- 成功判定先于放弃判定 —— 否则第 120 跳刚好把蓝牙开起来会被误报成超时
- **放弃判定先于 `RETRY`** —— 否则 `(ON, WAITING_OFF, 120)` 会永远 `RETRY` 下去，闹钟链变成无限循环。第 14 条测试专门盯这个

- [ ] **Step 1: 写 18 条失败测试**

```kotlin
package com.blreay.bluetoothmonitor

import com.blreay.bluetoothmonitor.ReEnablePolicy.Action
import com.blreay.bluetoothmonitor.ReEnablePolicy.MAX_ATTEMPTS
import com.blreay.bluetoothmonitor.ReEnablePolicy.Phase.WAITING_OFF
import com.blreay.bluetoothmonitor.ReEnablePolicy.Phase.WAITING_ON
import com.blreay.bluetoothmonitor.ReEnablePolicy.decide
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The slow phase's entire brain. Pure function, no Android, so every cell of
 * (phase x state) can be pinned down - v1 had zero coverage here and shipped
 * a re-enable path that could never work.
 */
class ReEnablePolicyTest {

    // --- WAITING_OFF: disable() returned true, waiting for the radio to go off

    @Test
    fun `off while waiting off is the only state that may enable`() {
        assertEquals(Action.ENABLE_AND_RETRY, decide(AdapterState.OFF, WAITING_OFF, 0))
    }

    @Test
    fun `turning off while waiting off must retry not enable`() {
        // This is the exact cell v1 got wrong: it called enable() here, hit
        // the documented "immediate problem", and never retried.
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_OFF, WAITING_OFF, 0))
    }

    @Test
    fun `still on while waiting off retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.ON, WAITING_OFF, 3))
    }

    @Test
    fun `turning on while waiting off retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_ON, WAITING_OFF, 0))
    }

    @Test
    fun `unknown state while waiting off gives up on the first tick`() {
        assertEquals(Action.GIVE_UP_NO_ADAPTER, decide(AdapterState.UNKNOWN, WAITING_OFF, 0))
    }

    // --- WAITING_ON: we saw OFF and called enable(), waiting for it to come up

    @Test
    fun `on while waiting on is done`() {
        assertEquals(Action.DONE_RESTORED, decide(AdapterState.ON, WAITING_ON, 4))
    }

    @Test
    fun `still off while waiting on enables again`() {
        assertEquals(Action.ENABLE_AND_RETRY, decide(AdapterState.OFF, WAITING_ON, 4))
    }

    @Test
    fun `turning on while waiting on retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_ON, WAITING_ON, 4))
    }

    @Test
    fun `turning off while waiting on retries`() {
        assertEquals(Action.RETRY, decide(AdapterState.TURNING_OFF, WAITING_ON, 4))
    }

    @Test
    fun `unknown state while waiting on gives up`() {
        assertEquals(Action.GIVE_UP_NO_ADAPTER, decide(AdapterState.UNKNOWN, WAITING_ON, 4))
    }

    // --- evaluation order

    @Test
    fun `success beats the attempt cap`() {
        // The tick that finally restores Bluetooth must not be reported as a
        // timeout just because it happened to be the last allowed one.
        assertEquals(Action.DONE_RESTORED, decide(AdapterState.ON, WAITING_ON, MAX_ATTEMPTS))
    }

    @Test
    fun `no adapter beats the attempt cap`() {
        assertEquals(
            Action.GIVE_UP_NO_ADAPTER,
            decide(AdapterState.UNKNOWN, WAITING_ON, MAX_ATTEMPTS)
        )
    }

    // --- the cap itself

    @Test
    fun `waiting off at the cap gives up as never turned off`() {
        assertEquals(
            Action.GIVE_UP_NEVER_TURNED_OFF,
            decide(AdapterState.TURNING_OFF, WAITING_OFF, MAX_ATTEMPTS)
        )
    }

    @Test
    fun `still on at the cap gives up instead of retrying forever`() {
        // Without the cap being checked before RETRY, a user who tapped
        // "deny" on the MIUI dialog would leave the alarm chain looping
        // once a second forever. This test is the reason the cap check sits
        // above the RETRY fallthrough.
        assertEquals(Action.GIVE_UP_NEVER_TURNED_OFF, decide(AdapterState.ON, WAITING_OFF, MAX_ATTEMPTS))
    }

    @Test
    fun `waiting on at the cap gives up as never turned back on`() {
        assertEquals(
            Action.GIVE_UP_NEVER_TURNED_BACK_ON,
            decide(AdapterState.OFF, WAITING_ON, MAX_ATTEMPTS)
        )
    }

    // --- boundary: one tick either side of the cap

    @Test
    fun `one tick below the cap still retries`() {
        assertEquals(
            Action.RETRY,
            decide(AdapterState.TURNING_OFF, WAITING_OFF, MAX_ATTEMPTS - 1)
        )
    }

    @Test
    fun `one tick below the cap still enables when waiting on`() {
        assertEquals(
            Action.ENABLE_AND_RETRY,
            decide(AdapterState.OFF, WAITING_ON, MAX_ATTEMPTS - 1)
        )
    }

    @Test
    fun `the cap is two minutes of one second ticks`() {
        // MAX_ATTEMPTS must stay larger than the time a human needs to read
        // and tap the MIUI "toggle Bluetooth" dialog. If it is too small the
        // policy gives up before the radio has even started turning off,
        // which is worse than v1: Bluetooth then goes off and never returns.
        assertEquals(120, MAX_ATTEMPTS)
    }
}
```

- [ ] **Step 2: 跑测试确认 RED**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --tests '*ReEnablePolicyTest*' --stacktrace`
Expected: `BUILD FAILED`，报错含 `unresolved reference: ReEnablePolicy`（Kotlin 的 RED 是编译失败，见"执行前必读"）

- [ ] **Step 3: 写实现**

```kotlin
package com.blreay.bluetoothmonitor

/**
 * The slow phase's decision table: given what the radio is doing right now,
 * what the chain is waiting for, and how many ticks have already passed,
 * decide the single next action.
 *
 * Deliberately free of any android.* import so it is unit-testable on a
 * plain JVM. The framework int from BluetoothAdapter.getState() is mapped to
 * [AdapterState] by [BluetoothSwitch.state] before it reaches here.
 *
 * The invariant this class exists to enforce: enable() is only ever called
 * when the state is exactly OFF. Never during TURNING_OFF - that is what v1
 * did, and the framework documents enable() as returning false on an
 * "immediate problem", so the call was silently rejected and nothing retried.
 */
object ReEnablePolicy {

    /** What the alarm chain is currently waiting to observe. */
    enum class Phase {
        /** disable() returned true; waiting for the radio to actually go off. */
        WAITING_OFF,

        /** We saw OFF and called enable(); waiting for the radio to come up. */
        WAITING_ON,
    }

    enum class Action {
        /** Call enable(), move to WAITING_ON, count the tick, arm the next one. */
        ENABLE_AND_RETRY,

        /** Count the tick and arm the next one. Never calls enable(). */
        RETRY,

        /** Bluetooth is back on. Toast, clear the store, cancel the chain. */
        DONE_RESTORED,

        /** No adapter, or getState() is unusable. Nothing can be done. */
        GIVE_UP_NO_ADAPTER,

        /** The cap expired while still waiting for OFF - the user denied, or MIUI refused. */
        GIVE_UP_NEVER_TURNED_OFF,

        /** The cap expired while still waiting for ON - enable() is being rejected. */
        GIVE_UP_NEVER_TURNED_BACK_ON,
    }

    /**
     * 120 ticks x 1s = a two minute ceiling.
     *
     * This must stay larger than the time a human needs to read and tap the
     * MIUI "toggle Bluetooth" permission dialog. Set it too low and the chain
     * gives up before the radio has started turning off, then Bluetooth goes
     * off and never comes back - strictly worse than the bug being fixed.
     */
    const val MAX_ATTEMPTS = 120

    /**
     * Order matters, and both orderings are covered by tests:
     *
     * 1. UNKNOWN first - no adapter means no tick can ever make progress.
     * 2. Success before the cap - otherwise the tick that finally restores
     *    Bluetooth would be reported as a timeout.
     * 3. The cap before the RETRY fallthrough - otherwise a user who tapped
     *    "deny" leaves the chain arming a new alarm every second forever.
     */
    fun decide(state: AdapterState, phase: Phase, attempts: Int): Action {
        if (state == AdapterState.UNKNOWN) return Action.GIVE_UP_NO_ADAPTER
        if (phase == Phase.WAITING_ON && state == AdapterState.ON) return Action.DONE_RESTORED
        if (attempts >= MAX_ATTEMPTS) {
            return if (phase == Phase.WAITING_OFF) {
                Action.GIVE_UP_NEVER_TURNED_OFF
            } else {
                Action.GIVE_UP_NEVER_TURNED_BACK_ON
            }
        }
        return if (state == AdapterState.OFF) Action.ENABLE_AND_RETRY else Action.RETRY
    }
}
```

- [ ] **Step 4: 跑测试确认 GREEN，且 BandMatcher 没被带坏**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`。检查 `app/build/test-results/testReleaseUnitTest/`：
- `TEST-com.blreay.bluetoothmonitor.ReEnablePolicyTest.xml` → `tests="18" skipped="0" failures="0" errors="0"`
- `TEST-com.blreay.bluetoothmonitor.BandMatcherTest.xml` → `tests="10" skipped="0" failures="0" errors="0"`

合计 28 条。

读结果**不要**用 `grep`/正则手搓 —— `<testcase>` 成功时是自闭合的 `<testcase ... />`，非贪婪正则会跨条误配，把失败安到别的测试头上（实测踩过）。用 ElementTree：

```bash
python3 -c "
import xml.etree.ElementTree as ET
r=ET.parse('app/build/test-results/testReleaseUnitTest/TEST-com.blreay.bluetoothmonitor.ReEnablePolicyTest.xml').getroot()
print({k:r.get(k) for k in ('tests','failures','errors')})
for tc in r.findall('testcase'):
    f=tc.find('failure')
    if f is not None: print('FAILED:', tc.get('name'), '|', (f.get('message') or '')[:90])
"
```

- [ ] **Step 5: 变异测试 —— 确认这 18 条真的能抓到 bug**

全绿本身不证明什么。`decide()` 里那三条求值顺序规则各自对应一个真实故障，逐一破坏一次，确认对应测试变红。两次变异各约 3 秒。

**变异 A：把上限检查挪到最前面**（破坏"成功判定先于放弃判定"）

```kotlin
    fun decide(state: AdapterState, phase: Phase, attempts: Int): Action {
        if (attempts >= MAX_ATTEMPTS) {
            return if (phase == Phase.WAITING_OFF) {
                Action.GIVE_UP_NEVER_TURNED_OFF
            } else {
                Action.GIVE_UP_NEVER_TURNED_BACK_ON
            }
        }
        if (state == AdapterState.UNKNOWN) return Action.GIVE_UP_NO_ADAPTER
        if (phase == Phase.WAITING_ON && state == AdapterState.ON) return Action.DONE_RESTORED
        return if (state == AdapterState.OFF) Action.ENABLE_AND_RETRY else Action.RETRY
    }
```

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --tests '*ReEnablePolicyTest*'`
Expected: `BUILD FAILED`，`failures="2"`，且**恰好**是这两条：

```
FAILED: success beats the attempt cap | expected:<DONE_RESTORED> but was:<GIVE_UP_NEVER_TURNED_BACK_ON>
FAILED: no adapter beats the attempt cap | expected:<GIVE_UP_NO_ADAPTER> but was:<GIVE_UP_NEVER_TURNED_BACK_ON>
```

**变异 B：把上限检查挪到 `RETRY` 之后**（破坏"放弃判定先于 RETRY"，这是更危险的那个 —— 闹钟链会无限自我重排）

```kotlin
    fun decide(state: AdapterState, phase: Phase, attempts: Int): Action {
        if (state == AdapterState.UNKNOWN) return Action.GIVE_UP_NO_ADAPTER
        if (phase == Phase.WAITING_ON && state == AdapterState.ON) return Action.DONE_RESTORED
        if (state != AdapterState.OFF) return Action.RETRY
        if (attempts >= MAX_ATTEMPTS) {
            return if (phase == Phase.WAITING_OFF) {
                Action.GIVE_UP_NEVER_TURNED_OFF
            } else {
                Action.GIVE_UP_NEVER_TURNED_BACK_ON
            }
        }
        return Action.ENABLE_AND_RETRY
    }
```

Expected: `BUILD FAILED`，`failures="2"`，且**恰好**是这两条：

```
FAILED: waiting off at the cap gives up as never turned off | expected:<GIVE_UP_NEVER_TURNED_OFF> but was:<RETRY>
FAILED: still on at the cap gives up instead of retrying forever | expected:<GIVE_UP_NEVER_TURNED_OFF> but was:<RETRY>
```

`but was:<RETRY>` 就是那个无限循环：`ReEnableReceiver` 会一直 `attempts+1` 再排一跳，永不停止。

**两次变异之后，把 `decide()` 改回 Step 3 的版本，重跑 Step 4 确认 28 条全绿再提交。** 变异体绝不能进 commit。

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/ReEnablePolicy.kt app/src/test/java/com/blreay/bluetoothmonitor/ReEnablePolicyTest.kt
git commit -m "feat: decide Bluetooth re-enable from adapter state, not a timer

v1 gated its re-enable on isEnabled(), which the framework documents as
getState() == STATE_ON - so it was already false during STATE_TURNING_OFF.
The fixed 3s timer therefore called enable() mid-shutdown, hit the
documented 'immediate problem', got false back, and nothing ever retried.
Bluetooth stayed off. The javadoc says to listen for ACTION_STATE_CHANGED;
v1 used a timer instead.

decide() is a pure function of (state, phase, attempts) with no android.*
import, so the logic that was broken and completely untested in v1 is now
the best-tested part of the app: 18 tests pin every (phase x state) cell,
both sides of the attempt cap, and the two evaluation-order rules.

The cap is checked before the RETRY fallthrough on purpose. Without that
ordering a user who taps 'deny' on the MIUI toggle dialog leaves the alarm
chain arming a new alarm every second forever.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 3: BluetoothSwitch 增加 state() 和 enable()

**Files:**
- Modify: `app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt`

本任务只**新增**，不删 `enableIfOff()` —— 它还有两个调用方（`ReEnableScheduler`、`ReEnableReceiver`），删早了整个树编译不过。Task 8 再删。

import 不用动：`AdapterState` 和 `ReEnablePolicy` 与 `BluetoothSwitch` 同包。`android.Manifest` 和 `android.content.pm.PackageManager` 此时仍被 `enableIfOff()` 的权限检查用着，Task 8 才随之删除。

- [ ] **Step 1: 更新类注释**

把 `BluetoothSwitch.kt:10-18` 的类注释整块替换成：

```kotlin
/**
 * The only place in the app that touches the deprecated
 * [BluetoothAdapter.enable] and [BluetoothAdapter.disable], and the only
 * place that maps the framework's int state onto [AdapterState].
 *
 * Android 13 closed enable()/disable() to third-party apps targeting API
 * 33+; this app targets 32 specifically to keep them. The framework javadoc
 * lists the only exemptions as Device Owner, Profile Owner and system apps -
 * there is no permission a third-party app can request. They can still return
 * false when the OEM refuses, so every caller must check the result rather
 * than assume the radio actually changed state.
 *
 * Both calls are asynchronous: a true return means the transition has
 * *begun*, not that it finished. Callers must therefore never infer "it is
 * off now" from disable() returning true - they must observe STATE_OFF via
 * [state]. That inference is exactly what broke v1.
 */
```

- [ ] **Step 2: 加 state()**

在 `BluetoothSwitch.kt` 里 `adapter()` 函数（当前第 23-24 行）**之后**插入：

```kotlin

    /**
     * The adapter's state as our own enum, so [ReEnablePolicy] never has to
     * import android.bluetooth.
     *
     * UNKNOWN means "we cannot tell", covering a missing adapter, a
     * getState() that threw, and any value outside the four documented ones.
     * The policy gives up immediately on UNKNOWN, which is correct: no tick
     * can make progress without a readable state.
     */
    fun state(context: Context): AdapterState {
        val adapter = adapter(context) ?: return AdapterState.UNKNOWN
        return try {
            when (adapter.state) {
                BluetoothAdapter.STATE_OFF -> AdapterState.OFF
                BluetoothAdapter.STATE_TURNING_ON -> AdapterState.TURNING_ON
                BluetoothAdapter.STATE_ON -> AdapterState.ON
                BluetoothAdapter.STATE_TURNING_OFF -> AdapterState.TURNING_OFF
                else -> AdapterState.UNKNOWN
            }
        } catch (t: Throwable) {
            // SecurityException is possible if BLUETOOTH_CONNECT is revoked
            // mid-flight. "Cannot tell" is a safer answer than crashing the
            // alarm chain.
            Log.e(TAG, "getState() threw", t)
            AdapterState.UNKNOWN
        }
    }
```

- [ ] **Step 3: 加 enable()**

在 `enableIfOff()` 函数**之后**、`disable()` 函数**之前**插入：

```kotlin

    /**
     * Asks the system to turn Bluetooth on. Callers must not decide when to
     * call this from a timer or from `!isEnabled` - [ReEnablePolicy] decides,
     * and it only ever says yes at exactly STATE_OFF.
     *
     * @return what the framework said. A false return does NOT end the chain:
     *         the next tick re-reads the state and tries again, up to
     *         [ReEnablePolicy.MAX_ATTEMPTS]. That retry loop is the whole
     *         reason v2 works where v1 did not.
     */
    fun enable(adapter: BluetoothAdapter): Boolean {
        @Suppress("DEPRECATION")
        val ok = try {
            adapter.enable()
        } catch (t: Throwable) {
            Log.e(TAG, "enable() threw", t)
            false
        }
        if (ok) {
            Log.i(TAG, "enable() accepted; the next tick confirms STATE_ON")
        } else {
            Log.e(TAG, "enable() returned false; the next tick will retry")
        }
        return ok
    }
```

- [ ] **Step 4: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning。`enableIfOff()` 仍在文件里且仍被 `ReEnableReceiver.kt:26` 和 `ReEnableScheduler.kt:49` 调用，所以不会有 unused 警告。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt
git commit -m "feat: read adapter state through the framework constants

state() maps BluetoothAdapter.getState() onto AdapterState using the real
STATE_* constants rather than hand-copied 10/11/12/13 literals, so the
compiler guarantees the left-hand side of the mapping is right. UNKNOWN
covers a missing adapter, a throw, and any undocumented value.

enable() is now a plain call whose false return does not end the chain -
the next tick re-reads the state and retries. enableIfOff() is left in
place for now; it still has two callers and is removed once they move over.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 4: PendingResetStore 持久状态

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/PendingResetStore.kt`

慢段的唯一真相来源。必须是 SharedPreferences 而不是内存：闹钟每一跳都可能是系统重新拉起进程后的第一次执行，内存里的东西那时已经没了。

- [ ] **Step 1: 写 PendingResetStore**

```kotlin
package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log

/**
 * The slow phase's durable state, and the reason the alarm chain survives
 * MIUI reclaiming the process.
 *
 * Every tick of [ReEnableReceiver] may be the first thing this process has
 * ever done - the system starts it to deliver the alarm and nothing is in
 * memory. So the phase, the tick count and the debug flag all live here
 * rather than in a field.
 *
 * A single record on purpose: the app does one thing, and two concurrent
 * resets would fight over the same radio. Arming while already armed just
 * restarts the count, which is harmless because enable() is only ever called
 * at STATE_OFF.
 */
object PendingResetStore {

    private const val TAG = "BtReset.Store"
    private const val PREFS = "bt_reset_pending"
    private const val KEY_ARMED = "armed"
    private const val KEY_PHASE = "phase"
    private const val KEY_ATTEMPTS = "attempts"
    private const val KEY_WANTS_TOAST = "wants_toast"

    /**
     * @param wantsToast the `--ez toast true` debug flag, carried here because
     *        the tick that finally reports the outcome runs in a different
     *        process invocation than the one that started the reset, and so
     *        has no access to the original Intent.
     */
    data class Pending(
        val phase: ReEnablePolicy.Phase,
        val attempts: Int,
        val wantsToast: Boolean,
    )

    /** Arms a fresh chain: WAITING_OFF, zero ticks so far. */
    fun arm(context: Context, wantsToast: Boolean) {
        write(context, ReEnablePolicy.Phase.WAITING_OFF, 0, wantsToast)
        Log.i(TAG, "armed: phase=WAITING_OFF attempts=0 wantsToast=$wantsToast")
    }

    /**
     * @return null when nothing is armed, or when the stored phase cannot be
     *         parsed. A corrupt record must read as "nothing to do", never as
     *         a crash inside a BroadcastReceiver the system just started.
     */
    fun read(context: Context): Pending? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_ARMED, false)) return null
        val rawPhase = prefs.getString(KEY_PHASE, null) ?: return null
        val phase = try {
            ReEnablePolicy.Phase.valueOf(rawPhase)
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "unreadable phase '$rawPhase'; treating as not armed", e)
            return null
        }
        return Pending(
            phase = phase,
            attempts = prefs.getInt(KEY_ATTEMPTS, 0),
            wantsToast = prefs.getBoolean(KEY_WANTS_TOAST, false),
        )
    }

    fun write(
        context: Context,
        phase: ReEnablePolicy.Phase,
        attempts: Int,
        wantsToast: Boolean,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ARMED, true)
            .putString(KEY_PHASE, phase.name)
            .putInt(KEY_ATTEMPTS, attempts)
            .putBoolean(KEY_WANTS_TOAST, wantsToast)
            .apply()
    }

    /** Every give-up path and the success path must call this, or alarms leak. */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .clear()
            .apply()
        Log.i(TAG, "cleared")
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/PendingResetStore.kt
git commit -m "feat: persist the slow phase state so the alarm chain survives process death

Every tick of the re-enable chain may be the first thing the process has
ever done - the system starts it to deliver an alarm, so nothing is in
memory. Phase, tick count and the debug toast flag therefore live in
SharedPreferences.

The wantsToast flag has to be stored rather than passed by Intent because
the tick that reports the final outcome runs in a different process
invocation than the one that started the reset.

An unreadable phase reads as 'not armed' instead of throwing: a corrupt
record must not crash a receiver the system just started.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 5: Notifier 与 Toast 文案

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/Notifier.kt`
- Modify: `app/src/main/res/values/strings.xml`

- [ ] **Step 1: 加字符串**

把 `app/src/main/res/values/strings.xml` 整个替换成：

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">蓝牙重启</string>

    <!-- Shown unconditionally the moment the band is seen. Short duration,
         no confirmation needed, disappears by itself. -->
    <string name="toast_band_found">已找到手环，正在重启蓝牙…</string>

    <!-- Shown unconditionally once the chain observes STATE_ON again. -->
    <string name="toast_restored">蓝牙已恢复</string>

    <!-- Debug only, enabled by the toast extra (Notifier.EXTRA_TOAST). %1$s is
         the Result or Action enum name, which is why this is a format string. -->
    <string name="toast_outcome">蓝牙重启: %1$s</string>

    <!-- Always shown, regardless of the debug flag: if the grant is missing
         nothing works and the user has no other way to find out why. -->
    <string name="toast_permission_denied">未授予「附近的设备」权限，无法扫描或开关蓝牙</string>
</resources>
```

**XML 注释里不要出现字面的 `--ez`。** XML 规范禁止注释内出现 `--`，多数解析器会直接报错。不要用 `-&#45;` 这种转义绕过 —— XML 注释里的字符引用不会被解析，源码里就留下一串 mojibake。正确做法是注释里只提"toast extra"，把带 `--ez toast true` 的完整命令示例放进 `Notifier.EXTRA_TOAST` 的 **Kotlin KDoc**（那里 `--` 完全合法），提交后的 `Notifier.kt` 就是这么写的。

- [ ] **Step 2: 写 Notifier**

```kotlin
package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log
import android.widget.Toast

/**
 * The only place in the app that shows a Toast.
 *
 * Text toasts, not custom views: Android 11+ silently drops custom toast
 * views sent from the background, and by the time the chain reports its
 * outcome this process is very much in the background.
 *
 * Two messages are unconditional - "found the band" and "Bluetooth is back" -
 * because the user asked for a silent on-screen confirmation that needs no
 * tap and disappears on its own. Everything else is gated behind the
 * `--ez toast true` debug flag, which exists so the phone can be diagnosed
 * without a computer. v1 had no always-on output at all, which is why its
 * re-enable failure had to be reverse-engineered from the framework javadoc.
 *
 * Known risk: MIUI may suppress toasts from background apps. If real-device
 * testing shows nothing, the fallback is a short heads-up Notification.
 */
object Notifier {

    private const val TAG = "BtReset.Notify"

    /**
     * The `--ez toast true` extra, read by every entry point and carried into
     * [PendingResetStore] so the slow phase can honour it too.
     */
    const val EXTRA_TOAST = "toast"

    /** Unconditional: this is the message the user asked for. */
    fun bandFound(context: Context) {
        show(context, context.getString(R.string.toast_band_found))
    }

    /** Unconditional: proof the chain actually completed. */
    fun bluetoothRestored(context: Context) {
        show(context, context.getString(R.string.toast_restored))
    }

    /**
     * @param verbose pass `false` to suppress. Callers hand in the flag rather
     *        than reading it themselves so the decision stays in one place.
     */
    fun outcome(context: Context, verbose: Boolean, message: String) {
        if (!verbose) return
        show(context, context.getString(R.string.toast_outcome, message))
    }

    /**
     * Always shown. Used for the one failure the user cannot otherwise see:
     * a denied permission grant.
     */
    fun always(context: Context, message: String) {
        show(context, message)
    }

    private fun show(context: Context, text: String) {
        Log.i(TAG, "toast: $text")
        // applicationContext: a Toast outlives the Receiver or Service that
        // asked for it, and must not keep one alive.
        Toast.makeText(context.applicationContext, text, Toast.LENGTH_SHORT).show()
    }
}
```

- [ ] **Step 3: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/Notifier.kt app/src/main/res/values/strings.xml
git commit -m "feat: show a brief self-dismissing message when the band is matched

Two toasts are now unconditional - 'found the band' and 'Bluetooth is
back' - so the phone can be diagnosed without a computer. v1 gated every
message behind --ez toast true, which is why its re-enable failure had to
be reverse-engineered from the framework javadoc instead of read off the
screen.

Text toasts only: Android 11+ drops custom toast views from the
background, and the tick that reports the outcome runs in the background.

The debug flag is kept and widened - with it, every outcome is reported,
not just the two.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 6: 快段改用闹钟链（ReEnableScheduler + BandResetController）

**Files:**
- Rewrite: `app/src/main/java/com/blreay/bluetoothmonitor/ReEnableScheduler.kt`
- Modify: `app/src/main/java/com/blreay/bluetoothmonitor/BandResetController.kt`

两个文件必须同一个 commit 改完：`ReEnableScheduler.schedule(context, handler)` 的唯一调用方就是 `BandResetController.cycleBluetooth()`，分开改中间那次 commit 编译不过。

改完之后**全项目不再有任何进程内重开定时器**。

- [ ] **Step 1: 重写 ReEnableScheduler**

把 `ReEnableScheduler.kt` 整个文件替换成：

```kotlin
package com.blreay.bluetoothmonitor

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Arms one tick of the re-enable chain.
 *
 * A chain rather than a single delayed callback, because the thing being
 * waited for has no predictable duration: MIUI shows a permission dialog
 * before the radio even starts turning off, and then gracefully tears down
 * every Bluetooth connection. v1 guessed 3 seconds, called enable() while
 * the state was still TURNING_OFF, and the framework rejected it.
 *
 * Each tick re-reads the state via [ReEnableReceiver] and re-arms itself
 * until [ReEnablePolicy] says stop. Nothing here knows how long Bluetooth
 * takes to turn off, and that is the point.
 *
 * The chain lives entirely in AlarmManager plus [PendingResetStore]. There
 * is no in-process timer any more: a Handler callback dies with the process,
 * and MIUI reclaims this one readily.
 */
object ReEnableScheduler {

    /**
     * One second per tick. With [ReEnablePolicy.MAX_ATTEMPTS] that is a two
     * minute ceiling - long enough for a human to read and tap the MIUI
     * dialog, short enough that a stuck chain stops by itself.
     */
    const val TICK_INTERVAL_MS = 1_000L

    private const val TAG = "BtReset.Sched"
    private const val REQUEST_CODE = 1

    /**
     * Arms the next tick. Called once to start the chain and then once per
     * tick to continue it.
     *
     * @param context must be an application context - the PendingIntent and
     *                the alarm both outlive the caller.
     */
    fun armNextTick(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
        if (am == null) {
            // Fatal for the slow phase: nothing else can re-enable Bluetooth.
            Log.e(TAG, "AlarmManager unavailable; Bluetooth will stay OFF and needs a manual toggle")
            return
        }

        val triggerAt = System.currentTimeMillis() + TICK_INTERVAL_MS
        val pending = tickPendingIntent(context)

        if (am.canScheduleExactAlarms()) {
            // setExact, NOT setExactAndAllowWhileIdle: the latter is throttled
            // to roughly one firing per 9 seconds while idle, which would
            // stretch a 1s retry chain into something unusable. The screen is
            // on and the user just triggered this, so Doze is not a concern.
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pending)
        } else {
            // SCHEDULE_EXACT_ALARM is granted by default at targetSdk 32, so
            // this should not happen. If it does the chain still runs, just
            // coarsely, and Bluetooth may stay off longer than two minutes.
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pending)
            Log.e(
                TAG,
                "exact alarms not permitted; ticks will be inexact - " +
                    "Bluetooth may stay OFF longer than the two minute ceiling"
            )
        }
    }

    /**
     * Cancels any armed tick. Every terminal path - success and all three
     * give-ups - must call this, otherwise a stale alarm fires later and
     * finds nothing in the store.
     */
    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        am.cancel(tickPendingIntent(context))
        Log.i(TAG, "chain cancelled")
    }

    private fun tickPendingIntent(context: Context): PendingIntent {
        // Explicit intent to the class: exempt from the Android 8+ ban on
        // implicit broadcasts to manifest-declared receivers, and it lets the
        // system start the process to deliver the tick after MIUI reclaimed it.
        val intent = Intent(context, ReEnableReceiver::class.java)
            .setAction(ReEnableReceiver.ACTION_REENABLE)
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE,
            intent,
            // FLAG_IMMUTABLE is mandatory on API 31+, which is our minSdk.
            // FLAG_UPDATE_CURRENT makes re-arming replace the previous tick
            // instead of stacking, so at most one alarm is ever pending.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

- [ ] **Step 2: 改 BandResetController 的类注释**

把 `BandResetController.kt:13-30` 的类注释和 `context` 行注释整块替换成：

```kotlin
/**
 * The fast phase: find the band, then turn Bluetooth off and hand the
 * turning-back-on to the alarm chain.
 *
 * Deliberately free of any UI dependency so [ResetService] and
 * [TriggerReceiver] share one implementation and cannot drift apart. The
 * caller supplies the scan window: the Service can afford 8s, the broadcast
 * receiver must fit inside goAsync()'s ~10s budget and uses 6s.
 *
 * Callback contract: [Callback.onResult] fires exactly once and means "the
 * fast phase is finished". For [Result.RESET_TRIGGERED] it does NOT mean
 * Bluetooth is off yet, and it certainly does not mean it is back on -
 * disable() only begins the transition. The caller should stop itself
 * immediately; the slow phase is owned by [ReEnableReceiver] and survives
 * this process dying.
 *
 * v1 told callers to stay alive for 3.5s here to protect an in-process
 * re-enable timer. That is gone, and with it the reason the launcher
 * Activity had to hold input focus while the user was looking at their home
 * screen.
 */
// `context` must be an application context: the scan callback outlives
// whichever caller supplied it.
```

- [ ] **Step 3: 改 run() 的签名和入口守卫**

把 `BandResetController.kt:63-100`（`run()` 的 KDoc 和函数体）整块替换成：

```kotlin
    /**
     * Starts the fast phase. Returns immediately; the outcome arrives on
     * [callback], on the main thread, exactly once.
     *
     * @param scanTimeoutMs how long to scan before reporting NOT_FOUND. The
     *        Service can afford 8s; the broadcast receiver must fit inside
     *        goAsync()'s ~10s budget and uses 6s.
     * @param wantsToast the `--ez toast true` debug flag. Passed in rather
     *        than read from an Intent so both entry points behave identically.
     */
    fun run(scanTimeoutMs: Long, wantsToast: Boolean, callback: Callback) {
        try {
            val adapter = BluetoothSwitch.adapter(context)
            if (adapter == null) {
                Log.e(TAG, "no BluetoothAdapter; this device has no Bluetooth radio")
                emit(callback, wantsToast, Result.NO_ADAPTER)
                return
            }
            // state, not isEnabled. isEnabled is documented as
            // getState() == STATE_ON, so it is false during TURNING_OFF and
            // TURNING_ON too - which would let a second tap on the launcher
            // icon start a second cycle against a radio that is mid-transition.
            val state = BluetoothSwitch.state(context)
            if (state != AdapterState.ON) {
                Log.i(TAG, "Bluetooth is not ON (state=$state); exiting without doing anything")
                emit(callback, wantsToast, Result.BLUETOOTH_OFF)
                return
            }
            if (!BtPermissions.allGranted(context)) {
                Log.w(TAG, "missing permission(s) ${BtPermissions.missing(context)}; cannot scan")
                emit(callback, wantsToast, Result.NEEDS_PERMISSION)
                return
            }
            val scanner = adapter.bluetoothLeScanner
            if (scanner == null) {
                Log.e(TAG, "BluetoothLeScanner is null even though Bluetooth reports ON")
                emit(callback, wantsToast, Result.ERROR)
                return
            }
            Session(adapter, scanner, wantsToast, callback).start(scanTimeoutMs)
        } catch (t: Throwable) {
            Log.e(TAG, "controller failed before scanning", t)
            emit(callback, wantsToast, Result.ERROR)
        }
    }

    /**
     * The single exit for every early return, so the debug toast and the
     * callback cannot get out of step. The Session has its own emit() because
     * it also has to guard against reporting twice.
     */
    private fun emit(callback: Callback, wantsToast: Boolean, result: Result) {
        Notifier.outcome(context, wantsToast, result.name)
        callback.onResult(result)
    }
```

- [ ] **Step 4: 改 Session 的构造和 cycleBluetooth()**

把 `BandResetController.kt:102-113`（Session 的 KDoc、构造参数、`handler`、`settled`）替换成：

```kotlin
    /**
     * One scan-and-cycle attempt. Holds the mutable "have I already reported
     * a result" state so the timeout, the scan callback and a scan failure
     * cannot race into reporting twice.
     *
     * The Handler here is for the scan timeout only. v1 also used it to
     * post the re-enable; that is what made the launcher Activity hold input
     * focus for seconds, and it is gone.
     */
    private inner class Session(
        private val adapter: BluetoothAdapter,
        private val scanner: BluetoothLeScanner,
        private val wantsToast: Boolean,
        private val callback: Callback,
    ) {
        private val handler = Handler(Looper.getMainLooper())
        private var settled = false
```

把 `BandResetController.kt:122-132` 的 `onScanResult` 替换成：

```kotlin
        private val scanCallback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                if (settled) return
                val mac = result.device?.address
                val name = advertisedName(result)
                Log.d(TAG, "seen $mac name=${name ?: "<none>"}")
                if (!BandMatcher.isTarget(mac, name)) return
                Log.i(TAG, "band is visible: $mac name=${name ?: "<none>"}")
                teardown()
                // Before disable(), so the message appears even when MIUI then
                // refuses the toggle. It is also the moment the user asked to
                // be told about.
                Notifier.bandFound(context)
                cycleBluetooth()
            }
```

把 `BandResetController.kt:198-216`（`cycleBluetooth()` 和 `emit()`）替换成：

```kotlin
        private fun cycleBluetooth() {
            if (!BluetoothSwitch.disable(adapter)) {
                // Bluetooth never began turning off, so nothing must be armed.
                // Arming a chain here would eventually enable() a radio that
                // was already on.
                Log.e(TAG, "system refused to turn Bluetooth off; leaving it ON and exiting")
                emit(Result.DISABLE_FAILED)
                return
            }
            // disable() returning true means the transition has BEGUN. The
            // radio is almost certainly still in STATE_TURNING_OFF right now,
            // and calling enable() here is exactly what broke v1. Arm the
            // chain and let it wait for a real STATE_OFF.
            Log.i(TAG, "disable() accepted; arming the state-driven re-enable chain")
            PendingResetStore.arm(context, wantsToast)
            ReEnableScheduler.armNextTick(context)
            emit(Result.RESET_TRIGGERED)
        }

        private fun emit(result: Result) {
            if (settled) return
            settled = true
            Notifier.outcome(context, wantsToast, result.name)
            callback.onResult(result)
        }
```

- [ ] **Step 5: 改 Result 枚举的两条 KDoc**

枚举的**值一个都不动**（`ResetService`、`TriggerReceiver`、README 都按名字引用），但有两条评论现在说假话了。

把 `BandResetController.kt:37-38` 替换成：

```kotlin
        /**
         * Bluetooth was not in STATE_ON, so there was nothing to reset. Covers
         * OFF as well as TURNING_OFF / TURNING_ON: starting a cycle against a
         * radio that is mid-transition is what produced v1's stuck-off bug.
         */
        BLUETOOTH_OFF,
```

把 `BandResetController.kt:52-53` 替换成：

```kotlin
        /**
         * Band found, disable() accepted, alarm chain armed. This says nothing
         * about the radio's state right now - it is almost certainly still
         * TURNING_OFF. [ReEnableReceiver] owns the outcome from here.
         */
        RESET_TRIGGERED,
```

`DISABLE_FAILED` 那条（"Bluetooth is still ON. No re-enable was scheduled."）继续成立，不动。

- [ ] **Step 6: 编译 + 全量测试**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`，28 条测试全过（`ReEnablePolicyTest` 18 + `BandMatcherTest` 10）。

这一步能过的原因是：`ReEnableScheduler.schedule()` 的唯一调用方就是 `BandResetController.cycleBluetooth()`，Step 4 已经把它换掉了；而 `ReEnableReceiver.kt:26` 调的是 `BluetoothSwitch.enableIfOff()`，那个函数 Task 3 特意保留着，要到 Task 8 才删。

如果这里报 `ReEnableScheduler` 或 `enableIfOff` 的 unresolved reference，说明 Step 1 或 Task 3 没按计划落地 —— 用 `grep -rn "schedule\|enableIfOff" app/src/main/` 定位，不要靠猜。

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/ReEnableScheduler.kt app/src/main/java/com/blreay/bluetoothmonitor/BandResetController.kt
git commit -m "feat: hand re-enable to a per-second alarm chain

ReEnableScheduler no longer posts anything to a Handler. It arms one exact
alarm per tick and the tick re-arms the next, so the chain lives entirely
in AlarmManager plus PendingResetStore and survives the process dying.

setExact rather than setExactAndAllowWhileIdle: the latter is throttled to
roughly one firing per 9s while idle, which would stretch a 1s retry chain
into something unusable.

cycleBluetooth() now arms WAITING_OFF instead of calling enable() after a
guessed delay. disable() returning true only means the transition began -
the radio is still TURNING_OFF at that instant, and enabling there is the
bug being fixed.

The controller's entry guard reads state() instead of isEnabled, so a
second tap on the icon cannot start a second cycle against a radio that is
mid-transition.

Drops the v1 instruction that callers stay alive 3.5s to protect an
in-process timer. That instruction is why the launcher Activity held input
focus while the user looked at their home screen.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 7: ReEnableReceiver 执行状态机

**Files:**
- Rewrite: `app/src/main/java/com/blreay/bluetoothmonitor/ReEnableReceiver.kt`

- [ ] **Step 1: 重写 ReEnableReceiver**

把 `ReEnableReceiver.kt` 整个文件替换成：

```kotlin
package com.blreay.bluetoothmonitor

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * One tick of the re-enable chain: read the radio, ask the policy, act.
 *
 * Declared in the manifest rather than registered at runtime, so the system
 * can start the process to deliver a tick even after MIUI has reclaimed it.
 * Reached only by an explicit PendingIntent aimed at this class, which is
 * why exported=false is safe and why the Android 8+ implicit-broadcast ban
 * does not apply.
 *
 * This may be the first thing the process has ever done, so nothing is read
 * from memory: the phase, the tick count and the debug flag all come from
 * [PendingResetStore].
 *
 * Every path either re-arms the chain or terminates it. There is no path
 * that returns without doing one of those, or the chain would stall with
 * Bluetooth off.
 */
class ReEnableReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_REENABLE) {
            Log.w(TAG, "ignoring unexpected action ${intent.action}")
            return
        }

        // applicationContext: the store and the scheduler both outlive
        // onReceive, and a receiver context must not leak into a PendingIntent.
        val app = context.applicationContext

        val pending = PendingResetStore.read(app)
        if (pending == null) {
            // Normal after a success or a give-up: a tick that was already in
            // flight when the chain terminated. Not an error.
            Log.i(TAG, "nothing armed; this tick is stale, ignoring")
            return
        }

        val state = BluetoothSwitch.state(app)
        val action = ReEnablePolicy.decide(state, pending.phase, pending.attempts)
        val nextAttempts = pending.attempts + 1
        Log.i(
            TAG,
            "tick attempts=${pending.attempts} phase=${pending.phase} state=$state -> $action"
        )

        when (action) {
            ReEnablePolicy.Action.ENABLE_AND_RETRY -> {
                val adapter = BluetoothSwitch.adapter(app)
                if (adapter == null) {
                    // state() would have returned UNKNOWN and the policy would
                    // have given up, so this is a race, not a path. Bail safely.
                    Log.e(TAG, "adapter vanished between state() and enable(); terminating")
                    terminate(app, pending.wantsToast, action)
                    return
                }
                // The return value is logged by BluetoothSwitch and then
                // deliberately ignored: whether enable() "worked" is answered
                // by the next tick reading STATE_ON, not by this boolean.
                BluetoothSwitch.enable(adapter)
                PendingResetStore.write(
                    app,
                    ReEnablePolicy.Phase.WAITING_ON,
                    nextAttempts,
                    pending.wantsToast
                )
                ReEnableScheduler.armNextTick(app)
            }

            ReEnablePolicy.Action.RETRY -> {
                PendingResetStore.write(app, pending.phase, nextAttempts, pending.wantsToast)
                ReEnableScheduler.armNextTick(app)
            }

            ReEnablePolicy.Action.DONE_RESTORED -> {
                Log.i(TAG, "Bluetooth is back on after $nextAttempts tick(s)")
                Notifier.bluetoothRestored(app)
                terminate(app, pending.wantsToast, action)
            }

            ReEnablePolicy.Action.GIVE_UP_NO_ADAPTER,
            ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_OFF,
            ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_BACK_ON -> {
                // NEVER_TURNED_OFF means the radio never left STATE_ON: the
                // user tapped deny on the MIUI dialog, or MIUI refused.
                // Bluetooth is still on and usable - not a stranded state.
                // NEVER_TURNED_BACK_ON is the bad one: it is off and needs a
                // manual toggle.
                Log.e(
                    TAG,
                    "giving up ($action) after $nextAttempts tick(s), last state=$state" +
                        if (action == ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_BACK_ON) {
                            " - Bluetooth is OFF and needs a manual toggle"
                        } else {
                            ""
                        }
                )
                terminate(app, pending.wantsToast, action)
            }
        }
    }

    /** Clears the store and cancels the chain, then reports if asked to. */
    private fun terminate(context: Context, wantsToast: Boolean, action: ReEnablePolicy.Action) {
        Notifier.outcome(context, wantsToast, action.name)
        PendingResetStore.clear(context)
        ReEnableScheduler.cancel(context)
    }

    companion object {
        const val ACTION_REENABLE = "com.blreay.bluetoothmonitor.action.REENABLE"
        private const val TAG = "BtReset.ReEnable"
    }
}
```

- [ ] **Step 2: 编译 + 全量测试**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`，28 条测试全过

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/ReEnableReceiver.kt
git commit -m "feat: execute the re-enable state machine one alarm tick at a time

Each tick reads the radio, asks ReEnablePolicy, and either re-arms or
terminates. There is no path that returns without doing one of those, or
the chain would stall with Bluetooth off.

enable()'s return value is logged and then deliberately ignored - whether
it worked is answered by the next tick observing STATE_ON, not by that
boolean. Treating the boolean as the answer is what made v1 give up after
one rejection.

State comes from PendingResetStore, never from memory: a tick is often the
first thing this process has ever done.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 8: 删掉带 bug 的 enableIfOff()

**Files:**
- Modify: `app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt`

- [ ] **Step 1: 确认已无调用方**

Run: `grep -rn "enableIfOff" app/src/`
Expected: 只命中 `BluetoothSwitch.kt` 里的定义和它的 KDoc，**没有任何调用点**。如果还有调用点，说明 Task 6 或 7 没改干净，先回去修。

- [ ] **Step 2: 删除函数**

删掉 `BluetoothSwitch.kt` 里 `enableIfOff()` 的整个函数，包括它上面的 KDoc 注释块（"Turns Bluetooth on if it is currently off. Idempotent, ..." 那一段）。

同时删掉因此不再需要的 import：`android.Manifest` 和 `android.content.pm.PackageManager`（这两个只被 `enableIfOff` 里的权限检查用到）。删除后 `BluetoothSwitch.kt` 的 import 块应为：

```kotlin
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
```

- [ ] **Step 3: 编译 + 全量测试**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`，28 条测试全过，**无 unused import warning**

- [ ] **Step 4: 确认全项目再无进程内重开定时器**

Run: `grep -rn "IN_PROCESS_DELAY_MS\|ALARM_DELAY_MS\|postDelayed" app/src/main/java/`
Expected: 只命中两处，都是扫描超时/backstop 用途，都与重开蓝牙无关：
- `BandResetController.kt` 的 `handler.postDelayed(onTimeout, scanTimeoutMs)`
- `TriggerReceiver.kt` 的 `handler.postDelayed({ finishOnce() }, GO_ASYNC_BACKSTOP_MS)`

`IN_PROCESS_DELAY_MS` 和 `ALARM_DELAY_MS` 应当**零命中** —— 这两个常量随 Task 6 的重写一起消失了。

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/BluetoothSwitch.kt
git commit -m "refactor: remove the isEnabled() gate that broke re-enable

enableIfOff() decided when to enable from \`!isEnabled\`. The framework
documents isEnabled() as getState() == STATE_ON, so it is already false
during STATE_TURNING_OFF - meaning a timer-driven call landed mid-shutdown,
hit the documented 'immediate problem', returned false, and nothing retried.

ReEnablePolicy now owns that decision and only says enable at exactly
STATE_OFF. Removing the old function makes the bad gate uncallable rather
than merely unused.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 9: ResetService

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/ResetService.kt`

- [ ] **Step 1: 写 ResetService**

```kotlin
package com.blreay.bluetoothmonitor

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log

/**
 * Runs the fast phase for the launcher-icon path.
 *
 * Started, not foreground: a foreground Service would put a notification in
 * the status bar, and Android 12+ throws
 * ForegroundServiceStartNotAllowedException when a background app starts one
 * - which would break the sendBroadcast entry point outright.
 *
 * Being started from [MainActivity] while it is in the foreground is what
 * makes this legal, and the fast phase is bounded at 8s, well inside the
 * window Android 8+ gives a backgrounded app's services before stopping
 * them. If MIUI does reclaim it mid-scan, nothing has been armed yet and
 * Bluetooth is still on - there is no half-state to recover from.
 *
 * The slow phase is not here. It belongs to [ReEnableReceiver] and survives
 * this Service being stopped, which is the whole reason it was split out.
 */
class ResetService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val wantsToast = intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false
        Log.i(TAG, "fast phase starting (wantsToast=$wantsToast)")

        BandResetController(applicationContext).run(SCAN_TIMEOUT_MS, wantsToast) { result ->
            Log.i(TAG, "result=$result")
            // Whatever happened, this Service is done. On RESET_TRIGGERED the
            // alarm chain now owns the outcome and does not need us alive -
            // v1 kept its Activity alive here, and holding input focus for
            // seconds is what made the home screen feel frozen.
            stopSelf()
        }

        // Not sticky: if the system kills this, restarting it would begin a
        // second scan for no reason. A slow phase already in flight is driven
        // by alarms, which do not need this Service.
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "BtReset.Svc"

        /**
         * The Service has no goAsync() budget to fit inside, unlike
         * [TriggerReceiver], so it can afford the longer window.
         */
        const val SCAN_TIMEOUT_MS = 8_000L

        /**
         * The one way to start the fast phase, shared by [MainActivity] and
         * [PermissionActivity] so they cannot drift apart.
         */
        fun start(context: Context, wantsToast: Boolean) {
            val intent = Intent(context, ResetService::class.java)
                .putExtra(Notifier.EXTRA_TOAST, wantsToast)
            context.startService(intent)
        }
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/ResetService.kt
git commit -m "feat: run the fast phase in a started service

Started rather than foreground: a foreground Service would put a
notification in the status bar, and Android 12+ throws
ForegroundServiceStartNotAllowedException when a background app starts one,
which would break the sendBroadcast entry point outright.

Being started from the launcher Activity while it is foreground is what
makes this legal, and the fast phase is bounded at 8s - well inside the
window Android 8+ gives a backgrounded app's services.

stopSelf() fires on every result including RESET_TRIGGERED. v1 kept its
Activity alive at exactly this point to protect an in-process timer, and
holding input focus for seconds is what made the home screen feel frozen.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 10: PermissionActivity

**Files:**
- Create: `app/src/main/java/com/blreay/bluetoothmonitor/PermissionActivity.kt`

`Theme.NoDisplay` 要求在 `onResume()` 之前 `finish()`，所以 NoDisplay 的 Activity 不能承载 `requestPermissions()` —— 那个回调要等系统权限对话框返回。授权因此必须有自己的 Activity。这也是全项目唯一会出现窗口的地方，且只在首次运行时出现。

- [ ] **Step 1: 写 PermissionActivity**

```kotlin
package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log

/**
 * Requests the Nearby-devices grant, then starts the fast phase.
 *
 * This is the only window the app ever creates, and it only ever appears on
 * first run - the grant persists across launches and across overwrite
 * installs, because the signing key is fixed.
 *
 * It has to be a separate Activity because [MainActivity] uses
 * Theme.NoDisplay, which requires finish() before onResume(). A permission
 * request cannot survive that: onRequestPermissionsResult only arrives after
 * the system dialog returns, which is well past onResume().
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI beyond
 * the system dialog, and the app has no androidx dependencies at all.
 */
class PermissionActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (BtPermissions.allGranted(this)) {
            // Granted between MainActivity's check and here, or this Activity
            // was started directly. Either way, just run.
            ResetService.start(this, wantsToast())
            finish()
            return
        }
        Log.i(TAG, "requesting Nearby-devices permissions (first run only)")
        requestPermissions(BtPermissions.REQUIRED, REQUEST_CODE_PERMISSIONS)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_CODE_PERMISSIONS) {
            finish()
            return
        }
        val granted = grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        if (granted) {
            Log.i(TAG, "permissions granted")
            ResetService.start(this, wantsToast())
        } else {
            // Shown unconditionally, ignoring the debug flag: without this
            // grant nothing works, and a silent no-op would leave the user
            // with no idea why. Not retried and not looped - one message,
            // then finish.
            Log.e(TAG, "permission denied; cannot scan or toggle Bluetooth")
            Notifier.always(this, getString(R.string.toast_permission_denied))
        }
        finish()
    }

    private fun wantsToast(): Boolean =
        intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false

    companion object {
        private const val TAG = "BtReset.Perm"
        private const val REQUEST_CODE_PERMISSIONS = 1
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/PermissionActivity.kt
git commit -m "feat: move the permission request out of the launcher activity

MainActivity is becoming Theme.NoDisplay, which requires finish() before
onResume(). A permission request cannot survive that - the result callback
only arrives after the system dialog returns, long past onResume().

So this becomes the only window the app ever creates, and it only appears
on first run since the grant persists across overwrite installs.

A denial shows a message unconditionally, ignoring the debug flag: without
the grant nothing works, and a silent no-op would leave the user with no
idea why.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 11: MainActivity 变成零窗口壳

**Files:**
- Rewrite: `app/src/main/java/com/blreay/bluetoothmonitor/MainActivity.kt`

这是修 ④ 卡顿的那一刀。

- [ ] **Step 1: 重写 MainActivity**

把 `MainActivity.kt` 整个文件替换成：

```kotlin
package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.util.Log

/**
 * The launcher icon, and nothing else. Dispatches, then finishes.
 *
 * Theme.NoDisplay, and finish() inside onCreate() as that theme requires.
 * The system never creates a window for this Activity, so it never takes
 * input focus.
 *
 * That last part is the fix. v1 used a translucent theme and deliberately
 * stayed alive for 3.5s after a match - 8s when nothing was found - to
 * protect an in-process re-enable timer. Translucent keeps the home screen
 * visible while the Activity on top owns the focus, so the desktop was
 * visible but dead to touch for that whole window. Replacing the timer with
 * an alarm chain removed the reason to stay alive, which removed the jank.
 *
 * A launcher icon requires an Activity: launchers enumerate activities with
 * MAIN/LAUNCHER, and a Service or Receiver never appears on the home screen.
 * So the Activity stays, and is instead made free.
 *
 * Extends android.app.Activity, not AppCompatActivity: there is no UI, and
 * the app has no androidx dependencies at all.
 */
class MainActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val wantsToast = intent?.getBooleanExtra(Notifier.EXTRA_TOAST, false) ?: false
        if (BtPermissions.allGranted(this)) {
            Log.i(TAG, "dispatching to ResetService (wantsToast=$wantsToast)")
            ResetService.start(this, wantsToast)
        } else {
            Log.i(TAG, "dispatching to PermissionActivity (first run only)")
            startActivity(
                Intent(this, PermissionActivity::class.java)
                    .putExtra(Notifier.EXTRA_TOAST, wantsToast)
            )
        }
        // Unconditional, and before any return path. Theme.NoDisplay requires
        // finish() before onResume(), and finishing immediately is the entire
        // point: this Activity must never own a window or hold focus.
        finish()
    }

    companion object {
        private const val TAG = "BtReset.Main"
    }
}
```

- [ ] **Step 2: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" compileReleaseKotlin --stacktrace`
Expected: `BUILD SUCCESSFUL`，无 warning（`TAG` 有被两条日志用到，不会报 unused）

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/MainActivity.kt
git commit -m "fix: stop the launcher activity holding input focus for seconds

v1 used a translucent theme and deliberately stayed alive 3.5s after a
match - 8s when nothing was found - to protect an in-process re-enable
timer. Translucent keeps the home screen visible while the Activity on top
owns the focus, so the desktop was visible but dead to touch for that whole
window. That is the severe jank reported from real-device testing.

The alarm chain removed the reason to stay alive, so this now dispatches
and finishes inside onCreate(). Theme.NoDisplay means the system never
creates a window for it at all, so it never takes focus.

A launcher icon still requires an Activity - launchers enumerate
MAIN/LAUNCHER activities and a Service never appears on the home screen -
so the Activity stays and is instead made free.

Permission handling moves to PermissionActivity, since NoDisplay cannot
survive to onRequestPermissionsResult.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 12: TriggerReceiver 接上新控制器

**Files:**
- Modify: `app/src/main/java/com/blreay/bluetoothmonitor/TriggerReceiver.kt`

改动很小：读 `wantsToast`、传给 `run()`、更新注释。**不 `startService`** —— Android 8+ 禁止后台应用启动 Service，会抛 `IllegalStateException`。继续用 `goAsync()` 内联跑快段正好绕开。

- [ ] **Step 1: 更新类注释**

把 `TriggerReceiver.kt:10-25` 的类注释整块替换成：

```kotlin
/**
 * Entry point for `sendBroadcast` callers: Tasker, MacroDroid, Termux.
 *
 * Runs the fast phase inline under goAsync() rather than starting
 * [ResetService], because Android 8+ forbids a background app from calling
 * startService() and this receiver is always in the background. goAsync()
 * buys about ten seconds, so the scan is capped at 6s.
 *
 * The slow phase needs no help from here: [BandResetController] arms an
 * alarm chain that the system delivers even after this process is reclaimed
 * the moment goAsync() ends.
 *
 * Cannot prompt for permissions - there is no UI and Android 10+ blocks
 * starting an Activity from the background. If the grant is missing it logs
 * and gives up; launching the icon once is the only way to grant it.
 *
 * Left exported without a signature permission on purpose, so Tasker,
 * MacroDroid and Termux can all trigger it. Worst case for an uninvited
 * caller is one extra Bluetooth cycle.
 */
```

- [ ] **Step 2: 传入 wantsToast**

把 `TriggerReceiver.kt:54-61` 替换成：

```kotlin
        // applicationContext: the scan callback outlives onReceive.
        val wantsToast = intent.getBooleanExtra(Notifier.EXTRA_TOAST, false)
        BandResetController(context.applicationContext).run(
            RECEIVER_SCAN_TIMEOUT_MS,
            wantsToast
        ) { result ->
            Log.i(TAG, "result=$result")
            if (result == BandResetController.Result.RESET_TRIGGERED) {
                Log.i(TAG, "Bluetooth is turning off; the alarm chain will bring it back")
            }
            finishOnce()
        }
```

- [ ] **Step 3: 编译 + 全量测试**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`，28 条测试全过

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/blreay/bluetoothmonitor/TriggerReceiver.kt
git commit -m "feat: pass the debug toast flag through the broadcast entry point

Still runs the fast phase inline under goAsync() rather than starting
ResetService: Android 8+ forbids a background app from calling startService()
and this receiver is always in the background.

The slow phase needs no help from here - the controller arms an alarm chain
that the system delivers even after this process is reclaimed.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 13: Manifest 与主题

**Files:**
- Modify: `app/src/main/AndroidManifest.xml`
- Modify: `app/src/main/res/values/themes.xml`

- [ ] **Step 1: 改 MainActivity 的主题**

把 `AndroidManifest.xml:32-55`（MainActivity 的注释和整个 `<activity>` 块）替换成：

```xml
        <!--
          The launcher icon, and nothing else. It dispatches and finishes
          inside onCreate().

          Theme.NoDisplay means the system never creates a window for it, so
          it never takes input focus. v1 used a translucent theme and stayed
          alive for seconds, which kept the home screen visible but dead to
          touch - the jank this replaces. NoDisplay requires finish() before
          onResume(), which is exactly what MainActivity does.

          A launcher icon requires an Activity: launchers enumerate
          MAIN/LAUNCHER activities, and a Service never appears on the home
          screen.

          exported=true is required: it is the launcher target and the entry
          point for Tasker / MacroDroid / Automate / `am start`.
        -->
        <activity
            android:name=".MainActivity"
            android:excludeFromRecents="true"
            android:exported="true"
            android:launchMode="singleTop"
            android:taskAffinity=""
            android:theme="@android:style/Theme.NoDisplay">
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
          The only window this app ever creates, and it only appears on first
          run - the grant persists across launches and across overwrite
          installs, because the signing key is fixed.

          Separate from MainActivity because NoDisplay cannot survive to
          onRequestPermissionsResult. exported=false: reached only by our own
          startActivity from MainActivity.
        -->
        <activity
            android:name=".PermissionActivity"
            android:excludeFromRecents="true"
            android:exported="false"
            android:taskAffinity=""
            android:theme="@style/Theme.BtReset.Invisible" />

        <!--
          Runs the fast phase for the launcher-icon path. Started, not
          foreground: a foreground Service would show a notification, and
          Android 12+ blocks background apps from starting one. exported=false:
          reached only by our own startService.
        -->
        <service
            android:name=".ResetService"
            android:exported="false" />
```

`TriggerReceiver` 和 `ReEnableReceiver` 两块**原样不动**。

- [ ] **Step 2: 更新 themes.xml 的注释**

把 `themes.xml:3-20` 的注释整块替换成：

```xml
    <!--
      The invisible Activity theme, used by PermissionActivity only.

      MainActivity uses Theme.NoDisplay instead, which creates no window at
      all. This theme cannot do that - it has to stay resumable long enough
      to receive onRequestPermissionsResult - so it is translucent, which
      suppresses the Android 12+ system splash icon and keeps whatever is
      behind it visible.

      PermissionActivity only ever appears on first run, and it immediately
      shows the system permission dialog on top, so the residual ~100-300ms
      of unresponsiveness is not user-visible in practice.

      backgroundDimEnabled=false matters: a translucent Activity dims what is
      behind it by default, and that dim WOULD be visible.

      windowAnimationStyle=@null removes the Activity transition animation.
      Transparent system bars stop a flicker when the previous window used
      different bar colours.
    -->
```

- [ ] **Step 3: 编译验证**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" assembleRelease --stacktrace`
Expected: `BUILD SUCCESSFUL`

- [ ] **Step 4: 用 aapt2 确认 manifest 真的落地了**

Run:
```bash
source ~/.cache/btreset-build/env.sh && "$ANDROID_HOME/build-tools/34.0.0/aapt2" dump xmltree --file AndroidManifest.xml app/build/outputs/apk/release/app-release.apk
```
Expected: 输出中能找到全部四项
- `A: android:theme(0x01010000)=@android:style/Theme.NoDisplay` 出现在 `.MainActivity` 的 `E: activity` 块里
- `E: activity` + `A: android:name="...PermissionActivity"` + `android:exported=false`
- `E: service` + `A: android:name="...ResetService"` + `android:exported=false`
- `A: android:name="...MainActivity"` 的块里仍有 `android.intent.action.MAIN` 和 `android.intent.category.LAUNCHER`

同时确认 `targetSdkVersion` 没被动过：
```bash
source ~/.cache/btreset-build/env.sh && "$ANDROID_HOME/build-tools/34.0.0/aapt2" dump badging app/build/outputs/apk/release/app-release.apk | grep -E "targetSdkVersion|package:"
```
Expected: `package: name='com.blreay.bluetoothmonitor' ...` 且 `targetSdkVersion:'32'`

- [ ] **Step 5: Commit**

```bash
git add app/src/main/AndroidManifest.xml app/src/main/res/values/themes.xml
git commit -m "feat: declare the no-display launcher, permission activity and reset service

MainActivity switches to Theme.NoDisplay, which creates no window at all -
the manifest-level half of the jank fix. The launcher icon still needs an
Activity, since launchers enumerate MAIN/LAUNCHER activities and a Service
never appears on the home screen.

PermissionActivity is new and is the only window the app ever creates; it
exists because NoDisplay cannot survive to onRequestPermissionsResult.

ResetService is started rather than foreground, so no notification and no
ForegroundServiceStartNotAllowedException on the broadcast path.

The invisible translucent theme stays, now used by PermissionActivity only.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 14: 全量构建与产物验证

**Files:** 无（只验证；若发现问题则修相应文件并追加 commit）

- [ ] **Step 1: 清构建，跑全部单测**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" clean testReleaseUnitTest --stacktrace`
Expected: `BUILD SUCCESSFUL`，28 条测试全过：
```bash
grep -o 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/test-results/testReleaseUnitTest/*.xml
```
Expected 两行：`tests="18" ... failures="0" errors="0"`（ReEnablePolicyTest）和 `tests="10" ... failures="0" errors="0"`（BandMatcherTest）

- [ ] **Step 2: 构建签名 release APK**

Run: `source ~/.cache/btreset-build/env.sh && "$BTRESET_GRADLE" assembleRelease --stacktrace`
Expected: `BUILD SUCCESSFUL`，产出 `app/build/outputs/apk/release/app-release.apk`

- [ ] **Step 3: 确认签名与仓库里的 keystore 一致**

Run:
```bash
source ~/.cache/btreset-build/env.sh
APK=app/build/outputs/apk/release/app-release.apk
"$ANDROID_HOME/build-tools/34.0.0/apksigner" verify --print-certs "$APK" 2>/dev/null \
  | grep -i 'Signer #1 certificate SHA-256 digest:' | tr -d ':' | tr 'A-Z' 'a-z' | awk '{print $NF}'
"$JAVA_HOME/bin/keytool" -list -v -keystore keystore/release.jks -storepass btreset-local-only 2>/dev/null \
  | grep -i 'SHA256:' | head -1 | tr -d ':' | tr 'A-Z' 'a-z' | awk '{print $NF}'
```
Expected: 两行输出**完全相同**，且都是 `2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad`

（`tr -d ':'` + 小写化是必需的：apksigner 和 keytool 的字段名大小写和冒号位置不同，直接比对会假报 MISMATCH。上一轮就踩过这个坑。）

- [ ] **Step 4: 确认没有残留的旧 API 引用**

Run: `grep -rn "enableIfOff\|IN_PROCESS_DELAY_MS\|ALARM_DELAY_MS\|ReEnableScheduler.schedule" app/src/`
Expected: **零命中**。任何命中都说明 Task 6/8 没改干净。

- [ ] **Step 5: 确认 enable() 只在状态机里被调用**

Run: `grep -rn "BluetoothSwitch.enable(" app/src/main/java/`
Expected: 恰好一行，在 `ReEnableReceiver.kt` 的 `ENABLE_AND_RETRY` 分支里。`BandResetController`、`ResetService`、`MainActivity`、`TriggerReceiver` 都不能出现。

Run: `grep -rn "BluetoothSwitch.disable(" app/src/main/java/`
Expected: 恰好一行，在 `BandResetController.kt` 的 `cycleBluetooth()` 里。

- [ ] **Step 6: 若有修复则 commit，否则跳过**

```bash
git add -A && git commit -m "fix: address findings from the full build verification

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

只有 Step 1-5 中某项不符合预期、并且你改了代码时才执行。全绿就跳过这一步，不要制造空 commit。

---

### Task 15: README

**Files:**
- Modify: `README.md`

五处要改。逐处给出完整替换内容。

- [ ] **Step 1: 替换开头的行为描述**

把 `README.md:1-10` 替换成：

```markdown
# 蓝牙重启 (Bluetooth Band Reset)

一个没有界面的 Android 小工具，只做一件事：

1. 蓝牙没开 → 直接退出
2. 蓝牙开着 → BLE 扫描 **8 秒**，找 `Xiaomi Smart Band 8 35BA`
3. 找到了 → 屏幕上闪一行「已找到手环，正在重启蓝牙…」，关闭蓝牙，**等它真的关完**再打开，确认开起来了再退出
4. 没找到 → 直接退出

判定"找到"只看 **BLE 扫描结果**，不要求已连接。

点图标时**不会有任何窗口**，桌面全程可以正常点击滑动。
```

- [ ] **Step 2: 替换 MIUI 必做配置**

把 `README.md:28-36` 的 `## MIUI / HyperOS 必做配置` 整节替换成：

```markdown
## MIUI / HyperOS 必做配置

**这两项不做，工具就是坏的。** 不是"可能失效"，是失效。

| 设置项 | 路径 | 不做会怎样 |
|---|---|---|
| **开关蓝牙** | 设置 → 应用设置 → 应用管理 → 蓝牙重启 → 权限管理 → **开关蓝牙** → 改成**允许** | 默认是"询问"，每次关蓝牙和开蓝牙都会弹框等你手点。你要是不在 2 分钟内点完，工具会放弃 |
| **自启动** | 同上 → **自启动** → 开 | 关着的话系统闹钟拉不起进程。重开蓝牙**完全**靠闹钟驱动，所以蓝牙会被留在关闭状态 |
| 省电策略 | 同上 → 省电策略 → 无限制 | 快段（扫描那 8 秒）可能被提前杀掉。不致命，但会白跑一趟 |
| 附近的设备 | 同上 → 权限管理 → 附近的设备 → 允许 | 扫描和开关蓝牙都需要。首次点图标会弹系统框，点允许即可 |

HyperOS 上入口名称可能略有出入，在"应用信息"页里找"权限管理"和"自启动"。

**关于"开关蓝牙"这个权限：** 它不是 Android 权限，是 MIUI 自己加的，所以应用无法在代码里申请或绕过。Android 官方对程序化开关蓝牙的豁免清单只有 Device Owner、Profile Owner 和系统应用三类身份，`BLUETOOTH_PRIVILEGED` 是 `signature|privileged` 级别，第三方应用拿不到。这一项只能你手动设。
```

- [ ] **Step 3: 替换调试小节**

把 `README.md:66-93` 的 `## 调试` 整节替换成：

```markdown
## 调试

**命中手环和蓝牙恢复这两条消息是默认就显示的**，不用加任何参数：

- `已找到手环，正在重启蓝牙…` —— 扫到手环的那一刻，约 2 秒自动消失
- `蓝牙已恢复` —— 确认蓝牙真的重新开起来了

这两条就是你不接电脑时的诊断手段。看到第一条没看到第二条，说明重开失败，去看下面的日志表。

**想看每一个结局**（包括"没找到""蓝牙本来就没开""放弃了"），加一个 extra：

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.MainActivity --ez toast true
adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT --ez toast true
```

**接电脑看日志**：

```bash
adb logcat -s BtReset.Main BtReset.Perm BtReset.Svc BtReset.Trigger BtReset.Ctrl BtReset.Switch BtReset.Sched BtReset.Store BtReset.ReEnable BtReset.Notify
```

日志含义：

| 日志 | 含义 |
|---|---|
| `Bluetooth is not ON (state=...)` | 蓝牙没开，或者正在开关过程中，按设计直接退出 |
| `missing permission(s) [...]` | 权限没给，点一次图标授权 |
| `seen AA:BB:... name=...` | 扫到的每个设备（debug 级） |
| `band is visible: ...` | 命中手环，紧接着会弹 Toast |
| `scan window elapsed; band is not visible` | 扫了 8 秒没看到手环 |
| `disable() accepted; arming the state-driven re-enable chain` | 关闭已被系统接受，开始等它真的关完 |
| `system refused to turn Bluetooth off` | MIUI 拒绝了 `disable()`，蓝牙仍是开着的 |
| `tick attempts=N phase=... state=... -> ...` | **慢段每一跳的完整决策**，排查重开问题看这个 |
| `enable() accepted; the next tick confirms STATE_ON` | 已请求开启，下一跳验证 |
| `enable() returned false; the next tick will retry` | 这次被拒了，一秒后自动再试（最多 120 次） |
| `Bluetooth is back on after N tick(s)` | **成功**，N 就是总共花了几秒 |
| `giving up (GIVE_UP_NEVER_TURNED_OFF) ...` | 2 分钟内蓝牙一直没关成。通常是 MIUI 弹框你点了拒绝，或没设"开关蓝牙"权限。**蓝牙还开着，没坏** |
| `giving up (GIVE_UP_NEVER_TURNED_BACK_ON) ...` | **蓝牙关掉了但没能开回来，需要手动开一次**。看前面 `enable()` 的日志判断是被谁拒的 |
| `exact alarms not permitted` | 闹钟降级为非精确，重开会变慢 |
| `BLE scan failed errorCode=2` | 触发了扫描节流，见下 |
```

- [ ] **Step 4: 替换工作原理**

把 `README.md:130-153` 的 `## 工作原理` 整节替换成：

```markdown
## 工作原理

流程按"时长能不能预测"切成两段。

```
点图标 → MainActivity (Theme.NoDisplay, onCreate 里立刻 finish)
           │  ← 不创建窗口，不拿输入焦点，桌面全程可点
           ├─ 权限缺失 → PermissionActivity (全项目唯一的窗口, 仅首次)
           │                └─ 授权 → startService
           └─ 权限齐全 → startService
                          ↓
                       ResetService → BandResetController      【快段 ≤8 秒】
                          ├─ 无适配器 / 蓝牙非 ON / 权限缺失 → 退出
                          ├─ BLE 扫描 8s
                          │    ├─ 未命中 → 退出
                          │    └─ 命中 → Toast「已找到手环…」
                          │              → disable()
                          │                  ├─ false → 退出（不布置闹钟）
                          │                  └─ true  → 状态落盘 WAITING_OFF
                          │                             → 布置 +1 秒闹钟
                          │                             → stopSelf
                          ↓ 进程从这里开始随时可以被杀，状态在磁盘上
                       ReEnableReceiver  每秒一跳               【慢段 ≤120 跳】
                          decide(getState(), phase, attempts)
                          ├─ ENABLE_AND_RETRY → enable() + 落盘 + 再排一跳
                          ├─ RETRY            → attempts+1 + 再排一跳
                          ├─ DONE_RESTORED    → Toast「蓝牙已恢复」+ 清状态 + 取消闹钟
                          └─ GIVE_UP_*        → 记日志 + 清状态 + 取消闹钟
```

广播入口（`sendBroadcast`）走 `TriggerReceiver.goAsync()` 内联跑同一个快段（扫描窗口 6 秒），之后共用同一条慢段。它不调 `startService` —— Android 8+ 禁止后台应用启动 Service。

### 两条不变量

**1. 只在 `getState() == STATE_OFF` 时调 `enable()`，绝不在 `STATE_TURNING_OFF` 期间调。**

`disable()` 返回 `true` 只表示"开始关了"，不表示"关完了"。官方文档明写 `isEnabled()` 等价于 `getState() == STATE_ON`，所以蓝牙正在关的那段时间里 `isEnabled()` 已经是 `false` —— 拿它当"需要重开"的判据，就会在关到一半时调 `enable()`，撞上文档说的 "immediate problem" 被拒。这正是上一版蓝牙关不回来的原因。

**2. 只有 `disable()` 返回 `true` 之后才布置闹钟链。** 否则 MIUI 拦截时，我们会去开一个本来就开着的蓝牙。

### 为什么是每秒一跳的闹钟，不是定时器

因为**要等的时长不可预测**：MIUI 会先弹框等你手点，然后蓝牙还要优雅断开所有已连接设备。定时器只能猜，猜错了就是上一版的结果。闹钟链不猜 —— 它每秒重新读一次真实状态，读到 `STATE_OFF` 才动手，最多 120 跳（2 分钟）后自己放弃。

120 这个上限特意放得比"一个人读完弹框并点下允许"所需的时间更宽。设小了会更糟：状态机先放弃，然后蓝牙才关掉，就再也开不回来了。

慢段的状态存在 SharedPreferences 里而不是内存里，因为每一跳都可能是系统重新拉起进程后的第一次执行。这也意味着**MIUI「自启动」必须开着**。

设计文档：`docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md`
```

- [ ] **Step 5: 修正"三个已知限制"里的第 3 条措辞**

`README.md:101` 那条讲 targetSdk 32 的限制**内容不变**，但在它后面（`README.md:103` 那段之后）补一句：

```markdown

补充：官方 javadoc 列出的豁免身份只有 Device Owner、Profile Owner 和系统应用三类。**没有任何第三方可申请的权限能免掉这个限制**，所以钉 targetSdk 是唯一办法，不是权宜之计。
```

- [ ] **Step 6: 校对 README 里每一个引用的字符串都和源码一致**

Run:
```bash
for s in "已找到手环" "蓝牙已恢复" "Bluetooth is not ON" "band is visible" "disable() accepted" "tick attempts=" "enable() accepted" "Bluetooth is back on after" "giving up" "exact alarms not permitted" "system refused to turn Bluetooth off"; do
  printf '%-38s src=%s readme=%s\n' "$s" \
    "$(grep -rl "$s" app/src/main/ 2>/dev/null | wc -l)" \
    "$(grep -c "$s" README.md)"
done
```
Expected: 每一行 `src=` 至少 1、`readme=` 至少 1。任何 `src=0` 说明 README 引用了一个源码里不存在的字符串 —— 要么改 README，要么说明该字符串来自 `strings.xml`（Toast 文案），用下面这条单独确认：

```bash
grep -c "已找到手环\|蓝牙已恢复" app/src/main/res/values/strings.xml
```
Expected: `2`

同时确认日志 tag 列表和源码一致：
```bash
grep -rho 'TAG = "BtReset\.[A-Za-z]*"' app/src/main/java/ | sort -u
```
Expected: 十个 tag，与 README 里 `adb logcat -s` 那行**逐一对应**（`BtReset.Main`、`BtReset.Perm`、`BtReset.Svc`、`BtReset.Trigger`、`BtReset.Ctrl`、`BtReset.Switch`、`BtReset.Sched`、`BtReset.Store`、`BtReset.ReEnable`、`BtReset.Notify`）

- [ ] **Step 7: Commit**

```bash
git add README.md
git commit -m "docs: rewrite the README for the alarm-chain architecture

Adds the MIUI 'toggle Bluetooth' permission as the first mandatory setup
step - it is a MIUI-private permission, not an Android one, so no code can
request or bypass it, and leaving it on 'ask' means a dialog before every
toggle.

Promotes 'autostart' from a fallback note to a hard requirement: the slow
phase is now driven entirely by alarms, so without it Bluetooth gets
stranded off.

Documents the always-on toasts, which are the on-device diagnostic that v1
lacked - its re-enable failure had to be reverse-engineered from the
framework javadoc because nothing was visible on the phone.

Replaces the data-flow diagram and states both invariants, including why a
per-second alarm chain beats a timer when the thing being waited for has no
predictable duration.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
```

---

### Task 16: Push 并验证 CI 与产物

**Files:** 无

前 15 个任务全绿之前**不要执行这个任务**。

- [ ] **Step 1: 确认工作区干净、本地领先远端**

Run: `git status --porcelain && git log --oneline origin/main..HEAD`
Expected: `git status` 无输出（干净）；`git log` 列出本次全部新 commit（v2 spec 的 `fd5c204` 加上 Task 1-15 的 commit）

- [ ] **Step 2: Push**

Run: `git push origin main`
Expected: 推送成功，无冲突

- [ ] **Step 3: 等 CI 并确认全绿**

`gh` CLI 在这个环境里没装，`github.com` 的 HTML 页面会超时，但 `api.github.com` 可用。用 API 轮询：

```bash
SHA=$(git rev-parse HEAD)
for i in $(seq 1 40); do
  R=$(curl -sS --max-time 20 "https://api.github.com/repos/blreay/bluetooth_monitor/commits/$SHA/status" \
      | grep -o '"state": *"[a-z]*"' | head -1)
  echo "[$i] $R"
  case "$R" in *success*|*failure*|*error*) break ;; esac
  sleep 30
done
```
Expected: 最终 `"state": "success"`。

如果拿不到状态（有些仓库的 Actions 不写 commit status），改查 runs：
```bash
curl -sS --max-time 20 "https://api.github.com/repos/blreay/bluetooth_monitor/actions/runs?per_page=3" \
  | grep -o '"conclusion": *"[a-z_]*"\|"head_sha": *"[0-9a-f]*"\|"name": *"[a-z-]*"' | head -12
```
Expected: 最新一条 run 的 `head_sha` 等于 `$SHA` 且 `conclusion` 为 `success`。

若 CI 失败：先看是不是 Task 14 已验证过的东西在 runner 上表现不同（上一轮就是 SDK 缺失，靠隔壁 `gpt` 分支的历史定位的）。**不要猜，去取日志。**

- [ ] **Step 4: 确认滚动 Release 只有一个、资产只有一个**

```bash
curl -sS --max-time 20 "https://api.github.com/repos/blreay/bluetooth_monitor/releases" \
  | grep -o '"tag_name": *"[^"]*"\|"name": *"bt-reset.apk"' | head
```
Expected: 一个 `"tag_name": "latest"`，一个 `"name": "bt-reset.apk"`

- [ ] **Step 5: 下载已发布的 APK 并验证**

```bash
source ~/.cache/btreset-build/env.sh
cd /tmp && rm -f bt-reset-check.apk
curl -sSL --max-time 120 -o bt-reset-check.apk \
  'https://github.com/blreay/bluetooth_monitor/releases/download/latest/bt-reset.apk'
ls -la bt-reset-check.apk
BT="$ANDROID_HOME/build-tools/34.0.0"
"$BT/aapt2" dump badging bt-reset-check.apk | grep -E "^package:|targetSdkVersion"
"$BT/apksigner" verify --print-certs bt-reset-check.apk 2>/dev/null \
  | grep -i 'Signer #1 certificate SHA-256 digest:'
```
Expected:
- `package: name='com.blreay.bluetoothmonitor'` 且 `versionCode` 大于上一版（上一版是 4）
- `targetSdkVersion:'32'`
- 签名 SHA-256 = `2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad`

- [ ] **Step 6: 确认发布的 APK 里 manifest 真的是新的**

```bash
source ~/.cache/btreset-build/env.sh
"$ANDROID_HOME/build-tools/34.0.0/aapt2" dump xmltree --file AndroidManifest.xml /tmp/bt-reset-check.apk \
  | grep -E "NoDisplay|PermissionActivity|ResetService"
```
Expected: 三项都出现。这一步是防止"CI 绿了但发的是旧包"。

- [ ] **Step 7: 把执行结果记进计划文档**

在本文件末尾追加一节 `## Execution record (2026-11-21)`，记录：实际发生的偏差、CI 是否一次过、发布的 `versionCode`、以及**哪些是真机验证过的、哪些没有**。

上一版就是靠这一节把"猜的"和"证过的"分开写的，这次照做。特别要写清楚：**单元测试只能证明状态机决策正确，不能证明 MIUI 放行 `disable()`、不能证明闹钟能拉起进程、不能证明 Toast 能显示** —— 这三条只有真机能答。

```bash
git add docs/superpowers/plans/2026-11-21-bluetooth-band-reset-v2.md
git commit -m "docs: record what actually happened during v2 execution

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>"
git push origin main
```

- [ ] **Step 8: 交给用户做真机验收**

按 spec §10 的四条验收标准逐条问用户，特别是：

1. 点图标后桌面**能不能立刻正常点击滑动**（这是 ④ 的验收）
2. 设了 MIUI「开关蓝牙」= 允许之后，弹框是否消失（① 的验收）
3. 命中手环时有没有看到「已找到手环，正在重启蓝牙…」（③ 的验收）
4. 蓝牙关掉之后有没有自己回来，屏幕上有没有出现「蓝牙已恢复」（② 的验收）

第 4 条如果失败，让用户报 `tick attempts=... -> ...` 那几行日志或 Toast 内容 —— 有了常开 Toast 和逐跳日志，这次不需要靠反推。

还要提醒一个 NoDisplay 的残余风险：**如果点图标时屏幕上闪过一下黑屏或启动图**，说明 MIUI 对 `Theme.NoDisplay` 仍然生成了 splash。备选方案是把 `MainActivity` 的主题换回 `@style/Theme.BtReset.Invisible`（半透明主题不生成 starting window，因为后面的 Activity 保持可见），其余一律不动 —— `onCreate` 里立刻 `finish()` 才是消除卡顿的关键，主题只决定有没有那一下闪烁。这是一行改动，随时可以回退。

---

## Self-Review 记录

**1. Spec 覆盖**

| Spec 章节 | 对应任务 |
|---|---|
| §1.1 缺陷 2 根因 | Task 2（policy）、Task 3（state）、Task 8（删掉坏的判据） |
| §1.2 缺陷 4 根因 | Task 9（Service 承载快段）、Task 11（NoDisplay + 立刻 finish） |
| §1.3 MIUI「开关蓝牙」权限 | Task 15 Step 2 |
| §1.4 图标必须有 Activity | Task 11 Step 1 的类注释、Task 13 Step 1 的 manifest 注释、Task 15 Step 4 |
| §2 快段/慢段分离 | Task 6、7、9 |
| §2.1 不用前台 Service | Task 9 Step 1 的类注释、Task 13 Step 1 的 service 注释 |
| §2.2 广播入口不 startService | Task 12 Step 1 的类注释 |
| §3 核心不变量 | Task 6 Step 3（入口守卫用 state）、Task 2（只在 OFF 时 enable）、Task 15 Step 4 |
| §4.1 纯函数接口 | Task 1、Task 2 |
| §4.2 求值顺序 | Task 2 Step 1 的测试 11、12、14 |
| §4.3 MAX_ATTEMPTS=120 的理由 | Task 2 Step 1 的测试 18 + Step 3 的 KDoc |
| §4.4 Action 副作用 | Task 7 Step 1 |
| §5.1 新增 6 个文件 | Task 1、2、4、5、9、10 |
| §5.2 重写 3 个文件 | Task 6、7、11 |
| §5.3 修改 6 个文件 | Task 3、6、12、13、15 |
| §5.4 明确不动 | Task 14 Step 1（BandMatcherTest 仍 10 条）、Step 4-5 |
| §6 Toast | Task 5、Task 6 Step 4、Task 7 Step 1、Task 10 Step 1 |
| §7 数据流 | Task 15 Step 4 |
| §8 错误处理表 | Task 2（give-up 分支）、Task 4（read 返回 null）、Task 7（每条路径都 re-arm 或 terminate） |
| §9 测试策略 | Task 2 Step 1（18 条）、Task 14 Step 1 |
| §10 验收标准 | Task 16 Step 8 |

**无遗漏。**

**2. Placeholder 扫描**：无 TBD / TODO / "适当处理错误" / "类似 Task N"。每个改代码的 step 都给了完整代码。Task 14 Step 6 是条件性 commit（"全绿就跳过"），已明确写出跳过条件，不是占位符。

**3. 类型一致性**（逐个核对跨任务引用）

- `ReEnablePolicy.Phase.WAITING_OFF` / `WAITING_ON` —— Task 2 定义，Task 4（`arm`）、Task 7（`write`）引用 ✓
- `ReEnablePolicy.Action` 六个值 —— Task 2 定义，Task 7 的 `when` **穷尽全部六个**（`ENABLE_AND_RETRY`、`RETRY`、`DONE_RESTORED`，加三个 `GIVE_UP_*` 合并在一个分支）✓
- `ReEnablePolicy.MAX_ATTEMPTS` —— Task 2 定义，Task 6 Step 1 的 KDoc 引用 ✓
- `ReEnablePolicy.decide(state, phase, attempts)` —— Task 2 定义，Task 7 调用，参数顺序一致 ✓
- `AdapterState` 五个值 —— Task 1 定义，Task 3 的 `when` 产出全部五个，Task 2 的测试消费 ✓
- `BluetoothSwitch.state(context): AdapterState` —— Task 3 定义，Task 6 Step 3 和 Task 7 调用 ✓
- `BluetoothSwitch.enable(adapter): Boolean` —— Task 3 定义，Task 7 调用（忽略返回值，KDoc 已说明为什么）✓
- `BluetoothSwitch.adapter(context)` / `disable(adapter)` —— 原有，Task 6/7 继续用，签名未变 ✓
- `PendingResetStore.arm(context, wantsToast)` —— Task 4 定义，Task 6 Step 4 调用 ✓
- `PendingResetStore.read(context): Pending?` —— Task 4 定义，Task 7 调用并做 null 检查 ✓
- `PendingResetStore.write(context, phase, attempts, wantsToast)` —— Task 4 定义，Task 7 两处调用，参数顺序一致 ✓
- `PendingResetStore.clear(context)` —— Task 4 定义，Task 7 的 `terminate()` 调用 ✓
- `PendingResetStore.Pending(phase, attempts, wantsToast)` —— Task 4 定义，Task 7 读三个字段 ✓
- `Notifier.EXTRA_TOAST` —— Task 5 定义，Task 7 不用、Task 9/10/11/12 读 Intent 时用 ✓
- `Notifier.bandFound(context)` —— Task 5 定义，Task 6 Step 4 调用 ✓
- `Notifier.bluetoothRestored(context)` —— Task 5 定义，Task 7 调用 ✓
- `Notifier.outcome(context, verbose, message)` —— Task 5 定义，Task 6 Step 3/4 和 Task 7 的 `terminate()` 调用，参数顺序一致 ✓
- `Notifier.always(context, message)` —— Task 5 定义，Task 10 调用 ✓
- `ReEnableScheduler.armNextTick(context)` —— Task 6 定义，Task 6 Step 4 和 Task 7 调用 ✓
- `ReEnableScheduler.cancel(context)` —— Task 6 定义，Task 7 的 `terminate()` 调用 ✓
- `ReEnableScheduler.TICK_INTERVAL_MS` —— Task 6 定义，在同文件 `armNextTick()` 内被 `System.currentTimeMillis() + TICK_INTERVAL_MS` 使用，故不会触发 unused 警告。保持 public 是为了和 `ReEnablePolicy.MAX_ATTEMPTS` 对称、便于 README 引用；两者相乘就是 2 分钟上限。
- `ReEnableReceiver.ACTION_REENABLE` —— 原有常量，Task 6 Step 1 的 `tickPendingIntent` 引用 ✓
- `ResetService.start(context, wantsToast)` —— Task 9 定义，Task 10 和 Task 11 调用 ✓
- `ResetService.SCAN_TIMEOUT_MS` —— Task 9 定义，Task 9 自身使用 ✓
- `BandResetController.run(scanTimeoutMs, wantsToast, callback)` —— Task 6 Step 3 定义，Task 9 和 Task 12 调用，**三参数**一致 ✓
- `R.string.toast_band_found` / `toast_restored` / `toast_outcome` / `toast_permission_denied` —— Task 5 Step 1 定义，Task 5 Step 2 全部引用 ✓
- `Theme.BtReset.Invisible` —— 原有，Task 13 改为只给 PermissionActivity 用 ✓
- `@android:style/Theme.NoDisplay` —— 框架主题，Task 13 Step 1 引用 ✓

**两处需要在执行时留意，都已在对应任务里写了处理办法**：

1. Task 6 里 `BandResetController` 同时存在一个 controller 级的 `emit(callback, wantsToast, result)`（3 参）和一个 `Session` 内的 `emit(result)`（1 参）。arity 不同，Kotlin 按签名解析，内部类成员优先，不构成歧义 —— 但改动时别把两者合并成一个。
2. `Notifier` 的所有方法都必须在主线程调用。三个调用点都在主线程：`ScanCallback.onScanResult`（默认投递到 main looper）、`ReEnableReceiver.onReceive`、`PermissionActivity` 回调。新增调用点时要复查这一条，否则 `Toast` 会抛 `Can't toast on a thread that has not called Looper.prepare()`。

其余引用全部一致。
