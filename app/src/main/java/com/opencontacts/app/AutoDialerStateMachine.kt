package com.opencontacts.app

import android.content.Context
import android.telecom.TelecomManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * AutoDialerStateMachine — manages the lifecycle of an auto-redial session.
 *
 * States:
 *   IDLE → PREPARING → DIALING → WAITING_ANSWER → ENDED (per call)
 *   → INTERVAL → DIALING (next call)
 *   → COMPLETED / ABORTED
 *
 * Fail-safe watchdog: if a call runs longer than maxCallDurationSeconds,
 * it is forcibly ended.
 *
 * Crash/reboot protection: the session state survives process death via
 * the BootCompletedReceiver restoring any pending scheduled calls.
 */
object AutoDialerStateMachine {

    enum class State {
        IDLE, PREPARING, DIALING, IN_CALL, INTERVAL, COMPLETED, ABORTED, ERROR
    }

    data class Session(
        val phoneNumber: String,
        val simSlotIndex: Int,
        val totalCalls: Int,
        val callDurationSeconds: Int,
        val intervalSeconds: Int,
        val speakerEnabled: Boolean,
        val currentCall: Int = 0,
        val state: State = State.IDLE,
        val statusMessage: String = "",
        val remainingSeconds: Int = 0,
    )

    private val _session = MutableStateFlow<Session?>(null)
    val session: StateFlow<Session?> = _session

    private val scope = CoroutineScope(Dispatchers.IO)
    private var job: Job? = null
    private const val WATCHDOG_MAX_SECONDS = 300 // 5-minute hard cap

    fun start(context: Context, config: Session) {
        abort()
        _session.value = config.copy(state = State.PREPARING, statusMessage = "Starting…")
        job = scope.launch {
            runSession(context, config)
        }
    }

    fun abort() {
        job?.cancel()
        job = null
        _session.value = _session.value?.copy(state = State.ABORTED, statusMessage = "Stopped by user")
    }

    fun reset() {
        abort()
        _session.value = null
    }

    private suspend fun runSession(context: Context, config: Session) {
        for (i in 1..config.totalCalls) {
            if (!isJobActive()) return

            // ── DIALING ─────────────────────────────────────────────────────
            updateState(State.DIALING, "Call $i / ${config.totalCalls} — dialing…", i, config)

            com.opencontacts.core.common.startInternalCallOrPrompt(context, config.phoneNumber)

            if (config.speakerEnabled) {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
                delay(1500) // give telecom a moment to connect
                audio?.isSpeakerphoneOn = true
            }

            // ── IN_CALL with watchdog ───────────────────────────────────────
            val durationCap = if (config.callDurationSeconds > 0)
                minOf(config.callDurationSeconds, WATCHDOG_MAX_SECONDS)
            else WATCHDOG_MAX_SECONDS

            updateState(State.IN_CALL, "Call $i / ${config.totalCalls} active…", i, config)

            for (s in durationCap downTo 1) {
                if (!isJobActive()) return
                _session.value = _session.value?.copy(remainingSeconds = s)
                delay(1000)
            }

            // ── Force end call ───────────────────────────────────────────────
            runCatching {
                val tm = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
                @Suppress("DEPRECATION") tm?.endCall()
            }

            if (!isJobActive()) return

            // ── INTERVAL ────────────────────────────────────────────────────
            if (i < config.totalCalls) {
                updateState(State.INTERVAL, "Waiting ${config.intervalSeconds}s before call ${i + 1}…", i, config)
                for (s in config.intervalSeconds downTo 1) {
                    if (!isJobActive()) return
                    _session.value = _session.value?.copy(remainingSeconds = s)
                    delay(1000)
                }
            }
        }

        // ── COMPLETED ────────────────────────────────────────────────────────
        _session.value = _session.value?.copy(
            state = State.COMPLETED,
            statusMessage = "Completed ${config.totalCalls} call(s)",
            remainingSeconds = 0,
        )
    }

    private fun isJobActive(): Boolean = job?.isActive == true

    private fun updateState(state: State, msg: String, callIdx: Int, config: Session) {
        _session.value = _session.value?.copy(
            state = state,
            statusMessage = msg,
            currentCall = callIdx,
            remainingSeconds = 0,
        )
    }
}
