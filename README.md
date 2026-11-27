# 蓝牙手环重启 · 后台 Shizuku 优先版

本分支 `bluetooth_monitor_shizuku_nogui` 的当前源码版本为 **1.4**：在通知详情中显式保存自动监控来源、分钟间隔与完整广播目标名；启用后可按定时、亮屏、灭屏独立组合触发真实 BLE 检测，**找到目标才走原有安全 OFF→ON 重启，不是无条件周期切换蓝牙**。沿用 1.3 的三类秒级轻量纯文本 `miband.log`、横排按钮和手动 SCAN 瞬时摘要，保留独立自动历史、已验证累计计数及安全恢复。**日常图标、RUN 与广播入口仍为 NoDisplay：没有新首页，也不显示透明占位窗口；必要首次权限设置是例外，只有主动点击通知才进入真正的不透明详情。**

1.4 保持后台版 1.0—1.3 的包名与单一证书签名守卫，本地 code 5 用于升级实际已交付的 **1.3/code 4**。**1.4 正式签名 APK、实际大小/hash、证书及并排升级身份核验尚待后续交付，不把源码版本变更当成已交付；1.4 真机验证未执行。** 已交付的 1.1/1.2/1.3 原文件与核验数据见历史章节，不改写历史 APK 事实。旧版可见四按钮 GUI 工具仍在 `bluetooth_monitor_shizuku` 分支，包名/签名与后台版独立，可以分别安装。本版本不是给那个页面增加隐藏开关，也不修改 fooView 应用列表识别行为。

## 使用方式

1. 当前已核验交付文件为 **`bt-monitor-nogui-1.3.apk`**（持久路径见历史章节），它不是 1.4。1.4 后续正式 APK 须实际核验包名 **`com.blreay.bluetoothmonitor.nogui`**、原单一证书与 code 4→5，才符合对后台 1.3 覆盖升级的身份条件；不应靠卸载或清数据绕过身份核验。真机覆盖与数据/权限保留尚未执行 1.4 验收。旧 GUI 版是另一安装，不需卸载；更高 code 的 CI 安装不能用本地 code 5 降级。
2. 点击后台版图标。首次必要时允许「附近的设备」权限；允许通知，才能看到运行模式、结果并进入详情设置。
3. 应用启动即检测 Shizuku。用户已具备可用 Shizuku 时，已授权则直接检查/连接；首次未授权会自动申请，在 Shizuku 对话框中允许。**没有每次都要点的授权按钮**；本应用不能替用户启动或配对 Shizuku。
4. 图标/RUN 仍保留一次 BLE 检测入口。当前包的完整广播名精确命中本次冻结的目标后才关闭手机蓝牙，再等待实际 OFF 后请求恢复 ON；初始目标为 `Xiaomi Smart Band 8 35BA`，可在详情显式保存新目标。没有找到就不切换。
5. 运行和恢复通过静默通知说明。**只有主动点击通知才打开真正的不透明详情窗口**，可查看本次/所选自动历史与累计，设置自动监控，并操作同一等宽横行中的「打开蓝牙」「关闭蓝牙」「扫描蓝牙」；第二行是系统蓝牙设置与应用权限/通知设置。窄屏文字允许换行。ON/OFF 与 SCAN 均使用瞬时消息，不显示持久开关结果区、手动扫描设备卡或日志说明页；目标下拉候选只是设置输入辅助。首次权限设置及显式系统设置仍是必要例外，不是日常首页。
6. 如需写根目录日志，在可见详情中主动点击「日志权限」，在系统所有文件访问设置中允许本应用，再返回详情；这是独立的日志特殊访问授权，不是 Shizuku 或附近设备授权。日常 fooView/图标/RUN/广播入口不会隐式授权或自动打开此权限页面，拒绝授权也不阻塞原有蓝牙流程。

后台版的数据、附近设备权限和 Shizuku 授权与旧安装独立，需单独完成首次授权。第一次的系统权限/Shizuku授权可能需要参与，普通后备也允许必要的系统确认；这些是例外，不是常规启动页面。拒绝附近设备权限时，不进行扫描或假称已经重启。

