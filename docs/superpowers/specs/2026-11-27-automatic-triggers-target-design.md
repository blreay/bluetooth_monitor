# 自主触发、持久配置与可编辑目标名称

日期：2026-11-27。工作树：`/home/admin/git/bluetooth_monitor_shizuku`。分支：`bluetooth_monitor_shizuku_nogui`。功能基线：`f88434dbf9f91c921827f40f315489f8f04f002f`（已交付1.3/code4）。

## 用户确认与范围

用户要求分析根`design.md`的普通方案，脱离fooView，由应用自己定时、亮屏或息屏触发扫描，三项独立可选，间隔自定义、默认三分钟，Shizuku已经可用。对候选普通方案，用户实际回复「可以继续吧」，并补充：修改后的配置须持久保存；目标名称改为可编辑下拉框，列出当前扫描到的设备，也允许输入文本。随后在名称匹配问题中，用户实际选择「完整名称精确匹配（推荐）」。

上述确认及用户既有「计划ready之后，直接进入后续环节，不用再找我确认了。一直做到最后」工作偏好作为本次执行依据。设计/计划落地后连续实现、测试、审查、同签构建和当前分支交付，不新增中途“是否继续”审批。以下未由用户指定的初始值和边界采用明确、保守的默认值。

本次使用普通Android调度，**不使用永久WakeLock，不修改全局Doze，不新增任意Shizuku命令、root守护进程、boot receiver或强停后自恢复承诺**。原根`design.md`是未跟踪的参考材料，不覆盖或提交它。

## 1. 产品行为与持久设置

详情窗口增加紧凑的自动触发设置区，保留原两个横排按钮行、授权、历史、累计和恢复入口，不增加首页或透明占位窗口。

- 自动触发总开关。
- 定时扫描、亮屏扫描、息屏扫描三个独立选择项，支持任一、任两项或三项全部。
- 定时间隔为整数分钟，范围1—1440，默认3；未勾选定时时禁用间隔输入但保留已保存的值。
- 可编辑目标名称下拉框：可以选择本次真实BLE扫描取得的名称，也可以直接输入。
- 显式「保存」操作；一次性验证并持久保存全部字段，成功后再更新运行配置并提示。输入空名称、非法间隔，或开启总开关却未选择任何触发方式时拒绝保存。

升级初始值：自动触发关闭；定时选中；亮屏/息屏未选中；间隔3分钟；目标名称为原`Xiaomi Smart Band 8 35BA`。不会仅因覆盖升级而悄悄开启全天监控。用户开启后关闭窗口不停止监控。修改后的总开关、触发组合、间隔、目标名称在进程重建和再次打开应用后恢复。

保存目标或间隔不会自动执行一次扫描。启用定时后第一次计划在当前单调时间加间隔；屏幕触发等待真实边沿。修改有效设置后替换本应用周期计划；无实际变化的保存不制造新触发。亮屏/息屏额外扫描不重置定时节奏。

配置属于应用运行时，不属于Activity。窗口编辑草稿，观察回调不能覆盖用户正在输入的文字。关闭或重建窗口不改变已保存配置；未保存草稿不称为已保存。

## 2. 精确名称与本次扫描候选

目标规则：保存完整名称原文，拒绝全空白值；名称中的空白和大小写均有意义，**仅当前BLE广告中的完整名称与保存值相等才命中**。采集保留原始广播名，展示可独立处理空白，不能先trim广播再冒充原文匹配。不做关键词contains，不使用`BluetoothDevice.name`或已配对缓存证明当前存在，不把无名广播、占位文字或旧`35:BA`后缀当作命中。原后缀/Xiaomi前缀规则被本次名称配置语义取代；与之直接相关的旧产品测试更新为新规则，其余安全测试保留。

下拉候选来自现有手动「扫描蓝牙」的真实8秒BLE观察，不是经典蓝牙发现、所有配对设备列表或持久历史回放：

