# 1.3 紧凑日志与横排按钮 Implementation Plan

> **For agentic workers:** 使用`superpowers:subagent-driven-development`。每项任务单一实现writer/Gradle owner，先可执行RED/GREEN，再规格→质量审查；已批准，不增加确认门。所有项目改动用native Write/Edit。

**Goal:** 秒级纯文本三类摘要日志；扫描结果改消息；操作/设置按钮横排；同签1.3/code4 APK及当前分支推送。
**Architecture:** 大幅删除1.2通用事件捕获和生产观察调用，保留单一有界后台滚动存储和原蓝牙权威协议。扫描摘要只观察现有有效广告，开关摘要只读取原权威结果；不实现另一套成功/恢复判断。
**Tech Stack:** Kotlin1.9.24、AGP8.4、Gradle8.7、JDK17、SDK31/32/34、Shizuku13.1.5、JUnit4；生产依赖不变。

## 已批准范围与基线

设计：[2026-11-27-compact-log-ui-design.md](../specs/2026-11-27-compact-log-ui-design.md)，用户实际回复「OK」。现有worktree`/home/admin/git/bluetooth_monitor_shizuku`，分支`bluetooth_monitor_shizuku_nogui`，功能基线`bf045315364af7d1f39c4744dabf047474693aef`。

已实际aapt/apksigner/hash核验原1.2：包名`com.blreay.bluetoothmonitor.nogui`、1.2/code3、2652533字节、APK SHA256`60cfa2fffcc65ae6ad1197855a27e02dde23e1b22fd79211f65872bc0f3d497d`、单一证书`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。持久`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.2.apk`；额外构建目录外备份`/tmp/btreset-1.3-baseline-sqj_26dn/original-delivered-1.2.apk`，字节/hash已核验。旧1.0遗失不重开，也不删除原1.1/1.2。

- [x] 探索、轻量设计与真实用户批准；保存实际1.2升级输入。
- [x] Task1：紧凑日志、实际摘要、SCAN消息和横排UI；规格/质量/父回归。
- [x] Task2：1.3身份/说明、缺签名拒绝、规格/质量及最终整体审查。
- [ ] Task3：父两次签名构建、实际原/新APK核验、外部保存、当前分支提交推送。

## Task1：一个cohesive日志/UI实现任务

**Modify:** `app/src/main/java/com/blreay/bluetoothmonitor/`下`AuditLog.kt`、`BoundedLogWriter.kt`、`MibandLog.kt`、`LogStatusNotification.kt`（仅状态DTO适配，如需）、`RuntimeAudit.kt`（删除详细backend装饰，可替换为小型摘要helper）、`MonitorApplication.kt`、`MainActivity.kt`、`TriggerReceiver.kt`、`ReEnableReceiver.kt`、`ResetService.kt`、`ResetCoordinator.kt`、`BandResetController.kt`、`ManualBleScanner.kt`、`ManualOperationController.kt`、`ManualFeedback.kt`、`PermissionActivity.kt`、`DurableResetEngine.kt`、`RuntimeOwnership.kt`。`RollingLogFile.kt`只允许必要的legacy单行限额引用解耦，滚动/异常算法不重写。最多一个额外小型`ScanSummary.kt`或等价纯观察helper。

**Test:** 更新1.2专用`AuditLogTest.kt`、`BoundedLogWriterTest.kt`、`RuntimeAuditTest.kt`、`ManualFeedbackTest.kt`的已被1.3取代的格式/详细事件/UI断言，保留相关有限行/异常/去重/authority测试；`RollingLogFileTest.kt`、`LogStorageBoundaryTest.kt`滚动/权限/导出/故障断言不放宽。扩展实际manual/auto扫描seam测试。原295基线安全测试保留，不因新日志降低蓝牙约束；旧UI源码断言确实与新横排/删SCAN卡冲突时只改该UI断言，保持其它断言与测试数量。

- [x] **1. 先建立最小类型和实际行为RED。** 建议接口如下；允许等价小调整，但报告实际API，不引入字段map/通用event框架：

```kotlin
sealed interface CompactLogEntry {
    data object ProcessStarted : CompactLogEntry
    data class ScanEnded(
        val count: Int?, val overflow: Boolean,
        val targetSeen: Boolean?, val result: String
    ) : CompactLogEntry
    data class BluetoothEnded(
        val enabled: Boolean, val mode: SwitchMode?, val result: String
    ) : CompactLogEntry
}
fun interface AuditSink {
    fun record(entry: CompactLogEntry)
    fun event(entry: CompactLogEntry) {
        try { record(entry) } catch (_: Exception) { }
    }
    companion object { val NONE = AuditSink { } }
}
fun interface AuditClock { fun wallMillis(): Long }
// AuditLog(clock, zone).capture(entry): String
// String is the complete timestamp-prefixed UTF-8 line captured before enqueue.
```

真实formatter测试示例：固定clock使`capture(ProcessStarted)`输出`2026-11-27 08:20:01 进程启动\n`，没有JSON/PID/UUID/ms/nanos。ScanEnded(5,false,true,"完成")只含5和目标有，不含名称/MAC；overflow用100+；未扫描count/target未知，不写假0/无。BluetoothEnded(false,SHIZUKU_ROOT,"成功")用Shizuku，不暴露root/shell细分；ORDINARY用普通，NONE/null不能伪造模式或成功调用。控制字符只占一行，新单行最多512 UTF-8字节，超长原因在拼接前有限截取，不hash/编码整条不受限字符串。

执行并保存编译成功后的RED XML/log：

```bash
source /home/admin/.cache/btreset-build/env.sh
BT_NOGUI_SIGNING_PROPERTIES=/tmp/btreset-1.3-task1-no-signing.properties \
  "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku testReleaseUnitTest \
  --tests '*AuditLogTest' --tests '*BoundedLogWriterTest' \
  --tests '*RuntimeAuditTest' --tests '*ManualFeedbackTest' \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