自动监控默认关闭；不启用时保留**一次启动、一次检测**，启用后才按所选来源持续尝试检测。没有开机自动运行。它重启的是手机整个蓝牙无线电，可能暂时断开耳机等其它设备；不直接配对、连接或重置手环，重连由 Mi Fitness 等拥有连接的应用自行完成。

## 1.4 自动监控设置、目标与后台边界

### 显式保存，不把设置操作当扫描

- 在通知详情中编辑「自动监控」总开关、「定时」「亮屏」「灭屏」、整数分钟间隔和完整目标名，再点击 **「保存自动设置」**。初始值为 **总开关 OFF、定时 ON、亮屏 OFF、灭屏 OFF、3 分钟**；定时被勾选不代表总开关已经启用。`TIMER` / `SCREEN_ON` / `SCREEN_OFF` 相互独立，可任意选择一种、两种或三种；总开关 ON 时至少选一种，不必同时选择亮/灭屏。
- 来源选择、原始目标文本和 **1—1440 的整数分钟**间隔持久保存。取消定时仅禁用间隔编辑，保留最后有效间隔；只用屏幕来源仍保留它。保存确认不代表宿主启动或 alarm 安排成功，请分别查看详情状态。
- **保存不立即扫描**；定时安排下一间隔，不补扫漏掉的周期。未修改的保存不制造新触发、不重排健康周期；打开详情、刷新候选或重建窗口也不是设置变更。显式可见入口可以尝试启动/恢复监控宿主，但仍不把「保存」变成一次扫描。
- 保存失败或版本冲突保留草稿、光标、基准版本与最后确认的设置，不用未确认的偏好缓存冒充保存成功，也不自动覆盖另一版本。冷启动读到损坏/不可验证配置时 **fail-closed 暂停监控**，披露错误，不伪装成默认值；真正首次没有配置才使用上述默认值。

### 只用当前 BLE 包名匹配；下拉不是设备身份锁定

- 可直接输入完整广播名，也可先点「扫描蓝牙」做一次真实手动 BLE 扫描，从目标下拉选择。下拉只来自**本进程最近一次被接纳的真实手动扫描**的当前候选：新扫描开始替换旧候选、扫描中随当前结果更新、结束后保留本次结果，最多 **100 个不同地址**，超限摘要为 `100+`。冷进程的旧持久结果不注入成新候选；拒绝的按钮不会伪造新扫描或清掉已确认的候选。
- 候选可显示名称与 MAC 便于观察，但选择只写入 **原始广播名**，不写 MAC 或显示后缀。原始名称为 null/空白时显示的「无广播名（不可选择）」仅是占位，不能作为目标；如果当前广告的真实原始名称就是 **`未知设备`**，它仍可选择，并按该完整文本精确匹配，不因看起来像占位而排除。相同名称的多个候选仍可能命中多个物理设备，**名称不唯一识别一只手环**。不会因 adapter 更新把占位、后缀或另一个名称覆盖输入草稿。
- 匹配使用 **当前 `ScanRecord` 包的完整名称**，大小写及前后/内部空格都显著；不 trim、不忽略大小写、不追加 MAC，也不使用已配对列表、`BluetoothDevice.name` 或缓存/旧广告名兜底。每次被接纳的工作冻结其设置版本和目标，后续编辑/保存不改变进行中的目标。
- 手动 SCAN 只观察和给瞬时摘要，**不触发 OFF→ON、不增加自动重启计数**，也不为扫描偷偷开启蓝牙。自动扫描使用目标过滤与当前包校验，其「设备数」仅是本次接纳的过滤后不同地址数，**不是周围全部设备数**；不得与手动发现数量等同。

### 调度与锁屏只是尽力，不承诺严格三分钟

