# 蓝牙手环重启 · 后台 Shizuku 优先版

本分支 `bluetooth_monitor_shizuku_nogui` 的当前源码版本为 **1.2**：保留 1.1 的通知详情手动控制、真实 BLE 扫描、独立自动历史与已验证累计计数，改用 ON/OFF 瞬时 Toast 反馈，并增加每次启动、扫描与开关的有界 `miband.log` 日志。**日常图标、RUN 与广播入口仍为 NoDisplay：没有新首页，也不显示透明占位窗口；自动检测并申请首次 Shizuku 授权，后台扫描目标手环，找到才重启手机蓝牙。**

1.2 保持后台版 1.0/1.1 的包名与签名守卫，本地 code 3 用于升级实际已交付的 1.1/code 2；**正式 APK 已完成两次父构建和实际原 1.1 并排身份核验，同包同证书、code 2→3，符合覆盖升级身份条件；真机升级和数据保留未验证**。旧版可见四按钮 GUI 工具仍在 `bluetooth_monitor_shizuku` 分支，包名/签名与后台版独立，可以分别安装。本版本不是给那个页面增加隐藏开关，也不修改 fooView 应用列表识别行为。

## 使用方式

1. 安装已核验的 **`bt-monitor-nogui-1.2.apk`**（持久路径见下方）。包名保持 **`com.blreay.bluetoothmonitor.nogui`**，同证书、code 2→3，可对后台 1.1/code 2 直接覆盖安装，无需先卸载或清数据；真机覆盖与数据/权限保留未执行验收。旧 GUI 版是另一安装，不需卸载；更高 code 的 CI 安装不能用本地 code 3 降级。
2. 点击后台版图标。首次必要时允许「附近的设备」权限；允许通知，才能看到运行模式和结果。
3. 应用启动即检测 Shizuku。若已启动且支持本应用，会自动申请第一次授权，在 Shizuku 对话框中允许；**没有每次都要点的授权按钮**。已授权时直接检查/连接。
4. 执行一次 BLE 扫描。当前广告命中 `Xiaomi Smart Band 8 35BA` 后才关闭手机蓝牙，再等待实际 OFF 后请求恢复 ON。没有找到就不切换。
5. 运行和恢复通过静默通知说明。**只有主动点击通知才打开真正的不透明详情窗口**，可查看本次/所选历史、实时手动扫描结果并操作「打开蓝牙」「关闭蓝牙」「扫描蓝牙」；ON/OFF 使用瞬时消息，不再有按钮下方的结果文字区。首次权限设置及显式系统设置仍是必要例外，不是日常首页。
6. 如需写根目录日志，在可见详情中主动点击「授权日志写入（所有文件访问）」，在系统设置中允许本应用管理所有文件，再返回详情；这是独立的日志特殊访问授权，不是 Shizuku 或附近设备授权。日常 fooView/图标/RUN/广播入口不会自动打开此权限页面，拒绝授权也不阻塞原有蓝牙流程。

后台版的数据、附近设备权限和 Shizuku 授权与旧安装独立，需单独完成首次授权。第一次的系统权限/Shizuku授权可能需要参与，普通后备也允许必要的系统确认；这些是例外，不是常规启动页面。拒绝附近设备权限时，不进行扫描或假称已经重启。

这是**一次启动、一次检测**，不是全天监控；没有开机自动运行或周期扫描。它重启的是手机整个蓝牙无线电，可能暂时断开耳机等其它设备。它不直接配对、连接或重置手环，重连由 Mi Fitness 等拥有连接的应用自行完成。

## Shizuku 优先与普通后备

自动重启的初始关闭、每次恢复开启，以及详情中的手动 ON/OFF 都走同一个异步开关驱动，分别检测当前 Shizuku 能力；不是「Shizuku 关闭、固定普通开启」。手动操作也优先 Shizuku，安全可用时才转普通后备。

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

## 通知详情中的手动控制（1.2，保留 1.1 安全约束）

