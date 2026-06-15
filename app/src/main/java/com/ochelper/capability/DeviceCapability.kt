package com.ochelper.capability

import kotlinx.serialization.json.JsonObject

/** Base interface for all Android device capabilities exposed via OCNode and MCP. */
interface DeviceCapability {
    val id: String
    val name: String
    val description: String
    val inputSchema: JsonObject
    suspend fun execute(params: JsonObject): JsonObject
}
