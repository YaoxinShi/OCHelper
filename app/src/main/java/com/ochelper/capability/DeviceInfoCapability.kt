package com.ochelper.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class DeviceInfoCapability(private val context: Context) : DeviceCapability {
    override val id = "device.info"
    override val name = "Device Information"
    override val description = "Get Android device information including battery, storage, and system details"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val bm = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
        var batteryLevel = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        // BATTERY_PROPERTY_CAPACITY is unsupported on some devices/emulators and
        // returns Integer.MIN_VALUE. Fall back to the sticky battery broadcast.
        if (batteryLevel == Int.MIN_VALUE || batteryLevel < 0 || batteryLevel > 100) {
            val batteryIntent = context.registerReceiver(
                null,
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
            )
            val level = batteryIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
            val scale = batteryIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
            batteryLevel = if (level >= 0 && scale > 0) (level * 100) / scale else -1
        }
        val isCharging = bm.isCharging

        val statFs = StatFs(Environment.getDataDirectory().path)
        val totalBytes = statFs.totalBytes
        val freeBytes = statFs.freeBytes

        val runtime = Runtime.getRuntime()
        val usedMemMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
        val totalMemMb = runtime.totalMemory() / (1024 * 1024)

        return buildJsonObject {
            put("manufacturer", Build.MANUFACTURER)
            put("model", Build.MODEL)
            put("android_version", Build.VERSION.RELEASE)
            put("sdk_int", Build.VERSION.SDK_INT)
            put("battery_level", batteryLevel)
            put("battery_charging", isCharging)
            put("storage_total_gb", "%.1f".format(totalBytes / 1e9).toDouble())
            put("storage_free_gb", "%.1f".format(freeBytes / 1e9).toDouble())
            put("memory_used_mb", usedMemMb)
            put("memory_total_mb", totalMemMb)
        }
    }
}