- 复用原最多100个设备的去重采集，不引入无界列表或另一套扫描器。
- 使用`advertisementName`区分真实名称与`未知设备`占位；真正广播名恰为`未知设备`仍是合法名称。无名设备可披露，但不能把占位词保存为该设备的名称目标。
- 下拉展示可带地址以区分设备，选择只填入真实名称，不把展示用的地址串存成目标，也不隐式绑定MAC。同名设备在名称策略下不能区分，任一个相同完整广播名均可命中。
- 独立、进程内、带operationId的“本次扫描候选”快照，经现有exact-owner回调更新；新SCAN被接纳时清空，结束后保留该次最后候选供选择。旧回调不能覆盖新操作的候选。
- 不从`manual_results`、cold fence说明或Activity保存状态恢复候选。进程死亡后候选为空，但已保存目标文字仍在。
- 候选变化只更新adapter，不重建窗口、不覆盖草稿、不恢复旧SCAN设备卡、不回放Toast。手动扫描仍只扫描，不自动开蓝牙、不重启、不配对或连接。

每次自动扫描、手动扫描摘要在操作接纳时冻结目标配置；保存新目标只影响后续操作，当前已接纳扫描和安全恢复不会中途换目标。扫描观察、目标认领和开关准入仍是不同事实。

## 3. 普通监控宿主与单次工作分离

推荐独立、非导出的`connectedDevice`监控前台服务，承担用户显式开启的监控生命周期、动态屏幕监听和任务期间短WakeLock。独立静默常驻通知使用不与100—105及runId历史卡冲突的身份，点击直接进入现有真实详情Activity。

空闲监控宿主不是一次蓝牙操作，不能永久占据现有`serviceDone`/admission或automatic owner。原`ResetService`继续作为单次操作的确切run/token/startId、`START_NOT_STICKY`宿主；不能简单把它改成空闲常驻。

监控前台能力须由实际成功`startForeground`、当前宿主身份和存活lease支持。监控内的每次扫描仍拥有自己的exact admission、runId、有限lease、finish和清理身份。SCREEN_ON/OFF本身不视为可在后台启动另一前台服务的豁免；使用已经成功入场的监控宿主能力，不伪造单次服务接管证据。

既有单次图标/RUN/广播行为继续可用；所有新来源进入同一主线程协调器、RuntimeOperationGate、checked pending/manualFence与scanner retirement。空闲监控不能妨碍合法手动操作，正在进行的工作、恢复或未知authority仍阻止新工作。

关闭自动触发立即取消周期计划和屏幕监听并禁止新自动请求，**不得取消原OFF→ON恢复闹钟或清空pending**。已有工作按原安全边界结束；必要时延后宿主真正停止，直至本次有限工作窗口完成。宿主销毁或WakeLock释放不证明native执行结束。

## 4. 定时与屏幕事件

定时采用独立单次`ELAPSED_REALTIME_WAKEUP`闹钟；有exact access时使用`setExactAndAllowWhileIdle`，无访问时明确显示降级，使用allow-while-idle的尽力安排。不使用Handler延时冒充CPU唤醒。

- 周期闹钟的显式、immutable PendingIntent身份与原安全恢复完全分离；保存设置revision和独立、每次消费后递增的周期ticket generation，取消/替换周期计划不触碰恢复身份。重复及旧ticket不再执行。每个ticket的exact/inexact来源采用两个稳定URI子身份，与实际arm方法对应；当前delivery来源不能由当前权限、下一次arm结果或可被creator更新的extra推断。失败的exact重排不得把旧inexact delivery重新标记为exact；周期取消只处理自己的两个子身份。
- 每个周期到达先验证当前已保存ticket，checked消费并保存新的下一次ticket、成功安排下一次，再申请本次扫描。安排失败禁止该次新扫描，如实报告，不声称监控计划健康。
- 已验证事件形成独立、进程内、一次性的触发attempt；下一次generation变化不使当前attempt自我失效。冷宿主就绪前配置改变/关闭会丢弃未接纳attempt，就绪时核对attempt与设置revision而非已消费的旧alarm。只有真实宿主和exact child成功接纳后才冻结目标；关闭配置不会撤销该child及其原安全恢复。无exact access的inexact冷闹钟不冒充exact FGS启动豁免，缺少合法宿主/机会时跳过新工作。
- 正常时以当前实际调度点加间隔重排，系统延迟后只处理当前一次，不补齐所有漏扫，不建积压队列。
- 定时、亮屏、息屏共用自动触发准入；忙碌/隔离直接跳过，不抢占。相近自动事件合并，自动触发采用30秒最短间隔避免反复亮灭屏造成扫描节流和整机蓝牙反复断开；手动请求仍走原手动准入，不由此冷却授权或解锁。
- 屏幕事件在存活服务内动态注册`ACTION_SCREEN_ON/OFF`，按系统交互状态而非解锁、AOD刷新理解。进程死亡期间丢失的屏幕边沿不补发，不靠manifest监听假称冷进程可靠接收。

