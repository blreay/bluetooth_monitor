# miband.log 与开关瞬时消息 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: 使用`superpowers:subagent-driven-development`逐任务实施。每任务单一实现代理，先行为红绿、规格审查，再质量审查；不并行生产文件writer或Gradle。用户已批准本次设计，之后不停下询问是否继续。

**Goal:** 详情中的ON/OFF不再展示下方结果文字，以瞬时消息反馈；每次启动/扫描/开关有时间戳滚动日志；交付同包同证书的1.2/code3 APK。
**Architecture:** 主线程coordinator和既有蓝牙安全协议不变。独立日志sink捕获不可变事件，单一有限后台writer负责私有暂存和固定共享根文件的字节裁剪/导出；实际manual controller产生非持久反馈事件。所有文件访问权限只从可见详情的显式按钮申请，日志失败与蓝牙checked store隔离。
**Tech Stack:** 保持Kotlin1.9.24、AGP8.4、Gradle8.7、JDK17、min31/compile34/target32、Shizuku13.1.5、JUnit4及test-only JSON；不增加生产依赖。

---

## 已批准范围、基线与进度

设计：`docs/superpowers/specs/2026-11-26-miband-log-design.md`。用户2026-11-26明确「审核通过」。签名/日志权限/UI/字节和异常约定以该设计为准，不追加审核门。

基线HEAD：`71377337e036518ce8c92550df89e7d4a9409733`，当前已有worktree：`/home/admin/git/bluetooth_monitor_shizuku`，分支`bluetooth_monitor_shizuku_nogui`。原1.1持久文件与输出副本已再次核验为2542237字节、SHA256`2e429826fd80af4bcecc17fa142ca81eb39c9a57ddefa353f6a0fde19eff7960`。另已创建并核验`/tmp/btreset-1.2-baseline-793it9ek/original-delivered-1.1.apk`，构建不能清理这个输入。原1.0遗失记录不重开。

- [x] 只读探索与唯一真实业务选择：用户选择所有文件访问。
- [x] 设计/执行范围审核通过，核验原1.1并新增构建目录外备份。
- [x] Task1：独立日志core、文件writer及Android存储适配。
- [x] Task2：瞬时手动反馈与全部实际启动/扫描/开关日志接线。
- [ ] Task3：1.2身份、README、任务和整体审查。
- [ ] Task4：父fresh完整构建、实际APK/原文件保留核验、交付和Git推送。

## 文件边界与接口

所有下列生产Kotlin相对目录为`app/src/main/java/com/blreay/bluetoothmonitor/`，测试目录为`app/src/test/java/com/blreay/bluetoothmonitor/`。

- `AuditLog.kt`（新）：纯Kotlin事件/sink/编码，时间戳、PID/session/sequence、有限单行UTF-8、转义和exception-isolated调用。不得依赖Android静态Log或读取签名。
- `RollingLogFile.kt`（新）：文件字节上限、完整行裁剪、有限尾部读取、正常追加、临时文件原子替换、失败/半行处理；文件策略可注入测试故障。
- `BoundedLogWriter.kt`（新）：有限队列、单writer调度、私有暂存、授权检查、共享导出、状态和溢出统计；不使用蓝牙worker/lease/store。
- `MibandLog.kt`（新）：主应用singleton Android adapter，lazy后台文件定位与special-access检测、Application Context、独立executor、immutable状态读/显式refresh；不启动Activity。
- `LogStatusNotification.kt`（新）：独立静默日志状态通知（新ID105/独立渠道），direct immutable PermissionActivity PI，不改变历史/自动通知，不由日志错误触发新的蓝牙动作。
- `ManualFeedback.kt`（新）：不持久的feedback类型/去重器，ON/OFF接纳、拒绝、真正终态、保存失败和未知事件；不根据snapshot重新推断结果。
- Task2可新增单一小型`RuntimeAudit.kt`观察helper以支持真实生产路径的callback身份/时间测试，不能实现第二套策略；如包装SwitchBackend必须完整转发六个port及原Observed invocation，不把新增日志当有状态BackendEntry插入checked历史。可见cached日志状态的最小adapter观察接线可修改`MibandLog.kt`，但优先使用生命周期受控的cache重绘，不重构已审查core。
- Task2修改已存在的`PermissionActivity.kt`、`ManualOperationController.kt`、`ResetCoordinator.kt`、`MonitorApplication.kt`、`MainActivity.kt`、`BandResetController.kt`、`ManualBleScanner.kt`、`TriggerReceiver.kt`、`ReEnableReceiver.kt`、`ResetService.kt`、`app/src/main/AndroidManifest.xml`。如增加engine/retirement观察事件，仅修改`DurableResetEngine.kt`/`RuntimeOwnership.kt`的观察接线，不改变原安全状态迁移。`Notifier.always`现有实现复用。
- Task3仅版本/说明：`app/build.gradle.kts`、`AppIdentityBoundaryTest.kt`、`README.md`和本设计/执行记录。

