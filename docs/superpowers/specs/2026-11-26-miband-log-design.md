# 1.2：开关消息反馈与 miband.log 滚动日志设计

日期：2026-11-26。分支：`bluetooth_monitor_shizuku_nogui`。基线：`71377337e036518ce8c92550df89e7d4a9409733`。

状态：用户于2026-11-26明确「审核通过」，本设计及执行范围已经批准；2026-11-27已完成实施、规格/质量/整体审查、父两次签名构建及实际APK核验，交付1.2/code3同包同证书产物。无连接设备，真机升级/权限/日志/BLE/OEM验收未执行。实施及证据见[1.2实施计划](../plans/2026-11-26-miband-log.md)。设计/计划审核是唯一审批门；之后连续完成实施、测试、规格/质量/整体审查、父构建核验、当前分支提交推送和APK交付，不追加是否继续的确认。

## 用户要求和已经确定的选择

- 删除「打开蓝牙」「关闭蓝牙」按钮下面的文字结果区，点击执行通过消息反馈。
- 详细记录应用进程启动、每次扫描、每次开关蓝牙，必须带时间戳。fooView定时启动可能复用同一进程，必须同时记录每次启动触发，不能只记录Application.onCreate。
- 外部日志固定为当前Android用户主共享存储根目录的`miband.log`，通常显示为`/sdcard/miband.log`。
- 日志最大3MB；超限删除最老1MB，保障新记录写入。
- 小版本升级为1.2，本地versionCode3，保持现有后台包名和签名，直接升级已交付1.1/code2。
- 用户已选择**允许管理所有文件**：通过系统设置一次性授予`MANAGE_EXTERNAL_STORAGE`特殊访问，不使用SAF文件选择器方案。

## 方案取舍

已比较两个授权方案：SAF单文件授权的权限范围更小，但系统提供者/厂商选择器不一定允许创建或选中严格的根目录固定文件；所有文件访问权限范围较大，但能让普通应用UID直接维护固定根路径。按用户选择采用后者，只在本功能中使用固定日志路径及必要滚动临时文件，不浏览或上传其它共享文件。

日志采用独立、串行、有限队列的应用日志模块，不复用蓝牙工作池。同步文件写入会阻塞Main/前台服务接管或蓝牙清理，不采用；把日志写入扩展为Shizuku命令会增加特权协议和执行权限耦合，也不采用。

## 1. 详情窗口与瞬时消息

`PermissionActivity`仅移除ON/OFF的两个TextView及对应渲染接线。保留「扫描蓝牙」的实时设备结果、当前自动状态、所选历史、累计、权限和恢复入口；不删除持久ON/OFF结果或手动安全fence。

消息使用已有`Notifier.always()`的普通文字Toast，应用Context发送，不增加overlay、透明Activity或后台Activity。点击接纳时可提示「已接纳，等待执行」；最终消息必须区分已观察实际目标状态、失败、拒绝和结果未知。`SUBMITTED`只能表示请求提交，不能显示为已经打开/关闭成功。简短消息显示实际模式及关键原因，完整时间和原因进入日志。

新增非持久的手动反馈事件，而不是在`render()`或snapshot订阅中识别旧结果弹Toast。事件由手动controller的实际接纳、拒绝、前台接管失败、终态保存成功/失败和隔离边界发出，coordinator传给Notifier。相同操作的同类反馈不重复发送；独立拒绝点击各自反馈；从未知转为真正结束可以发送新的状态消息。重开窗口、Activity重建、初始snapshot重放和查看旧卡片都不重播旧Toast。

反馈回调异常不能改变controller结果、checked事务、lease、native权限或扫描清理。窗口关闭不取消命令，也不作为执行结束证据。Toast受Android/OEM限制，不能承诺送达；完整诊断以日志和既有安全记录为准。

## 2. 存储授权与后台行为

Manifest增加`android.permission.MANAGE_EXTERNAL_STORAGE`，不增加旧WRITE_EXTERNAL_STORAGE、requestLegacyExternalStorage、INTERNET、boot或定时组件。

运行详情增加独立日志状态区和「授权日志写入（所有文件访问）」按钮。只有用户主动点击此按钮，才打开`Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`，data为本应用`package:`URI；若厂商没有这个页面，使用系统所有文件访问设置入口，仍只由显式点击触发。打开失败说明实际失败，不假称授权成功。

