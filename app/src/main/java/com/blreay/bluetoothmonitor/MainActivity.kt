package com.blreay.bluetoothmonitor

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** Launching or receiving action.RUN only opens this UI. Every action starts at an explicit click. */
class MainActivity : Activity() {
    private enum class Operation { NONE, PERMISSION, SCAN, PREFLIGHT, TOGGLE, CONFIRM }
    private val handler = Handler(Looper.getMainLooper())
    private val stateWorker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "bt-state").apply { isDaemon = true }
    }
    private val interlock = ManualBluetoothInterlock.shared
    private lateinit var client: ShizukuBluetoothClient
    private lateinit var shizukuStatus: TextView
    private lateinit var operationStatus: TextView
    private lateinit var deviceRows: LinearLayout
    private lateinit var authorizeButton: Button
    private lateinit var offButton: Button
    private lateinit var onButton: Button
    private lateinit var scanButton: Button
    private val rows = linkedMapOf<String, TextView>()
    private var found = emptyList<DiscoveredDevices.Device>()
    private var operation = Operation.NONE
    private var visible = false
    private var resumed = false
    private var uiGeneration = 0
    private var permissionPending = false
    private var grantedScanPending = false
    private var authorizationClick = false
    private var discovery: BluetoothDiscovery? = null
    private var manualToken: Long? = null
    private var confirmation: Future<*>? = null
    private var confirmationTimeout: Runnable? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        shizukuStatus = TextView(this).apply { text = "正在读取 Shizuku 状态"; textSize = 16f }
        operationStatus = TextView(this).apply {
            text = savedInstanceState?.getString("operation_status") ?: "请选择操作；扫描不会自动打开蓝牙"
            textSize = 16f
            setPadding(0, 12, 0, 12)
        }
        root.addView(shizukuStatus)
        authorizeButton = Button(this).apply { text = "申请shizuku权限" }
        offButton = Button(this).apply { text = "关闭蓝牙" }
        onButton = Button(this).apply { text = "打开蓝牙" }
        scanButton = Button(this).apply { text = "扫描蓝牙" }
        for (button in listOf(authorizeButton, offButton, onButton, scanButton)) root.addView(button)
        root.addView(operationStatus)
        root.addView(TextView(this).apply { text = "本次发现的设备（名称 / MAC / RSSI / 类型）" })
        deviceRows = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(deviceRows)
        // Whole-page scrolling keeps buttons, wrapped status/output, and devices usable in short windows.
        val pageScroll = ScrollView(this).apply { isFillViewport = true; addView(root) }
        setContentView(pageScroll)
        restoreDevices(savedInstanceState)
        client = ShizukuBluetoothClient(applicationContext) { status ->
            shizukuStatus.text = status
            if (authorizationClick && !client.busy) {
                authorizationClick = false
                operationStatus.text = if (client.canToggle) "高权限服务已就绪，可关闭或打开蓝牙"
                    else "高权限服务未就绪，请查看 Shizuku 状态；扫描不依赖 Shizuku"
            }
            updateButtons()
        }
        authorizeButton.setOnClickListener {
            if (operation != Operation.NONE || client.busy) return@setOnClickListener
            authorizationClick = true
            operationStatus.text = "正在申请 Shizuku 权限 / 连接服务"
            client.requestAuthorization()
            updateButtons()
        }
        offButton.setOnClickListener { toggle(false) }
        onButton.setOnClickListener { toggle(true) }
        scanButton.setOnClickListener { scanClicked() }
        updateButtons()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStart() {
        super.onStart()
        visible = true
        client.start()
        updateButtons()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (grantedScanPending && permissionPending && operation == Operation.PERMISSION) {
            grantedScanPending = false
            permissionPending = false
            startScan()
        }
    }

    override fun onPause() { resumed = false; super.onPause() }

    private fun updateButtons() {
        if (!::client.isInitialized) return
        val idle = visible && operation == Operation.NONE && !client.busy
        authorizeButton.isEnabled = idle
        offButton.isEnabled = idle && client.canToggle
        onButton.isEnabled = idle && client.canToggle
        scanButton.isEnabled = idle
    }

    private fun toggle(enabled: Boolean) {
        if (!visible || operation != Operation.NONE || client.busy) return
        if (!client.canToggle) {
            operationStatus.text = "请先点击申请shizuku权限，并等待高权限服务就绪"
            return
        }
        operation = Operation.PREFLIGHT
        operationStatus.text = "正在确认高权限授权和服务状态"
        updateButtons()
        val generation = uiGeneration
        client.checkReady { ready ->
            if (!visible || generation != uiGeneration || operation != Operation.PREFLIGHT) return@checkReady
            if (!ready) { finishToggle("Shizuku 未授权或服务未就绪，请重新连接"); return@checkReady }
            val ownership = interlock.claimManual()
            if (ownership == null) { finishToggle("其他手动操作正在进行，请稍候"); return@checkReady }
            manualToken = ownership
            try {
                // Claim first invalidates legacy scan tokens. Clear the alarm before sending a manual Off/On.
                ReEnableScheduler.cancel(applicationContext)
                PendingResetStore.clear(applicationContext)
            } catch (e: Exception) {
                try { PendingResetStore.clear(applicationContext) } catch (_: Exception) {}
                finishToggle("无法取消旧版自动恢复，未发送蓝牙命令：${e.message}")
                return@checkReady
            }
            operation = Operation.TOGGLE
            operationStatus.text = if (enabled) "正在通过 Shizuku 提交打开蓝牙请求" else "正在通过 Shizuku 提交关闭蓝牙请求"
            client.executeToggle(enabled) { result ->
                if (!visible || generation != uiGeneration || !interlock.isManualCurrent(ownership)) return@executeToggle
                if (result.status != BluetoothCommandResult.Status.SUBMITTED) {
                    finishToggle("蓝牙请求未确认成功：${result.output}")
                } else if (!BtPermissions.allGranted(this)) {
                    finishToggle("请求已提交（svc 返回 0）；未获得附近设备权限，蓝牙状态未确认${outputNote(result)}")
                } else confirmState(enabled, ownership, generation, result)
            }
        }
    }

    private fun confirmState(enabled: Boolean, token: Long, generation: Int, result: BluetoothCommandResult) {
        operation = Operation.CONFIRM
        operationStatus.text = "请求已提交（svc 返回 0，不代表已切换），正在确认蓝牙状态（最多 10 秒）${outputNote(result)}"
        val expected = if (enabled) AdapterState.ON else AdapterState.OFF
        confirmationTimeout = Runnable {
            if (visible && generation == uiGeneration && interlock.isManualCurrent(token)) {
                finishToggle("请求已提交；等待蓝牙${if (enabled) "开启" else "关闭"}超时，实际状态未确认${outputNote(result)}")
            }
        }.also { handler.postDelayed(it, STATE_TIMEOUT_MS) }
        confirmation = stateWorker.submit {
            val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
            var last = AdapterState.UNKNOWN
            try {
                while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                    last = BluetoothSwitch.state(applicationContext)
                    if (last == expected || !BtPermissions.allGranted(applicationContext)) break
                    Thread.sleep(200)
                }
            } catch (_: InterruptedException) { return@submit }
            val observed = last
            handler.post {
                if (!visible || generation != uiGeneration || !interlock.isManualCurrent(token)) return@post
                finishToggle(if (observed == expected) {
                    "已确认蓝牙${if (enabled) "打开" else "关闭"}（当前状态 $observed）${outputNote(result)}"
                } else "请求已提交；实际状态未确认（最后读取 $observed）${outputNote(result)}")
            }
        }
    }

    private fun outputNote(result: BluetoothCommandResult): String =
        if (result.output.isBlank()) "" else "\nsvc 输出：${result.output}"

    private fun finishToggle(message: String) {
        confirmationTimeout?.let { handler.removeCallbacks(it) }
        confirmationTimeout = null
        confirmation?.cancel(true)
        confirmation = null
        manualToken?.let { interlock.releaseManual(it) }
        manualToken = null
        operation = Operation.NONE
        operationStatus.text = message
        updateButtons()
    }

    private fun scanClicked() {
        if (!visible || operation != Operation.NONE || client.busy) return
        val missing = BtPermissions.missing(this)
        if (missing.isNotEmpty()) {
            operation = Operation.PERMISSION
            permissionPending = true
            operationStatus.text = "扫描需要附近设备权限，请选择是否允许"
            updateButtons()
            requestPermissions(missing.toTypedArray(), SCAN_PERMISSION_CODE)
        } else startScan()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != SCAN_PERMISSION_CODE || !permissionPending || operation != Operation.PERMISSION) return
        if (grantResults.isEmpty() || !BtPermissions.allGranted(this)) {
            permissionPending = false
            grantedScanPending = false
            operation = Operation.NONE
            operationStatus.text = "附近设备权限被拒绝或已取消，未开始扫描；可在应用权限设置中允许"
            updateButtons()
        } else if (visible && resumed) {
            permissionPending = false
            startScan()
        } else if (visible) {
            grantedScanPending = true
        } else {
            permissionPending = false
            operation = Operation.NONE
            operationStatus.text = "界面已离开，未开始扫描；请再次点击扫描蓝牙"
        }
    }

    private fun startScan() {
        if (!visible) return
        operation = Operation.SCAN
        updateButtons()
        val generation = uiGeneration
        lateinit var attempt: BluetoothDiscovery
        attempt = BluetoothDiscovery(applicationContext, object : BluetoothDiscovery.Listener {
            private fun current() = visible && generation == uiGeneration && discovery === attempt
            override fun onStarted() { if (current()) showDevices(emptyList(), clear = true) }
            override fun onPhase(message: String) { if (current()) operationStatus.text = message }
            override fun onDevices(devices: List<DiscoveredDevices.Device>) { if (current()) showDevices(devices) }
            override fun onFinished(cancelled: Boolean, errors: List<String>, count: Int) {
                if (!current()) return
                discovery = null
                operation = Operation.NONE
                operationStatus.text = when {
                    cancelled -> "扫描已取消，保留已发现的设备（$count 台）"
                    errors.isNotEmpty() -> "扫描部分失败 / 未完成；本次发现 $count 台\n${errors.joinToString("\n")}"
                    count == 0 -> "扫描结束，未发现设备；设备可能未广播或不可发现，不能据此判断具体原因"
                    else -> "扫描结束，共发现 $count 台设备；未连接、未重置任何设备"
                }
                updateButtons()
            }
        })
        discovery = attempt
        val error = attempt.start()
        if (error != null) {
            discovery = null
            operation = Operation.NONE
            operationStatus.text = error
            updateButtons()
        }
    }

    private fun showDevices(devices: List<DiscoveredDevices.Device>, clear: Boolean = false) {
        if (clear) { rows.clear(); deviceRows.removeAllViews() }
        val previous = found.associateBy { it.mac }
        for (device in devices) {
            if (previous[device.mac] == device && rows.containsKey(device.mac)) continue
            val view = rows.getOrPut(device.mac) {
                TextView(this).apply {
                    textSize = 15f
                    setPadding(0, 12, 0, 12)
                    setTextIsSelectable(true)
                    deviceRows.addView(this)
                }
            }
            view.text = "${device.name}\n${device.mac}  RSSI ${device.rssi?.let { "$it dBm" } ?: "未知"}  ${device.types.joinToString(" / ")}"
        }
        found = devices
    }

    @Suppress("DEPRECATION")
    private fun restoreDevices(state: Bundle?) {
        val saved = state?.getParcelableArrayList<Bundle>("devices") ?: return
        showDevices(saved.map { row ->
            DiscoveredDevices.Device(row.getString("mac") ?: "", row.getString("name") ?: "未知设备",
                if (row.containsKey("rssi")) row.getInt("rssi") else null,
                row.getStringArrayList("types")?.toSet() ?: emptySet())
        })
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("operation_status", if (operation != Operation.NONE) "操作因界面重建而取消；请再次点击按钮" else operationStatus.text.toString())
        outState.putParcelableArrayList("devices", ArrayList(found.map { device ->
            Bundle().apply {
                putString("mac", device.mac); putString("name", device.name)
                device.rssi?.let { putInt("rssi", it) }
                putStringArrayList("types", ArrayList(device.types))
            }
        }))
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        visible = false
        resumed = false
        uiGeneration++
        val interrupted = operation != Operation.NONE || client.busy
        val toggleInFlight = operation == Operation.TOGGLE || operation == Operation.CONFIRM
        discovery?.stop()
        discovery = null
        permissionPending = false
        grantedScanPending = false
        authorizationClick = false
        client.stop()
        finishToggle(if (interrupted) {
            if (toggleInFlight) "界面已离开，停止等待；蓝牙请求可能已提交，实际状态未确认"
            else "操作已取消：界面已离开；保留已发现设备"
        } else operationStatus.text.toString())
        super.onStop()
    }

    override fun onDestroy() {
        discovery?.stop()
        manualToken?.let { interlock.releaseManual(it) }
        manualToken = null
        confirmationTimeout?.let { handler.removeCallbacks(it) }
        confirmation?.cancel(true)
        client.close()
        stateWorker.shutdownNow()
        super.onDestroy()
    }

    companion object {
        private const val SCAN_PERMISSION_CODE = 101
        private const val STATE_TIMEOUT_MS = 10_000L
    }
}
