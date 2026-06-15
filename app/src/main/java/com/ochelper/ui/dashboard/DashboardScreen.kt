package com.ochelper.ui.dashboard

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

@Composable
fun DashboardScreen() {
    val context = LocalContext.current
    val vm: DashboardViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST")
            return DashboardViewModel(context) as T
        }
    })
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("OCHelper", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Text(state.localIp, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        // Service Cards Grid
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceCard(
                modifier = Modifier.weight(1f),
                title = "OC Node",
                status = if (state.ocNodeConnected) "已连接" else "未连接",
                connected = state.ocNodeConnected,
                detail = state.ocNodeState,
            )
            ServiceCard(
                modifier = Modifier.weight(1f),
                title = "MCP Server",
                status = if (state.mcpRunning) "运行中" else "已停止",
                connected = state.mcpRunning,
                detail = if (state.mcpRunning) ":${state.mcpPort}" else "",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ServiceCard(
                modifier = Modifier.weight(1f),
                title = "OC Gateway",
                status = if (state.gatewayConnected) "已连接" else "未连接",
                connected = state.gatewayConnected,
                detail = state.gatewayModel.take(20),
            )
            ServiceCard(
                modifier = Modifier.weight(1f),
                title = "RTSP 推流",
                status = if (state.rtspStreaming) "推流中" else "已停止",
                connected = state.rtspStreaming,
                detail = if (state.rtspStreaming) ":${state.rtspPort}" else "",
            )
        }

        // Device Info
        ElevatedCard {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("设备信息", style = MaterialTheme.typography.labelLarge)
                HorizontalDivider()
                InfoRow("IP 地址", state.localIp)
                InfoRow("Android 版本", state.androidVersion)
            }
        }
    }
}

@Composable
private fun ServiceCard(
    modifier: Modifier = Modifier,
    title: String,
    status: String,
    connected: Boolean,
    detail: String,
) {
    ElevatedCard(modifier = modifier) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(
                    imageVector = if (connected) Icons.Default.CheckCircle else Icons.Default.Cancel,
                    contentDescription = null,
                    tint = if (connected) Color(0xFF4CAF50) else Color(0xFFBDBDBD),
                    modifier = Modifier.size(12.dp)
                )
                Text(status, style = MaterialTheme.typography.bodySmall,
                    color = if (connected) Color(0xFF4CAF50) else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (detail.isNotEmpty()) {
                Text(detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1)
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row {
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(80.dp))
        Text(value, style = MaterialTheme.typography.bodySmall)
    }
}
