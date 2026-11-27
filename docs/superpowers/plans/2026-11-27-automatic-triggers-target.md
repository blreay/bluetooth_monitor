# 自主触发、持久设置与可编辑目标 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: 使用`superpowers:subagent-driven-development`。每项任务单一fresh实现writer/Gradle owner，先可执行行为RED/GREEN，规格PASS后质量审查；Important同实现者修复、同审查者复审。用户已确认普通方案可继续和完整名称精确匹配，按既有偏好连续完成至同签APK与当前分支推送，不追加“是否继续”门。项目编辑只用native Write/Edit。

**Goal:** 应用自主定时/亮屏/息屏扫描，持久保存任意触发组合、默认三分钟间隔与可编辑目标名称，普通锁屏尽力运行。

**Architecture:** 独立监控FGS的真实存活能力借给exact单次工作；新配置/周期alarm事务与原安全恢复分离。每次接纳冻结完整名称，自动使用真实名称ScanFilter，所有开关仍经原协调器、retirement、checked恢复和Shizuku驱动。

**Tech Stack:** Kotlin1.9.24、AGP8.4、Gradle8.7、JDK17、SDK31/32/34、Shizuku13.1.5、JUnit4；无新增生产依赖。

---

## 已确认设计、基线与执行约束

设计：`docs/superpowers/specs/2026-11-27-automatic-triggers-target-design.md`。用户真实回复「可以继续吧」，补充持久设置和可编辑设备名称；AskUserQuestion真实选择「完整名称精确匹配（推荐）」。名称保存原文而非trim后冒充广播原文；所有空白、大小写有意义。默认自动OFF、定时选中、两个屏幕项OFF、3分钟、原完整手环名；开启时至少一项、间隔1—1440。

基线`f88434dbf9f91c921827f40f315489f8f04f002f`，仅原用户`?? design.md`存在。该文件不覆盖、不提交。所有命令留在现有worktree `/home/admin/git/bluetooth_monitor_shizuku`，不创建新worktree、原checkout、bare stash、main/GUI修改、PR或workflow。

父已实际aapt/apksigner/hash核验原1.3/code4：2621153字节，hash`208170e2dc9567f48c0f539587dc59d0fe857e02d7e85134b764929ac5dee16b`，单一证书`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`；保存额外真实副本`/tmp/btreset-1.4-baseline-_326f3mi/original-delivered-1.3.apk`，报告`public-verification.json`。真实1.2/1.1也再次核验，未重编冒充旧文件。`source-and-prior-test-contract.json`冻结88源码及原28suite/424准确case名称；该报告是旧生成报告核验，不是本轮fresh测试。

私密签名路径保持；只允许Gradle内部使用，不Read/打印/生成/替换JKS或口令。原native/SDK固定命令、EOF、single-slot、quarantine、arm-before-OFF、exact identity和cleanup不得为可用性放宽。编译/fixture/基础设施错误不是行为RED。无设备不标锁屏/Doze/BLE/升级通过。

- [x] 探索普通方案、现有安全接线和新的配置/候选接口；用户确认。
- [x] 设计自查：区分锁屏与Doze、真实宿主与enabled、原文名称与trim展示、周期alarm与恢复alarm、当前候选与历史、设置failure与ledger failure。
- [ ] Task1：纯配置/周期ticket/精确名称/本次候选seam，RED/GREEN及规格/质量。
- [ ] Task2：单一cohesive runtime/FGS/扫描/UI整合，RED/GREEN及规格/质量/父fresh。
- [ ] Task3：1.4/code5、README、缺签名拒绝、审查和最终整体审查。
- [ ] Task4：父两次正式构建、实际APK/原文件核验、保存、attributed commit/push和真实结果报告。

## Task1：可独立测试的配置、alarm代际和候选

**Create:** `app/src/main/java/com/blreay/bluetoothmonitor/AutomaticMonitorSettings.kt`、`AutomaticMonitorScheduler.kt`（纯决策与port，Android绑定留Task2）、`ManualScanCandidates.kt`。

**Test:** `app/src/test/java/com/blreay/bluetoothmonitor/AutomaticMonitorSettingsTest.kt`、`AutomaticMonitorSchedulerTest.kt`、`ManualScanCandidatesTest.kt`。

Task1不改当前Android扫描/服务/UI或原ledger/driver/SDK。允许小型等价分文件/命名，但必须报告实际API，下一任务使用同一套而非复制另一策略。以下是生产使用的seam合同，不能只在测试中建立fake实现：

