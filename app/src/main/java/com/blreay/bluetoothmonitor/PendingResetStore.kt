package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

/** One checked durable record; no Binder or in-memory-lifetime assumptions are persisted. */
object PendingResetStore {
    private const val PREFS = "bt_reset_pending"
    fun persistence(context: Context): ResetPersistence = object : ResetPersistence {
        override fun read() = PendingResetStore.read(context)
        override fun commit(expected: ResetRecord?, next: ResetRecord?) = PendingResetStore.commit(context, expected, next)
    }

    fun read(context: Context): ResetRecord? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString("record", null)
        if (raw == null && !prefs.getBoolean("armed", false)) return null
        return try {
            val json = JSONObject(requireNotNull(raw))
            val history = json.optJSONArray("history") ?: JSONArray()
            ResetRecord(
                runId = json.getString("run"), commandId = json.getString("command"),
                version = json.getLong("version"), stage = ResetStage.valueOf(json.getString("stage")),
                phase = ReEnablePolicy.Phase.valueOf(json.getString("phase")), attempts = json.getInt("attempts"),
                extraObservations = json.optInt("extra"), wantsToast = json.optBoolean("toast"),
                offDispatchedAt = json.optLong("offDispatch"), offCompletedAt = json.optLong("offComplete"),
                dispatchedAt = json.optLong("dispatch"), commandDeadline = json.optLong("deadline"),
                authorityEnded = json.getBoolean("ended"), lastStatus = SwitchOutcome.Status.valueOf(json.getString("status")),
                lastMode = SwitchMode.valueOf(json.getString("mode")), ordinaryOnly = json.optBoolean("ordinaryOnly"),
                ordinaryOnAttempts = json.optInt("ordinaryAttempts"), lastOrdinaryOnAt = json.optLong("ordinaryAt"),
                modeBits = json.optInt("modeBits"), history = (0 until minOf(history.length(), 12)).map {
                    val entry = history.getJSONObject(it)
                    BackendEntry(SwitchMode.valueOf(entry.getString("mode")), entry.getString("reason").take(512))
                }, reason = json.optString("reason").take(1024),
            )
        } catch (e: Exception) {
            Log.e("BtReset.Store", "Unreadable/legacy pending record; preserve safety quarantine", e)
            // Corrupt or old dispatched state must NEVER read as empty and authorize another OFF.
            ResetRecord("unreadable-pending", "unknown-command", stage = ResetStage.QUARANTINED,
                authorityEnded = false, reason = "旧/损坏的待恢复记录无法判定执行结束；保留安全隔离，请检查系统蓝牙状态")
        }
    }

    fun commit(context: Context, expected: ResetRecord?, next: ResetRecord?): Boolean {
        if (read(context) != expected) return false
        return try {
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear()
            if (next != null) edit.putString("record", encode(next))
            edit.commit().also { if (!it) Log.e("BtReset.Store", "durable commit failed; no new command permitted") }
        } catch (e: Exception) {
            Log.e("BtReset.Store", "durable commit threw; no new command permitted", e)
            false
        }
    }

    private fun encode(record: ResetRecord): String = JSONObject().apply {
        put("run", record.runId); put("command", record.commandId); put("version", record.version)
        put("stage", record.stage.name); put("phase", record.phase.name); put("attempts", record.attempts)
        put("extra", record.extraObservations); put("toast", record.wantsToast)
        put("offDispatch", record.offDispatchedAt); put("offComplete", record.offCompletedAt)
        put("dispatch", record.dispatchedAt); put("deadline", record.commandDeadline); put("ended", record.authorityEnded)
        put("status", record.lastStatus.name); put("mode", record.lastMode.name); put("ordinaryOnly", record.ordinaryOnly)
        put("ordinaryAttempts", record.ordinaryOnAttempts); put("ordinaryAt", record.lastOrdinaryOnAt)
        put("modeBits", record.modeBits); put("reason", record.reason.take(1024))
        put("history", JSONArray().apply { record.history.takeLast(12).forEach { entry ->
            put(JSONObject().put("mode", entry.mode.name).put("reason", entry.reason.take(512)))
        } })
    }.toString()
}
