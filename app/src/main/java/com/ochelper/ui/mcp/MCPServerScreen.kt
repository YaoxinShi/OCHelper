package com.ochelper.ui.mcp

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ochelper.util.NetworkUtils

@Composable
fun MCPServerScreen() {
    val context = LocalContext.current
    val vm: MCPServerViewModel = viewModel(factory = object : androidx.lifecycle.ViewModelProvider.Factory {
        override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T {
            @Suppress("UNCHECKED_CAST") return MCPServerViewModel(context) as T
        }
    })

    val port by vm.port.collectAsState()
    val token by vm.token.collectAsState()
    val isRunning by vm.isRunning.collectAsState()
    val localIp = remember { NetworkUtils.getLocalIpAddress(context) }
    var portText by remember(port) { mutableStateOf(port.toString()) }

    val mcpUrl = "http://$localIp:$port/mcp"
    val configJson = """{"type":"http","url":"$mcpUrl","headers":{"Authorization":"Bearer $token"}}"""

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text("MCP Server", style = MaterialTheme.typography.titleLarge)

        // Status & Control
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row {
                    Text("状态: ${if (isRunning) "● 运行中" else "○ 已停止"}", style = MaterialTheme.typography.bodyMedium)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = portText, onValueChange = { portText = it },
                        label = { Text("端口") }, modifier = Modifier.width(100.dp), singleLine = true,
                    )
                    if (!isRunning) {
                        Button(onClick = { vm.startServer(portText.toIntOrNull() ?: 8765) }) { Text("启动") }
                    } else {
                        OutlinedButton(onClick = { vm.stopServer() }) { Text("停止") }
                    }
                }
            }
        }

        // Access Info
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("接入信息", style = MaterialTheme.typography.labelMedium)
                CopyableRow("MCP 端点", mcpUrl, context)
                CopyableRow("Bearer Token", token, context)
                OutlinedButton(onClick = { vm.regenerateToken() }, modifier = Modifier.fillMaxWidth()) {
                    Text("重新生成 Token")
                }
            }
        }

        // OpenClaw Config
        ElevatedCard {
            Column(Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("OpenClaw 配置示例", style = MaterialTheme.typography.labelMedium)
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Text(configJson, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(8.dp).fillMaxWidth())
                }
                OutlinedButton(
                    onClick = {
                        val clip = ClipData.newPlainText("MCP Config", configJson)
                        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
                        Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.ContentCopy, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("复制全部配置")
                }
            }
        }
    }
}

@Composable
private fun CopyableRow(label: String, value: String, context: Context) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodySmall, maxLines = 2)
        }
        IconButton(onClick = {
            val clip = ClipData.newPlainText(label, value)
            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
            Toast.makeText(context, "已复制", Toast.LENGTH_SHORT).show()
        }) {
            Icon(Icons.Default.ContentCopy, "Copy", Modifier.size(16.dp))
        }
    }
}
