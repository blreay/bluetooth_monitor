# 蓝牙重启 v2 设计：去窗口化 + 状态驱动重开

**日期：** 2026-11-21
**状态：** 已批准，待实现
**取代：** `2026-11-20-bluetooth-band-reset-design.md` 中关于执行载体和重开机制的部分。功能需求（做什么）不变，变的是怎么做。

---

## 1. 起因：真机实测暴露的四件事

v1 在小米 Android 13+ 真机上跑过之后，用户反馈四条：

1. 关蓝牙时会弹一个框，问能不能申请一个"蓝牙安全控制权限"让它别弹
2. **蓝牙关掉之后再也没有打开** —— 核心功能坏了
3. 希望命中手环时在屏幕上静默显示一行消息，不用确认，一会儿自己消失
4. **很严重的卡顿，太明显了** —— 要求彻底改机制，不要前台 Activity，改成服务，"完全没有 activity 那种"，不想看到窗口，希望对正常使用手机没有任何影响

其中 2 和 4 是缺陷，1 是平台限制问题，3 是新需求。

### 1.1 缺陷 2 的根因：用定时器驱动一个异步状态机

证据来自本地 Android SDK 的官方框架存根 javadoc（`platforms/android-34/android-stubs-src.jar` → `android/bluetooth/BluetoothAdapter.java`），逐字引用：

> `isEnabled()` — Return true if Bluetooth is currently enabled and ready for use. **Equivalent to: `getBluetoothState() == STATE_ON`**

> `disable()` — **This is an asynchronous call**: it will return immediately, and clients should **listen for `ACTION_STATE_CHANGED`** to be notified of subsequent adapter state changes. If this call returns true, then the adapter state will immediately transition from `STATE_ON` to `STATE_TURNING_OFF`, and **some time later** transition to either `STATE_OFF` or `STATE_ON`.

> `enable()` — If this call returns **false** then there was an **immediate problem** that will prevent the adapter from being turned on - such as Airplane mode, or the adapter is already turned on.

v1 的 `BluetoothSwitch.enableIfOff()` 用 `if (adapter.isEnabled)` 作为"需要重开"的判据。但蓝牙正在关闭的那段时间里状态是 `STATE_TURNING_OFF`，此时 `isEnabled()` 已经是 `false`。于是：

```
disable() 返回 true（仅表示"开始关了"）
  → 固定 3s / 3.5s 定时器到点
  → isEnabled() == false（因为还在 TURNING_OFF）
  → 调 enable()
  → 撞上文档说的 "immediate problem"，返回 false
  → 之后再没有任何东西重试
  → 蓝牙永远关着
```

MIUI 要 "gracefully shuts down all Bluetooth connections"（手环、耳机等），关完远不止 3 秒。文档给的正确做法是监听 `ACTION_STATE_CHANGED`，v1 用了定时器。这是设计错误，不是厂商行为。

用户确认弹框是 **MIUI 的权限询问框，带"允许/拒绝"**，这构成第二个死因：`disable()` 返回之后 MIUI 先弹框等人点，点完蓝牙才开始关；3 秒定时器在人点下去之前就跑完了。关蓝牙需要多久取决于人什么时候点，**固定时长在这种场景下不可能对**。

### 1.2 缺陷 4 的根因：隐形 Activity 长期持有输入焦点

v1 的 `MainActivity` 用半透明主题，从 `onCreate` 一直活到 `report()`：

- 没命中手环 → 活满 8 秒扫描窗口
- 命中手环 → 活 `IN_PROCESS_DELAY_MS + FINISH_GRACE_MS` = 3.5 秒

半透明主题让桌面**仍然可见**，但**输入焦点在这个隐形 Activity 身上**。结果是桌面看得见、点不动，持续 3.5~8 秒。这就是"很严重的卡顿，太明显了"。

这是 v1 为了保活进程、防止 MIUI 在 3 秒等待期内回收进程而**故意**做的设计（`MainActivity.kt` 的类注释写明了理由）。它换来了进程优先级，代价是长时间劫持焦点。v2 把"保活"责任整体转交给 AlarmManager + SharedPreferences，Activity 因此可以立刻消失。

