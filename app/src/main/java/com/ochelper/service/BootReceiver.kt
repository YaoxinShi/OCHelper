package com.ochelper.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            val prefs = android.preference.PreferenceManager.getDefaultSharedPreferences(context)
            // Services are started on demand from MainActivity; boot-start is optional
            // OCNodeService.start(context)
        }
    }
}
