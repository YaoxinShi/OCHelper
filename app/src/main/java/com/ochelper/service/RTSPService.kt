package com.ochelper.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.ochelper.OCHelperApp
import com.ochelper.R
import com.ochelper.rtsp.RTSPServer
import com.ochelper.rtsp.RTSPStreamConfig
import com.ochelper.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RTSPService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rtspServer: RTSPServer? = null
    private var previewSurface: android.view.Surface? = null

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _config = MutableStateFlow(RTSPStreamConfig())
    val config: StateFlow<RTSPStreamConfig> = _config.asStateFlow()

    inner class LocalBinder : Binder() {
        fun getService(): RTSPService = this@RTSPService
    }
    private val binder = LocalBinder()

    override fun onCreate() {
        super.onCreate()
        startForeground(OCHelperApp.NOTIFICATION_ID_RTSP, buildNotification())
        ServiceRegistry.rtspService = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    fun startStreaming(cfg: RTSPStreamConfig = RTSPStreamConfig()) {
        _config.value = cfg
        rtspServer?.stop()
        rtspServer = RTSPServer(applicationContext, cfg)
        rtspServer!!.setPreviewSurface(previewSurface)
        rtspServer!!.start()
        _isStreaming.value = true
    }

    fun stopStreaming() {
        rtspServer?.stop()
        rtspServer = null
        _isStreaming.value = false
    }

    /** Attach or detach an on-screen camera preview surface. */
    fun setPreviewSurface(surface: android.view.Surface?) {
        previewSurface = surface
        rtspServer?.setPreviewSurface(surface)
    }

    override fun onDestroy() {
        stopStreaming()
        scope.cancel()
        ServiceRegistry.rtspService = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, OCHelperApp.CHANNEL_ID)
            .setContentTitle("RTSP Stream")
            .setContentText(getString(R.string.notification_rtsp_running))
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    companion object {
        fun start(context: Context) {
            context.startForegroundService(Intent(context, RTSPService::class.java))
        }
        fun stop(context: Context) {
            context.stopService(Intent(context, RTSPService::class.java))
        }
    }
}
