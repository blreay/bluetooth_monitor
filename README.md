# 蓝牙重启 / Shizuku 蓝牙控制

一个有可见操作页面的 Android 小工具。**打开应用只显示页面，不自动扫描、申请权限或重启蓝牙。**

页面提供四个按钮：

| 按钮 | 行为 |
|---|---|
| **申请shizuku权限** | 主动向正在运行的 Shizuku 请求授权；实际授权成功后显示「shizuku授权成功」，并连接高权限服务 |
| **关闭蓝牙** | 通过 Shizuku 的 shell/root 身份请求关闭蓝牙；不会自动重新开启 |
| **打开蓝牙** | 通过同一高权限路径请求开启蓝牙 |
| **扫描蓝牙** | 手动发现附近的 BLE / 经典蓝牙设备，实时显示名称、MAC、RSSI 和发现类型 |

扫描只列出设备，**即使找到目标手环，也不重启、配对或连接设备**。开关按钮不使用普通应用的 `BluetoothAdapter.enable()/disable()`，也不会降级到系统确认弹窗路径。

## 使用方法

1. 安装并启动 [Shizuku](https://shizuku.rikka.app/)。可按 Shizuku 的指引使用无线调试、adb 或 root 启动。
2. 打开本应用，点击 **申请shizuku权限**，在 Shizuku 的授权对话框中允许。
3. 页面显示 **shizuku授权成功**。高权限服务连接成功后，点击 **关闭蓝牙** 或 **打开蓝牙**。
4. 点击 **扫描蓝牙**。首次扫描时，系统会请求「附近的设备」权限；允许后才开始本次扫描。

应用通过官方 Shizuku Provider 注册并接收 Binder；授权按钮主动发起权限申请，Binder 尚未到达时会有限等待，不调用私有注册接口。启动页面不会主动申请授权。

Shizuku 授权对话框和首次扫描的「附近的设备」权限框是必要的授权过程，**不是蓝牙开关确认框**。高权限开关不为读取状态额外申请附近设备权限。

Shizuku 停止、授权拒绝/撤销、服务连接失败或系统拒绝执行时，页面会显示原因；不会静默改用普通开关 API。手机重启后通常需要先重新启动 Shizuku。

如果高权限服务在连接超时前从未返回 Binder，官方 SDK 的本地连接可能无法安全释放。应用会隔离这次未完成连接，不继续创建新连接或冒认迟到的旧服务；页面若提示重启应用，请在系统设置中**强行停止本应用，再重新打开**，不需要卸载。这是异常连接状态的安全恢复步骤，不是每次授权都需要；扫描功能仍独立于 Shizuku。

### 开关结果如何理解

- **请求已提交**：高权限命令已经返回，不代表蓝牙硬件完成了异步切换。
- 已有「附近的设备」权限时，应用会在有限时间内检查真实状态，确认 ON/OFF 后才显示已打开/已关闭。
- 没有读取状态的权限时，会明确说明无法确认最终状态；可查看系统蓝牙开关，或授予扫描所需权限后再次操作。
- 超时、错误或 OEM 限制会显示失败/未确认，不会冒充成功或回退到普通 API。

手动开关会取消本应用遗留的自动恢复链，并使旧的在途重置扫描失效，避免刚手动关闭就被旧闹钟重新打开。开关和扫描不会同时运行。

### 扫描结果如何理解

- 先进行约 **8 秒 BLE 扫描**，再进行最多 **15 秒经典蓝牙发现**。结果实时更新，总时长约 23 秒以内。
- 每次实际开始新扫描时清空上一次结果；结束后保留本次结果。
- 同一 MAC 只列一次，重复发现更新 RSSI；同一设备被 BLE 和经典发现时合并类型。没有名称时显示「未知设备」。
- 列表只包含**本次实际发现**的设备，不把已配对缓存混作扫描结果。
- 蓝牙未开启/正在切换时，扫描只提示先打开，不会隐式切换蓝牙。旧自动恢复尚未完成时，扫描提示等待，不中断必要的恢复。
- 离开页面会停止扫描。打开、重开页面或旋转屏幕不会自行开始新的扫描。
- 单个扫描阶段失败时保留已有结果，并说明哪个阶段未完成。

BLE 外设不广播、经典设备未进入可发现模式时可能扫不到。**设置里能看到已配对设备，不代表它当前能被扫描发现。**

Android 还限制短时间内频繁启动 BLE 扫描；超额可能静默不给结果。错误码 2 是扫描客户端注册失败，不是该限流。零设备也可能只是附近没有可发现设备，不能仅凭零结果认定限流。

## 下载

`main` 每次 push 自动更新固定下载地址，签名不变，可以覆盖安装：

```
https://github.com/blreay/bluetooth_monitor/releases/download/latest/bt-reset.apk
```

**功能分支的修改不会自动更新这个 `latest` APK。** 工作流只在 `main` push 或手动 dispatch 时运行；使用分支代码测试时需自行构建，或等待该功能合入 `main`。

安装时需要允许「未知来源」。同签名覆盖安装可保留已经授予的应用权限。

## 页面入口

以下入口现在都只打开控制页面，不启动自动扫描：

- 点击桌面图标 **蓝牙重启**。
- Tasker / MacroDroid / Automate 发 Activity Intent：

  ```
  Action:   com.blreay.bluetoothmonitor.action.RUN
  Package:  com.blreay.bluetoothmonitor
  Class:    com.blreay.bluetoothmonitor.MainActivity
  ```

- adb：

  ```bash
  adb shell am start -n com.blreay.bluetoothmonitor/.MainActivity
  ```

普通后台应用启动 Activity 仍受 Android 10+ 的后台启动限制。

## 兼容：显式广播自动重置手环

为了保留已有自动化脚本，下面这个**独立广播入口**仍执行原有逻辑，**不是页面扫描按钮的行为**：

```bash
adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT
```

广播入口使用 6 秒 BLE 扫描，命中 `Xiaomi Smart Band 8 35BA` 后关闭蓝牙，再交由状态驱动的闹钟链重新开启。它不直接连接手环，连接恢复由 Mi Fitness 等原应用自行完成。

此兼容路径仍使用原来的普通应用蓝牙接口，**不具备新页面 Shizuku 开关的高权限保证**。如果不需要自动重置，直接使用页面上的四个按钮即可，不要发送该广播。手动操作期间旧重置扫描不能再切换蓝牙。

### 旧广播路径的 MIUI / HyperOS 设置

下面是**旧自动重置路径**的要求，不是 Shizuku 开关的前提：

| 设置项 | 作用 |
|---|---|
| 应用权限管理 → 开关蓝牙 → 允许 | 避免旧普通应用开关路径被 MIUI 确认框拦住 |
| 自启动 → 开 | 允许旧恢复闹钟在进程被回收后继续重新开启蓝牙 |
| 省电策略 → 无限制 | 减少旧后台扫描被提前终止 |
| 附近的设备 → 允许 | 允许旧广播扫描以及读取/控制蓝牙 |

旧恢复链只在观察到 `STATE_OFF` 后请求开启，不在 `STATE_TURNING_OFF` 时盲目开启；最多 120 跳后放弃。它的状态落盘在 SharedPreferences 中，进程被杀后由 alarm 继续。

`targetSdk` 暂时保留 32，以保持**这条旧路径**的兼容性。新页面的开关使用 Shizuku UserService，不以普通应用的 targetSdk 豁免作为控制方式；不要为消除 lint 警告直接升级 SDK 而破坏旧路径。

### 旧广播运行结果

旧广播的每次运行都会在「运行结果」通知渠道留一条静默结果；关闭通知时退化为 Toast。正文附带扫描时长、设备数和失败原因，命中手环与恢复成功另有 Toast。

想让每个旧广播结局都弹 Toast：

```bash
adb shell am broadcast -a com.blreay.bluetoothmonitor.action.RESET_IF_PRESENT --ez toast true
```

修改旧广播的目标手环：编辑 `app/src/main/java/com/blreay/bluetoothmonitor/BandMatcher.kt` 中的 `TARGET_NAME`、`TARGET_NAME_PREFIX` 和 `TARGET_MAC_SUFFIX`。这些匹配常量**不会过滤新页面的设备列表**。

## 自己构建

需要 JDK 17、Android SDK platform 34 + build-tools 34.0.0：

```bash
./gradlew testReleaseUnitTest assembleDebug assembleRelease
# debug：app/build/outputs/apk/debug/app-debug.apk
# release：app/build/outputs/apk/release/app-release.apk
```

release 签名使用仓库里的 `keystore/release.jks` 和 `gradle.properties`。该密钥仅用于这个私人工具，公开提交是为了保持跨构建签名一致，不代表其他身份。

本开发环境已有缓存工具链，默认 JAVA_HOME 不适用时使用：

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug --stacktrace
```

本地默认 versionCode 为 1，CI 用 `GITHUB_RUN_NUMBER + 1`。覆盖安装已有更高版本号时，需要构建时提供合适的运行号；不要为测试而卸载导致权限丢失。

## 工作原理

- **MainActivity**：正常可见页面，只由按钮触发授权、开关和扫描。
- **Shizuku 客户端**：真实授权检查、Binder 生命周期和专用 UserService 连接。
- **高权限 UserService**：shell/root UID 下执行固定 `/system/bin/svc bluetooth enable|disable`，通过系统管理接口控制蓝牙；没有普通 API fallback。
- **独立 discovery scanner**：BLE 和经典发现、MAC 去重、实时设备列表、超时与生命周期清理，不包含手环匹配/重置。
- **手动操作门闩**：使旧在途自动重置失效，防止旧回调/恢复 alarm 抢回手动控制。
- **TriggerReceiver / ReEnableReceiver**：保留独立的旧广播自动化与状态驱动恢复协议。

历史自动重置设计：`docs/superpowers/specs/2026-11-21-bluetooth-band-reset-v2-design.md`。

新手动页面设计：`docs/superpowers/specs/2026-11-24-manual-shizuku-control-design.md`。

构建、JVM 测试不能代替真机验证。尤其需要在实际 Shizuku / MIUI / HyperOS 上确认权限流程、真实 ON/OFF 状态以及开关过程没有普通确认框。
