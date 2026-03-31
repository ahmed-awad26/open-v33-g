package com.opencontacts.app

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.ui.graphics.vector.ImageVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object TelecomCallCoordinator {
    private var inCallService: InCallService? = null
    private val calls = linkedMapOf<String, Call>()
    private val connectedAt = mutableMapOf<String, Long>()
    private val cachedUi = mutableMapOf<String, ActiveCallUiState>()
    private val dtmfHandler = Handler(Looper.getMainLooper())
    private var dtmfStopRunnable: Runnable? = null

    private val _incomingCall = MutableStateFlow<IncomingCallUiState?>(null)
    val incomingCall: StateFlow<IncomingCallUiState?> = _incomingCall

    private val _activeCall = MutableStateFlow<ActiveCallUiState?>(null)
    val activeCall: StateFlow<ActiveCallUiState?> = _activeCall

    private val _telecomState = MutableStateFlow(TelecomCallUiState())
    val telecomState: StateFlow<TelecomCallUiState> = _telecomState

    fun attachService(service: InCallService) {
        inCallService = service
        updateAudioState(service.callAudioState)
        refreshState(selectedCall())
    }

    fun detachService(service: InCallService) {
        if (inCallService === service) {
            inCallService = null
        }
    }

    fun onIncoming(call: Call, ui: IncomingCallUiState) {
        calls[call.key()] = call
        cachedUi[call.key()] = ui.toActiveCallUiState()
        _incomingCall.value = ui
        refreshState(call)
    }

    fun onCallActive(call: Call, ui: ActiveCallUiState) {
        val key = call.key()
        calls[key] = call
        cachedUi[key] = ui
        if (call.state == Call.STATE_ACTIVE && connectedAt[key] == null) {
            connectedAt[key] = System.currentTimeMillis()
        }
        if (call.state != Call.STATE_RINGING) {
            _incomingCall.value = null
        }
        refreshState(call)
    }

    fun onCallStateChanged(call: Call) {
        val key = call.key()
        calls[key] = call
        if (call.state == Call.STATE_ACTIVE && connectedAt[key] == null) {
            connectedAt[key] = System.currentTimeMillis()
        }
        if (call.state != Call.STATE_RINGING) {
            _incomingCall.value = null
        }
        refreshState(call)
    }

    fun onCallRemoved(call: Call) {
        val key = call.key()
        calls.remove(key)
        cachedUi.remove(key)
        connectedAt.remove(key)
        if (calls.isEmpty()) {
            clearAll()
        } else {
            refreshState(selectedCall())
        }
    }

    fun updateAudioState(state: CallAudioState?) {
        if (state == null) return
        val route = state.route
        val mask = state.supportedRouteMask
        _telecomState.value = _telecomState.value.copy(
            isMuted = state.isMuted,
            currentAudioRoute = route,
            currentRouteLabel = audioRouteLabel(route),
            supportedAudioRouteMask = mask,
            availableRoutes = buildAudioRouteOptions(mask, route),
            canSpeaker = mask and CallAudioState.ROUTE_SPEAKER == CallAudioState.ROUTE_SPEAKER,
            bluetoothAvailable = mask and CallAudioState.ROUTE_BLUETOOTH == CallAudioState.ROUTE_BLUETOOTH,
            wiredAvailable = mask and CallAudioState.ROUTE_WIRED_HEADSET == CallAudioState.ROUTE_WIRED_HEADSET,
            earpieceAvailable = mask and CallAudioState.ROUTE_EARPIECE == CallAudioState.ROUTE_EARPIECE,
        )
    }

    fun clearIncoming() {
        _incomingCall.value = null
    }

    fun clearAll() {
        calls.clear()
        connectedAt.clear()
        cachedUi.clear()
        _incomingCall.value = null
        _activeCall.value = null
        _telecomState.value = TelecomCallUiState()
    }

    fun rejectCall() {
        val call = selectedCall() ?: calls.values.firstOrNull() ?: return
        runCatching { call.reject(false, null) }
    }

    fun answer() {
        selectedCall()?.answer(android.telecom.VideoProfile.STATE_AUDIO_ONLY)
    }

    fun decline() {
        val call = selectedCall() ?: return
        runCatching { call.disconnect() }.onFailure {
            runCatching { if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) call.reject(false, null) }
        }
    }

    fun disconnect() {
        selectedCall()?.disconnect()
    }

    fun deflect(targetNumber: String): Result<Unit> {
        val call = selectedCall() ?: return Result.failure(IllegalStateException("No active ringing call"))
        val target = targetNumber.trim()
        if (target.isBlank()) return Result.failure(IllegalArgumentException("Empty target number"))
        val address = Uri.parse("tel:${Uri.encode(target)}")
        return runCatching {
            val method = call.javaClass.methods.firstOrNull { candidate ->
                candidate.name == "deflect" && candidate.parameterTypes.size == 1 && candidate.parameterTypes[0] == Uri::class.java
            } ?: error("deflect not supported")
            method.invoke(call, address)
            Unit
        }
    }

    fun toggleHold() {
        val call = selectedCall() ?: return
        when (call.state) {
            Call.STATE_HOLDING -> call.unhold()
            Call.STATE_ACTIVE -> if (call.details.canCapability(Call.Details.CAPABILITY_HOLD)) call.hold()
        }
    }

    fun setMuted(enabled: Boolean) {
        inCallService?.setMuted(enabled)
        _telecomState.value = _telecomState.value.copy(isMuted = enabled)
    }

    fun setAudioRoute(route: Int) {
        inCallService?.setAudioRoute(route)
        _telecomState.value = _telecomState.value.copy(currentAudioRoute = route, currentRouteLabel = audioRouteLabel(route))
    }

    fun toggleSpeaker() {
        val next = if (_telecomState.value.currentAudioRoute == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_EARPIECE else CallAudioState.ROUTE_SPEAKER
        setAudioRoute(next)
    }

    /**
     * Merge all active+held calls into a conference call.
     * Requires CAPABILITY_MERGE_CONFERENCE on the active call.
     */
    fun mergeToConference() {
        val active = calls.values.firstOrNull { it.state == Call.STATE_ACTIVE } ?: return
        if (active.details.canCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)) {
            runCatching { active.mergeConference() }
        }
    }

    /**
     * Swap the active call with the held call (if both exist).
     * Works on SWAP_CONFERENCE capability.
     */
    fun swapCalls() {
        val active = calls.values.firstOrNull { it.state == Call.STATE_ACTIVE }
        if (active != null && active.details.canCapability(Call.Details.CAPABILITY_SWAP_CONFERENCE)) {
            runCatching { active.swapConference() }
        } else {
            // Fallback: hold active, unhold held
            active?.let { if (it.details.canCapability(Call.Details.CAPABILITY_HOLD)) it.hold() }
            calls.values.firstOrNull { it.state == Call.STATE_HOLDING }?.let { runCatching { it.unhold() } }
        }
    }

    /**
     * Returns true when a conference merge is possible (active + held call both present).
     */
    fun canMerge(): Boolean {
        val active = calls.values.firstOrNull { it.state == Call.STATE_ACTIVE } ?: return false
        val hasHeld = calls.values.any { it.state == Call.STATE_HOLDING }
        return hasHeld && active.details.canCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE)
    }

    /**
     * Returns the number of current calls (active + held + dialing).
     */
    fun callCount(): Int = calls.values.count {
        it.state in listOf(Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING, Call.STATE_CONNECTING)
    }

    fun sendDtmf(digit: Char) {
        val call = selectedCall() ?: return
        runCatching {
            call.playDtmfTone(digit)
            dtmfStopRunnable?.let(dtmfHandler::removeCallbacks)
            dtmfStopRunnable = Runnable { runCatching { call.stopDtmfTone() } }
            dtmfHandler.postDelayed(dtmfStopRunnable!!, 180L)
        }
    }

    fun launchAddCallUi(context: Context): Boolean {
        val service = inCallService ?: return false
        return runCatching {
            val telecomManager = service.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.showInCallScreen(true)
            service.startActivity(
                Intent(Intent.ACTION_DIAL).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            )
            true
        }.getOrElse { false }
    }

    fun showSystemDialpad() {
        val service = inCallService ?: return
        runCatching {
            val telecomManager = service.getSystemService(Context.TELECOM_SERVICE) as TelecomManager
            telecomManager.showInCallScreen(true)
        }.onFailure {
            runCatching {
                val intent = Intent(service, ActiveCallControlsActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                service.startActivity(intent)
            }
        }
    }

    private fun selectedCall(): Call? {
        return calls.values.maxByOrNull { it.priorityScore() }
    }

    private fun refreshState(preferredCall: Call?) {
        val call = preferredCall ?: selectedCall()
        if (call == null) {
            _activeCall.value = null
            _telecomState.value = TelecomCallUiState()
            return
        }
        val key = call.key()
        val baseUi = cachedUi[key] ?: _activeCall.value ?: ActiveCallUiState(
            displayName = call.details.handle?.schemeSpecificPart.orEmpty().ifBlank { "Unknown caller" },
            number = call.details.handle?.schemeSpecificPart.orEmpty(),
        )
        val details = call.details
        val accountHandle = details.accountHandle
        val accountLabel = runCatching {
            val telecomManager = inCallService?.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager
            telecomManager?.getPhoneAccount(accountHandle)?.label?.toString()
        }.getOrNull()
        _activeCall.value = baseUi.copy(
            phoneAccountId = accountHandle?.id,
            phoneAccountLabel = accountLabel ?: baseUi.phoneAccountLabel,
        )
        _telecomState.value = _telecomState.value.copy(
            state = call.state,
            isOnHold = call.state == Call.STATE_HOLDING,
            canHold = details.canCapability(Call.Details.CAPABILITY_HOLD),
            canMute = inCallService != null,
            canAddCall = call.state == Call.STATE_ACTIVE || call.state == Call.STATE_HOLDING || call.state == Call.STATE_DIALING,
            canDtmf = call.state == Call.STATE_ACTIVE || call.state == Call.STATE_DIALING || call.state == Call.STATE_HOLDING,
            canMerge = calls.values.any { it.state == Call.STATE_HOLDING } && details.canCapability(Call.Details.CAPABILITY_MERGE_CONFERENCE),
            callCount = calls.values.count { it.state in listOf(Call.STATE_ACTIVE, Call.STATE_HOLDING, Call.STATE_DIALING, Call.STATE_CONNECTING) },
            connectedAtMillis = connectedAt[key],
            disconnectAtMillis = if (call.state == Call.STATE_DISCONNECTED || call.state == Call.STATE_DISCONNECTING) System.currentTimeMillis() else null,
            disconnectCauseLabel = details.disconnectCause?.label?.toString()?.takeIf { it.isNotBlank() } ?: details.disconnectCause?.description?.toString()?.takeIf { it.isNotBlank() },
            phoneAccountLabel = accountLabel,
            phoneAccountId = accountHandle?.id,
            directionLabel = directionLabel(details.callDirectionCompat()),
            primaryCallId = key,
            secondaryCallCount = (calls.size - 1).coerceAtLeast(0),
        )
    }
}

