package com.ochelper.ui.ocnode

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ochelper.ocnode.OCNodeState

@Composable
fun OCNodeScreen() {
    val context = LocalContext.current
    val vm: OCNodeViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return OCNodeViewModel(context) as T
        }
    })

    val nodeState by vm.nodeState.collectAsState()
    val savedUrl by vm.serverUrl.collectAsState()
    val savedToken by vm.authToken.collectAsState()

    var url by remember(savedUrl) { mutableStateOf(savedUrl) }
    var token by remember(savedToken) { mutableStateOf(savedToken) }
    var tokenVisible by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("OpenClaw Node", style = MaterialTheme.typography.titleLarge)

        // Status
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth()) {
                Text("连接状态", style = MaterialTheme.typography.labelMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    when (nodeState) {
                        is OCNodeState.Connected -> "● 已连接  (session: ${(nodeState as OCNodeState.Connected).sessionId.take(12)})"
                        is OCNodeState.Connecting -> "○ 连接中..."
                        is OCNodeState.Error -> "✗ 错误: ${(nodeState as OCNodeState.Error).message}"
                        else -> "○ 未连接"
                    },
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Config
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("服务器配置", style = MaterialTheme.typography.labelMedium)
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Gateway URL") },
                    placeholder = { Text("ws://192.168.1.1:9000") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Auth Token") },
                    modifier = Modifier.fillMaxWidth(), singleLine = true,
                    visualTransformation = if (tokenVisible)
                        androidx.compose.ui.text.input.VisualTransformation.None
                    else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { tokenVisible = !tokenVisible }) {
                            Text(if (tokenVisible) "隐藏" else "显示")
                        }
                    }
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.saveAndConnect(url, token) },
                        modifier = Modifier.weight(1f),
                        enabled = url.isNotEmpty(),
                    ) { Text("连接") }
                    OutlinedButton(
                        onClick = { vm.disconnect() },
                        modifier = Modifier.weight(1f),
                        enabled = nodeState is OCNodeState.Connected || nodeState is OCNodeState.Connecting,
                    ) { Text("断开") }
                }
            }
        }
    }
}
