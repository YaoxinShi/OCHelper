package com.ochelper.capability

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Reads active notifications via OCNotificationListenerService.
 * The service must be enabled in Notification Access settings.
 */
class NotificationCapability(private val context: Context) : DeviceCapability {
    override val id = "notifications.list"
    override val name = "List Notifications"
    override val description = "Get the current active notifications on the device (requires Notification Access permission)"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val notifications = OCNotificationStore.getNotifications()
        return buildJsonObject {
            put("count", notifications.size)
            put("notifications", kotlinx.serialization.json.buildJsonArray {
                notifications.forEach { n ->
                    add(buildJsonObject {
                        put("id", n.id)
                        put("app", n.packageName)
                        put("title", n.title)
                        put("text", n.text)
                        put("timestamp", n.timestamp)
                    })
                }
            })
        }
    }
}

/** Simple in-memory store populated by OCNotificationListenerService */
object OCNotificationStore {
    data class NotificationItem(
        val id: String,
        val packageName: String,
        val title: String,
        val text: String,
        val timestamp: Long,
    )

    private val items = mutableListOf<NotificationItem>()

    fun update(list: List<NotificationItem>) {
        synchronized(items) {
            items.clear()
            items.addAll(list)
        }
    }

    fun getNotifications(): List<NotificationItem> = synchronized(items) { items.toList() }
}