详情窗口由通知直接打开，不会由后台服务抢前台，也不会因日常图标/RUN/广播入口而成为首页。1.2 删除「打开蓝牙」「关闭蓝牙」按钮下方的结果文字区，**仅「扫描蓝牙」保留实时结果区**；当前自动状态、所选完整 runId 的历史明细、累计、权限和恢复入口仍独立显示，不用最新结果冒充旧卡片。持久手动结果和未知执行隔离记录仍保留，不因移除 TextView 而删除。

- **打开蓝牙 / 关闭蓝牙：** Shizuku 优先、普通路径后备，由实际进行中的请求发送普通文字 Toast：接纳/等待、拒绝、实际终态、保存失败或结果未知各自说明关键原因；已知实际路径时带 **Shizuku shell / Shizuku root / 普通模式**。接纳时 backend 为 null 不编造路径，`NONE` 只表示「尚未切换、未调用开关」，不伪称高权限执行。「已接纳」或 `SUBMITTED`/「请求已提交」**不是成功**；只有可靠的实际目标状态、执行结束证据与 checked 保存结果才能支持成功终态，no-op 也不是重启成功。手动关闭完成后保持 OFF，**不创建自动 ON、自动恢复任务或自动计数**；除非用户另行明确打开或其它应用改变蓝牙状态。
- **瞬时而非历史回放：** 同操作同类反馈去重，独立拒绝点击各自提示；未知之后真正结束可以有新反馈。消息不从持久 snapshot、旧卡片或窗口 render 重构，打开/重建详情不会重播旧 Toast。Toast 受 Android/OEM 限制，尽力发送而不保证送达；完整原因与时间仍在日志和原安全记录中。
- **冲突拒绝：** 自动运行、待恢复、手动开关或扫描正在占用资源时，不接纳冲突请求，不排队、不抢占、不偷走旧任务所有权。拒绝原因通过本次瞬时消息反馈，不替换仍进行中的持久结果；权限补齐后也不自动重放旧按钮。
- **扫描蓝牙：** 仅进行一次 **8 秒真实 BLE 广告扫描**，实时显示本次收到的设备；最多保留 **100 个**，额外设备用 overflow 告知未保留。它不是已配对设备列表或经典蓝牙发现列表。命中目标只加「目标手环，仅展示」标记，**不自动开启蓝牙、不配对、不连接、不重置**；蓝牙为 OFF 时不会为了扫描偷偷打开。
- 关闭窗口、Activity 重建/销毁或 Service 销毁不代表底层执行结束。超时、取消、窗口消失或当前硬件已到目标状态，都不能解除未知的 native/scanner 所有权隔离；冷进程也不重放未确认的手动操作，只有可靠的底层结束证据才能安全释放占用。

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

### 自动历史与累计计数（从 1.1 开始）

每个**自动扫描命中目标且被接纳的运行**各自记录一张静默、可划掉的详细历史卡，以完整 `runId` 隔离，不用固定一张最新卡覆盖所有运行。卡片与通知详情展示目标广告、接收/扫描时间、OFF/ON 命令与实际调用/提交/结束证据、实际状态采样、Shizuku/普通/混合路径、终态原因、本次截至累计和当前持久累计。请求/返回/观察时间不是精确硬件切换瞬间；缺少可验证耗时或事实时如实标为未知。

**只有同一自动运行完整验证「已提交 OFF → 实际 OFF → 已提交 ON → 实际 ON」，并确认相关 native 执行权限均已结束，才计数一次。** 手动操作、no-op、未命中、冲突拒绝、失败/未知及缺少完整证据的旧运行都不计入。重复回调或恢复不会重复计数；仅看到蓝牙 ON、授权成功或请求提交都不足以增加总数。

