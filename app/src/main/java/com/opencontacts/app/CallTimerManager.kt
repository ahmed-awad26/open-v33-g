package com.opencontacts.app

import android.content.Context
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * CallTimerManager – auto-ends a call after a user-configured duration.
 *
 * When [start] is called (on call answer), it:
 *   1. Counts down the configured seconds.
 *   2. Calls TelecomManager.endCall() when the timer expires.
 *
 * Call [cancel] to stop the timer (e.g., user ends call manually).
 */
object CallTimerManager {

    private var timerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    /**
     * @param durationSeconds  0 = disabled.
     * @param onTick           called every second with remaining seconds.
     * @param onEnd            called when the timer fires.
     */
    fun start(
        context: Context,
        durationSeconds: Int,
        onTick: (remaining: Int) -> Unit = {},
        onEnd: () -> Unit = {},
    ) {
        if (durationSeconds <= 0) return
        cancel()
        timerJob = scope.launch {
            for (s in durationSeconds downTo 1) {
                onTick(s)
                delay(1000)
            }
            // Auto-end the active call
            try {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION")
                tm?.endCall()
            } catch (_: Exception) { }
            onEnd()
        }
    }

    fun cancel() {
        timerJob?.cancel()
        timerJob = null
    }
}
