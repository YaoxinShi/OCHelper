package com.ochelper.capability

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

class LocationCapability(private val context: Context) : DeviceCapability {
    override val id = "location.get"
    override val name = "Get Location"
    override val description = "Get the current device GPS/network location"
    override val inputSchema: JsonObject = buildJsonObject {}

    override suspend fun execute(params: JsonObject): JsonObject {
        val hasFine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        val hasCoarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            return buildJsonObject { put("error", "location permission not granted") }
        }

        val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Try last known first (fast)
        val lastKnown = try {
            if (hasFine) {
                locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            } else {
                locationManager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
            }
        } catch (_: SecurityException) { null }

        val location = lastKnown ?: withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine<Location?> { cont ->
                val listener = object : android.location.LocationListener {
                    override fun onLocationChanged(loc: Location) {
                        if (cont.isActive) cont.resume(loc)
                        locationManager.removeUpdates(this)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                }
                try {
                    val provider = if (hasFine) LocationManager.GPS_PROVIDER
                    else LocationManager.NETWORK_PROVIDER
                    locationManager.requestLocationUpdates(provider, 0L, 0f, listener)
                    cont.invokeOnCancellation { locationManager.removeUpdates(listener) }
                } catch (e: SecurityException) {
                    cont.resume(null)
                }
            }
        }

        return if (location != null) {
            buildJsonObject {
                put("latitude", location.latitude)
                put("longitude", location.longitude)
                put("accuracy_m", location.accuracy.toDouble())
                put("altitude_m", location.altitude)
                put("provider", location.provider ?: "unknown")
                put("timestamp_ms", location.time)
            }
        } else {
            buildJsonObject { put("error", "could not get location") }
        }
    }
}
