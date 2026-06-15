package com.ochelper.ocnode

import android.os.Build
import android.util.Log
import com.ochelper.capability.CapabilityManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

sealed class OCNodeState {
    object Disconnected : OCNodeState()
    object Connecting : OCNodeState()
    data class Connected(val serverUrl: String, val sessionId: String) : OCNodeState()
    data class Error(val message: String) : OCNodeState()
}

data class OCNodeConfig(
    val serverUrl: String,       // ws://host:port
    val authToken: String,
    val nodeId: String,
    val autoReconnect: Boolean = true,
    val reconnectIntervalSec: Int = 5,
)

class OCNodeClient(
    private val capabilityManager: CapabilityManager,
) {
    private val TAG = "OCNodeClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()

    private val _state = MutableStateFlow<OCNodeState>(OCNodeState.Disconnected)
    val state: StateFlow<OCNodeState> = _state.asStateFlow()

    private var connectJob: Job? = null
    private var socket: WebSocket? = null
    private var config: OCNodeConfig? = null

    private val client = OkHttpClient.Builder()
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    fun connect(cfg: OCNodeConfig) {
        config = cfg
        connectJob?.cancel()
        connectJob = scope.launch { runLoop(cfg) }
    }

    fun disconnect() {
        connectJob?.cancel()
        connectJob = null
        socket?.close(1000, "user disconnect")
        socket = null
        _state.value = OCNodeState.Disconnected
    }

    private suspend fun runLoop(cfg: OCNodeConfig) {
        while (currentCoroutineContext().isActive) {
            _state.value = OCNodeState.Connecting
            Log.d(TAG, "Connecting to ${cfg.serverUrl}")
            try {
                val closeDeferred = CompletableDeferred<Unit>()
                val request = Request.Builder()
                    .url(cfg.serverUrl)
                    .header("User-Agent", buildUserAgent())
                    .build()

                socket = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        scope.launch { doHandshake(ws, cfg, closeDeferred) }
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        scope.launch { handleMessage(text) }
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "WebSocket failure: ${t.message}")
                        closeDeferred.complete(Unit)
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        closeDeferred.complete(Unit)
                    }
                })
                closeDeferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            }

            socket = null
            pending.forEach { (_, d) -> d.completeExceptionally(Exception("disconnected")) }
            pending.clear()

            if (_state.value !is OCNodeState.Error) {
                _state.value = OCNodeState.Disconnected
            }

            if (!cfg.autoReconnect) break
            Log.d(TAG, "Reconnecting in ${cfg.reconnectIntervalSec}s")
            delay(cfg.reconnectIntervalSec * 1000L)
        }
    }

    private suspend fun doHandshake(ws: WebSocket, cfg: OCNodeConfig, closeDeferred: CompletableDeferred<Unit>) {
        try {
            // Send connect request (OpenClaw Gateway protocol v3)
            val capabilities = capabilityManager.enabledCapabilities().map { it.id }
            val connectParams = buildJsonObject {
                put("role", "node")
                put("token", cfg.authToken)
                put("caps", buildJsonArray { capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("commands", buildJsonArray { capabilities.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
                put("client", buildJsonObject {
                    put("id", cfg.nodeId)
                    put("displayName", "OCHelper Android")
                    put("version", "1.0.0")
                    put("platform", "android")
                    put("mode", "node")
                    put("deviceFamily", "Android")
                    put("modelIdentifier", "${Build.MANUFACTURER} ${Build.MODEL}")
                })
                put("userAgent", buildUserAgent())
            }
            val connectId = UUID.randomUUID().toString()
            val connectFrame = buildJsonObject {
                put("type", "req")
                put("id", connectId)
                put("method", "connect")
                put("params", connectParams)
            }
            sendFrame(ws, connectFrame)

            // Wait for connect ACK
            val deferred = CompletableDeferred<JsonObject>()
            pending[connectId] = deferred
            val ack = withTimeout(12_000L) { deferred.await() }

            val ok = ack["ok"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
            if (!ok) {
                val errMsg = ack["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content ?: "connect rejected"
                _state.value = OCNodeState.Error(errMsg)
                closeDeferred.complete(Unit)
                return
            }

            val sessionId = ack["result"]?.jsonObject?.get("sessionId")?.jsonPrimitive?.content ?: connectId
            _state.value = OCNodeState.Connected(cfg.serverUrl, sessionId)
            Log.i(TAG, "Connected. sessionId=$sessionId caps=$capabilities")

        } catch (e: Exception) {
            Log.w(TAG, "Handshake failed: ${e.message}")
            _state.value = OCNodeState.Error(e.message ?: "handshake failed")
            closeDeferred.complete(Unit)
        }
    }

    private suspend fun handleMessage(text: String) {
        val frame = try {
            json.parseToJsonElement(text).jsonObject
        } catch (_: Exception) { return }

        when (frame["type"]?.jsonPrimitive?.content) {
            "res" -> {
                // Response to a pending request
                val id = frame["id"]?.jsonPrimitive?.content ?: return
                val deferred = pending.remove(id) ?: return
                deferred.complete(frame)
            }
            "req" -> {
                // Incoming invoke request from OpenClaw
                val id = frame["id"]?.jsonPrimitive?.content ?: return
                val method = frame["method"]?.jsonPrimitive?.content ?: return
                scope.launch { handleInvoke(id, method, frame["params"]?.jsonObject ?: buildJsonObject {}) }
            }
            "event" -> {
                val event = frame["event"]?.jsonPrimitive?.content
                Log.d(TAG, "Gateway event: $event")
            }
        }
    }

    private suspend fun handleInvoke(id: String, command: String, params: JsonObject) {
        val ws = socket ?: return
        Log.d(TAG, "invoke: $command params=$params")
        val result = capabilityManager.execute(command, params)
        val hasError = result["error"] != null
        val responseFrame = buildJsonObject {
            put("type", "res")
            put("id", id)
            put("ok", !hasError)
            if (!hasError) put("result", result) else put("error", buildJsonObject {
                put("code", "INVOKE_FAILED")
                put("message", result["error"]?.jsonPrimitive?.content ?: "failed")
            })
        }
        sendFrame(ws, responseFrame)
    }

    private suspend fun sendFrame(ws: WebSocket, frame: JsonObject) {
        writeLock.withLock { ws.send(frame.toString()) }
    }

    private fun buildUserAgent(): String =
        "OCHelper/1.0.0 (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"
}