### 1.3 需求 1 的答案：没有可申请的权限，但有 MIUI 设置项

官方 javadoc 的豁免清单逐字如下：

> Deprecation Exemptions:
> - Device Owner (DO), Profile Owner (PO) and system apps.

豁免只有这三类身份，**没有任何"申请一个权限就不弹窗"的机制**。`BLUETOOTH_PRIVILEGED` 是 `signature|privileged` 级别，第三方应用拿不到，运行时申请也不会产生对话框。

但 MIUI 把"开关蓝牙"当成一个**独立的私有权限**管理，默认值是"询问"，这正是用户看到的弹框。把它改成"允许"之后 `disable()` 和 `enable()` 都不再弹框。应用侧无法代劳，只能写进 README 当必做项，且它是最重要的必做项 —— 不设置的话每次运行都要手点两次弹框。

路径（HyperOS 上入口名称可能略有出入，在"应用信息"页里找"权限管理"）：

```
设置 → 应用设置 → 应用管理 → 蓝牙重启 → 权限管理 → 开关蓝牙 → 允许
```

### 1.4 需求 4 中"完全没有 activity"不可行，但目标可达成

Android 上桌面图标**必须**是一个带 `MAIN` / `LAUNCHER` intent-filter 的 Activity。桌面（含 MIUI 桌面）通过 `PackageManager` 枚举 Activity 来生成图标列表，Service 和 BroadcastReceiver 永远不会出现在桌面上。所以"完全没有 activity"与原始需求"要有图标，可以点击"直接冲突。

但 Activity 可以做成 `Theme.NoDisplay` + 在 `onCreate` 里立刻 `finish()`：**系统不为它创建窗口，它拿不到输入焦点，几十毫秒就消失**。图标保留，卡顿根除。用户要的两样都能有，这是 v2 采用的做法。

---

## 2. 架构：快段 / 慢段彻底分离

按"时长能不能预测"切分，这是 v2 的核心决定。

| | 快段 | 慢段 |
|---|---|---|
| 做什么 | 扫描 → 命中 → Toast → `disable()` → 布置闹钟链 | 等真关完 → `enable()` → 等真开完 |
| 时长 | ≤8 秒，有界 | 不可预测（MIUI 弹框等人点、蓝牙优雅断连） |
| 载体 | started Service（图标路径）/ `goAsync()` Receiver（广播路径） | AlarmManager + manifest Receiver，每秒一跳 |
| 状态存哪 | 内存 | **SharedPreferences**，进程被杀也在 |
| 被杀的后果 | 什么都没布置，蓝牙还开着，无半状态 | 无影响，store + 闹钟自行恢复 |

**进程内定时器全部删除。** 慢段唯一的真相来源是 `getState()`，不是时间。

`Theme.NoDisplay` 的约束是必须在 `onResume()` 之前 `finish()`，所以 NoDisplay 的 Activity 不能承载 8 秒扫描 —— 这正是需要 Service 作为快段容器的原因，而不是为了"看起来更像服务"。

### 2.1 为什么慢段不用前台 Service

前台 Service 进程优先级最高、最抗杀，但：

- 运行期间状态栏会有一条通知，与"不想看到窗口、对正常使用没有任何影响"冲突
- Android 12+ 后台启动前台 Service 会抛 `ForegroundServiceStartNotAllowedException`，**广播入口会直接废掉**
- targetSdk 32 还需补 `foregroundServiceType`

started（非前台）Service 靠的是"Android 8+ 后台 Service 在应用退到后台后还能跑几分钟"这个窗口，快段只有 8 秒，余量很大。真被 MIUI 提前杀也不致命 —— 慢段不在 Service 里。

**代价（必须诚实记录）：慢段完全依赖 MIUI「自启动」开着**，否则闹钟拉不起进程，蓝牙会被留在关闭状态。v1 里这条是"兜底"，v2 里它是"唯一路径"，README 的语气要相应加重，与「开关蓝牙」并列为两个必做项。

