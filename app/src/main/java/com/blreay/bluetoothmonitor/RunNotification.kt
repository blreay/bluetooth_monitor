package com.blreay.bluetoothmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
import android.net.Uri
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Persists bounded run details and posts best-effort status/final output; visibility is not guaranteed.
 *
 * This is the app's durable output. Toasts are unconditional for only the two
 * success moments and are gated behind the debug extra for everything else, so
 * before this existed a failed run produced literally nothing on screen and the
 * only way to diagnose it was to attach a computer. The user asked to be able
 * to watch the result without one.
 *
 * A notification rather than a toast for the failures because MIUI is known to
 * suppress toasts from background apps, and every failure path here runs in the
 * background: the launcher Activity has already finished by the time the scan
 * ends. Toasts are still used as the fallback when notifications are disabled.
 *
 * Separate ongoing, progress, and final ids keep the FGS notification distinct from the result.
 * Taps are immutable direct Activity PendingIntents, never background dialogs or receiver trampolines.
 *
 * IMPORTANCE_LOW on purpose: the notification appears in the shade and puts an
 * icon in the status bar, but makes no sound and no heads-up popup. The user
 * asked for this to be observable, not for it to interrupt, and an earlier
 * requirement was that the app have no effect on normal use of the phone.
 * Raising it to DEFAULT is a one-word change in [ensureChannel].
 *
 * Must be called on the main thread, like [Notifier]. All current call sites
 * are: the controller's callback, and [ReEnableReceiver.onReceive]. The toast
 * fallback hops to the main looper regardless, so a future background call site
 * degrades instead of throwing.
 */
data class HistoryCardIdentity(val tag: String, val id: Int, val contentAction: String, val contentData: String,
                               val deleteAction: String, val deleteData: String)

/** Android posting uses this exact identity and confirmed-row visibility decision. */
object HistoryNotificationPlan {
    const val HISTORY_ID = 103
    fun identity(packageName: String, runId: String): HistoryCardIdentity = HistoryCardIdentity(runId, HISTORY_ID,
        "$packageName.action.HISTORY_DETAILS.$runId", "btreset://$packageName/history/$runId",
        "$packageName.action.HISTORY_DISMISS.$runId", "btreset://$packageName/dismiss/$runId")
    fun visible(snapshot: ResetStoreSnapshot, runId: String): ResetHistoryRow? =
        snapshot.history.rows.find { it.runId == runId && !it.dismissed }
}

data class PostedHistoryCard(val tag: String?, val id: Int, val channel: String?)

/** Android supplies only this app's active notifications; reconciliation never posts any old run. */
object HistoryNotificationRetirement {
    const val CHANNEL = "bt_reset_history"
    fun reconcile(snapshot: ResetStoreSnapshot, active: () -> List<PostedHistoryCard>, cancel: (String, Int) -> Unit): Int {
        if (snapshot.storageFailed || snapshot.integrityBlocked) return 0
        val dismissed = snapshot.history.rows.filter { it.dismissed }.map { it.runId }.toSet()
        // A pending orphan is visually protected, but explicit confirmed dismissal overrides only
        // that protection. Cancellation does not remove/alter its durable recovery or authority.
        val retained = snapshot.history.rows.filterNot { it.dismissed }.map { it.runId }.toSet() +
            listOfNotNull(snapshot.pending?.runId).filterNot { it in dismissed }
        val retired = active().filter { it.id == HistoryNotificationPlan.HISTORY_ID && it.channel == CHANNEL &&
            !it.tag.isNullOrBlank() && it.tag !in retained }.map { requireNotNull(it.tag) }.distinct()
        retired.forEach { cancel(it, HistoryNotificationPlan.HISTORY_ID) }
        return retired.size
    }
}