先核验临时签名配置不存在。未定义symbol/编译失败不是行为RED；记录AssertionError与其它实际失败类型，不笼统杜撰。

- [x] **2. 简化capture/writer并删除详细hooks。** capture只秒级本地日期时间+固定描述，队列存String；默认小队列64、每批16，单一scheduled drain。删session/sequence/eventId/elapsed/source/receipt和log.dropped记录，drop数量可只在cached状态。保留scheduler/storage/onStatus异常隔离、私有原行导出和ACK失败可回放。legacy旧行允许原8KiB限额，新行512；不要因新格式把旧pending当损坏、清空旧根文件或增加转换器。删除Main/receiver/service/coordinator/scan/cleanup/engine的详细事件和RuntimeAudit backend wrapper；执行回原backend，完整保留原Observed invocation/状态读/命令策略，不能把observer当checked backend history。

- [x] **3. 真实scan摘要。** 主进程guard后process.start改成唯一ProcessStarted。自动run/emit/cancel各真实结束路径最多一个摘要，复用现有去重count并记住已观察目标，目标出现不等于claim/arm成功。前置拒绝且未创建被接纳操作/扫描实例的SCAN点击不是一次扫描，只需真实REJECTED消息，不恢复入口日志；已接纳后无法准备/启动则仍需未执行摘要，不编造0/无目标。手动使用小型annotation-only摘要累计器或现有collector，不加无界集合；目标在每个有效当前广告、保留上限判断之前锁存，之后名字更新不撤销。建议给ManualScanEvents增加有默认body的`summary(value: ScanSummary)`，原必需方法不变；finish和silent retire共用一次close-observation摘要，不让retire递归events.finished。controller按exact operation/command接受末摘要，serviceEnded已pre-mark scanFinished时也不能丢该摘要；完成Toast仍需要原exact retirement/checked terminal，不让摘要释放占用。保留内部观察时钟和8秒准入。

