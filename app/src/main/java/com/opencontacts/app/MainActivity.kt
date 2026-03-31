package com.opencontacts.app

import android.app.Activity
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.os.LocaleListCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opencontacts.core.common.isOpenContactsDefaultDialer
import com.opencontacts.app.telecom.requestDialerRole
import com.opencontacts.core.ui.localization.isArabicUi
import com.opencontacts.core.ui.theme.OpenContactsTheme
import com.opencontacts.feature.contacts.ContactDetailsRoute
import com.opencontacts.feature.contacts.ContactsRoute
import com.opencontacts.feature.dashboard.DashboardRoute
import com.opencontacts.feature.vaults.VaultsRoute
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            ThemedApp(viewModel)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onStop() {
        super.onStop()
        AppVisibilityTracker.setForeground(false)
        if (!isChangingConfigurations) {
            viewModel.onAppBackgrounded()
        }
    }

    override fun onStart() {
        super.onStart()
        AppVisibilityTracker.setForeground(true)
        if (!isChangingConfigurations) {
            viewModel.onAppForegrounded()
        }
    }
}

@Composable
private fun ThemedApp(viewModel: AppViewModel) {
    val settings by viewModel.appLockSettings.collectAsStateWithLifecycle()
    LaunchedEffect(settings.appLanguage) {
        val localeTags = when (settings.appLanguage.uppercase()) {
            "AR" -> "ar"
            "EN" -> "en"
            else -> ""
        }
        val currentTags = AppCompatDelegate.getApplicationLocales().toLanguageTags()
        if (currentTags != localeTags) {
            AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(localeTags))
        }
    }
    OpenContactsTheme(themeMode = settings.themeMode, themePreset = settings.themePreset, accentPalette = settings.accentPalette, cornerStyle = settings.cornerStyle, backgroundCategory = settings.backgroundCategory, appFontProfile = settings.appFontProfile, customFontPath = settings.customFontPath) {
        Surface(color = MaterialTheme.colorScheme.background) {
            AppRoot(viewModel)
        }
    }
}

@Composable
private fun AppRoot(viewModel: AppViewModel) {
    val shouldShowUnlock by viewModel.shouldShowUnlock.collectAsStateWithLifecycle()
    if (shouldShowUnlock) {
        UnlockRoute(viewModel = viewModel)
    } else {
        Box(modifier = Modifier.fillMaxSize()) {
            AppNavHost(viewModel)
            DefaultDialerBootstrapDialog(viewModel)
            IncomingCallInAppHost()
        }
    }
}


@Composable
private fun ActiveCallReturnEntry() {
    val localCall by ActiveCallOverlayController.state.collectAsStateWithLifecycle()
    val telecomCall by TelecomCallCoordinator.activeCall.collectAsStateWithLifecycle()
    val call = telecomCall ?: localCall
    val context = LocalContext.current
    if (call != null) {
        Box(modifier = Modifier.fillMaxSize()) {
            AssistChip(
                onClick = { launchActiveCallControls(context, call!!, forceShow = true) },
                label = { Text(text = call!!.displayName.ifBlank { call!!.number.ifBlank { "Call in progress" } }) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 24.dp),
            )
        }
    }
}

