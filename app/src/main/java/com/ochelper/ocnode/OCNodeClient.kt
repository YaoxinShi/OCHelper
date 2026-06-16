package com.ochelper.ocnode

import android.content.Context
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.Locale
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
    context: Context,
) {
    private val TAG = "OCNodeClient"
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val writeLock = Mutex()
    private val pending = ConcurrentHashMap<String, CompletableDeferred<JsonObject>>()
    private val identityStore = DeviceIdentityStore(context.applicationContext)

    // Carries the gateway's connect.challenge nonce and server timestamp.
    private data class ConnectChallenge(val nonce: String, val serverTs: Long?)

    // Completed when the gateway sends its connect.challenge on a new connection.
    @Volatile private var connectNonceDeferred = CompletableDeferred<ConnectChallenge>()

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
            connectNonceDeferred = CompletableDeferred()
            var ws: WebSocket? = null
            try {
                val closeDeferred = CompletableDeferred<Unit>()
                val requestBuilder = Request.Builder()
                    .url(cfg.serverUrl)
                    .header("User-Agent", buildUserAgent())
                val request = requestBuilder.build()

                ws = client.newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(ws: WebSocket, response: Response) {
                        Log.d(TAG, "WS onOpen (HTTP ${response.code})")
                        scope.launch { doHandshake(ws, cfg, closeDeferred) }
                    }
                    override fun onMessage(ws: WebSocket, text: String) {
                        scope.launch { handleMessage(text) }
                    }
                    override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                        Log.w(TAG, "WS onClosing code=$code reason=$reason")
                    }
                    override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                        Log.w(TAG, "WebSocket failure: ${t.message} (HTTP ${response?.code})")
                        closeDeferred.complete(Unit)
                    }
                    override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                        Log.w(TAG, "WS onClosed code=$code reason=$reason")
                        closeDeferred.complete(Unit)
                    }
                })
                socket = ws
                closeDeferred.await()
            } catch (e: Exception) {
                Log.w(TAG, "Connection error: ${e.message}")
            } finally {
                // Ensure this iteration's socket is closed (e.g. on cancellation)
                // so it doesn't linger and flap until the ping timeout.
                ws?.cancel()
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
            // OpenClaw Gateway protocol v4: the gateway sends a `connect.challenge`
            // event with a nonce immediately after the socket opens. We must wait for
            // that nonce, sign it with our Ed25519 device key, and include the signed
            // `device` block in the connect request.
            Log.d(TAG, "Handshake: waiting for connect.challenge nonce")
            val challenge = withTimeout(10_000L) { connectNonceDeferred.await() }
            val connectNonce = challenge.nonce
            Log.d(TAG, "Handshake: got nonce, sending connect")

            val identity = identityStore.loadOrCreate()
            val enabledIds = capabilityManager.enabledCapabilities().map { it.id }
            // Advertise the gateway's canonical capability/command vocabulary.
            // Internal ids (e.g. camera.take_photo) are not recognized by the
            // gateway allowlist and would be dropped, leaving the node un-invokable.
            val caps = OCNodeCommandMap.caps(enabledIds)
            val commands = OCNodeCommandMap.commands(enabledIds)
            val role = "node"
            val scopes = emptyList<String>()
            val clientId = "openclaw-android"
            val clientMode = "node"
            val platform = "android"
            val deviceFamily = "Android"
            // Use the server-provided timestamp from the challenge so a skewed
            // device clock cannot push signedAt outside the gateway freshness window.
            val signedAtMs = challenge.serverTs ?: System.currentTimeMillis()

            val signaturePayload = DeviceAuthPayload.buildV3(
                deviceId = identity.deviceId,
                clientId = clientId,
                clientMode = clientMode,
                role = role,
                scopes = scopes,
                signedAtMs = signedAtMs,
                token = cfg.authToken.takeIf { it.isNotEmpty() },
                nonce = connectNonce,
                platform = platform,
                deviceFamily = deviceFamily,
            )
            val signature = identityStore.signPayload(signaturePayload, identity)
            val publicKey = identityStore.publicKeyBase64Url(identity)

            val connectParams = buildJsonObject {
                put("minProtocol", 3)
                put("maxProtocol", 4)
                put("client", buildJsonObject {
                    put("id", clientId)
                    put("displayName", "OCHelper Android")
                    put("version", "1.0.0")
                    put("platform", platform)
                    put("mode", clientMode)
                    put("instanceId", cfg.nodeId)
                    put("deviceFamily", deviceFamily)
                    put("modelIdentifier", "${Build.MANUFACTURER} ${Build.MODEL}")
                })
                put("caps", buildJsonArray { caps.forEach { add(JsonPrimitive(it)) } })
                put("commands", buildJsonArray { commands.forEach { add(JsonPrimitive(it)) } })
                put("role", role)
                if (cfg.authToken.isNotEmpty()) {
                    put("auth", buildJsonObject {
                        put("token", cfg.authToken)
                    })
                }
                if (!signature.isNullOrBlank() && !publicKey.isNullOrBlank()) {
                    put("device", buildJsonObject {
                        put("id", identity.deviceId)
                        put("publicKey", publicKey)
                        put("signature", signature)
                        put("signedAt", signedAtMs)
                        put("nonce", connectNonce)
                    })
                }
                put("locale", Locale.getDefault().toLanguageTag())
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

            val sessionId = ack["payload"]?.jsonObject?.get("server")?.jsonObject?.get("connId")?.jsonPrimitive?.content ?: connectId
            _state.value = OCNodeState.Connected(cfg.serverUrl, sessionId)
            Log.i(TAG, "Connected. sessionId=$sessionId caps=$caps commands=$commands")

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
                // Some gateways may deliver invokes as a request envelope instead
                // of a node.invoke.request event. Translate to the same handler.
                val method = frame["method"]?.jsonPrimitive?.content
                if (method == "node.invoke.request") {
                    val payload = frame["params"]?.jsonObject ?: frame["payload"]?.jsonObject
                    if (payload != null) scope.launch { handleInvoke(payload) }
                } else {
                    Log.d(TAG, "Gateway request: $method")
                }
            }
            "event" -> {
                val event = frame["event"]?.jsonPrimitive?.content
                when (event) {
                    "connect.challenge" -> {
                        val payload = frame["payload"]?.jsonObject
                        val nonce = payload?.get("nonce")?.jsonPrimitive?.content
                        val serverTs = payload?.get("ts")?.jsonPrimitive?.content?.toLongOrNull()
                        Log.d(TAG, "Received connect.challenge nonce=${nonce?.take(8)}... ts=$serverTs")
                        if (!nonce.isNullOrBlank() && !connectNonceDeferred.isCompleted) {
                            connectNonceDeferred.complete(ConnectChallenge(nonce, serverTs))
                        }
                    }
                    "node.invoke.request" -> {
                        val payload = frame["payload"]?.jsonObject
                        if (payload != null) scope.launch { handleInvoke(payload) }
                    }
                    else -> Log.d(TAG, "Gateway event: $event")
                }
            }
        }
    }

    private suspend fun handleInvoke(payload: JsonObject) {
        val ws = socket ?: return
        val invokeId = payload["id"]?.jsonPrimitive?.content ?: return
        val nodeId = payload["nodeId"]?.jsonPrimitive?.content ?: return
        val command = payload["command"]?.jsonPrimitive?.content ?: return
        // Params arrive either as a nested object or a JSON-encoded string.
        val params = payload["params"]?.jsonObject
            ?: payload["paramsJSON"]?.jsonPrimitive?.content?.let {
                try { json.parseToJsonElement(it).jsonObject } catch (_: Exception) { null }
            } ?: buildJsonObject {}
        // Translate the gateway's canonical command name (e.g. camera.snap) back
        // to our internal capability id (e.g. camera.take_photo) before dispatch.
        val capabilityId = OCNodeCommandMap.resolveCapabilityId(command)
        Log.d(TAG, "invoke: $command -> $capabilityId params=$params")
        val result = try {
            capabilityManager.execute(capabilityId, params)
        } catch (e: Exception) {
            buildJsonObject { put("error", e.message ?: "execute failed") }
        }
        val hasError = result["error"] != null
        val resultParams = buildJsonObject {
            put("id", invokeId)
            put("nodeId", nodeId)
            put("ok", !hasError)
            if (!hasError) {
                put("payloadJSON", result.toString())
            } else {
                put("error", buildJsonObject {
                    put("code", "INVOKE_FAILED")
                    put("message", result["error"]?.jsonPrimitive?.content ?: "failed")
                })
            }
        }
        val resultFrame = buildJsonObject {
            put("type", "req")
            put("id", UUID.randomUUID().toString())
            put("method", "node.invoke.result")
            put("params", resultParams)
        }
        sendFrame(ws, resultFrame)
    }

    private suspend fun sendFrame(ws: WebSocket, frame: JsonObject) {
        writeLock.withLock { ws.send(frame.toString()) }
    }

    private fun buildUserAgent(): String =
        "OCHelper/1.0.0 (Android ${Build.VERSION.RELEASE}; SDK ${Build.VERSION.SDK_INT})"
}
