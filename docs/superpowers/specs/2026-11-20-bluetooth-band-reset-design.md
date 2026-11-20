# 蓝牙手环重连工具 —— 设计文档

- 日期：2026-11-20
- 仓库：`git@github.com:blreay/bluetooth_monitor`
- 状态：已与需求方确认，待生成实现计划

## 1. 目标

一个只做一件事的 Android 应用：

1. 蓝牙没开 → 直接退出。
2. 蓝牙开着 → BLE 扫描可见设备，找 `Xiaomi Smart Band 8 35BA`（**可见即算命中，不要求已连接**）。
3. 命中 → 关闭蓝牙，等待 3 秒，重新打开蓝牙，然后退出。
4. 未命中 → 直接退出。

约束：

- **没有可见窗口**，但要有桌面图标，可点击。
- 可以被其他应用启动和调用。
- 在当前目录开发，推到 GitHub，由 GitHub Actions 直接产出 APK。

## 2. 已确认的关键决策

| # | 决策点 | 结论 |
|---|---|---|
| 1 | 目标机型 | 小米/红米，Android 13+（MIUI 14 / HyperOS） |
| 2 | 检测方式 | **只算 BLE 扫描到的**，严格按需求原话。已连接的手环不广播、扫不到 → 不动作 |
| 3 | 权限授予 | 首次运行时弹一次系统对话框，之后永久静默 |
| 4 | 触发入口 | 四种全要：桌面图标、Tasker/MacroDroid/Automate、adb/Termux `am start`、`sendBroadcast` |
| 5 | 签名 | 仓库内提交固定 keystore，保证签名跨构建一致 |
| 6 | 运行时结构 | 半透明 Activity + AlarmManager 交接 |
| 7 | 应用名 | 桌面显示 `蓝牙重启` |

## 3. 平台约束（决定了整个架构）

### 3.1 Android 13 禁止程序化开关蓝牙

Android 13 起，**targetSdk ≥ 33** 的应用调用 `BluetoothAdapter.enable()` / `disable()` 一律返回 `false`。豁免对象只有 device owner / profile owner，以及持有 signature|privileged 级 `BLUETOOTH_PRIVILEGED` 的系统应用。

唯一可行的绕过方式：**把 targetSdk 保持在 32 或更低**。这个限制是按 targetSdkVersion 而非系统版本判定的，所以 targetSdk 32 的应用跑在 Android 13/14/15/16 上仍然走老路径。

安装下限是另一回事，且不构成障碍：Android 14 拒绝安装 targetSdk < 23 的应用，Android 15 拒绝 < 24。targetSdk 32 远高于两者。

Tasker 的 `TaskerSettings` 和 MacroDroid 的 Helper App 用的就是这个手法 —— Tasker 6.2 更新日志明确写着 "Made Bluetooth action use Tasker Settings app to toggle bluetooth"。

**已知风险**（不是我们独有的，是该路径本身的风险）：

- Google Issue Tracker #232107689、#239343962 报告过 targetSdk ≤ 32 的应用在 Android 13 上 enable/disable 仍然失败。
- OEM 皮肤可能额外弹确认框（MacroDroid 论坛有华为机型弹出"某应用正在尝试更改蓝牙"的记录，MIUI 同理可能发生）。
- 这是无文档保证的路径，Google 未来可能彻底移除。

