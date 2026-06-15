package com.ochelper.ui.gateway

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ochelper.data.AppPreferences
import com.ochelper.gateway.GatewayConnectionState
import com.ochelper.gateway.GatewayEvent
import com.ochelper.gateway.ModelInfo
import com.ochelper.gateway.OCGatewayConfig
import com.ochelper.service.OCGatewayService
import com.ochelper.service.ServiceRegistry
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class ChatMessage(
    val role: String,  // "user" | "assistant"
    val content: String,
    val isStreaming: Boolean = false,
)

data class ToolCallRecord(
    val toolName: String,
    val inputJson: String,
    val outputJson: String = "",
    val durationMs: Long = 0,
    val ok: Boolean = true,
)

class GatewayViewModel(private val context: Context) : ViewModel() {
    private val prefs = AppPreferences(context)

    val gatewayUrl = prefs.gatewayUrl.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val apiKey = prefs.gatewayApiKey.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val defaultModel = prefs.gatewayDefaultModel.stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _chatMessages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

    private val _toolCallHistory = MutableStateFlow<List<ToolCallRecord>>(emptyList())
    val toolCallHistory: StateFlow<List<ToolCallRecord>> = _toolCallHistory.asStateFlow()

    private val _isSending = MutableStateFlow(false)
    val isSending: StateFlow<Boolean> = _isSending.asStateFlow()

    private var currentSessionId: String? = null

    init {
        viewModelScope.launch {
            while (true) {
                val client = ServiceRegistry.gatewayClient
                _connectionState.value = client?.connectionState?.value ?: GatewayConnectionState.Disconnected
                _currentModel.value = client?.currentModel?.value
                kotlinx.coroutines.delay(1000)
            }
        }
        // Subscribe to gateway events
        viewModelScope.launch {
            while (true) {
                val client = ServiceRegistry.gatewayClient
                client?.events?.collect { event ->
                    when (event) {
                        is GatewayEvent.ModelChanged -> _currentModel.value = event.model
                        is GatewayEvent.ToolCallEnd -> {
                            val rec = ToolCallRecord(
                                toolName = event.toolName,
                                inputJson = "",
                                outputJson = event.outputJson,
                                durationMs = event.durationMs,
                                ok = event.ok,
                            )
                            _toolCallHistory.value = (listOf(rec) + _toolCallHistory.value).take(100)
                        }
                        else -> {}
                    }
                }
                kotlinx.coroutines.delay(2000)
            }
        }
    }

    fun saveAndConnect(url: String, key: String, model: String) {
        viewModelScope.launch {
            prefs.setGatewayUrl(url)
            prefs.setGatewayApiKey(key)
            prefs.setGatewayDefaultModel(model)
            prefs.setGatewayEnabled(true)
            OCGatewayService.start(context)
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            prefs.setGatewayEnabled(false)
            OCGatewayService.stop(context)
        }
    }

    fun sendMessage(message: String) {
        if (_isSending.value) return
        val client = ServiceRegistry.gatewayClient ?: return

        _chatMessages.value = _chatMessages.value + ChatMessage("user", message)
        val streamingIdx = _chatMessages.value.size
        _chatMessages.value = _chatMessages.value + ChatMessage("assistant", "", isStreaming = true)
        _isSending.value = true

        viewModelScope.launch {
            val sb = StringBuilder()
            val result = client.sendTask(
                message = message,
                sessionId = currentSessionId,
                onChunk = { delta ->
                    sb.append(delta)
                    val updated = _chatMessages.value.toMutableList()
                    if (updated.size > streamingIdx) {
                        updated[streamingIdx] = ChatMessage("assistant", sb.toString(), isStreaming = true)
                        _chatMessages.value = updated
                    }
                }
            )
            result.onSuccess { sid ->
                currentSessionId = sid
                val updated = _chatMessages.value.toMutableList()
                if (updated.size > streamingIdx) {
                    updated[streamingIdx] = ChatMessage("assistant", sb.toString(), isStreaming = false)
                    _chatMessages.value = updated
                }
            }.onFailure { e ->
                val updated = _chatMessages.value.toMutableList()
                if (updated.size > streamingIdx) {
                    updated[streamingIdx] = ChatMessage("assistant", "错误: ${e.message}", isStreaming = false)
                    _chatMessages.value = updated
                }
            }
            _isSending.value = false
        }
    }

    fun newSession() { currentSessionId = null }
    fun clearToolHistory() { _toolCallHistory.value = emptyList() }
}
