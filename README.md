# 蓝牙手环重启 · 后台 Shizuku 优先版

本分支 `bluetooth_monitor_shizuku_nogui` 是独立安装的后台版本。**正常启动没有控制页面，也不显示透明占位窗口：自动检测并申请首次 Shizuku 授权，后台扫描目标手环，找到才重启手机蓝牙。**

旧版可见四按钮工具仍在 `bluetooth_monitor_shizuku` 分支；本版本不是给那个页面增加一个隐藏开关。

## 使用方式

1. 安装后台版 APK。包名 **`com.blreay.bluetoothmonitor.nogui`**、release 签名均与旧版不同，旧版不需要卸载。
2. 点击后台版图标。首次必要时允许「附近的设备」权限；允许通知，才能看到运行模式和结果。
3. 应用启动即检测 Shizuku。若已启动且支持本应用，会自动申请第一次授权，在 Shizuku 对话框中允许；**没有每次都要点的授权按钮**。已授权时直接检查/连接。
4. 执行一次 BLE 扫描。当前广告命中 `Xiaomi Smart Band 8 35BA` 后才关闭手机蓝牙，再等待实际 OFF 后请求恢复 ON。没有找到就不切换。
5. 运行和恢复通过静默通知说明。需要详细原因或重新授权时，点击通知打开真正的详情/设置对话框。

后台版的数据、附近设备权限和 Shizuku 授权与旧安装独立，需单独完成首次授权。第一次的系统权限/Shizuku授权可能需要参与，普通后备也允许必要的系统确认；这些是例外，不是常规启动页面。拒绝附近设备权限时，不进行扫描或假称已经重启。

这是**一次启动、一次检测**，不是全天监控；没有开机自动运行或周期扫描。它重启的是手机整个蓝牙无线电，可能暂时断开耳机等其它设备。它不直接配对、连接或重置手环，重连由 Mi Fitness 等拥有连接的应用自行完成。

## Shizuku 优先与普通后备

初始关闭和每次恢复开启都走同一个异步开关驱动，分别检测当前 Shizuku 能力；不是「Shizuku 关闭、固定普通开启」。

| 情况 | 行为 |
|---|---|
| Shizuku 已授权、专用服务可用 | shell/root 高权限路径执行固定 `svc bluetooth enable/disable`，尽量避免普通系统开关确认 |
| 未检测到 manager、没有可用连接、未授权、版本不支持或连接失败 | 通知说明观察到的原因，自动尝试普通开关路径，不等待用户点击详情 |
| 系统/厂商没有完成高权限请求 | 检查实际状态与旧请求执行情况，满足安全条件后转普通路径 |
| 旧请求超时/结果不确定 | 不盲目重复关闭；保留恢复观察，说明后备推迟及必要处理 |
| 普通接口拒绝、权限缺失或通知被关闭 | 如实记录，不能保证执行或用户一定看到提示 |

普通路径保留 `targetSdk=32`，使用原有 `BluetoothAdapter.enable()/disable()`，仍可能弹出 MIUI/HyperOS 确认或被厂商拒绝。不能承诺所有手机完全无弹窗。