### 2.2 为什么广播入口不 startService

Android 8+ 禁止后台应用 `startService()`，会抛 `IllegalStateException`。`TriggerReceiver` 是纯后台入口，所以它继续用 `goAsync()` 内联跑快段（6 秒扫描窗口，因为 `goAsync()` 只有约 10 秒预算），跑完布置闹钟链后交回。两条入口共用同一个快段实现和同一条慢段。

---

## 3. 核心不变量

> **只在 `getState() == STATE_OFF` 时调 `enable()`。永不在 `STATE_TURNING_OFF` / `STATE_TURNING_ON` 期间调。**

推论：

- `BluetoothSwitch.enableIfOff()` 连同它的 `isEnabled` 判据一起删除。什么时候该 enable 由 policy 决定，不由 `BluetoothSwitch` 决定
- v1 的另一条不变量继续成立：**只有 `disable()` 返回 `true` 之后才布置闹钟链**。否则 MIUI 拒绝时我们会去开一个本来就开着的蓝牙
- 快段入口守卫从 `!isEnabled` 改成 `state != ON`，这样连点两次图标不会触发第二次 `disable()`

---

## 4. 慢段状态机

### 4.1 纯函数接口

```kotlin
object ReEnablePolicy {
    enum class Phase  { WAITING_OFF, WAITING_ON }
    enum class Action {
        ENABLE_AND_RETRY,
        RETRY,
        DONE_RESTORED,
        GIVE_UP_NO_ADAPTER,
        GIVE_UP_NEVER_TURNED_OFF,
        GIVE_UP_NEVER_TURNED_BACK_ON,
    }

    const val MAX_ATTEMPTS = 120

    fun decide(state: AdapterState, phase: Phase, attempts: Int): Action
}
```

`ReEnablePolicy` **零 Android import**，可在纯 JVM 上单测。为此 `getState()` 返回的框架 int 先在 `BluetoothSwitch` 里映射成我们自己的 `AdapterState` 枚举：

```kotlin
enum class AdapterState { OFF, TURNING_ON, ON, TURNING_OFF, UNKNOWN }
```

`UNKNOWN` 覆盖三种情况：拿不到 `BluetoothAdapter`、`getState()` 返回了四个已知值之外的东西、以及 `getState()` 抛异常（缺 `BLUETOOTH_CONNECT` 时可能）。映射由 `BluetoothSwitch.state()` 用真常量完成。

### 4.2 求值顺序

顺序是有意义的 —— **成功判定必须先于放弃判定**，否则第 120 跳刚好开成功会被误报成失败。

1. `state == UNKNOWN` → `GIVE_UP_NO_ADAPTER`
2. `phase == WAITING_ON && state == ON` → **`DONE_RESTORED`**
3. `attempts >= MAX_ATTEMPTS` → `phase == WAITING_OFF` 时 `GIVE_UP_NEVER_TURNED_OFF`，`phase == WAITING_ON` 时 `GIVE_UP_NEVER_TURNED_BACK_ON`
4. `phase == WAITING_OFF`：
   - `OFF` → `ENABLE_AND_RETRY`（执行后 phase 切到 `WAITING_ON`）
   - 其余（`ON` / `TURNING_OFF` / `TURNING_ON`）→ `RETRY`
5. `phase == WAITING_ON`：
   - `OFF` → `ENABLE_AND_RETRY`（上次 `enable()` 被拒了，再来一次）
   - 其余 → `RETRY`

### 4.3 MAX_ATTEMPTS = 120 的理由

120 跳 × 1 秒 = 2 分钟上限。这个值**必须大于"一个人看到 MIUI 弹框并点下允许"所需的时间**。

如果设成 5 跳，用户 6 秒才点，状态机就先放弃了，然后蓝牙才关掉 —— **比 v1 更糟**（v1 至少不会主动放弃）。放弃时日志带上 phase 和最后一次 state，`NEVER_TURNED_OFF`（用户点了拒绝）和 `NEVER_TURNED_BACK_ON`（`enable()` 被拦）能直接分辨，所以不需要额外的"still ON"计数器。

