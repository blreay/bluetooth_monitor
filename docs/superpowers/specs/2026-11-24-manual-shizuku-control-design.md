# 可见页面与手动 Shizuku 蓝牙控制

日期：2026-11-24。适用分支：`bluetooth_monitor_shizuku`。

## Context

旧应用的 launcher 是 `Theme.NoDisplay` Activity，启动即进入权限申请/扫描服务；BLE 命中目标手环后会关闭蓝牙并安排恢复。用户现在需要一个可见的手动控制页面，明确申请 Shizuku 权限、无普通确认框的高权限蓝牙开关，以及直接显示扫描设备的按钮。

本设计经单一计划审批后执行；不额外设置文档/实现阶段审批门。

## 用户可见行为

- 打开应用、再次打开、`action.RUN`、旋转页面：显示正常 Activity，不自动授权、扫描、重置或切换蓝牙。
- 四个按钮固定为「申请shizuku权限」「关闭蓝牙」「打开蓝牙」「扫描蓝牙」。
- 请求 Shizuku 权限后，只有真实 grant 才显示「shizuku授权成功」。授权和专用高权限服务是否连接成功是两个状态。
- 手动关闭不会安排自动重新开启；手动打开由对应按钮独立触发。
- 扫描只发现设备，发现目标手环也不触发匹配重置、配对或连接。
- 设备列表显示名称、MAC、RSSI、发现类型，未知名称有明确占位；MAC 去重，保留有效名称、更新 RSSI、合并 BLE/经典类型。
- 扫描结束保留结果，实际启动新扫描时才清空；离开页面停止扫描。

## 架构与责任

### 可见 MainActivity

沿用原生 `android.app.Activity`，使用正常、不透明主题和最近任务。布局包含授权按钮、授权状态、独立开关按钮、扫描按钮、操作状态和设备列表，不引入 UI 框架。控制、状态和设备行整体可滚动，避免短横屏窗口、大字体或较长命令输出把扫描结果挤出可访问区域。

删除 NoDisplay 即时 dispatch/finish 行为以及旧 PermissionActivity 的授权后 reset 入口。Nearby 权限由可见页面在扫描点击之后申请，授权完成只恢复那次明确点击请求，不能变成通用启动扫描入口。

### Shizuku 客户端

使用官方 `api` / `provider` 13.1.5。Provider authority 为 `${applicationId}.shizuku`，`exported=true`、`multiprocess=false`，由 `INTERACT_ACROSS_USERS_FULL` 保护访问；这不是普通应用获得系统权限。

授权按钮主动请求；已有授权时确认/重连，不重复弹框。已有正常服务时，在重新读取真实 grant 后仅确认当前槽及其确切 live Binder，不解绑健康服务或创建新注册；缺失/已死服务才进入连接/隔离流程。处理 requestCode、未运行、拒绝/永久拒绝、binder 死亡/重启、连接超时、服务断开；销毁时移除监听、取消界面与命令权限、忽略旧回调。授权成功不被服务连接错误误写为授权失败。

官方 SDK 13.1.5 的本地 UserService 回调集合/缓存不是完整线程安全的，而 `unbindUserService(..., false)` 在新服务端还可能先进行阻塞 Binder 调用。采用进程级单槽连接 broker：每个槽使用独立 UUID tag 与回调，仅注册一次；管理调用串行放后台，不调用 remove=false。取消时清除界面所有者，只销毁该槽的确切远端并执行 remove=true。新槽必须等待旧管理任务完成，以及 SDK `onServiceDisconnected` 返回后的主线程 posted barrier，不能与旧缓存清理重叠。

从未收到服务 Binder 的失败/超时槽可能永远没有可证明的 SDK 本地清理回调。此时有限等待后明确提示隔离状态，要求从系统设置强行停止本应用后重开；不卸载、不自动重启，也不凭主 Shizuku Binder 死亡、remove=true 返回或任意延时冒认清理完成。迟到的旧槽 Binder 仅可用于销毁，不可作为新连接。扫描仍独立可用。这个异常恢复限制优先保留代际身份、主线程非阻塞与无普通接口降级。

### 高权限 UserService