```kotlin
data class AutomaticMonitorSettings(
    val revision: Long = 0,
    val enabled: Boolean = false,
    val timerEnabled: Boolean = true,
    val screenOnEnabled: Boolean = false,
    val screenOffEnabled: Boolean = false,
    val intervalMinutes: Int = 3,
    val targetName: String = "Xiaomi Smart Band 8 35BA",
)
data class TargetSnapshot(val settingsRevision: Long, val advertisementName: String) {
    fun matches(currentAdvertisementName: String?) =
        currentAdvertisementName == advertisementName
}
enum class AutomaticTrigger { TIMER, SCREEN_ON, SCREEN_OFF }
data class MonitorAlarmTicket(val settingsRevision: Long, val generation: Long)
data class AutomaticMonitorRecord(
    val settings: AutomaticMonitorSettings,
    val alarmGeneration: Long = 0,
    val pendingAlarm: MonitorAlarmTicket? = null,
)
sealed interface AutomaticSettingsRead {
    data object Missing : AutomaticSettingsRead
    data class Valid(val record: AutomaticMonitorRecord) : AutomaticSettingsRead
    data class Invalid(val reason: String) : AutomaticSettingsRead
}
interface AutomaticSettingsPersistence {
    fun read(): AutomaticSettingsRead
    fun commit(record: AutomaticMonitorRecord): Boolean
}
interface MonitorAlarmPort {
    fun arm(ticket: MonitorAlarmTicket, elapsedAt: Long): Boolean
    fun cancel(ticket: MonitorAlarmTicket)
}
```

store必须区分用户settings revision与周期generation；UI保存整体设置+新的周期ticket、timer消费后原子旋转ticket。false commit不能读回eager cache确认；纯store保留last-confirmed、独立fault和explicit save结果。仅用户显式有效重存能修复配置故障，不清旧ledger/native隔离。Task2的Android适配器实现相同port。

- [ ] **1. 写可编译行为RED和最小合同。** 测试默认值、全部7种非空组合、masterOFF仍保留选择/间隔；1和1440有效、0/1441/空目标/ON零触发无效；原文空白大小写精确；expected revision conflict；false/throw eager cache；invalid不是Missing。

```kotlin
@Test fun `failed eager commit cannot publish unsaved target`() {
    val initial = AutomaticMonitorRecord(AutomaticMonitorSettings())
    val persistence = FakeAutomaticPersistence(initial)
    val store = CheckedAutomaticSettingsStore(persistence)
    persistence.failAfterMutatingCache = true
    val result = store.save(0, initial.settings.copy(targetName = "Other Band"))
    assertFalse(result.saved)
    assertEquals("Xiaomi Smart Band 8 35BA", store.snapshot().record!!.settings.targetName)
    assertTrue(store.snapshot().blocked)
}
@Test fun `exact target uses only this packet name`() {
    val target = TargetSnapshot(7, "Other Band")
    assertTrue(target.matches("Other Band"))
    assertFalse(target.matches("Other Band extra"))
    assertFalse(target.matches("other band"))
    assertFalse(target.matches(null))
    assertFalse(target.matches("Xiaomi Smart Band 8 35BA"))
    assertFalse(TargetSnapshot(7, " Name ").matches("Name"))
}
```

`FakeAutomaticPersistence`实现上述read/commit并可在返回false前改自己的cache；store公开snapshot包含确认record/blocked，save返回saved和明确INVALID/CONFLICT/WRITE_FAILED/UNCHANGED/SAVED状态。先提供能编译但保留旧/未实现行为的最小seam，再执行真正断言RED；不能把缺symbol当RED。

- [ ] **2. 实现checked record与验证。** init仅Missing使用默认record；Invalid/throw屏蔽新自动任务。save(expectedRevision,draft)先完整验证，revision+generation有限单调递增（溢出拒绝）；有效变化一次commit全record，成功才发布。无变化且健康返回UNCHANGED。周期ticket属于本record，不能同时存在于关闭timer的record。literal target非blank；UI单行和有界长度限制可在draft验证统一实现，不得静默trim实际目标。

