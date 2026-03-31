package com.opencontacts.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking

class BootCompletedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_LOCKED_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val settings = runBlocking {
            EntryPointAccessors.fromApplication(context.applicationContext, IncomingCallEntryPoint::class.java)
                .appLockRepository()
                .settings.first()
        }
        runCatching { ensureChannels(context, settings) }
        AppVisibilityTracker.setForeground(false)
    }
}
