package com.blreay.bluetoothmonitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku

/** Application-owned official SDK listeners and exact broker slot. Never owned by an Activity. */
class ShizukuBluetoothClient(private val context: Context, private val onStatus: (String) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    @Volatile private var attached = false
    private var started = false
    private var authorizationBusy = false
    @Volatile private var binding: Binding? = null
    private var permissionCode: Int? = null
    private var nextPermissionCode = 5000
    private var humanWait: ((SwitchCapability) -> Unit)? = null
    private var humanTimer: Runnable? = null
    private var bindWait: ((SwitchCapability) -> Unit)? = null
    private var bindTimer: Runnable? = null
    private var lastMode: SwitchMode? = null
    var status: String = "Shizuku 授权状态未知；尚未连接"
        private set
    private val prefs get() = context.getSharedPreferences("bt_reset_setup", Context.MODE_PRIVATE)
    private fun automaticRequest(code: Int) = AutomaticAuthorization(
        { prefs.getBoolean("shizuku_actual_attempt", false) },
        { prefs.edit().putBoolean("shizuku_actual_attempt", true).commit() },
        { Shizuku.requestPermission(code) },
    )

    private val received = Shizuku.OnBinderReceivedListener {
        if (!started) return@OnBinderReceivedListener
        attached = true // SDK attachment callback, not merely pingBinder().
        publish("Shizuku 已连接；授权需通过 SDK 检查（SDK 可能缓存结果）")
    }
    private val dead = Shizuku.OnBinderDeadListener {
        if (!started) return@OnBinderDeadListener
        attached = false
        lastMode = null
        retireBinding()
        publish("Shizuku 连接已断开；将自动尝试普通方式")
        finishBind(SwitchCapability(null, status))
        finishHuman(SwitchCapability(null, status))
    }
    private val permission = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (!started || code != permissionCode) return@OnRequestPermissionResultListener
        permissionCode = null
        val granted = result == PackageManager.PERMISSION_GRANTED
        publish(if (granted) "Shizuku 授权成功；不代表蓝牙切换成功" else "Shizuku 授权被拒绝；自动尝试普通方式，可在详情中显式重试")
        val continuation = humanWait
        if (continuation == null) {
            // A late grant updates readiness only. It cannot restart scan/reset or alter a dispatched backend.
            return@OnRequestPermissionResultListener
        }
        clearHumanTimer()
        humanWait = null
        if (granted) preflight(CommandLease.foreground(), 5_000L, continuation)
        else continuation(SwitchCapability(null, status))
    }

    fun start() {
        if (started) return
        started = true
        Shizuku.addBinderReceivedListenerSticky(received)
        Shizuku.addBinderDeadListener(dead)
        Shizuku.addRequestPermissionResultListener(permission)
    }

    /** Startup only: actual first permission request precedes the Nearby gate. Recovery never calls this. */
    fun startup(explicit: Boolean = false, callback: (SwitchCapability) -> Unit) {
        start()
        if (authorizationBusy) { callback(SwitchCapability(null, "权限检查/选择已在等待；不会重复申请")); return }
        authorizationBusy = true
        val completion = CompletionGate()
        val guardedCallback: (SwitchCapability) -> Unit = { capability ->
            if (completion.finish()) { authorizationBusy = false; callback(capability) }
        }
        probe(CommandLease.foreground(), 5_000L) { probe ->
            if (probe.mode == null) { guardedCallback(SwitchCapability(null, probe.reason)); return@probe }
            if (probe.granted) { connect(probe.mode, 5_000L, guardedCallback); return@probe }
            val code = nextPermissionCode++
            permissionCode = code
            humanWait = guardedCallback
            publish("自动检查首次 Shizuku 权限申请；授权不等于切换成功")
            humanTimer = Runnable { finishHuman(SwitchCapability(null, "等待 Shizuku 权限选择超时；自动尝试普通方式；迟到授权仅影响今后运行")) }
                .also { handler.postDelayed(it, 60_000L) }
            val requestLease = CommandLease({ SystemClock.elapsedRealtime() }, SystemClock.elapsedRealtime() + 5_000L)
            val automatic = automaticRequest(code)
            BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.STATUS, requestLease, 5_000L,
                { RequestResult(null, "权限请求未进入执行；队列/执行窗口已占用，不消耗首次标记") },
                { RequestResult(null, "Shizuku 权限请求超时/异常；自动尝试普通方式，迟到授权仅影响今后运行") },
                {
                    val connected = attached && Shizuku.pingBinder()
                    val granted = connected && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                    if (!requestLease.valid()) RequestResult(null, "权限请求执行窗口已过期；未开始新申请")
                    else RequestResult(automatic.authorize(connected, granted, explicit), "")
                }) { request, late ->
                if (late || humanWait == null || permissionCode != code) return@submit
                when (request.decision) {
                    AutomaticAuthorization.Decision.REQUESTED -> publish("等待 Shizuku 权限选择（最多 60 秒）；授权不等于硬件切换成功")
                    AutomaticAuthorization.Decision.READY -> {
                        clearHumanTimer()
                        val ready = humanWait; humanWait = null
                        ready?.let { preflight(CommandLease.foreground(), 5_000L, it) }
                    }
                    AutomaticAuthorization.Decision.STORE_FAILED -> finishHuman(SwitchCapability(null, "无法保存首次实际申请标记；未申请，改用普通方式"))
                    AutomaticAuthorization.Decision.NO_BINDER -> finishHuman(SwitchCapability(null, "Shizuku 尚未连接，可能未启动/配对；未消耗首次标记，不能可靠诊断配对原因"))
                    AutomaticAuthorization.Decision.PREVIOUS_ATTEMPT -> finishHuman(SwitchCapability(null, "Shizuku 未授权；此前已实际申请，不自动重复弹窗；详情可显式重试"))
                    null -> finishHuman(SwitchCapability(null, request.reason))
                }
            }
        }
    }

    private data class RequestResult(val decision: AutomaticAuthorization.Decision?, val reason: String)
    private data class Probe(val mode: SwitchMode?, val granted: Boolean, val reason: String)
    private fun probe(lease: CommandLease, budget: Long, callback: (Probe) -> Unit) {
        val gate = CompletionGate()
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.STATUS, lease, budget,
            { Probe(null, false, "Shizuku 状态任务未开始/已过期/队列占用") },
            { Probe(null, false, "Shizuku 状态检查超时；授权未知，自动尝试普通方式") },
            {
                val end = SystemClock.elapsedRealtime() + (budget - 200L).coerceAtLeast(1L)
                while (!attached && lease.valid() && SystemClock.elapsedRealtime() < end) Thread.sleep(100)
                if (!attached || !Shizuku.pingBinder()) Probe(null, false, managerUnavailable())
                else if (Shizuku.isPreV11() || Shizuku.getVersion() < 13) Probe(null, false, "Shizuku 服务版本过旧；本应用保守要求 v13+ 的非 daemon UserService/移除与权限回调能力，自动改用普通方式")
                else {
                    val uid = Shizuku.getUid()
                    val mode = when (uid) { 0 -> SwitchMode.SHIZUKU_ROOT; 2000 -> SwitchMode.SHIZUKU_SHELL; else -> null }
                    if (mode == null) Probe(null, false, "Shizuku 服务实际 UID=$uid，不是 root/shell")
                    else Probe(mode, Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED,
                        "已检查 SDK 授权（可能使用 SDK 缓存）；实际服务 UID=$uid")
                }
            },
        ) { value, _ ->
            if (gate.finish()) {
                lastMode = value.mode
                publish(if (value.granted) "Shizuku 授权成功；${value.reason}" else "Shizuku 未授权/不可用；${value.reason}")
                callback(value)
            }
        }
    }

    private fun managerUnavailable(): String {
        // Capability is checked FIRST, so Sui/root Binder works without the manager package.
        val installed = try { context.packageManager.getApplicationInfo("moe.shizuku.privileged.api", 0); true }
            catch (_: PackageManager.NameNotFoundException) { false }
        return if (installed) "Shizuku 尚未连接，可能未启动/配对；不能可靠诊断配对原因"
            else "Shizuku 尚未连接；未找到 Shizuku 管理器（Sui/root 连接亦可使用）"
    }

    fun preflight(lease: CommandLease, budgetMs: Long, callback: (SwitchCapability) -> Unit) {
        val start = SystemClock.elapsedRealtime()
        probe(lease, budgetMs) { value ->
            if (!lease.valid()) { callback(SwitchCapability(null, "执行窗口已过期")); return@probe }
            if (value.mode == null || !value.granted) {
                callback(SwitchCapability(null, if (value.mode != null) "Shizuku SDK 授权被拒绝/未授予" else value.reason))
            } else {
                val remaining = budgetMs - (SystemClock.elapsedRealtime() - start)
                if (remaining <= 0) callback(SwitchCapability(null, "Shizuku 检查/连接总窗口已过期"))
                else connect(value.mode, remaining, callback)
            }
        }
    }

    private inner class Binding {
        var registration: ShizukuUserServiceBroker.Registration? = null
        var remote: IPrivilegedBluetoothService? = null
        val death = IBinder.DeathRecipient { handler.post { if (binding === this) {
            retireBinding(); publish("高权限服务已断开；自动尝试普通方式")
            finishBind(SwitchCapability(null, status))
        } } }
        val owner = object : ShizukuRegistrationBroker.Owner<IBinder> {
            override fun connected(endpoint: IBinder) {
                handler.post {
                    if (binding !== this@Binding) {
                        registration?.let { ShizukuUserServiceBroker.retire(it) }
                        return@post
                    }
                    try {
                        endpoint.linkToDeath(death, 0)
                        remote = IPrivilegedBluetoothService.Stub.asInterface(endpoint)
                        finishBind(SwitchCapability(lastMode, "高权限服务就绪；每次命令仍需新检查"))
                    } catch (_: Exception) {
                        retireBinding(); finishBind(SwitchCapability(null, "高权限端点连接失败"))
                    }
                }
            }
            override fun disconnected() {
                handler.post { if (binding === this@Binding) {
                    retireBinding(); finishBind(SwitchCapability(null, "高权限服务已断开"))
                } }
            }
            override fun failed(message: String) {
                handler.post { if (binding === this@Binding) {
                    retireBinding(); finishBind(SwitchCapability(null, message))
                } }
            }
        }
    }

    private fun connect(mode: SwitchMode, budget: Long, callback: (SwitchCapability) -> Unit) {
        lastMode = mode
        val current = binding
        val endpoint = current?.remote?.asBinder()
        val registration = current?.registration
        if (endpoint?.isBinderAlive == true && registration != null &&
            ShizukuUserServiceBroker.canReconfirm(registration, endpoint)) {
            callback(SwitchCapability(mode, "重新确认同一健康 Shizuku 服务槽；SDK 授权已检查"))
            return
        }
        if (bindWait != null) { callback(SwitchCapability(null, "高权限服务连接正在进行；普通方式可继续")); return }
        if (ShizukuUserServiceBroker.quarantined) { callback(SwitchCapability(null, ShizukuUserServiceBroker.UNAVAILABLE)); return }
        retireBinding()
        bindWait = callback
        val next = Binding()
        binding = next
        bindTimer = Runnable {
            retireBinding()
            finishBind(SwitchCapability(null, "高权限服务连接超时；${ShizukuUserServiceBroker.UNAVAILABLE}"))
        }.also { handler.postDelayed(it, budget) }
        next.registration = ShizukuUserServiceBroker.bind(context, next.owner)
        if (next.registration == null) { retireBinding(); finishBind(SwitchCapability(null, ShizukuUserServiceBroker.UNAVAILABLE)) }
    }

    fun executeToggle(enabled: Boolean, lease: CommandLease, budgetMs: Long, callback: (SwitchOutcome) -> Unit) {
        val current = binding
        val remote = current?.remote
        val mode = lastMode
        if (remote == null || mode == null) { callback(SwitchOutcome.notInvoked("Shizuku 服务在派发前不可用")); return }
        BoundedBluetoothWork.submit(BoundedBluetoothWork.Lane.RPC, lease, budgetMs,
            { SwitchOutcome.notInvoked("高权限命令未进入执行；窗口过期/队列占用") },
            { SwitchOutcome.uncertain(mode, "高权限 RPC 超时/异常；可能已提交，旧执行权限未确认结束，不重放命令") },
            {
                if (!lease.valid() || binding !== current || !remote.asBinder().isBinderAlive) SwitchOutcome.notInvoked("派发前服务/执行窗口已失效")
                else {
                    val result = BluetoothCommandResult.decode(remote.setBluetoothEnabled(enabled))
                    val status = when (result.status) {
                        BluetoothCommandResult.Status.SUBMITTED -> SwitchOutcome.Status.SUBMITTED
                        BluetoothCommandResult.Status.NOT_INVOKED -> SwitchOutcome.Status.NOT_INVOKED
                        BluetoothCommandResult.Status.ERROR -> SwitchOutcome.Status.UNCERTAIN
                        else -> SwitchOutcome.Status.UNCERTAIN
                    }
                    SwitchOutcome(status, if (status == SwitchOutcome.Status.NOT_INVOKED) SwitchMode.NONE else mode,
                        result.output.ifBlank { "svc 返回 ${result.status}；不代表硬件状态" }, result.authorityEnded)
                }
            },
        ) { result, late ->
            // Do not cancel native Future or dispose its endpoint on timeout: neither proves quiescence.
            if (late) publish("迟到高权限返回：${result.status}；仅核对同一持久化命令，不重启扫描")
            callback(result)
        }
    }

    private fun retireBinding() {
        val old = binding
        binding = null
        try { old?.remote?.asBinder()?.unlinkToDeath(old.death, 0) } catch (_: Exception) {}
        old?.registration?.let { ShizukuUserServiceBroker.retire(it) }
        old?.remote = null
    }
    private fun finishBind(capability: SwitchCapability) {
        bindTimer?.let { handler.removeCallbacks(it) }; bindTimer = null
        val callback = bindWait; bindWait = null
        callback?.invoke(capability)
    }
    private fun clearHumanTimer() { humanTimer?.let { handler.removeCallbacks(it) }; humanTimer = null }
    private fun finishHuman(capability: SwitchCapability) {
        clearHumanTimer()
        val callback = humanWait; humanWait = null
        publish(capability.reason)
        callback?.invoke(capability)
        // Keep permissionCode: a late SDK result may update future readiness, but not callback again.
    }
    private fun publish(message: String) { status = message.take(1024); onStatus(status) }
}
