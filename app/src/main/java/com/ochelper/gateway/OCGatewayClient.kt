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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID
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
                val statusOk = fetchStatus(cfg)
                if (statusOk) {
                    _connectionState.value = GatewayConnectionState.Connected(cfg.gatewayUrl)
                    // Keep alive loop — OpenClaw has no dedicated SSE events endpoint
                    while (currentCoroutineContext().isActive) {
                        delay(30_000L)
                        // Re-check health
                        if (!fetchStatus(cfg)) break
                    }
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
                .url("${cfg.gatewayUrl}/health")
                .get()
                .build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                // Also refresh model list
                try {
                    val modelsReq = Request.Builder()
                        .url("${cfg.gatewayUrl}/v1/models")
                        .header("Authorization", "Bearer ${cfg.apiKey}")
                        .get()
                        .build()
                    httpClient.newCall(modelsReq).execute().use { mr ->
                        if (mr.isSuccessful) {
                            val body = mr.body?.string() ?: return@use
                            val obj = json.parseToJsonElement(body).jsonObject
                            val firstModel = obj["data"]?.jsonArray?.firstOrNull()?.jsonObject
                            val modelId = firstModel?.get("id")?.jsonPrimitive?.content
                            if (modelId != null) {
                                _currentModel.value = ModelInfo(
                                    id = modelId,
                                    provider = firstModel?.get("owned_by")?.jsonPrimitive?.content ?: "openclaw"
                                )
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "fetchModels error: ${e.message}")
                }
                true
            }
        } catch (e: Exception) {
            Log.d(TAG, "fetchStatus error: ${e.message}")
            false
        }
    }

    /** Send a task message to OpenClaw using OpenAI-compatible /v1/chat/completions. */
    suspend fun sendTask(
        message: String,
        sessionId: String? = null,
        model: String? = null,
        onChunk: suspend (String) -> Unit,
    ): Result<String> {
        val cfg = config ?: return Result.failure(Exception("not connected"))

        val effectiveModel = model?.takeIf { it.isNotEmpty() }
            ?: cfg.defaultModel.takeIf { it.isNotEmpty() }
            ?: "openclaw"

        val body = buildJsonObject {
            put("model", effectiveModel)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("role", "user")
                    put("content", message)
                })
            })
            put("stream", true)
        }.toString().toRequestBody("application/json".toMediaType())

        val reqBuilder = Request.Builder()
            .url("${cfg.gatewayUrl}/v1/chat/completions")
            .header("Authorization", "Bearer ${cfg.apiKey}")
            .header("Accept", "text/event-stream")
            .post(body)
        if (!sessionId.isNullOrEmpty()) reqBuilder.header("X-Session-Id", sessionId)
        val request = reqBuilder.build()

        return withContext(Dispatchers.IO) {
            try {
                val result = StringBuilder()
                var newSessionId = sessionId ?: UUID.randomUUID().toString()

                httpClient.newCall(request).execute().use { resp ->
                    val rawBody = resp.body
                        ?: return@withContext Result.failure(Exception("empty response"))

                    if (!resp.isSuccessful) {
                        val errBody = rawBody.string()
                        val errMsg = extractErrorMessage(errBody) ?: "HTTP ${resp.code}"
                        return@withContext Result.failure(Exception(errMsg))
                    }

                    val source = rawBody.source()
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        if (!line.startsWith("data:")) continue
                        val data = line.removePrefix("data:").trim()
                        if (data == "[DONE]") break
                        try {
                            val obj = json.parseToJsonElement(data).jsonObject

                            // Inline error in SSE chunk
                            val errObj = obj["error"]?.jsonObject
                            if (errObj != null) {
                                val errMsg = errObj["message"]?.jsonPrimitive?.content ?: "unknown error"
                                return@withContext Result.failure(Exception(errMsg))
                            }

                            // OpenAI format: choices[0].delta.content
                            val delta = obj["choices"]?.jsonArray
                                ?.firstOrNull()?.jsonObject
                                ?.get("delta")?.jsonObject
                                ?.get("content")?.jsonPrimitive?.content

                            if (delta != null) {
                                result.append(delta)
                                onChunk(delta)
                            }

                            obj["id"]?.jsonPrimitive?.content?.let { newSessionId = it }
                        } catch (e: Exception) {
                            Log.w(TAG, "SSE chunk parse error: $data — ${e.message}")
                        }
                    }
                }
                Result.success(newSessionId)
            } catch (e: Exception) {
                Log.e(TAG, "sendTask failed", e)
                Result.failure(Exception(e.message ?: e.javaClass.simpleName, e))
            }
        }
    }

    private fun extractErrorMessage(body: String): String? = try {
        json.parseToJsonElement(body).jsonObject["error"]
            ?.jsonObject?.get("message")?.jsonPrimitive?.content
    } catch (_: Exception) { null }
}
