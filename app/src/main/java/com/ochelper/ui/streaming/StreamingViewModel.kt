package com.ochelper.ui.streaming

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ochelper.data.AppPreferences
import com.ochelper.rtsp.RTSPStreamConfig
import com.ochelper.service.RTSPService
import com.ochelper.service.ServiceRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class StreamingViewModel(private val context: Context) : ViewModel() {
    private val prefs = AppPreferences(context)

    val port = prefs.rtspPort.stateIn(viewModelScope, SharingStarted.Eagerly, 8554)
    val resolution = prefs.rtspResolution.stateIn(viewModelScope, SharingStarted.Eagerly, "1280x720")
    val fps = prefs.rtspFps.stateIn(viewModelScope, SharingStarted.Eagerly, 30)
    val bitrate = prefs.rtspBitrate.stateIn(viewModelScope, SharingStarted.Eagerly, 2_000_000)
    val cameraFacing = prefs.rtspCameraFacing.stateIn(viewModelScope, SharingStarted.Eagerly, "back")

    val isStreaming: StateFlow<Boolean> = flow {
        while (true) {
            emit(ServiceRegistry.rtspService?.isStreaming?.value == true)
            kotlinx.coroutines.delay(500)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun startStreaming() {
        viewModelScope.launch {
            val resStr = prefs.rtspResolution.first()
            val parts = resStr.split("x")
            val w = parts.getOrNull(0)?.toIntOrNull() ?: 1280
            val h = parts.getOrNull(1)?.toIntOrNull() ?: 720
            val cfg = RTSPStreamConfig(
                port = prefs.rtspPort.first(),
                width = w, height = h,
                frameRate = prefs.rtspFps.first(),
                bitrateBps = prefs.rtspBitrate.first(),
                cameraFacing = prefs.rtspCameraFacing.first(),
            )
            prefs.setRtspEnabled(true)
            RTSPService.start(context)
            kotlinx.coroutines.delay(500)
            ServiceRegistry.rtspService?.startStreaming(cfg)
        }
    }

    fun stopStreaming() {
        viewModelScope.launch {
            prefs.setRtspEnabled(false)
            ServiceRegistry.rtspService?.stopStreaming()
        }
    }

    fun toggleCamera() {
        viewModelScope.launch {
            val current = prefs.rtspCameraFacing.first()
            prefs.setRtspCameraFacing(if (current == "back") "front" else "back")
        }
    }

    fun saveConfig(resStr: String, fps: Int, bps: Int) {
        viewModelScope.launch {
            prefs.setRtspResolution(resStr)
            prefs.setRtspFps(fps)
            prefs.setRtspBitrate(bps)
        }
    }
}
