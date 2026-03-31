package com.opencontacts.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.util.DisplayMetrics
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.CallEnd
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.*
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.opencontacts.core.ui.theme.OpenContactsTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * MiniCallFloatingService – shows a draggable mini call chip when the user
 * leaves the active call screen while a call is still in progress.
 *
 * Controls:
 *   - Tap → open full call screen
 *   - Speaker button → toggle speaker
 *   - Mute button   → toggle mute
 *   - Drag          → reposition chip
 *
 * Removed automatically when the call ends.
 */
class MiniCallFloatingService : Service() {

    private var windowManager: WindowManager? = null
    private var composeView: ComposeView? = null
    private val serviceScope = CoroutineScope(Dispatchers.Main)
    private var callWatchJob: Job? = null

    // WindowManager layout params (mutable for drag)
    private var layoutParams: WindowManager.LayoutParams? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        if (!canShowOverlay()) {
            stopSelf()
            return
        }
        setupComposeView()
        watchCallState()
    }

    private fun canShowOverlay(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun setupComposeView() {
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 24
            y = 160
        }
        layoutParams = lp

        val owner = FakeLifecycleOwner()
        owner.start()

        composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(owner)
            setContent {
                OpenContactsTheme {
                    MiniCallChip(
                        onOpen = { openFullCallScreen() },
                        onDrag = { dx, dy -> relocate(dx, dy) },
                        onDragEnd = { snapToNearestEdge() },
                    )
                }
            }
        }

        runCatching { windowManager?.addView(composeView, lp) }
    }

    private fun relocate(dx: Float, dy: Float) {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        val bounds = overlayBounds(view.width, view.height)
        lp.x = (lp.x + dx.toInt()).coerceIn(bounds.minX, bounds.maxX)
        lp.y = (lp.y + dy.toInt()).coerceIn(bounds.minY, bounds.maxY)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun snapToNearestEdge() {
        val lp = layoutParams ?: return
        val view = composeView ?: return
        val bounds = overlayBounds(view.width, view.height)
        val centerX = lp.x + (view.width / 2)
        val targetX = if (centerX < bounds.screenWidth / 2) bounds.minX else bounds.maxX
        lp.x = targetX.coerceIn(bounds.minX, bounds.maxX)
        lp.y = lp.y.coerceIn(bounds.minY, bounds.maxY)
        runCatching { windowManager?.updateViewLayout(view, lp) }
    }

    private fun overlayBounds(viewWidth: Int, viewHeight: Int): OverlayBounds {
        val metrics = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            resources.displayMetrics
        } else {
            @Suppress("DEPRECATION")
            DisplayMetrics().also { windowManager?.defaultDisplay?.getMetrics(it) }
        }
        val screenWidth = metrics.widthPixels.coerceAtLeast(1)
        val screenHeight = metrics.heightPixels.coerceAtLeast(1)
        val margin = (16 * resources.displayMetrics.density).toInt()
        val safeWidth = viewWidth.coerceAtLeast((82 * resources.displayMetrics.density).toInt())
        val safeHeight = viewHeight.coerceAtLeast((82 * resources.displayMetrics.density).toInt())
        return OverlayBounds(
            minX = margin,
            maxX = (screenWidth - safeWidth - margin).coerceAtLeast(margin),
            minY = margin,
            maxY = (screenHeight - safeHeight - margin * 4).coerceAtLeast(margin),
            screenWidth = screenWidth,
        )
    }

    private fun openFullCallScreen() {
        ActiveCallOverlayController.state.value?.let {
            launchActiveCallControls(this, it, forceShow = true)
        }
    }

    private fun watchCallState() {
        callWatchJob = serviceScope.launch {
            TelecomCallCoordinator.activeCall.collectLatest { call ->
                if (call == null) {
                    // Call ended — remove ourselves
                    stopSelf()
                }
            }
        }
    }

    override fun onDestroy() {
        callWatchJob?.cancel()
        serviceScope.cancel()
        composeView?.let { runCatching { windowManager?.removeView(it) } }
        composeView = null
        super.onDestroy()
    }

    private data class OverlayBounds(
        val minX: Int,
        val maxX: Int,
        val minY: Int,
        val maxY: Int,
        val screenWidth: Int,
    )

    companion object {
        fun show(context: Context) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) return
            context.startService(Intent(context, MiniCallFloatingService::class.java))
        }

        fun hide(context: Context) {
            context.stopService(Intent(context, MiniCallFloatingService::class.java))
        }
    }
}

// ── Mini Chip Composable ───────────────────────────────────────────────────────

