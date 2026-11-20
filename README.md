# 蓝牙手环条件重启器

一个不常驻的 Android 小工具：**仅当蓝牙已开启，而且本次 BLE 扫描发现 `Xiaomi Smart Band 8 35BA` 时，关闭蓝牙，确认关闭后等待 3 秒，再打开蓝牙，结束运行。**

- 蓝牙原本关闭：退出，不打开。
- 最多扫描 15 秒；未发现目标：退出，不切换。
- 精确匹配本次广播的名称，不把历史配对、缓存名称或已连接列表当作“可见”。不连接手环。
- 每次调用只运行一次，重复点击不并发重启。
- 有桌面图标；日常没有普通应用页面。首次有系统授权框，透明启动可能有转场闪动，执行期间可能有短暂通知/系统活动应用提示。
- 不联网、不上传信息、不定时运行，无广告，无 Root/Shizuku 依赖。

## 兼容性：请先看

本项目针对小米 17 Pro Max / 澎湃 OS 4 的无 Root 环境设计，**尚未在该手机实测**。

使用 `minSdk 26`（Android 8+）、`compileSdk 35`、**`targetSdk 32`**。Android 官方对普通应用直接调用蓝牙 `enable()`/`disable()` 的禁止针对目标 API 33 及以上；目标 32 保留旧兼容路径。因此本项目是**直接安装的兼容版 APK**，不是符合 Google Play 新应用上架要求的版本。

**新系统和澎湃 OS 可能额外限制权限、后台启动或蓝牙切换。编译成功不代表该手机必定允许自动开关。** 如果系统拒绝，应用会退出并记录原因，不使用隐藏 API、Root、Shizuku 或其他权限绕过。日常蓝牙切换会断开所有当前蓝牙设备，包括耳机、手表和车机。

手环必须在该时刻发出包含完整目标名的 BLE 广播。已连接手环可能停止广播，或只广播无名称的包；这种情况下应用不会重启蓝牙。缓存设备名即使与目标相同，也不作为证据。

## 下载 APK

