# 1.3：紧凑纯文本日志与横排控制按钮

日期：2026-11-27。分支：`bluetooth_monitor_shizuku_nogui`。基线：`bf045315364af7d1f39c4744dabf047474693aef`。

状态：用户已对下面的轻量方案明确回复「OK」。这是本轮唯一审批门；实施、测试、审查、同签APK和当前分支推送连续完成，不再中途询问是否继续。

## 已确认的目标

日志只写秒级本地时间戳和简短纯文本描述，例如：

```text
2026-11-27 08:20:01 进程启动
2026-11-27 08:20:09 扫描：5个设备，目标手环：有
2026-11-27 08:20:10 关闭蓝牙：Shizuku，成功
2026-11-27 08:20:12 打开蓝牙：普通，成功
```

- 仅保留进程启动、扫描结束摘要、蓝牙关闭/开启三类新记录。
- 不记录JSON、PID/session/UUID、源/接收/投递时间字段、毫秒/纳秒、广告逐条事件、设备名称/MAC/RSSI列表、入口/授权/清理协议事件或`log.dropped`行。
- 真正主应用进程启动记录一次；复用同一进程的launcher/RUN不伪称进程重启，也不额外写入口事件。
- 扫描数量按本次实际接受的去重设备数，不是广播次数。手动100个保留上限溢出显示`100+`，不编造精确总数。目标是否出现需记住每个有效当前广告的匹配，不能因超限丢弃或后续名字变化丢失已发现目标的事实。
- 自动扫描与手动扫描均写摘要。取消/失败/未执行明确简短说明，部分扫描不伪称完整扫描；目标观察不等于已接纳或已完成重启。
- ON/OFF显示实际使用的Shizuku或普通方式；未调用、已处于目标、失败及状态/执行未知如实标明。Shizuku授权或能力可用不是本次实际模式证据，SUBMITTED不是成功。普通后备不能继承旧命令的模式。
- 正常流程尽量每次扫描/每个方向一条结果摘要，不重放旧store、snapshot或每个状态轮询。未知后来有新可靠结论可以更新反馈/记录，但不恢复详细事件流。

## 轻量实现边界

删除多余生产日志调用点和通用字段捕获，不只在最终sink过滤事件名。移除只为1.2详细日志服务的装饰/映射、JSON编码、身份生成和计时；小型纯字符串摘要函数允许存在，但不能成为第二套蓝牙策略。

保留已有独立单一后台writer、有限队列、固定共享/私有路径、完整UTF-8单行和文件滚动。新单行紧凑有界，控制字符/换行不允许生成伪记录。日志异常不改变蓝牙事务或执行隔离。低频摘要使用小型有限队列，不增加线程/周期任务。

`/sdcard/miband.log`仍指当前Android用户主共享存储根目录，最大3MiB（3145728字节），超限删除最老至少1MiB（1048576字节）并延伸到完整换行。私有pending继续同一有限策略；all-files权限仍在实际worker写入时检查，未授权/撤销/失败私有暂存，恢复异步导出。

**旧日志不主动清空。** 已有根文件和私有pending的1.2旧行按原字节保留/导出，新记录用纯文本，旧格式随正常滚动逐步淘汰。不增加旧JSON转换器，不删除历史、不新增分卷。写入/确认失败可能回放旧行；简洁日志仍不是exactly-once事务。强杀前的内存记录和外部篡改/断电不作绝对保留保证。

## 详情页

```text
[打开蓝牙] [关闭蓝牙] [扫描蓝牙]
[系统蓝牙设置]     [应用权限设置]
```

- 三个操作按钮一行、等分可用宽度；两个系统设置按钮一行、等分宽度。窄屏文字允许换行，不以横向滚动隐藏按钮。
- 删除手动SCAN结果TextView及渲染，不显示设备列表。ON/OFF本已使用消息，SCAN也使用实际controller产生的非持久结果消息：数量、目标是否出现、必要失败/未知原因。正常完成消息一次，不每广告弹消息、不从初始snapshot/重建窗口重放。
- 删除`logBody`、日志说明/状态描述和仅为该描述服务的可见每秒重绘。保留简短「日志权限」按钮、显式all-files系统设置及fallback、onResume一次异步refresh/导出；后台不打开权限页。
- 当前自动状态、所选历史、累计、已有权限/恢复入口保持；持久手动结果和安全fence不删除。关闭窗口不取消执行、不解除native/scanner占用。

## 版本、安全与交付

升级为1.3、本地versionCode4、CI run number+4。同applicationId `com.blreay.bluetoothmonitor.nogui`、namespace、SDK31/32/34和生产依赖不变；证书必须保持`7825ccfb3eb4fa47d7bd87ff70c82800fb0801d67907c69d2825f6c2bb492c9a`，只允许构建进程内部读取既有私密配置，不模型读取/生成/替换/提交密钥。

不改固定AIDL/svc argv、Shizuku single-slot/disconnected barrier/quarantine、native EOF authority证明、exact FGS、retained scanner cleanup、6秒受限广播/8秒普通及手动观察、arm-before-OFF、expected version、checked历史计数或手动未知OFF fence。内部用于安全窗口的ms/nanos继续保留，去掉的是日志中的精细计时。

所有项目改动native Write/Edit，留在现有worktree；单一实现writer/Gradle owner，不bare stash、不转原checkout、不改GUI/main/CI/wrapper、不PR或发布rolling latest。已保存的实际1.2升级输入为`/home/admin/bluetooth-monitor-apks/bt-monitor-nogui-1.2.apk`，2652533字节、SHA256`60cfa2fffcc65ae6ad1197855a27e02dde23e1b22fd79211f65872bc0f3d497d`；额外核验备份`/tmp/btreset-1.3-baseline-sqj_26dn/original-delivered-1.2.apk`。

先行为RED/GREEN、规格/质量及整体审查，再父fresh两次test/debug/release/lint、实际APK/签名/旧文件保留核验，产物保存到构建目录之外，当前分支attributed commit/push并检查远端SHA/clean tree。真机仅有已授权设备才验收；无设备逐项说明未执行，不把JVM/APK检查当现场升级、权限、Toast或BLE验证。