- 三种来源共用 **30 秒 accepted-only 防抖**：只有实际接纳工作才开始冷却；忙碌、冷却中或未获接纳的事件跳过，不延迟排队、不在空闲后重放。周期到达时仍先持久消费/轮换票据并安排下一周期，再决定本次是否扫描，忙碌或冷却不会冻结健康定时器。
- 普通自主调度沿用下文 **Shizuku-first 的高权限 ON/OFF**，用户已可使用的 Shizuku 不因增加监控而被降成固定普通开关；只有原驱动允许的狭窄安全条件才走普通后备。所有来源仍须真实扫描命中与原准入/恢复检查，**绝不无条件定时关闭/开启蓝牙**。
- 空闲监控由真正的 **`connectedDevice` 前台服务（FGS）**承载；定时使用 allow-while-idle alarm。只有被接纳任务持有有限 `PARTIAL_WAKE_LOCK`：初始工作片 **30 秒**，其中包含真实自动 BLE 扫描的 **8 秒观察窗口**，不是额外再加 8 秒；该工作片在真实宿主接管且任务获接纳后开始，**不包含冷宿主启动，也不等待人工 Shizuku 授权**，仅做原有有界准备。安全恢复片为 **15 秒**；无空闲/永久 WakeLock，也不靠持续持锁保活。
- 亮/灭屏监听动态注册，必须有**仍存活且已验证的宿主**；屏幕边沿只借用该宿主，不凭广播自行启动新 FGS。精确 alarm 当前投递可尝试冷启动真实宿主，但系统可能拒绝，接管未成功不算接纳。**精确闹钟系统设置、附近设备权限、显式 Shizuku 重试、电池优化系统设置**是相互独立的显式入口；打开一个页面不授予其它能力。
- 无精确闹钟权限时如实降级为 **inexact allow-while-idle**，不是精确调度。无存活宿主时非精确周期不扫描、不借它绕过后台 FGS 限制，需要显式打开详情/图标恢复宿主。锁屏尽力执行，但深度 Doze 配额、厂商后台限制、强停、权限撤销与系统启动拒绝都可能延迟或阻止检测，**不能保证严格每 3 分钟或每个屏幕边沿都执行**。
- 宿主为 **`START_NOT_STICKY`**，无 boot receiver；屏幕-only 模式进程/宿主死亡后没有周期入口兜底，需要显式重开。系统强停或重启也不承诺自恢复，电池/自启动设置不等于新增开机启动。
- 被阻塞的 scanner retirement 恢复交接有一次有限 **15 秒**逻辑期限，不随重试/再阻塞续期；到期仅结束该子任务的 **hosting**，不宣称 scanner/native 权威已结束、不清隔离或 pending。它由 Handler 投递，设备睡眠时**不保证在 15 个墙钟秒内执行回调**；安全 OFF→ON 已继续执行时也不能被旧交接期限截断。

### 关闭监控不取消已经进入的安全工作

关闭总开关并保存会阻止 **新 child / 尚未进入的新扫描**，仅取消监控周期 alarm 和屏幕监听；不是取消所有 alarm 或清理安全记录。已接纳且已经进入扫描的工作和原安全 OFF→ON 恢复仍按 **原冻结目标**继续。原 pending、恢复 alarm、native 权威、scanner retirement、隔离及历史/计数均保留；仅有接纳但尚未进入扫描的许可会被撤销。设置写入故障也不能清除这些安全 fence，必要时暂停新监控而保留最后确认状态与既有恢复。

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

## 通知详情中的手动控制（沿用 1.3，保留原安全约束）

详情窗口由通知直接打开，不会由后台服务抢前台，也不会因日常图标/RUN/广播入口而成为首页。「打开蓝牙」「关闭蓝牙」「扫描蓝牙」位于同一等宽横行，系统蓝牙设置与应用权限/通知设置位于第二行，窄屏按钮文字可以换行。**不恢复 ON/OFF 持久结果文字区，也不显示 SCAN 设备卡或日志说明卡**；「日志权限」仍是独立显式入口。当前自动状态、所选完整 runId 的历史明细、累计、权限和恢复入口仍独立保留，不用最新结果冒充旧卡片。原持久手动结果、设备事实和未知执行隔离记录仍在私有安全存储中，不因移除展示而删除。