来源：[Android 13 behavior changes](https://developer.android.com/about/versions/13/behavior-changes-13)、[BluetoothAdapter API](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter)、[Issue 232107689](https://issuetracker.google.com/issues/232107689)、[Issue 239343962](https://issuetracker.google.com/issues/239343962)、[TaskerSettings](https://github.com/joaomgcd/TaskerSettings/blob/main/README.md)、[Tasker 6.2 release notes](https://tasker.joaoapps.com/changes/changes6.2.html)、[MacroDroid Helper App](https://wiki.macrodroid.com/wiki/index.php?title=Helper_App)、[Android 14 安装下限](https://bayton.org/android/android-14-minimum-sdk/)

### 3.2 手环是纯 BLE 设备

Xiaomi Smart Band 8 只支持 BLE，**经典蓝牙 discovery（`startDiscovery()` / `ACTION_FOUND`）永远扫不到它**。必须用 `BluetoothLeScanner`。

### 3.3 已连接的 BLE 外设停止广播

手环一旦被 Mi Fitness 连上就不再广播，因此 BLE 扫描扫不到它。"可见"与"已连接"对这个设备基本互斥。

需求方已明确选择严格按"可见"判定：手环正常连接时本工具不动作；手环掉线但在附近时会广播 → 扫到 → 重启蓝牙 → Mi Fitness 重连。这与"手环掉线了想强制重连"的意图一致。

### 3.4 半透明 Activity 的可见性

设置 `windowIsTranslucent=true` + 全透明 `windowBackground` 后，应用自身不绘制任何像素，Android 12+ 的系统闪屏图标也会被抑制（这是社区通用绕过手法，Google 官方文档不推荐但可行）。

残留的可见痕迹：

- 冷启动时可能有 100~300ms 的"顿挫感"：系统的起始窗口技术上仍然存在，只是变成全透明，此时身后的桌面不响应触摸。
- MIUI 桌面自己的图标点击动画照常播放，应用主题管不了。
- Android 12+ **没有任何受支持的方法**能彻底关掉闪屏。

**重要副作用**：部分 ROM 会把"看不见的 Activity"判定为应用已关闭从而隐藏其窗口（Sailfish AppSupport 有此记录），即本 Activity 有被系统当作后台而降级/杀掉的现实风险。这正是选择 AlarmManager 兜底的原因。

来源：[42gears](https://techblogs.42gears.com/handling-android-12-splash-screen-in-existing-projects/)、[Qt](https://www.qt.io/blog/android-and-qt-splash-screen)、[官方 Splash screens](https://developer.android.com/develop/ui/views/launch/splash-screen)、[隐藏强制闪屏](https://levelup.gitconnected.com/how-to-hide-the-forced-splash-screen-in-android-e5269ea640e2)、[r/kivy](https://www.reddit.com/r/kivy/comments/10oaqdu/black_screen_with_icon_in_the_middle_before/)、[Sailfish 论坛](https://forum.sailfishos.org/t/appsupport-android-app-window-hidden-app-closed-by-android-during-activity-transition-while-task-is-still-alive-and-visible/35309)

### 3.5 后台启动限制决定了入口形态

- Android 10+ **禁止从后台启动 Activity**。Tasker/MacroDroid/Automate 这类工具持有后台启动 Activity 的特权或悬浮窗权限，所以它们能调；纯后台应用不能。
- Android 12+ **禁止从后台启动前台 Service**（抛 `ForegroundServiceStartNotAllowedException`）。这就是否决"前台 Service"方案的原因 —— 它会让 `sendBroadcast` 入口直接失效。
- `BroadcastReceiver.goAsync()` 只给约 10 秒执行预算。完整流程（扫描 8s + 关闭 + 等 3s + 开启）约 12~15 秒，**超预算**。解法：接收器把关蓝牙之后的事交给 AlarmManager，自己立刻返回。

### 3.6 权限模型

`minSdk 31` 是一个重要简化：在 Android 12+ 上，`BLUETOOTH_SCAN` 配合 `android:usesPermissionFlags="neverForLocation"` 即可扫描，**完全不需要 `ACCESS_FINE_LOCATION`**，也不用处理 Android 11 及以下的定位权限分支。

需要的运行时权限：

- `BLUETOOTH_SCAN`（neverForLocation）—— BLE 扫描
- `BLUETOOTH_CONNECT` —— 读取设备名 + 调用 `disable()`/`enable()`

普通权限（安装时授予）：

- `SCHEDULE_EXACT_ALARM` —— targetSdk 32 时系统默认授予（Android 13 的"默认拒绝"只作用于 targetSdk ≥ 33）

这两个运行时权限**无法静默授予**，必须有一次用户交互。已确认方案：首次点图标时弹一次 Android 原生"允许访问附近的设备？"对话框，之后永久静默。

## 4. 架构

### 4.1 工程参数

| 项 | 值 | 理由 |
|---|---|---|
| appId | `com.blreay.bluetoothmonitor` | 与仓库名一致 |
| 语言 | Kotlin | 蓝牙 API 大量可空返回，空安全有价值 |
| `minSdk` | 31 | 免掉整个定位权限分支 |
| `targetSdk` | **32** | 保留 enable/disable 老路径的唯一办法 |
| `compileSdk` | 34 | 与 targetSdk 解耦 |
| Gradle | 8.7 | 与 AGP 8.4 匹配 |
| AGP | 8.4.0 | 需要 Gradle 8.6+ |
| Kotlin | 1.9.24 | 与 AGP 8.4 兼容 |
| JDK | 17（CI） | AGP 8 要求 |
| `minifyEnabled` | false | 此体量下 R8 只增加失败模式，无收益 |

### 4.2 组件

四个类，职责单一，Activity 与 Receiver 共用同一份业务逻辑，避免两条路径行为不一致。

**`BandResetController.kt`** —— 全部业务逻辑，不依赖任何 UI。

```
输入：Context、扫描超时毫秒数、完成回调
输出：Result 枚举
```

`Result` 取值：`NO_ADAPTER`、`BLUETOOTH_OFF`、`NEEDS_PERMISSION`、`NOT_FOUND`、`DISABLE_FAILED`、`RESET_TRIGGERED`、`ERROR`。

**`MainActivity.kt`** —— 图标 / Tasker / `am start` 的入口。

半透明主题，`exported=true`，`excludeFromRecents=true`，`taskAffinity=""`。缺权限则 `requestPermissions()`（仅首次），授权后继续；拒绝则记日志退出。否则调 controller，完成后 `finish()`。

**生命周期要点**：MainActivity **必须存活到蓝牙重新开启完成之后才 `finish()`**（即全程约 12 秒），而不是 `disable()` 之后立刻退出。原因是 Activity 一 finish，进程就失去前台优先级，MIUI 很可能在 3 秒等待期内把它杀掉，进程内的 `postDelayed` 随之失效。让 Activity 撑完全程，进程内快路径才可靠；闹钟退为纯兜底。

注意**不能**用 `Theme.NoDisplay`：该主题要求 `onResume` 之前必须 `finish()`，会中断我们 12 秒的流程。必须用半透明主题。

**`TriggerReceiver.kt`** —— `sendBroadcast` 入口，自定义 action，`exported=true`。

`goAsync()` + 扫描上限压缩到 6 秒，关蓝牙后立即把重开交给闹钟并 `finish()` pending result，稳落在 10 秒预算内。后台无法弹权限框，缺权限则记日志退出。

**`ReEnableReceiver.kt`** —— 闹钟接收器，`exported=false`，**manifest 声明**（这样进程被杀后系统仍能拉起进程投递闹钟）。逻辑：适配器存在且蓝牙仍关闭 → `enable()`。

### 4.3 核心不变量

> **只有 `disable()` 返回 `true` 之后，才安排重新开启。**

否则 MIUI 拦截 `disable()` 时，我们会平白无故去 `enable()` 一个本来就开着的蓝牙。

### 4.4 设备匹配规则

`35BA` 是手环 MAC 地址的后两个字节（小米手环把 MAC 尾字节附在广播名里），因此 **MAC 是比广播名更可靠的主键** —— 广播名可能被截断（BLE 广播名长度受限）或放在 scan response 里。

```
命中 = (MAC 以 "35:BA" 结尾 且 (名字为 null 或 以 "Xiaomi Smart Band" 开头))
       或 名字包含 "Xiaomi Smart Band 8 35BA"
```

实现要点：

- **不使用硬件 `ScanFilter`** —— 它对截断名和 scan-response 名不可靠。
- 使用 `ScanSettings.SCAN_MODE_LOW_LATENCY`，不加过滤全量扫描，在回调里软件匹配。
- 名字来源同时尝试 `scanResult.scanRecord?.deviceName` 与 `scanResult.device.name`。
- 三个常量（`TARGET_NAME`、`TARGET_NAME_PREFIX`、`TARGET_MAC_SUFFIX`）集中放在文件顶部，换手环改一行即可。

### 4.5 重新开启的双重机制

`disable()` 成功后**同时**安排两条路径：

1. 进程内 `Handler.postDelayed(3000)` —— 精确快路径
2. `AlarmManager.setExactAndAllowWhileIdle(+3500ms)` → `ReEnableReceiver` —— 抗进程死亡兜底

`enable()` 是幂等的：先到的生效，后到的是无害 no-op。进程在第 1 条路径生效前被杀掉时，第 2 条仍然会把蓝牙打开。

两条入口对这两个机制的依赖程度不同：

- **Activity 路径**：Activity 存活全程（见 4.2），进程有前台优先级，`postDelayed` 是实际生效的主路径，闹钟是兜底。
- **Receiver 路径**：`goAsync()` 结束后进程随时可能被回收，`postDelayed` 不可靠，**闹钟是唯一实际保障**。

降级：若 `canScheduleExactAlarms()` 为 false，则只用进程内 `postDelayed`，并在日志中明确告警 —— 此时 Receiver 路径存在把蓝牙留在关闭状态的风险。

### 4.6 数据流

```
点图标 / Tasker / am start / sendBroadcast
        ↓
MainActivity 或 TriggerReceiver
        ↓
BandResetController
  ├─ 适配器为 null      → NO_ADAPTER，退出
  ├─ 蓝牙未开启         → BLUETOOTH_OFF，退出      ← 需求第一条
  ├─ 权限缺失           → NEEDS_PERMISSION（Activity 弹框 / Receiver 退出）
  ├─ BLE 扫描 ≤8s（Receiver 路径 ≤6s）
  │    └─ 未命中        → NOT_FOUND，退出
  └─ 命中 → stopScan → disable()
              ├─ false  → DISABLE_FAILED，退出（不安排重开）
              └─ true   → postDelayed(3s) + 精确闹钟(+3.5s) → RESET_TRIGGERED
                          ↓ 进程此刻死掉也没关系
                       ReEnableReceiver → enable()
```

## 5. 错误处理

每一条退出路径都写 logcat，绝不静默失败。

| 情况 | 行为 |
|---|---|
| 无蓝牙硬件（adapter 为 null） | log + 退出 |
| 蓝牙未开启 | log + 退出（按需求） |
| 权限缺失（Activity 路径） | 弹一次系统框；被拒则 log + 退出 |
| 权限缺失（Receiver 路径） | log + 退出（后台无法弹框） |
| 扫描超时未命中 | log + 退出 |
| `disable()` 返回 false | log + 退出，**不**安排重开 |
| 精确闹钟不被允许 | 退化为仅进程内 `postDelayed` |
| `enable()` 返回 false | log，交给用户手动开启 |
| controller 抛异常 | catch-all → log → 结束 |

## 6. Manifest 与资源

### 6.1 权限声明

```xml
<uses-permission android:name="android.permission.BLUETOOTH_SCAN"
                 android:usesPermissionFlags="neverForLocation" />
<uses-permission android:name="android.permission.BLUETOOTH_CONNECT" />
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />
```

`minSdk 31` 意味着不需要 `BLUETOOTH`/`BLUETOOTH_ADMIN`（API 30 及以下的旧权限）也不需要 `ACCESS_FINE_LOCATION`。

### 6.2 Activity 主题

`res/values/themes.xml` 中定义 `Theme.BtReset.Invisible`：

- parent `@android:style/Theme.Translucent.NoTitleBar`
- `windowIsTranslucent` = true
- `windowBackground` = `@android:color/transparent`
- `windowAnimationStyle` = `@null`（去掉切换动画）
- `windowNoTitle` = true
- `statusBarColor` / `navigationBarColor` = transparent（避免系统栏闪烁）

### 6.3 图标

纯 XML 自适应图标，**不需要任何 PNG**（`minSdk 31 ≥ 26`，`mipmap-anydpi-v26` 足够）：

- `res/mipmap-anydpi-v26/ic_launcher.xml`（+ `ic_launcher_round.xml`）
- `res/drawable/ic_launcher_foreground.xml` —— 矢量蓝牙符号
- `res/values/ic_launcher_background.xml` —— 纯色背景

### 6.4 自定义 action 常量

| 用途 | action |
|---|---|
| Activity（供 Tasker / `am start` 调用） | `com.blreay.bluetoothmonitor.action.RUN` |
| BroadcastReceiver（供 `sendBroadcast` 调用） | `com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT` |
| 内部闹钟 | `com.blreay.bluetoothmonitor.action.REENABLE` |

`TriggerReceiver` 保持开放（不设 `android:permission`），否则 Tasker 等第三方应用无法调用。风险可接受：该 action 最坏后果只是多重启一次蓝牙。

## 7. 构建与发布

### 7.1 签名

- `keystore/release.jks` **提交进仓库**，凭据写在 `gradle.properties`。
- 目的：签名跨构建永久一致 → 新版可直接覆盖安装 → **不需要重新授权"附近的设备"权限**。
- 若用 CI 自带的 debug 签名，每次构建 keystore 都随机生成，签名不同 → 每次更新都要先卸载再装 → 权限重授。这是已规避的坑。
- 该 keystore 不代表任何身份，仅用于签这一个私人工具，公开无实质风险。

### 7.2 Gradle wrapper

`gradlew`、`gradlew.bat`、`gradle/wrapper/gradle-wrapper.jar`、`gradle-wrapper.properties` 全部提交。本机无 Gradle，从 `mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.7-bin.zip` 下载（已实测 HTTP 200）后本地生成 wrapper。

`distributionUrl` 保持官方 `https://services.gradle.org/distributions/gradle-8.7-bin.zip` —— GitHub runner 可正常访问。

### 7.3 GitHub Actions

`.github/workflows/build.yml`：

- 触发：push 到 `main` + `workflow_dispatch`
- `actions/setup-java@v4`，temurin，JDK 17
- `gradle/actions/setup-gradle@v4`
- `./gradlew assembleRelease`
- `actions/upload-artifact@v4` 上传 APK
- 发布 GitHub Release，附带 APK，使手机浏览器可直接下载

**Release 策略（固定，避免每次构建产生新 tag）**：

- 使用**单一滚动 tag `latest`**，每次 push 到 main 都覆盖更新同一个 Release（`softprops/action-gh-release@v2`，`tag_name: latest`）。
- 好处：手机上的下载地址永久不变，收藏一次即可，无需每次去 Actions 页面找 artifact（artifact 下载还必须登录 GitHub）。
- APK 文件名带短 commit SHA 以便区分版本，例如 `bt-reset-1.0-a1b2c3d.apk`。

**版本号**：`versionName` 从 `1.0` 起手工维护；`versionCode` 在 CI 中由 `GITHUB_RUN_NUMBER` 派生，保证单调递增，避免"版本号相同导致覆盖安装被拒"。

注意：若仓库为 private，手机上下载 Release APK 需先登录 GitHub。

## 8. 验证计划

### 8.1 本地构建验证（不依赖 CI）

本机有 Java 21、无 Android SDK、无 Gradle，但以下源已实测可达：

- `https://mirrors.aliyun.com/macports/distfiles/gradle/gradle-8.7-bin.zip` → HTTP 200，134 MB
- `https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip` → HTTP 200，153 MB
- `https://repo.maven.apache.org/maven2/` → HTTP 200
- `https://plugins.gradle.org/` → HTTP 200

因此**在本地完整搭建 Android SDK 并真正编译出 APK**，确认 compile / lint / package 全部通过后再推送。不做"推上去赌 CI 能过"。

本机 `java` 是 21，AGP 8.4 要求 JDK 17+；若 JDK 21 构建失败则另取 JDK 17。

### 8.2 单元测试

匹配规则是纯函数，用 JUnit 覆盖：

- 完整名字精确命中
- 名字被截断（如 `Xiaomi Smart Band 8`）但 MAC 尾字节命中
- 名字为 null、仅 MAC 命中
- 别的小米设备（MAC 尾字节不匹配）→ 不命中
- 完全无关设备 → 不命中

### 8.3 真机验证（只能由需求方完成）

README 提供：

- `adb logcat` 过滤命令与每行日志含义对照
- 调试用 intent extra `--ez toast true`：不接电脑也能在屏幕上看到本次结果
- MIUI 必做配置：开启"自启动"、关闭电池优化限制、授予"附近的设备"权限

## 9. 已知风险与坑

| 风险 | 说明 | 缓解 |
|---|---|---|
| targetSdk 32 老路径失效 | Google 有 issue 报告 enable/disable 在部分 Android 13 构建上对低 targetSdk 应用也失败 | 无技术缓解；`disable()` 返回 false 时记日志并不安排重开，保证不会把蓝牙留在关闭状态 |
| MIUI 弹确认框 | OEM 可能在 AOSP 之上追加"某应用正在尝试更改蓝牙"对话框 | 无法预先消除；真机验证时确认 |
| MIUI 杀进程 | 半透明 Activity 可能被判为后台 | AlarmManager 兜底重开 |
| MIUI 自启动限制 | 未开自启动时广播触发与闹钟可能不生效 | README 写明配置步骤 |
| BLE 扫描节流 | Android 限制每 30 秒最多 5 次扫描，超限后静默禁扫 30 秒 | README 说明：反复点图标测试时"扫不到"可能不是 bug |
| 手环已连接时扫不到 | 已连接的 BLE 外设停止广播 | 需求方已确认这是期望行为 |

## 10. 明确不做（YAGNI）

- 不做任何设置界面 / 配置界面
- 不做定时自动运行（需求是被动触发）
- 不做前台 Service、不显示任何通知
- 不做经典蓝牙 discovery（手环是纯 BLE，扫不到）
- 不支持 Android 12 以下设备
- 不做多设备管理，目标设备写死为常量
- 不接入任何第三方依赖（仅 Kotlin 标准库 + Android SDK + JUnit 测试）
