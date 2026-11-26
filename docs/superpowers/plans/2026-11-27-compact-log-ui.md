# 1.3 紧凑日志与横排按钮 Implementation Plan

> **For agentic workers:** 使用`superpowers:subagent-driven-development`。每项任务单一实现writer/Gradle owner，先可执行RED/GREEN，再规格→质量审查；已批准，不增加确认门。所有项目改动用native Write/Edit。

**Goal:** 秒级纯文本三类摘要日志；扫描结果改消息；操作/设置按钮横排；同签1.3/code4 APK及当前分支推送。
**Architecture:** 大幅删除1.2通用事件捕获和生产观察调用，保留单一有界后台滚动存储和原蓝牙权威协议。扫描摘要只观察现有有效广告，开关摘要只读取原权威结果；不实现另一套成功/恢复判断。
**Tech Stack:** Kotlin1.9.24、AGP8.4、Gradle8.7、JDK17、SDK31/32/34、Shizuku13.1.5、JUnit4；生产依赖不变。

## 已批准范围与基线

设计：[2026-11-27-compact-log-ui-design.md](../specs/2026-11-27-compact-log-ui-design.md)，用户实际回复「OK」。现有worktree`/home/admin/git/bluetooth_monitor_shizuku`，分支`bluetooth_monitor_shizuku_nogui`，功能基线`bf045315364af7d1f39c4744dabf047474693aef`。