- **打开蓝牙 / 关闭蓝牙：** Shizuku 优先、普通路径后备，由实际进行中的请求发送普通文字 Toast：接纳/等待、拒绝、实际终态、保存失败或结果未知各自说明关键原因；已知实际路径时带 **Shizuku shell / Shizuku root / 普通模式**。接纳时 backend 为 null 不编造路径，`NONE` 只表示「尚未切换、未调用开关」，不伪称高权限执行。「已接纳」或 `SUBMITTED`/「请求已提交」**不是成功**；只有可靠的实际目标状态、执行结束证据与 checked 保存结果才能支持成功终态，no-op 也不是重启成功。手动关闭完成后保持 OFF，**不创建自动 ON、自动恢复任务或自动计数**；除非用户另行明确打开或其它应用改变蓝牙状态。
- **瞬时而非历史回放：** 同操作同类反馈去重，独立拒绝点击各自提示；未知之后真正结束可以有新反馈。消息不从持久 snapshot、旧卡片或窗口 render 重构，打开/重建详情不会重播旧 Toast。Toast 受 Android/OEM 限制，尽力发送而不保证送达；原安全记录仍保留完整原因与时间，轻量日志仅保留结论，不是详细事件流。
- **冲突拒绝：** 自动运行、待恢复、手动开关或扫描正在占用资源时，不接纳冲突请求，不排队、不抢占、不偷走旧任务所有权。拒绝原因通过本次瞬时消息反馈，不替换仍进行中的持久结果；权限补齐后也不自动重放旧按钮。
- **扫描蓝牙：** 仅进行一次 **8 秒真实 BLE 广告扫描**；只对当前操作提供瞬时、紧凑的完成/拒绝/未确认摘要，包含设备数、是否见到目标和必要结果，不逐广告提示、不回放 snapshot 或重建窗口前的结果。摘要正常显示计数，超过原 **100 个**保留上限显示 `100+`；设备数/目标无法确认时标为未知，未执行、失败/取消的部分结果或清理未确认不伪称完成。底层原有界设备事实保留，但不再展示持久设备列表。它不是已配对设备列表或经典蓝牙发现列表。目标仅用于这次手动扫描的摘要，**不自动开启蓝牙、不配对、不连接、不重置**；蓝牙为 OFF 时不会为了扫描偷偷打开。
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

## 沿用 1.3 轻量日志：miband.log、授权与有限保留

外部文件仍固定为**当前 Android 用户的主共享存储根目录**下 `miband.log`，通常为 **`/sdcard/miband.log`**；不是应用专属外部目录，也不是私有暂存文件。文件系统读写由**普通应用 UID** 的独立日志线程完成，不借 Shizuku、root、shell 或 `su` 写日志；开关蓝牙使用高权限不代表日志获得了存储权限。

### 仅三类结论与本地秒级时间

新记录是本地时间 `yyyy-MM-dd HH:mm:ss`、一个空格和简短结论，末尾换行；没有 JSON、毫秒/时区字段、PID/入口/回执字段、新事件身份或设备列表。例如：

```text
2026-11-27 08:20:01 进程启动
2026-11-27 08:20:09 扫描：5个设备，目标手环：有
2026-11-27 08:20:10 关闭蓝牙：Shizuku，成功
2026-11-27 08:20:12 打开蓝牙：普通，成功
```

- **进程启动：** 只在主应用进程通过 UID/进程 guard 后实际 Application 启动时记录；Shizuku 特权进程不冒充主进程。fooView、图标或 RUN 的同 PID 热启动不是新进程，不另写「进程启动」；也不伪称识别了 fooView 来源。
- **扫描结论：** 自动/手动扫描只输出设备数与目标「有/无/未知」摘要，不逐广告写名称/MAC/RSSI，也不记录注册、授权、清理或状态轮询的详细事件流。设备数是本次扫描接纳的不同设备地址数量，同地址的重复广告不增加计数。**自动扫描只计目标过滤且通过当前包名校验的地址，不是周围全部设备**，不新增手动的 100 个上限；手动扫描观察当前发现结果，保留最多 100 个设备，超出后摘要为 `100+个设备`。正常结论不额外附「成功」。目标「有」表示本次扫描**曾观察到**本次冻结的目标；即使目标出现在手动 100 个保留上限之后，或同地址后续广告名称改变，仍保持「有」，不随最终设备列表改回「无」，但旧名不用于命中后续广告。设备数未确认为「设备数未知」，未开始不编造 0；失败、取消或执行/清理未确认时附「未执行」「失败（部分结果）」「取消（部分结果）」等真实结论。部分观察的「无」不证明周围绝无目标，扫描正常结束亦不等于完成蓝牙重启。
- **开关结论：** 只写打开/关闭、实际结果路径和简短结果；Shizuku shell/root 在轻量文件中统一显示「Shizuku」，普通为「普通」。依据本次实际 outcome，而不是当前 capability/授权状态；`NONE` 显示「未调用」，null 显示「方式未知」，不填成高权限。`SUBMITTED`/请求提交不是成功；未执行、目标未确认、执行/清理未知、保存未确认与实际成功各自如实说明。no-op 可显示「已是目标状态，未调用」，不是一次重启；未知后确切结束可以产生同类后续结论。

