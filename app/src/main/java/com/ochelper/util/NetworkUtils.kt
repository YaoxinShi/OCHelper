package com.ochelper.util

import android.content.Context
import android.net.wifi.WifiManager
import java.net.NetworkInterface

object NetworkUtils {
    /** Returns the device's best LAN IPv4 address (prefers WiFi, falls back to any non-loopback). */
    fun getLocalIpAddress(context: Context): String {
        // Try WiFi first
        try {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            val ip = wm.connectionInfo.ipAddress
            if (ip != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff
                )
            }
        } catch (_: Exception) {}

        // Fallback: enumerate interfaces
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (iface in interfaces.asSequence()) {
                if (iface.isLoopback || !iface.isUp) continue
                for (addr in iface.inetAddresses.asSequence()) {
                    if (addr.isLoopbackAddress) continue
                    val hostAddr = addr.hostAddress ?: continue
                    if (!hostAddr.contains(':')) return hostAddr // IPv4
                }
            }
        } catch (_: Exception) {}
        return "127.0.0.1"
    }
}
