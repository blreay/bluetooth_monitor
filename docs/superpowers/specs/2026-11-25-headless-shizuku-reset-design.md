# 无窗口后台扫描与 Shizuku 优先蓝牙重启

日期：2026-11-25。分支：`bluetooth_monitor_shizuku_nogui`。起始提交：`de083db`。

本设计对应新 nogui 请求，不修改已验收的 `bluetooth_monitor_shizuku` 手动页面版本。用户已选择「扫描手环，命中后重启」及「通知提示，点击查看对话框」，并批准本次单一实施计划。后续不设置另一个文档/实现审批门。

## 产品契约

- 新安装身份 `com.blreay.bluetoothmonitor.nogui` 和独立、稳定的 release 证书，可与旧包并存。保留源码/AIDL namespace，不混淆安装包名与 Java 类名。
- 点击图标或 RUN 自动初始化 Shizuku 检测与首次授权，然后后台执行一次 BLE 扫描。没有日常四按钮页面，也没有透明占位窗口。
- 仅当前广告命中 `Xiaomi Smart Band 8 35BA` 才关闭手机整个蓝牙无线电并恢复开启；未命中不切换，不直接连接/配对/重置手环。维持现有名称/MAC 后缀匹配规则和扫描事实报告。
- 不新增开机启动、周期扫描或 24/7 监控。常驻通知只为活动操作/恢复保活。
- 首次必要的 Nearby/Shizuku/系统通知授权，以及普通开关必要系统确认允许出现；正常后续运行不要求手动申请 Shizuku。
- Shizuku 不可用或不能完成操作时，说明实际观察到的原因，自动尝试普通路径。详情对话框只在通知被点击时出现，回退不等待确认，也不绕过后台 Activity 限制。

## 安装身份与签名

`app/build.gradle.kts` 直接切换本分支 applicationId，不引入 flavors 或维护第二种 GUI 构建。min31/compile34/target32、Kotlin/AGP/Shizuku 版本保持。target32 是普通 enable/disable 的兼容前提。

新签名默认读取仓库外 `~/.local/share/bluetooth-monitor-nogui/signing.properties`，可由 `BT_NOGUI_SIGNING_PROPERTIES` 覆盖路径；使用独立的 `BT_NOGUI_*` 字段。不读取旧 `BT_RESET_*` 作为后备，不修改旧 public keystore、旧 gradle.properties、CI 或 wrapper。

密钥仅在不存在时生成并持久保存，目录700、文件600，口令不进入 argv、日志或 git。测试/debug 不要求 release 配置；release 打包必须检查配置、密钥及证书身份，缺失或复用旧证书明确失败，不能悄悄生成 unsigned/old-signed release。后续覆盖安装需要保留/备份这把新私钥。

两安装的权限、通知、Provider、PendingIntent 与数据独立；action 使用 runtime applicationId 派生。它们仍控制同一个物理蓝牙无线电，不能保证同时运行无冲突。

## 入口、权限和进程所有权

MainActivity 使用 `Theme.NoDisplay`，无内容、不等待授权/扫描，在 `onCreate` dispatch 后结束，resume 前必须 finish。授权与命令不属于它的生命周期。

MonitorApplication 只在普通应用进程初始化 coordinator 和官方 Shizuku listeners。UID0/2000 专用 UserService 不执行这套 app 初始化。Shizuku 自动探测先于 Nearby 权限门；拒绝 Nearby 禁止扫描，不应取消首次 Shizuku 检测。

PermissionActivity 仅是真正有内容的不透明设置/详情对话框：首次必要 Nearby setup、notification details、明确 retry/settings。首次创建 channels，为 target32 的系统控制通知授权提供正常 Activity 机会。已设置后不得反复跳页面；禁用通知后不能宣称用户必然看见提示。

ResetCoordinator 主线程拥有唯一 run/command，自动合并/拒绝重复入口，不覆盖正在恢复的记录。service 仅提供操作生命周期保活；恢复 alarm 可以冷启动进程。实例重建不等于重新扫描。

## Shizuku 自动授权