- [ ] **3. 写周期顺序/重复与自动组合RED。** 本次timer只有expected pendingAlarm才可消费；先commit新generation，再arm下一次，arm成功才尝试dispatch；busy/cooldown也须advance但不排队。settings变更或重复旧ticket不能再arm/dispatch。自动cooldown仅accepted事件更新，30秒；不影响manual/launcher。使用可记录调用顺序的port：

```kotlin
@Test fun `next alarm commit and arm precede dispatch`() {
    val events = mutableListOf<String>()
    val fixture = schedulerFixture(events)
    fixture.deliverCurrentTimer(now = 180_000L, busy = false)
    assertEquals(listOf("commit-next", "arm-next", "dispatch"), events)
    events.clear()
    fixture.deliverPreviousTimer(now = 180_001L)
    assertTrue(events.isEmpty())
}
@Test fun `busy timer advances without backlog`() {
    val events = mutableListOf<String>()
    val fixture = schedulerFixture(events)
    fixture.deliverCurrentTimer(now = 180_000L, busy = true)
    assertEquals(listOf("commit-next", "arm-next"), events)
    fixture.becomeIdle()
    assertEquals(listOf("commit-next", "arm-next"), events)
}
```

fixture组合真实store+真实scheduler+fake ports而非测试专用策略；测试选择源、禁用、invalid/write failure、arm failure、generation overflow、clock边界、resume不补齐N次、屏幕不改timer、周期cancel只调用自己的ticket。`MonitorAlarmTicket`不可只靠重复覆盖extras做身份。

- [ ] **4. 写本次候选行为RED/GREEN。** 候选组件begin(operationId,commandId)、update(exact identity,devices)、close(exact identity,finalDevices)、snapshot，独立保留terminal末数据；新SCAN替换而rejected/ON/OFF不clear，旧回调不覆盖。数据仅传真实本次`advertisementName`与MAC，不从store hydration。

```kotlin
@Test fun `terminal choices survive live result clearing but stale scan cannot replace them`() {
    val candidates = ManualScanCandidates()
    candidates.begin("scan-1", "cmd-1")
    candidates.close("scan-1", "cmd-1", listOf(candidate("AA:BB:CC:DD:EE:01", "Target")))
    assertEquals(listOf("Target"), candidates.snapshot()!!.devices.mapNotNull { it.advertisementName })
    candidates.begin("scan-2", "cmd-2")
    candidates.update("scan-1", "cmd-1", listOf(candidate("AA:BB:CC:DD:EE:02", "Old")))
    assertTrue(candidates.snapshot()!!.devices.isEmpty())
}
```

device candidate可复用原`DiscoveredDevices.Device`或一个包含mac/nullable原始name的DTO，不能把`name`占位当advertisementName；保留最多100，不建额外无界集合。只有schema真实观察名`未知设备`可保存为名称；null placeholder不行。测试原始空白、重复地址、同名、cap、throwing observer隔离和cold empty。

- [ ] **5. 单一Gradle聚焦GREEN/完整回归与两审。** 此阶段原88源码hash应仅允许本任务新文件（无旧文件改动）；原424准确case仍全执行。实现者冻结hash、case差异和真实RED/GREEN XML/log；规格PASS再质量，父核验后attributed exact allowlist提交。

```bash
source /home/admin/.cache/btreset-build/env.sh
# 每轮先确认临时配置路径不存在。
BT_NOGUI_SIGNING_PROPERTIES=/tmp/btreset-1.4-task1-no-signing.properties \
 "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku testReleaseUnitTest \
 --tests '*AutomaticMonitorSettingsTest' --tests '*AutomaticMonitorSchedulerTest' \
 --tests '*ManualScanCandidatesTest' --rerun-tasks --stacktrace --console=plain \
 --max-workers=2 --no-parallel
```

## Task2：单一cohesive监控/扫描/详情整合

**Create:** 同生产目录`MonitorService.kt`、`AutomaticMonitorController.kt`、`MonitorAlarmReceiver.kt`、`AutomaticSettingsPreferences.kt`、`MonitorHostedWork.kt`（host/capability/window纯合同可与RuntimeOwnership分工，不建第二协调器）；Android alarm适配可放`AutomaticMonitorScheduler.kt`的独立适配类或新`AndroidMonitorAlarm.kt`。