已实际aapt/apksigner/hash核验原1.2：包名`com.blreay.bluetoothmonitor.nogui`、1.2/code3、2652533字节、APK SHA256`60cfa2fffcc65ae6ad1197855a27e02dde23e1b22fd79211f65872bc0f3d497d`、单一证书`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。持久`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.2.apk`；额外构建目录外备份`/tmp/btreset-1.3-baseline-sqj_26dn/original-delivered-1.2.apk`，字节/hash已核验。旧1.0遗失不重开，也不删除原1.1/1.2。

- [x] 探索、轻量设计与真实用户批准；保存实际1.2升级输入。
- [ ] Task1：紧凑日志、实际摘要、SCAN消息和横排UI；规格/质量/父回归。
- [ ] Task2：1.3身份/说明、缺签名拒绝、规格/质量及最终整体审查。
- [ ] Task3：父两次签名构建、实际原/新APK核验、外部保存、当前分支提交推送。

## Task1：一个cohesive日志/UI实现任务

**Modify:** `app/src/main/java/com/blreay/bluetoothmonitor/`下`AuditLog.kt`、`BoundedLogWriter.kt`、`MibandLog.kt`、`LogStatusNotification.kt`（仅状态DTO适配，如需）、`RuntimeAudit.kt`（删除详细backend装饰，可替换为小型摘要helper）、`MonitorApplication.kt`、`MainActivity.kt`、`TriggerReceiver.kt`、`ReEnableReceiver.kt`、`ResetService.kt`、`ResetCoordinator.kt`、`BandResetController.kt`、`ManualBleScanner.kt`、`ManualOperationController.kt`、`ManualFeedback.kt`、`PermissionActivity.kt`、`DurableResetEngine.kt`、`RuntimeOwnership.kt`。`RollingLogFile.kt`只允许必要的legacy单行限额引用解耦，滚动/异常算法不重写。最多一个额外小型`ScanSummary.kt`或等价纯观察helper。

**Test:** 更新1.2专用`AuditLogTest.kt`、`BoundedLogWriterTest.kt`、`RuntimeAuditTest.kt`、`ManualFeedbackTest.kt`的已被1.3取代的格式/详细事件/UI断言，保留相关有限行/异常/去重/authority测试；`RollingLogFileTest.kt`、`LogStorageBoundaryTest.kt`滚动/权限/导出/故障断言不放宽。扩展实际manual/auto扫描seam测试。原295基线安全测试保留，不因新日志降低蓝牙约束；旧UI源码断言确实与新横排/删SCAN卡冲突时只改该UI断言，保持其它断言与测试数量。

- [ ] **1. 先建立最小类型和实际行为RED。** 建议接口如下；允许等价小调整，但报告实际API，不引入字段map/通用event框架：

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

- [ ] **2. 简化capture/writer并删除详细hooks。** capture只秒级本地日期时间+固定描述，队列存String；默认小队列64、每批16，单一scheduled drain。删session/sequence/eventId/elapsed/source/receipt和log.dropped记录，drop数量可只在cached状态。保留scheduler/storage/onStatus异常隔离、私有原行导出和ACK失败可回放。legacy旧行允许原8KiB限额，新行512；不要因新格式把旧pending当损坏、清空旧根文件或增加转换器。删除Main/receiver/service/coordinator/scan/cleanup/engine的详细事件和RuntimeAudit backend wrapper；执行回原backend，完整保留原Observed invocation/状态读/命令策略，不能把observer当checked backend history。

- [ ] **3. 真实scan摘要。** 主进程guard后process.start改成唯一ProcessStarted。自动run/emit/cancel各真实结束路径最多一个摘要，复用现有去重count并记住已观察目标，目标出现不等于claim/arm成功。手动使用小型annotation-only摘要累计器或现有collector，不加无界集合；目标在每个有效当前广告、保留上限判断之前锁存，之后名字更新不撤销。建议给ManualScanEvents增加有默认body的`summary(value: ScanSummary)`，原必需方法不变；finish和silent retire共用一次close-observation摘要，不让retire递归events.finished。controller按exact operation/command接受末摘要，serviceEnded已pre-mark scanFinished时也不能丢该摘要；完成Toast仍需要原exact retirement/checked terminal，不让摘要释放占用。保留内部观察时钟和8秒准入。

- [ ] **4. 真实ON/OFF摘要。** 自动成功OFF在原engine.observe实际OFF→ON handoff，成功ON在原RESTORED terminal结果；manual在原checked finish/quarantine/window/storage失败。SUBMITTED只说明已提交，不能成功；authority未知即使目标硬件已到也要注明未知。从当前exact command原backend history/真实结果读取实际mode，普通后备不能借旧mode；特别是engine.prepare旋转到ON_READY但lastMode仍保留OFF的情况，不能借前阶段推断ON模式。no-op表示未执行。失败/未知/迟到改善按原事实写简短结果。最多小型有界每命令观察去重，防重复采样/同失败事务回调产生重复行；不额外采样、不加计数/恢复判断、不新增policy/store schema/持久logger identity。

- [ ] **5. UI/SCAN反馈。** SCAN正常终态消息含去重数量/100+、目标有/未见；失败/未知/保存失败消息如实，保留同操作同类去重、独立拒绝，不从snapshot/recreate/close重播、不每广告Toast。coordinator不再过滤成功SCAN terminal。删除manualBodies[SCAN]和整个结果渲染consumer，但DetailsText/manual持久结果保持供安全/旧契约。删除logBody/renderLogStatus/每秒cache重绘，仅onResume异步refresh及显式短标签「日志权限」按钮保留，settings fallback不变。两个row均horizontal+equalweight，三个button和两个settingsbutton分行；其它自动状态/历史/累计/权限/恢复保持，不重建dialog重置滚动或改NoDisplay入口。

- [ ] **6. 聚焦GREEN、完整回归、审查。** 保存生产使用seam行为：scan目标在101st设备仍记住、重复/后续名变化不丢；取消/failedstart/retire/超时/latecleanup有摘要且不解fence；actualswitch mode/result/unknown/no-op/fallback/checkedfail；logger/Toast抛异常不改变蓝牙；旧行字节导出、无第四类日志。source仅验证UIrow/删卡删说明/guard与删除旧详细hooks，不替代行为。单一Gradle owner运行完整无release命令，规格PASS后质量审查，Important同implementer红绿修/同reviewer复审，父独立完整回归后Task1完成。

## Task2：版本与说明

**Modify:** `app/build.gradle.kts`、`app/src/test/java/com/blreay/bluetoothmonitor/AppIdentityBoundaryTest.kt`、`README.md`；父维护本计划/设计状态。

- [ ] **1. 版本RED/GREEN。** 只改两个版本测试到1.3/+4，12项身份测试仍在；先实际编译成功断言RED，再最小改：

```kotlin
// Version 1.3 uses local code 4, or the CI run number plus 4,
// to upgrade the delivered No-GUI 1.2/code 3 APK.
val autoVersionCode: Int = (ciRunNumber ?: 0) + 4
// defaultConfig:
versionName = "1.3"
```

保留同applicationId/namespace/SDK/AIDL/UserService协议version1、生产依赖、BT_NOGUI私密配置/78证书pin/退役证书拒绝。不得读/替换/新建私钥。

- [ ] **2. README。** 更新轻量三类纯文本例子、秒级、无设备列表、SCAN消息/两个按钮行/无日志说明、短日志授权、同根文件3MiB/trim1MiB/旧行自然滚动/私有失败和强杀边界。说明controller安全记录和内部计时不因精简删除。原1.0丢失与实际1.1/1.2历史数据保持；新1.3实际artifact数据只在父核验后写。
- [ ] **3. 缺配置完整test/debug/lint和release拒绝。** 临时不存在BT_NOGUI_SIGNING_PROPERTIES完整命令成功；assembleRelease同路径应在validateNoguiReleaseSigning明确拒绝，expected fail-closed不是成功release/行为RED。两阶段review，再独立整体审查全部1.3 baseline diff/new文件及安全未变。

## Task3：父签名构建与交付

- [ ] **1. 冻结scope、核验原APK、两次完整强制重跑。** 每次检查实际JUnit零failure/error/skip、原安全suite数量不减、lint实际问题（原target32 Fatal/ScopedStorage不抑制，不称lint-clean）。第一份release先独占保存构建目录外再第二次：

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

- [ ] **2. 新公开verifier实际aapt/apksigner/DEX/JUnit。** 用native Write/Edit建立1.3 verifier，实际1.2 input1.2/code3/hash/字节/单一78证书与备份均不变，新1.3/code4、同包同证书、SDK31/32/34、NoDisplay完整launcher/内部组件/Shizuku provider/connectedDevice、RUN/RESET_IF_PRESENT、all-files和无WRITE/INTERNET/boot。DEX精简logger/summary/feedback类存在、已退役详细wrapper不要求存在。不要沿用1.2硬编码403/旧JSON事件suite要求；维护原295安全suite和真实新行为suite覆盖，不允许测试静默不执行。
- [ ] **3. 复制重验及设备边界。** 最终第二份保存`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.3.apk`和`app/build/outputs/apk/release/bt-monitor-nogui-1.3.apk`；不存在exclusive create，存在先看/hash，不覆写未知目标；copy后metadata/hash/size/cert重验。原1.2持久/备份重新核验、原输出只从实际原文件恢复，不重编冒充。adb有授权设备才做升级/数据保留/日志/授权/SCAN消息/横排和真实BLE/OEM验收，无设备逐项未执行。
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
