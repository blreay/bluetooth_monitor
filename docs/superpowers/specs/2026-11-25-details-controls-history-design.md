# 运行详情手动控制与累计重启通知

日期：2026-11-26。分支：`bluetooth_monitor_shizuku_nogui`。基线：`2b8ab36376f98689e055411f180c79bb4816c846`。

用户已验收1.0后台行为；本设计对应详情三按钮、累计重启通知和同签1.1升级。用户明确选择手动扫描仅显示结果、不自动重启；整体实施计划已通过唯一审批门。后续连续实施、测试、审查、构建和交付，不再设置文档/执行审批门。

## 产品契约

- 日常图标、RUN和广播仍执行原无窗口后台流程；不恢复可见主页或透明占位窗口，不新增周期/boot/全天监控，不修改fooView入口。
- 点击通知打开真实「运行详情」：增加「打开蓝牙」「关闭蓝牙」「扫描蓝牙」。分别保留最近执行结果，实时显示阶段、模式、提交/实际状态、失败原因和时间。现有权限、恢复及系统设置入口保留。
- 手动开关共用现有Shizuku优先/普通后备驱动；必要OEM确认仍允许。手动OFF保持关闭，不生成ON恢复，也不计自动重启。
- 手动扫描是一次8秒BLE广播观察；展示广播名、MAC、RSSI及目标标记，最多展示100个设备并说明截断。不是经典蓝牙全集或配对缓存，不自动ON，不配对/连接，目标出现也不重启。
- 冲突操作立即拒绝，不排队、不抢占/取消旧命令。拒绝消息不替换正在进行操作的身份；窗口关闭/重建不取消应用拥有的native操作。
- 每个被接受的自动目标命中有自己的序号和通知，只有该run的阶段会更新它。未命中不新增重启历史；arming失败仍报告该次失败，但不关闭/计成功。
- 每张卡包括目标、发现/请求提交/首次观察OFF和ON/执行结束/终态时间、耗时、真实方式和原因。请求时间不冒充硬件切换时间，未知时间/跨进程单调耗时不虚构。
- 累计成功只统计新接受的自动run：同run实际提交OFF与ON、先实际观察OFF后观察ON、相关native authority结束且未计数。no-op、未提交/拒绝、手动、只请求成功、未结束/耗尽/隔离和没有完整证据的旧pending不加成功数。恢复ON不自动等同应用执行了完整重启。
- 点击历史卡不会自动删除，划掉可手动删除。删除只改变卡片的dismissed状态，不取消恢复、不解锁authority、不减累计；迟到结果继续持久保存，不复活卡片。
- 保留最近100个已结束运行的详细历史，保护当前pending；累计总数独立。裁剪移除旧卡且迟到事件不能重建已裁剪行。系统/OEM可能更早限制可见通知、自动分组或回收卡片，不能保证无限同时可见。
- 从1.1开始记账，不从1.0最新通知文本反推次数。升级后旧pending按原安全条件恢复，缺失时间/提交证据显示未知，不伪造历史。

## 所有权与手动生命周期

`ResetCoordinator`仍是普通应用进程的唯一主线程owner。手动controller由应用拥有，而非Activity。统一准入检查自动run/setup/service admission、durable pending（包括耗尽/隔离）、手动fence、active/unretired scanner。原pending可以继续自身恢复，但不能被手动操作接管。

手动操作先checked保存operation/command/revision/action/fence，再进入native-capable工作；写失败禁止执行。开关复用`BluetoothSwitchDriver`和有界状态观察。保留同目标普通后备的旧authority已结束、稳定错误状态及观察窗口条件，不为手动功能新增裸adapter enable/disable。

手动FGS只在真实详情窗口的合法前台机会请求；exact ticket获接纳后才执行。serviceStarted/serviceGone明确区分自动/手动owner，晚到ticket不影响新操作。总工作窗口有限，窗口结束可以停FGS，但无法据此解除native隔离。

One-shot receiver/FGS finish与迟到native reconciliation分离。只有匹配确切operation/command的真实authority结束、或native进入前的确定拒绝，才允许清除命令fence；扫描还需确切registration retirement。失败clear仍阻塞；冷进程未结束记录不重放、无“清除继续”覆盖按钮。超时、Future取消、硬件目标、Activity/Service销毁或强停请求都不是结束证据。