使用官方 api/provider13.1.5 的 sticky Binder-ready、dead 和 permission-result callbacks。公共调用、连接和 RPC 放有界后台 worker；结果回主线程按 generation/requestCode 应用。

启动检查支持的 server version（非 preV11 且 server API13及以上）、实际 UID0/2000、SDK grant、UserService readiness。第一次实际自动 requestPermission 前持久化尝试标记；无 Binder 不消耗。约5秒状态/连接等待与约60秒人类授权等待分离。已授权直接确认/连接，不重复弹框。拒绝或超时后普通后备；再申请由通知详情显式重试触发。

迟到 grant 可以更新后续可用性，但不能再扫描、重启或替换已经派发的 backend。恢复 ON 不等待人的授权。

公共 SDK 没有可靠配对状态 API。加入 manager package queries 后可说明当前用户未找到应用、未收到可用连接、未授权、不支持或操作失败；不能从 ping=false 精确声称「未配对」或「未启动」。可用 Sui/root Binder 优先于 manager 安装检查。SDK grant 可缓存，不宣称每次查询都是未缓存的 server revalidation。

## 统一开关驱动与后备

初始 OFF 和全部恢复 ON 经同一 BluetoothSwitchDriver，在每次实际调用前重新检查能力。普通 `BluetoothSwitch` wrapper 仅作集中后备。健康的确切 UserService 槽可重确认，不在 OFF→ON 之间无谓 teardown。

- 调用前不可用、无 grant、unsupported、连接失败或隔离：说明原因并直接普通后备。
- 高权限调用：UID0/2000、固定 `/system/bin/svc bluetooth enable|disable` 参数；exit0 只表示提交，实际 state 确认后才报告结果。
- 明确未调用与可能已提交分开。RPC 异常、超时、未知返回默认为不确定；不能凭 ERROR 断言无副作用。
- 高权限提交后约10秒仍未观察目标状态：说明未完成/可能厂商限制；只有旧执行端已结束、稳定可读状态仍需要同一变化时才普通后备。已经达到目标或朝目标过渡时继续观察，不重复请求。
- 旧 OFF 不能证明结束时，不盲目重新关闭；保留 durable 恢复观察并提示后备推迟。取消 Future 不等于取消 native Binder/system operation。
- 普通 accepted 亦只表示提交，等待 state；普通拒绝 ON 最多3次、间隔至少5秒，防止每个 tick 打开确认框。明确失败可以从详情 retry，不伪装已经恢复。

保留 AIDL ID1 与 destroy16777114、无参 generated Stub、daemon(false)、固定 argv、bounded output drain、runner terminal/spawn-publication destruction。禁止任意用户命令、sh-c、direct su、私有/废弃 Shizuku API。

终态拒绝、busy、kill 请求或直接 wrapper 的退出不能单独证明旧命令树结束。继承输出仍打开、读取失败/中断或清理未完成时，保留 UNCERTAIN/authorityEnded=false；只有实际 EOF 和 reader 清理完成，结合直接进程退出，才建立该完成 fence。隔离可能阻止自动恢复，不能为了可用性虚构旧命令已经停止。

## SDK 注册安全不退化

保留进程级 UUID single-slot broker、主线程 owner 状态、后台串行 management 与独立 exact-endpoint destruction。只用 remove=true，从不 remove=false；不重用旧 registration/tag/callback。

新槽需旧 management 返回及 SDK disconnected 返回后的主线程 posted cleanup barrier 双重证明。主 Binder death、remove返回或任意延迟均不算证明。never-connected ambiguous slot 保持 detached quarantine；迟到 endpoint 仅销毁。后台版改提示方式，可普通后备，但不能绕过隔离、自动强制停止或静默接纳旧服务。

## Durable 扫描/恢复状态

复用 BandResetController/BandMatcher 和 ReEnablePolicy/PendingResetStore/ReEnableScheduler，不另建重启引擎。目标命中先置 in-flight guard 再 stopScan，排队/batch 广告不得重复 OFF。