- 划掉卡片只隐藏该运行的通知，**不取消安全恢复、不减少累计，也不会被后续回调或冷进程恢复重新弹回**。
- 明细保留最近 **100 个已结束运行**，仍 pending/待恢复的记录受保护，不因上限清理被丢弃。累计是独立持久总数，不由现存卡片数推算，清理旧明细或划卡不减总数。
- Android/厂商可能限制、分组或驱逐通知；独立记录不等于保证无限张卡同时可见，也不保证每张通知一定送达。
- 记录和计数从 **1.1** 开始。合法的旧 1.0 空记录或 pending 升级到已知的 **0 起点**，明确披露「旧版历史未记录」，不追算 1.0 次数。旧 pending 继续安全恢复，但因没有完整的计数证据，不计成一次新重启。
- 已有 1.1 数据若缺失、部分损坏或累计不可验证，必须披露未知/损坏并保留安全限制，**不能静默重置为 0** 或编造旧历史。未知手动 native/scanner 隔离记录也不能用冷启动、超时、销毁、取消或硬件目标状态来解锁。

## 1.2 日志：miband.log、授权与有限保留

外部文件固定为**当前 Android 用户的主共享存储根目录**下 `miband.log`，通常为 **`/sdcard/miband.log`**；不是应用专属外部目录，也不是私有暂存文件。文件系统读写由**普通应用 UID** 的独立日志线程完成，不借 Shizuku、root、shell 或 `su` 写日志；开关蓝牙使用高权限不代表日志获得了存储权限。

### 所有文件访问与后台入口

