package com.opencontacts.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.automirrored.filled.Launch
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BluetoothAudio
import androidx.compose.material.icons.filled.CallEnd
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.NoteAdd
import androidx.compose.material.icons.filled.Message
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PhoneInTalk
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.CallMerge
import androidx.compose.material.icons.filled.SwapCalls
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencontacts.core.common.buildCallRecordingFileName
import com.opencontacts.core.common.startInternalCallOrPrompt
import com.opencontacts.core.common.ContactCallBehaviorPreferences
import com.opencontacts.core.crypto.AppLockSettings
import com.opencontacts.core.ui.ReliableOutlinedTextField
import com.opencontacts.core.ui.theme.OpenContactsTheme
import com.opencontacts.data.repository.TransferDestinationManager
import dagger.hilt.android.EntryPointAccessors
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider

private const val EXTRA_FORCE_SHOW = "force_show"
internal const val EXTRA_OPEN_CONTACT_ID = "open_contact_id"

data class ActiveCallUiState(
    val displayName: String,
    val number: String,
    val photoUri: String? = null,
    val folderName: String? = null,
    val tags: List<String> = emptyList(),
    val contactId: String? = null,
    val phoneAccountLabel: String? = null,
    val phoneAccountId: String? = null,
)

object ActiveCallOverlayController {
    val state = MutableStateFlow<ActiveCallUiState?>(null)

    fun show(call: ActiveCallUiState) {
        state.value = call
    }

    fun clear() {
        state.value = null
    }
}

object CallRecordingStateHolder {
    val isRecording = MutableStateFlow(false)

    fun setRecording(active: Boolean) {
        isRecording.value = active
    }
}

