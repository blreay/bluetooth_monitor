package com.blreay.bluetoothmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Real opaque, user-facing necessary setup or notification-tapped details. Never a trampoline. */
class PermissionActivity : Activity() {
    private lateinit var coordinator: ResetCoordinator
    private var setupRun: String? = null
    private var resumed = false
    private var authSettled = false
    private var permissionPending = false
    private var dialog: AlertDialog? = null
    private var body: TextView? = null
    private val prefs get() = getSharedPreferences("bt_reset_setup", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ResetCoordinator.get(this)
        setupRun = intent.getStringExtra(EXTRA_SETUP_RUN)
        permissionPending = savedInstanceState?.getBoolean("permission_pending") ?: false
        if (setupRun != null) {
            // Legitimate first Activity opportunity for Android 13 target32's system-controlled prompt.
            RunNotification.ensureChannels(this)
            prefs.edit().putBoolean("notification_setup_seen", true).commit()
            dialog = AlertDialog.Builder(this).setTitle("首次必要设置")
                .setMessage("先自动检查/申请 Shizuku 权限，再申请附近设备权限；只有本次广播命中目标手环才重启手机蓝牙。通知可查看结果与详情。")
                .setPositiveButton("继续", null).setNegativeButton("取消") { _, _ -> cancelSetup() }
                .setOnCancelListener { cancelSetup() }.create()
            dialog!!.show()
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            coordinator.awaitStartupForSetup(setupRun!!) {
                authSettled = true
                dialog?.setMessage("${coordinator.client.status}\n\n通知权限由系统控制；拒绝后结果可能不可见。下一步只申请必要附近设备权限，不连接手环。")
                dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                    isEnabled = true
                    setOnClickListener { requestNearbyOrContinue() }
                }
                if (resumed && !BtPermissions.allGranted(this)) requestNearbyOrContinue()
            }
        } else showDetails()
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        if (setupRun != null && authSettled && !BtPermissions.allGranted(this)) requestNearbyOrContinue()
    }
    override fun onPause() { resumed = false; super.onPause() }

    private fun requestNearbyOrContinue() {
        if (!resumed || !authSettled || permissionPending) return
        val missing = BtPermissions.missing(this)
        if (missing.isEmpty()) {
            setupRun?.let { coordinator.setupCompleted(it, true) }
            finish()
        } else {
            if (!prefs.edit().putBoolean("nearby_attempted", true).commit()) { cancelSetup(); return }
            permissionPending = true
            requestPermissions(missing.toTypedArray(), NEARBY_CODE)
        }
    }

    private fun showDetails() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        body = TextView(this).apply { text = RunNotification.details(this@PermissionActivity); setTextIsSelectable(true) }
        root.addView(body)
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        }
        button("显式重试 Shizuku 授权（不重新扫描）") {
            body?.text = "正在显式重试授权；不会重新扫描/关闭蓝牙"
            coordinator.retryAuthorization { body?.text = RunNotification.details(this) }
        }
        button("显式申请附近设备权限（不自动重置）") {
            val missing = BtPermissions.missing(this)
            if (missing.isNotEmpty() && !permissionPending) {
                prefs.edit().putBoolean("nearby_attempted", true).commit()
                permissionPending = true
                requestPermissions(missing.toTypedArray(), NEARBY_CODE)
            }
        }
        button("继续安全待恢复/重试耗尽恢复（实际 OFF 才开启）") {
            coordinator.retryRecovery { body?.text = RunNotification.details(this) }
        }
        button("系统蓝牙设置") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
        button("应用权限与通知设置") {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
        val scroll = ScrollView(this).apply { addView(root) }
        dialog = AlertDialog.Builder(this).setTitle("运行详情")
            .setView(scroll).setPositiveButton("关闭") { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog!!.show()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NEARBY_CODE || !permissionPending) return
        permissionPending = false
        val granted = grantResults.isNotEmpty() && BtPermissions.allGranted(this)
        setupRun?.let { coordinator.setupCompleted(it, granted); finish() } ?: run {
            RunNotification.message(this, if (granted) "附近设备权限已授予" else "附近设备权限被拒绝",
                "未自动开始扫描/开关；如需新运行请打开桌面图标。Shizuku 授权独立于附近设备权限")
            body?.text = RunNotification.details(this)
        }
    }
    private fun cancelSetup() { setupRun?.let { coordinator.setupCompleted(it, false) }; finish() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_pending", permissionPending)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (setupRun != null) coordinator.detachSetupCallback()
        dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
    companion object {
        const val EXTRA_SETUP_RUN = "setup_run"
        private const val NEARBY_CODE = 101
    }
}