**Modify:** `ResetCoordinator.kt`、`RuntimeOwnership.kt`、`ResetService.kt`（仅common绑定接线，原exact生命周期不改）、`BandResetController.kt`、`ManualOperationController.kt`、`ManualBleScanner.kt`（raw名称、候选/summary注入，手动不引入reset策略）、`DiscoveredDevices.kt`、`ScanSummary.kt`、`PermissionActivity.kt`、`RunNotification.kt`、`MonitorApplication.kt`（如确需主进程初始化接线）、`app/src/main/AndroidManifest.xml`。

**Test:** 新`MonitorHostTest.kt`、`AutomaticTriggerRuntimeTest.kt`、`AutomaticTargetScanTest.kt`、`AutomaticSettingsUiTest.kt`或等价；扩展原`ForegroundAdmissionTest.kt`、`HeadlessRuntimeTest.kt`、`ManualFeedbackTest.kt`、`DiscoveredDevicesTest.kt`、`DetailsSnapshotTest.kt`与必要source guard。原driver/native/SDK/engine/retirement/ledger测试正文不放宽；不改生产依赖、AIDL、UserService协议、恢复算法或永久Gradle设置。

- [ ] **1. 抽生产使用的opaque host与exact child seam，跑机械保行为基线，再行为RED。** 不把idle host设置成automatic owner/serviceDone。建议合同：

```kotlin
data class MonitorChildTicket(
    val operation: ForegroundAdmission.Ticket,
    val hostSession: String,
    val childToken: String,
)
sealed interface OperationForegroundBinding {
    data class ResetServiceOwner(
        val operation: ForegroundAdmission.Ticket,
        val originalStartId: Int,
    ) : OperationForegroundBinding
    data class MonitorChild(val child: MonitorChildTicket) : OperationForegroundBinding
}
interface MonitorHostPort {
    fun acquireChild(ticket: ForegroundAdmission.Ticket): MonitorChildTicket?
    fun ownsChild(child: MonitorChildTicket): Boolean
    fun releaseChild(child: MonitorChildTicket)
}
```

`MonitorHostCapability`只在actual startForeground成功后由当前service instance注册，process-local、non-Parcelable、不可由Intent或持久enabled制造；精确撤销不影响replacement。coordinator保留原serviceStarted/serviceGone(ticket,originalStartId)协议，common operation-binding安装按精确owner/token/ticket。每个child一份bounded lease+completion，失败释放自己的child不盗旧owner。

```kotlin
@Test fun `idle host is not an operation and screen trigger starts no second FGS`() {
    val fixture = realCoordinatorHostFixture()
    fixture.registerVerifiedIdleHost()
    assertTrue(fixture.manualAdmissionAllowed())
    fixture.screenOff()
    assertEquals(0, fixture.additionalForegroundServiceStarts)
    assertEquals(1, fixture.acceptedAutomaticChildren)
    assertFalse(fixture.manualAdmissionAllowed())
}
@Test fun `stale host destruction cannot revoke replacement`() {
    val fixture = realCoordinatorHostFixture()
    val old = fixture.registerVerifiedIdleHost()
    fixture.revoke(old)
    val replacement = fixture.registerVerifiedIdleHost()
    fixture.revoke(old)
    assertTrue(fixture.hostIsValid(replacement))
}
```

fixture驱动真实production host/coordinator seam；不是只测fake期望。覆盖wrong child/run/host/token、4秒迟到准入、inline ready、exact startId、failed/hung/late scanner retirement和旧completion不结束replacement。原ResetService仍START_NOT_STICKY、空/过期intent停止，serviceDone仅pending/accepted operation。

- [ ] **2. 接监控service、短WakeLock和drain。** 非exported connectedDevice，独立静默ongoing通知ID106/channel（不复用100—105或runId history）。合法可见机会或validated exact-alarm尝试启动，FGS被系统拒绝时如实保留失败，不启动后台Activity。service start ticket、successful foreground、当前capability和lease均须真实核验。监控服务默认START_NOT_STICKY；timer可提供冷重建机会，仅屏幕组合在进程死亡后不承诺复活。

每次自动扫描整体窗口建议15秒，内部仍8秒；每个recoveryStep新15秒bounded work window；manual45秒/scan8秒不变。`HostedWorkWindow`通过当前exact child取得有限CommandLease和带timeout的release-once PARTIAL_WAKE_LOCK；无child/idle/draining不得发新工作。serviceTick和alarm重入仍tickBusy/CompletionGate单飞，禁止以`CommandLease.foreground()`无限lease替代monitor work窗口。锁超时释放锁/禁新work，独立接纳late proof，不清authority或scanner fence。

