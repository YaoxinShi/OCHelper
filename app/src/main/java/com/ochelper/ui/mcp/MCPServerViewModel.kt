package com.ochelper.ui.mcp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ochelper.data.AppPreferences
import com.ochelper.service.MCPServerService
import com.ochelper.service.ServiceRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

class MCPServerViewModel(private val context: Context) : ViewModel() {
    private val prefs = AppPreferences(context)
    val port = prefs.mcpPort.stateIn(viewModelScope, SharingStarted.Eagerly, 8765)
    val token = prefs.mcpToken.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val isRunning: StateFlow<Boolean> = flow {
        while (true) { emit(ServiceRegistry.mcpServer != null); kotlinx.coroutines.delay(500) }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun startServer(port: Int) {
        viewModelScope.launch {
            prefs.setMcpPort(port)
            prefs.setMcpEnabled(true)
            MCPServerService.start(context)
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            prefs.setMcpEnabled(false)
            MCPServerService.stop(context)
        }
    }

    fun regenerateToken() {
        viewModelScope.launch { prefs.setMcpToken(AppPreferences.generateToken()) }
    }
}