/** Same immutable snapshot renderer used by the visible window and each independent history card. */
object DetailsText {
    fun foreground(owner: ForegroundAdmission.Owner): Pair<String, String> = when (owner) {
        ForegroundAdmission.Owner.AUTOMATIC -> "本次蓝牙扫描/恢复运行中" to "仅命中目标广播才切换；点击查看实际方式与详情"
        ForegroundAdmission.Owner.MANUAL -> "本次手动蓝牙操作运行中" to "显式开关或只展示 BLE 广播；不自动重置，不创建自动恢复/计数；点击查看独立手动结果"
    }
    fun current(snapshot: DetailsSnapshot): String = buildString {
        append("当前自动状态（独立于所选历史/手动输出）\n${snapshot.automaticStatus}\n")
        snapshot.store.pending?.let { append("当前待恢复 ${it.runId} / ${it.commandId} ${it.stage} ${it.phase}；native结束确认=${it.authorityEnded}\n${it.reason}\n") }
        append("当前授权：${snapshot.authorization}\n${snapshot.store.disclosure.orEmpty()}\n")
        snapshot.runtimeNotice?.let { append("独立运行/存储告知：$it\n") }
    }
    fun time(at: Long?): String = at?.let { SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date(it)) } ?: "未知/未记录"
    fun history(row: ResetHistoryRow, snapshot: ResetStoreSnapshot): String = buildString {
        append("自动运行 #${row.sequence} ${row.runId}\n")
        append("目标本次广播：${row.advertisementName ?: "无广播名"} (${row.address ?: "未知地址"})\n")
        append("接收命中：${time(row.acceptedAt)}\n扫描实际注册进入：${time(row.scanStartedAt)}\n扫描观察结束：${time(row.scanEndedAt)}\n")
        append("扫描事实：${row.scanSummary}\n")
        append("OFF 首次实际状态读取：${time(row.firstOffObservedAt)}\nON 首次实际状态读取：${time(row.firstOnObservedAt)}\n")
        fun evidence(label: String, e: ResetCommandEvidence?) {
            append("$label 命令 ${e?.commandId ?: "未记录"}\n请求派发：${time(e?.requestedAt)}；实际调用进入：${time(e?.enteredAt)}\n")
            append("提交确认=${e?.submitted ?: false}，返回确认时间：${time(e?.submittedAt)}；native结束确认=${e?.authorityEnded ?: false}，返回时间：${time(e?.endedAt)}\n")
            append("实际目标状态采样：${time(e?.observedAt)}\n")
        }
        evidence("OFF", row.offEvidence); evidence("ON", row.onEvidence)
        append("实际方式：${BackendHistory(row.backendHistory, row.modeBits).label()}\n")
        append("本次终态：${row.finalDisposition ?: "仍进行/待恢复"}；${if (row.counted) "已验证自动 OFF→ON，已计数" else "未计数（失败/未确认/无完整调用与观察证据）"}\n")
        append("终态决策/记录时间（非硬件切换）：${time(row.terminalRecordedAt)}\n")
        append("本次截至累计：${row.asOfTotal ?: "未知"}；当前持久累计：${snapshot.history.total ?: "未知"}\n")
        append("本进程可验证观察耗时：${row.durationMs?.let { "${it}ms" } ?: "未知（缺少同进程单调时间证据）"}\n")
        append("请求/返回/观察时间不等于硬件切换瞬间；真实硬件切换瞬间未知。\n${row.finalReason}\n")
        row.backendHistory.forEach { append("${it.mode.label}：${it.reason}\n") }
        snapshot.disclosure?.let { append("存储/旧历史披露：$it\n") }
    }
    fun manual(action: ManualAction, snapshot: DetailsSnapshot): String = buildString {
        val result = snapshot.manual(action)
        val label = when (action) { ManualAction.ON -> "打开蓝牙"; ManualAction.OFF -> "关闭蓝牙"; ManualAction.SCAN -> "扫描蓝牙" }
        append("$label · 独立最新结果\n")
        if (result == null) append("尚未请求\n") else {
            append("操作 ${result.operationId}\n状态：${result.status}；${result.reason}\n")
            append("实际使用方式：${BackendHistory(result.backendHistory, result.modeBits).label()}\n")
            result.backendHistory.forEach { append("${it.mode.label}：${it.reason}\n") }
            append("开始：${time(result.begunAt)}；请求派发：${time(result.requestedAt)}\n")
            append("实际调用进入：${time(result.nativeEnteredAt)}；提交确认：${time(result.submittedAt)}；执行结束确认：${time(result.authorityEndedAt)}\n")
            append("实际读取状态：${result.observedState ?: "未知/尚未采样"}；读取：${time(result.observedAt)}；完成：${time(result.completedAt)}\n")
            if (action == ManualAction.SCAN) {
                append("BLE 实际注册进入：${time(result.scanStartedAt)}；注册返回：${time(result.scanReturnedAt)}；观察结束：${time(result.scanEndedAt)}\n")
                append("本次广播 ${result.devices.size} 个${if (result.overflow) "（超过100，额外设备未保留）" else ""}；只展示，不自动开启/连接/配对/重置\n")
                result.devices.forEach {
                    val marker = if (BandMatcher.isTarget(it.mac, it.advertisementName)) "【目标手环，仅展示】 " else ""
                    append("$marker${it.name} ${it.mac} RSSI=${it.rssi ?: "未知"} ${it.types.joinToString("/")}\n")
                }
            }
        }
        snapshot.rejectionNotices[action]?.let { append("独立拒绝/保存告知（不替换进行中的操作）：$it\n") }
    }
}