时间在日志入队前捕获，表示记录结论的本地时间，不是精确硬件切换时刻或未知的源端采样时间；不会用导出时刻替换原时间。轻量日志不是完整安全明细，原自动历史、累计、手动结果与设备事实仍由独立私有安全存储保留。

### 所有文件访问与后台入口

- 仅在可见详情中主动点击短标签「日志权限」才打开本包的系统特殊访问页面：`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，附本应用 `package:` URI；厂商缺少该页面时退回 `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION` 的系统所有文件访问设置。页面打开失败会明确提示，打开页面本身不是授权成功。
- 返回详情的 `onResume` 请求异步刷新，由日志线程重新检查 `Environment.isExternalStorageManager()`、文件可写性并导出暂存；每次实际写入仍检查所有文件访问。详情不显示日志说明卡，不在 Main 读写文件或通过重绘反复申请权限；cached 状态由独立日志状态通知说明。授权并不自动重放手动按钮。
- fooView 的同进程热启动、普通图标、RUN、广播、服务和恢复入口**不会为日志自动打开详情或权限页，也不隐式授予权限**，未授权不延迟已有蓝牙动作。拒绝、撤销授权或外部 I/O 失败时，明确说明外部日志不可写/未确认，尝试保留**最多 3MiB 的私有 pending 日志**；不是无限历史，也不是已经交付到根文件。私有读写也失败时披露可能丢失/未确认，不承诺一定暂存成功。
- 独立静默日志状态通知 **ID 105 / `bt_log_status`** 与原 100—104 运行通知及自动历史渠道隔离；只按存储状态变化去重更新，不每条广告或新记录刷通知。点击直接进入真实详情，不操作蓝牙。通知和 Toast 均可能受系统/用户限制，不保证送达。
- 授权恢复后异步按原顺序重放仍保留的**原始完整行**，既包括旧 1.2 行，也包括新轻量行，不重新格式化或替换时间；旧字段仅存在于原样保留的旧行。**不因升级转换旧日志、清空根文件/pending 或创建另名滚动分卷**；正常有界裁剪与成功导出后的私有确认机制不变。

### 字节上限、裁剪和失败边界

- **3MiB = 3,145,728（3145728）字节，1MiB = 1,048,576（1048576）字节**，不是十进制 MB。根文件和私有 pending 都使用原有界策略；恰好等于 3MiB 且无新超限追加时不裁剪，计划追加将超限时，先删除最老**至少 1MiB**，延伸到下一完整换行边界，再写入最新记录。因此可能多删一小段，仍保留最新完整 UTF-8 行；已有超大文件也有限读取/裁剪，不整文件装入内存。
- 新行**最多 512 字节 UTF-8，包含末尾换行**；结论中的控制字符替换为空格，超长结果按 UTF-8 字符边界有界截断，不拆字符。存储兼容预算仍允许**最多 8192 字节（8KiB）**的旧完整行原样重放；这不是新日志的行长预算。
- 单一异步 writer，**生产内存队列最多 64 条，每批最多 16 条**；不追加 `log.dropped` 或其它诊断行，丢弃计数留在内存状态中。积压溢出丢最老行、保留较新行，落盘/调度失败可能丢失或保留有限队列待下次刷新；不无限增加内存或阻塞蓝牙等待日志。文件 I/O、同步及裁剪在 Main 和蓝牙工作/清理 lane 之外。强杀发生在 drain/落盘之前，尚在内存队列的记录可能丢失。
- 裁剪使用同目录独占创建的临时文件，有限完整尾部写入/sync 后原子替换，只清理本次 writer 创建的临时文件，不改成多份滚动日志。异常或中途进程死亡可能留下临时文件；不把临时文件称为主日志，**不对外部应用篡改、强杀、断电或所有崩溃提供绝对保留保证**。
- 追加/同步失败可能已写入部分内容，半行不作为完整记录。仅在共享根文件追加并同步成功后确认私有导出，使用原子替换而非就地清空；私有 ACK 失败明确披露未确认。根写成功而 ACK 失败、崩溃或重试时，可能重放相同原始行。日志**不是 exactly-once 审计事务**，重复行不是新的蓝牙开关次数；共享日志成功不能证明所有事件已永久保存。读取、写入、授权检查与确认均保留失败/不确定状态，不把 I/O 失败包装成蓝牙成功。

### 隐私与安全分离

新轻量行不包含附近设备名称/MAC；已保留或重放的旧日志可能仍有这些信息。获得共享存储访问的其它应用/用户可能读取，分享前应检查旧内容。日志仅本地保存，**不上传**，不记录口令、私钥、授权 token、任意 Intent extras 或其它文件内容。

日志/Toast 只是异常隔离的观察输出：成功或失败都**不驱动 checked 历史事务、累计计数或 native/scanner fence**，不改变原 durable 安全元数据、定时器、存储协议、准入、恢复或安全清理。取消、超时、窗口结束、stopScan 请求或目标状态仍不能证明底层执行已经结束。

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

**以下原 1.0 文件丢失发生于当时的 1.1 release 构建；本节的「本次」指那次 1.1 交付，不是当前 1.4 开发。原文件保留失败的历史结论不变。**

原 **`bt-monitor-nogui.apk`** 是已交付的后台 **1.0/code 1**，不是当前 1.4 产物。以下为原文件在本次构建前实测的历史数据。**本次 release 打包清理了构建输出目录，旧命名文件缺失；可访问的公开产物/缓存目录中未找到备份，原文件保存这项未完成，也无法再做本次原文件并排重验。** 历史路径：

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

**本节只记录实际 1.1 历史交付，不是当前 1.4 的最终测试/签名/真机验收。**

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

### 已验证的 1.2 历史产物与验收边界

**本节只记录实际 1.2 历史交付，不是当前 1.4 的最终测试/签名/真机验收。**

2026-11-27，1.2 最终第二份 release 已复制并逐份重新核验：

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

### 已验证的 1.3 历史产物与验收边界

**本节只记录实际 1.3 历史交付，不是当前 1.4 的最终测试/签名/真机验收。**

2026-11-27，最终第二份签名 release 已复制到以下两处，并逐份重新核验实际 APK：

```text
持久文件: /home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.3.apk
输出副本: app/build/outputs/apk/release/bt-monitor-nogui-1.3.apk
包名: com.blreay.bluetoothmonitor.nogui
版本: 1.3 / versionCode 4
大小: 2621153 字节
APK SHA-256: 208170e2dc9567f48c0f539587dc59d0fe857e02d7e85134b764929ac5dee16b
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