```kotlin
@Test fun `disable cancels periodic alarm but lets accepted restoration finish`() {
    val fixture = realCoordinatorHostFixture()
    fixture.beginAcceptedOffToOn()
    fixture.saveMonitoringEnabled(false)
    assertTrue(fixture.periodicAlarmCancelled)
    assertFalse(fixture.recoveryAlarmCancelled)
    assertTrue(fixture.hostDraining)
    fixture.proveOffEndedAndObserveOff()
    fixture.proveOnEndedAndObserveOn()
    assertTrue(fixture.originalRunRestored)
    assertTrue(fixture.hostStoppedAfterChild)
}
```

关闭自动成功后撤屏幕监听/取消周期/阻新auto；accepted child继续原恢复直至有限生命周期完成。真正销毁仍只撤对应host、cancel观察lease、retire exact scanner，不以此证明native结束。手动OFF不会因监控重新打开；window/storage/authority故障保留独立隔离。

- [ ] **3. Android配置和代际安全alarm。** `AutomaticSettingsPreferences`严格解析缺失/完整record/损坏，原子commit schema、settings revision、周期generation和pending ticket，不读eager-cache确认。periodic非exported receiver、独立requestCode/action、generation-bearing URI、immutable PI；与ReEnableScheduler requestCode10完全分离。

```kotlin
val targetFilter = ScanFilter.Builder()
    .setDeviceName(target.advertisementName)
    .build()
val periodicIntent = Intent(context, MonitorAlarmReceiver::class.java)
    .setAction(context.packageName + ".action.MONITOR_TICK")
    .setData(Uri.parse("btmonitor://periodic/${ticket.settingsRevision}/${ticket.generation}"))
val periodicPending = PendingIntent.getBroadcast(
    context, 106, periodicIntent,
    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
)
```

actualarm时查询canScheduleExactAlarms；有access setExactAndAllowWhileIdle，无access setAndAllowWhileIdle并显示降级，不声称精确。inexact冷delivery不能当exact FGS豁免；无合法host/启动机会就跳过新work并显示需显式启动，原receiver预算不延长。timerticket消费commit→下一次arm成功→dispatch；busy也前两步，无backlog。process death/配置变更/duplicate/old URI正确拒绝。schedule failure不称健康计划；只cancel周期自身PI。

独立触发attempt合同：`data class MonitorTriggerAttempt(val settingsRevision: Long, val attemptId: String)`，process-local、一次性、只证明事件已验证，不授权native。消费旧alarm和arm新generation后，建立该attempt；冷宿主就绪时核对当前confirmed settings revision和exact attempt，不再要求它等于已消费的旧alarm。配置变化/关闭丢弃未接纳attempt；exact child接纳后独立冻结目标，后续关闭不能撤销child。补行为RED：arm-next不误使当前attempt失效、cold-host等待时关闭/换目标不执行、child已接纳后关闭仍按旧目标恢复。

- [ ] **4. 三触发和冻结目标接线。** service动态监听SCREEN_ON/OFF，API33 flags遵循受保护系统广播，旧API仍可用；无manifest屏幕receiver、无USER_PRESENT替代。每次source检查当前confirmed settings/capability/gate/cooldown；接受时冻结TargetSnapshot到run/operation，异步ready后不再读新设置。自动scanner使用上面真实非空完整name filter，并在当前packet再核对TargetSnapshot；MAC/旧suffix/旧cached name只可诊断而非命中。

`BandResetController`实际predicate、诊断和`ScanSummaryObservation`一致，当前有效广告在cap前锁存target；所有主要接纳点包括launcher、broadcast、monitor、manual summary均传入本次target。旧BandMatcher可作为不再驱动当前Android行为的legacy helper保留原10测试，但生产扫描/当前summary不得漏用其default fallback；若替换该helper，则精确记录直接产品测试变化，不删原native安全用例。

`DiscoveredDevices`保留真实raw advertisementName，display name处理可独立；manualScanner仍只采集/summary，不能引入matcher/reset/bond/connect/classic discovery。filtered自动count只表示过滤后的观察，logger仍三类且异常不改变蓝牙；手动100+规则保留。