纯sink建议接口（实际调用必须通过异常隔离的event方法；实现者可按一致命名做等价小调整并报告API）：

```kotlin
fun interface AuditSink {
    fun record(name: String, fields: Map<String, Any?>, occurredAt: Long?)
    fun event(name: String, vararg fields: Pair<String, Any?>, at: Long? = null) {
        try { record(name, fields.toMap(), at) } catch (_: Exception) { /* observational only */ }
    }
    companion object { val NONE = AuditSink { _, _, _ -> } }
}
```

生产controller/scanner通过可注入`audit: AuditSink = AuditSink.NONE`记录，coordinator注入`MibandLog`。sink捕获字段的有限不可变副本、时间和sequence；禁止保留Activity、ScanResult或mutable操作对象到writer。

## Task1：独立日志与合法存储adapter

**Create:** 上述5个日志文件。**Test:** `AuditLogTest.kt`、`RollingLogFileTest.kt`、`BoundedLogWriterTest.kt`；如Android-source契约需要新增`LogStorageBoundaryTest.kt`。本task不改manual/启动/蓝牙状态逻辑或版本。

- [x] **1. 行为RED：确定格式和边界。** 用真实临时目录、注入时钟和手动调度器写测试。起点可先建立接口最小stub并让断言失败，不用未定义symbol编译失败冒充RED。示例断言目标：

```kotlin
val max = 3 * 1024 * 1024
val trim = 1024 * 1024
// 用真实UTF-8行填到max字节；等于max不裁剪。
// append("new timestamped line\n")后，字节<=max，新行存在，最老至少trim字节消失。
// 每次裁剪后UTF-8解码及首行时间戳完整；重复超限保留最新序号。
```

覆盖毫秒/时区、队列前捕获时间、同PID不同启动事件、换行/引号/控制字符转义、超长字段/记录标注截断、不同进程session、序号顺序、恰好3MiB、1MiB完整行边界、含中文/emoji、大于3MiB的旧文件和超长旧行、有限读取、最新记录保留。

- [x] **2. 运行RED并保存JUnit证据。**

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku testReleaseUnitTest \
  --tests '*AuditLogTest' --tests '*RollingLogFileTest' --tests '*BoundedLogWriterTest' \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

Expected：编译成功，实际行为断言失败。独立保存RED XML/日志，后续构建会覆盖默认XML。

- [x] **3. 实现字节writer。** constants MAX=3,145,728、TRIM=1,048,576；新记录有限完整UTF-8行，超限先裁剪到下一个换行，重复计算旧超大文件的有限尾部，不能readBytes整个异常大输入。正常append在计划上限内；裁剪采用独占创建的同目录temp，有限完整尾部+新记录写入/sync后atomic move替换。异常保留旧文件，不改未知temp或其它路径，不通过sh/su/Shizuku写文件。失败半行不当完整事件；当前writer只清理自己创建的temp。

- [x] **4. 行为RED/GREEN：失败和队列。** 注入append、sync、rename和权限异常；证明旧文件在失败裁剪中不被破坏、root不谎报成功、pending仍保留。手动scheduler阻塞writer后连续enqueue超过容量，确认调度工作数有限、保留新事件、产生带时间范围的丢弃统计、事件捕获不等待I/O。私有暂存同3MiB策略；权限撤销仍暂存、恢复按原事件时间/身份导出；失败后重复导出不伪造新eventId，不影响任何蓝牙状态。