@Composable
private fun MiniCallChip(
    onOpen: () -> Unit,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val activeCall by TelecomCallCoordinator.activeCall.collectAsState()
    val telecomState by TelecomCallCoordinator.telecomState.collectAsState()
    val recordingState by CallRecordingStateHolder.isRecording.collectAsState()
    val overlayCall by ActiveCallOverlayController.state.collectAsState()
    var collapsed by remember { mutableStateOf(false) }
    var lastTapAt by remember { mutableStateOf(0L) }

    var elapsed by remember { mutableStateOf(0) }
    LaunchedEffect(telecomState.connectedAtMillis) {
        while (true) {
            val connected = telecomState.connectedAtMillis
            elapsed = if (connected != null) ((System.currentTimeMillis() - connected) / 1000).toInt() else 0
            delay(1000)
        }
    }

    val isSpeaker = telecomState.currentAudioRoute == android.telecom.CallAudioState.ROUTE_SPEAKER
    val isMuted = telecomState.isMuted
    val call = activeCall ?: return
    val title = call.displayName.ifBlank { call.number.ifBlank { "Unknown" } }
    val simName = telecomState.phoneAccountLabel ?: overlayCall?.phoneAccountLabel ?: "Default SIM"
    val recordingLabel = if (recordingState) "REC" else "Ready"
    val muteLabel = if (isMuted) "Muted" else "Live"
    val subtitle = buildString {
        if (call.number.isNotBlank() && call.number != title) append(call.number)
        if (isNotBlank()) append("  •  ")
        append(formatElapsed(elapsed))
    }

    Surface(
        modifier = Modifier
            .widthIn(min = if (collapsed) 82.dp else 236.dp, max = if (collapsed) 82.dp else 292.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = onDragEnd,
                    onDrag = { _, dragAmount -> onDrag(dragAmount.x, dragAmount.y) },
                )
            }
            .combinedClickable(
                onClick = {
                    if (collapsed) collapsed = false
                    lastTapAt = SystemClock.elapsedRealtime()
                },
                onDoubleClick = {
                    lastTapAt = SystemClock.elapsedRealtime()
                    onOpen()
                },
                onLongClick = { collapsed = !collapsed },
            ),
        shape = RoundedCornerShape(if (collapsed) 28.dp else 24.dp),
        color = Color(0xFF101828),
        shadowElevation = 18.dp,
        tonalElevation = 8.dp,
    ) {
        if (collapsed) {
            Column(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1D4ED8)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = title.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF22C55E))
                )
                Text(
                    text = formatElapsed(elapsed),
                    color = Color(0xFFE2E8F0),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    Text(
                        text = if (recordingState) "REC" else if (isMuted) "MUTED" else simName.take(8),
                        color = if (recordingState) Color(0xFFF87171) else Color(0xFFCBD5E1),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        } else {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF1D4ED8)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            text = title.take(1).uppercase(),
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp,
                        )
                    }
                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Box(
                                Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF22C55E))
                            )
                            Text(
                                text = title,
                                color = Color.White,
                                fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        Text(
                            text = subtitle,
                            color = Color(0xFF94A3B8),
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MiniStatusPill(
                                text = simName,
                                background = Color(0xFF1E293B),
                                content = Color(0xFFC7D2FE),
                            )
                            MiniStatusPill(
                                text = recordingLabel,
                                background = if (recordingState) Color(0xFF7F1D1D) else Color(0xFF1F2937),
                                content = if (recordingState) Color(0xFFFCA5A5) else Color(0xFFCBD5E1),
                            )
                            MiniStatusPill(
                                text = muteLabel,
                                background = if (isMuted) Color(0xFF7C2D12) else Color(0xFF0F172A),
                                content = if (isMuted) Color(0xFFF97316) else Color(0xFF86EFAC),
                            )
                        }
                    }
                    Icon(
                        Icons.Default.CallEnd,
                        contentDescription = "Collapse",
                        tint = Color(0xFFCBD5E1),
                        modifier = Modifier.size(18.dp).clickable { collapsed = true },
                    )
                }

                Spacer(Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    MiniChipButton(
                        icon = if (isSpeaker) Icons.Default.VolumeUp else Icons.Default.VolumeOff,
                        active = isSpeaker,
                        label = "Speaker",
                        onClick = { TelecomCallCoordinator.toggleSpeaker() },
                    )
                    MiniChipButton(
                        icon = if (isMuted) Icons.Default.MicOff else Icons.Default.Mic,
                        active = isMuted,
                        label = "Mute",
                        activeColor = Color(0xFFF97316),
                        onClick = { TelecomCallCoordinator.setMuted(!isMuted) },
                    )
                    MiniChipButton(
                        icon = Icons.Default.OpenInFull,
                        active = false,
                        label = "Open",
                        activeColor = Color(0xFF38BDF8),
                        onClick = onOpen,
                    )
                    MiniChipButton(
                        icon = Icons.Outlined.CallEnd,
                        active = true,
                        label = "End",
                        activeColor = Color(0xFFEF4444),
                        onClick = { TelecomCallCoordinator.disconnect() },
                    )
                }
            }
        }
    }
}

@Composable
private fun MiniStatusPill(
    text: String,
    background: Color,
    content: Color,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            color = content,
            fontSize = 9.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun MiniChipButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    active: Boolean,
    label: String,
    activeColor: Color = Color(0xFF4CAF50),
    onClick: () -> Unit,
) {
    val bg = if (active) activeColor.copy(alpha = 0.18f) else Color.White.copy(alpha = 0.08f)
    val tint = if (active) activeColor else Color(0xFFCBD5E1)
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = bg,
        modifier = Modifier.size(width = 58.dp, height = 46.dp),
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = tint, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
    }
}

private fun formatElapsed(seconds: Int): String {
    val m = seconds / 60; val s = seconds % 60
    return "%d:%02d".format(m, s)
}

// ── Fake lifecycle owner for ComposeView in a Service ──────────────────────────

private class FakeLifecycleOwner : LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    fun start() {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun stop() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
    }
}