- [x] **4. 真实ON/OFF摘要。** 自动成功OFF在原engine.observe实际OFF→ON handoff，成功ON在原RESTORED terminal结果；manual在原checked finish/quarantine/window/storage失败。SUBMITTED只说明已提交，不能成功；authority未知即使目标硬件已到也要注明未知。从当前exact command原backend history/真实结果读取实际mode，普通后备不能借旧mode；特别是engine.prepare旋转到ON_READY但lastMode仍保留OFF的情况，不能借前阶段推断ON模式。no-op表示未执行。失败/未知/迟到改善按原事实写简短结果。最多小型有界每命令观察去重，防重复采样/同失败事务回调产生重复行；不额外采样、不加计数/恢复判断、不新增policy/store schema/持久logger identity。

- [x] **5. UI/SCAN反馈。** SCAN正常终态消息含去重数量/100+、目标有/未见；失败/未知/保存失败消息如实，保留同操作同类去重、独立拒绝，不从snapshot/recreate/close重播、不每广告Toast。coordinator不再过滤成功SCAN terminal。删除manualBodies[SCAN]和整个结果渲染consumer，但DetailsText/manual持久结果保持供安全/旧契约。删除logBody/renderLogStatus/每秒cache重绘，仅onResume异步refresh及显式短标签「日志权限」按钮保留，settings fallback不变。两个row均horizontal+equalweight，三个button和两个settingsbutton分行；其它自动状态/历史/累计/权限/恢复保持，不重建dialog重置滚动或改NoDisplay入口。

- [x] **6. 聚焦GREEN、完整回归、审查。** 保存生产使用seam行为：scan目标在101st设备仍记住、重复/后续名变化不丢；取消/failedstart/retire/超时/latecleanup有摘要且不解fence；actualswitch mode/result/unknown/no-op/fallback/checkedfail；logger/Toast抛异常不改变蓝牙；旧行字节导出、无第四类日志。source仅验证UIrow/删卡删说明/guard与删除旧详细hooks，不替代行为。单一Gradle owner运行完整无release命令，规格PASS后质量审查，Important同implementer红绿修/同reviewer复审，父独立完整回归后Task1完成。

## Task2：版本与说明

**Modify:** `app/build.gradle.kts`、`app/src/test/java/com/blreay/bluetoothmonitor/AppIdentityBoundaryTest.kt`、`README.md`；父维护本计划/设计状态。

- [x] **1. 版本RED/GREEN。** 只改两个版本测试到1.3/+4，12项身份测试仍在；先实际编译成功断言RED，再最小改：

```kotlin
// Version 1.3 uses local code 4, or the CI run number plus 4,
// to upgrade the delivered No-GUI 1.2/code 3 APK.
val autoVersionCode: Int = (ciRunNumber ?: 0) + 4
// defaultConfig:
versionName = "1.3"
```

保留同applicationId/namespace/SDK/AIDL/UserService协议version1、生产依赖、BT_NOGUI私密配置/78证书pin/退役证书拒绝。不得读/替换/新建私钥。

- [x] **2. README。** 更新轻量三类纯文本例子、秒级、无设备列表、SCAN消息/两个按钮行/无日志说明、短日志授权、同根文件3MiB/trim1MiB/旧行自然滚动/私有失败和强杀边界。说明controller安全记录和内部计时不因精简删除。原1.0丢失与实际1.1/1.2历史数据保持；新1.3实际artifact数据只在父核验后写。
- [x] **3. 缺配置完整test/debug/lint和release拒绝。** 临时不存在BT_NOGUI_SIGNING_PROPERTIES完整命令成功；assembleRelease同路径应在validateNoguiReleaseSigning明确拒绝，expected fail-closed不是成功release/行为RED。两阶段review，再独立整体审查全部1.3 baseline diff/new文件及安全未变。

