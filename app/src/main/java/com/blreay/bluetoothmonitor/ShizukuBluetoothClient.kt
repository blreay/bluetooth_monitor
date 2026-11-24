package com.blreay.bluetoothmonitor

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import rikka.shizuku.Shizuku
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Owns listeners, a non-daemon UserService, and bounded asynchronous Binder calls. */
class ShizukuBluetoothClient(
    private val context: Context,
    private val onStatus: (String) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool { task ->
        Thread(task, "bt-shizuku").apply { isDaemon = true }
    }
    private val auth = ShizukuAuthorization()
    @Volatile private var started = false
    @Volatile private var epoch = 0
    private var statusSerial = 0
    private var operationSerial = 0
    private var detail = "Shizuku 未启动"
    private var permissionCode: Int? = null
    private var lastPermissionCode: Int? = null
    private var awaitingBinder = false
    @Volatile private var binding: Binding? = null
    private var statusTimeout: Runnable? = null
    private var statusRead: Future<*>? = null
    private var requestTimeout: Runnable? = null
    private var rpcTimeout: Runnable? = null
    private var rpc: Future<*>? = null
    private var operationCallback: ((BluetoothCommandResult) -> Unit)? = null
    var busy = false
        private set
    val canToggle: Boolean get() = started && auth.canToggle && binding?.remote?.asBinder()?.isBinderAlive == true

    private var received: Shizuku.OnBinderReceivedListener? = null
    private var dead: Shizuku.OnBinderDeadListener? = null
    private var permissionResult: Shizuku.OnRequestPermissionResultListener? = null

    private fun createReceived(life: Int) = Shizuku.OnBinderReceivedListener {
        if (!started) return@OnBinderReceivedListener
        if (life != epoch) return@OnBinderReceivedListener
        if (awaitingBinder && busy) {
            detail = "已收到 Shizuku 连接，正在检查授权"
            publish()
        } else {
            lastPermissionCode = null
            disconnect("Shizuku 已重新连接，请点击申请权限连接服务")
            refresh()
        }
    }
    private fun createDead(life: Int) = Shizuku.OnBinderDeadListener {
        if (!started) return@OnBinderDeadListener
        if (life != epoch) return@OnBinderDeadListener
        lastPermissionCode = null
        auth.binderDied()
        disconnect("Shizuku 连接已断开，请重新启动 Shizuku")
    }
    private fun createPermissionResult(life: Int) = Shizuku.OnRequestPermissionResultListener { code, result ->
        if (!started || life != epoch || code != lastPermissionCode) return@OnRequestPermissionResultListener
        val activeRequest = code == permissionCode
        permissionCode = null
        clearRequestTimer()
        auth.permissionResult(result == PackageManager.PERMISSION_GRANTED)
        if (!activeRequest) {
            detail = "权限选择已返回；先前等待已取消，请点击申请权限连接服务"
            publish()
        } else if (auth.granted) connect()
        else {
            busy = false
            detail = "授权被拒绝；若已选择不再询问，请在 Shizuku 中允许本应用"
            publish()
        }
    }

    fun start() {
        if (started) return
        started = true
        epoch++
        received = createReceived(epoch)
        dead = createDead(epoch)
        permissionResult = createPermissionResult(epoch)
        Shizuku.addBinderReceivedListener(received!!)
        Shizuku.addBinderDeadListener(dead!!)
        Shizuku.addRequestPermissionResultListener(permissionResult!!)
        refresh()
    }

    /** Reading a grant is not requesting one, and never binds a service on startup. */
    private fun refresh() {
        cancelStatusRead()
        val life = epoch
        val serial = ++statusSerial
        val token = auth.beginStatusRead()
        detail = unavailableIfQuarantined("正在读取 Shizuku 授权状态")
        publish()
        statusTimeout = Runnable {
            if (!started || life != epoch || serial != statusSerial || !auth.timeoutStatusRead(token)) return@Runnable
            cancelStatusRead()
            detail = unavailableIfQuarantined("Shizuku 状态读取超时；授权与服务状态未确认，请检查 Shizuku 后重试")
            publish()
        }.also { handler.postDelayed(it, BIND_TIMEOUT_MS) }
        try {
            statusRead = statusIo.submit {
                val result = try {
                    val available = Shizuku.pingBinder()
                    available to (available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
                } catch (_: Exception) { null }
                handler.post {
                    if (!started || life != epoch || serial != statusSerial) return@post
                    if (!auth.finishStatusRead(token, result?.first ?: false, result?.second ?: false)) return@post
                    cancelStatusRead()
                    detail = when {
                        ShizukuUserServiceBroker.quarantined -> ShizukuUserServiceBroker.UNAVAILABLE
                        result == null -> "无法读取 Shizuku 状态；授权状态未知，请重试"
                        result.first -> "服务未连接，请点击申请权限"
                        else -> "Shizuku 未启动，请先启动 Shizuku"
                    }
                    publish()
                }
            }
        } catch (_: RejectedExecutionException) {
            auth.timeoutStatusRead(token)
            cancelStatusRead()
            detail = unavailableIfQuarantined("Shizuku 状态读取仍未完成；授权状态未知，请检查 Shizuku 后重试")
            publish()
        }
    }

    private fun cancelStatusRead() {
        auth.cancelStatusRead()
        statusTimeout?.let { handler.removeCallbacks(it) }
        statusTimeout = null
        statusRead?.cancel(true)
        (statusRead as? Runnable)?.let { statusIo.remove(it) }
        statusRead = null
    }

    /** Called only by the authorization button. No request/retry is performed by lifecycle callbacks. */
    fun requestAuthorization() {
        if (!started || busy) return
        cancelStatusRead()
        if (ShizukuUserServiceBroker.quarantined) {
            detail = ShizukuUserServiceBroker.UNAVAILABLE
            publish()
            return
        }
        busy = true
        lastPermissionCode = null
        awaitingBinder = true
        statusSerial++
        detail = "正在检查 Shizuku 授权"
        publish()
        val life = epoch
        val serial = statusSerial
        armRequestTimer("申请授权超时，请重试")
        io.execute {
            try {
                // The official provider receives the main-process binder automatically.
                val deadline = SystemClock.elapsedRealtime() + BIND_TIMEOUT_MS
                var available = Shizuku.pingBinder()
                while (!available && SystemClock.elapsedRealtime() < deadline) {
                    Thread.sleep(100)
                    available = Shizuku.pingBinder()
                }
                val grant = available && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                val permanentlyDenied = available && !grant && Shizuku.shouldShowRequestPermissionRationale()
                handler.post {
                    if (!started || life != epoch || serial != statusSerial || !busy) return@post
                    awaitingBinder = false
                    if (!available) {
                        auth.binderDied()
                        authorizationFailed("Shizuku 未启动，请先启动 Shizuku")
                    } else {
                        auth.binderReceived(grant)
                        when {
                            grant -> { clearRequestTimer(); connect() }
                            permanentlyDenied -> authorizationFailed("授权被拒绝且不再询问，请在 Shizuku 中允许本应用")
                            else -> requestPermission(life)
                        }
                    }
                }
            } catch (e: Exception) {
                handler.post {
                    if (started && life == epoch && serial == statusSerial) authorizationFailed("无法申请授权：${e.message}")
                }
            }
        }
    }

    private fun requestPermission(life: Int) {
        armRequestTimer("等待 Shizuku 权限选择超时，请再次点击申请权限")
        val code = nextPermissionCode++
        permissionCode = code
        lastPermissionCode = code
        detail = "等待 Shizuku 权限选择"
        publish()
        io.execute {
            if (!started || life != epoch) return@execute
            try { Shizuku.requestPermission(code) } catch (e: Exception) {
                handler.post {
                    if (started && life == epoch && permissionCode == code) authorizationFailed("申请失败：${e.message}")
                }
            }
        }
    }

    private inner class Binding {
        var registration: ShizukuUserServiceBroker.Registration? = null
        var remote: IPrivilegedBluetoothService? = null
        val death = IBinder.DeathRecipient {
            handler.post { if (started && binding === this) disconnect("高权限服务已断开，请点击申请权限重新连接") }
        }
        val owner = object : ShizukuRegistrationBroker.Owner<IBinder> {
            override fun connected(endpoint: IBinder) {
                handler.post {
                    if (!started || binding !== this@Binding) {
                        dispose(this@Binding)
                        return@post
                    }
                    try {
                        endpoint.linkToDeath(death, 0)
                        remote = IPrivilegedBluetoothService.Stub.asInterface(endpoint)
                        auth.serviceConnected()
                        clearRequestTimer()
                        busy = false
                        detail = "高权限服务已就绪"
                        publish()
                    } catch (e: Exception) { disconnect("高权限服务连接失败：${e.message}") }
                }
            }
            override fun disconnected() {
                handler.post { if (started && binding === this@Binding) disconnect("高权限服务已断开，请重连") }
            }
            override fun failed(message: String) {
                handler.post { if (started && binding === this@Binding) disconnect("$message；${ShizukuUserServiceBroker.UNAVAILABLE}") }
            }
        }
    }

    private fun connect() {
        val current = binding
        val endpoint = current?.remote?.asBinder()
        val registration = current?.registration
        // A fresh genuine grant may reconfirm this exact live slot without rebinding or retiring it.
        if (auth.granted && endpoint?.isBinderAlive == true && registration != null &&
            ShizukuUserServiceBroker.canReconfirm(registration, endpoint)) {
            auth.serviceConnected()
            clearRequestTimer()
            busy = false
            detail = "已确认 Shizuku 授权；当前高权限服务仍就绪"
            publish()
            return
        }
        val old = binding
        binding = null
        old?.let { dispose(it) }
        auth.serviceDisconnected()
        val next = Binding()
        binding = next
        detail = "正在连接高权限服务"
        publish()
        armRequestTimer("高权限服务连接超时；${ShizukuUserServiceBroker.UNAVAILABLE}")
        next.registration = ShizukuUserServiceBroker.bind(context, next.owner)
        if (next.registration == null) disconnect(ShizukuUserServiceBroker.UNAVAILABLE)
    }

    /** Fresh preflight, before Activity claims ownership and cancels the legacy alarm. */
    fun checkReady(callback: (Boolean) -> Unit) {
        val life = epoch
        val current = binding
        val serial = ++operationSerial
        rpcTimeout = Runnable {
            if (started && life == epoch && serial == operationSerial) {
                operationSerial++
                disconnect("授权检查超时，请重新连接")
                callback(false)
            }
        }.also { handler.postDelayed(it, BIND_TIMEOUT_MS) }
        io.execute {
            val granted = try {
                Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            } catch (_: Exception) { false }
            handler.post {
                if (!started || life != epoch || serial != operationSerial) return@post
                clearRpcTimer()
                auth.permissionResult(granted)
                publish()
                callback(current != null && binding === current && canToggle)
            }
        }
    }

    fun executeToggle(enabled: Boolean, callback: (BluetoothCommandResult) -> Unit) {
        if (!canToggle || operationCallback != null) {
            callback(BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "Shizuku 未授权或服务未就绪"))
            return
        }
        val remote = binding!!.remote!!
        val serial = ++operationSerial
        operationCallback = callback
        rpcTimeout = Runnable {
            if (serial == operationSerial) {
                completeOperation(BluetoothCommandResult(BluetoothCommandResult.Status.TIMEOUT, "高权限调用超时；请求可能已提交，状态未确认"))
                disconnect("高权限调用超时，请重新连接")
            }
        }.also { handler.postDelayed(it, RPC_TIMEOUT_MS) }
        rpc = io.submit {
            val result = try {
                BluetoothCommandResult.decode(remote.setBluetoothEnabled(enabled))
            } catch (e: Exception) {
                BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "高权限调用失败，状态未确认：${e.message}")
            }
            handler.post {
                if (!started || serial != operationSerial) return@post
                if (result.status != BluetoothCommandResult.Status.SUBMITTED) {
                    disconnect("高权限调用未成功，请重新连接", result)
                } else completeOperation(result)
            }
        }
    }

    private fun completeOperation(result: BluetoothCommandResult) {
        operationSerial++
        clearRpcTimer()
        rpc?.cancel(true)
        rpc = null
        val callback = operationCallback
        operationCallback = null
        callback?.invoke(result)
    }

    private fun disconnect(message: String, result: BluetoothCommandResult? = null) {
        cancelStatusRead()
        awaitingBinder = false
        statusSerial++
        permissionCode = null
        clearRequestTimer()
        auth.serviceDisconnected()
        val old = binding
        binding = null
        old?.let { dispose(it) }
        busy = false
        detail = unavailableIfQuarantined(message)
        if (operationCallback != null) completeOperation(result ?: BluetoothCommandResult(BluetoothCommandResult.Status.ERROR, "$message；蓝牙状态未确认"))
        publish()
    }

    private fun dispose(old: Binding) {
        try { old.remote?.asBinder()?.unlinkToDeath(old.death, 0) } catch (_: Exception) {}
        old.remote = null
        old.registration?.let { ShizukuUserServiceBroker.retire(it) }
    }

    private fun authorizationFailed(message: String) {
        awaitingBinder = false
        permissionCode = null
        statusSerial++
        clearRequestTimer()
        busy = false
        detail = message
        publish()
    }

    private fun armRequestTimer(message: String) {
        clearRequestTimer()
        requestTimeout = Runnable {
            if (started) disconnect(if (awaitingBinder) "未收到 Shizuku 连接，请先启动 Shizuku 后重试" else message)
        }
            .also { handler.postDelayed(it, BIND_TIMEOUT_MS) }
    }
    private fun clearRequestTimer() { requestTimeout?.let { handler.removeCallbacks(it) }; requestTimeout = null }
    private fun clearRpcTimer() { rpcTimeout?.let { handler.removeCallbacks(it) }; rpcTimeout = null }
    private fun unavailableIfQuarantined(message: String): String =
        if (ShizukuUserServiceBroker.quarantined) ShizukuUserServiceBroker.UNAVAILABLE else message
    private fun publish() {
        val status = when {
            auth.granted -> "shizuku授权成功"
            auth.statusKnown -> "shizuku未授权"
            else -> "shizuku授权状态未知"
        }
        if (started) onStatus(status + "；" + detail)
    }

    fun stop() {
        cancelStatusRead()
        if (!started) return
        started = false
        awaitingBinder = false
        epoch++
        statusSerial++
        operationSerial++
        permissionCode = null
        lastPermissionCode = null
        clearRequestTimer()
        clearRpcTimer()
        rpc?.cancel(true)
        rpc = null
        operationCallback = null
        received?.let { Shizuku.removeBinderReceivedListener(it) }
        dead?.let { Shizuku.removeBinderDeadListener(it) }
        permissionResult?.let { Shizuku.removeRequestPermissionResultListener(it) }
        received = null
        dead = null
        permissionResult = null
        auth.binderDied()
        busy = false
        val old = binding
        binding = null
        old?.let { dispose(it) }
    }

    fun close() { stop(); io.shutdownNow() }

    companion object {
        // Binder ignores thread interruption: cap status reads across Activity instances, not just their callbacks.
        private val statusIo = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(1),
            { task -> Thread(task, "bt-shizuku-status").apply { isDaemon = true } })
        private var nextPermissionCode = 5000
        private const val BIND_TIMEOUT_MS = 5_000L
        private const val RPC_TIMEOUT_MS = 8_000L
    }
}
