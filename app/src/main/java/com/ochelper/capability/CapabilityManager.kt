package com.ochelper.capability

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Central registry for all DeviceCapability instances.
 * Maintains per-capability enable/disable state.
 */
class CapabilityManager(context: Context) {

    private val appContext = context.applicationContext

    /** All registered capabilities, keyed by id */
    val all: Map<String, DeviceCapability> by lazy { buildCapabilities() }

    private val _enabledState = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val enabledState: StateFlow<Map<String, Boolean>> = _enabledState.asStateFlow()

    fun setEnabled(id: String, enabled: Boolean) {
        _enabledState.value = _enabledState.value + (id to enabled)
    }

    fun isEnabled(id: String): Boolean = _enabledState.value[id] ?: defaultEnabled(id)

    fun enabledCapabilities(): List<DeviceCapability> =
        all.values.filter { isEnabled(it.id) }

    suspend fun execute(id: String, params: JsonObject): JsonObject {
        val cap = all[id] ?: return buildJsonObject { put("error", "unknown capability: $id") }
        if (!isEnabled(id)) return buildJsonObject { put("error", "capability disabled: $id") }
        return try {
            cap.execute(params)
        } catch (e: Exception) {
            Log.e("CapabilityManager", "execute($id) failed", e)
            buildJsonObject { put("error", e.message ?: "execution failed") }
        }
    }

    private fun defaultEnabled(id: String): Boolean = when {
        id.startsWith("contacts") || id.startsWith("calendar") ||
        id.startsWith("notifications") || id.startsWith("sms") -> false
        else -> true
    }

    private fun buildCapabilities(): Map<String, DeviceCapability> {
        val caps = listOf(
            DeviceInfoCapability(appContext),
            SystemSettingsCapability(appContext),
            CameraCapability(appContext),
            GalleryCapability(appContext),
            MicrophoneCapability(appContext),
            ScreenCapability(appContext),
            LocationCapability(appContext),
            NotificationCapability(appContext),
            ContactsCapability(appContext),
            CalendarCapability(appContext),
            AppManagerCapability(appContext),
        )
        return caps.associateBy { it.id }
    }
}
