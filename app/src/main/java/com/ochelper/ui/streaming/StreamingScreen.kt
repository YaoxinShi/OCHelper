package com.ochelper.ui.streaming

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.FlipCameraAndroid
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ochelper.service.ServiceRegistry
import com.ochelper.util.NetworkUtils

@Composable
fun StreamingScreen() {
    val context = LocalContext.current
    val vm: StreamingViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return StreamingViewModel(context) as T
        }
    })

    val isStreaming by vm.isStreaming.collectAsState()
    val port by vm.port.collectAsState()
    val resolution by vm.resolution.collectAsState()
    val fps by vm.fps.collectAsState()
    val bitrate by vm.bitrate.collectAsState()
    val cameraFacing by vm.cameraFacing.collectAsState()
    val localIp = remember { NetworkUtils.getLocalIpAddress(context) }
    val rtspUrl = "rtsp://$localIp:$port/live"

    val resolutionOptions = listOf("1920x1080", "1280x720", "854x480", "640x360")
    val fpsOptions = listOf(15, 24, 30, 60)

    var selectedRes by remember(resolution) { mutableStateOf(resolution) }
    var selectedFps by remember(fps) { mutableStateOf(fps) }
    var bitrateText by remember(bitrate) { mutableStateOf((bitrate / 1000).toString()) }

    // Preview surface is hoisted so it can be re-attached to the service once it exists
    // (the surface usually becomes available before streaming starts).
    var previewSurface by remember { mutableStateOf<Surface?>(null) }
    LaunchedEffect(previewSurface, isStreaming) {
        ServiceRegistry.rtspService?.setPreviewSurface(previewSurface)
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("RTSP 视频流", style = MaterialTheme.typography.titleLarge)

        // Camera preview
        CameraPreview(
            onSurfaceChanged = { previewSurface = it },
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
        )

        // Status + RTSP URL
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isStreaming) "● 推流中  ($resolution  ${fps}fps)" else "○ 已停止",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(rtspUrl, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val clip = ClipData.newPlainText("RTSP URL", rtspUrl)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    }) {
                        Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!isStreaming) {
                        Button(onClick = { vm.startStreaming() }, modifier = Modifier.weight(1f)) { Text("开始推流") }
                    } else {
                        OutlinedButton(onClick = { vm.stopStreaming() }, modifier = Modifier.weight(1f)) { Text("停止推流") }
                    }
                    OutlinedButton(onClick = { vm.toggleCamera() }) {
                        Icon(Icons.Default.FlipCameraAndroid, null, Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text(if (cameraFacing == "back") "后置" else "前置")
                    }
                }
            }
        }

        // Stream config
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("流配置", style = MaterialTheme.typography.labelMedium)
                Text("分辨率", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    resolutionOptions.forEach { res ->
                        FilterChip(
                            selected = selectedRes == res,
                            onClick = { selectedRes = res },
                            label = { Text(res, style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                Text("帧率", style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    fpsOptions.forEach { f ->
                        FilterChip(
                            selected = selectedFps == f,
                            onClick = { selectedFps = f },
                            label = { Text("${f}fps", style = MaterialTheme.typography.bodySmall) },
                        )
                    }
                }
                OutlinedTextField(
                    value = bitrateText, onValueChange = { bitrateText = it },
                    label = { Text("码率 (kbps)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = { vm.saveConfig(selectedRes, selectedFps, (bitrateText.toIntOrNull() ?: 2000) * 1000) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("保存配置") }
            }
        }
    }
}

/**
 * Rectangular camera preview area. Provides its [Surface] to the running RTSP service so
 * the camera capture session can mirror the streamed frames on screen.
 */
@Composable
private fun CameraPreview(
    onSurfaceChanged: (Surface?) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.background(Color.Black),
    ) {
        AndroidView(
            factory = { ctx ->
                TextureView(ctx).apply {
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(st: SurfaceTexture, width: Int, height: Int) {
                            st.setDefaultBufferSize(1280, 720)
                            onSurfaceChanged(Surface(st))
                        }

                        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, width: Int, height: Int) {}

                        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean {
                            onSurfaceChanged(null)
                            return true
                        }

                        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
                    }
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
