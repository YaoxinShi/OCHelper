package com.ochelper.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ochelper.OCHelperApp
import com.ochelper.R
import com.ochelper.data.AppPreferences
import com.ochelper.gateway.GatewayConnectionState
import com.ochelper.gateway.OCGatewayClient
import com.ochelper.gateway.OCGatewayConfig
import com.ochelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class OCGatewayService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var prefs: AppPreferences
    val gatewayClient = OCGatewayClient()

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        startForeground(OCHelperApp.NOTIFICATION_ID_GATEWAY, buildNotification())
        ServiceRegistry.gatewayClient = gatewayClient
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val url = prefs.gatewayUrl.first()
            val apiKey = prefs.gatewayApiKey.first()
            val model = prefs.gatewayDefaultModel.first()
            if (url.isNotEmpty()) {
                gatewayClient.connect(OCGatewayConfig(
                    gatewayUrl = url,
                    apiKey = apiKey,
                    defaultModel = model,
                ))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        gatewayClient.disconnect()
        scope.cancel()
        ServiceRegistry.gatewayClient = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OCHelperApp.CHANNEL_ID)
            .setContentTitle("OC Gateway")
            .setContentText(getString(R.string.notification_gateway_running))
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, OCGatewayService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, OCGatewayService::class.java))
        }
    }
}