## Task3：父签名构建与交付

- [x] **1. 冻结scope、核验原APK、两次完整强制重跑。** 每次检查实际JUnit零failure/error/skip、原安全suite数量不减、lint实际问题（原target32 Fatal/ScopedStorage不抑制，不称lint-clean）。第一份release先独占保存构建目录外再第二次：

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

- [x] **2. 新公开verifier实际aapt/apksigner/DEX/JUnit。** 用native Write/Edit建立1.3 verifier，实际1.2 input1.2/code3/hash/字节/单一78证书与备份均不变，新1.3/code4、同包同证书、SDK31/32/34、NoDisplay完整launcher/内部组件/Shizuku provider/connectedDevice、RUN/RESET_IF_PRESENT、all-files和无WRITE/INTERNET/boot。DEX精简logger/summary/feedback类存在、已退役详细wrapper不要求存在。不要沿用1.2硬编码403/旧JSON事件suite要求；维护原295安全suite和真实新行为suite覆盖，不允许测试静默不执行。
- [x] **3. 复制重验及设备边界。** 最终第二份保存`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.3.apk`和`app/build/outputs/apk/release/bt-monitor-nogui-1.3.apk`；不存在exclusive create，存在先看/hash，不覆写未知目标；copy后metadata/hash/size/cert重验。原1.2持久/备份重新核验、原输出只从实际原文件恢复，不重编冒充。adb有授权设备才做升级/数据保留/日志/授权/SCAN消息/横排和真实BLE/OEM验收，无设备逐项未执行。
- [ ] **4. 文档与Git。** 只补实际artifact/RED/GREEN/review/build/lint/无设备证据；allowlist stage，APK/私钥/cache不进git。提交末行`Co-Authored-By: Claude Code <noreply@anthropic.com>`，只push`origin bluetooth_monitor_shizuku_nogui`，ls-remote同本地SHA，clean tree。不amend/forcepush/main/GUI/PR/发布workflow。最终给实际APK路径、1.3/code4同签升级条件、简短授权步骤及真实验证边界。

## 完整无release命令

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

## 执行记录

按实际发生记录，未执行项不能标完成。用户批准后连续执行，不重复审批，不调用自动fork的review技能、不读代理JSONL；拒绝权限不可绕过或借peer代执行。

### 父交付核验准备（2026-11-27）

- 已批准设计/计划独立提交`273d1f74c2ea7996a155739648f4457acee4bc86`，仅两个文档，实施范围不变。
- 父使用native Write建立新公开verifier`/tmp/bt-nogui-1.3-verification.py`。它读取公开APK/已生成JUnit与lint以及最终审查后冻结的源码/testcase契约，不读取私密签名输入；最终构建必须符合真实1.3契约，不能沿用1.2详细JSON断言或硬编码403总数。
- `--baseline-only`实际exit0，报告`/tmp/btreset-1.3-original-public-verification.json`；实际原1.2及额外备份的包名、1.2/code3、2652533字节、原60cfa2 hash和单一7825cc证书均不变；compiled manifest NoDisplay、内部组件、RUN/广播、connectedDevice、Shizuku provider、SDK及all-files已再次核验。
- 父以实际原1.2测试新DEX class-definition解析器：1个DEX、1596个class定义，18个固定核心定义均真实存在；不是只在DEX字符串里搜索类名。这不代表尚未构建的1.3已通过。
- Task1生产/测试已冻结，父已核验24份文件hash与冻结manifest SHA256`2cca76a149a89908ce4e3fdee8b07e6acf2f2eda033393e00f5de43039880fbe`；独立规格审查进行中。质量、父fresh回归、版本和最终APK仍待实际完成后记录。

### Task1首轮冻结证据（尚未完成审查）

