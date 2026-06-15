package com.ochelper.gateway

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.util.concurrent.TimeUnit

data class OCGatewayConfig(
    val gatewayUrl: String,       // http://host:port
    val apiKey: String,
    val defaultModel: String = "",
)

sealed class GatewayConnectionState {
    object Disconnected : GatewayConnectionState()
    object Connecting : GatewayConnectionState()
    data class Connected(val url: String) : GatewayConnectionState()
    data class Error(val message: String) : GatewayConnectionState()
}

data class ModelInfo(
    val id: String,
    val provider: String,
    val contextWindow: Int = 0,
)

data class GatewayMetrics(
    val activeSessions: Int = 0,
    val queuedTasks: Int = 0,
    val totalTokensToday: Long = 0L,
    val estimatedCostUsd: Double = 0.0,
    val avgLatencyMs: Long = 0L,
)

sealed class GatewayEvent {
    data class TextDelta(val sessionId: String, val delta: String) : GatewayEvent()
    data class ToolCallStart(val id: String, val toolName: String, val inputJson: String) : GatewayEvent()
    data class ToolCallEnd(val id: String, val toolName: String, val outputJson: String, val durationMs: Long, val ok: Boolean) : GatewayEvent()
    data class ModelChanged(val model: ModelInfo) : GatewayEvent()
    data class TaskComplete(val sessionId: String, val totalTokens: Int) : GatewayEvent()
    data class GatewayError(val code: Int, val message: String) : GatewayEvent()
}

class OCGatewayClient {
    private val TAG = "OCGatewayClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val _connectionState = MutableStateFlow<GatewayConnectionState>(GatewayConnectionState.Disconnected)
    val connectionState: StateFlow<GatewayConnectionState> = _connectionState.asStateFlow()

    private val _currentModel = MutableStateFlow<ModelInfo?>(null)
    val currentModel: StateFlow<ModelInfo?> = _currentModel.asStateFlow()

    private val _metrics = MutableStateFlow(GatewayMetrics())
    val metrics: StateFlow<GatewayMetrics> = _metrics.asStateFlow()

    private val _events = MutableSharedFlow<GatewayEvent>(extraBufferCapacity = 100)
    val events: SharedFlow<GatewayEvent> = _events.asSharedFlow()

    private var config: OCGatewayConfig? = null
    private var sseJob: Job? = null
    private var reconnectJob: Job? = null

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun connect(cfg: OCGatewayConfig) {
        config = cfg
        reconnectJob?.cancel()
        reconnectJob = scope.launch { connectLoop(cfg) }
    }

    fun disconnect() {
        reconnectJob?.cancel()
        sseJob?.cancel()
        sseJob = null
        config = null
        _connectionState.value = GatewayConnectionState.Disconnected
    }

    private suspend fun connectLoop(cfg: OCGatewayConfig) {
        var backoffSec = 1L
        while (currentCoroutineContext().isActive) {
            _connectionState.value = GatewayConnectionState.Connecting
            try {
                // First do a status probe
                val statusOk = fetchStatus(cfg)
                if (statusOk) {
                    _connectionState.value = GatewayConnectionState.Connected(cfg.gatewayUrl)
                    subscribeToEvents(cfg)
                    backoffSec = 1L
                }
            } catch (e: Exception) {
                Log.w(TAG, "Gateway connect failed: ${e.message}")
                _connectionState.value = GatewayConnectionState.Error(e.message ?: "connection failed")
            }
            delay(backoffSec * 1000L)
            backoffSec = (backoffSec * 2).coerceAtMost(60L)
        }
    }

    private fun fetchStatus(cfg: OCGatewayConfig): Boolean {
        return try {
            val req = Request.Builder()
                .url("${cfg.gatewayUrl}/api/v1/status")
                .header("Authorization", "Bearer ${cfg.apiKey}")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val body = resp.body?.string() ?: return false
                val obj = json.parseToJsonElement(body).jsonObject
                val modelId = obj["model"]?.jsonObject?.get("id")?.jsonPrimitive?.content
                    ?: obj["current_model"]?.jsonPrimitive?.content
                if (modelId != null) {
                    _currentModel.value = ModelInfo(
                        id = modelId,
                        provider = obj["model"]?.jsonObject?.get("provider")?.jsonPrimitive?.content ?: "unknown"
                    )
                }
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchStatus error: ${e.message}")
            false
        }
    }