data class TelecomCallUiState(
    val state: Int = Call.STATE_DISCONNECTED,
    val isMuted: Boolean = false,
    val isOnHold: Boolean = false,
    val canHold: Boolean = false,
    val canMute: Boolean = false,
    val canSpeaker: Boolean = false,
    val canAddCall: Boolean = false,
    val canDtmf: Boolean = false,
    val canMerge: Boolean = false,
    val callCount: Int = 1,
    val currentAudioRoute: Int = CallAudioState.ROUTE_EARPIECE,
    val currentRouteLabel: String = "Earpiece",
    val supportedAudioRouteMask: Int = CallAudioState.ROUTE_EARPIECE,
    val bluetoothAvailable: Boolean = false,
    val wiredAvailable: Boolean = false,
    val earpieceAvailable: Boolean = true,
    val availableRoutes: List<AudioRouteOption> = emptyList(),
    val connectedAtMillis: Long? = null,
    val disconnectAtMillis: Long? = null,
    val disconnectCauseLabel: String? = null,
    val phoneAccountLabel: String? = null,
    val phoneAccountId: String? = null,
    val directionLabel: String? = null,
    val primaryCallId: String? = null,
    val secondaryCallCount: Int = 0,
) {
    fun elapsedSeconds(nowMillis: Long): Int? {
        val start = connectedAtMillis ?: return null
        val end = disconnectAtMillis ?: nowMillis
        return ((end - start).coerceAtLeast(0L) / 1000L).toInt()
    }
}

