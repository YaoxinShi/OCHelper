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
import com.ochelper.mcp.MCPServer
import com.ochelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class MCPServerService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var mcpServer: MCPServer? = null
    private lateinit var prefs: AppPreferences

    override fun onCreate() {
        super.onCreate()
        prefs = AppPreferences(this)
        startForeground(OCHelperApp.NOTIFICATION_ID_MCP, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        scope.launch {
            val port = prefs.mcpPort.first()
            val token = prefs.mcpToken.first().ifEmpty {
                AppPreferences.generateToken().also { prefs.setMcpToken(it) }
            }
            val capabilityManager = ServiceRegistry.getCapabilityManager(applicationContext)
            mcpServer = MCPServer(capabilityManager, port = port, bearerToken = token)
            mcpServer!!.start()
            ServiceRegistry.mcpServer = mcpServer
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mcpServer?.stop()
        mcpServer = null
        scope.cancel()
        ServiceRegistry.mcpServer = null
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
            .setContentTitle("MCP Server")
            .setContentText(getString(R.string.notification_mcp_running))
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, MCPServerService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, MCPServerService::class.java))
        }
    }
}