### 4.4 两个 Action 的副作用区别

- `ENABLE_AND_RETRY`：调 `BluetoothSwitch.enable()`（记录返回值，但**不**据此决定下一步 —— 下一步仍由下一跳的 `getState()` 决定），写回 `phase = WAITING_ON` 且 `attempts + 1`，再排一跳
- `RETRY`：`phase` 不变、`attempts + 1` 写回 store，再排一跳
- `DONE_RESTORED`：Toast「蓝牙已恢复」，`clear store`，`cancel alarm`
- `GIVE_UP_*`：记日志（含 phase、attempts、last state），`clear store`，`cancel alarm`

所有放弃路径都清 store 并取消闹钟，不留悬挂闹钟。

---

## 5. 组件与文件

### 5.1 新增

| 文件 | 职责 |
|---|---|
| `PermissionActivity.kt` | **全项目唯一会有窗口的地方**，只在首次授权时出现。半透明主题，`requestPermissions` → 授权后 `startService(ResetService)` → `finish()`；被拒则记日志 + Toast 说明无法工作 + `finish()`，不重试、不死循环 |
| `ResetService.kt` | started（非前台，无通知）。`onStartCommand` 跑快段，回调后 `stopSelf()`，返回 `START_NOT_STICKY` |
| `ReEnablePolicy.kt` | §4.1 的纯决策函数 |
| `AdapterState.kt` | 蓝牙状态的裸枚举，零 Android import。int→枚举的映射不放在这里，而是放在 `BluetoothSwitch.state()` 里直接用 `BluetoothAdapter.STATE_*` 真常量 —— 手抄 10/11/12/13 只会让测试验证"抄的和抄的一致"，而抄错 `TURNING_ON`/`TURNING_OFF` 就会重现本设计要修的 bug |
| `PendingResetStore.kt` | SharedPreferences 存 `{armed, phase, attempts, wantsToast}`，慢段唯一真相来源。跨进程死亡存活。`wantsToast` 必须存进来而不是靠 Intent 传：慢段的每一跳都是系统重新拉起进程后的一次独立 `onReceive`，拿不到当初那次启动的 Intent |
| `Notifier.kt` | **唯一碰 Toast 的地方** |

### 5.2 重写

| 文件 | 改动 |
|---|---|
| `MainActivity.kt` | 主题改 `@android:style/Theme.NoDisplay`。`onCreate`：权限缺 → 起 `PermissionActivity`；权限齐 → `startService(ResetService)`；**然后无条件 `finish()`**。删除"故意多活 3.5 秒保前台"的逻辑及其类注释 —— 它正是 §1.2 卡顿的成因 |
| `ReEnableReceiver.kt` | 闹钟单跳处理器：读 store → `getState()` → `decide()` → 执行 Action |
| `ReEnableScheduler.kt` | 改成 `armNextTick()` / `cancel()`，用 `setExact(RTC_WAKEUP, now + 1000)`。`canScheduleExactAlarms()` 为 false 时降级 `setAndAllowWhileIdle` 并大声记日志 |

**为什么重试用 `setExact` 而不是 `setExactAndAllowWhileIdle`：** 后者在 idle 状态下有"每 9 秒最多一次"的节流，会把 1 秒重试链拖垮。用户刚点了图标，屏幕是亮的，不在 Doze 里，`setExact` 足够；降级路径才用 `setAndAllowWhileIdle`。

### 5.3 修改

