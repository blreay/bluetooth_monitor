# 运行详情控制与累计历史 — 实施及验证记录

日期：2026-11-26。分支：`bluetooth_monitor_shizuku_nogui`。基线：`2b8ab36376f98689e055411f180c79bb4816c846`。

设计：[运行详情手动控制与累计重启通知](../specs/2026-11-25-details-controls-history-design.md)。唯一实施计划已批准；不追加文档/执行确认门，连续完成实现、审查、父核验、提交推送和APK交付。

## 当前状态

1.1功能源码、规格/质量/整体源码审查、两次完整构建及新APK交付核验已完成；功能提交已推送并核对远端SHA及干净tree。本记录的最终状态更新随文档提交交付。旧1.0输出缓存被AGP清理，公开备份查找未能恢复，原文件保留/本次并排重验门未通过；不宣称全部计划门通过。下面复选框只按实际完成证据更新。

## 任务

### Task1 — durable历史、手动记录与原子终态
- [x] 可执行行为红测：四run、精确一次计数、no-op/手动/不确定排除、事务失败、旧JSON、裁剪/删除。
- [x] `ResetHistory`、`ManualOperation`、实际使用codec与checked持久层；同preferences事务，pending更新不清ledger。
- [x] `DurableResetEngine`观察证据与terminal transaction；SharedPreferences eager-cache失败保留last-confirmed/fail-closed。
- [x] 聚焦及完整测试、debug/lint编译。
- [x] 独立规格审查；修正后质量审查通过。

### Task2 — manual runtime、BLE、历史卡及live详情
- [x] 行为红测覆盖shared admission、native晚结果、冷fence、UIrecreation、scan retirement。
- [x] coordinator-owned manual controller；统一Shizuku-first driver；手动OFF无自动ON。
- [x] exact FGS自动/手动owner、有限工作窗口和durable quarantine。
- [x] 8秒观察BLE、100展示上限、target不reset、exact retirement成功信号。
- [x] 结构化自动accepted match/请求/观察/终态事实；安全arm-before-OFF不退化。
- [x] fullrunId卡片身份、direct immutable详情Intent、non-exported删除receiver、删除不复活/不减数。
- [x] 详情三个按钮与三个结果区，snapshot生命周期及历史selection。
- [x] 四run四卡等聚焦及完整回归、spec后quality审查通过。

### Task3 — 同签1.1升级、文档及整体审查
- [x] versionName1.1/localcode2/CI+2，identity测试红绿。
- [x] 保留私密signer并核对公开证书pin，release fail-closed。
- [x] README使用范围、累计语义、100明细、删除/OEM/legacy/隔离边界。
- [x] spec/quality及最终整体源码审查通过，无未修重要缺陷。

### Task4 — 父fresh verification、APK及Git交付
- [x] 最终diff/scope检查，未改旧GUI/main/CI/wrapper/private inputs。
- [x] 父强制全量tests/debug/release/lint并核对JUnit/lint实际结果。
- [x] 1.1包名/版本/证书与原1.0构建前实测记录相符，重复release签名稳定。
- [x] 新`bt-monitor-nogui-1.1.apk`复制后metadata/size/hash核验，并保存构建目录之外的同字节副本。
- [ ] 原1.0文件保留及本次原文件并排重验：失败，AGP清理输出后公开备份查找未恢复，不能标通过。
- [x] adb设备可用性与真实执行/未执行的验收项。
- [x] attribution功能提交、仅当前分支push、远端SHA与干净tree确认；最终记录更新单独提交。

## 验证命令

```bash
source /home/admin/.cache/btreset-build/env.sh
"$BTRESET_GRADLE" -p /home/admin/git/bluetooth_monitor_shizuku \
  testReleaseUnitTest assembleDebug assembleRelease lintDebug \
  --rerun-tasks --stacktrace --console=plain --max-workers=2 --no-parallel
```

只对当前命令限并发，不改变测试断言、原5秒JVM fixture或生产超时。expected测试零失败/错误/跳过；lint保留target32问题时如实记录，不称lint-clean。

## 已执行的规划基线核验