- [x] **5. Androidadapter。** `initialize(context)`只在主应用guard之后调用，保存app Context/启动固定日志executor，磁盘定位和文件操作延后worker。root通过Android当前主共享存储解析，文件名严格`miband.log`；`Environment.isExternalStorageManager`在实际导出时重新检查，失败catch并披露。内部pending路径在filesDir，不动`bt_reset_pending`preferences。adapter暴露`status()`和`refreshAccess()`，都不在Main检查文件内容。状态通知异常隔离、只按变化更新；新渠道/ID105/direct immutable details PI，不background startActivity。

- [x] **6. 聚焦GREEN、完整tests/debug/lint。** 运行下方完整无release命令，核验实际JUnit和lint（新增权限声明在Task2）。独立规格审查通过后质量审查；修复重要问题、同reviewer复审。代理冻结并报告实际公开API、完整文件allowlist、RED/GREEN日志和测试数量。父独立重跑后进入Task2。

## Task2：瞬时反馈与实际日志入口

**Create:** `ManualFeedback.kt`、`ManualFeedbackTest.kt`、`RuntimeAuditTest.kt`。**Modify/Test:** 文件边界列出的runtime/UI/manifest，以及`DetailsSnapshotTest.kt`、`ManualSourceBoundaryTest.kt`、`ScanLifetimeTest.kt`/现有ScanRetirement suite的必要生产seam断言。保持原295项baseline不减少。

- [x] **1. 先写feedback与观察sink红测。** controller新默认参数`feedback: (ManualFeedback) -> Unit = {}`、`audit: AuditSink = AuditSink.NONE`保留原构造调用兼容。反馈包含action/operationId/类型/状态/关键原因/实际mode和observedState；去重按同操作同类反馈，拒绝每次独立发送，不从旧store或snapshot初始化发送。实际观察到ON/OFF且store完成成功才发成功；SUBMITTED/未知/保存失败不可发成功。前台接管失败、冲突、权限缺失、迟到真实结束、窗口结束未知、反复Service回调、close/recreate不重播全部可执行测试。

- [x] **2. controller实际接线。** 在request ingress记录日志（即使拒绝），accepted/reject、dispatch、每次backend/entered/新command/result、sample、scan事件、quarantine和finish边界记录。`at`使用existing worker元数据；fallback commandId使用当前变更后的变量。feedback/sink异常全部隔离，失败不让checked事务被当成功，也不释放native/scanner/lease。ON/OFF消息由coordinator调用`Notifier.always(app, conciseMessage)`；SCAN仍保留结果区，不每个设备弹Toast。

- [x] **3. UI红绿与所有文件设置。** 删除`manualBodies[ManualAction.ON] = text()`和OFF等价行，仅SCAN进入结果map，保留内部renderer/持久结果。新独立日志状态TextView与授权Button，状态来自immutable `MibandLog.status()`。Button仅点击时启动本包`ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION`；缺失页面fallback `ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION`，失败Toast。onResume显示实际权限检查结果并触发后台flush，不自动重放蓝牙按钮/启动扫描，不自动打开页面。异步flush完成后，即使没有蓝牙callback也须更新可见日志状态：使用单一生命周期受控的cached状态重绘或合并的状态观察回调，stop/destroy取消；不在此更新中调用文件I/O、周期refreshAccess或扫描。Manifest只新增MANAGE_EXTERNAL_STORAGE，不加WRITE/legacy/INTERNET/boot。

- [x] **4. 主进程与每次真实入口。** `MonitorApplication.onCreate`保持UID0/2000及非主进程guard，日志初始化/`process.start`在coordinator构造之前。`MainActivity.onCreate`记录实际action/component（没有任意extras）和新requestId，保持dispatch+finish/NoDisplay。coordinator每次launch/broadcast/recovery/service入口及拒绝/accept/复用记录；不能只在成功分配run后才打日志，不把MAIN来源伪称fooView。

- [x] **5. 自动和手动扫描真实事件。** run/prepare、确切ticket、worker startScan真实入口/返回、通过现有准入的广告名/MAC/RSSI、sample/received/delivered时间、target claim、结果/取消/失败、retirement request和确切确认/失败。Manual callback捕获wall时间（batch共享receipt），保留原`AdvertisementDelivery`和8秒`ManualObservationWindow`准入；日志不能延长窗口或新增native调用。自动claim仍先于teardown，accepted匹配才arm。cleanup confirmed只接真正retirement callback；failure/timeout只说明未知。

