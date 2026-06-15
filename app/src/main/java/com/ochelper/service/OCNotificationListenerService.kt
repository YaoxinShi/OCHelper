package com.ochelper.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.ochelper.capability.OCNotificationStore

class OCNotificationListenerService : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        updateStore()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        updateStore()
    }

    private fun updateStore() {
        val activeNotifications = try { activeNotifications ?: emptyArray() } catch (_: Exception) { emptyArray() }
        val items = activeNotifications.map { sbn ->
            val extras = sbn.notification.extras
            OCNotificationStore.NotificationItem(
                id = sbn.key,
                packageName = sbn.packageName,
                title = extras.getString("android.title") ?: "",
                text = extras.getCharSequence("android.text")?.toString() ?: "",
                timestamp = sbn.postTime,
            )
        }
        OCNotificationStore.update(items)
    }
}