data class AudioRouteOption(
    val route: Int,
    val label: String,
    val subtitle: String,
    val icon: ImageVector,
    val supported: Boolean,
)

private fun Call.key(): String = System.identityHashCode(this).toString()

private fun Call.priorityScore(): Int = when (state) {
    Call.STATE_ACTIVE -> 500
    Call.STATE_DIALING -> 400
    Call.STATE_CONNECTING -> 350
    Call.STATE_RINGING -> 300
    Call.STATE_HOLDING -> 200
    else -> 100
}

private fun android.telecom.Call.Details?.canCapability(capability: Int): Boolean {
    val capabilities = this?.callCapabilities ?: return false
    return capabilities and capability == capability
}

private fun buildAudioRouteOptions(mask: Int, selectedRoute: Int): List<AudioRouteOption> = listOf(
    AudioRouteOption(CallAudioState.ROUTE_EARPIECE, "Earpiece", if (selectedRoute == CallAudioState.ROUTE_EARPIECE) "Current route" else "Move audio to the handset earpiece", Icons.Default.PhoneInTalk, mask and CallAudioState.ROUTE_EARPIECE == CallAudioState.ROUTE_EARPIECE),
    AudioRouteOption(CallAudioState.ROUTE_SPEAKER, "Speaker", if (selectedRoute == CallAudioState.ROUTE_SPEAKER) "Current route" else "Use loud speaker", Icons.Default.VolumeUp, mask and CallAudioState.ROUTE_SPEAKER == CallAudioState.ROUTE_SPEAKER),
    AudioRouteOption(CallAudioState.ROUTE_BLUETOOTH, "Bluetooth", if (selectedRoute == CallAudioState.ROUTE_BLUETOOTH) "Current route" else "Switch to paired Bluetooth audio", Icons.Default.BluetoothAudio, mask and CallAudioState.ROUTE_BLUETOOTH == CallAudioState.ROUTE_BLUETOOTH),
    AudioRouteOption(CallAudioState.ROUTE_WIRED_HEADSET, "Headset", if (selectedRoute == CallAudioState.ROUTE_WIRED_HEADSET) "Current route" else "Use the wired headset", Icons.Default.PhoneInTalk, mask and CallAudioState.ROUTE_WIRED_HEADSET == CallAudioState.ROUTE_WIRED_HEADSET),
).filter { it.supported }

private fun audioRouteLabel(route: Int): String = when (route) {
    CallAudioState.ROUTE_SPEAKER -> "Speaker"
    CallAudioState.ROUTE_BLUETOOTH -> "Bluetooth"
    CallAudioState.ROUTE_WIRED_HEADSET -> "Headset"
    else -> "Earpiece"
}

private fun directionLabel(direction: Int?): String? = when (direction) {
    0 -> "Outgoing"
    1 -> "Incoming"
    2 -> "Unknown"
    else -> null
}

private fun Call.Details.callDirectionCompat(): Int? {
    return try {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) callDirection else null
    } catch (_: Throwable) {
        null
    }
}
