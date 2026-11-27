package com.blreay.bluetoothmonitor

interface AutomaticMonitorRuntime {
    fun busy(): Boolean
    fun startHost(start: MonitorHostStart): Boolean
    fun later(delay: Long, task: () -> Unit)
    fun dispatch(target: TargetSnapshot): Boolean
    fun childGone(child: MonitorChildTicket)
}
data class AutomaticMonitorRuntimeSnapshot(val settings: AutomaticSettingsSnapshot, val schedule: AutomaticScheduleSnapshot,
    val hostAlive: Boolean = false, val hostStarting: Boolean = false, val notice: String? = null,
    val screenSourceFailure: String? = null)

/** Source/host adapter on the coordinator lane. No native command, ledger or recovery authority here. */
class AutomaticMonitorController(private val store: CheckedAutomaticSettingsStore, private val scheduler: AutomaticMonitorScheduler,
    private val alarms: MonitorAlarmPort, val host: MonitorHostLifetime, private val runtime: AutomaticMonitorRuntime,
    private val changed: () -> Unit = {}) {
    private data class Waiting(val attempt: TriggerAttempt, val start: MonitorHostStart)
    private var waiting: Waiting? = null
    private var selectionSession: MonitorHostSession? = null
    private var selection: ((AutomaticMonitorSettings?) -> Unit)? = null
    private var notice: String? = null
    private var screenSourceFailure: String? = null
    fun configure(expected: Long?, draft: AutomaticMonitorSettings, visible: Boolean): AutomaticSettingsSave {
        val result = scheduler.configure(expected, draft)
        if (result == AutomaticSettingsSave.SAVED || store.snapshot().blocked) discardWaiting()
        refreshHost()
        if (visible && (result == AutomaticSettingsSave.SAVED || result == AutomaticSettingsSave.UNCHANGED)) visibleOpportunity()
        publish()
        return result
    }
    /** Called synchronously only while a real explicit visible opportunity still exists. Never scans. */
    fun visibleOpportunity() {
        scheduler.resume()
        refreshHost()
        val config = store.snapshot()
        if (!config.blocked && config.record?.settings?.enabled == true && host.session() == null) startHost(null)
        publish()
    }
    /** Decode the CURRENT receipt before any cold resume/arm can alter transport. */
    fun timer(receipt: MonitorAlarmIdentity): Boolean {
        val attempt = scheduler.onTimer(receipt.ticket, runtime.busy())
        refreshHost()
        if (attempt == null) { publish(); return false }
        if (host.session() != null) return accept(attempt)
        if (receipt.origin == MonitorAlarmOrigin.INEXACT) {
            scheduler.discard(attempt)
            notice = "当前为非精确周期投递且无已验证监控宿主；本次未扫描，请显式打开详情/图标启动宿主"
            publish(); return false
        }
        // Only an exact CURRENT origin permits attempting a real cold FGS; refusal is not acceptance.
        startHost(attempt)
        publish()
        return false
    }
    fun screen(source: AutomaticTrigger): Boolean {
        if (source == AutomaticTrigger.TIMER || host.session() == null) return false
        val attempt = scheduler.onScreen(source, runtime.busy()) ?: return false
        return accept(attempt) // synchronous borrow of an already verified host, no new service start
    }
    fun hostStarted(start: MonitorHostStart, stop: () -> Unit, selection: (AutomaticMonitorSettings?) -> Unit): MonitorHostSession? {
        val session = host.started(start, stop) ?: return null
        selectionSession = session; this.selection = selection
        val pending = waiting?.takeIf { it.start === start }
        if (pending != null) waiting = null
        refreshHost()
        if (host.session() === session) pending?.let { accept(it.attempt) }
        else pending?.let { scheduler.discard(it.attempt) }
        publish()
        return session
    }
    fun hostGone(session: MonitorHostSession) {
        if (selectionSession === session) { selectionSession = null; selection = null; screenSourceFailure = null }
        host.destroy(session)?.let { runtime.childGone(it) }
        publish()
    }
    fun hostFailed(start: MonitorHostStart, reason: String) {
        if (!host.failStart(start)) return
        waiting?.takeIf { it.start === start }?.let { scheduler.discard(it.attempt); waiting = null }
        notice = "监控宿主未获前台接管：$reason；未接受新扫描，无后台界面绕过"
        publish()
    }
    fun screenRegistration(session: MonitorHostSession, failure: String?): Boolean {
        if (host.session() !== session || selectionSession !== session) return false
        screenSourceFailure = failure
        publish()
        return true
    }
    fun snapshot() = AutomaticMonitorRuntimeSnapshot(store.snapshot(), scheduler.snapshot(), host.session() != null,
        host.pendingStart() != null, notice, screenSourceFailure)
    fun targetSnapshot(): TargetSnapshot? = store.snapshot().record?.settings?.let { TargetSnapshot(it.revision, it.targetName) }
    private fun accept(attempt: TriggerAttempt): Boolean {
        val result = scheduler.accept(attempt, runtime.busy()) { target ->
            host.session() != null && runtime.dispatch(target)
        }
        publish()
        return result
    }
    private fun startHost(attempt: TriggerAttempt?) {
        val existing = host.pendingStart()
        if (existing != null) {
            if (attempt != null) waiting = Waiting(attempt, existing)
            return
        }
        val start = host.beginStart()
        if (start == null) { attempt?.let { scheduler.discard(it) }; return }
        if (attempt != null) waiting = Waiting(attempt, start)
        val requested = try { runtime.startHost(start) } catch (_: Exception) { false }
        if (!requested) { hostFailed(start, "实际 startForegroundService 请求被拒绝/权限未满足"); return }
        try {
            runtime.later(MonitorHostLifetime.START_WINDOW_MS) {
                if (host.pendingStart() === start) hostFailed(start, "4秒内未收到本次真实 startForeground 成功")
            }
        } catch (_: Exception) { hostFailed(start, "本次宿主启动期限无法安排") }
    }
    private fun discardWaiting() {
        waiting?.let { scheduler.discard(it.attempt) }
        waiting = null
    }
    private fun refreshHost() {
        val config = store.snapshot()
        val settings = config.record?.settings?.takeIf { !config.blocked && it.enabled }
        // Revoke new SCAN before any source unregister/alarm cancellation can block. Child recovery stays owned.
        if (settings == null) {
            host.revokeNewScan()
            discardWaiting()
            host.pendingStart()?.let { host.failStart(it) }
        }
        // Configuration faults cancel PERIODIC ONLY. Accepted child/recovery eligibility is untouched.
        if (config.blocked) config.record?.pendingAlarm?.let { ticket ->
            try { alarms.cancel(ticket) } catch (_: Exception) { notice = "自动配置已暂停；周期闹钟取消失败（原安全恢复不受影响）" }
        }
        if (selectionSession === host.session()) try { selection?.invoke(settings) }
        catch (_: Exception) { screenSourceFailure = "所选亮/灭屏来源动态注册失败；定时状态单独显示" }
        try { host.keepAlive(settings != null) }
        catch (_: Exception) { notice = "宿主停止请求失败；能力已撤销，不作为新扫描许可；已确认配置保存结果不改变" }
    }
    private fun publish() { try { changed() } catch (_: Exception) { } }
}