| 文件 | 改动 |
|---|---|
| `BluetoothSwitch.kt` | 删 `enableIfOff`；加 `state(context): AdapterState` 和裸 `enable(adapter): Boolean`。`disable()` 不变。仍是全项目唯一碰 deprecated `enable`/`disable` 的地方 |
| `BandResetController.kt` | 命中时调 `Notifier.bandFound()`；`cycleBluetooth()` 里 `disable()` 返回 true 后改成 `PendingResetStore.arm()` + `ReEnableScheduler.armNextTick()`；入口守卫改 `state != ON`；删掉 Session 里为重开准备的 Handler 用途（扫描超时仍需要 Handler） |
| `TriggerReceiver.kt` | 逻辑基本不变（继续 `goAsync()` 内联跑快段，6 秒扫描），重开手段换成闹钟链。**不调 `startService`**（§2.2） |
| `AndroidManifest.xml` | MainActivity 换主题；新增 `PermissionActivity`（`exported=false`）和 `ResetService`（`exported=false`）；`TriggerReceiver`（`exported=true` + filter）和 `ReEnableReceiver`（`exported=false`，无 filter）不动 |
| `README.md` | MIUI「开关蓝牙」权限提到必做配置第一行；「自启动」语气加重（§2.1）；重写工作原理图和日志表；更新 Toast 说明 |

### 5.4 明确不动

targetSdk 32、`keystore/release.jks`、`BandMatcher` 三个常量、CI workflow 的十个步骤、minSdk 31、"只看 BLE 扫描结果、已连接不算"的判定、四个触发入口、`Theme.BtReset.Invisible`（保留给 `PermissionActivity`）。

---

## 6. 需求 3：Toast

`Notifier.bandFound()` 在**命中手环的那一刻**发，无条件，不看任何 extra：

```
已找到手环，正在重启蓝牙…
```

`Toast.LENGTH_SHORT` ≈ 2 秒自动消失，不需要点确认。`DONE_RESTORED` 时再发一条：

```
蓝牙已恢复
```

从后台进程发**文本** Toast 是允许的 —— Android 11 只禁止后台应用发**自定义视图** Toast，我们用的是 `Toast.makeText`。用 `context.applicationContext` 构造，避免持有 Service/Receiver。

`--ez toast true` 调试开关**保留并扩大**：默认只在命中和恢复时出声；加上它则每个结局都出声（`NOT_FOUND`、`BLUETOOTH_OFF`、`DISABLE_FAILED`、`GIVE_UP_*`）。extra 由 `MainActivity` 从启动 Intent 读出，透传给 `ResetService`，再由 `PendingResetStore.arm()` 落盘（§5.1），慢段各跳据此决定是否出声。两条入口都支持：`am start ... --ez toast true` 和 `am broadcast ... --ez toast true`。

**保留这个开关的理由：** v1 之所以只能靠 javadoc 反推根因，就是因为真机上看不到任何输出。有了常开 Toast，用户以后不接电脑也能自己读出结局。

**已知风险（诚实记录）：** MIUI 有可能压制后台应用的 Toast。若实测看不到，备选是改用短暂 heads-up `Notification`。先按 Toast 实现，实测说了算。

---

## 7. 数据流

```
点图标 → MainActivity (NoDisplay, onCreate 内立刻 finish)
           ├─ 权限缺失 → PermissionActivity (唯一可见窗口, 仅首次)
           │                └─ 授权 → startService(ResetService)
           └─ 权限齐全 → startService(ResetService)
                          ↓
                       ResetService → BandResetController        【快段 ≤8s】
                          ├─ 无适配器 / state != ON / 权限缺失 → stopSelf
                          ├─ BLE 扫描 8s
                          │    ├─ 未命中 → stopSelf
                          │    └─ 命中 → Notifier.bandFound()
                          │              → disable()
                          │                  ├─ false → stopSelf (不布置闹钟)
                          │                  └─ true  → arm(WAITING_OFF, attempts=0)
                          │                             → armNextTick(+1s)
                          │                             → stopSelf
                          ↓ 进程从这里开始随时可以被杀
                       ReEnableReceiver  每秒一跳                 【慢段 ≤120 跳】
                          decide(getState(), phase, attempts)
                          ├─ ENABLE_AND_RETRY → enable() + 写回 + 再排一跳
                          ├─ RETRY            → attempts+1 + 再排一跳
                          ├─ DONE_RESTORED    → Toast「蓝牙已恢复」+ 清 store + 取消闹钟
                          └─ GIVE_UP_*        → 记日志 + 清 store + 取消闹钟
```

