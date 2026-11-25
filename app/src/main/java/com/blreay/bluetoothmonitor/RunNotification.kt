package com.blreay.bluetoothmonitor

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.Context
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
object RunNotification {

    private const val TAG = "BtReset.Notif"
    private const val CHANNEL_ID = "bt_reset_result"
    private const val NOTIF_ID = 100
    private const val STATUS_ID = 101
    const val ONGOING_ID = 102
    private const val ONGOING_CHANNEL = "bt_reset_work"
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
    fun runtime(context: Context, record: ResetRecord, state: AdapterState, message: String, final: Boolean = false) {
        val history = BackendHistory(record.history, record.modeBits)
        val scan = prefs(context).getString("scan", null)
        val body = join(message, "实际使用方式：${history.label()}",
            "阶段 ${record.stage} / ${record.phase}；最近读取蓝牙状态 $state；执行结束确认=${record.authorityEnded}",
            "运行 ${record.runId.take(36)}；命令 ${record.commandId.take(36)}；版本 ${record.version}", scan,
            history.entries.joinToString("\n") { "${it.mode.label}：${it.reason}" })
        post(context, if (final) "蓝牙运行结果" else "蓝牙重启状态", body, final)
    }
    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        ensureChannel(nm, context.applicationContext)
        try { nm.createNotificationChannel(NotificationChannel(ONGOING_CHANNEL, "本次扫描与恢复", NotificationManager.IMPORTANCE_LOW)) }
        catch (e: Exception) { Log.w(TAG, "ongoing channel creation unavailable", e) }
    }
    private fun detailsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 20, Intent(context, PermissionActivity::class.java)
            .setAction("${context.packageName}.action.DETAILS")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    fun ongoing(context: Context): Notification {
        ensureChannels(context)
        return Notification.Builder(context, ONGOING_CHANNEL).setSmallIcon(R.drawable.ic_stat_bluetooth)
            .setContentTitle("本次蓝牙扫描/恢复运行中")
            .setContentText("仅命中目标广播才切换；点击查看实际方式与详情")
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

    private fun post(context: Context, title: String, text: String, final: Boolean = true) {
        val app = context.applicationContext
        val body = (text + "\n授权状态（不代表已使用/切换成功）：" + prefs(app).getString("auth", "未知")).take(12_000)
        if (!prefs(app).edit().putString("body", body).commit()) Log.e(TAG, "details persistence failed")
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
            nm.notify(if (final) NOTIF_ID else STATUS_ID, notification)
            if (final) nm.cancel(STATUS_ID)
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
