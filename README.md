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