广播入口：

```
sendBroadcast(RESET_IF_PRESENT) → TriggerReceiver.goAsync()
                                     └─ 同一个 BandResetController，扫描窗口 6s
                                        └─ 之后共用同一条慢段
```

---

## 8. 错误处理

| 情况 | 结果 |
|---|---|
| 拿不到 `BluetoothAdapter` | `UNKNOWN` → 第一跳就 `GIVE_UP_NO_ADAPTER` |
| 用户在 MIUI 弹框点了**拒绝** | state 一直 `ON` → 120 跳后 `GIVE_UP_NEVER_TURNED_OFF`。**不会去 enable**，蓝牙本来就开着，安全 |
| 用户点允许点得慢（10~30 秒） | 一直 `RETRY`，等到 `OFF` 才 enable。这是 `MAX_ATTEMPTS=120` 存在的全部理由 |
| `enable()` 被 MIUI 反复拒绝 | `WAITING_ON` 每跳看到 `OFF` 就重试，120 跳封顶 → `GIVE_UP_NEVER_TURNED_BACK_ON`，日志说明需手动开 |
| `BLUETOOTH_CONNECT` 中途被撤销 | 同上，有界，不会死循环 |
| 精确闹钟权限没有 | 降级 `setAndAllowWhileIdle`，链还在，只是变粗；大声记日志 |
| MIUI「自启动」关着 | 闹钟拉不起进程，蓝牙被留在关闭状态。**这是 v2 最大的单点风险**，README 必做项 |
| 快段中途进程被杀 | 什么都没 arm，蓝牙还开着，无半状态 |
| arm 之后进程被杀 | store + 闹钟恢复 |
| 连点两次图标 | 第二次守卫看到 `state != ON` → 直接退出，不会二次 `disable()` |
| BLE 扫描节流（30 秒内超过 5 次） | 不变，`onScanFailed errorCode=2` 记日志 |
| 广播路径 `goAsync()` 超时 | 保留 v1 的 9 秒 backstop，到点强制 `finish()` |

---

## 9. 测试策略

| 测试 | 内容 |
|---|---|
| `ReEnablePolicyTest`（新，约 18 条） | (phase × state) 每个格子；`attempts` 上限两侧（119 / 120）；两个 give-up 变体分别在正确 phase 出现；`UNKNOWN` 在两个 phase 下都优先于一切；成功判定优先于放弃判定（`WAITING_ON` + `ON` + `attempts=120` → `DONE_RESTORED`） |
| `BandMatcherTest`（不变，10 条） | 匹配逻辑没动 |

`ReEnablePolicyTest` 覆盖的正是 v1 完全没被测到、却恰好坏掉的那部分逻辑。v1 只有 `BandMatcher` 一个类有测试。

CI 三步（`testReleaseUnitTest` / `assembleRelease` / 滚动 Release）不变。

**真机才是最终验收。** 单元测试能证明状态机决策正确，但不能证明 MIUI 放行 `disable()`、不能证明闹钟能拉起进程、不能证明 Toast 能显示。这三条只能实测。

---

## 10. 验收标准

v2 完成的判据，按用户四条反馈逐一对应：

1. **弹框** —— README 有明确的 MIUI「开关蓝牙」→ 允许 的路径说明，且列为第一必做项。应用侧不做任何无效尝试
2. **重开** —— 真机上关掉蓝牙后，**在用户点完 MIUI 弹框之后**，蓝牙能自己回来。日志/Toast 能看到 `DONE_RESTORED`
3. **静默消息** —— 命中手环时屏幕上出现一行「已找到手环，正在重启蓝牙…」，约 2 秒自动消失，不需要点任何东西
4. **卡顿** —— 点图标后桌面**始终可以正常点击滑动**，没有任何"看得见点不动"的时段。图标仍在桌面上，仍可点击

以及不回归：四个触发入口都还能用；签名不变可覆盖安装；CI 绿；单测全过。