pending 保存 runId、commandId、stage/version、OFF dispatch/completion/deadline、phase/attempts、有限 backend/reason history，不存 Binder。操作前 durable commit 成功，并确认 alarm 已安排，再记录 dispatched 并调用 OFF。写入或 scheduling 失败禁止 OFF。

tick 与 completion 做 expected-run/command/version 更新。OFF 在途不能同时 ON，即使已看到 OFF；旧 completion 不能覆盖新的 phase 或 ON history。冷进程不把内存锁消失当 quiescence 证明。仅真实 OFF 请求恢复 ON，只有观察 ON 才成功。

相同已派发命令的迟到完成若安全通过身份/版本/authority 校验，须检查重排 durable 观察，即使旧 receiver lease 过期或 service 已结束；不得借旧 lease 内联发新命令。失败要保留 pending 并说明。详情恢复在有界确认实际 OFF 后只建立 ON readiness；若重排失败留下安全 ON_READY，再次显式继续必须幂等重试调度，不重放 OFF、不改变命令身份或反复重置计数。

原 ReEnablePolicy 保留，外层安全规则在 cap 仍给实际 OFF 恢复机会，过渡状态额外最多30个观察 tick。报告最后观察状态，不把尝试数当墙钟秒数，不武断说仍 ON/必定 OFF。

## Android component 生命周期

正常用户启动/权限完成使用短生命周期 foreground ResetService，及时 silent ongoing notification，满足 Nearby 后 connectedDevice type；START_NOT_STICKY 和 startId/run ownership 保证不重扫/误 stop 新请求。已提交但尚未收到服务回调的 admission 是独立的 pending 状态，不等于拒绝服务；等待确切 run/attempt 的成功或有界失败后才选择服务8秒或受限6秒扫描。失败/服务结束先取消并清除确切旧 controller，不影响较新 run；过期或迟到回调不能接纳旧 attempt。

已开始扫描的 exact scanner/callback 清理是保留到实际完成的 retirement，不是过期后可丢弃的新扫描请求。清理准入独立于可能阻塞的 adapter 状态读取，线程/队列有界且合并同一清理；清理尚未完成、失败或阻塞时不能假称 registration 已释放，也不能叠加替代扫描。主线程观察超时可结束报告，但不取消这条 native retirement 责任。

广播/alarm 尝试合法 service handoff，拒绝时不后台开页面。受限广播6秒扫描/9秒 goAsync backstop，命中写 OFF_READY job 并安排执行，不把授权/长RPC塞进剩余扫描预算。

冷 alarm 拒绝 service 时，一次 goAsync coordinator step 约8秒，先重排后续 tick；约1.5秒 capability/bind、约6秒RPC，留 finish margin，不等人类授权。receiver只派发/记录；10秒状态确认分后续 ticks，不额外叠在 receiver 内。过期 queued work 不执行新命令。

exact/inexact、Doze、MIUI/HyperOS自启动/省电可推迟或禁止运行。无 boot recovery，不保证 reboot/force-stop 后自动恢复；app再次运行先处理已有 pending，而非新 OFF。无限 native stall 可能需要通知指引手动处理。

## 通知、测试与交付

独立 ongoing/status 与最终结果 ID。模式依据真正尝试的 backend：Shizuku shell/root、普通、混合、尚未切换；grant 不等于本次模式。每阶段显示 submission/confirmation/uncertainty 和 fallback reason。详情 immutable direct Activity PendingIntent，无 trampoline；ordinary fallback不等点击。

保留原42 matcher/policy/report与 privilege/runner/broker regressions。增加自动请求一次、迟到grant、duplicate/stale run、arm-before-OFF、failed scheduling、两阶段backend、冷恢复、uncertain command、ON重试限制、worker admission、receiver预算及真实模式测试。source boundary 只辅助行为测试；旧GUI/no-fallback/startup-inert断言有目的地改写。

spec review 后 quality review；修正后 fresh test/build/signature/metadata/checksum。真机可用时验证并存、无窗口、实际ON/OFF、first-use和fallback；不可用明确未验证。最后推送本分支并交付 `bt-monitor-nogui.apk`。不合并main、不PR、不dispatch或覆盖rolling latest。