    private suspend fun subscribeToEvents(cfg: OCGatewayConfig) {
        val request = Request.Builder()
            .url("${cfg.gatewayUrl}/api/v1/events")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .get()
            .build()

        sseJob?.cancel()
        // Read SSE stream line-by-line
        var currentEventType: String? = null
        try {
            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    _connectionState.value = GatewayConnectionState.Error("HTTP ${resp.code}")
                    return
                }
                val source = resp.body?.source() ?: return
                while (currentCoroutineContext().isActive) {
                    val line = source.readUtf8Line() ?: break
                    when {
                        line.startsWith("event:") -> currentEventType = line.removePrefix("event:").trim()
                        line.startsWith("data:") -> {
                            val data = line.removePrefix("data:").trim()
                            if (data.isNotEmpty() && data != "[DONE]") handleSseEvent(currentEventType, data)
                            if (line.isEmpty()) currentEventType = null
                        }
                        line.isEmpty() -> currentEventType = null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "SSE stream error: ${e.message}")
            _connectionState.value = GatewayConnectionState.Error(e.message ?: "SSE disconnected")
        }
    }

    private fun handleSseEvent(type: String?, data: String) {
        try {
            val obj = json.parseToJsonElement(data).jsonObject
            val event = when (type) {
                "model_changed" -> {
                    val modelId = obj["id"]?.jsonPrimitive?.content ?: return
                    val model = ModelInfo(
                        id = modelId,
                        provider = obj["provider"]?.jsonPrimitive?.content ?: "unknown"
                    )
                    _currentModel.value = model
                    GatewayEvent.ModelChanged(model)
                }
                "tool_call_start" -> GatewayEvent.ToolCallStart(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    toolName = obj["tool"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "",
                    inputJson = obj["input"]?.toString() ?: "{}"
                )
                "tool_call_end" -> GatewayEvent.ToolCallEnd(
                    id = obj["id"]?.jsonPrimitive?.content ?: "",
                    toolName = obj["tool"]?.jsonPrimitive?.content ?: obj["name"]?.jsonPrimitive?.content ?: "",
                    outputJson = obj["output"]?.toString() ?: "{}",
                    durationMs = obj["duration_ms"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L,
                    ok = obj["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
                )
                "text_delta" -> GatewayEvent.TextDelta(
                    sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
                    delta = obj["delta"]?.jsonPrimitive?.content ?: ""
                )
                "task_complete" -> GatewayEvent.TaskComplete(
                    sessionId = obj["session_id"]?.jsonPrimitive?.content ?: "",
                    totalTokens = obj["total_tokens"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                )
                else -> return
            }
            scope.launch { _events.emit(event) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse SSE event: $data")
        }
    }

    /** Send a task message to OpenClaw and stream the response via events. */
    suspend fun sendTask(
        message: String,
        sessionId: String? = null,
        model: String? = null,
        onChunk: suspend (String) -> Unit,
    ): Result<String> {
        val cfg = config ?: return Result.failure(Exception("not connected"))

        val body = buildJsonObject {
            put("message", message)
            if (sessionId != null) put("session_id", sessionId)
            val effectiveModel = model ?: cfg.defaultModel
            if (effectiveModel.isNotEmpty()) put("model", effectiveModel)
        }.toString().toRequestBody("application/json".toMediaType())

        val request = Request.Builder()
            .url("${cfg.gatewayUrl}/api/v1/chat")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body)
            .build()

        return try {
            val result = StringBuilder()
            var newSessionId = sessionId ?: ""

            httpClient.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    return Result.failure(Exception("HTTP ${resp.code}: ${resp.message}"))
                }
                val source = resp.body?.source() ?: return Result.failure(Exception("empty response"))
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    if (line.startsWith("data: ")) {
                        val data = line.removePrefix("data: ").trim()
                        if (data == "[DONE]") break
                        try {
                            val obj = json.parseToJsonElement(data).jsonObject
                            val delta = obj["delta"]?.jsonPrimitive?.content
                                ?: obj["content"]?.jsonPrimitive?.content
                            if (delta != null) {
                                result.append(delta)
                                onChunk(delta)
                            }
                            obj["session_id"]?.jsonPrimitive?.content?.let { newSessionId = it }
                        } catch (_: Exception) {}
                    }
                }
            }
            Result.success(newSessionId.ifEmpty { result.toString() })
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