- 实现证据目录`/tmp/btreset-1.3-task1-20261127-editor-evidence`。父实际检查日志与XML：完整`testReleaseUnitTest assembleDebug lintDebug`exit0、26秒、73任务全部执行，28suite/416tests/0failure/error/skipped；聚焦5suite/101tests/0。不是父fresh构建，也不是正式release。
- 父逐份`git show bf045315...:<testfile>`与当前字节比较，21个独立测试文件、23个suite条目均不变：原22suite/295安全测试，以及RollingLogFileTest20。LogStorageBoundary23旧测试保留，增加mixed legacy/plaintext replay；具体新旧专用日志测试名变化见`test-name-delta.json`。
- 父独立XML核验`/tmp/btreset-1.3-task1-parent-evidence-check.json`：格式RED24/1（ComparisonFailure）；owner-seam RED92/22（2ComparisonFailure、15AssertionError、1NullPointerException、4NoSuchElementException）；corner RED48/6及scan-final RED49/2均AssertionError。各run errors/skipped0，编译成功；不能把owner全部22项描述为断言失败。父实际trace确认这5个非断言异常：ManualFeedbackTest.kt:261所需末反馈scanSummary尚未实现而为null；RuntimeAuditTest.kt:79/92/101/129在required summary尚未产生时rows.last为空。属于编译通过的未实现行为，不是fixture建立/基础设施失败；各异常类型仍原样披露。
- 实际lint XML14项：13Warning/1Fatal ExpiredTargetSdkVersion、MissingPermission0/Error0，含ScopedStorage；不是lint-clean。三个原通知setter弃用warning以及删除详细日志后新暴露的`ManualOperationController.kt:116`冗余saved初始化warning尚在。设备验收未执行。
- 一个中间GREEN新增测试误期望acceptance存储失败后的engine.read为null；实际原安全协议保留QUARANTINED sentinel。实现者改该新增断言为原隔离事实，未改既有安全策略/测试；最终全绿。审查需核对这个区别，不把错fixture修正当蓝牙逻辑修复。

### 首轮规格审查发现（修复/复审待完成）

- 已核实扫描启动失败回调时序问题：`ManualBleScanner`先发`events.returned(false)`，controller同步finished→retire，可能先把失败摘要关闭为「取消」；若独立exact cleanup已先返回，checked终态可先清owner而丢失真正末摘要/设备。父直接检查生产回调及controller路径确认；现有手工先summary再finished的fake测试不覆盖真实顺序。
- 同一边界另有事实分类问题：`Started(false,null,true)`已证明未进入注册，scanner却仅凭ticket存在标「状态未确认」。已知未执行应写「未执行」且数量/目标未知；没有真正ended/no-entry证明的超时/迟到工作仍必须保持未知，不能一律按没有entry时间就推断未执行。
- 这两项影响1.3扫描日志/反馈真实性，要求同实现者用生产使用的Started交付seam补两个cleanup顺序及known-notinvoked/unknown对照的行为RED/GREEN，原cleanup/authority/lease协议不改；规格复审PASS前不进入质量或正式打包。
- 同实现者在既有`ScanSummary.kt`抽取实际scanner使用的同步Started交付/源事实seam，机械抽取后的原聚焦101项保持GREEN；没有改controller。新加7项测试使用真实controller、checked store、retirement、exact owner；父核验修复RED目录`/tmp/btreset-1.3-task1-spec-fix-20261127-editor/red-xml`为5suite/108tests/4failure/0error/skipped，4项均AssertionError，分别重现retain→取消、cleanup先返回→丢100+/目标/设备、knownnotinvoked→unknown、entry源事实存在但nanos缺失→误未执行。超时/迟到未知、原native注册在途与新owner迟到、成功start不等扫描完成3项对照在旧行为已通过。
- 抽取先有一次public/internal类型可见性编译失败，机械修正后才跑上述真正行为RED，原日志另留；该编译失败不计RED。以下记录实际后续GREEN/full与复审，不把先前待办提前标完成。