@Composable
private fun AppNavHost(viewModel: AppViewModel) {
    val navController = rememberNavController()
    val activity = LocalContext.current as? android.app.Activity
    // Handle deep-link navigation from notifications (e.g. "Send SMS" from missed call)
    LaunchedEffect(Unit) {
        val nav = activity?.intent?.getStringExtra("navigate_to")
        if (!nav.isNullOrBlank()) {
            navController.navigate(nav)
            activity.intent.removeExtra("navigate_to")
        }
    }
    val activeVaultName by viewModel.activeVaultName.collectAsStateWithLifecycle()
    val vaults by viewModel.vaults.collectAsStateWithLifecycle()
    val appSettings by viewModel.appLockSettings.collectAsStateWithLifecycle()
    val pendingContactId = activity?.intent?.getStringExtra(EXTRA_OPEN_CONTACT_ID)
    val pendingOpenDialpad = activity?.intent?.getBooleanExtra("open_dialpad", false) ?: false
    val pendingPrefillNumber = activity?.intent?.getStringExtra("prefill_number").orEmpty()
    val pendingCreateContactNumber = activity?.intent?.getStringExtra("create_contact_number").orEmpty()

    LaunchedEffect(pendingContactId) {
        if (!pendingContactId.isNullOrBlank()) {
            navController.navigate("contact/$pendingContactId")
            activity?.intent?.removeExtra(EXTRA_OPEN_CONTACT_ID)
        }
    }

    LaunchedEffect(pendingCreateContactNumber) {
        if (pendingCreateContactNumber.isNotBlank()) {
            activity?.intent?.removeExtra("create_contact_number")
        }
    }

    NavHost(navController = navController, startDestination = "contacts") {
        composable("contacts") {
            ContactsRoute(
                activeVaultName = activeVaultName,
                vaults = vaults,
                onOpenDetails = { navController.navigate("contact/$it") },
                onOpenWorkspace = { navController.navigate("workspace") },
                onOpenImportExport = { navController.navigate("settings/importexport") },
                onOpenSearch = null,
                onOpenSecurity = { navController.navigate("settings") },
                themeMode = appSettings.themeMode,
                onToggleThemeMode = {
                    val nextMode = if (appSettings.themeMode.equals("DARK", ignoreCase = true)) "LIGHT" else "DARK"
                    viewModel.setThemeMode(nextMode)
                },
                onOpenBackup = { navController.navigate("settings/importexport/backup") },
                onOpenTrash = { navController.navigate("settings/trash") },
                onOpenVaults = { navController.navigate("vaults") },
                onSwitchVault = viewModel::switchVault,
                initialDialPadVisible = pendingOpenDialpad,
                initialDialNumber = pendingPrefillNumber,
                initialCreateContactNumber = pendingCreateContactNumber,
            )
        }
        composable(route = "contact/{contactId}", arguments = listOf(navArgument("contactId") { type = NavType.StringType })) {
            ContactDetailsRoute(onBack = { navController.popBackStack() })
        }
        composable("vaults") { VaultsRoute(onBack = { navController.popBackStack() }) }
        composable("dashboard") {
            val vaultCount by viewModel.vaults.collectAsStateWithLifecycle()
            DashboardRoute(
                activeVaultName = activeVaultName,
                vaultCount = vaultCount.size,
                contactCount = 0,
                onOpenContacts = { navController.navigate("contacts") { popUpTo("dashboard") } },
                onOpenVaults = { navController.navigate("vaults") },
                onOpenSecurity = { navController.navigate("settings/security") },
                onOpenSearch = { navController.navigate("search") },
                onOpenWorkspace = { navController.navigate("workspace") },
                onOpenBackup = { navController.navigate("settings/importexport/backup") },
                onOpenImportExport = { navController.navigate("settings/importexport") },
            )
        }
        composable("workspace") {
            WorkspaceRoute(
                onBack = { navController.popBackStack() },
                onOpenDetails = { navController.navigate("contact/$it") },
            )
        }
        composable("search") { SearchRoute(onBack = { navController.popBackStack() }) }
        composable("settings") { SettingsHomeRoute(onBack = { navController.popBackStack() }, onNavigate = { navController.navigate(it) }) }
        composable("settings/security") { SecurityRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/backup") { BackupRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/importexport/backup") { BackupRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel, title = "Backup & Restore") }
        composable("settings/importexport") { ImportExportRoute(onBack = { navController.popBackStack() }, onOpenBackupRestore = { navController.navigate("settings/importexport/backup") }, appViewModel = viewModel) }
        composable("settings/preferences") { PreferencesRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/notifications") { NotificationsIncomingCallsRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/blocked") { BlockedContactsRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/trash") { TrashRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/appearance") { AppearanceRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/about") { AboutRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/recordings") { RecordingsRoute(onBack = { navController.popBackStack() }, appViewModel = viewModel) }
        composable("settings/flashsms") { FlashSmsRoute(onBack = { navController.popBackStack() }) }
        composable("settings/smscomposer") {
            val ctx = LocalContext.current
            val act = ctx as? android.app.Activity
            val smsTo = act?.intent?.getStringExtra("sms_to").orEmpty()
            val smsName = act?.intent?.getStringExtra("sms_name").orEmpty()
            val smsContactId = act?.intent?.getStringExtra("sms_contact_id")
            val smsTemplate = act?.intent?.getStringExtra("sms_template").orEmpty()
            SmsComposerRoute(
                onBack = { navController.popBackStack() },
                initialNumber = smsTo,
                initialName = smsName,
                initialContactId = smsContactId,
                initialTemplate = smsTemplate,
            )
        }
        composable("settings/permissions") { PermissionCheckerRoute(onBack = { navController.popBackStack() }) }
        composable("settings/debug") { DebugAnalyticsRoute(onBack = { navController.popBackStack() }) }
        composable("settings/autodialer") {
            val ctx = LocalContext.current
            com.opencontacts.feature.dialer.AutoDialerRoute(
                onBack = { navController.popBackStack() },
                onInitiateCall = { phone, _, speakerEnabled ->
                    com.opencontacts.core.common.startInternalCallOrPrompt(ctx, phone)
                    if (speakerEnabled) {
                        val audio = ctx.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                        audio.isSpeakerphoneOn = true
                    }
                },
                availableSims = com.opencontacts.core.common.availableCallSimOptions(ctx)
                    .map { com.opencontacts.feature.dialer.SimOption(it.label, it.slotIndex) },
            )
        }
        composable("settings/callscheduler") {
            val ctx = LocalContext.current
            val scheduledCalls = remember { androidx.compose.runtime.mutableStateListOf<com.opencontacts.feature.dialer.ScheduledCall>() }
            com.opencontacts.feature.dialer.CallSchedulerRoute(
                onBack = { navController.popBackStack() },
                availableSims = com.opencontacts.core.common.availableCallSimOptions(ctx)
                    .map { com.opencontacts.feature.dialer.SimOption(it.label, it.slotIndex) },
                scheduledCalls = scheduledCalls,
                onScheduleCall = { scheduledCalls.add(it) },
                onCancelCall = { scheduledCalls.remove(it) },
            )
        }
    }
}


