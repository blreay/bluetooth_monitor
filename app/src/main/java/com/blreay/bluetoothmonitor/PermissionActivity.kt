package com.blreay.bluetoothmonitor

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.content.ActivityNotFoundException
import android.widget.Toast
import android.provider.Settings
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** Real opaque setup/details window, opened explicitly. Presentation never owns/cancels native commands. */
class PermissionActivity : Activity() {
    private lateinit var coordinator: ResetCoordinator
    private var setupRun: String? = null
    private var selectedRun: String? = null
    private var visible = false
    private var resumed = false
    private var authSettled = false
    private var permissionPending = false
    private var dialog: AlertDialog? = null
    private var subscription: Long? = null
    private var body: TextView? = null
    private var selectedBody: TextView? = null
    private var scroll: ScrollView? = null
    private val prefs get() = getSharedPreferences("bt_reset_setup", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator = ResetCoordinator.get(this)
        setupRun = savedInstanceState?.getString("setup_run") ?: intent.getStringExtra(EXTRA_SETUP_RUN)
        selectedRun = savedInstanceState?.getString("selected_run") ?: historySelection(intent)
        permissionPending = savedInstanceState?.getBoolean("permission_pending") ?: false
        if (setupRun != null) {
            RunNotification.ensureChannels(this)
            prefs.edit().putBoolean("notification_setup_seen", true).commit()
            dialog = AlertDialog.Builder(this).setTitle("首次必要设置")
                .setMessage("先自动检查/申请 Shizuku 权限，再申请附近设备权限；只有本次广播命中目标手环才重启手机蓝牙。通知可查看结果与详情。")
                .setPositiveButton("继续", null).setNegativeButton("取消") { _, _ -> cancelSetup() }
                .setOnCancelListener { cancelSetup() }.create()
            dialog!!.show()
            dialog!!.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        } else {
            showDetails()
            val savedY = savedInstanceState?.getInt("scroll_y") ?: 0
            if (savedY > 0) scroll?.post { scroll?.scrollTo(0, savedY) }
        }
    }
    override fun onStart() {
        super.onStart(); visible = true
        if (setupRun == null) {
            subscription = coordinator.subscribeDetails { render(it) }
        } else coordinator.awaitStartupForSetup(setupRun!!) {
            authSettled = true
            dialog?.setMessage("${coordinator.client.status}\n\n通知权限由系统控制；拒绝后结果可能不可见。下一步只申请必要附近设备权限，不连接手环。")
            dialog?.getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
                isEnabled = true; setOnClickListener { requestNearbyOrContinue() }
            }
            if (resumed && !BtPermissions.allGranted(this)) requestNearbyOrContinue()
        }
    }
    override fun onResume() {
        super.onResume(); resumed = true
        if (setupRun == null) {
            MibandLog.refreshAccess() // once per visible resume; actual storage checks/flush are asynchronous
        }
        if (setupRun != null && authSettled && !BtPermissions.allGranted(this)) requestNearbyOrContinue()
    }
    override fun onPause() { resumed = false; super.onPause() }
    override fun onStop() {
        visible = false
        subscription?.let { coordinator.unsubscribeDetails(it) }; subscription = null
        if (setupRun != null) coordinator.detachSetupCallback()
        super.onStop()
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent)
        selectedRun = historySelection(intent)
        if (setupRun == null) render(coordinator.detailsSnapshot())
    }
    private fun historySelection(intent: Intent): String? {
        val run = intent.getStringExtra(EXTRA_HISTORY_RUN) ?: return null
        val identity = HistoryNotificationPlan.identity(packageName, run)
        return run.takeIf { intent.action == identity.contentAction && intent.data?.toString() == identity.contentData }
    }
    private fun requestNearbyOrContinue() {
        if (!resumed || !authSettled || permissionPending) return
        val missing = BtPermissions.missing(this)
        if (missing.isEmpty()) { setupRun?.let { coordinator.setupCompleted(it, true) }; finish() }
        else {
            if (!prefs.edit().putBoolean("nearby_attempted", true).commit()) { cancelSetup(); return }
            permissionPending = true
            requestPermissions(missing.toTypedArray(), NEARBY_CODE)
        }
    }
    private fun showDetails() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (16 * resources.displayMetrics.density).toInt(); setPadding(pad, pad, pad, pad)
        }
        fun text(): TextView = TextView(this).apply { setTextIsSelectable(true); root.addView(this) }
        fun LinearLayout.button(label: String, action: () -> Unit) {
            val horizontal = orientation == LinearLayout.HORIZONTAL
            addView(Button(this@PermissionActivity).apply {
                text = label; setSingleLine(false); minWidth = 0; minimumWidth = 0
                setOnClickListener { action() }
            }, if (horizontal) LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                else LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        }
        fun row(actions: LinearLayout.() -> Unit) {
            root.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; actions() })
        }
        text().text = "运行详情 · 手动扫描绝不自动重置"
        selectedBody = text()
        body = text()
        row {
            button("打开蓝牙") { coordinator.manualRequest(ManualAction.ON, visible && resumed && !isFinishing) }
            button("关闭蓝牙") { coordinator.manualRequest(ManualAction.OFF, visible && resumed && !isFinishing) }
            button("扫描蓝牙") { coordinator.manualRequest(ManualAction.SCAN, visible && resumed && !isFinishing) }
        }
        row {
            button("系统蓝牙设置") { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            button("应用权限与通知设置") { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))) }
        }
        root.button("显式重试 Shizuku 授权（不重新扫描）") { coordinator.retryAuthorization() }
        root.button("显式申请附近设备权限（不自动重放按钮）") {
            val missing = BtPermissions.missing(this)
            if (missing.isNotEmpty() && !permissionPending) {
                prefs.edit().putBoolean("nearby_attempted", true).commit()
                permissionPending = true
                requestPermissions(missing.toTypedArray(), NEARBY_CODE)
            }
        }
        root.button("继续安全待恢复/重试耗尽恢复（实际 OFF 才开启）") { coordinator.retryRecovery() }
        root.button("日志权限") { openLogAccessSettings() }
        root.button("关闭") { finish() }
        scroll = ScrollView(this).apply { addView(root) }
        setContentView(scroll)
        render(coordinator.detailsSnapshot()) // initial render does not depend on a later publication
    }
    private fun openLogAccessSettings() {
        if (!visible || !resumed || isFinishing) return
        try {
            try { startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))) }
            catch (_: ActivityNotFoundException) { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
        } catch (failure: Exception) {
            Toast.makeText(applicationContext, "无法打开所有文件访问设置；未确认授权，请在系统设置中查找本应用", Toast.LENGTH_LONG).show()
        }
    }
    private fun render(snapshot: DetailsSnapshot) {
        selectedBody?.text = if (selectedRun == null) "未选择历史卡；当前持久累计自动重置：${snapshot.store.history.total ?: "未知"}\n${snapshot.store.disclosure.orEmpty()}"
        else snapshot.selected(selectedRun)?.let { DetailsText.history(it, snapshot.store) }
            ?: "所选运行 $selectedRun 的明细已被保留上限清理/未确认保存；不使用当前运行冒充旧结果。当前累计 ${snapshot.store.history.total ?: "未知"}"
        body?.text = DetailsText.current(snapshot)
        // Update existing text only. No dialog/layout reconstruction, scroll reset or scan restart per advertisement.
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != NEARBY_CODE || !permissionPending) return
        permissionPending = false
        val granted = grantResults.isNotEmpty() && BtPermissions.allGranted(this)
        setupRun?.let { coordinator.setupCompleted(it, granted); finish() } ?: run {
            RunNotification.notice(this, if (granted) "附近设备权限已授予" else "附近设备权限被拒绝",
                "未自动开始扫描/开关；请显式重新点击所需按钮。Shizuku 授权独立于附近设备权限")
            coordinator.publishDetails()
        }
    }
    private fun cancelSetup() { setupRun?.let { coordinator.setupCompleted(it, false) }; finish() }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("permission_pending", permissionPending)
        outState.putString("selected_run", selectedRun); outState.putString("setup_run", setupRun)
        outState.putInt("scroll_y", scroll?.scrollY ?: 0)
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        subscription?.let { coordinator.unsubscribeDetails(it) }; subscription = null
        if (setupRun != null) coordinator.detachSetupCallback()
        dialog?.dismiss(); dialog = null
        super.onDestroy()
    }
    companion object {
        const val EXTRA_SETUP_RUN = "setup_run"
        const val EXTRA_HISTORY_RUN = "history_run"
        private const val NEARBY_CODE = 101
    }
}
