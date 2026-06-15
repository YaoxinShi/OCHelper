package com.ochelper.ui.dashboard

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ochelper.ocnode.OCNodeState
import com.ochelper.service.ServiceRegistry
import com.ochelper.util.NetworkUtils
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class DashboardUiState(
    val localIp: String = "",
    val ocNodeState: String = "Disconnected",
    val ocNodeConnected: Boolean = false,
    val mcpRunning: Boolean = false,
    val mcpPort: Int = 8765,
    val gatewayConnected: Boolean = false,
    val gatewayModel: String = "",
    val rtspStreaming: Boolean = false,
    val rtspPort: Int = 8554,
    val batteryLevel: Int = 0,
    val androidVersion: String = android.os.Build.VERSION.RELEASE,
    val recentEvents: List<String> = emptyList(),
)

class DashboardViewModel(context: Context) : ViewModel() {
    private val appContext = context.applicationContext
    private val _state = MutableStateFlow(DashboardUiState())
    val state: StateFlow<DashboardUiState> = _state.asStateFlow()

    init {
        _state.value = _state.value.copy(localIp = NetworkUtils.getLocalIpAddress(appContext))
        viewModelScope.launch {
            while (true) {
                refresh()
                delay(2000)
            }
        }
    }

    private fun refresh() {
        val nodeClient = ServiceRegistry.ocNodeClient
        val nodeState = nodeClient?.state?.value
        val gatewayClient = ServiceRegistry.gatewayClient
        val rtspSvc = ServiceRegistry.rtspService

        _state.value = _state.value.copy(
            ocNodeConnected = nodeState is OCNodeState.Connected,
            ocNodeState = nodeState?.let {
                when (it) {
                    is OCNodeState.Connected -> "已连接 (${it.sessionId.take(8)})"
                    is OCNodeState.Connecting -> "连接中..."
                    is OCNodeState.Error -> "错误: ${it.message}"
                    else -> "未连接"
                }
            } ?: "未连接",
            mcpRunning = ServiceRegistry.mcpServer != null,
            gatewayConnected = gatewayClient?.connectionState?.value
                .let { it?.toString()?.contains("Connected") == true },
            gatewayModel = gatewayClient?.currentModel?.value?.id ?: "",
            rtspStreaming = rtspSvc?.isStreaming?.value == true,
        )
    }
}
