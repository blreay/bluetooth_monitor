# 蓝牙手环条件重启器设计

## 已确认需求与设备

用户于 2026-11-20 确认以下方案，设备为小米 17 Pro Max、澎湃 OS 4，无 Root、无 Shizuku。在当前仓库开发普通侧载 APK，推送到 GitHub 仓库 `blreay/bluetooth_monitor`，使用 GitHub Actions 构建 APK。应用只在主动调用时执行一次，不定时扫描、不常驻、不联网。

## 行为

1. 蓝牙关闭或手机没有蓝牙适配器时，立即结束，不打开蓝牙。
2. 蓝牙开启且权限齐全时，最多扫描 15 秒的实时 BLE 广播。
3. 只使用本次收到的广播记录中的设备名称，精确匹配 `Xiaomi Smart Band 8 35BA`。不使用历史配对列表、缓存名称或连接状态代替可见性。不连接手环。
4. 未发现目标时结束，不更改蓝牙。
5. 发现目标后停止扫描，请求关闭蓝牙；等待真实 `STATE_OFF`，从该时刻计时 3 秒，再请求打开蓝牙。
6. 等待真实 `STATE_ON` 后结束。状态切换最长各等待 15 秒；异常、权限拒绝、扫描失败均记录日志并清理。
7. 同一次运行中的多个命中及重复启动不执行多次切换。应用主动关闭蓝牙后，如果关闭状态确认超时但随后观察到关闭状态，尝试恢复蓝牙一次；无法恢复时明确记录失败，不能报告成功。进程被系统强杀后不能保证恢复，不声明绝对可靠。

## 平台兼容边界

采用 Java、Android 原生 API，`minSdk 26`、`compileSdk 35`、`targetSdk 32`。Android 官方文档及 AOSP 的 `RESTRICT_ENABLE_DISABLE` 兼容变更表明，普通应用针对 API 33 及以上时 `BluetoothAdapter.enable/disable` 失败；目标 API 32 保留兼容路径。编译 SDK 版本不会代替目标 SDK。

目标 API 32 不符合 Google Play 新应用上架要求，本项目只侧载。澎湃 OS 4 可能另有 OEM 权限、后台及蓝牙策略，必须用户真机验证。不使用隐藏 API、权限绕过、Root 或 Shizuku。

Android 12 及以上申请 `BLUETOOTH_SCAN` 和 `BLUETOOTH_CONNECT`。为避免 `neverForLocation` 导致部分 BLE 广播被过滤，扫描同时申请精确位置权限、不声明该断言，文档提示打开系统位置服务。Android 8–11 使用旧版蓝牙权限和精确位置权限。应用不推导、保存或上传位置，不申请后台位置权限。

## 无普通窗口启动与授权

桌面图标与外部调用共用导出的透明 Activity。已授权时不展示应用页面，不调用蓝牙开启确认框；首次权限授权允许系统弹窗。透明 Activity 保持短暂前台资格以启动前台服务，服务确认启动后立即结束 Activity。透明 Activity 仍是系统窗口，因此不能保证完全无转场闪动。

服务使用低重要度的短暂通知，完成后移除通知并停止服务。不能保证无系统活动应用提示。服务不导出；外部应用通过显式 Activity 或自定义 action 调用，受系统后台 Activity 启动限制。无任意命令、动态设备名或远程控制接口。

## 结构

- `TriggerActivity`：权限、透明入口、启动服务握手及结束。
- `ResetService`：单次运行的前台通知和生命周期。
- `AndroidBluetoothSession`：适配器、实时 BLE 扫描、状态广播、定时与资源清理。
- `ResetController`：不依赖 Android 的可测试状态机，控制扫描、关闭、3 秒延时、开启与超时恢复。
- XML 资源：图标、透明主题、通知及授权字符串。
- 单元测试：蓝牙关闭、未命中、精确名称匹配、关闭确认、延时、打开确认、重复命中、取消、超时及恢复。

应用使用有超时的部分唤醒锁以减少锁屏导致三秒等待延后的概率，并在清理时释放。进程退出含义为结束 Activity/Service 和相关资源，允许 Android 缓存空闲进程；不使用强制杀进程。

## 构建与交付

提交 Gradle Wrapper、固定 Gradle/Android Gradle Plugin 版本。Actions 在 push、pull_request、workflow_dispatch 时运行 JVM 测试、Android lint、APK 构建；上传签名 debug APK 和校验和作为 artifact。debug APK 可安装，但每次 CI 使用不同临时调试密钥可能无法覆盖安装，README 说明需卸载旧版本或配置固定发布签名。不提交密钥或凭据。

默认推送用户当前 `gpt` 分支，不强推、不擅自合并 `main`。Actions 文件存在于该分支即可由 push 触发。README 提供 artifact 下载、安装、权限设置、外部 Intent、ADB 启动和日志命令，以及真机验收清单。

## 验证及验收

自动测试仅证明控制流、打包、静态检查，不证明 BLE 可见性或 OEM 静默切换。真机验收包括：关闭蓝牙启动不打开；手环不在附近不切换；手环本次广播出现目标名时仅切换一次；关闭确认后三秒再打开；授权拒绝/后台限制时安全退出；首次授权之后没有普通应用页面；前台服务和通知最终消失。

已核实资料：
- https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable()
- https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/guide/components/activities/secure-bal