- [ ] **5. 本次候选、draft和详情接线。** 在ManualOperationController exact accepted SCAN begin和guarded devices/summary末snapshot更新Task1候选；新SCAN清空、末值在live清除后仍在，迟到不覆盖新扫描；不从snapshot.manual(SCAN)或manual_results hydration恢复。详情adapter用真实name，选择只填名称，未知placeholder不填；可列地址区分但不绑定MAC。无广播名选择提示不能设为名称目标。

新增master/三个checkbox、分钟EditText、可编辑AutoCompleteTextView和显式Save，用户草稿与confirmed revision分开，保存conflict/false明确提示且保留草稿。adapter变化不覆盖text/cursor/dirty，Activity重建只保留草稿/选择的历史身份、不保存候选冒充fresh。subscribe/unsubscribe原生命周期保持，窗口关闭不取消工作。保留原两行buttons、无独立SCAN卡/日志说明、explicit log access、历史/累计；新增显式exact-alarm和电池优化系统设置入口，不自动shell修改策略。

```kotlin
val targetInput = AutoCompleteTextView(this).apply {
    setSingleLine(true)
    setText(confirmed.settings.targetName, false)
}
// Candidate adapter changes never call setText; onItemClick copies only its actual name.
// Save validates the complete draft and expected revision before runtime reconfiguration.
```

测试需要真实生产draft/candidate/schema/modelseam，而非仅source字符串。source tests只辅助核验actual filter/service/receiver/settingsIntent/no trampoline/no boot/no permanent lock/no arbitrary command，允许新增nonexported periodicreceiver。

- [ ] **6. RED/GREEN和冻结审查。** 聚焦真实host/trigger/target/UI/candidate/oldmanual/FGS seam。新增边界包括权限撤销、nearby失败、exact权限降级、无宿主屏幕skip、timer冷启动被拒绝、设置损坏与安全ledger独立、same-name/nameless、目标在manual第101st广告仍锁存、summary失败finally仍交付authority、无锁idle/异常release-once、late native/window ended不重复。

全部GREEN后完整test/debug/lint强制重跑，记录每个suite准确名称；原未取代安全测试源码/数量和5秒fixture不放宽。lint原13Warning+1Fatal如实；新增MissingPermission/Error必须修复而非抑制。规格PASS→质量PASS，父fresh完整回归和scope/hash后Task2提交；不得并行Gradle。

## Task3：1.4身份、说明与完整审查

**Modify:** `app/build.gradle.kts`、`app/src/test/java/com/blreay/bluetoothmonitor/AppIdentityBoundaryTest.kt`、`README.md`。

- [ ] **1. 身份先RED。** 原12测试名称全部保留，仅两个版本敏感正文改1.4/+5，另10正文不动。真实compile成功后跑12项，应因当前1.3/+4出现两个断言failure，不把编译错误算RED。

```kotlin
assertTrue(build.contains("val autoVersionCode: Int = (ciRunNumber ?: 0) + 5"))
assertTrue(build.contains("versionName = \"1.4\""))
assertEquals("The No-GUI upgrade must be named 1.4", "1.4", versionName)
assertEquals("Local builds must upgrade delivered 1.3/code 4 to code 5", 5, codeOffset)
for (ciRun in listOf(1, 42, 1000)) {
    assertEquals(ciRun + 5, ciRun + codeOffset!!)
}
```

- [ ] **2. 最小build GREEN。** 只改版本与对应注释：

```kotlin
// Version 1.4 uses local code 5, or the CI run number plus 5,
// to upgrade the delivered No-GUI 1.3/code 4 APK.
val autoVersionCode: Int = (ciRunNumber ?: 0) + 5
// defaultConfig:
versionName = "1.4"
```

保留applicationId/label/namespace/SDK/deps/signing guard/AIDL/UserService version1。README说明显式启用、持久保存、精确名称/同名/无名/本次下拉、filter count、三组合、30sec防抖、Doze不保准时、无boot/force-stop保证、设置failure和原恢复不取消、权限独立。更新历史明显过期的“当前仍1.2”等文案，但不篡改历史artifact事实；新APK数据仅父核验后补。

- [ ] **3. 聚焦和完整无release GREEN，缺配置release拒绝。** 临时properties先核验不存在；缺配置assembleRelease应在现有validateNoguiReleaseSigning明确failclosed，不能成功packageRelease，也不是产品RED。
- [ ] **4. fresh规格→质量→最终整体只读审查。** 整体覆盖baseline→所有tracked/new文件、生产使用seam、exacthost/cleanup/SDK/native/恢复未退化、424名称差异分类、所有新source guard允许范围。通过前不正式签名构建。父检查exact allowlist后提交Task3。