- [x] **6. 开关与恢复。** coordinator driver调用记录请求、actual backend尝试、当前command entry/outcome、native-ended与submitted分别标注、每个sample、engine决策/terminal checked结果。普通API和BinderRPC的现有worker捕获时间保留；不为获取svc spawn时间改AIDL/固定argv/runner。首次Shizuku能力、授权、连接问题记录actual reason。automatic count只读原checked结果作日志，禁止计数从logger成功/notification推算。

- [x] **7. 实际运行seam红绿和完整回归。** fake sink抛Exception应不改变driver/engine/manual/native/scan行为；重复启动、拒绝、双方向/普通后备、SCAN只展示和8秒deadline、exact cleanup及command身份顺序可执行。source contract补充所有文件权限的唯一可见申请入口、只SCAN结果map、真实sink注入及startup在guard之后；不能用source检查替代纯行为测试。单一Gradle owner，规格→质量→父fresh无release完整命令。

## Task3：1.2升级身份、文档和整体审查

**Modify:** `app/build.gradle.kts`、`AppIdentityBoundaryTest.kt`、`README.md`、本执行记录和设计状态。

- [ ] **1. 版本行为/source RED。** 修改现有两项1.1/+2测试为1.2/+3，保留全部signer/生产依赖/namespace/SDK守卫，先实际测试断言失败。
- [ ] **2. 最小版本变更。**

```kotlin
// Version 1.2 uses local code 3, or the CI run number plus 3,
// to upgrade the delivered No-GUI 1.1/code 2 APK.
val autoVersionCode: Int = (ciRunNumber ?: 0) + 3
// defaultConfig, all other fields unchanged:
versionName = "1.2"
```

- [ ] **3. README。** 更新当前源码/安装为1.2，ON/OFF消息、扫描结果保留、所有文件授权路径和后台不抢前台、根路径/3MiB/1MiB/字节行边界、内部有限暂存/失败/异步强杀边界、日志公开MAC隐私、主进程vsRPC与计数不由日志决定。保留原1.0失败、原1.1历史实际交付数据；实际新artifact数据仅在父核验后写。
- [ ] **4. GREEN、缺私密配置debug/tests及release fail-closed。** 实际临时不存在BT_NOGUI_SIGNING_PROPERTIES完整test/debug/lint应成功；同缺失配置`assembleRelease`应在validateNoguiReleaseSigning明确失败，此为expected拒绝而非通过release或行为RED。不读取签名文件，不另造私钥作测试，wrong cert仅保留原source契约并最终检查真实正确证书。
- [ ] **5. 规格、质量和最终整体源码审查。** 各任务完整diff/new files及实际Android接线、原安全回归/私钥边界都审查。重要缺陷实际确认后同implementer红绿修正、同reviewer复审；无剩余重要缺陷才交父release门。

## Task4：父fresh构建、公开artifact验证及Git交付

- [ ] **1. 查看最终diff和scope。** 未改main/旧GUI/CI/wrapper/私密输入；原安全协议、295项baseline保留。核验原1.1持久及额外tmp副本的exact hash仍不变。
- [ ] **2. 两次完整强制重跑。**

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

实际读取JUnit XML、计数、failure/error/skipped和lint XML。记录新增ScopedStorage等权限政策warning，不添加无证据抑制、不称lint-clean，要求没有新增MissingPermission/Error；target32 Fatal按实际披露。每次成功release先保存输出目录外临时副本，防止下一次AGP清理。

- [ ] **3. 创建本次公开verifier。** 用native Write创建`/tmp/bt-nogui-1.2-verification.py`，输入实际持久1.1，不复用硬编码缺失1.0的旧preservation门。aapt badging/xmltree检查同包、1.2/code3、min31/target32、NoDisplay完整launcher、RUN/RESET_IF_PRESENT、内部PermissionActivity/ResetService/ReEnable/HistoryDismiss、MANAGE_EXTERNAL_STORAGE、无WRITE/boot/INTERNET。apksigner检查单一signer固定7825cc并与实际1.1一致；DEX日志/feedback/newruntime类存在；所有baseline suite数量不减少、新pure行为suite存在。
- [ ] **4. 复制并重验交付。** 最终第二份release保存为`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.2.apk`和`app/build/outputs/apk/release/bt-monitor-nogui-1.2.apk`。目标不存在用exclusive create；已存在先检查，如果不同拒绝未知覆写。复制后metadata、size、hash核验。同原1.1持久input重新检查，必要时只从核验副本恢复原1.1输出文件，不把重编旧版当保存成功。
- [ ] **5. adb可用性与边界。** `adb devices -l`有已授权设备才做覆盖安装数据保留、所有文件设置授权/撤销、根日志与实际裁剪、循环启动及Toast/扫码/高权限fallback/通知历史。无设备则逐项标未执行，不以JVM和aapt冒充真机。
- [ ] **6. 最终数据文档、attributed commit/push。** 按allowlist stage，核查无APK/私钥/cache进入git。提交末行`Co-Authored-By: Claude Code <noreply@anthropic.com>`，仅`origin bluetooth_monitor_shizuku_nogui` push、`ls-remote`与本地SHA一致、clean tree。更新执行记录并单独docs commit如需，不amend/force-push。交付新APK持久路径、版本、同签覆盖条件、授权使用步骤、真实tests/lint和无设备项。

