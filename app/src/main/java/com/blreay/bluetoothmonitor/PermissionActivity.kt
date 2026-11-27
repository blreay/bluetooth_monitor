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
import android.widget.CheckBox
import android.widget.EditText
import android.widget.AutoCompleteTextView
import android.widget.ArrayAdapter
import android.text.Editable
import android.text.TextWatcher
import android.text.InputType

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
    private lateinit var editor: AutomaticSettingsEditor
    private var monitorStatus: TextView? = null
    private var saveStatus: TextView? = null
    private var master: CheckBox? = null
    private var timerSource: CheckBox? = null
    private var screenOnSource: CheckBox? = null
    private var screenOffSource: CheckBox? = null
    private var intervalInput: EditText? = null
    private var targetInput: TargetNameInput? = null
    private var paintingDraft = false
    private var candidateChoices = emptyList<AutomaticTargetChoice>()
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
            editor = AutomaticSettingsEditor(coordinator.detailsSnapshot().monitor!!.settings, readDraft(savedInstanceState))
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
            coordinator.automaticVisibleOpportunity() // synchronous while this opaque Activity is really resumed
            render(coordinator.detailsSnapshot())
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
        addAutomaticEditor(root)
        row {
            button("精确闹钟系统设置") { openSystemSettings(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:$packageName"))) }
            button("电池优化系统设置") { openSystemSettings(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
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
    /** Platform AutoComplete completion runs BEFORE item click. Never let a placeholder overwrite raw draft. */
    private class TargetNameInput(context: android.content.Context) : AutoCompleteTextView(context) {
        override fun replaceText(text: CharSequence?) { /* explicit editor.choose is the only candidate completion */ }
    }
    private fun addAutomaticEditor(root: LinearLayout) {
        val state = editor.state()
        monitorStatus = TextView(this).also { root.addView(it) }
        master = CheckBox(this).apply { text = "自动监控"; isChecked = state?.enabled == true; isEnabled = state != null; root.addView(this) }
        val sources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; root.addView(this) }
        fun source(label: String, checked: Boolean): CheckBox = CheckBox(this).apply {
            text = label; isChecked = checked; isEnabled = state != null
            sources.addView(this, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        timerSource = source("定时", state?.timer == true)
        screenOnSource = source("亮屏", state?.screenOn == true)
        screenOffSource = source("灭屏", state?.screenOff == true)
        intervalInput = EditText(this).apply {
            hint = "整数间隔（分钟，1–1440）"; inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true); setText(state?.intervalText.orEmpty()); isEnabled = editor.intervalEnabled(); root.addView(this)
        }
        targetInput = TargetNameInput(this).apply {
            hint = "本次广播完整目标名（可直接输入，空格/大小写精确）"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            threshold = 0; setSingleLine(true); setText(state?.targetText.orEmpty()); isEnabled = state != null
            state?.let { setSelection(it.selectionStart.coerceIn(0, text.length), it.selectionEnd.coerceIn(0, text.length)) }
            root.addView(this)
            setOnItemClickListener { parent, _, position, _ ->
                val choice = parent.getItemAtPosition(position) as? AutomaticTargetChoice ?: return@setOnItemClickListener
                if (editor.choose(choice)) paintTargetDraft()
            }
            setOnFocusChangeListener { _, focused -> if (focused && candidateChoices.isNotEmpty()) showDropDown() }
        }
        for (checkbox in listOf(master, timerSource, screenOnSource, screenOffSource)) checkbox?.setOnCheckedChangeListener { _, _ ->
            editor.sources(master?.isChecked == true, timerSource?.isChecked == true, screenOnSource?.isChecked == true, screenOffSource?.isChecked == true)
            intervalInput?.isEnabled = editor.intervalEnabled()
        }
        fun watch(changed: () -> Unit) = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) { }
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { }
            override fun afterTextChanged(value: Editable?) { if (!paintingDraft) changed() }
        }
        targetInput?.addTextChangedListener(watch { captureTargetDraft() })
        intervalInput?.addTextChangedListener(watch { editor.interval(intervalInput?.text?.toString().orEmpty()) })
        saveStatus = TextView(this).also { root.addView(it) }
        root.addView(Button(this).apply {
            text = "保存自动设置"; isEnabled = state != null
            setOnClickListener {
                if (!visible || !resumed || isFinishing) return@setOnClickListener
                captureTargetDraft()
                val result = editor.save { base, draft -> coordinator.saveAutomatic(base, draft, true) }
                if (result == AutomaticSettingsSave.SAVED) editor.confirmSaved(coordinator.detailsSnapshot().monitor!!.settings)
                saveStatus?.text = when (result) {
                    AutomaticSettingsSave.SAVED -> "已确认整条设置保存；不立即扫描，宿主/周期安排状态请看上方（保存不代表启动/安排成功）"
                    AutomaticSettingsSave.UNCHANGED -> "设置未改变；不重新安排健康周期，不立即扫描"
                    else -> "未保存（$result）；草稿与基准版本保留。请修正有效整数/完整目标名；冲突不能覆盖另一版本"
                }
                render(coordinator.detailsSnapshot())
            }
        })
    }
    private fun captureTargetDraft() {
        val input = targetInput ?: return
        editor.text(input.text.toString(), input.selectionStart.coerceAtLeast(0), input.selectionEnd.coerceAtLeast(0))
    }
    private fun paintTargetDraft() {
        val state = editor.state() ?: return
        val input = targetInput ?: return
        paintingDraft = true
        try {
            input.setText(state.targetText, false)
            input.setSelection(state.selectionStart, state.selectionEnd)
        } finally { paintingDraft = false }
    }
    private fun updateCandidates(snapshot: ManualScanCandidatesSnapshot) {
        captureTargetDraft()
        editor.candidates(snapshot)
        val choices = editor.choices()
        if (choices == candidateChoices) return
        candidateChoices = choices
        val input = targetInput ?: return
        val raw = input.text.toString(); val start = input.selectionStart.coerceAtLeast(0); val end = input.selectionEnd.coerceAtLeast(0)
        val adapter = object : ArrayAdapter<AutomaticTargetChoice>(this, android.R.layout.simple_dropdown_item_1line, choices) {
            override fun areAllItemsEnabled() = false
            override fun isEnabled(position: Int) = getItem(position)?.selectable == true
        }
        input.setAdapter(adapter)
        // Adapter/platform callbacks are presentation only; preserve any draft text/cursor exactly.
        paintingDraft = true
        try {
            if (input.text.toString() != raw) input.setText(raw, false)
            input.setSelection(start.coerceIn(0, raw.length), end.coerceIn(0, raw.length))
        } finally { paintingDraft = false }
    }
    private fun openSystemSettings(intent: Intent) {
        if (!visible || !resumed || isFinishing) return
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(applicationContext, "无法打开此系统设置；未确认系统授权状态", Toast.LENGTH_LONG).show()
        }
    }
    private fun readDraft(bundle: Bundle?): AutomaticSettingsDraft? {
        if (bundle?.getBoolean("automatic_draft") != true) return null
        return AutomaticSettingsDraft(bundle.getLong("automatic_base"), bundle.getBoolean("automatic_enabled"),
            bundle.getBoolean("automatic_timer"), bundle.getBoolean("automatic_on"), bundle.getBoolean("automatic_off"),
            bundle.getString("automatic_interval").orEmpty(), bundle.getInt("automatic_retained_interval"),
            bundle.getString("automatic_target").orEmpty(), bundle.getInt("automatic_cursor_start"), bundle.getInt("automatic_cursor_end"),
            bundle.getBoolean("automatic_dirty"))
    }
    private fun writeDraft(bundle: Bundle) {
        if (!::editor.isInitialized) return
        captureTargetDraft()
        val state = editor.state() ?: return
        bundle.putBoolean("automatic_draft", true); bundle.putLong("automatic_base", state.baseRevision)
        bundle.putBoolean("automatic_enabled", state.enabled); bundle.putBoolean("automatic_timer", state.timer)
        bundle.putBoolean("automatic_on", state.screenOn); bundle.putBoolean("automatic_off", state.screenOff)
        bundle.putString("automatic_interval", state.intervalText); bundle.putInt("automatic_retained_interval", state.retainedInterval)
        bundle.putString("automatic_target", state.targetText); bundle.putInt("automatic_cursor_start", state.selectionStart)
        bundle.putInt("automatic_cursor_end", state.selectionEnd); bundle.putBoolean("automatic_dirty", state.dirty)
        // No candidates, collector, operation lease or authority is saved into Activity state.
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
        snapshot.monitor?.let { monitor ->
            editor.observe(monitor.settings)
            val confirmed = monitor.settings.record?.settings
            monitorStatus?.text = buildString {
                append("自动配置已确认：")
                if (confirmed == null) append("未知；阻塞 ${monitor.settings.fault}，不重置默认目标/计数")
                else append("版本 ${confirmed.revision}；总开关 ${if (confirmed.enabled) "开" else "关"}；定时=${confirmed.timerEnabled} 亮屏=${confirmed.screenOnEnabled} 灭屏=${confirmed.screenOffEnabled}；间隔 ${confirmed.intervalMinutes} 分钟\n完整目标：${confirmed.targetName}")
                append("\n配置故障：${monitor.settings.fault ?: "无"}；宿主=${if (monitor.hostAlive) "已真实前台接管" else if (monitor.hostStarting) "等待真实前台接管（不是扫描接受）" else "未运行"}")
                append("\n周期安排：${monitor.schedule.status}；来源方式：${snapshot.periodicTransport?.origin ?: "尚未安排/未知"}")
                snapshot.periodicTransport?.failure?.let { append("；$it") }
                append("\n精确闹钟系统权限=${coordinator.exactAlarmAccess()}；系统忽略电池优化=${coordinator.batteryOptimizationIgnored()}（未自动更改）")
                monitor.notice?.let { append("\n$it") }
                monitor.screenSourceFailure?.let { append("\n来源不可用：$it") }
                append("\n候选仅来自本进程当前/最近一次真正接受的手动 BLE 扫描；同名不同MAC按名称策略无法区分")
            }
            updateCandidates(snapshot.scanCandidates)
        }
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
        writeDraft(outState)
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