### Task1窄修复冻结与规格复审（2026-11-27）

- 实现者补充publication异常回归：编译成功的5suite/109tests/1failure（AssertionError）RED，随后以nested finally保留原ended返回交付和exact retirement；未改controller/native协议。最终聚焦5suite/109tests、完整28suite/424tests，均0failure/error/skipped；完整无release命令exit0、19秒、73任务执行。证据`/tmp/btreset-1.3-task1-spec-fix-20261127-editor`；这仍是实现者运行，不冒充父fresh构建。
- 父独立核验实际XML、RED异常类型、24个当前源码hash及`git diff --check`，记录`/tmp/btreset-1.3-task1-spec-fix-parent-evidence-check.json`。新冻结manifest SHA256`a81b7bd63a0504a690e0b0e8226f195d7ab9c9aaffd85f20478eef6fe6a69890`；与初冻结只有`ManualBleScanner.kt`、`ScanSummary.kt`、`ManualFeedbackTest.kt`3文件改变，另21不变。
- 同独立规格审查者实际复审返回SPEC PASS：真实scanner调用Started同步交付seam，失败末摘要/设备先于可重入returned终态；captured entry或真实entry返回证明执行，ended/null-return/no-capture证明未执行，未结束且无entry仍未知。两个原finding均关闭，没有新verified规格缺陷；直接读取真实controller/store/retirement/exact-owner回归，验证exception finally、迟到/新owner及成功注册不是扫描完成。
- 原295安全case与Rolling20仍字节不变并全部执行；ManualFeedback保留原24项加8项。规格审查未改文件/跑Gradle/读秘密/委派；24hash在审查结束仍匹配。已开始fresh独立质量审查；Task1总checkbox暂不标完成，版本/正式APK仍待执行。
- 父在无其它Gradle owner时fresh强制完整`testReleaseUnitTest assembleDebug lintDebug`，指定先核验不存在的临时签名配置；实际exit0、18秒、73任务全部执行。`/tmp/btreset-1.3-parent-task1-iTh7cS/full.log`、`full-xml`、`lint.xml`及`verification.json`保留：28suite/424tests/0failure/error/skipped，全部case names与规格审查冻结精确相同；24源码hash及23原安全/滚动suite条目的baseline字节仍一致，`git diff --check`通过。lint仍14项（13Warning、1Fatal ExpiredTargetSdkVersion、Error0/MissingPermission0），原通知弃用与saved冗余warning如实保留；未构建release，未做设备验收。
- fresh独立质量审查返回Quality APPROVED，无verified Critical/Important或新actionable Minor。实际阅读24冻结路径及必要未改接线，独立验证21测试文件（295安全case+Rolling20）和20核心接线文件字节与baseline一致、原Storage23名称保留及mixed replay新增；24hash和manifest SHA在结束匹配。冗余initializer仅已披露非阻塞warning，不夹带清理。审查只读，无Gradle/委派/秘密读取。规格、质量与父fresh回归三门均完成，Task1标完成；版本和正式APK仍是后续任务。
- 父在核对冻结源码、fresh零失败报告和exact允许列表后提交Task1：`a5bba544404e14ef4e13c04adc0b9b7662fdaaa1`，25路径（24生产/测试+本执行计划），提交后工作树clean。未push；fresh单一Task2实现者开始版本/README任务，父不并行跑Gradle或修改其3路径。
- 父以实际原1.2 DEX class-definition确认`AuditRow`、`LogDropRange`确实存在；最终1.3契约将要求精简typed/summary/helper实际定义并禁止这两个退役定义，不误禁仍复用类名的小型`RuntimeAudit`。这仅为公开verifier准备，不冒充最终1.3 APK已验证。

### Task2版本RED（后续回归/审查待完成）