1. 打开 [GitHub Actions](https://github.com/blreay/bluetooth_monitor/actions/workflows/build-apk.yml)。
2. 选择 **`gpt` 分支最新一次绿色成功的 Build APK**。
3. 在页面底部 **Artifacts** 下载 `bluetooth-monitor-apk`（GitHub 通常要求登录才能下载）。
4. 解压后安装 **`bluetooth-monitor.apk`**。同目录含 SHA-256 校验文件。Artifact 保留 30 天，可再次 push 触发构建。

这是使用调试密钥签名、可直接安装的 debug APK，不是未签名 release 包。CI 使用的临时调试密钥可能变化，后续 APK 若提示签名冲突，需卸载旧版后重装并重新授权。卸载会清除本应用授权；不会解除手环配对。仓库不包含发布私钥；如需稳定覆盖更新，可另行配置固定的私有签名密钥和 Actions Secrets。

默认分支为 `main`，当前开发提交在 `gpt`；push 会自动触发该分支构建，不需要先合并 main。手动 `workflow_dispatch` 入口通常要求 workflow 已存在于默认分支，本次不擅自合并默认分支。

## 首次使用

1. 安装 APK，允许系统安装来源（若系统要求）。
2. 开启手机蓝牙，把手环放在附近，点击“蓝牙手环重启”图标。
3. 按系统提示授权 **附近的设备**、**精确位置**。如果位置提示提供“模糊/精确”选项，选择精确。
4. 确保系统位置服务已开启。首次分阶段授权后可能要再次点击图标，才会执行完整扫描。
5. 此后点击图标即可运行；没有设置页，也不会自动开启本来关闭的蓝牙。

申请位置权限是为了支持不经过 `neverForLocation` 过滤的 BLE 扫描；应用不推算、保存或上传位置。**不申请后台位置权限**。Android 8–11 使用旧蓝牙权限和精确位置；Android 12+ 使用附近设备和位置权限。

如果拒绝或永久拒绝权限，应用安全退出，不反复弹窗；可以在 **系统设置 → 应用管理 → 蓝牙手环重启 → 权限** 中补齐后重试。根据澎湃 OS 实际限制，必要时允许后台运行/自启动、调整电池限制；这些设置不赋予系统蓝牙特权。

## 被其他应用调用

包名：`com.blreay.bluetoothmonitor`

导出入口：`com.blreay.bluetoothmonitor.TriggerActivity`

自定义 action：`com.blreay.bluetoothmonitor.RUN`

建议使用显式 Intent，避免 action 冲突：

```java
Intent intent = new Intent("com.blreay.bluetoothmonitor.RUN");
intent.setClassName(
    "com.blreay.bluetoothmonitor",
    "com.blreay.bluetoothmonitor.TriggerActivity"
);
intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
context.startActivity(intent);
```

Tasker、MacroDroid 等支持启动 Activity 的工具，可填上面的包名和类名，或使用“启动应用”。没有参数：目标名称固定，外部调用不能执行任意命令或指定其他设备。

**后台调用受 Android 后台 Activity 启动限制**。前台应用中的用户点击是推荐调用方式；不能保证任何后台应用随时调用都成功。应用不提供常驻广播监听器或导出的服务来规避此限制。首次使用请先从图标完成权限授权。

ADB 示例：

```bash
adb shell am start -n com.blreay.bluetoothmonitor/.TriggerActivity
adb logcat -s BluetoothMonitor
```

## 执行、退出与故障

运行使用 `connectedDevice|location` 类型的短时前台服务，保持透明入口结束后扫描所需的前台位置权限资格，不要求后台位置授权。低重要度前台通知和最长有界的部分唤醒锁降低锁屏导致等待延后的概率。退出时停止扫描、注销状态接收器、清除计时任务、释放唤醒锁、移除通知、结束服务与 Activity。Android 可能缓存空闲进程，这是正常的，不等于持续运行。

关闭和打开状态转换各有 15 秒超时。关闭首次超时后保留一次额外 15 秒的迟到关闭恢复窗口，仍未确认关闭则结束，不盲目开启。正常与恢复路径都从真实关闭状态开始等待 3 秒，不从调用 `disable()` 时开始计时，也不把“正在关闭”当成“已经关闭”。扫描失败、权限错误、系统拒绝开关等原因会写到 `BluetoothMonitor` 日志。

应用尝试对自身关闭后的可恢复错误做一次有界恢复，但**如果进程在关闭期间被强杀、被强制停止、权限撤销或系统拒绝开启，不能保证蓝牙恢复**。发现蓝牙仍关闭时请手动打开。它不会宣称失败的切换已成功，也不会无限重试。

## 本地构建

需要 JDK 17、Android SDK 平台 35 和 build-tools 35.0.0。设置 `ANDROID_HOME`（或在未提交的 `local.properties` 中设置 `sdk.dir`）后运行：

```bash
./gradlew --no-daemon :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

Gradle Wrapper 固定为 8.9，AGP 固定为 8.7.3。Actions 使用 Java 17，自动运行测试、Android lint 和 APK 打包，同时上传验证报告。Lint 仅豁免与本项目侧载目标冲突的 `ExpiredTargetSdkVersion`（Google Play 上架要求），其他错误仍使构建失败。测试使用虚拟时钟验证控制流，不依赖真实蓝牙硬件。

## 手机验收清单

- [ ] 蓝牙关闭时点击：没有打开蓝牙。
- [ ] 蓝牙开启、手环不在附近：等待最多 15 秒后结束，没有切换。
- [ ] 实际扫描收到目标名称：停止扫描，蓝牙关闭，确认关闭后三秒再开启。
- [ ] 连续点击或多个命中：仅执行一次重启。
- [ ] 手环只是历史配对但当前不广播：不切换。
- [ ] 权限拒绝或扫描失败：安全结束，不错误切换蓝牙。
- [ ] 首次授权后不出现普通应用页面；通知/服务在完成后消失。
- [ ] 从另一个前台应用通过显式 Intent 可启动。
- [ ] 手机锁屏时观察运行能结束；失败时查看日志，不将测试报告当作真机成功证明。

## 平台资料

- [BluetoothAdapter 开关限制](https://developer.android.com/reference/android/bluetooth/BluetoothAdapter#disable())
- [蓝牙与扫描权限](https://developer.android.com/develop/connectivity/bluetooth/bt-permissions)
- [前台服务后台启动限制](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start)
- [后台 Activity 启动限制](https://developer.android.com/guide/components/activities/secure-bal)