返回详情时通过`Environment.isExternalStorageManager()`重新检查，日志模块在后台重新尝试导出。正常图标、RUN、广播和恢复入口不为日志授权打开详情或设置，不推迟已有蓝牙执行，不自动授予或反复弹出授权页面。

未授权、权限撤销或外部写失败时，保留最多3MiB的应用私有临时日志，通过独立静默日志状态通知和详情明确显示「外部日志未写入/内部暂存」。点击通知直接进入真实详情，通知不操作蓝牙；与原100—104号通知、自动累计历史渠道隔离。状态通知仅在状态变化时更新，不按每个广告刷通知。

授权恢复后按原事件顺序导出仍保留的内部记录，保留原时间戳和事件身份。内部缓冲也已达到上限时同样裁剪最老内容，不能声称保留了无限的未授权历史。内部文件不是用户指定的根目录交付文件；未授权时必须明确外部路径仍不可写。

共享日志含本设备扫描到的蓝牙名称、地址及运行诊断，其它获得存储访问的应用/用户可能读取；不写入口令、私钥、授权令牌、任意Intent extras或其它文件内容，不上传日志。

## 3. 记录格式、身份和时间真实性

UTF-8单行结构化文本，每条记录包含带毫秒和时区的时间、PID、主应用进程session UUID、进程内递增序号和事件类型；关联信息按实际可用情况包括启动requestId、runId、operationId、commandId、scanner run/serial、目标ON/OFF、来源入口、backend、结果、状态和原因。

示例格式：

```json
{"time":"2026-11-26T12:34:56.123+08:00","pid":12345,"session":"session-uuid","seq":42,"event":"switch.observed","owner":"automatic","runId":"run-uuid","commandId":"command-uuid","target":"OFF","state":"OFF","authorityEnded":true}
```

时间在事件边界捕获，不使用日志worker实际落盘时刻代替事件时刻。另记当前进程的单调时间用于排序/耗时分析，不跨进程用elapsed时钟编造持续时间。迟到的worker入口/返回/实际状态样本保留源端capturedAt/enteredAt/returnedAt/sampledAt，并区分回调接收/记录时刻。

明确区分请求、尝试路径、进入普通API/应用侧Binder RPC、返回/提交、native权限结束、实际状态观察和checked终态决策；Binder RPC进入不等于已验证svc子进程创建，状态采样不等于精确硬件切换瞬间。未知时间或结束证据写未知，不用当前时间补齐。

只记录本应用主进程的启动，保留UID0/2000及非主进程的Application初始化保护。专用Shizuku服务的应用侧连接/能力/调用结果可以记录，但不让该特权进程成为外部日志writer，不声称捕获了其它系统或svc子进程的全部启动。

外部名称、地址、action和错误原因安全转义，换行不能伪造一条带时间戳的新记录。每条记录有有限字节上限，截断文本保留UTF-8字符边界并标注截断，不能因一条超长广告/异常信息破坏文件上限。

## 4. 实际执行覆盖

日志入口必须接入当前Android生产路径，而不是只改未使用的旧发现类、通知renderer或测试替身。

| 类别 | 必须记录 |
|---|---|
| 主应用进程 | UID检查后的Application启动、PID/session、版本；在构造coordinator前初始化日志 |
| 每次启动触发 | NoDisplay launcher/RUN的实际action/component、独立请求身份；每次broadcast/alarm/service启动的真实入口；接纳、复用待恢复或拒绝及原因 |
| 启动检测 | Shizuku能力/连接和授权结果；附近设备条件；前台接管请求、成功/拒绝、窗口结束 |
| 自动扫描 | 请求窗口和normal/restricted路线、准备、确切注册、真实startScan入口/返回、有效广告名称/MAC/RSSI、目标匹配与claim、无命中/失败/取消、结果摘要、退役请求和真实退役/失败 |
| 手动扫描 | 每次按钮请求/拒绝、准备/注册/真实入口/返回、通过8秒时间准入的真实广告、设备数/overflow、窗口结束、失败和确切退役；目标仍只标记、不重启 |
| 自动/手动开关 | 请求目标和身份、每次backend/preflight选择、每个新command的真实进入/返回/提交、普通后备的原因与新身份、每次有界实际状态样本、权限结束/未知、最终成功/失败/隔离及checked保存结果 |
| 自动恢复/终态 | alarm/recovery入口及恢复决策、实际OFF/ON证据、终态结果/计数和保存成功或失败；日志不反向决定恢复或计数 |