父会话在最终整体源码审查通过后，两次完整强制重跑 test/debug/release/lint：每次17秒、97任务全部执行，均为 **28 suite、424项 JVM 测试，零失败/错误/跳过**。冻结契约覆盖88个生产/测试/资源/build/CI路径及全部424个准确测试名称，不只检查总数。两次签名均为同一原7825cc证书；第一份APK先独占保存到构建目录外并在第二次构建后重验，hash为`0ec4d1afdbb955ca0ebf69a5eb5fae0a36c3854051926b08eb238fdf86dabb5d`，APK字节不要求可复现。两项任务的规格、质量及最终整体源码审查均通过；缺签名配置时release明确拒绝的独立验证不冒充成功打包。

实际原1.2/code3持久文件与额外备份仍是上述2652533字节/原60cfa2 hash；原1.1/code2也保持原字节/hash，两份旧输出副本仅从实际原文件恢复，没有重编旧版冒充保留。实际新旧包名和单一证书一致、code3→4，**已核验符合覆盖升级身份条件**，不是已经在手机上完成升级。

实际APK核验包括完整NoDisplay launcher、RUN/RESET_IF_PRESENT、内部Activity/service/receiver、connectedDevice FGS、Shizuku provider、SDK31/32/34、MANAGE_EXTERNAL_STORAGE、无WRITE/INTERNET/boot，以及28个必需DEX类的真实定义；新typed/summary/feedback/runtime组件存在，退役`AuditRow`和`LogDropRange`定义不存在。日志与反馈仍不替代原安全权威和计数事务。

