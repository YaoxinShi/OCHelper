package com.ochelper.ui.gateway

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ochelper.gateway.GatewayConnectionState

@Composable
fun GatewayScreen() {
    val context = LocalContext.current
    val vm: GatewayViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return GatewayViewModel(context) as T
        }
    })

    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("任务", "监控", "配置")

    Column(Modifier.fillMaxSize()) {
        Text("OC Gateway", style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { i, title ->
                Tab(selected = selectedTab == i, onClick = { selectedTab = i }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> TaskPanel(vm)
            1 -> MonitorPanel(vm)
            2 -> ConfigPanel(vm)
        }
    }
}

@Composable
private fun TaskPanel(vm: GatewayViewModel) {
    val messages by vm.chatMessages.collectAsState()
    val isSending by vm.isSending.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) listState.animateScrollToItem(messages.size - 1)
    }

    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
        ) {
            items(messages) { msg ->
                ChatBubble(msg)
            }
            if (isSending && (messages.isEmpty() || !messages.last().isStreaming)) {
                item { CircularProgressIndicator(modifier = Modifier.size(24.dp)) }
            }
        }

        HorizontalDivider()
        Row(
            Modifier.padding(8.dp).fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                placeholder = { Text("输入任务消息...") },
                modifier = Modifier.weight(1f),
                maxLines = 3,
                enabled = !isSending,
            )
            IconButton(
                onClick = {
                    if (inputText.isNotBlank()) {
                        vm.sendMessage(inputText.trim())
                        inputText = ""
                    }
                },
                enabled = inputText.isNotBlank() && !isSending,
            ) {
                Icon(Icons.Default.Send, "Send")
            }
        }
    }
}

@Composable
private fun ChatBubble(msg: ChatMessage) {
    val isUser = msg.role == "user"
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            color = if (isUser) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(
                topStart = if (isUser) 12.dp else 2.dp,
                topEnd = if (isUser) 2.dp else 12.dp,
                bottomStart = 12.dp, bottomEnd = 12.dp,
            ),
            modifier = Modifier.widthIn(max = 300.dp)
        ) {
            Text(
                text = if (msg.isStreaming && msg.content.isEmpty()) "▌" else msg.content + if (msg.isStreaming) "▌" else "",
                color = if (isUser) MaterialTheme.colorScheme.onPrimary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(10.dp),
            )
        }
    }
}

@Composable
private fun MonitorPanel(vm: GatewayViewModel) {
    val connectionState by vm.connectionState.collectAsState()
    val currentModel by vm.currentModel.collectAsState()
    val toolHistory by vm.toolCallHistory.collectAsState()

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Connection status
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("连接状态", style = MaterialTheme.typography.labelMedium)
                Text(
                    when (connectionState) {
                        is GatewayConnectionState.Connected -> "● 已连接  ${(connectionState as GatewayConnectionState.Connected).url}"
                        is GatewayConnectionState.Connecting -> "○ 连接中..."
                        is GatewayConnectionState.Error -> "✗ 错误: ${(connectionState as GatewayConnectionState.Error).message}"
                        else -> "○ 未连接"
                    },
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }

        // Current model
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("当前模型", style = MaterialTheme.typography.labelMedium)
                if (currentModel != null) {
                    Text(currentModel!!.id, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
                    Text("Provider: ${currentModel!!.provider}", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("未知", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        // Tool call history
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("工具调用历史 (${toolHistory.size})", style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f))
                    TextButton(onClick = { vm.clearToolHistory() }) { Text("清除") }
                }
                if (toolHistory.isEmpty()) {
                    Text("暂无记录", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                toolHistory.take(20).forEach { rec ->
                    ToolCallCard(rec)
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ToolCallCard(rec: ToolCallRecord) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Text(
                text = if (rec.ok) "✓" else "✗",
                color = if (rec.ok) Color(0xFF4CAF50) else Color(0xFFF44336),
                modifier = Modifier.width(16.dp),
            )
            Text(rec.toolName, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            if (rec.durationMs > 0) {
                Text("${rec.durationMs}ms", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.bodySmall)
            }
        }
        if (expanded && rec.outputJson.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = MaterialTheme.shapes.small) {
                Text(rec.outputJson, style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(6.dp).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun ConfigPanel(vm: GatewayViewModel) {
    val savedUrl by vm.gatewayUrl.collectAsState()
    val savedKey by vm.apiKey.collectAsState()
    val savedModel by vm.defaultModel.collectAsState()
    val connectionState by vm.connectionState.collectAsState()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var key by remember(savedKey) { mutableStateOf(savedKey) }
    var model by remember(savedModel) { mutableStateOf(savedModel) }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Gateway 配置", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Gateway URL") },
                    placeholder = { Text("http://192.168.1.1:9000") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = key, onValueChange = { key = it },
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = model, onValueChange = { model = it },
                    label = { Text("默认模型 (可选)") },
                    placeholder = { Text("留空使用 Gateway 默认") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.saveAndConnect(url, key, model) },
                        modifier = Modifier.weight(1f),
                        enabled = url.isNotEmpty(),
                    ) { Text("保存并连接") }
                    OutlinedButton(
                        onClick = { vm.disconnect() },
                        modifier = Modifier.weight(1f),
                        enabled = connectionState !is GatewayConnectionState.Disconnected,
                    ) { Text("断开") }
                }
            }
        }
    }
}