- 仅在可见详情中主动点击「授权日志写入（所有文件访问）」才打开本包的系统特殊访问页面：`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，附本应用 `package:` URI；厂商缺少该页面时退回 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 的系统所有文件访问设置。页面打开失败会明确提示，打开页面本身不是授权成功。
- 返回详情重新检查 `Environment.isExternalStorageManager()`，由日志线程异步检查文件并导出暂存；详情只重绘内存中的 cached 日志状态，不在 Main 读写文件或通过状态重绘反复申请权限。授权并不自动重放手动按钮。
- fooView 的同进程热启动、普通图标、RUN、广播、服务和恢复入口**不会为日志自动打开详情或权限页**，未授权也不延迟已有蓝牙动作。拒绝、撤销授权或外部 I/O 失败时，明确说明外部日志不可写/未确认，尝试保留**最多 3MiB 的私有 pending 日志**；不是无限历史，也不是已经交付到根文件。私有读写也失败时披露可能丢失/未确认，不承诺一定暂存成功。
- 独立静默日志状态通知 **ID 105 / `bt_log_status`** 与原 100—104 运行通知及自动历史渠道隔离；只按状态变化去重更新，不每条 BLE 广告刷通知。点击直接进入真实详情，不操作蓝牙。通知和 Toast 均可能受系统/用户限制，详情可查看 cached 状态。
- 授权恢复后异步按原顺序导出仍保留的记录，保留原时间戳、`eventId` 和序号，不把导出时刻当事件发生时刻。

### 字节上限、裁剪和失败边界

- **3MiB = 3,145,728（3145728）字节，1MiB = 1,048,576（1048576）字节**，不是十进制 MB。根文件和私有 pending 都使用同一有界策略；恰好等于 3MiB 不裁剪，计划追加将超限时，先删除最老**至少 1MiB**，延伸到下一完整换行边界，再写入最新记录。因此可能多删一小段，仍保留最新完整 UTF-8 行；已有超大文件也有限读取/重复裁剪，不整文件装入内存。
- 单行结构化 UTF-8，名称、MAC、action 和原因中的换行/控制字符转义，超长内容按 UTF-8 字符边界截断并标记 `truncated`；单行最多 8KiB，不能靠超长广告绕过文件上限。
- 单一独立 writer，**内存队列最多 512 条；每批取出最多 64 条队列事件，另可附加至多 1 条 `log.dropped` 诊断记录**。事件时间在入队前捕获；文件 I/O、同步及裁剪在 Main 和蓝牙工作/清理 lane 之外。积压溢出保留较新事件并报告丢弃数/时间范围，不无限增加内存或阻塞蓝牙等待日志。强杀发生在 drain/落盘之前，尚在内存队列的事件可能丢失。
- 裁剪使用同目录独占创建的临时文件，有限完整尾部写入/sync 后原子替换，只清理本次 writer 创建的临时文件。异常或中途进程死亡可能留下临时文件；不把临时文件称为主日志，**不对外部应用篡改、强杀、断电或所有崩溃提供绝对保留保证**。
- 追加/同步失败可能已写入部分内容，半行不作为完整记录；外部写入成功而私有确认失败、崩溃或重试时，可能回放**相同 eventId**。日志**不是 exactly-once 审计事务**，重复行不是新的蓝牙开关次数；共享日志成功不能证明所有事件已永久保存。

### 记录事实、时间与隐私

每行有带**毫秒和时区**的 `time`/`receivedTime`，以及 `occurredAtMs`、`receivedAtMs`、`timeSource`、PID、主进程 session、递增 `sequence`、`eventId`、进程单调时间与 `kind`。关联字段按实际可用情况包含 `requestId`、`runId`、`operationId`、当前 `commandId`、scanner 身份、入口、目标、实际 mode、状态、执行结束证据和原因。后备新命令使用变化后的 commandId；缺失事实标为 null/未知，不编造 backend 或源端时间。

| 实际边界 | 日志含义与限制 |
|---|---|
| `process.start` 与每次入口 | 主应用进程通过 UID/进程 guard 后实际 Application 启动；另外记录 launcher/RUN、receivers、service、恢复的实际触发、接纳/复用/拒绝。同进程热启动不是新进程；MAIN 只标 launcher，没有可靠来源标识时不伪称识别到 fooView。专用特权进程初始化不冒充主应用 process.start。 |
| 自动/手动 BLE 扫描 | 请求、准备、确切注册、真实 startScan 进入/返回、有效实际广告名称/MAC/RSSI、目标判定、结果、取消、retirement 请求及实际确认/失败。广告的源端采样单调时间、callback receipt、Main delivery 分开；手动广告仍受原 8 秒准入，最多显示 100 个目标/其它 BLE 设备，目标仅展示。 |
| 开关与恢复 | 每次能力/preflight 和实际 Shizuku shell/root/普通路径、普通 API 或应用侧 Binder RPC 进入/返回/提交、实际状态样本、native authority 结束/未知、checked 终态/保存结果；RPC 进入**不等于**已确认私有 svc 子进程创建，svc spawn 时间未知，不声称捕获了其它系统进程启动。 |

有真实源端 `enteredAt`/`returnedAt`/`sampledAt` 时保留它们，并与收到回调/记录事件的时间区别；没有时只说明 receipt，不能用当前时间补成未知的源端执行时刻。单调时间只用于可关联的进程内顺序/耗时，不跨进程编造持续时间。状态采样是**观察到状态的时刻**，不是精确硬件切换瞬间；取消、超时、窗口结束、stopScan 请求或目标状态都不是底层执行已经结束的证明。

日志包含附近设备的**名称/MAC** 与本机运行诊断，获得共享存储访问的其它应用/用户可能读取，分享文件前应考虑隐私。日志仅本地保存，**不上传**，不记录口令、私钥、授权 token、任意 Intent extras 或其它文件内容。日志/Toast 只是异常隔离的观察输出：成功或失败都**不驱动 checked 历史事务、累计计数或 native/scanner fence**，不改变准入、恢复或安全清理。

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

### 已验证的 1.0 历史交付

**以下原 1.0 文件丢失发生于当时的 1.1 release 构建；本节的「本次」指那次 1.1 交付，不是正在实施的 1.2。原文件保留失败的历史结论不变。**

原 **`bt-monitor-nogui.apk`** 是已交付的后台 **1.0/code 1**，不是当前 1.2 产物。以下为原文件在本次构建前实测的历史数据。**本次 release 打包清理了构建输出目录，旧命名文件缺失；可访问的公开产物/缓存目录中未找到备份，原文件保存这项未完成，也无法再做本次原文件并排重验。** 历史路径：

```text
app/build/outputs/apk/release/bt-monitor-nogui.apk
```

2026-11-25 交付文件：2339669 字节，versionCode1/versionName1.0。

```text
APK SHA-256: 999119f1a9fee387f52d99df437215a421cf5800a89059cd07f52fa048d2e518
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