## Task4：父正式构建、APK核验与Git交付

- [ ] **1. 冻结最终源码/testcase契约与原APK。** 重新inspect持久原1.3及额外备份、1.2/1.1；生成当前实际编译源码/resource/build/CI/wrapper所有hash、准确suite/case名称、必需DEX类名单。不可复用旧88/424硬编码为新最终要求。
- [ ] **2. 两次完整正式强制构建。** 单一Gradle owner，secret仅Gradle内部读取；第一份exclusive保存构建目录外后才第二次。每次检查实际XML零failure/error/skipped，准确名称、任务exit、lint及签名guard成功。

```bash
source /home/admin/.cache/btreset-build/env.sh
BT_NOGUI_SIGNING_PROPERTIES=/home/admin/.local/share/bluetooth-monitor-nogui/signing.properties \
 "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
 testReleaseUnitTest assembleDebug assembleRelease lintDebug \
 --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

- [ ] **3. 新公开verifier实际APK检查。** native Write/Edit创建1.4 verifier，沿用公开aapt/apksigner/manifest树/DEX class_def/JUnit契约方法，更新新version/components/WAKE_LOCK真实要求。完整NoDisplay launcher/RUN/Trigger保留；新MonitorService connectedDevice与MonitorAlarmReceiver nonexported，无boot/INTERNET/WRITE/globalDoze；原Shizuku provider/security/SDK和单一78证书/v2有效。实际编译新settings/target/host/scheduler/candidate/UI类存在，retired AuditRow/LogDropRange不存在。logger不作为native proof。
- [ ] **4. 最终第二份exclusive复制和重验。** `/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.4.apk`与`app/build/outputs/apk/release/bt-monitor-nogui-1.4.apk`。目标存在先inspect/hash，未知不覆盖；复制后逐份metadata/bytes/hash/cert重验。旧1.3/1.2/1.1原输出只从真实保存文件恢复并重验，不重编冒充保留。
- [ ] **5. ADB与真实边界。** `adb devices -l`有授权设备才做覆盖升级、设置持久化、下拉/手输/精确命中、normal-screen-off/Doze/filteredBLE、组合冲突、短锁、权限撤销/恢复与停止auto保留ON恢复验收。无设备逐项未执行；模拟器不是BLE/厂商Doze真机替代，本任务不下载模拟器。
- [ ] **6. 补真实文档、提交推送。** 仅事实补README/spec/plan。最终编译hash/准确XML/实际artifact再次核对，attributed commit末行`Co-Authored-By: Claude Code <noreply@anthropic.com>`；只正常push`origin bluetooth_monitor_shizuku_nogui`，ls-remote与HEAD相同。用户原`design.md`仍未跟踪，不能claim绝对clean或把它stage。CI可读则查，否则披露受限，不绕过安全限制、安装/触发发布workflow。最终给APK路径、1.4/code5、简短设置方式、实际测试及未做真机/CI边界。

## 完整无release命令

```bash
source /home/admin/.cache/btreset-build/env.sh
# 命令前确认该临时配置路径不存在；不打印真实私密配置。
BT_NOGUI_SIGNING_PROPERTIES=/tmp/btreset-1.4-no-signing.properties \
 "$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
 testReleaseUnitTest assembleDebug lintDebug \
 --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

## 执行记录

按实际发生追加，未执行项不标完成。父在本轮准备期间只新增已确认设计/计划，未改生产源码、运行新Gradle或读取秘密。普通方案平台规则通过官方搜索索引核对，正文抓取受限；真实锁屏/Doze/手环广播尚未验证。

计划inline自查：规格1—2对应Task1/Task2.4—5，3—6对应Task2.1—3/5，7对应Task1 checked store及Task2 adapter，8覆盖所有阶段不可退化约束，9对应Task3/Task4。处理架构建议差异：保留零触发拒绝、inexact明确降级和raw完整名称；monitor选NOT_STICKY并披露仅屏幕冷恢复边界。周期settings revision/persisted ticket/一次attempt三个身份分开，接纳前取消和接纳后drain不同。示例测试fixture不是已有API，须由实现者在相应test文件定义并组合实际production seams；不得把fixture中的假策略当生产行为。实现者报告最终API供下一任务直接接线，不能在Task2重建另一settings/scheduler authority。