首次实际自动授权尝试会记住；无 Binder 时不消耗这次尝试。拒绝/超时后不反复弹授权框，可通过通知详情显式重试。手机重启后通常仍需要按 [Shizuku](https://shizuku.rikka.app/) 的说明重新启动它；本应用不能替用户完成无线调试配对。

**诊断边界：** Shizuku 公共客户端接口不能可靠区分「未启动」与「尚未配对」，所以会说明「未连接，可能未启动或未完成配对」，不会从一个 ping 失败伪造确定原因。可用 Sui/root Binder 也不会只因 manager 应用未安装就被拒绝。为保留非 daemon 服务及安全清理，本后台版仅接纳 server API13及以上；过旧的服务会说明原因并转普通模式，建议更新 Shizuku。SDK 授权状态可能缓存，不宣称每次检查都是无缓存的服务器校验。

SDK 异常连接可能进入安全隔离。此时不接纳迟到的旧服务、不无限重绑，也不自动强制停止应用；通知会指导必要时在系统设置中强行停止后台版后重开，**不要求卸载**。可用普通后备不受这条高权限连接隔离影响。

## 通知与结果

- **Shizuku shell / Shizuku root**：本阶段真正尝试了相应高权限路径。
- **普通模式**：本阶段使用普通应用开关。
- **混合模式**：例如高权限关闭之后，因 Shizuku 断开改用普通开启。
- **尚未切换**：未命中目标、缺少必要条件，或只检查授权尚未发出开关。

授权成功不等于本次已使用高权限，也不等于蓝牙重启成功。

- 「请求已提交」不是实际 ON/OFF；系统切换是异步的。
- 只有观察到 OFF 才请求恢复，不在 TURNING_OFF 时盲目开启。
- 只有实际观察到 ON 才报告恢复成功。
- 超时/失败说明最后观察状态，不武断声称一定 ON 或一定 OFF。
- 活动服务通知与最终结果通知分开；详情通过直接 Activity PendingIntent 打开，不由后台服务强行抢前台。

通知被用户或渠道关闭时，详情仍可持久记录并写日志，Toast 只是尽力提示，不能保证送达。首次设置会创建通知渠道，Android13+ 在 target32 下由系统决定通知授权时机。

## 自动化入口

应用源码类 namespace 保持原值，与新安装包名不同。请使用**完整组件名**，不要把新包名后面的 `.MainActivity` 简写当成真实类名。

Activity Intent：

```text
Action:   com.blreay.bluetoothmonitor.nogui.action.RUN
Package:  com.blreay.bluetoothmonitor.nogui
Class:    com.blreay.bluetoothmonitor.MainActivity
```

adb 启动一次后台检测：

```bash
adb shell am start \
  -n com.blreay.bluetoothmonitor.nogui/com.blreay.bluetoothmonitor.MainActivity \
  -a com.blreay.bluetoothmonitor.nogui.action.RUN
```

显式广播（限定新包，避免旧安装同时执行）：

```bash
adb shell am broadcast \
  -a com.blreay.bluetoothmonitor.nogui.action.RESET_IF_PRESENT \
  -p com.blreay.bluetoothmonitor.nogui
```

Tasker/MacroDroid 等可使用这些入口，但 Android 的后台 Activity/foreground-service 启动限制仍生效。受限广播保持有界扫描；命中后先保存 job，再通过恢复调度继续，不在广播的约10秒生命周期内无限等待授权/RPC。

## 恢复与厂商限制

关闭之前先确认恢复状态已持久写入、下一次 alarm 已安排，失败就不关闭。重复入口不会覆盖已经进行中的重启；冷进程依据 durable run/command 状态恢复，不把上一进程退出当旧命令一定停止。

Android/OEM 可以延迟 alarm、提前回收进程、限制自启动；**尝试次数不是保证的墙钟秒数**。系统强行停止或重启也不是普通进程回收，本版本没有 boot receiver，不能承诺这两种情况自动恢复。再次运行会先处理已有 pending，而不是直接再关闭。

若旧命令或其继承输出的子进程是否结束仍不确定，会保留 pending 并拒绝危险的重复/反向切换；即使当前看到 OFF，也不能把它当成旧请求已停止的证明。这种安全隔离可能阻止自动恢复，需手动检查蓝牙状态。用于 SDK 连接隔离的「强停后重开」不会被当成旧开关命令已经结束的证明。

MIUI/HyperOS 可按需设置：

| 设置 | 作用 |
|---|---|
| 附近的设备 → 允许 | 真实扫描与读取状态/普通后备所需 |
| 通知 → 允许 | 查看模式、失败详情、恢复结果 |
| 应用权限 → 开关蓝牙 → 允许 | 减少普通后备的厂商确认/拒绝，不是高权限授权本身 |
| 自启动 / 省电策略 → 允许后台或无限制 | 减少后台扫描/恢复被限制；不等于本应用新增开机启动 |

两安装虽可并存，控制的是同一个物理蓝牙；不建议同时触发两个版本的开关/恢复任务。

## APK 与构建

本分支的交付文件名为 **`bt-monitor-nogui.apk`**，本次已验证的本地路径：

```text
app/build/outputs/apk/release/bt-monitor-nogui.apk
```

2026-11-25 交付文件：2339669 字节，versionCode1/versionName1.0。

```text
APK SHA-256: 999119f1a9fee387f52d99df437215a421cf5800a89059cd07f52fa048d2e518
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

主会话对最终源码两次完整重跑：181 项 JVM 测试，零失败/错误/跳过，debug/release 构建成功；新证书有效且重复构建稳定。独立规格、质量及最终整体审查通过。Lint 保留1项 `ExpiredTargetSdkVersion` Fatal及12项Warning，并非lint-clean。没有连接的adb设备，真机验证未执行。

已有 `main` 的 `latest/bt-reset.apk` 仍是原滚动产物，**不是后台新版**；功能分支 push 不自动发布它。本任务不合并 main 或手动触发发布工作流。

JDK17、SDK platform34/build-tools34.0.0：

```bash
./gradlew testReleaseUnitTest assembleDebug
# debug：app/build/outputs/apk/debug/app-debug.apk
```

release 需你自己的仓库外私密签名配置，默认：

```text
~/.local/share/bluetooth-monitor-nogui/signing.properties
```

文件包含 `BT_NOGUI_STORE_FILE`、`BT_NOGUI_STORE_PASSWORD`、`BT_NOGUI_KEY_ALIAS`、`BT_NOGUI_KEY_PASSWORD`，指向持久 JKS。限制文件访问并备份；**不要把私钥/口令提交 git**。缺配置的 release 明确失败，不退回仓库中旧公开密钥或 debug key。换机器构建要恢复同一密钥，不能每次生成新证书。

```bash
BT_NOGUI_SIGNING_PROPERTIES="$HOME/.local/share/bluetooth-monitor-nogui/signing.properties" \
  ./gradlew testReleaseUnitTest assembleRelease
# release：app/build/outputs/apk/release/app-release.apk
```

当前开发环境使用缓存工具链：

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain
```

共享构建主机发生资源压力时，可仅对当前命令追加 `--max-workers=2 --no-parallel`，或通过临时 `GRADLE_OPTS` 限制并发；不改变测试断言、5秒 JVM fixture 或生产超时。默认并发下曾出现两次原有 JVM fixture 超时，临时限流的完整重跑通过；这支持环境压力假说，但没有证明原始根因，详见验证记录。

本地 versionCode 默认1，沿用 `GITHUB_RUN_NUMBER+1` 的版本逻辑。独立包首次安装不受旧包版本号影响；以后更新后台版本仍需同签名与不低于已安装版本的版本号。

设计与实际验证记录：

- [后台版设计](docs/superpowers/specs/2026-11-25-headless-shizuku-reset-design.md)
- [实施与验证记录](docs/superpowers/plans/2026-11-25-headless-shizuku-reset.md)
- [已验收的手动页面设计](docs/superpowers/specs/2026-11-24-manual-shizuku-control-design.md)

JVM 测试和 APK 构建不替代真机验证。Shizuku/OEM确认框、无焦点窗口、实际OFF→ON和进程死亡后的恢复，需在实际测试手机上验证；最终交付记录明确哪些执行、哪些未执行。