## 完整无release验证命令

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

## 不可退化的安全/操作约束

所有项目文件用native Write/Edit；所有命令在当前worktree；不bare stash/pop、不新建/移除worktree、不转原checkout。只允许构建内部读取现有私密BT_NOGUI配置；不模型Read、替换、生成、提交或外传JKS/口令，不BT_RESET/debug-key后备。固定证书：`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。

保持svc固定argv、AIDL IDs、Shizuku single-slot/disconnected barrier/quarantine、native EOF和authority证明、exact FGS、retained scan retirement、receiver预算、arm-before-OFF、expected version/checked历史计数。日志/Toast异常、超时、Future cancel、kill请求、窗口关闭或硬件目标均不能解锁执行隔离。日志只能观察，不能依赖日志成功授权蓝牙命令。不得放宽原5秒fixture、断言、生产预算或永久Gradle配置，不把基础设施/compiler failure称为行为RED。

## 执行证据

本节按实际发生追加RED/GREEN、父核验、审查、artifact和失败数据；未执行项不能标完成。

### Task1初始RED与基线保护

父再次实际aapt/apksigner核验原1.1持久APK及额外备份：同包、1.1/code2、单一原7825cc证书、原2542237字节/hash不变，报告`/tmp/btreset-1.2-original-baseline.json`。初始`adb devices -l`为空，尚不是真机验收。

Task1实现代理仅创建5个新日志生产文件和4个新测试文件。父检查保存的RED日志`/home/admin/.cache/btreset-build/task1-miband-log-20261126/red/gradle.log`及`red/assertions-xml`：生产/测试编译完成后执行62项，56项失败、0错误/跳过，其中54项明确AssertionError，另2项是空stub结果导致的IndexOutOfBoundsException/NullPointerException；不把这两项称为断言失败，也不是compiler/基础设施失败。四suite分别Audit13、Writer11、Storage19、Rolling19。代理报告初始聚焦62项转绿，仍在补充边界并准备完整test/debug/lint；父此时尚未独立执行GREEN、未做规格/质量审查，不将Task1标完成。

### Task1冻结与父首次独立回归

代理DONE/frozen，父检查实际Git scope：仅上述5新生产/4新测试及父计划记录修改，旧runtime/UI/manifest/build/版本/README未改。父核验额外保存的compile-success断言RED：`red-bounds-status`39项/6失败、`red-controls-cleanup`37项/2失败、`red-marker`19项/1失败，失败均为AssertionError，0错误/跳过。代理完整日志`green/full.log`实际30秒/73任务执行，26suite368项零失败/错误/跳过，新增Audit19、Rolling20、Writer11、Storage23；日志此时仍未接入应用入口，未生成新release。

父随后使用确实不存在的`BT_NOGUI_SIGNING_PROPERTIES=/tmp/btreset-1.2-parent-task1-missing-signing-20261126.properties`独立执行完整无release命令成功：50秒、73任务全执行，日志`/tmp/btreset-1.2-task1-parent-full.log`。父读取本次生成的JUnit确认26suite368项零失败/错误/跳过，保留原295项；lint仍为13项（Warning12/Fatal1 ExpiredTargetSdkVersion），MissingPermission0/Error0，三个原通知setter弃用warning保留。生成报告另存`/tmp/btreset-1.2-task1-parent-m5t8236p`，不是只相信代理报告。独立Task1规格审查返回PASS：实际5生产/4测试核对，无已验证Critical/Important；73新测试使用实际core/storage seam，Android权限/路径/通知的source断言不当真机验证。一个非阻塞测试建议（完成行后第二行在多字节字符中断）未对应实现缺陷。随后启动独立质量审查；质量门尚未完成，Task1仍不标完成。

质量审查过程中，审查代理调用的code-review技能意外启动了第二审查代理，违反本任务禁止再委派的约束。审查代理立即发出停止读取指令，TaskStop被所有权限制拒绝；父不代执行已被拒绝的停止操作、不绕过限制。额外代理随后报告已自行停止，仅执行只读Git diff/列表、未读取源码/完成审查/修改文件；此为代理报告，不使用其输出作审查证据。原质量审查继续直接读取源码，不再调用自动委派技能。该流程违规如实保留，不伪称从未发生。

直接质量审查返回NOT APPROVED，1项Important：`AuditSink.event()`在capture的96字节key限制之前，先associate/hash完整新字符串key；多MiB未hash过的key使调用线程（包括NONE）执行输入大小相关工作，违反有限capture。父读取实际`AuditLog.kt:11—16`及相关测试确认，已有超长key测试仅调用capture，不能覆盖convenience入口。已交同Task1实现代理仅修改AuditLog/AuditLogTest，用真实event入口红绿修正、在map插入前先做有限字符长度检查并保留truncation marker和escaped-byte限制，再完整无release回归。原368项回归通过不代表此质量缺陷关闭，Task1仍未完成。两个Minor建议不对应存储正确性缺陷：多字节第二行部分追加测试可补强；ACK_FAILED状态可被后续成功private读/暂存清掉，具体重复回放提示不sticky，通用原eventId可能回放/非exactly-once披露仍有效。此轮不引入非必要状态重构。

### Task1质量修正通过、父再次独立核验

同实现代理仅修改AuditLog/AuditLogTest并冻结：event入口在map插入/hash之前先检查key.length>96，最多访问前32个输入slot；忽略超长key/字段溢出的marker在重复key和32项边界仍明确，capture的escaped-byte规则不变。父实际核验`quality-key-bound/red/gradle.log`与`red/xml`：编译成功、23项/4个AssertionError失败、0错误/跳过；代理聚焦GREEN23项通过，完整日志和XML报告26suite372项零失败/错误/跳过（原295+原Task1全部73+4回归）。

同独立质量审查代理直接重新读取实际两文件返回APPROVED：原Important关闭，无新Critical/Important；未自动委派或执行构建，原Minor仍非阻塞。父随后独立完整test/debug/lint强制重跑成功：50秒、73任务全执行，日志`/tmp/btreset-1.2-task1-parent-key-fix-full.log`，实际26suite372项零失败/错误/跳过（Audit23、Rolling20、Writer11、Storage23），生成报告另存`/tmp/btreset-1.2-task1-parent-key-fix-bkrhm6z9`。lint仍13项Warning12/Fatal1（ExpiredTargetSdkVersion），MissingPermission0/Error0；非lint-clean。父再次实际核验原1.1持久/额外备份未变，报告`/tmp/btreset-1.2-task1-key-fix-baseline.json`，diff-check通过。Task1规格/质量和父独立回归门均通过，已标完成。父按明确allowlist提交Task1日志文件/测试及证据记录，提交`058c605fd39e137bc02d258bd3d408268143e8dc`，attribution末行已核验，未push。随后以该clean baseline启动单一Task2实现代理接入生产；父只更新文档/公开verifier，不并行修改runtime或执行Gradle。当前仍未生成新1.2 release。

### Task2冻结、证据分类与父独立首次回归

Task2实现代理已DONE/frozen，父实际核对13个tracked生产修改（manifest/UI/controller/coordinator/入口/scan/engine/retirement）和2新生产ManualFeedback/RuntimeAudit、2新测试；Task1 core、原372测试/26suite、版本/identity/build/README均未改，父计划修改另行保留。实现代理报告消息真实边界接线、ON/OFF结果区移除/SCAN保留、可见显式所有文件设置、单一visible cached状态每秒重绘且stop/destroy取消、各实际入口/扫描/开关/退役与checked终态日志；这些仍待独立规格/质量核验，不仅凭实现报告确认符合。

父检查保存的compile-success RED日志与XML：初始`/home/admin/.cache/btreset-task2-red.log`及`btreset-task2-red-results`18项/17失败（13 AssertionError+4缺少事件/反馈的NoSuchElementException）；proof阶段`btreset-task2-red-proof.log`及结果12项/3失败，均为缺少事件的NoSuchElementException，不能称3个明确断言失败。advertisement阶段`btreset-task2-red-advertisement.log`及结果13项/2 AssertionError，其中一项是缺少广告接线，另一项为新测试delayed-Main fixture时钟意外相等；代理修正新fixture以真实模拟延迟，不把该fixture错误冒充生产缺陷，不放宽原fixture/assertion。上述均实际执行测试后失败，0错误/跳过，非compiler失败。

代理最终完整日志`/home/admin/.cache/btreset-task2-full-final.log`报告22秒/73任务执行、28suite399项零失败/错误/跳过，新增Feedback11/RuntimeAudit16。父随后独立强制完整test/debug/lint成功：56秒/73任务全执行，日志`/tmp/btreset-1.2-task2-parent-full.log`；父读取本次28suite399项零失败/错误/跳过，完整报告另存`/tmp/btreset-1.2-task2-parent-zk83emsb`。lint实际14项Warning13/Fatal1，新增未抑制ScopedStorage（MANAGE_EXTERNAL_STORAGE），Fatal仍为原ExpiredTargetSdkVersion，MissingPermission0/Error0，不称lint-clean。父再次实际aapt/apksigner/hash核验原1.1持久及备份未变，报告`/tmp/btreset-1.2-task2-baseline-after-parent.json`，diff-check通过。Task2独立规格审查返回NOT PASS：1项Important，实际ManualFeedback.mode被capture但conciseMessage只拼action/reason，coordinator真正Toast调用此formatter；普通OFF和Shizuku-root OFF成功消息相同，未满足显示实际模式的约定。父读取ManualFeedback/controller真实观察完成边界/coordinatorNotifier接线确认，不仅凭DTO测试认为已满足。已交同Task2实现代理仅修改formatter/实际显示payload测试，unknown/accept/reject不能虚构路径，不改变checked终态。Minor：NoDisplay dispatcher创建有日志，launch同步返回/finish请求无对应观察；同时以最小MainActivity事件和RuntimeAudit源契约补充，finish请求不假称销毁/native结束。质量门尚未启动，399项通过不替代规格符合，Task2未标完成。

### Task2规格修正冻结

同实现代理仅修改ManualFeedback/ManualFeedbackTest/MainActivity/RuntimeAuditTest并冻结。父读取实际formatter确认known mode使用SwitchMode.label，null不虚构backend，NONE显示尚未切换/未调用开关；MainActivity实际dispatch-return在同步launch之后，finish-request在finish之前，recreation只记录skip。父解析保存的`/home/admin/.cache/btreset-task2-review-fix-red-results`：2suite31项/4 AssertionError，0错误/跳过；GREEN31项零失败，full-results28suite403项零失败/错误/跳过（Feedback14、RuntimeAudit17），原399项保留。本次实现代理完整日志`btreset-task2-review-fix-full.log`，父尚未独立重跑修正后的完整命令。同规格reviewer直接复审实际四文件和controller/coordinator/Notifier调用返回PASS：原Important实际模式消息和Minor分发观察均关闭，无新已验证Critical/Important；reviewer未运行构建，source-order测试不当Android执行证明。随后启动独立只读质量审查，禁止再次委派或使用自动fork技能。独立质量审查随后返回APPROVED，无已验证Critical/Important/Minor；实际读取完整13文件diff、4新文件及相邻authority实现，17项冻结hash匹配，未运行构建或再委派。父再独立完整test/debug/lint强制重跑：52秒/73任务全执行，`/tmp/btreset-1.2-task2-parent-review-full.log`，28suite403项零失败/错误/跳过；报告另存`/tmp/btreset-1.2-task2-parent-review-3up8eqhz`。使用实际不存在私密配置路径，debug/tests不依赖release凭据；全部17冻结hash仍匹配。lint实际14项Warning13/Fatal1 ExpiredTargetSdkVersion，ScopedStorage未抑制，MissingPermission0/Error0，非lint-clean。父实际原1.1并排aapt/apksigner/hash核验仍不变，报告`/tmp/btreset-1.2-task2-final-baseline.json`。规格、质量、父fresh门均通过，Task2及七子步骤标完成；尚未生成1.2 release。