@Composable
private fun DefaultDialerBootstrapDialog(viewModel: AppViewModel) {
    val context = LocalContext.current
    var openDialog by remember {
        mutableStateOf(shouldShowDefaultDialerBootstrap(context))
    }
    val requestRoleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val granted = isOpenContactsDefaultDialer(context)
        viewModel.syncCallingModeWithDefaultPhoneRole(granted)
        markDefaultDialerBootstrapShown(context)
        openDialog = false
    }
    if (!openDialog) return
    AlertDialog(
        onDismissRequest = {},
        title = { Text(text = if (isArabicUi()) "إعدادات الاتصال الافتراضي" else "Default calling setup") },
        text = {
            Text(
                text = if (isArabicUi()) {
                    "للحصول على أفضل تجربة اتصال داخل التطبيق، اجعل OpenContacts هو تطبيق الهاتف الافتراضي. لا يوجد في أندرويد دور افتراضي مستقل لتطبيق جهات الاتصال، لذلك سيتم استخدام هذا الإعداد لتحديد مسار الاتصال. إذا تخطيت هذه الخطوة الآن فسيتم التحويل تلقائياً إلى وضع system_phone_app."
                } else {
                    "For the deepest in-app calling experience, make OpenContacts the default phone app. Android does not provide a separate default-contacts role, so this setup controls the calling path. If you skip now, OpenContacts will automatically switch to system_phone_app mode."
                },
                textAlign = TextAlign.Start,
            )
        },
        confirmButton = {
            TextButton(onClick = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val roleManager = context.getSystemService(RoleManager::class.java)
                    if (roleManager != null && roleManager.isRoleAvailable(RoleManager.ROLE_DIALER) && !roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
                        requestRoleLauncher.launch(roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    } else {
                        viewModel.syncCallingModeWithDefaultPhoneRole(true)
                        markDefaultDialerBootstrapShown(context)
                        openDialog = false
                    }
                } else {
                    requestDialerRole(context)
                    viewModel.syncCallingModeWithDefaultPhoneRole(isOpenContactsDefaultDialer(context))
                    markDefaultDialerBootstrapShown(context)
                    openDialog = false
                }
            }) { Text(if (isArabicUi()) "تعيين كافتراضي" else "Set as default") }
        },
        dismissButton = {
            TextButton(onClick = {
                viewModel.syncCallingModeWithDefaultPhoneRole(false)
                markDefaultDialerBootstrapShown(context)
                openDialog = false
            }) { Text(if (isArabicUi()) "تخطي" else "Skip") }
        },
    )
}

private fun shouldShowDefaultDialerBootstrap(context: Context): Boolean {
    val prefs = context.getSharedPreferences("opencontacts_bootstrap", Context.MODE_PRIVATE)
    return !prefs.getBoolean(KEY_DEFAULT_DIALER_PROMPT_SHOWN, false)
}

private fun markDefaultDialerBootstrapShown(context: Context) {
    context.getSharedPreferences("opencontacts_bootstrap", Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_DEFAULT_DIALER_PROMPT_SHOWN, true)
        .apply()
}

private const val KEY_DEFAULT_DIALER_PROMPT_SHOWN = "default_dialer_prompt_shown"