- 父独立读取`/tmp/btreset-task2-1.3-FQeRQ4ax/red-junit`与`red.log`：身份12项全部执行，2failure/0error/skipped，单位测试编译成功后才在test任务失败。真实类型为+4预期的`java.lang.AssertionError`（line31）和1.3版本预期的`org.junit.ComparisonFailure`（line54），不是编译/基础设施故障。
- 父首次证据脚本错把两个失败都要求完全相同的AssertionError类型而自检失败；修正为保留上述两个实际类型后核验通过。未因此修改生产/测试；原12个case names保留。实现者继续GREEN/完整缺配置回归及明确release fail-closed，父不并行Gradle。
- Task2实现者完成并冻结3路径：build仅1.3/+4和对应注释，身份保留12个名称与另10项原正文，README更新轻量描述并保留真实1.1/1.2历史与1.0遗失。父独立检查源hash及实际GREEN/full XML：聚焦12/0，完整28suite/424tests/0failure/error/skipped、19秒/73任务全执行；424 case names与Task1父fresh报告完全一致。Task1的24文件hash全部仍匹配，`git diff --check`通过。
- 独立缺配置release真实exit1，在`validateNoguiReleaseSigning`明确报告`private signing properties are unavailable`，没有成功packageRelease。完整缺配置test/debug/lint实际exit0，lint仍14项（13Warning/1Fatal ExpiredTargetSdkVersion、MissingPermission0/Error0），不是lint-clean。父证据`/tmp/btreset-1.3-task2-parent-evidence-check.json`；已开始fresh规格审查，质量/整体/两次正式签名构建仍待完成。
- Task2首轮SPEC FAIL仅README扫描说明：合并自动/手动描述未明确唯一地址去重计数、100+只适用手动保留上限，以及曾观察目标在超限/名称变化后仍锁存。父核对README95、自动seenAddresses计数和DiscoveredDevices原逻辑确认；自动101个唯一设备实际是101而非100+。交同实现者只做README事实澄清，不改已通过Task1的集合/策略或测试；规格复审前不进入质量。
- 同实现者只改README95，父读实际一行diff并核验新README SHA256`a8b232b69cd0b3ee1c8f900c21a249222c3a039f0ab7c33cca1dd3186e3ff04a`，build/身份/24Task1文件hash不变。该文档修正未由实现者重跑Gradle，不冒充fresh；同规格审查者直接复审返回SPEC PASS，唯一finding关闭、无剩余Task2规格缺口，3路径和冻结副本hash匹配。已开始fresh独立质量审查。
- 父随后fresh完整强制无release回归：`/tmp/btreset-1.3-parent-task2-U2u1MC/full.log`、`full-xml`、`lint.xml`与`verification.json`，exit0、19秒、73任务全执行、28suite/424tests/0failure/error/skipped，全部case names匹配冻结；27源码/README/hash不变，diff-check通过。lint仍14项（13Warning/1Fatal、MissingPermission0/Error0），未构建正式release/未做设备验收；以下记录后续审查。
- fresh独立Task2质量审查返回Quality APPROVED，无Critical/Important/Minor。直接读3实际路径与未改接线，确认最小版本diff、原12身份名称/10其余正文以及全424声明名称不变；README准确轻量/存储/UI/100+语义和历史/未完成交付边界。3hash在审查开始/结束均匹配，无写入/Gradle/委派/秘密读取。已派fresh最终整体只读审查全部baseline→working-tree1.3改动；该结果前不开始正式签名构建。
- fresh最终整体审查返回FINAL READY，无verified Critical/Important或新actionable Minor。实际审查完整29路径（19生产、6测试、build、README、2批准文档），45核心/资源/build/wrapper/CI路径与baseline字节相同；22suite/295安全case及Rolling20仍覆盖，身份仅两项版本正文更新。独立核验父28suite/424tests全部名称与现源码声明吻合、0failure/error/skipped；27冻结hash及manifest SHA在结束匹配，diff-check通过。只读，无Gradle/委派/秘密读取。Task2完成，接下来才开始Task3两次正式签名构建与实际交付。
- 正式构建前父再次`--baseline-only`核验真实原1.2、额外备份及1.1：`/tmp/btreset-1.3-before-final-originals-jm5rsF.json`exit0，原版本/code/字节/hash/单一78证书与编译manifest均不变。

