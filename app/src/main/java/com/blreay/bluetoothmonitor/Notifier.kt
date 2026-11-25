package com.blreay.bluetoothmonitor

import android.content.Context
import android.util.Log
import android.os.Handler
import android.os.Looper
import android.widget.Toast

/**
 * The only place in the app that shows a Toast.
 *
 * Text toasts, not custom views: Android 11+ silently drops custom toast
 * views sent from the background, and by the time the chain reports its
 * outcome this process is very much in the background.
 *
 * Two messages are unconditional - "found the band" and "Bluetooth is back" -
 * because the user asked for a silent on-screen confirmation that needs no
 * tap and disappears on its own. Everything else is gated behind the toast
 * extra, which exists so the phone can be diagnosed without a computer. v1
 * had no always-on output at all, which is why its re-enable failure had to
 * be reverse-engineered from the framework javadoc.
 *
 * Every method here must be called on the main thread. All three current call
 * sites are: ScanCallback.onScanResult (delivered to the main looper),
 * BroadcastReceiver.onReceive, and Activity callbacks. Toast on a thread
 * without a Looper throws, so re-check this when adding a call site.
 *
 * Known risk: MIUI may suppress toasts from background apps. If real-device
 * testing shows nothing, the fallback is a short heads-up Notification.
 */
object Notifier {

    private const val TAG = "BtReset.Notify"

    /**
     * The debug extra, read by every entry point and carried into
     * [PendingResetStore] so the slow phase can honour it too:
     *
     *     am start -n com.blreay.bluetoothmonitor.nogui/com.blreay.bluetoothmonitor.MainActivity --ez toast true
     *     am broadcast -a com.blreay.bluetoothmonitor.nogui.action.RESET_IF_PRESENT --ez toast true
     */
    const val EXTRA_TOAST = "toast"

    /** Unconditional: this is the message the user asked for. */
    fun bandFound(context: Context) {
        show(context, context.getString(R.string.toast_band_found))
    }

    /** Unconditional: proof the chain actually completed. */
    fun bluetoothRestored(context: Context) {
        show(context, context.getString(R.string.toast_restored))
    }

    /**
     * @param verbose pass `false` to suppress. Callers hand in the flag rather
     *        than reading it themselves so the decision stays in one place.
     */
    fun outcome(context: Context, verbose: Boolean, message: String) {
        if (!verbose) return
        show(context, context.getString(R.string.toast_outcome, message))
    }

    /**
     * Always shown. Used for the one failure the user cannot otherwise see:
     * a denied permission grant.
     */
    fun always(context: Context, message: String) {
        show(context, message)
    }

    private fun show(context: Context, text: String) {
        Log.i(TAG, "toast: $text")
        // applicationContext: a Toast outlives the Receiver or Service that
        // asked for it, and must not keep one alive.
        val app = context.applicationContext
        val display = {
            try { Toast.makeText(app, text.take(1000), Toast.LENGTH_SHORT).show() }
            catch (e: Exception) { Log.w(TAG, "best-effort toast unavailable; visibility not guaranteed", e) }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) display() else Handler(Looper.getMainLooper()).post { display() }
    }
}
