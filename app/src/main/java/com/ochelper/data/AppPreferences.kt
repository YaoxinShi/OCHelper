package com.ochelper.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "ochelper_prefs")

class AppPreferences(private val context: Context) {

    // ── OCNode ────────────────────────────────────────────────
    val ocNodeUrl: Flow<String> = context.dataStore.data.map { it[Keys.OC_NODE_URL] ?: "" }
    val ocNodeToken: Flow<String> = context.dataStore.data.map { it[Keys.OC_NODE_TOKEN] ?: "" }
    val ocNodeEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.OC_NODE_ENABLED] ?: false }
    val ocNodeId: Flow<String> = context.dataStore.data.map {
        it[Keys.OC_NODE_ID] ?: UUID.randomUUID().toString().also { id ->
            // Will be persisted on first write below
        }
    }

    suspend fun setOcNodeUrl(url: String) = context.dataStore.edit { it[Keys.OC_NODE_URL] = url }
    suspend fun setOcNodeToken(token: String) = context.dataStore.edit { it[Keys.OC_NODE_TOKEN] = token }
    suspend fun setOcNodeEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.OC_NODE_ENABLED] = enabled }
    suspend fun setOcNodeId(id: String) = context.dataStore.edit { it[Keys.OC_NODE_ID] = id }

    // ── MCP Server ────────────────────────────────────────────
    val mcpPort: Flow<Int> = context.dataStore.data.map { it[Keys.MCP_PORT] ?: 8765 }
    val mcpToken: Flow<String> = context.dataStore.data.map { it[Keys.MCP_TOKEN] ?: generateToken() }
    val mcpEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.MCP_ENABLED] ?: false }

    suspend fun setMcpPort(port: Int) = context.dataStore.edit { it[Keys.MCP_PORT] = port }
    suspend fun setMcpToken(token: String) = context.dataStore.edit { it[Keys.MCP_TOKEN] = token }
    suspend fun setMcpEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.MCP_ENABLED] = enabled }

    // ── RTSP ──────────────────────────────────────────────────
    val rtspPort: Flow<Int> = context.dataStore.data.map { it[Keys.RTSP_PORT] ?: 8554 }
    val rtspEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.RTSP_ENABLED] ?: false }
    val rtspResolution: Flow<String> = context.dataStore.data.map { it[Keys.RTSP_RESOLUTION] ?: "1280x720" }
    val rtspFps: Flow<Int> = context.dataStore.data.map { it[Keys.RTSP_FPS] ?: 30 }
    val rtspBitrate: Flow<Int> = context.dataStore.data.map { it[Keys.RTSP_BITRATE] ?: 2000000 }
    val rtspCameraFacing: Flow<String> = context.dataStore.data.map { it[Keys.RTSP_CAMERA_FACING] ?: "back" }

    suspend fun setRtspPort(port: Int) = context.dataStore.edit { it[Keys.RTSP_PORT] = port }
    suspend fun setRtspEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.RTSP_ENABLED] = enabled }
    suspend fun setRtspResolution(res: String) = context.dataStore.edit { it[Keys.RTSP_RESOLUTION] = res }
    suspend fun setRtspFps(fps: Int) = context.dataStore.edit { it[Keys.RTSP_FPS] = fps }
    suspend fun setRtspBitrate(bps: Int) = context.dataStore.edit { it[Keys.RTSP_BITRATE] = bps }
    suspend fun setRtspCameraFacing(facing: String) = context.dataStore.edit { it[Keys.RTSP_CAMERA_FACING] = facing }

    // ── Gateway ───────────────────────────────────────────────
    val gatewayUrl: Flow<String> = context.dataStore.data.map { it[Keys.GATEWAY_URL] ?: "" }
    val gatewayApiKey: Flow<String> = context.dataStore.data.map { it[Keys.GATEWAY_API_KEY] ?: "" }
    val gatewayEnabled: Flow<Boolean> = context.dataStore.data.map { it[Keys.GATEWAY_ENABLED] ?: false }
    val gatewayDefaultModel: Flow<String> = context.dataStore.data.map { it[Keys.GATEWAY_DEFAULT_MODEL] ?: "" }

    suspend fun setGatewayUrl(url: String) = context.dataStore.edit { it[Keys.GATEWAY_URL] = url }
    suspend fun setGatewayApiKey(key: String) = context.dataStore.edit { it[Keys.GATEWAY_API_KEY] = key }
    suspend fun setGatewayEnabled(enabled: Boolean) = context.dataStore.edit { it[Keys.GATEWAY_ENABLED] = enabled }
    suspend fun setGatewayDefaultModel(model: String) = context.dataStore.edit { it[Keys.GATEWAY_DEFAULT_MODEL] = model }

    // ── Capability toggles ────────────────────────────────────
    fun capabilityEnabled(name: String): Flow<Boolean> =
        context.dataStore.data.map { it[booleanPreferencesKey("cap_$name")] ?: defaultCapabilityEnabled(name) }

    suspend fun setCapabilityEnabled(name: String, enabled: Boolean) =
        context.dataStore.edit { it[booleanPreferencesKey("cap_$name")] = enabled }

    private fun defaultCapabilityEnabled(name: String): Boolean = when (name) {
        "contacts", "calendar", "notifications", "sms" -> false
        else -> true
    }

    private object Keys {
        val OC_NODE_URL = stringPreferencesKey("oc_node_url")
        val OC_NODE_TOKEN = stringPreferencesKey("oc_node_token")
        val OC_NODE_ENABLED = booleanPreferencesKey("oc_node_enabled")
        val OC_NODE_ID = stringPreferencesKey("oc_node_id")

        val MCP_PORT = intPreferencesKey("mcp_port")
        val MCP_TOKEN = stringPreferencesKey("mcp_token")
        val MCP_ENABLED = booleanPreferencesKey("mcp_enabled")

        val RTSP_PORT = intPreferencesKey("rtsp_port")
        val RTSP_ENABLED = booleanPreferencesKey("rtsp_enabled")
        val RTSP_RESOLUTION = stringPreferencesKey("rtsp_resolution")
        val RTSP_FPS = intPreferencesKey("rtsp_fps")
        val RTSP_BITRATE = intPreferencesKey("rtsp_bitrate")
        val RTSP_CAMERA_FACING = stringPreferencesKey("rtsp_camera_facing")

        val GATEWAY_URL = stringPreferencesKey("gateway_url")
        val GATEWAY_API_KEY = stringPreferencesKey("gateway_api_key")
        val GATEWAY_ENABLED = booleanPreferencesKey("gateway_enabled")
        val GATEWAY_DEFAULT_MODEL = stringPreferencesKey("gateway_default_model")
    }

    companion object {
        fun generateToken(): String = "oc-${UUID.randomUUID().toString().replace("-", "").take(16)}"
    }
}
