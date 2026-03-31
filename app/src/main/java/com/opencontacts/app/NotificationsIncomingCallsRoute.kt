package com.opencontacts.app

import android.app.role.RoleManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.media.RingtoneManager
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.opencontacts.core.common.IncomingRingtonePreferences
import com.opencontacts.core.common.RingtonePreviewPlayer
import com.opencontacts.core.common.availableCallSimOptions
import androidx.core.app.NotificationManagerCompat
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun NotificationsIncomingCallsRoute(
    onBack: () -> Unit,
    appViewModel: AppViewModel,
) {
    val settings by appViewModel.appLockSettings.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    val notificationsEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    val batteryOptimized = !(context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) ?: false)
    val isDefaultDialer = isDefaultDialerApp(context)
    val contactsHandlerState = contactsHandlerState(context)
    var transparencySlider by remember(settings.incomingCallWindowTransparency) { mutableFloatStateOf(settings.incomingCallWindowTransparency.toFloat()) }
    var timeoutSlider by remember(settings.incomingCallAutoDismissSeconds) { mutableFloatStateOf(settings.incomingCallAutoDismissSeconds.toFloat()) }
    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let(appViewModel::setRecordingsFolder)
    }
    var pendingSimRingtoneHandle by remember { mutableStateOf<String?>(null) }
    val ringtonePicker = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val picked = result.data?.getParcelableExtra<Uri>(RingtoneManager.EXTRA_RINGTONE_PICKED_URI)
        pendingSimRingtoneHandle?.let { handleId ->
            IncomingRingtonePreferences.setSimRingtoneUri(context, handleId, picked?.toString())
        }
        pendingSimRingtoneHandle = null
    }

    SettingsScaffold(title = stringResource(R.string.notifications_calls_title), onBack = onBack) { modifier ->
        LazyColumn(modifier = modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                SettingsSection(title = "Default apps, roles, and access", subtitle = "Android grants deeper calling surfaces through the phone role and system permissions.") {
                    SettingsValueRow(
                        title = stringResource(R.string.default_dialer),
                        subtitle = "Required for the deepest Telecom-based in-call experience and system handoff.",
                        value = if (isDefaultDialer) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                    )
                    SettingsSpacer()
                    SettingsValueRow(
                        title = stringResource(R.string.contacts_handler),
                        subtitle = "Android has no dedicated contacts-app role, so OpenContacts relies on permissions and supported view intents.",
                        value = contactsHandlerState,
                    )
                    SettingsSpacer()
                    SettingsValueRow(
                        title = stringResource(R.string.display_over_apps),
                        subtitle = "Needed when Android allows the call card to appear above home or other apps.",
                        value = if (canDrawOverlays) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                    )
                    SettingsSpacer()
                    SettingsValueRow(
                        title = stringResource(R.string.notifications_enabled),
                        subtitle = "Heads-up and ongoing call return entry points depend on notifications.",
                        value = if (notificationsEnabled) stringResource(R.string.granted) else stringResource(R.string.not_granted),
                    )
                    SettingsSpacer()
                    androidx.compose.material3.TextButton(onClick = { openDefaultPhoneAppSettings(context) }) {
                        Text(if (isDefaultDialer) stringResource(R.string.open_default_apps) else stringResource(R.string.set_default_phone_app))
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                    }) {
                        Text(stringResource(R.string.open_overlay_settings))
                    }
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                            putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        })
                    }) { Text("Open notification settings") }
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                    }) { Text(stringResource(R.string.open_app_info)) }
                }
            }
            item {
                SettingsSection(title = "Auto-start and background readiness", subtitle = "Android and OEM battery managers may still limit incoming-call presentation if the app is background-restricted.") {
                    SettingsValueRow(
                        title = "Battery optimization",
                        subtitle = "Turning this off improves the reliability of lock-screen and call return flows on some devices.",
                        value = if (batteryOptimized) "Optimized" else "Unrestricted",
                    )
                    SettingsSpacer()
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
                    }) { Text("Request unrestricted battery") }
                    androidx.compose.material3.TextButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
                    }) { Text("Open battery optimization list") }
                    SettingsSpacer()
                    Text(
                        "Some manufacturers hide extra auto-start controls outside standard Android settings. If incoming UI feels unreliable after reboot or while backgrounded, allow OpenContacts in the device's auto-start / protected apps screen as well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsSection(title = stringResource(R.string.overlay_status_title), subtitle = "Overlay windows depend on Android version, manufacturer policies, and whether the user granted the required access.") {
                    Text(
                        if (canDrawOverlays) {
                            "Overlay access is enabled. OpenContacts can try the centered incoming-call surface above home, lock screen, and other apps, but Android may still fall back to a heads-up or full-screen call notification on some devices."
                        } else {
                            "Overlay access is disabled. OpenContacts will keep using the in-app host plus heads-up or full-screen call notifications until you grant the overlay permission."
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Popup delivery mode",
                        subtitle = "Overlay mode tries the centered activity above other apps and on the lock screen, while heads-up stays closer to Android defaults.",
                        selected = settings.overlayPopupMode,
                        choices = listOf("IN_APP_ONLY", "HEADS_UP", "OVERLAY_WINDOW"),
                        onSelect = appViewModel::setOverlayPopupMode,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Window size",
                        subtitle = "Compact keeps the floating card small and focused.",
                        selected = settings.incomingCallWindowSize,
                        choices = listOf("COMPACT", "EXPANDED"),
                        onSelect = appViewModel::setIncomingCallWindowSize,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Window position",
                        subtitle = "Choose where the floating call card sits when it appears above the app, home screen, or lock screen.",
                        selected = settings.incomingCallWindowPosition,
                        choices = listOf("TOP", "CENTER", "BOTTOM"),
                        onSelect = appViewModel::setIncomingCallWindowPosition,
                    )
                    SettingsSpacer()
                    Text("Transparency", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = transparencySlider,
                        onValueChange = { transparencySlider = it },
                        valueRange = 20f..100f,
                        onValueChangeFinished = { appViewModel.setIncomingCallWindowTransparency(transparencySlider.toInt()) },
                    )
                    Text(
                        "Background tint opacity: ${transparencySlider.toInt()}%",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSpacer()
                    Text("Corner radius", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = settings.callCardCornerRadius.toFloat(),
                        onValueChange = { appViewModel.setCallCardCornerRadius(it.toInt()) },
                        valueRange = 16f..40f,
                    )
                    Text(
                        "Card corner radius: ${settings.callCardCornerRadius}dp",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    SettingsSpacer()
                    Text("Auto dismiss", style = MaterialTheme.typography.titleMedium)
                    Slider(
                        value = timeoutSlider,
                        onValueChange = { timeoutSlider = it },
                        valueRange = 0f..30f,
                        steps = 29,
                        onValueChangeFinished = { appViewModel.setIncomingCallAutoDismissSeconds(timeoutSlider.toInt()) },
                    )
                    Text(
                        if (timeoutSlider.toInt() == 0) "The floating card stays visible until you answer, decline, or tap ×."
                        else "Hide the floating card automatically after ${timeoutSlider.toInt()} seconds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsSection(title = "Ongoing call return", subtitle = "A persistent notification remains the main way to return to the call screen after leaving it.") {
                    SettingsSwitchRow(
                        title = stringResource(R.string.ongoing_call_notification),
                        subtitle = "Keep a non-dismissible notification that opens the active call controls instantly.",
                        checked = true,
                        enabled = false,
                        onCheckedChange = {},
                    )
                    SettingsSpacer()
                    Text(
                        "OpenContacts now keeps a dedicated ongoing-call notification so you can always return to the call screen after going home, opening another app, locking the screen, or reopening OpenContacts.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            item {
                SettingsSection(title = "Visible content") {
                    SettingsSwitchRow(
                        title = "Show number",
                        subtitle = "Display the phone number inside the floating incoming-call card.",
                        checked = settings.incomingCallShowNumber,
                        onCheckedChange = appViewModel::setIncomingCallShowNumber,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show group/folder",
                        subtitle = "Include the current folder classification when available.",
                        checked = settings.incomingCallShowGroup,
                        onCheckedChange = appViewModel::setIncomingCallShowGroup,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show tags",
                        subtitle = "Display a few tags without overcrowding the call surface.",
                        checked = settings.incomingCallShowTag,
                        onCheckedChange = appViewModel::setIncomingCallShowTag,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show contact photo",
                        subtitle = "Use the caller photo in notifications and the incoming surface when available.",
                        checked = settings.showPhotoInNotifications,
                        onCheckedChange = appViewModel::setShowPhotoInNotifications,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Use photo as card background",
                        subtitle = "Fill the incoming call card with the contact photo and tint it using the transparency slider.",
                        checked = settings.incomingCallPhotoBackgroundEnabled,
                        onCheckedChange = appViewModel::setIncomingCallPhotoBackgroundEnabled,
                    )
                }
            }
            item {
                SettingsSection(title = "Incoming call ringtones", subtitle = "Choose a different ringtone for each SIM used by incoming calls in OpenContacts.") {
                    val sims = availableCallSimOptions(context)
                    if (sims.isEmpty()) {
                        Text(
                            "No call-capable SIM accounts were found on this device.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            sims.forEach { sim ->
                                val ringtoneTitle = IncomingRingtonePreferences.describeUri(
                                    context,
                                    IncomingRingtonePreferences.getSimRingtoneUri(context, sim.handleId),
                                )
                                SettingsValueRow(
                                    title = sim.label,
                                    subtitle = "Used for incoming calls on this SIM when the contact has no custom ringtone.",
                                    value = ringtoneTitle,
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    androidx.compose.material3.TextButton(onClick = {
                                        pendingSimRingtoneHandle = sim.handleId
                                        ringtonePicker.launch(buildSystemRingtonePickerIntent(context, IncomingRingtonePreferences.getSimRingtoneUri(context, sim.handleId)))
                                    }) { Text("Choose ringtone") }
                                    androidx.compose.material3.TextButton(onClick = {
                                        RingtonePreviewPlayer.play(context, IncomingRingtonePreferences.getSimRingtoneUri(context, sim.handleId))
                                    }) { Text("Preview") }
                                    androidx.compose.material3.TextButton(onClick = {
                                        RingtonePreviewPlayer.stop()
                                        IncomingRingtonePreferences.setSimRingtoneUri(context, sim.handleId, null)
                                    }) { Text("Clear") }
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsSection(title = "Recording & storage", subtitle = "Pick a dedicated SAF folder for call recordings. Recordings still follow Android audio restrictions on many devices.") {
                    SettingsValueRow(
                        title = stringResource(R.string.recordings_folder),
                        subtitle = "Current save location for call recordings.",
                        value = describeRecordingsLocation(context, settings.recordingsFolderUri, settings.recordingsFolderName, settings.recordingsPath),
                    )
                    SettingsSpacer()
                    androidx.compose.material3.TextButton(onClick = { folderPicker.launch(null) }) {
                        Text(stringResource(R.string.choose_folder))
                    }
                    androidx.compose.material3.TextButton(onClick = appViewModel::resetRecordingsFolder) {
                        Text(stringResource(R.string.reset_to_app_storage))
                    }
                    settings.recordingsFolderUri?.let { uriString ->
                        SettingsSpacer()
                        androidx.compose.material3.TextButton(onClick = {
                            val intent = Intent(Intent.ACTION_VIEW).apply {
                                data = Uri.parse(uriString)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(intent)
                        }) {
                            Text("Open selected folder")
                        }
                    }
                }
            }
            item {
                SettingsSection(title = "Missed calls and privacy") {
                    SettingsSwitchRow(
                        title = "Enable missed call notification",
                        subtitle = "Post a compact missed-call notification with quick actions.",
                        checked = settings.enableMissedCallNotification,
                        onCheckedChange = appViewModel::setEnableMissedCallNotification,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Show folder and tags in notifications",
                        subtitle = "Keep contextual classification visible when privacy settings allow it.",
                        checked = settings.showFolderTagsInNotifications,
                        onCheckedChange = appViewModel::setShowFolderTagsInNotifications,
                    )
                    SettingsSpacer()
                    SettingsChoiceRow(
                        title = "Lock-screen visibility",
                        subtitle = "Choose whether secure lock screens show full caller details or hide sensitive information.",
                        selected = settings.lockScreenNotificationVisibility,
                        choices = listOf("SHOW_FULL", "HIDE_SENSITIVE", "SHOW_NAME_ONLY", "SHOW_NUMBER_ONLY"),
                        onSelect = appViewModel::setLockScreenNotificationVisibility,
                    )
                    SettingsSpacer()
                    SettingsSwitchRow(
                        title = "Heads-up notifications",
                        subtitle = "Keep urgent incoming call notifications visible above normal notifications.",
                        checked = settings.headsUpNotifications,
                        onCheckedChange = appViewModel::setHeadsUpNotifications,
                    )
                }
            }
        }
    }
}

private fun isDefaultDialerApp(context: android.content.Context): Boolean {
    val telecomManager = context.getSystemService(android.content.Context.TELECOM_SERVICE) as? android.telecom.TelecomManager
    return telecomManager?.defaultDialerPackage == context.packageName
}

private fun contactsHandlerState(context: android.content.Context): String {
    val intent = Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI)
    val resolved = intent.resolveActivity(context.packageManager)?.packageName
    return when {
        resolved == context.packageName -> "Handles contact-view intents"
        resolved.isNullOrBlank() -> "No supported contacts intent handler"
        else -> "Another app handles contact-view intents"
    }
}

private fun openDefaultPhoneAppSettings(context: android.content.Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java)
        if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
            context.startActivity(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
    }
    context.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

private fun describeRecordingsLocation(context: android.content.Context, uriString: String?, displayName: String?, fallbackPath: String): String {
    return if (!uriString.isNullOrBlank()) {
        displayName?.takeIf(String::isNotBlank) ?: DocumentFile.fromTreeUri(context, Uri.parse(uriString))?.name ?: "Selected folder"
    } else {
        "App storage/$fallbackPath"
    }
}
