package com.ochelper.capability

import android.content.Context
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.provider.Settings
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class SystemSettingsCapability(private val context: Context) : DeviceCapability {
    override val id = "system.settings"
    override val name = "System Settings"
    override val description = "Read and write system settings: brightness, volume, WiFi state, do-not-disturb"
    override val inputSchema: JsonObject = buildJsonObject {
        put("type", "object")
    }

    override suspend fun execute(params: JsonObject): JsonObject {
        val action = params["action"]?.jsonPrimitive?.content ?: "get"
        val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

        return when (action) {
            "get" -> buildJsonObject {
                val brightness = try {
                    Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
                } catch (_: Exception) { -1 }
                put("brightness", brightness)
                put("volume_music", audio.getStreamVolume(AudioManager.STREAM_MUSIC))
                put("volume_ring", audio.getStreamVolume(AudioManager.STREAM_RING))
                val ringerMode = when (audio.ringerMode) {
                    AudioManager.RINGER_MODE_SILENT -> "silent"
                    AudioManager.RINGER_MODE_VIBRATE -> "vibrate"
                    else -> "normal"
                }
                put("ringer_mode", ringerMode)
                val wm = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
                @Suppress("DEPRECATION")
                put("wifi_enabled", wm.isWifiEnabled)
            }
            "set_brightness" -> {
                val value = params["value"]?.jsonPrimitive?.content?.toIntOrNull() ?: 128
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS, value.coerceIn(0, 255))
                buildJsonObject { put("ok", true) }
            }
            "set_volume" -> {
                val stream = when (params["stream"]?.jsonPrimitive?.content) {
                    "ring" -> AudioManager.STREAM_RING
                    "alarm" -> AudioManager.STREAM_ALARM
                    else -> AudioManager.STREAM_MUSIC
                }
                val level = params["level"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5
                audio.setStreamVolume(stream, level, 0)
                buildJsonObject { put("ok", true) }
            }
            else -> buildJsonObject { put("error", "unknown action: $action") }
        }
    }
}