MAIN只标为launcher入口，RUN标为RUN入口；应用没有可靠的fooView来源标识，不能把所有MAIN请求伪称为已识别的fooView调用。同进程重复启动也必须先记录入口，再进行准入检查；拒绝请求不能消失于日志。

Fallback期间记录当前变化后的commandId，不能一直使用初始operation.commandId。取消和扫描退役各有独立事件，不能把cancel、观察窗口结束或stopScan请求当成已确认清理。

## 5. 滚动文件和错误隔离

字节约定：3MB为`3 * 1024 * 1024 = 3,145,728`字节，1MB为`1 * 1024 * 1024 = 1,048,576`字节。文件长度恰好等于3MiB不裁剪；计划追加会超限时，先删最老至少1MiB，并延伸到下一完整换行边界，再写新记录，因此可能比1MiB多删一小段。多次超限或既有超大文件重复计算裁剪，留下有限尾部，不能读入整个异常大文件。

写入由主应用唯一日志writer串行执行，Main只做有限事件捕获/入队。文件I/O、同步和裁剪不运行在Main、ADAPTER、SHIZUKU、DIAGNOSTIC或exact-cleanup工作lane。内存队列和单条记录都有上限；积压溢出时保留新事件并记录丢弃计数/时间范围，不能无界扩内存、创建新worker或阻塞蓝牙路径等待日志。

普通追加在超限前执行裁剪。裁剪用有限读取的尾部和同目录独占创建的临时文件，写入/sync完成后原子替换固定日志；临时文件失败不损坏旧完整文件，不使用未知已有文件作为可覆写临时目标，不长期保留分卷历史。只清理自己本次创建的临时文件。中途进程死亡可能留下临时文件，不将其误称为主日志或新事件。

文件读写/替换失败、目录占位、权限撤销和磁盘不足都使外部状态变为失败，保留内部待导出的事件并提示。正常追加发生部分失败时，不把半行认作完整记录；重试保留eventId，崩溃在外部写成功但内部确认前可能产生同eventId回放，不将重复日志当成新的开关次数。日志本身不是恰好一次的审计事务；自动累计仍完全由原checked历史事务决定。

对已授权且由本writer维护的根文件保证上限和保留新完整记录；其它应用同时修改/删除该公开文件、未授权、系统强杀或断电不在可保证范围。不能承诺强杀前尚在内存队列的记录已经落盘；写失败和丢弃必须如实披露。内部暂存文件也按相同上限裁剪。

日志失败不设置蓝牙store.storageFailed、不撤销/释放native fence、不阻止安全恢复/retirement、不改变准入或判定执行成功。日志模块不调用Shizuku、shell、su或任意命令。

## 6. 实施拆分与测试门

### Task1：纯日志模型、字节裁剪与有限writer

新增独立事件/文件/队列模块及JUnit行为测试。先跑真正的失败断言，再实现：时间戳捕获与转义、恰好3MiB、超限裁剪最老1MiB、完整UTF-8/行边界、保留最新记录、已有超大文件、超长记录、有限队列溢出、追加/裁剪/替换失败、内部暂存和授权后导出、顺序及事件身份。使用临时测试目录和可注入文件/授权/时钟接口，测试实现必须被实际Android模块调用。

### Task2：Android日志授权、瞬时反馈及生产路径接线

修改`PermissionActivity.kt`、`MonitorApplication.kt`、`MainActivity.kt`、`ResetCoordinator.kt`、`ManualOperationController.kt`、`ManualBleScanner.kt`、`BandResetController.kt`、`TriggerReceiver.kt`、`ReEnableReceiver.kt`、`ResetService.kt`及`AndroidManifest.xml`。必要的纯engine/retirement事件仅新增观察接线，不改变原状态迁移或协议。日志状态通知放独立模块，复用direct immutable详情PendingIntent模式。

行为红绿覆盖重复启动/拒绝仍记录、正常/受限/手动扫描、fallback新command身份、状态与native结束证据区分、日志sink抛异常不改变蓝牙行为、按钮无持久结果TextView、异步实际结果消息、拒绝/接管失败/未知/保存失败、重建不重播消息。保留既有DetailsSnapshot、ManualSourceBoundary、ScanRetirement、HeadlessRuntime、Driver及SDK/runner回归。

