package com.ochelper

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build

class OCHelperApp : Application() {

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_services),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "OCHelper background services"
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "ochelper_services"
        const val NOTIFICATION_ID_OCNODE = 1001
        const val NOTIFICATION_ID_MCP = 1002
        const val NOTIFICATION_ID_RTSP = 1003
        const val NOTIFICATION_ID_GATEWAY = 1004
    }
}
