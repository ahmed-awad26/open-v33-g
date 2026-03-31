package com.opencontacts.app

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.telecom.TelecomManager

internal fun canPresentCallUiOutsideApp(context: Context): Boolean {
    val isDefaultDialer = (context.getSystemService(Context.TELECOM_SERVICE) as? TelecomManager)?.defaultDialerPackage == context.packageName
    val canDrawOverlays = Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(context)
    return isDefaultDialer || canDrawOverlays
}
