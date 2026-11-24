package com.blreay.bluetoothmonitor

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper

/** Observation only: an 8s unfiltered BLE phase followed by up to 15s of classic discovery. */
class BluetoothDiscovery(private val context: Context, private val listener: Listener) {
    interface Listener {
        fun onStarted()
        fun onPhase(message: String)
        fun onDevices(devices: List<DiscoveredDevices.Device>)
        fun onFinished(cancelled: Boolean, errors: List<String>, count: Int)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val interlock = ManualBluetoothInterlock.shared
    private val devices = DiscoveredDevices()
    private var lastSnapshot = emptyList<DiscoveredDevices.Device>()
    private val errors = mutableListOf<String>()
    private var token: Long? = null
    private var adapter: BluetoothAdapter? = null
    private var scanner: BluetoothLeScanner? = null
    private var running = false
    private var phase = ""
    private var observedStart = false
    private var registered = false
    private var classicStarted = false
    private var classicAnnounced = false
    private val bleTimeout = Runnable { if (current() && phase == "BLE") startClassic() }
    private val classicTimeout = Runnable { if (current() && phase == "经典") finish(false) }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            handler.post {
                if (!current() || phase != "BLE") return@post
                val name = try { result.scanRecord?.deviceName?.takeIf { it.isNotBlank() } } catch (_: Exception) { null }
                    ?: safeName(result.device)
                record(result.device, name, result.rssi, "BLE")
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
        }
        override fun onScanFailed(errorCode: Int) {
            handler.post {
                if (!current() || phase != "BLE") return@post
                errors.add("BLE_SCAN_FAILED($errorCode)")
                startClassic()
            }
        }
    }

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!current()) return
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    if (phase != "经典" || !classicStarted) return
                    val device = if (Build.VERSION.SDK_INT >= 33) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    val rssi = if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI)) {
                        intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, 0).toInt()
                    } else null
                    record(device, intent.getStringExtra(BluetoothDevice.EXTRA_NAME)?.takeIf { it.isNotBlank() }
                        ?: safeName(device), rssi, "经典")
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> if (classicStarted && phase == "经典") classicAnnounced = true
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (classicStarted && classicAnnounced) finish(false)
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR) != BluetoothAdapter.STATE_ON) {
                        errors.add("BLUETOOTH_NOT_ON：扫描期间蓝牙已关闭或正在切换")
                        finish(false)
                    }
                }
            }
        }
    }

    /** Returns a preflight error without clearing the Activity's previous list. */
    @SuppressLint("MissingPermission") // BtPermissions.allGranted below checks both Nearby grants; revocation is caught.
    fun start(): String? {
        if (running) return "扫描已在进行"
        if (!BtPermissions.allGranted(context)) return "未获得附近设备权限，无法扫描"
        val radio = BluetoothSwitch.adapter(context) ?: return "设备没有蓝牙适配器"
        if (BluetoothSwitch.state(context) != AdapterState.ON) return "请先打开蓝牙，扫描不会自动开启蓝牙"
        if (PendingResetStore.read(context) != null) return "旧版自动恢复仍在进行，请等待恢复完成后再扫描"
        val ownership = interlock.claimManual() ?: return "其他手动操作正在进行，请稍候"
        token = ownership
        adapter = radio
        running = true
        phase = "BLE"
        try {
            register()
            scanner = radio.bluetoothLeScanner
            if (scanner == null) {
                errors.add("BLE_SCANNER_UNAVAILABLE")
                startClassic()
            } else {
                val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
                scanner!!.startScan(null, settings, scanCallback)
                markStarted()
                listener.onPhase("正在扫描 BLE（8 秒）")
                handler.postDelayed(bleTimeout, BLE_WINDOW_MS)
            }
        } catch (e: Exception) {
            errors.add("BLE_START_ERROR：${e.message ?: e.javaClass.simpleName}")
            startClassic()
        }
        return null
    }

    private fun register() {
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        // Protected Bluetooth broadcasts may come from Bluetooth's privileged UID, not system UID.
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else context.registerReceiver(receiver, filter)
        registered = true
    }

    @SuppressLint("MissingPermission") // Only entered after start's Nearby preflight; mid-scan revocation is caught.
    private fun startClassic() {
        if (!current()) return
        stopBle()
        phase = "经典"
        try {
            if (BluetoothSwitch.state(context) != AdapterState.ON) {
                errors.add("CLASSIC_BLUETOOTH_NOT_ON")
                finish(false)
                return
            }
            if (!registered) register()
            if (adapter?.isDiscovering == true) {
                errors.add("CLASSIC_ALREADY_RUNNING：系统已有发现任务，请稍后重试")
                finish(false)
                return
            }
            classicStarted = adapter?.startDiscovery() == true
            if (!classicStarted) {
                errors.add("CLASSIC_START_REFUSED：系统未接受经典发现请求")
                finish(false)
                return
            }
            markStarted()
            listener.onPhase("正在扫描经典蓝牙（最多 15 秒）")
            handler.postDelayed(classicTimeout, CLASSIC_WINDOW_MS)
        } catch (e: Exception) {
            errors.add("CLASSIC_START_ERROR：${e.message ?: e.javaClass.simpleName}")
            finish(false)
        }
    }

    private fun markStarted() {
        if (observedStart) return
        observedStart = true
        listener.onStarted()
    }

    private fun record(device: BluetoothDevice?, name: String?, rssi: Int?, type: String) {
        val mac = try { device?.address } catch (_: Exception) { null } ?: return
        devices.add(mac, name, rssi, type)
        val snapshot = devices.snapshot()
        if (snapshot != lastSnapshot) {
            lastSnapshot = snapshot
            listener.onDevices(snapshot)
        }
    }

    @SuppressLint("MissingPermission") // CONNECT was preflighted; a revoked grant yields the unknown-name fallback.
    private fun safeName(device: BluetoothDevice?): String? = try { device?.name } catch (_: Exception) { null }

    private fun current(): Boolean {
        if (!running) return false
        if (token?.let { interlock.isManualCurrent(it) } != true) {
            errors.add("STALE_SESSION：扫描已被其他操作取消")
            finish(true)
            return false
        }
        return true
    }

    @SuppressLint("MissingPermission") // Cleanup must attempt stopScan even after revocation; failure is harmless and caught.
    private fun stopBle() {
        handler.removeCallbacks(bleTimeout)
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanner = null
    }

    fun stop() { if (running) finish(true) }

    @SuppressLint("MissingPermission") // Cancel only our started discovery, including after revocation; the call is caught.
    private fun finish(cancelled: Boolean) {
        if (!running) return
        running = false
        stopBle()
        handler.removeCallbacks(classicTimeout)
        if (classicStarted) try { adapter?.cancelDiscovery() } catch (_: Exception) {}
        classicStarted = false
        classicAnnounced = false
        if (registered) try { context.unregisterReceiver(receiver) } catch (_: Exception) {}
        registered = false
        token?.let { interlock.releaseManual(it) }
        token = null
        listener.onFinished(cancelled, errors.toList(), devices.snapshot().size)
    }

    companion object {
        private const val BLE_WINDOW_MS = 8_000L
        private const val CLASSIC_WINDOW_MS = 15_000L
    }
}