- 2026-11-25/26，父会话确认工作树原始干净、当前分支正确、HEAD为基线。
- 原交付`app/build/outputs/apk/release/bt-monitor-nogui.apk`实际metadata：package `com.blreay.bluetoothmonitor.nogui`，versionName1.0/versionCode1，min31/target32，完整launcher类`com.blreay.bluetoothmonitor.MainActivity`。
- 原交付APK SHA256：`999119f1a9fee387f52d99df437215a421cf5800a89059cd07f52fa048d2e518`。
- 原交付证书SHA256：`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。升级必须保持该证书；不读取/生成/替换密钥。
- 规划时`adb devices -l`为空；这不是新版本真机验收。

## 执行结果

本节按发生顺序保留Task1—4的命令、红绿结果、审查、失败及交付数据；早期段落中的“尚未完成”是当时状态，最终结果见末段。

### Task1进行中的已披露问题

实现代理报告首次完整命令执行215项测试、1项失败：旧`AppIdentityBoundaryTest`把生产与测试依赖合并为三项精确列表，拒绝本计划明确允许的测试专用`org.json:json:20240303`。父已检查实际断言（该文件39—42行），授权仅拆开生产/测试依赖精确断言：生产仍仅Shizuku api/provider13.1.5，测试仅JUnit4.13.2及该JSON库。未授权跳过断言、伪装依赖或提前修改1.0/code+1版本测试。该首次失败后的完整重跑与后续审查修正结果按发生顺序记录如下。

合法1.0没有ledger/counter的新计数周期已明确从1.1已知0开始，旧pending不计数；损坏的已存在counter才未知/阻塞。实现代理加入真实旧JSON恢复不计数、下一次新成功0→1的回归。父读取当时生成的JUnit XML：20个suite、217项、零失败/错误/跳过；实现代理报告对应完整test/debug/lint命令通过。这不是父最终版本的独立构建核验。

随后实现代理自审追加「已存在但类型损坏的version/timestamp/armed」回归。该次运行遇到Gradle Test Executor连接超时（报告等待17分钟），新断言未执行，不能算行为红测；基础设施根因尚未确认。失败日志保留在`/tmp/btreset-task1-red-corrupt-facts.log`。代理用仅本次命令的`--no-daemon`重跑，实际执行断言红测（expected QUARANTINED / actual OFF_DISPATCHED，`/tmp/btreset-task1-red-corrupt-facts-retry.log`），再收紧codec类型检查转绿；没有改变测试/生产超时或永久Gradle配置。

实现代理最终报告原完整test/debug/lint命令成功（30秒，218项、零失败/错误/跳过），日志`/tmp/btreset-task1-final-full.log`；源码已冻结。lint仍是1项ExpiredTargetSdkVersion Fatal与12项Warning，不能称lint-clean。父独立完整test/debug/lint核验成功（23秒、73个任务全部执行，`/tmp/btreset-task1-parent-full.log`）。父读取本次生成的20个JUnit suite：218项、零失败/错误/跳过；其中History7、Manual5、Codec15、Headless42，原matcher10/policy18/report14均保留。父读取lint XML：Fatal1（ExpiredTargetSdkVersion）、Warning12、MissingPermission0、Error0。首轮只读规格审查未通过：两个重要缺口（父已读取实际source确认）——类型合法但stage/phase/authority矛盾或负时间的pending未隔离；已有counter丢ledger未阻塞，且正常已结束1.0的空preferences升级缺少旧历史未知说明。已交回同实现代理做行为红绿、最小codec修正和完整重跑。以下追加修正后的独立核验与重新审查结果；此前218项通过不等于规格符合。

同实现代理已报告修正完毕并重新冻结源码。新增实际行为红测：pending不变量19项/4失败（包括错误ON和同目标fallback派发）、epoch完整性23项/4失败、未知ordinal直接接收23项/1失败；日志分别为`/tmp/btreset-task1-review-red-record-invariants.log`、`/tmp/btreset-task1-review-red-epoch-integrity.log`、`/tmp/btreset-task1-review-red-unknown-ordinal.log`。仅codec、history与codec测试改动：非法stage/phase/authority和负事实隔离；空旧preferences建立已知0的新周期并持久披露旧历史未记录；部分1.1周期缺ledger/count阻塞，独立合法total保留，ordinal用0明确未知且不允许新建行。代理报告原完整命令12秒成功、226项零失败/错误/跳过，日志`/tmp/btreset-task1-review-final-full.log`。父独立完整重跑已成功：12秒、73个任务全部执行，日志`/tmp/btreset-task1-parent-review-full.log`；父核对本次20个JUnit suite共226项、零失败/错误/跳过，lint仍为Warning12/Fatal1（ExpiredTargetSdkVersion），MissingPermission0/Error0，`git diff --check`无问题。原规格审查代理重新读取实际修改后返回PASS：两个Important均关闭，未发现新的Task1重要缺口。独立质量审查发现1项Important，父读取实际Engine256—270确认：native OFF已提交且结束，但首次观察尚未tick记录、后续alarm重排失败时，显式`retryRecovery(实际OFF)`转ON_READY却未保存OFF观察证据；之后真实ON恢复仍会漏计该完整run。已交同实现代理做ledger-backed行为红绿及最小修正，保留ON_READY重复点击无写入/不换身份的规则，不伪造旧/未进入native证据。同实现代理已完成最小修正并冻结：仅Engine及HeadlessRuntimeTest改动，显式实际OFF观察在转ON_READY的同一个checked事务保存，只有匹配当前WAITING_OFF的既有OFF entry才补观察，不创造entry/authority。实际ledger-backed红测到终态后expected1/actual0（45项/2失败，`/tmp/btreset-task1-quality-red-explicit-off.log`）；聚焦转绿，完整命令代理报告12秒成功、229项零失败/错误/跳过（Headless45），日志`/tmp/btreset-task1-quality-final-full.log`。父独立完整重跑成功：12秒、73任务全执行，日志`/tmp/btreset-task1-parent-quality-full.log`；父核对20个JUnit suite共229项、零失败/错误/跳过，lint保持Warning12/Fatal1（ExpiredTargetSdkVersion）、MissingPermission0/Error0，diff-check无问题。同质量审查代理重新读取修改与回归后APPROVED，无剩余Critical/Important。Task1规格与质量门均通过，已标完成；随后Task2单一实现代理开始整合manual runtime、BLE、历史卡及live详情。版本仍1.0，尚未生成1.1 APK。

### Task2实现交接与首轮审查

实现代理已交接并冻结源码，没有活动Gradle或后续写入。父核对实际tracked/new文件、HEAD及分支：仍为基线`2b8ab36376f98689e055411f180c79bb4816c846`与`bluetooth_monitor_shizuku_nogui`；build.gradle仅Task1测试JSON依赖变动，版本/签名未改，README/CI/wrapper未改，diff-check通过。新增manual controller、真实BLE observer、delete-only receiver与details/history行为测试，runtime/UI/model/codec/engine接线待独立规格及质量审查；交接不等于审查通过。

代理最终完整test/debug/lint命令日志`/tmp/btreset-task2-full-final.log`实际为12秒、73任务全部执行；代理报告22个suite、271项零失败/错误/跳过。父独立同命令重跑成功：13秒、73任务全部执行，日志`/tmp/btreset-task2-parent-full.log`；父读取完整日志与本次JUnit XML确认22个suite、271项零失败/错误/跳过，其中Details19、HistoryNotification6、Codec27、Headless48、ScanRetirement15、Driver10，原matcher10/policy18/report14均保留。父读取lint XML确认13项：Warning12/Fatal1（ExpiredTargetSdkVersion），MissingPermission0/Error0；diff-check通过。代理报告lint回到Warning12/Fatal1（ExpiredTargetSdkVersion），MissingPermission0/Error0；三个显式静默通知builder setter的Kotlin弃用警告仍保留，不称lint-clean。

父检查以下真实RED日志，均执行测试断言后失败而非编译/基础设施失败：最后native入口lease撤销/完成提交失败/迟到隔离entry（69项/3失败，`/tmp/btreset-task2-red-safety-entrances.log`）；exact scanner owner/serial与重入replacement（25项/2失败，`/tmp/btreset-task2-red-exact-scanner-routing.log`）；旧sample阻塞新manual（42项/1失败，`/tmp/btreset-task2-red-late-sample.log`）；非法可选时间/耗时（57项/2失败，`/tmp/btreset-task2-red-optional-metadata.log`）；backend原因与采样时刻fallback（18项/2失败，`/tmp/btreset-task2-red-details-sample-time.log`）；manual FGS不能冒称自动扫描/恢复（19项/1失败，`/tmp/btreset-task2-red-foreground-disclosure.log`）；batch广告保留worker接收时间（46项/1失败，`/tmp/btreset-task2-red-batch-receipt.log`）。聚焦转绿与最终回归由代理报告，父独立重跑继续核验。首轮只读Task2规格审查未通过，返回3项Important；父读取实际source确认后已交同实现代理逐项行为红绿修正：历史裁剪只删row、没有取消已经发布的fullrunId/HISTORY_ID旧卡；manual BLE只显示名称/MAC/RSSI、没有目标标记；自动历史/engine terminal事务没有终态决策时间字段，无法持久或展示该时间。修正需使用实际Android调用的通知retirement seam、只展示的真实广播目标标记、同checked终态事务的明确时间，保留失败写入的last-confirmed与旧未知时间；质量审查须在规格重新通过后启动。上述271项通过不等于规格通过。尚无1.1 release APK、提交或推送。

同实现代理已修正三项并冻结：confirmed snapshot listener在checked成功后/cold初始化调用实际notification retirement，按本应用history渠道/ID103的fullrunId清理已裁剪孤卡，保护保留row/pending及其他ID，不repost；manual结果区用配置BandMatcher与本窗口真实advertisementName标记目标，缺名与展示占位分开且不触发reset；engine真正terminal决策边界新增nullable `terminalRecordedAt`，同历史/count/pending removal事务持久，旧缺失未知、非法类型/负值隔离，明确不是硬件时间。父检查真实RED断言：retirement 10项/2失败（`/tmp/btreset-task2-review1-red-retirement.log`）；目标标记20项/1失败（`/tmp/btreset-task2-review2-red-target-marker.log`）；terminal时间43项/5失败（`/tmp/btreset-task2-review3-red-terminal-time.log`）。代理报告各聚焦GREEN与完整22suite282项零失败/错误/跳过；父读取完整日志`/tmp/btreset-task2-review-full-final.log`，12秒/73任务全执行，baseline lint及三个弃用warning保留。父独立同命令重跑成功：12秒、73任务全执行，日志`/tmp/btreset-task2-parent-review-full.log`；父核对本次22suite282项零失败/错误/跳过（Details20、HistoryNotification14、Codec29；其他baseline suite保持），lint仍为Warning12/Fatal1（ExpiredTargetSdkVersion）、MissingPermission0/Error0，diff-check通过。原规格审查代理重新读取实际修正后返回PASS：三个Important均关闭，未发现新Important/Critical。独立质量审查未通过，发现两个Important事件顺序缺陷；父读取实际source确认后交回同实现代理：manual BLE广告准入只检查45秒lease/finished/entered，Main迟延12秒时9—11秒广告可在迟到start-result的零延时timeout之前被保存，需实际单调8秒deadline准入与过期即时finish；用户划掉→自动callback先repost→delete广播才确认dismissed时，retirement仍保护所有row及pending，未取消已repost的已删卡，需checked/cold reconciliation取消确切已确认dismissed的历史卡而不动pending/count/其他ID。要求实际使用seam的行为红绿，不以visible-null或时间到期假定native清理证明。Task2仍未整体标完成，版本仍1.0，未生成新release。

同实现代理已完成两项质量修正并冻结。父读取实际`ManualBleScanner`、`ManualObservationWindow`和notification retirement接线：worker真实native入口捕获固定单调时间，single/batch按sample/receipt/Main delivery准入，窗口过期的start-result立即finish并请求exact清理，不把观察到期当authority-ended；confirmed dismissed会取消确切history渠道/ID103/fullrunId卡，pending只保留恢复安全状态，不再阻止视觉删除。父检查真实RED日志：窗口33项/5断言失败（`/tmp/btreset-task2-quality1-red-window.log`），删除19项/4断言失败（`/tmp/btreset-task2-quality2-red-dismissal.log`）；对应GREEN日志均BUILD SUCCESSFUL。代理完整命令日志`/tmp/btreset-task2-quality-full-final.log`实际13秒/73任务全执行，代理报告22suite293项零失败/错误/跳过，baseline lint及三个弃用warning保留。父独立完整test/debug/lint重跑成功：13秒/73任务全部执行，日志`/tmp/btreset-task2-parent-quality-full.log`；父核对本次22suite293项零失败/错误/跳过（HistoryNotification19、ScanRetirement21，其余baseline保持），lint为Warning12/Fatal1（ExpiredTargetSdkVersion）、MissingPermission0/Error0，diff-check通过。原质量审查代理重新读取生产接线与回归后返回APPROVED：两项Important均关闭，未发现新的Critical/Important；只读审查所读293项报告不是独立执行或真机验收。Task2规格/质量与父独立回归门均通过，已标完成。随后单一Task3实现代理开始版本1.1/code2、原证书pin和README升级说明；没有活动父Gradle，未生成新release/提交/推送。

### Task3版本、证书守卫与说明

Task3代理交接并冻结，仅修改build.gradle/AppIdentityBoundaryTest/README：versionName1.1、本地code2/CI+2，原私密路径与退役证书拒绝不变，实际release守卫增加原7825cc证书pin；README新增manual/BLE/累计/删除/100/OEM/旧数据/隔离边界，原1.0交付单独保留，新APK仍待最终核验。父读取实际build脚本与三文件diff，diff-check通过。父检查真实版本/证书断言RED（12项/3失败，`/tmp/btreset-task3-RED-21KuYr.log`），GREEN日志`/tmp/btreset-task3-GREEN-1SeHoH.log`成功；缺配置release在`validateNoguiReleaseSigning`明确失败，日志`/tmp/btreset-task3-MISSING-SIGNING-y3xpBk.log`，不是行为红测或成功构建。代理完整test/debug/lint日志`/tmp/btreset-task3-FULL-gkzbbd.log`实际12秒/73任务全执行，报告295项零失败/错误/跳过。父使用另一个确实不存在的BT_NOGUI_SIGNING_PROPERTIES路径独立完整重跑成功，证明测试/debug不需要release配置：12秒/73任务全执行，`/tmp/btreset-task3-parent-full.log`；实际22suite295项零失败/错误/跳过、Identity12，debug metadata包名不变且1.1/code2，lint保持Warning12/Fatal1（ExpiredTargetSdkVersion）、MissingPermission0/Error0，三个setter弃用warning保留。没有用不同私钥执行wrong-certificate构建；公开pin的拒绝比较由实际source/test核对，最终正确证书仍须真实release/APK验证。Task3只读规格审查返回PASS，无Critical/Important规格缺口；独立quality读取实际三文件与日志后APPROVED，无Critical/Important。最终整体只读审查已启动，覆盖全部tracked/new代码及Android接线；父最终强制test/debug/release/lint成功：13秒/97任务全部执行，日志`/tmp/btreset-1.1-parent-final-full.log`，实际22suite295项零失败/错误/跳过。首次公开APK检查确认1.1/code2、同原7825cc证书、NoDisplay与内部receiver，2542237字节/SHA256 `8844c90ff2d2fe6245600480d24c658ddb554827071babd3a382b1ee53927b52`；这不是最终交付。完整verifier失败：AGP packageRelease清理release输出目录，原`bt-monitor-nogui.apk`不存在，未完成原文件hash对比，串联adb检查也未执行。父没有把失败隐藏或用新编译1.0冒充原文件；已启动只读原APK备份查找，并将新1.1公开产物复制且重验到输出目录外`/tmp/btreset-1.1-public-artifacts-xnb1fy0t/first-verified-1.1.apk`，后续打包前保留外部备份。父第二次完整test/debug/release/lint成功：14秒/97任务全执行，日志`/tmp/btreset-1.1-parent-repeat-full.log`，实际22suite295项零失败/错误/跳过与baseline lint保持。第二份APK仍为1.1/code2、同7825cc证书与compiled NoDisplay/internal receiver，2542237字节/SHA256 `2e429826fd80af4bcecc17fa142ca81eb39c9a57ddefa353f6a0fde19eff7960`；两次证书稳定，但APK字节不同，不声称字节可复现。第二份已复制并重新核验到同外部备份目录的`repeat-verified-1.1.apk`。实际`adb devices -l`为空，覆盖安装/焦点/OEM/四次真重启/划卡/recreation均未执行。最终整体只读源码审查APPROVED：实际20tracked/13new文件、既有权限/SDK/native/retirement边界与新接线均核对，无已验证Critical/Important；这不是原文件保存或真机验收通过。原APK的初始及扩展公开缓存/备份查找均未找到匹配字节，不绕过不可访问目录的权限，未恢复、未重编1.0冒充原文件。默认原文件-preservation verifier的失败保留；另行new-only verifier验证当前新APK与原构建前实测身份记录一致，明确输出`originalPreservationVerified=false`/`allPlannedAcceptanceGatesPassed=false`，不放宽生产/JVM断言。

最终第二份已复制为`app/build/outputs/apk/release/bt-monitor-nogui-1.1.apk`并重新验证包名、1.1/code2、单一7825cc证书、compiled NoDisplay/internalreceiver、manual/history/window类、2542237字节及SHA256 `2e429826fd80af4bcecc17fa142ca81eb39c9a57ddefa353f6a0fde19eff7960`，报告`/tmp/btreset-1.1-delivery-verification.json`。同字节副本保存并重新apksigner/aapt/hash核验到`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.1.apk`，报告`/tmp/btreset-1.1-durable-apk-backup.json`；后续不得仅依赖会被AGP清理的输出目录。README与新交付数据一致，明确原文件保存失败以及无设备验收，未把这些改写成通过。功能提交`b98725c0f48dbaf9bb1a78ff37cf27af46421393`已完成并推送，仅`bluetooth_monitor_shizuku_nogui`；父实际`git ls-remote`返回相同SHA，`git status --porcelain`为空，提交末尾attribution核对正确。未合并main/旧GUI、未PR/dispatch发布/替换rolling latest，未提交APK或私密签名输入。本段是功能push核验后的最终文档更新，另行docs提交/推送；最终文档SHA以Git HEAD及最终交付答复为准。