## 真实扫描

新增小型观察BLE scanner，复用有界ADAPTER prepare/start、`DiscoveredDevices`及共享`ScanRegistrationRetirement`。不原样实例化旧`BluetoothDiscovery`的同步native/吞清理失败路径，不扩展经典发现的另一套取消所有权。

广播名称从当前scanRecord读取，不在主线程读取cached device.name。manual target仅展示。exact scanner/callback从start进入至真实stop清理保持所有权；失败、晚start、拒绝cleanup、权限撤销或阻塞均不允许替代扫描。增加可选exact-ticket retirement成功信号回主线程更新详情/fence，不能凭观察窗口结束释放注册，也不能无限增加清理线程。

## 自动历史与原子计数

在现有`claimMatch(runId)`成功后立即获取结构化事实/目标/时间，创建accepted ledger，再checked arm。OFF仍必须先持久记录并确认后续alarm，receiver扫描阶段只arm，不额外执行长RPC。

历史与pending、手动fence/results共享checked事务。普通pending更新只移除pending keys，不`.clear()`全文件。终态事务检查expected run/command/version，原子保存该行终态、eligible成功累计及counted/as-of标记，再移除pending。计数不在通知、`final=true`或无runtime caller的旧slowPhase中进行。

SharedPreferences.commit失败可能已改变内存cache：存储适配器保留last-confirmed snapshot并process-wide fail-closed，不从eager cache伪称完成或解除执行保护。失败终态保留原安全pending并说明，不授权新开关。

只有合法accepted match可以新建历史。阶段/迟到/删除更新仅修改已接受的行，不能upsert已裁剪run；终态计数还要求当前pending身份，因此不需要无限增长的计数收据集合。总数与有界明细独立。

JSON新增字段采用确定性optional defaults；读取不生成UUID/当前时间。合法1.0记录没有新ledger/counter时，1.1的新计数周期从已知0开始，保留「旧版历史未记录」说明；旧pending安全恢复但不计数，之后新接受且有完整证据的run可以0→1。坏pending继续quarantine；已存在但损坏的history/count独立披露，不把未知累计静默当0。旧record.version仍是CAS revision，不是APK版本。

## 通知与详情

状态101、FGS102与新历史卡分离。历史卡以完整runId通知tag+固定独立ID标识，不hash UUID。content PendingIntent直接打开non-exported详情Activity，immutable，data/action包含runId，extras不是身份。autoCancel=false。

non-exported dismissal receiver接受immutable per-run delete PendingIntent，仅持久删除状态，无UI/开关/恢复/计数操作。打开旧卡展示该run事实，同时独立显示当前自动pending和三个最新手动结果。

详情可见时订阅应用snapshot并立即渲染；onStop/onDestroy解绑，selectedRun跨recreation保存并处理新通知Intent。不捕获Activity作为长命令owner，不每个广告重建dialog或复位滚动。

## 升级、安全与验证

- applicationId保持`com.blreay.bluetoothmonitor.nogui`，namespace保持`com.blreay.bluetoothmonitor`。versionName1.1，本地versionCode2，CI偏移+2。min31/compile34/target32、Shizuku13.1.5、AIDL IDs及UserService版本不变。
- 原私密signer保持证书SHA256 `7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`，可增加公开证书pin。只允许构建内部读取，不暴露/重生成/替换密钥，不修改旧public signer/CI/wrapper；release缺配置或错误signer fail-closed。
- 保留固定svc argv、SDK single-slot/disconnected barrier、EOF/native authority、exact FGS admission、retained cleanup、receiver预算及durable late completion rearm。不得自动force-stop/绕隔离。
- 先真实行为红绿，后spec/quality/final审查；父最终强制完整测试/debug/release/lint和APK metadata/signature/checksum。签名重复稳定不等于APK字节可复现。
- 四run四卡、重试一run、各卡选自己的详情、划掉不复活/不取消恢复/不减累计、手动OFF无ON、manual target无reset、冷fence/迟到proof、生命周期、事务crash/失败和真实旧JSON均需回归。
- 有已授权设备才执行现场升级/OEM/广播/四次实际重启验收；无设备明确未执行，JVM与APK检查不能代替。
- 新交付`bt-monitor-nogui-1.1.apk`，保留原1.0交付。只推送当前分支，不main/PR/发布工作流/rolling latest。