启用 AIDL。UserService 是公开无参构造的生成 Stub 子类，不是普通 Android Service，使用官方 `bindUserService` 启动 shell/root 专用进程。AIDL 所有方法明确 transaction ID；`destroy` 使用 SDK 约定的 `16777114`。命令 runner 的销毁是终态，与短暂的子进程创建/登记区间协调，先终止已创建子进程再结束专用父进程，不能漏掉尚未登记的子进程，也不能等待长命令执行锁；销毁后拒绝新命令。

只暴露固定 enable/disable 行为。执行前校验 UID 是 root(0) 或 shell(2000)，使用固定 `ProcessBuilder` 参数 `/system/bin/svc bluetooth enable|disable`。`svc` 内部调用系统高权限蓝牙管理接口。

禁止：普通应用 `BluetoothAdapter.enable()/disable()`、`ACTION_REQUEST_ENABLE`、普通接口 fallback、废弃/私有 `Shizuku.newProcess`、任意拼接命令、`sh -c`。

连接、RPC、命令和状态确认分别限时；等待不占主线程。合并输出持续排空，仅保存有限文本；超时终止进程。退出码 0 只能表示请求提交，不能视作最终 ON/OFF。

已有 Nearby 权限时有界读取真实状态，确认后才报告已开启/关闭；没有权限时明确报告未确认，不为开关额外弹 Nearby 或普通开关确认框。OEM 拒绝、输出错误、超时均可见且不降级。

### 只发现设备的 scanner

与 `BandResetController` 的目标命中/reset 完全分离。复用现有适配器和 Nearby 权限 helper，以及无硬件过滤低延迟 BLE、广播名优先/缓存名 fallback、batch callback、安全停止的模式。

蓝牙必须 ON；不隐式开蓝牙。旧恢复链仍在进行时提示等待，扫描不取消必要恢复。先 BLE 8 秒，再经典 discovery 最多 15 秒，总时长约 23 秒以内。阶段失败保留部分结果并注明未完成类型。

只显示本次真实 scan/discovery 结果，已配对列表不是扫描结果。零设备有多种原因，不武断认定系统限流。离开页面/销毁停止扫描、移除 timer、注销 receiver，旧 generation 回调不能更新新页面。

### 手动与旧自动化的隔离

保留显式 `RESET_IF_PRESENT` 广播和恢复 alarm 协议；普通 launcher / `action.RUN` 不再进入旧 service。

纯 Kotlin generation/manual-active interlock 让旧扫描令牌永久失效，防止旧会话在手动操作之后命中目标并重新安排恢复。手动 toggle 通过授权/连接预检后主线程占用 interlock，再清除 pending store/取消 alarm。旧 controller 在命中通知/reset 之前检查令牌；手动期间拒绝新 legacy reset，旧恢复回调不得抢回控制。

手动 scan 在权限/状态/无 pending recovery 预检后也占用 interlock。终止/失败/取消/销毁均释放，过期 token 不能释放新操作的锁。旧广播路径仍保留普通接口及其 OEM 限制，文档明确与新高权限按钮区分。

## 验证要求

- JUnit：命令固定/高 UID、授权与服务状态、binder 死亡、MAC 去重、有效名称保留、RSSI 更新、类型合并、目标也不 reset、旧 token 失效及 stale-release 安全；命令销毁与创建/登记竞态、终态拒绝、不等待长命令；单槽隔离、迟到 endpoint 仅销毁、管理返回与 SDK cleanup barrier 双重准入、清除所有者及旧槽回调安全。
- 回归：现有目标匹配、恢复状态机、通知报告测试不能受损。
- 构建：固定 SDK/工具链下编译 AIDL、Kotlin、资源，debug/release APK 和 lint；不得把编译通过视作真机成功。
- 真机：启动无副作用，Shizuku 未运行/拒绝/成功/重启，实际 ON/OFF 且无普通确认框，扫描设备/空结果/取消/旋转/退出，旧广播兼容与手动接管。
- 无可用设备时明确标记真机测试未执行；记录命令、测试数和构建结果，不冒称验证。

## 交付边界

在当前功能分支提交、推送，不自动合并 main 或覆盖 rolling latest。现有 CI 只监听 main push/manual dispatch，因此普通功能分支推送不等于 CI 已运行。

实现计划：`docs/superpowers/plans/2026-11-24-manual-shizuku-control.md`。