以上 **1.0 历史交付**的最终源码曾两次完整重跑：181 项 JVM 测试，零失败/错误/跳过，debug/release 构建成功；证书有效且重复构建稳定，独立规格、质量及最终整体审查通过。这不是 1.1 的最终测试总数或交付验收。

### 已验证的 1.1 产物与验收边界

**本节只记录实际 1.1 历史交付，不是 1.2 的最终测试/签名/真机验收。**

2026-11-26 的新交付文件 **`bt-monitor-nogui-1.1.apk`** 已复制并重新核验：

```text
app/build/outputs/apk/release/bt-monitor-nogui-1.1.apk
包名: com.blreay.bluetoothmonitor.nogui
版本: 1.1 / versionCode 2
大小: 2542237 字节
APK SHA-256: 2e429826fd80af4bcecc17fa142ca81eb39c9a57ddefa353f6a0fde19eff7960
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

实际 1.1 持久升级输入为 `/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.1.apk`；1.2 构建前另已核验同字节备份 `/tmp/btreset-1.2-baseline-793it9ek/original-delivered-1.1.apk`，均为上述 2542237 字节与原 hash。**AGP 可清理 APK 输出目录，今后构建前应先把需要保留的交付文件复制到目录之外。** 新文件与原1.0构建前实测的包名/证书一致，code1→2，符合覆盖升级身份条件；但旧文件已丢失，不能声称本次仍完成了原文件并排重验或原文件保留门。

父会话对最终功能源码两次完整强制重跑 test/debug/release/lint：**22 个 suite、295 项 JVM 测试，零失败/错误/跳过**，两次 release 签名均为同一原证书，APK字节不要求可复现。规格、质量和最终整体源码审查均通过；实际APK核验包括完整launcher、NoDisplay、内部receiver及已编译manual/history组件。这些结论不把旧文件保留失败改写为通过。

Lint 保持 **13 项：12 Warning、1 Fatal `ExpiredTargetSdkVersion`；MissingPermission 0、Error 0**，不是 lint-clean；现有 3 处通知 setter 弃用编译警告仍保留。本次 `adb devices -l` 为空：安装升级、详情焦点/真实窗口、Shizuku/OEM 交互、四次真实自动 OFF→ON、划卡及重建窗口等真机验证均未执行，**没有声称真机已通过**。

已有 `main` 的 `latest/bt-reset.apk` 仍是原滚动产物，**不是后台新版**；功能分支 push 不自动发布它。本任务不合并 main 或手动触发发布工作流。

### 已验证的 1.2 产物与验收边界

2026-11-27，最终第二份 release 已复制并逐份重新核验：

```text
持久文件: /home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.2.apk
输出副本: app/build/outputs/apk/release/bt-monitor-nogui-1.2.apk
包名: com.blreay.bluetoothmonitor.nogui
版本: 1.2 / versionCode 3
大小: 2652533 字节
APK SHA-256: 60cfa2fffcc65ae6ad1197855a27e02dde23e1b22fd79211f65872bc0f3d497d
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

父会话对最终功能源码两次完整强制重跑 test/debug/release/lint，分别20秒和19秒、每次97任务全执行；每次 **28 suite、403项 JVM 测试，零失败/错误/跳过**。第一份签名APK已先保存到构建目录外，第二次构建后仍核验原字节；两份均使用原7825cc证书，APK字节不要求可复现。独立规格、质量和最终整体源码审查通过，没有剩余审查问题。缺签名配置时release明确拒绝的测试也独立验证，不能把该expected拒绝当成成功打包。

