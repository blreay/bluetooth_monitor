# 蓝牙重启 (Bluetooth Band Reset)

一个没有界面的 Android 小工具，只做一件事：

1. 蓝牙没开 → 直接退出
2. 蓝牙开着 → BLE 扫描 **8 秒**，找 `Xiaomi Smart Band 8 35BA`
3. 找到了 → 屏幕上闪一行「已找到手环，正在重启蓝牙…」，关闭蓝牙，**等它真的关完**再打开，确认开起来了再退出
4. 没找到 → 直接退出

**每次运行都会在通知栏留一条结果**，不管找到没找到、成功还是失败。见[看运行结果](#看运行结果)。

判定"找到"只看 **BLE 扫描结果**，不要求已连接。

点图标时**不会有任何窗口**，桌面全程可以正常点击滑动。

## 下载

固定地址（每次 push 到 main 自动更新，签名不变，可直接覆盖安装）：

```
https://github.com/blreay/bluetooth_monitor/releases/download/latest/bt-reset.apk
```

安装时需要允许"未知来源"。因为签名固定，以后每次更新都是覆盖安装，**不会丢掉已授予的权限**。

## 首次使用（只需一次）

1. 点桌面图标 **蓝牙重启**
2. 系统弹出"允许访问附近的设备？" → **允许**
3. 系统可能再弹一个"允许发送通知？" → **允许**。拒绝也能用，只是通知退化成 Toast
4. 之后点图标**不会出现任何窗口**。每次运行的结果都在通知栏里；命中手环和蓝牙恢复这两件事还会各闪一行 Toast（约 2 秒自动消失）

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

广播路径的扫描窗口是 6 秒（前三种走 Service 的路径是 8 秒），因为 `goAsync()` 只有约 10 秒预算。

## 看运行结果

**每次运行都会发一条通知**，不管有没有找到手环、成功还是失败。这是不接电脑时主要的诊断手段。

通知走「运行结果」渠道，是静默的（不响铃、不弹横幅），只在通知栏和状态栏留一个图标。第二次运行覆盖第一次，不会堆一屏。

正文固定带一行扫描事实：

```
扫描 8.0/8.0 秒 · 看到 14 个设备（9 个带广播名）
```

**这行数字就是判断依据**，因为两种故障在屏幕上长得一模一样，只有设备数能分开：

| 通知里看到 | 说明 |
|---|---|
| 一个设备都没扫到 | 多半被系统限流了。Android 每 30 秒只放行 5 次扫描，**超了之后静默不给结果、也不报错**。反复点图标测试必撞，等 30 秒再点 |
| 扫到十几个设备，但没有目标 | 扫描本身是好的，手环没在广播 —— 它多半正连着手机 |
| 扫描只跑了一两秒就结束 | 进程被系统杀了，去设「省电策略 = 无限制」 |
| 看到疑似手环但没匹配上 | 手环换了广播名，通知里会写它实际叫什么，据此改 `BandMatcher.kt` |
| **完全没有任何通知** | 进程在发通知之前就死了，或者通知权限被关了 |

命中手环和蓝牙恢复这两件事**另外**还会各弹一条 Toast（约 2 秒自动消失），也是无条件的。看到第一条没看到第二条，说明重开失败。

**想让每一个结局都弹 Toast**（包括"没找到""蓝牙本来就没开"），加一个 extra：

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.MainActivity --ez toast true
adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT --ez toast true
```

**接电脑看日志**：

```bash
adb logcat -s BtReset.Main BtReset.Perm BtReset.Svc BtReset.Trigger BtReset.Ctrl BtReset.Switch BtReset.Sched BtReset.Store BtReset.ReEnable BtReset.Notify BtReset.Notif
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
| `BLE scan failed errorCode=2` | **扫描客户端注册失败**，全系统同时扫描的应用太多了。这**不是**限流 —— 限流根本不会走到这个回调，见下 |
| `posted: <标题>` | 通知发出去了 |
| `notifications disabled; falling back to a toast` | 通知权限被关了，改用 Toast 兜底 |

## 三个已知限制

**1. 手环已连接时扫不到 —— 而"设置里能看到"不等于"在广播"。** BLE 外设一旦连上就停止广播。设置 → 蓝牙里那个列表是**已配对设备**，配对记录存在本机，跟手环此刻有没有在发广播是两回事。所以你在设置里明明看得到它、扫描却扫不到，是正常现象，不是 bug。通知里会直接写明手环在不在已配对列表里。它的用途是手环掉线但在附近时，强制重启蓝牙让 Mi Fitness 重连。

**2. Android 限制每 30 秒最多 5 次 BLE 扫描，而且超限时是完全静默的。** 第 6 次 `startScan` 会被正常接受、不抛异常、**不回调 `onScanFailed`**，然后拿不到任何结果。表现就是"扫了 8 秒，一个设备都没看到"。反复点图标测试必然撞上，等 30 秒再点即可 —— 通知里的设备数为 0 就是这个情况。

（`BLE scan failed errorCode=2` 是另一码事：扫描客户端注册失败，说明全系统同时在扫描的应用太多，跟限流无关。）

**3. `targetSdk` 被故意钉在 32。** Android 13 起，targetSdk ≥ 33 的应用调用 `BluetoothAdapter.enable()/disable()` 一律返回 `false`。这个限制按 targetSdkVersion 而非系统版本判定，所以钉在 32 是让"程序化关蓝牙"在 Android 13/14/15/16 上继续可用的唯一办法 —— Tasker 的 `TaskerSettings` 和 MacroDroid 的 Helper App 用的是同一手法。

**不要为了消除 lint 警告而升级 targetSdk，那会静默废掉这个应用唯一的函数。** Android 没有替代 API：`ACTION_REQUEST_ENABLE` 是用户确认弹窗，而关蓝牙连这个都没有。

补充：官方 javadoc 列出的豁免身份只有 Device Owner、Profile Owner 和系统应用三类。**没有任何第三方可申请的权限能免掉这个限制**，所以钉 targetSdk 是唯一办法，不是权宜之计。

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
