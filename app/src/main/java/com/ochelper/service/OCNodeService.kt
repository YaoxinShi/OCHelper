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
import com.ochelper.capability.CapabilityManager
import com.ochelper.data.AppPreferences
import com.ochelper.ocnode.OCNodeClient
import com.ochelper.ocnode.OCNodeConfig
import com.ochelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.UUID

class OCNodeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    lateinit var nodeClient: OCNodeClient
        private set
    private lateinit var prefs: AppPreferences
    private var connectJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        val capabilityManager = ServiceRegistry.getCapabilityManager(applicationContext)
        nodeClient = OCNodeClient(capabilityManager)
        startForeground(OCHelperApp.NOTIFICATION_ID_OCNODE, buildNotification())
        ServiceRegistry.ocNodeClient = nodeClient
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        connectJob?.cancel()
        connectJob = scope.launch {
            val url = prefs.ocNodeUrl.first()
            val token = prefs.ocNodeToken.first()
            var nodeId = prefs.ocNodeId.first()
            if (nodeId.isEmpty()) {
                nodeId = UUID.randomUUID().toString()
                prefs.setOcNodeId(nodeId)
            }
            if (url.isNotEmpty()) {
                nodeClient.connect(OCNodeConfig(
                    serverUrl = url,
                    authToken = token,
                    nodeId = nodeId,
                ))
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        nodeClient.disconnect()
        scope.cancel()
        ServiceRegistry.ocNodeClient = null
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
            .setContentTitle("OC Node")
            .setContentText(getString(R.string.notification_ocnode_running))
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, OCNodeService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, OCNodeService::class.java))
        }
    }
}