实际保留的原1.1/code2 APK与额外备份仍为原2542237字节/hash，未以重编旧版冒充保留；新旧实际包名和单一证书相同，code2→3，**符合覆盖升级身份条件**。实际APK检查包括完整NoDisplay launcher、RUN/RESET_IF_PRESENT、内部Activity/service/receiver、connectedDevice FGS、Shizuku provider、MANAGE_EXTERNAL_STORAGE、无WRITE/INTERNET/boot，以及已编译日志/feedback/runtime类。原1.1输出副本也从实际原文件恢复并重验。这不改写历史1.0文件遗失。

Lint实测 **14项：13 Warning、1 Fatal `ExpiredTargetSdkVersion`，MissingPermission0/Error0**，含未抑制的新增`ScopedStorage`，**不是lint-clean**；三个原通知setter弃用编译warning仍保留。所有文件访问有Android/厂商设置和Google Play分发政策限制，本任务是独立APK，不申请Play发布。

最终`adb devices -l`为空。**真机覆盖安装和数据/权限保留、日志设置授权/拒绝/撤销/恢复、真实根文件写入/裁剪、fooView重复热启动、Toast显示、BLE、Shizuku/普通后备与OEM交互、通知历史/累计现场验收均未执行**。JVM/source契约、临时目录存储测试和APK元数据验证不替代真机步骤。

### 构建与私密签名

JDK17、SDK platform34/build-tools34.0.0：

```bash
./gradlew testReleaseUnitTest assembleDebug
# debug：app/build/outputs/apk/debug/app-debug.apk
```

release 必须使用已交付后台版的同一持久私密签名，仓库外配置路径保持不变，默认：

```text
~/.local/share/bluetooth-monitor-nogui/signing.properties
```

文件包含 `BT_NOGUI_STORE_FILE`、`BT_NOGUI_STORE_PASSWORD`、`BT_NOGUI_KEY_ALIAS`、`BT_NOGUI_KEY_PASSWORD`，指向持久 JKS。限制文件访问并备份；**不要把私钥/口令提交 git**。release 打包的现有 `validateNoguiReleaseSigning` 守卫除校验完整私密配置和可用私钥外，还固定校验证书 SHA-256 为 `7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`，任何其它证书都拒绝；退役证书 `2ebfee29e52a9867240060922a62e1c32bdf3ee587911a657082118235292dad` 的禁止规则仍保留。缺失/无效配置明确失败，不退回 `BT_RESET_*`、旧公开密钥或 debug key。测试/debug 不依赖 release 配置。换机器构建要恢复同一密钥，不能每次生成新证书。

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

源码 `versionName=1.2`，`autoVersionCode=(ciRunNumber ?: 0)+3`：本地默认 **code 3**，CI 使用 **`GITHUB_RUN_NUMBER+3`**，保留原有 CI 递增约定。交付核验后可升级同包同证书的实际后台 **1.1/code 2**（也保持 1.0/code 1 的身份兼容条件）；如果已安装的是更高 code 的 CI 产物，应使用不低于该 code 的同签名构建，而非用本地 code 3 降级。旧 GUI 独立包的版本号不影响本包。

设计与实际验证记录：

- [1.2开关消息与滚动日志设计](docs/superpowers/specs/2026-11-26-miband-log-design.md)
- [1.2实施、验证与交付记录](docs/superpowers/plans/2026-11-26-miband-log.md)
- [1.1详情控制与累计历史设计](docs/superpowers/specs/2026-11-25-details-controls-history-design.md)
- [1.1实施、验证与交付记录](docs/superpowers/plans/2026-11-25-details-controls-history.md)
- [后台版设计](docs/superpowers/specs/2026-11-25-headless-shizuku-reset-design.md)
- [实施与验证记录](docs/superpowers/plans/2026-11-25-headless-shizuku-reset.md)
- [已验收的手动页面设计](docs/superpowers/specs/2026-11-24-manual-shizuku-control-design.md)

JVM 测试和 APK 构建不替代真机验证。Shizuku/OEM确认框、无焦点窗口、实际OFF→ON和进程死亡后的恢复，需在实际测试手机上验证；最终交付记录明确哪些执行、哪些未执行。