### Task3：版本、文档及最终审查

身份测试先红后绿，将`versionName`改为`1.2`，`autoVersionCode=(ciRunNumber ?: 0)+3`。保持applicationId、namespace、SDK、Shizuku UserService协议版本、AIDL和私密签名配置及公开证书pin不变。README更新授权、暂存、日志格式、3MiB/1MiB、消息和无真机边界；旧1.1交付记录保留，原1.0文件保存失败不改写。

每个任务单一实现writer/单一Gradle owner，完成规格审查再质量审查，修正并复审通过。最终整体审查覆盖全部tracked/new文件和生产接线。不得用编译或基础设施失败冒充行为RED，不放宽原5秒fixture、断言、生产窗口或永久Gradle设置。

### Task4：父核验、保留旧APK与交付

构建前再次核验构建目录外的原1.1交付文件；它不作为AGP输出被清理，不重编旧版本冒充原文件。父对最终源码至少两次完整强制执行：

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

核对真实JUnit数量和零失败/错误/跳过，debug/release成功、实际lint问题及新增权限相关诊断，不称lint-clean。以实际保存的1.1 APK为升级输入，用aapt/apksigner验证1.2/code3、同包同证书、NoDisplay完整launcher和内部组件；重复release检查签名稳定，不要求APK字节可复现。

输出`bt-monitor-nogui-1.2.apk`，先保存到`/home/admin/bluetooth-monitor-apks/`，再保存构建输出副本，复制后重新验证metadata/hash。已有不同目标不盲目覆盖。重新验证原1.1持久文件仍为原hash；AGP清理输出里的旧副本时可从已核验原文件恢复，不影响持久输入。

检查adb设备：只有已授权设备才做覆盖安装保留历史/权限、所有文件访问授权/撤销、真实根文件和跨启动日志/裁剪、按钮与Shizuku/OEM行为验证。没有设备则明确这些未执行，不以JVM/APK身份核验冒充现场升级或存储授权测试。

测试、审查和父核验完成后，仅当前nogui分支attributed提交/push，核对远端SHA和干净tree。提交末尾：`Co-Authored-By: Claude Code <noreply@anthropic.com>`。不合并main、旧GUI，不PR、不dispatch发布、不替换rolling latest。

## 升级输入与安全边界

本轮已实际重新核验两份原1.1文件，均2542237字节，APK SHA256：

`2e429826fd80af4bcecc17fa142ca81eb39c9a57ddefa353f6a0fde19eff7960`

持久路径：`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.1.apk`；输出副本：`app/build/outputs/apk/release/bt-monitor-nogui-1.1.apk`。原1.1为`com.blreay.bluetoothmonitor.nogui`、1.1/code2；固定证书SHA256：

`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`

不读取私密signing.properties/JKS到模型，不生成/替换key、不外传或提交私密材料，不使用BT_RESET或debug-key后备。仅构建进程内部读取现有私密配置。

所有项目改动使用native Write/Edit，所有命令留在当前已有worktree，不新建/删除worktree、不转原checkout、不bare stash/pop。固定svc argv、SDK single-slot/disconnected barrier/quarantine、native EOF与authority fence、exact FGS接管、独立扫描retirement、receiver预算、arm-before-OFF、版本保护、同事务历史计数全部保留。取消、超时、kill请求、窗口关闭、文件落盘成功或当前硬件目标状态都不是native已结束的证明。

## 官方存储依据与未验证边界

- [Android共享文档访问](https://developer.android.com/training/data-storage/shared/documents-files)
- [Android 11存储限制](https://developer.android.com/about/versions/11/privacy/storage)
- [所有文件访问权限](https://developer.android.com/training/data-storage/manage-all-files)
- [特殊权限申请](https://developer.android.com/training/permissions/requesting-special)

本轮直接页面抓取失败，获得的是官方搜索索引内容；未声称读取全文或验证所有OEM设置行为。所有文件访问需要用户系统设置授权，当前target32不能靠普通存储权限当然写根目录。该权限还有Google Play分发限制；本任务交付独立APK，不申请Play审核或发布。