Lint实测仍为 **14项：13 Warning、1 Fatal `ExpiredTargetSdkVersion`，MissingPermission0/Error0**（包括`ScopedStorage`），不是lint-clean；三个原通知setter弃用及`ManualOperationController`冗余initializer编译warning如实保留。

本次`adb devices -l`为空。**1.3 真机覆盖升级与数据/权限保留、真实 BLE、Toast 送达、窄屏/大字体按钮布局、日志权限授权/撤销/恢复与根文件写入/裁剪、fooView热启动、Shizuku/普通后备及 OEM 交互、通知历史/累计均未执行现场验收。** JVM/source契约、临时目录存储测试及签名APK检查不替代这些步骤。

### 1.4 实际交付核验（2026-11-27）

最终交付采用第二次正式构建的实际 APK：

```text
持久文件: /home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.4.apk
输出副本: app/build/outputs/apk/release/bt-monitor-nogui-1.4.apk
包名: com.blreay.bluetoothmonitor.nogui
版本: 1.4 / versionCode 5
大小: 2768133 字节
APK SHA-256: 49caf1f4e5991ba6c1ab69b14dc7e57bcea50c236906a0952d346c994c726ea3
证书 SHA-256: 7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a
```

各任务规格、质量及最终整体源码审查通过后，父会话两次完整强制执行 `testReleaseUnitTest assembleDebug assembleRelease lintDebug`：分别 **21秒、22秒，均97任务全部执行、43 suite / 610项测试零失败/错误/跳过**。最终契约冻结111个编译源码/测试/资源/build/CI/wrapper路径与610个准确case名称，原424名称及Task1六文件保留。第一份先独占保存构建目录外，APK hash为`158f7ff89c1c6b01c63ef240f3b17835c80eab41dbeb4b66591b7c90505a725b`；两次均使用原单一7825cc证书并通过v2签名核验，APK字节不要求可复现。正式签名材料只由构建进程内部读取，未换密钥。

实际 `aapt` / `apksigner` / DEX定义核验包括1.4/code5、SDK31/32/34、NoDisplay launcher与原RUN/广播入口、non-exported `MonitorService` / `MonitorAlarmReceiver`、两项 `connectedDevice` 服务、WAKE_LOCK与原Shizuku provider权限；无boot/INTERNET/WRITE_EXTERNAL_STORAGE或manifest屏幕receiver。实际1个DEX、1725个类定义，71个必需类定义存在，退役`AuditRow` / `LogDropRange`不存在。两份交付副本复制后逐份重验metadata/字节/hash/证书，原1.1/1.2/1.3持久文件、真实备份和旧输出副本保持原hash，没有重编旧版冒充保留。

**已核验与原1.3/code4同包名、同单一证书、code4→5，符合覆盖升级身份条件；不是已经在手机上完成升级。** 本次`adb devices -l`为空：**1.4安装升级/数据与权限保留、设置持久化与下拉现场交互、真实BLE/锁屏/深度Doze、屏幕触发、短锁能耗、停止监控保留恢复、Shizuku/普通后备/OEM交互和通知历史/累计均未做真机验证**。JVM/source/APK核验不替代这些步骤，也不保证锁屏严格三分钟。

Lint仍为 **14项：13 Warning、1 Fatal `ExpiredTargetSdkVersion`，MissingPermission0/Error0**，不是lint-clean。缺签名配置实际在`validateNoguiReleaseSigning`拒绝且`packageRelease`未执行，这是独立fail-closed验证。现有CI仅监听`main`的push及显式workflow_dispatch；本轮不改main/CI、不触发发布workflow或替换rolling latest，不以本地两次构建冒称CI通过。

### 构建与私密签名

保持 JDK17、Kotlin1.9.24、AGP8.4.0、Gradle8.7、SDK platform34/build-tools34.0.0 与 Shizuku13.1.5，不为版本升级变更生产依赖或其它构建设置：