internal fun launchActiveCallControls(context: Context, call: ActiveCallUiState, forceShow: Boolean = false) {
    ActiveCallOverlayController.show(call)
    postOngoingCallNotification(context, call)
    if (forceShow || AppVisibilityTracker.isForeground.value || canPresentCallUiOutsideApp(context)) {
        context.startActivity(
            Intent(context, ActiveCallControlsActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra(EXTRA_FORCE_SHOW, forceShow),
        )
    }
}

internal fun IncomingCallUiState.toActiveCallUiState(): ActiveCallUiState {
    return ActiveCallUiState(
        displayName = displayName,
        number = number,
        photoUri = photoUri,
        folderName = folderName,
        tags = tags,
        contactId = contactId,
    )
}

class ActiveCallControlsActivity : ComponentActivity() {
    private fun maybeShowMiniCallView() {
        if (TelecomCallCoordinator.activeCall.value != null || ActiveCallOverlayController.state.value != null) {
            MiniCallFloatingService.show(applicationContext)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        setContent {
            val settings = EntryPointAccessors.fromApplication(applicationContext, IncomingCallEntryPoint::class.java)
                .appLockRepository()
                .settings
                .collectAsStateWithLifecycle(initialValue = AppLockSettings.DEFAULT)
                .value
            OpenContactsTheme(
                themeMode = settings.themeMode,
                themePreset = settings.themePreset,
                accentPalette = settings.accentPalette,
                cornerStyle = settings.cornerStyle,
                backgroundCategory = settings.backgroundCategory,
                appFontProfile = settings.appFontProfile,
                customFontPath = settings.customFontPath,
            ) {
                ActiveCallControlsRoot(onDismiss = {
                    maybeShowMiniCallView()
                    finish()
                })
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        maybeShowMiniCallView()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) maybeShowMiniCallView()
    }

    override fun onResume() {
        super.onResume()
        MiniCallFloatingService.hide(applicationContext)
    }
}

@Composable
private fun ActiveCallControlsRoot(onDismiss: () -> Unit) {
    val overlayCall by ActiveCallOverlayController.state.collectAsStateWithLifecycle()
    val telecomCall by TelecomCallCoordinator.activeCall.collectAsStateWithLifecycle()
    val call = telecomCall ?: overlayCall
    var lastEndedCall by remember { mutableStateOf<ActiveCallUiState?>(null) }
    var showPostCallSheet by remember { mutableStateOf(false) }

    // Detect call ending: had a call, now null
    LaunchedEffect(telecomCall) {
        if (telecomCall == null && overlayCall != null) {
            lastEndedCall = overlayCall
            showPostCallSheet = true
        }
    }

    if (call == null && !showPostCallSheet) {
        LaunchedEffect(Unit) { onDismiss() }
        return
    }

    if (showPostCallSheet && lastEndedCall != null) {
        PostCallSheet(
            call = lastEndedCall!!,
            onDismiss = {
                showPostCallSheet = false
                onDismiss()
            },
        )
    } else if (call != null) {
        ActiveCallControlsScreen(call = call, onDismiss = onDismiss)
    }
}

private data class ControlButtonModel(
    val label: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val active: Boolean = false,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ActiveCallControlsScreen(call: ActiveCallUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val entryPoint = remember(context) {
        EntryPointAccessors.fromApplication(context.applicationContext, IncomingCallEntryPoint::class.java)
    }
    val scope = rememberCoroutineScope()
    val audioManager = remember(context) { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val telecomState by TelecomCallCoordinator.telecomState.collectAsStateWithLifecycle()
    var isRecording by rememberSaveable { mutableStateOf(false) }
    var lastRecordingLabel by rememberSaveable { mutableStateOf<String?>(null) }
    var noteDraft by rememberSaveable { mutableStateOf("") }
    var showNoteDialog by rememberSaveable { mutableStateOf(false) }
    var showDtmfDialog by rememberSaveable { mutableStateOf(false) }
    var showRouteDialog by rememberSaveable { mutableStateOf(false) }
    val recorder = remember(context, call.displayName, call.number) {
        CallAudioRecorder(
            context = context,
            transferDestinationManager = entryPoint.transferDestinationManager(),
            contactName = call.displayName.ifBlank { null },
            phoneNumber = call.number.ifBlank { null },
        )
    }
    var timerNow by remember { mutableLongStateOf(System.currentTimeMillis()) }

    DisposableEffect(Unit) {
        onDispose {
            if (isRecording) recorder.stop()
            isRecording = false
            CallRecordingStateHolder.setRecording(false)
        }
    }

    LaunchedEffect(telecomState.connectedAtMillis, telecomState.state) {
        while (telecomState.connectedAtMillis != null && telecomState.state != android.telecom.Call.STATE_DISCONNECTED && telecomState.state != android.telecom.Call.STATE_DISCONNECTING) {
            timerNow = System.currentTimeMillis()
            delay(1000L)
        }
    }

    val recordPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            runCatching { recorder.start() }
                .onSuccess {
                    isRecording = true
                    CallRecordingStateHolder.setRecording(true)
                    lastRecordingLabel = null
                    context.toast(context.getString(R.string.recording_started))
                }
                .onFailure { context.toast(it.message ?: context.getString(R.string.recording_unavailable)) }
        } else {
            context.toast(context.getString(R.string.recording_permission_required))
        }
    }

    val routeOptions = remember(telecomState.availableRoutes) {
        telecomState.availableRoutes.filter { it.supported }.distinctBy { it.route }
    }

    val controls = buildList {
        if (telecomState.canSpeaker) {
            add(
                ControlButtonModel(
                    label = if (telecomState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER) "Speaker on" else "Speaker",
                    icon = Icons.Default.VolumeUp,
                    active = telecomState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER,
                ) {
                    TelecomCallCoordinator.toggleSpeaker()
                    setSpeakerEnabled(audioManager, telecomState.currentAudioRoute != android.telecom.CallAudioState.ROUTE_SPEAKER)
                }
            )
        }
        if (telecomState.canMute) {
            add(ControlButtonModel(label = if (telecomState.isMuted) "Muted" else "Mute", icon = Icons.Default.Mic, active = telecomState.isMuted) {
                val enabled = !telecomState.isMuted
                TelecomCallCoordinator.setMuted(enabled)
                setMicMuted(audioManager, enabled)
            })
        }
        if (telecomState.canDtmf) {
            add(ControlButtonModel(label = "DTMF", icon = Icons.Default.Dialpad) { showDtmfDialog = true })
        }
        if (telecomState.canHold) {
            add(ControlButtonModel(label = if (telecomState.isOnHold) "Resume" else "Hold", icon = Icons.Default.PauseCircle, active = telecomState.isOnHold) {
                TelecomCallCoordinator.toggleHold()
            })
        }
        if (telecomState.canAddCall) {
            add(ControlButtonModel(label = "Add call", icon = Icons.Default.Add) {
                if (!TelecomCallCoordinator.launchAddCallUi(context)) {
                    startInternalCallOrPrompt(context, call.number)
                }
            })
        }
        // Merge / Swap — shown when there is an active call AND a held call
        if (telecomState.canMerge) {
            add(ControlButtonModel(label = "Merge", icon = Icons.Default.CallMerge) {
                TelecomCallCoordinator.mergeToConference()
            })
        }
        if (telecomState.callCount > 1) {
            add(ControlButtonModel(label = "Swap", icon = Icons.Default.SwapCalls) {
                TelecomCallCoordinator.swapCalls()
            })
        }
        add(ControlButtonModel(label = "Note", icon = Icons.Default.NoteAdd) { showNoteDialog = true })
        add(ControlButtonModel(label = if (isRecording) "Stop" else "Record", icon = Icons.Default.FiberManualRecord, active = isRecording) {
            if (isRecording) {
                lastRecordingLabel = recorder.stop()?.displayPath
                isRecording = false
                CallRecordingStateHolder.setRecording(false)
                context.toast(context.getString(R.string.recording_saved))
            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                runCatching { recorder.start() }
                    .onSuccess {
                        isRecording = true
                        CallRecordingStateHolder.setRecording(true)
                        lastRecordingLabel = null
                        context.toast(context.getString(R.string.recording_started))
                    }
                    .onFailure { context.toast(it.message ?: context.getString(R.string.recording_unavailable)) }
            } else {
                recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        })
        if (routeOptions.size > 1) {
            add(ControlButtonModel(label = "Audio", icon = Icons.Default.BluetoothAudio) { showRouteDialog = true })
        }
        if (!call.contactId.isNullOrBlank()) {
            add(ControlButtonModel(label = "Contact", icon = Icons.AutoMirrored.Filled.Launch) {
                context.startActivity(
                    Intent(context, MainActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        .putExtra(EXTRA_OPEN_CONTACT_ID, call.contactId),
                )
            })
        }
    }

    val headerLine = buildList {
        telecomState.phoneAccountLabel?.takeIf { it.isNotBlank() }?.let(::add)
        if (telecomState.secondaryCallCount > 0) add("${telecomState.secondaryCallCount + 1} calls")
        when (telecomState.state) {
            android.telecom.Call.STATE_ACTIVE -> add("Active")
            android.telecom.Call.STATE_HOLDING -> add("On hold")
            android.telecom.Call.STATE_DIALING -> add("Dialing")
            android.telecom.Call.STATE_CONNECTING -> add("Connecting")
            android.telecom.Call.STATE_DISCONNECTED -> add("Disconnected")
            else -> Unit
        }
    }

    val timerText = remember(telecomState.connectedAtMillis, timerNow, telecomState.disconnectAtMillis) {
        formatElapsed(telecomState.connectedAtMillis, telecomState.disconnectAtMillis ?: timerNow)
    }

    // Modern full-screen call UI
    val primary = MaterialTheme.colorScheme.primary
    val surface = MaterialTheme.colorScheme.surface
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val onBackground = MaterialTheme.colorScheme.onBackground
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    // Pulsing animation for the active-call avatar ring
    val infiniteTransition = rememberInfiniteTransition(label = "call_pulse")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = 0.65f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1.0f,
        targetValue = 1.10f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_scale",
    )

    val callEndRed = Color(0xFFDC2626)
    val callEndContainer = Color(0xFFFFEBEB)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // Background gradient blob
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(320.dp)
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            primaryContainer.copy(alpha = 0.55f),
                            MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        ),
                    ),
                ),
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 28.dp),
            verticalArrangement = Arrangement.SpaceBetween,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.85f)) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Icon(Icons.Default.PhoneInTalk, contentDescription = null, tint = primary, modifier = Modifier.size(18.dp))
                        Text("OpenContacts Call", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    }
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f),
                    modifier = Modifier.clickable { onDismiss() },
                ) {
                    Box(Modifier.padding(10.dp), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.CallEnd, contentDescription = "Minimize", tint = onBackground, modifier = Modifier.size(20.dp))
                    }
                }
            }

            // ── Caller info ───────────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // Status label chip
                Surface(
                    shape = RoundedCornerShape(50),
                    color = primary.copy(alpha = 0.12f),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(
                                    if (telecomState.state == android.telecom.Call.STATE_ACTIVE)
                                        Color(0xFF22C55E)
                                    else primary
                                ),
                        )
                        Text(
                            text = headerLine.joinToString(" · ").ifBlank { "Active call" },
                            style = MaterialTheme.typography.labelMedium,
                            color = primary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }

                // Pulsing avatar ring
                Box(contentAlignment = Alignment.Center) {
                    // Outer pulse ring
                    if (telecomState.state == android.telecom.Call.STATE_ACTIVE) {
                        Box(
                            modifier = Modifier
                                .size((96 * pulseScale).dp)
                                .clip(CircleShape)
                                .background(primary.copy(alpha = pulseAlpha * 0.25f)),
                        )
                    }
                    // Avatar circle
                    Box(
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .background(
                                brush = Brush.radialGradient(
                                    colors = listOf(primaryContainer, primaryContainer.copy(alpha = 0.7f)),
                                ),
                            ),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = call.displayName.take(1).ifBlank { "#" }.uppercase(),
                            style = MaterialTheme.typography.headlineLarge.copy(fontWeight = FontWeight.Bold),
                            color = primary,
                        )
                    }
                }

                Spacer(Modifier.height(4.dp))

                // Caller name
                Text(
                    text = call.displayName.ifBlank { call.number.ifBlank { context.getString(R.string.unknown_caller) } },
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = (-0.5).sp,
                    ),
                    textAlign = TextAlign.Center,
                    color = onBackground,
                )

                // Number (if different from name)
                if (call.number.isNotBlank() && call.displayName != call.number) {
                    Text(
                        call.number,
                        style = MaterialTheme.typography.titleMedium,
                        color = onSurfaceVariant,
                    )
                }

                // Timer
                if (timerText != null) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = surface,
                        shadowElevation = 2.dp,
                    ) {
                        Text(
                            timerText,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.titleSmall.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            ),
                            color = primary,
                        )
                    }
                }

                // Tags / folder chips
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    call.folderName?.takeIf { it.isNotBlank() }?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                    call.tags.take(3).forEach { tag -> AssistChip(onClick = {}, label = { Text(tag) }) }
                    telecomState.currentRouteLabel.takeIf { it.isNotBlank() }?.let { AssistChip(onClick = {}, label = { Text(it) }) }
                }

                // Error / recording labels
                telecomState.disconnectCauseLabel?.takeIf { telecomState.state == android.telecom.Call.STATE_DISCONNECTED }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                lastRecordingLabel?.let {
                    Text(
                        "Saved · $it",
                        style = MaterialTheme.typography.labelSmall,
                        color = primary,
                    )
                }
            }

            // ── Controls grid ─────────────────────────────────────────────
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .padding(vertical = 8.dp),
            ) {
                items(controls) { control -> ModernCallControlButton(control, primary = primary, surface = surface) }
            }

            // ── End call button ───────────────────────────────────────────
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .shadow(8.dp, CircleShape)
                        .clip(CircleShape)
                        .background(callEndRed)
                        .clickable {
                            TelecomCallCoordinator.disconnect()
                            ActiveCallOverlayController.clear()
                            cancelOngoingCallNotification(context)
                            onDismiss()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "End call",
                        tint = Color.White,
                        modifier = Modifier.size(32.dp),
                    )
                }
                Text(
                    "End call",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = callEndRed,
                )
            }
        }
    }

    if (showNoteDialog) {
        AlertDialog(
            onDismissRequest = { showNoteDialog = false },
            title = { Text(context.getString(R.string.add_note)) },
            text = {
                ReliableOutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    placeholder = { Text(context.getString(R.string.call_note_hint)) },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val vaultId = entryPoint.vaultSessionManager().activeVaultId.value
                    if (vaultId != null && noteDraft.isNotBlank()) {
                        val noteText = noteDraft.trim()
                        scope.launch(Dispatchers.IO) {
                            entryPoint.contactRepository().saveCallNote(
                                vaultId = vaultId,
                                contactId = call.contactId,
                                rawPhone = call.number,
                                direction = telecomState.directionLabel,
                                callStartedAt = telecomState.connectedAtMillis,
                                callEndedAt = telecomState.disconnectAtMillis,
                                durationSeconds = telecomState.elapsedSeconds(timerNow),
                                phoneAccountLabel = telecomState.phoneAccountLabel,
                                noteText = noteText,
                            )
                        }
                        context.toast("Call note saved")
                    } else {
                        context.toast("Write a note first")
                    }
                    noteDraft = ""
                    showNoteDialog = false
                }) { Text(context.getString(R.string.save)) }
            },
            dismissButton = {
                TextButton(onClick = { showNoteDialog = false }) { Text(context.getString(R.string.cancel)) }
            },
        )
    }

    if (showDtmfDialog) {
        DtmfDialog(onDismiss = { showDtmfDialog = false }, onDigit = TelecomCallCoordinator::sendDtmf)
    }

    if (showRouteDialog) {
        AlertDialog(
            onDismissRequest = { showRouteDialog = false },
            title = { Text("Audio route") },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    items(routeOptions) { route ->
                        Card(
                            onClick = {
                                TelecomCallCoordinator.setAudioRoute(route.route)
                                showRouteDialog = false
                            },
                            colors = CardDefaults.cardColors(
                                containerColor = if (route.route == telecomState.currentAudioRoute) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                            ),
                            shape = RoundedCornerShape(18.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(route.icon, contentDescription = null)
                                Spacer(Modifier.width(12.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(route.label, style = MaterialTheme.typography.titleMedium)
                                    Text(route.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRouteDialog = false }) { Text("Close") } },
        )
    }
}

@Composable
private fun ModernCallControlButton(control: ControlButtonModel, primary: Color, surface: Color) {
    val bgColor = if (control.active) primary.copy(alpha = 0.15f) else surface
    val iconTint = if (control.active) primary else MaterialTheme.colorScheme.onSurface
    val borderColor = if (control.active) primary.copy(alpha = 0.45f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .shadow(if (control.active) 6.dp else 2.dp, CircleShape)
                .clip(CircleShape)
                .background(bgColor)
                .then(
                    if (control.enabled)
                        Modifier.clickable(onClick = control.onClick)
                    else Modifier
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (control.active) {
                // Subtle ring for active state
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .clip(CircleShape)
                        .background(Color.Transparent)
                        .then(
                            Modifier.padding(1.dp),
                        ),
                )
            }
            Icon(
                imageVector = control.icon,
                contentDescription = control.label,
                tint = if (!control.enabled) iconTint.copy(alpha = 0.35f) else iconTint,
                modifier = Modifier.size(26.dp),
            )
        }
        Text(
            text = control.label,
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Medium),
            color = if (control.active) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

// Kept for backward compat — replaced by ModernCallControlButton
@Composable
private fun CallControlButton(control: ControlButtonModel) {
    ModernCallControlButton(
        control = control,
        primary = MaterialTheme.colorScheme.primary,
        surface = MaterialTheme.colorScheme.surface,
    )
}

@Composable
private fun DtmfDialog(onDismiss: () -> Unit, onDigit: (Char) -> Unit) {
    val keys = listOf('1', '2', '3', '4', '5', '6', '7', '8', '9', '*', '0', '#')
    val primary = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Keypad", style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                keys.chunked(3).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        row.forEach { key ->
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(56.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(primary.copy(alpha = 0.09f))
                                    .clickable { onDigit(key) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    key.toString(),
                                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.SemiBold),
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}

private class CallAudioRecorder(
    private val context: Context,
    private val transferDestinationManager: TransferDestinationManager,
    private val contactName: String?,
    private val phoneNumber: String?,
) {
    private var recorder: MediaRecorder? = null
    private var file: File? = null
    private var lastDisplayPath: String? = null
    private var descriptor: ParcelFileDescriptor? = null

    fun start() {
        if (recorder != null) return
        val fileName = buildCallRecordingFileName(contactName = contactName, phoneNumber = phoneNumber, createdAt = System.currentTimeMillis())
        val target = runBlocking { transferDestinationManager.prepareRecordingOutput(fileName) }
        lastDisplayPath = target.displayPath
        val mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
        mediaRecorder.apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioEncodingBitRate(128_000)
            setAudioSamplingRate(44_100)
            if (target.absolutePath != null) {
                val outputFile = File(target.absolutePath)
                outputFile.parentFile?.mkdirs()
                file = outputFile
                setOutputFile(outputFile.absolutePath)
            } else {
                descriptor = target.parcelFileDescriptor
                setOutputFile(descriptor?.fileDescriptor)
            }
            prepare()
            start()
        }
        recorder = mediaRecorder
    }

    fun stop(): SavedRecording? {
        val current = recorder ?: return lastDisplayPath?.let { SavedRecording(it) }
        runCatching { current.stop() }
        current.reset()
        current.release()
        recorder = null
        descriptor?.close()
        descriptor = null
        return lastDisplayPath?.let { SavedRecording(it) }.also {
            file = null
            lastDisplayPath = null
        }
    }
}

private data class SavedRecording(val displayPath: String)

@Suppress("DEPRECATION")
private fun setSpeakerEnabled(audioManager: AudioManager, enabled: Boolean) {
    audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
    audioManager.isSpeakerphoneOn = enabled
}

@Suppress("DEPRECATION")
private fun setMicMuted(audioManager: AudioManager, enabled: Boolean) {
    audioManager.isMicrophoneMute = enabled
}

private fun formatElapsed(startedAt: Long?, endAt: Long): String? {
    val start = startedAt ?: return null
    val totalSeconds = ((endAt - start).coerceAtLeast(0L) / 1000L).toInt()
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%02d:%02d".format(minutes, seconds)
}

private fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

// ── Post-call quick action sheet ──────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PostCallSheet(call: ActiveCallUiState, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Header
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.padding(bottom = 8.dp),
            ) {
                Icon(Icons.Default.CallEnd, null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(24.dp))
                Column {
                    Text("Call ended", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(call.displayName, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider()

            // Actions
            val quickTemplate = ContactCallBehaviorPreferences.getBehavior(context, call.contactId).quickSmsTemplate.orEmpty()
            listOf(
                Triple(Icons.Default.NoteAdd, "Add note") {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra(EXTRA_OPEN_CONTACT_ID, call.contactId)
                    )
                    onDismiss()
                },
                Triple(Icons.Default.Message, "Send SMS") {
                    context.startActivity(
                        Intent(context, MainActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                            .putExtra("navigate_to", "settings/smscomposer")
                            .putExtra("sms_to", call.number)
                            .putExtra("sms_name", call.displayName)
                            .putExtra("sms_contact_id", call.contactId)
                            .putExtra("sms_template", quickTemplate)
                    )
                    onDismiss()
                },
                Triple(Icons.Default.Call, "Call again") {
                    startInternalCallOrPrompt(context, call.number)
                    onDismiss()
                },
                Triple(Icons.Default.Schedule, "Remind me in 10 min") {
                    schedulePostCallReminder(context, call.number, call.displayName, 10)
                    onDismiss()
                },
                Triple(Icons.Default.PersonAdd, "Add to contacts") {
                    val intent = Intent(android.provider.ContactsContract.Intents.Insert.ACTION).apply {
                        type = android.provider.ContactsContract.RawContacts.CONTENT_TYPE
                        putExtra(android.provider.ContactsContract.Intents.Insert.PHONE, call.number)
                        putExtra(android.provider.ContactsContract.Intents.Insert.NAME, call.displayName)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    runCatching { context.startActivity(intent) }
                    onDismiss()
                },
            ).forEach { (icon, label, action) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { action() }
                        .padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
                    Text(label, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) {
                Text("Dismiss")
            }
        }
    }
}

private fun schedulePostCallReminder(context: Context, number: String, name: String, minutes: Int) {
    if (number.isBlank()) return
    val am = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
    val pi = android.app.PendingIntent.getBroadcast(
        context, number.hashCode() + 200,
        Intent(context, CallReminderReceiver::class.java).putExtra("number", number).putExtra("name", name),
        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
    )
    val triggerAt = System.currentTimeMillis() + minutes * 60_000L
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S && am.canScheduleExactAlarms()) {
        am.setExactAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
    } else {
        am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
    }
    Toast.makeText(context, "Will remind you to call $name in $minutes minutes", Toast.LENGTH_SHORT).show()
}
