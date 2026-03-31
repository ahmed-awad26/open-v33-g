package com.opencontacts.app.telecom

import android.app.Activity
import android.app.role.RoleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager

private const val DEFAULT_DIALER_REQUEST_CODE = 1001
private const val PHONE_ACCOUNT_ID = "OpenContactsDialer"
private const val PHONE_ACCOUNT_LABEL = "OpenContacts Dialer"

fun requestDialerRole(context: Context) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val roleManager = context.getSystemService(RoleManager::class.java) ?: return
        if (!roleManager.isRoleHeld(RoleManager.ROLE_DIALER)) {
            val intent = roleManager.createRequestRoleIntent(RoleManager.ROLE_DIALER)
            val activity = context as? Activity
            if (activity != null) {
                @Suppress("DEPRECATION")
                activity.startActivityForResult(intent, DEFAULT_DIALER_REQUEST_CODE)
            } else {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    } else {
        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).apply {
            putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}

fun registerPhoneAccount(context: Context) {
    val telecomManager = context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager ?: return
    val handle = PhoneAccountHandle(
        ComponentName(context, AppConnectionService::class.java),
        PHONE_ACCOUNT_ID,
    )
    val existing = runCatching { telecomManager.getPhoneAccount(handle) }.getOrNull()
    if (existing != null) return

    val account = PhoneAccount.builder(handle, PHONE_ACCOUNT_LABEL)
        .setCapabilities(PhoneAccount.CAPABILITY_CALL_PROVIDER)
        .build()

    runCatching { telecomManager.registerPhoneAccount(account) }
}