object RunNotification {

    private const val TAG = "BtReset.Notif"
    private const val CHANNEL_ID = "bt_reset_result"
    private const val NOTIF_ID = 100
    private const val STATUS_ID = 101
    private const val NOTICE_ID = 104
    const val ONGOING_ID = 102
    private const val ONGOING_CHANNEL = "bt_reset_work"
    private const val HISTORY_CHANNEL = HistoryNotificationRetirement.CHANNEL
    private fun prefs(context: Context) = context.getSharedPreferences("bt_reset_details", Context.MODE_PRIVATE)

    fun authorization(context: Context, status: String) {
        prefs(context).edit().putString("auth", status.take(1024)).commit()
        Log.i(TAG, "authorization (not used backend): $status")
    }
    fun beginScan(context: Context) {
        prefs(context).edit().remove("scan").remove("body").commit()
    }
    fun details(context: Context): String {
        val p = prefs(context)
        return (p.getString("body", "尚无运行结果；尚未切换蓝牙") ?: "") +
            "\n\n当前授权状态（与实际使用方式独立）：" + p.getString("auth", "未知") +
            "\n\n仅当前广播命中 Xiaomi Smart Band 8 35BA 才重启手机蓝牙。不配对/连接手环，无定时/开机监控。通知可能被系统禁用；闹钟受 Doze/OEM 限制，force-stop/重启后的恢复不保证。"
    }
    fun message(context: Context, title: String, body: String) = post(context, title, body)
    /** Rejection/storage notices never replace the identity/result of an ongoing automatic/manual operation. */
    fun notice(context: Context, title: String, body: String) = post(context, title, body, persist = false)
    fun runtime(context: Context, record: ResetRecord, state: AdapterState, message: String, final: Boolean = false) {
        val history = BackendHistory(record.history, record.modeBits)
        val scan = prefs(context).getString("scan", null)
        val body = join(message, "实际使用方式：${history.label()}",
            "阶段 ${record.stage} / ${record.phase}；最近读取蓝牙状态 $state；执行结束确认=${record.authorityEnded}",
            "运行 ${record.runId.take(36)}；命令 ${record.commandId.take(36)}；版本 ${record.version}", scan,
            history.entries.joinToString("\n") { "${it.mode.label}：${it.reason}" })
        post(context, if (final) "蓝牙运行结果" else "蓝牙重启状态", body, final)
        history(context, record.runId)
    }
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(nm, context.applicationContext)
        try {
            nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "本次扫描与恢复", NotificationManager.IMPORTANCE_LOW))
            nm.createNotificationChannel(NotificationChannel(HISTORY_CHANNEL, "自动重置独立历史", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null); enableVibration(false) })
        }
        catch (e: Exception) { Log.w(TAG, "ongoing channel creation unavailable", e) }
    }
    private fun detailsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 20, Intent(context, PermissionActivity::class.java)
            .setAction("${context.packageName}.action.DETAILS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun reconcileHistory(context: Context, snapshot: ResetStoreSnapshot) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        try {
            HistoryNotificationRetirement.reconcile(snapshot, {
                manager.activeNotifications.filter { it.packageName == context.packageName }.map {
                    PostedHistoryCard(it.tag, it.id, it.notification.channelId)
                }
            }) { tag, id -> manager.cancel(tag, id) }
        } catch (e: Exception) { Log.w(TAG, "history retirement unavailable; retry at next confirmed snapshot/post or cold start", e) }
    }
    fun history(context: Context, runId: String) {
        val snapshot = PendingResetStore.snapshot(context)
        reconcileHistory(context, snapshot) // retry a prior cancellation failure, without reposting other runs
        val row = HistoryNotificationPlan.visible(snapshot, runId) ?: return // existing, confirmed, not dismissed; NEVER upsert
        val identity = HistoryNotificationPlan.identity(context.packageName, row.runId)
        val content = PendingIntent.getActivity(context, 21,
            Intent(context, PermissionActivity::class.java).setAction(identity.contentAction)
                .setData(Uri.parse(identity.contentData)).putExtra(PermissionActivity.EXTRA_HISTORY_RUN, row.runId)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val delete = PendingIntent.getBroadcast(context, 22,
            Intent(context, HistoryDismissReceiver::class.java).setAction(identity.deleteAction)
                .setData(Uri.parse(identity.deleteData)).putExtra(HistoryDismissReceiver.EXTRA_RUN, row.runId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        ensureChannels(context)
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        val body = DetailsText.history(row, snapshot)
        try {
            manager.notify(identity.tag, identity.id, Notification.Builder(context, HISTORY_CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_bluetooth)
                .setContentTitle("自动重置 #${row.sequence} · ${if (row.counted) "已验证计数" else "未计数/未确认"}")
                .setContentText("${row.advertisementName ?: row.address ?: "目标广播"}；本次截至累计 ${row.asOfTotal ?: "未知"}")
                .setStyle(Notification.BigTextStyle().bigText(body)).setContentIntent(content).setDeleteIntent(delete)
                .setOngoing(false).setAutoCancel(false).setSound(null).setVibrate(null).setDefaults(0).setOnlyAlertOnce(true).build())
        } catch (e: Exception) { Log.w(TAG, "history notification unavailable; checked row retained", e) }
    }
    fun ongoing(context: Context, owner: ForegroundAdmission.Owner = ForegroundAdmission.Owner.AUTOMATIC): Notification {
        ensureChannels(context)
        val text = DetailsText.foreground(owner)
        return Notification.Builder(context, ONGOING_CHANNEL).setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle(text.first)
            .setContentText(text.second)
            .setContentIntent(detailsIntent(context)).setOngoing(true).setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE).build()
    }

    /**
     * Reports the fast phase.
     *
     * @param facts what the scan observed, or null when the run ended before a
     *        scan started - there is then nothing to report about scanning.
     * @param matchedLabel the device that matched, for [BandResetController.Result.RESET_TRIGGERED]
     * @param nearMissSample a short rendering of the band-shaped devices that
     *        did not match, for [RunReport.NotFoundReason.NEAR_MISS]
     * @param adapterState the state that caused an early exit, for
     *        [BandResetController.Result.BLUETOOTH_OFF]
     */
    fun fastPhase(
        context: Context,
        result: BandResetController.Result,
        facts: RunReport.ScanFacts? = null,
        matchedLabel: String? = null,
        nearMissSample: String? = null,
        adapterState: AdapterState? = null,
    ) {
        val (title, body) = renderFast(context, result, facts, matchedLabel, nearMissSample, adapterState)
        prefs(context).edit().putString("scan", body.take(4096)).commit()
        val pending = PendingResetStore.read(context)
        if (pending != null && result == BandResetController.Result.RESET_TRIGGERED) {
            runtime(context, pending, adapterState ?: AdapterState.UNKNOWN, body)
        } else post(context, title, join(body, "实际使用方式：尚未切换"))
    }

    /**
     * Reports the slow phase, but only at its ends. [ReEnablePolicy.Action.RETRY]
     * and [ReEnablePolicy.Action.ENABLE_AND_RETRY] fire once per second for up
     * to two minutes; notifying on those would be spam.
     */
    fun slowPhase(context: Context, action: ReEnablePolicy.Action, attempts: Int) {
        val rendered = renderSlow(context, action, attempts) ?: return
        post(context, rendered.first, rendered.second)
    }

    // ---------------------------------------------------------------- render

    private fun renderFast(
        context: Context,
        result: BandResetController.Result,
        facts: RunReport.ScanFacts?,
        matchedLabel: String?,
        nearMissSample: String?,
        adapterState: AdapterState?,
    ): Pair<String, String> = when (result) {
        BandResetController.Result.NO_ADAPTER ->
            context.getString(R.string.notif_title_no_adapter) to
                context.getString(R.string.notif_body_no_adapter)

        BandResetController.Result.BLUETOOTH_OFF ->
            context.getString(R.string.notif_title_bluetooth_off) to
                context.getString(
                    R.string.notif_body_bluetooth_off,
                    adapterState?.name ?: AdapterState.UNKNOWN.name,
                )

        BandResetController.Result.NEEDS_PERMISSION ->
            context.getString(R.string.notif_title_permission) to
                context.getString(R.string.notif_body_permission)

        BandResetController.Result.DISABLE_FAILED ->
            context.getString(R.string.notif_title_disable_refused) to
                join(context.getString(R.string.notif_body_disable_refused), factsLine(context, facts))

        BandResetController.Result.RESET_TRIGGERED ->
            context.getString(R.string.notif_title_found) to
                join(
                    context.getString(R.string.notif_body_found, matchedLabel ?: "?"),
                    factsLine(context, facts),
                )

        BandResetController.Result.NOT_FOUND ->
            context.getString(R.string.notif_title_not_found) to
                notFoundBody(context, facts, nearMissSample)

        // A scan failure carries a code and therefore a real explanation; an
        // exception before or during the scan does not, so it gets the generic
        // body and the log tag to look at.
        BandResetController.Result.ERROR -> {
            val code = facts?.scanFailedCode
            context.getString(R.string.notif_title_scan_error) to
                if (code == null) {
                    join(context.getString(R.string.notif_body_scan_error), factsLine(context, facts))
                } else {
                    join(
                        context.getString(R.string.notif_hint_scan_failed, code),
                        factsLine(context, facts),
                    )
                }
        }
    }

    private fun notFoundBody(
        context: Context,
        facts: RunReport.ScanFacts?,
        nearMissSample: String?,
    ): String {
        if (facts == null) {
            // Should not happen: NOT_FOUND only comes out of a completed scan
            // window. Reported plainly rather than crashing the notifier.
            return context.getString(R.string.notif_hint_no_band)
        }
        val hint = when (RunReport.whyNotFound(facts)) {
            RunReport.NotFoundReason.SCAN_FAILED ->
                context.getString(R.string.notif_hint_scan_failed, facts.scanFailedCode ?: 0)

            RunReport.NotFoundReason.NEAR_MISS ->
                context.getString(
                    R.string.notif_hint_near_miss,
                    facts.nearMisses,
                    nearMissSample ?: "?",
                )

            RunReport.NotFoundReason.WINDOW_CUT_SHORT ->
                context.getString(R.string.notif_hint_cut_short)

            RunReport.NotFoundReason.NOTHING_SEEN ->
                context.getString(R.string.notif_hint_nothing)

            RunReport.NotFoundReason.SEEN_BUT_NO_BAND ->
                context.getString(R.string.notif_hint_no_band) +
                    context.getString(
                        if (facts.targetBonded) R.string.notif_note_bonded
                        else R.string.notif_note_not_bonded,
                    )
        }
        return join(hint, factsLine(context, facts))
    }

    private fun renderSlow(
        context: Context,
        action: ReEnablePolicy.Action,
        attempts: Int,
    ): Pair<String, String>? = when (action) {
        ReEnablePolicy.Action.DONE_RESTORED ->
            context.getString(R.string.notif_title_restored) to
                context.getString(R.string.notif_body_restored, attempts)

        ReEnablePolicy.Action.GIVE_UP_NO_ADAPTER ->
            context.getString(R.string.notif_title_gave_up) to
                context.getString(R.string.notif_body_gave_up_no_adapter)

        ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_OFF ->
            context.getString(R.string.notif_title_gave_up) to
                context.getString(R.string.notif_body_gave_up_never_off, attempts)

        ReEnablePolicy.Action.GIVE_UP_NEVER_TURNED_BACK_ON ->
            context.getString(R.string.notif_title_gave_up) to
                context.getString(R.string.notif_body_gave_up_never_on, attempts)

        // Mid-flight. Notifying here would put up to 120 notifications in the
        // drawer for one run.
        ReEnablePolicy.Action.ENABLE_AND_RETRY,
        ReEnablePolicy.Action.RETRY,
        -> null
    }

    private fun factsLine(context: Context, facts: RunReport.ScanFacts?): String? =
        facts?.let {
            context.getString(
                R.string.notif_facts,
                it.elapsedMs / 1000.0,
                it.windowMs / 1000.0,
                it.devicesSeen,
                it.namedSeen,
            )
        }

    /** Joins the parts that are present, so a null facts line leaves no gap. */
    private fun join(vararg parts: String?): String =
        parts.filterNotNull().joinToString("\n")

    // ------------------------------------------------------------------ post

    private fun post(context: Context, title: String, text: String, final: Boolean = true, persist: Boolean = true) {
        val app = context.applicationContext
        val body = (text + "\n授权状态（不代表已使用/切换成功）：" + prefs(app).getString("auth", "未知")).take(12_000)
        if (persist && !prefs(app).edit().putString("body", body).commit()) Log.e(TAG, "details persistence failed")
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        if (nm == null) {
            Log.e(TAG, "no NotificationManager; cannot report: $title / $body")
            Notifier.always(app, "$title\n$body")
            return
        }
        ensureChannel(nm, app)
        if (!nm.areNotificationsEnabled() || nm.getNotificationChannel(CHANNEL_ID)?.importance == NotificationManager.IMPORTANCE_NONE) {
            // The user turned notifications off for this app. Fall back to the
            // toast so the run is still not silent. Hopping to the main looper
            // makes this safe from any thread; Toast throws otherwise.
            Log.w(TAG, "notifications disabled; falling back to a toast")
            Handler(Looper.getMainLooper()).post {
                Notifier.always(app, "$title\n$body")
            }
            return
        }
        try {
            val notification = Notification.Builder(app, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_bluetooth)
                .setContentTitle(title)
                .setContentText(body.lineSequence().first())
                .setStyle(Notification.BigTextStyle().bigText(body))
                .setAutoCancel(true)
                .setContentIntent(detailsIntent(app))
                // A second run reuses its result id; without this it would re-alert
                // on every replacement.
                .setOnlyAlertOnce(true)
                .build()
            nm.notify(if (!persist) NOTICE_ID else if (final) NOTIF_ID else STATUS_ID, notification)
            if (final && persist) nm.cancel(STATUS_ID)
            Log.i(TAG, "posted: $title")
        } catch (t: Throwable) {
            // Never let reporting the result break the run itself.
            Log.e(TAG, "notify() threw; details persisted, visibility not guaranteed", t)
            Notifier.always(app, "$title\n$body")
        }
    }

    /**
     * Idempotent: re-creating an existing channel with the same settings is a
     * no-op, and the system ignores changes to importance on a channel that
     * already exists. Called on every post because the process may have been
     * started fresh by an alarm, long after the channel was first created.
     */
    private fun ensureChannel(nm: NotificationManager, app: Context) {
        try {
            val channel = NotificationChannel(
                CHANNEL_ID,
                app.getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = app.getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        } catch (t: Throwable) {
            Log.e(TAG, "createNotificationChannel threw", t)
        }
    }
}