```bash
missing_signing_dir=$(mktemp -d /tmp/bt-nogui-no-signing-XXXXXXXX)
test ! -e "$missing_signing_dir/signing.properties" && test ! -L "$missing_signing_dir/signing.properties" || exit 1
BT_NOGUI_SIGNING_PROPERTIES="$missing_signing_dir/signing.properties" \
  ./gradlew testReleaseUnitTest assembleDebug lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
# debug：app/build/outputs/apk/debug/app-debug.apk；不是正式交付签名
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

当前开发环境使用缓存工具链；测试/debug/lint 显式使用已验证不存在的临时配置路径，不读取正式签名配置：

```bash
source /home/admin/.cache/btreset-build/env.sh
missing_signing_dir=$(mktemp -d /tmp/bt-nogui-no-signing-XXXXXXXX)
test ! -e "$missing_signing_dir/signing.properties" && test ! -L "$missing_signing_dir/signing.properties" || exit 1
BT_NOGUI_SIGNING_PROPERTIES="$missing_signing_dir/signing.properties" \
  "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

同样缺签名配置下单独运行 `assembleRelease`，预期在 `validateNoguiReleaseSigning` 明确拒绝（private signing properties are unavailable）；这是 fail-closed 验证，不是成功 release 打包或行为 RED。正式 release 只能在受控交付时显式使用上述原私密配置。

共享构建主机发生资源压力时，可仅对当前命令追加 `--max-workers=2 --no-parallel`，或通过临时 `GRADLE_OPTS` 限制并发；不改变测试断言、5秒 JVM fixture 或生产超时。默认并发下曾出现两次原有 JVM fixture 超时，临时限流的完整重跑通过；这支持环境压力假说，但没有证明原始根因，详见验证记录。

源码 `versionName=1.4`，`autoVersionCode=(ciRunNumber ?: 0)+5`：本地默认 **code 5**，CI 使用 **`GITHUB_RUN_NUMBER+5`**，保留原有 nullable CI 递增约定。升级输入为已交付后台 **1.3/code 4**；正式 APK 已核验同包同单一证书、code 4→5，不代表真机升级已通过。如果已安装的是更高 code 的 CI 产物，应使用不低于该 code 的同签名构建，而非用本地 code 5 降级。源码 namespace `com.blreay.bluetoothmonitor`、安装包名 `com.blreay.bluetoothmonitor.nogui`、中文后台 label **蓝牙手环重启·后台**、min31/target32/compile34、生产依赖、UserService version 1 及固定 AIDL 方法/事务 ID 均不随此次版本/文档更新改变；原单一证书签名守卫不变。旧 GUI 独立包的版本号不影响本包。

设计与实际验证记录：

- [1.4自主触发、持久配置与可编辑目标设计](docs/superpowers/specs/2026-11-27-automatic-triggers-target-design.md)
- [1.4实施、审查与实际交付记录](docs/superpowers/plans/2026-11-27-automatic-triggers-target.md)
- [1.3紧凑日志与横排按钮设计](docs/superpowers/specs/2026-11-27-compact-log-ui-design.md)
- [1.3实施、验证与交付记录](docs/superpowers/plans/2026-11-27-compact-log-ui.md)
- [1.2开关消息与滚动日志设计](docs/superpowers/specs/2026-11-26-miband-log-design.md)
- [1.2实施、验证与交付记录](docs/superpowers/plans/2026-11-26-miband-log.md)
- [1.1详情控制与累计历史设计](docs/superpowers/specs/2026-11-25-details-controls-history-design.md)
- [1.1实施、验证与交付记录](docs/superpowers/plans/2026-11-25-details-controls-history.md)
- [后台版设计](docs/superpowers/specs/2026-11-25-headless-shizuku-reset-design.md)
- [实施与验证记录](docs/superpowers/plans/2026-11-25-headless-shizuku-reset.md)
- [已验收的手动页面设计](docs/superpowers/specs/2026-11-24-manual-shizuku-control-design.md)

JVM 测试和 APK 构建不替代真机验证。Shizuku/OEM确认框、无焦点窗口、实际OFF→ON和进程死亡后的恢复，需在实际测试手机上验证；最终交付记录明确哪些执行、哪些未执行。
