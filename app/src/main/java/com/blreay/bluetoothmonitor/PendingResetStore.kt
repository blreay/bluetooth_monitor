package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log

/** Pending, automatic ledger and manual fence/results share ONE checked preferences transaction. */
object PendingResetStore {
    private const val PREFS = "bt_reset_pending"
    private var cached: CheckedResetStore? = null

    /** Process-wide confirmed snapshot: separate callers never re-read an eagerly mutated failed cache. */
    @Synchronized fun persistence(context: Context): CheckedResetStore = cached ?: run {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        CheckedResetStore(object : ResetStorageAdapter {
            override fun read(): Map<String, String> = prefs.all.filterKeys { it in ResetStoreCodec.keys }.mapValues { (key, value) ->
                when {
                    value is String -> value
                    key == "armed" && value is Boolean -> value.toString()
                    else -> "unreadable-preference-type"
                }
            }
            override fun commit(changes: Map<String, String?>): Boolean {
                val edit = prefs.edit()
                changes.forEach { (key, value) -> if (value == null) edit.remove(key) else edit.putString(key, value) }
                return try {
                    edit.commit().also { if (!it) Log.e("BtReset.Store", "durable commit failed; no new command permitted") }
                } catch (_: Exception) {
                    Log.e("BtReset.Store", "durable commit threw; no new command permitted")
                    false
                }
            }
        }, onConfirmedSnapshot = { snapshot -> RunNotification.reconcileHistory(context.applicationContext, snapshot) }).also { cached = it }
    }

    fun read(context: Context): ResetRecord? = persistence(context).read()
    fun snapshot(context: Context): ResetStoreSnapshot = persistence(context).snapshot()
    fun commit(context: Context, expected: ResetRecord?, next: ResetRecord?): Boolean = persistence(context).commit(expected, next)
}