## 5. 息屏BLE与能耗

自动扫描使用真实非空`ScanFilter`，其deviceName为本次冻结的完整目标名称，仍经同一精确名称规则核对当前广告。不能用空过滤器规避平台行为，不猜测目标UUID/厂商数据，不把上次扫描名称或地址当成本次命中。

自动过滤后的设备数是本次实际接纳的设备数，不等于周边所有BLE设备数量；手动扫描保留原广泛观察和100/100+规则。日志仍只有进程启动、扫描结论、开关结论三类紧凑纯文本，过滤扫描范围可在扫描结论中简短注明，不新增每个tick/拒绝事件日志。

普通监控服务不一直持有WakeLock。任务期间获取带严格超时的PARTIAL_WAKE_LOCK，并覆盖正常结束、启动失败、取消、宿主退出和异常释放；锁超时不清除native/scanner隔离，不借锁延长receiver执行预算。新monitor child在实际宿主接纳后采用30秒初始自动工作上限，保留原8秒真实观察及原prepare/start/startup预算；不在等待冷宿主或用户授权时持该锁。每个被原恢复gate接纳的恢复步骤使用15秒独立slice，不因为pending仍在就无限续锁；quarantine/exhaustion停止续锁，迟到的exact结束证明仍按原恢复协议接纳。

锁屏不等于深度Doze。普通方案以设置间隔为期望，不保证深度Doze/OEM下严格三分钟。官方说明allow-while-idle仍有分钟级配额（常见九分钟，API文档还提示更长），FGS不保证CPU醒着，Shizuku可用不自动授予应用全部权限。名称过滤也不保证手环正在广播或一定收到scan response。

## 6. 权限、恢复与可见状态

详情提供显式精确闹钟访问和电池优化设置入口，查询实际`canScheduleExactAlarms`与电池优化状态。第一阶段只引导用户在系统页面给本应用授权/无限制，不自动执行deviceidle、standby bucket或AppOps命令。附近设备、通知、Shizuku、日志所有文件访问仍各自独立。

显示保存的启用选择与实际监控宿主/下一次计划/权限阻断状态，不能只因`enabled=true`就声称服务活着或任务准时。拒绝和延期不每次刷Toast或新增重启卡。

独立监控服务采用START_NOT_STICKY，不承诺系统无条件重建；普通进程回收后，合法周期闹钟可能提供冷启动机会，需重新验证保存设置、权限、宿主能力和安全fence。仅屏幕触发的配置在进程死亡后需重新打开应用建立监听，丢失边沿不补发。恢复监控不重放旧SCAN/旧OFF，不清空未知native/scanner或manualFence。无boot receiver：重启后保存配置仍在，但须重新打开应用建立监控；force-stop/OEM强杀不承诺自恢复。

## 7. 存储与故障

配置采用独立checked存储，一次原子保存完整验证后的设置和revision，运行时只发布last-confirmed快照。SharedPreferences的false commit可能已改内存cache，重新读取不是保存成功证明；false/exception不报告成功，不把未确认目标用于新扫描。

初次没有配置采用上述默认值；已有配置损坏须披露并暂停新自动触发，不能静默换回旧手环并运行。配置存储失败与原安全ledger故障边界分开，不用配置重存解除pending/manual/native隔离，不因展示异常改变事务结果。设置提交失败暂停新自动触发，保留安全恢复和原last-confirmed配置；明确提示未保存。

## 8. 不可退化的执行边界

保留Shizuku single-slot、non-daemon UserService、disconnected barrier/quarantine，固定AIDL和`/system/bin/svc bluetooth enable|disable` argv；不增加任意命令、direct su或private API。只有执行结束/EOF等既有证明才能解除authority；超时、取消、kill请求、硬件目标、服务或进程死亡均不是结束证明。