### Task3正式构建与真实APK核验（2026-11-27）

- Task2允许列表提交`a39f9ebdb1df225b574b29193acb384eee953a90`后工作树clean。最终整体审查通过后生成实际公开契约`/tmp/btreset-1.3-reviewed-contract.json`，SHA256`c21f5d0c20198f025cb5e592af4f461f4eeb3ce1a0221f1f206fd6ac22eaa138`：88个编译源码/测试/资源/build/CI路径、28suite的全部424准确case names、28必需DEX定义以及退役AuditRow/LogDropRange定义禁止。README产物事实和执行记录不参与编译契约，允许在真实核验后仅补事实；源码/测试不再修改。
- 父两次fresh强制完整`testReleaseUnitTest assembleDebug assembleRelease lintDebug`，每次实际exit0、17秒、97任务全执行，每次28suite/424tests/0failure/error/skipped；两个signing验证任务和packageRelease/assembleRelease均成功。现有私密配置只在Gradle内部使用，没有读JKS/口令到模型。证据`/tmp/btreset-1.3-parent-signed-Owleul/first-build.log`、`second-build.log`及两套JUnit/lint。
- 第一份签名APK先exclusive保存构建目录外`/tmp/btreset-1.3-parent-signed-Owleul/first-signed.apk`，2621153字节、hash`0ec4d1afdbb955ca0ebf69a5eb5fae0a36c3854051926b08eb238fdf86dabb5d`；第二次构建后重新inspect仍为原字节/metadata。最终第二份2621153字节、hash`208170e2dc9567f48c0f539587dc59d0fe857e02d7e85134b764929ac5dee16b`；两份actual1.3/code4、同包、同单一7825cc证书/v2验证、全部SDK与compiled manifest条件通过，字节无需可复现。
- 新公开verifier两次exit0：`first-verification.json`、`second-verification.json`。实际aapt完整NoDisplay launcher/内部组件/RUN/RESET_IF_PRESENT/connectedDevice/Shizuku provider/all-files及无WRITE/INTERNET/boot，真实DEX定义和全部准确JUnit名称通过；88冻结源码hash仍一致。lint每次14项：13Warning/1Fatal ExpiredTargetSdkVersion、MissingPermission0/Error0，不是lint-clean；3原通知setter弃用和saved冗余编译warning仍保留。
- 最终第二份exclusive复制并逐份inspect/hash/size/cert核验为`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.3.apk`和`app/build/outputs/apk/release/bt-monitor-nogui-1.3.apk`。原1.2及额外备份、原1.1全部保持原字节/hash/identity，两个旧输出副本只从实际原文件恢复，没有重编旧版。新旧实际包/单一证书同一，code3→4符合覆盖升级身份条件。
- 本次actual `adb devices -l`为空，证据`/tmp/btreset-1.3-parent-signed-Owleul/adb-devices.txt`：0连接/0授权设备。真机覆盖安装/数据权限保留、BLE、Toast、窄屏大字体布局、日志授权撤销恢复/根文件写入裁剪、fooView热启动、Shizuku/普通后备/OEM、现场历史累计均未执行，JVM/source/APK不替代。`delivery-summary.json`保存真实两份artifact/契约/JUnit/lint及身份核验；设备空列表不是设备通过。
- 父仅补README实际1.3产物/签名/两次构建/原文件保留/无设备边界和1.3记录链接，未改编译源码。当前尚待最后文档提交与分支push/远端SHA、clean tree核验，Task3总checkbox及Git步骤暂不标完成。