保留准确scanner/callback retirement、独立清理lane、8秒普通/手动与6秒受限扫描、receiver backstop、claim-before-teardown、arm-before-OFF、expected run/command/version、checked历史/计数终态和manual OFF保持关闭。新触发不是native授权。未命中不关闭；只有完整可靠的同次OFF→ON才计数。

## 9. 验证与交付

新增生产使用seam的行为RED/GREEN：配置保存/重启/false-commit eager-cache/损坏；精确名称/旧后缀拒绝/无名/占位/换目标快照；本次候选/terminal后保留/新operation清空/迟到/不回放旧store；三触发任意组合、间隔默认/边界、generation、冷启动、无积压、防抖和独立alarm；监控宿主真实入场与exact child admission、空闲不阻断手动、宿主停止/销毁保留authority、短锁所有退出；实际自动ScanFilter接线和保留手动无重启契约。

原424基线测试中仅直接被本次产品语义取代的名称/无周期文案/组件数量/版本断言允许更新并记录精确名称差异，不放宽native、SDK、cleanup、恢复、存储或执行预算。新增行为后完整回归，规格→质量→最终整体审查；单一实现writer/Gradle owner。无真机时不能把JVM/source/APK检查当锁屏、Doze、BLE或升级已通过。

交付1.4，本地code5，CI run number+5；同包名、namespace、SDK31/32/34、原证书`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。签名输入仅构建进程内部使用，不模型读取/生成/替换/提交密钥。构建前核验并保留真实1.3、1.2、1.1持久APK；两次最终完整签名构建，第一份先保存构建目录外，再核验第二份实际manifest/DEX/hash/证书/JUnit并保存`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.4.apk`及输出副本。

项目编辑使用native Write/Edit；仅当前分支attributed提交与正常push，核对远端SHA；不更改GUI/main/CI/wrapper，不PR、force push、发布rolling latest或替换用户根design.md。CI和真机只报告真实核验结果，受限步骤不得绕过权限。

## 平台资料依据

- https://developer.android.com/training/monitoring-device-state/doze-standby
- https://developer.android.com/reference/android/app/AlarmManager
- https://developer.android.com/reference/kotlin/android/bluetooth/le/BluetoothLeScanner
- https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start
- https://developer.android.com/develop/connectivity/bluetooth/ble/background

本轮官方页面正文抓取受限，上述平台说明核对自官方搜索索引；锁屏实际节奏、过滤器与目标手环广播仍须设备验收。

## 实际交付核验（2026-11-27）

各任务规格/质量及最终整体源码审查通过。父会话对最终111路径与43suite/610准确case契约两次完整强制test/debug/release/lint，分别21秒、22秒，均97任务全部执行、610测试零失败/错误/跳过；源码hash与Task1六文件不变。第一份先独占保存在构建目录外，两份正式APK均经实际aapt/apksigner/DEX核验，同单一原7825cc证书/v2签名；原1.1/1.2/1.3及真实备份、旧输出副本保持原hash。

第二份实际交付：`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.4.apk`，输出副本`app/build/outputs/apk/release/bt-monitor-nogui-1.4.apk`。包名`com.blreay.bluetoothmonitor.nogui`，1.4/code5，2768133字节，APK SHA256`49caf1f4e5991ba6c1ab69b14dc7e57bcea50c236906a0952d346c994c726ea3`；证书SHA256`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`。实际manifest保持NoDisplay/原入口和权限边界，新nonexported监控组件/connectedDevice/WAKE_LOCK通过；1个DEX、1725定义、71个必需类真实存在，退役两类不存在。复制后两路径逐份重验，符合原1.3/code4覆盖升级身份条件，不等于已经真机升级。

公开执行与APK证据：`/tmp/btreset-1.4-final-contract-7krheC83/contract.json`、`/tmp/btreset-1.4-parent-signed-first-kuu5zsx5/`、`/tmp/btreset-1.4-parent-signed-second-0oo21_tg/delivery-verification.json`。Lint仍13Warning+1FatalExpiredTargetSdkVersion，MissingPermission/Error0，不是lint-clean。实际adb设备列表为空；覆盖升级/数据权限保留、设置与下拉交互、BLE/锁屏/Doze/屏幕触发、短锁能耗/停止保恢复、Shizuku/OEM/通知历史累计均未做现场验收。未安装模拟器、未触发CI发布workflow或修改rolling latest，不能把本地核验冒称设备或CI通过。
