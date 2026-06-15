package com.ochelper.mcp

import com.ochelper.capability.CapabilityManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.bearer
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.request.receiveText
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

class MCPServer(
    private val capabilityManager: CapabilityManager,
    private val port: Int,
    private val bearerToken: String,
    private val authEnabled: Boolean = true,
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private var server: EmbeddedServer<*, *>? = null

    fun start() {
        server = embeddedServer(Netty, port = port, host = "0.0.0.0") {
            configureMCP()
        }
        server!!.start(wait = false)
    }

    fun stop() {
        server?.stop(500L, 1000L)
        server = null
    }

    private fun Application.configureMCP() {
        install(ContentNegotiation) { json(json) }
        install(CORS) {
            anyHost()
            allowHeader("Authorization")
            allowHeader("Content-Type")
        }

        if (authEnabled) {
            install(Authentication) {
                bearer("mcp-bearer") {
                    authenticate { tokenCredential ->
                        if (tokenCredential.token == bearerToken) {
                            io.ktor.server.auth.UserIdPrincipal("mcp-client")
                        } else null
                    }
                }
            }
        }

        routing {
            post("/mcp") {
                val body = call.receiveText()
                val response = handleMcpRequest(body)
                call.respondText(response.toString(), ContentType.Application.Json)
            }
        }
    }

    private suspend fun handleMcpRequest(body: String): JsonElement {
        val request = try {
            json.parseToJsonElement(body).jsonObject
        } catch (_: Exception) {
            return errorResponse(null, -32700, "Parse error")
        }

        val id = request["id"]
        val method = request["method"]?.jsonPrimitive?.contentOrNull
            ?: return errorResponse(id, -32600, "Invalid Request: missing method")

        return when (method) {
            "initialize" -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("result", buildJsonObject {
                    put("protocolVersion", "2024-11-05")
                    put("capabilities", buildJsonObject {
                        put("tools", buildJsonObject {})
                    })
                    put("serverInfo", buildJsonObject {
                        put("name", "OCHelper MCP Server")
                        put("version", "1.0.0")
                    })
                })
            }

            "tools/list" -> {
                val tools = buildJsonArray {
                    capabilityManager.enabledCapabilities().forEach { cap ->
                        add(buildJsonObject {
                            put("name", cap.id)
                            put("description", cap.description)
                            put("inputSchema", cap.inputSchema)
                        })
                    }
                }
                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    put("result", buildJsonObject { put("tools", tools) })
                }
            }

            "tools/call" -> {
                val params = request["params"]?.jsonObject
                    ?: return errorResponse(id, -32602, "Invalid params")
                val toolName = params["name"]?.jsonPrimitive?.contentOrNull
                    ?: return errorResponse(id, -32602, "Missing tool name")
                val toolArgs = params["arguments"]?.jsonObject ?: buildJsonObject {}

                val result = capabilityManager.execute(toolName, toolArgs)

                buildJsonObject {
                    put("jsonrpc", "2.0")
                    put("id", id ?: JsonNull)
                    put("result", buildJsonObject {
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", result.toString())
                            })
                        })
                        put("isError", result["error"] != null)
                    })
                }
            }

            "ping" -> buildJsonObject {
                put("jsonrpc", "2.0")
                put("id", id ?: JsonNull)
                put("result", buildJsonObject {})
            }

            else -> errorResponse(id, -32601, "Method not found: $method")
        }
    }

    private fun errorResponse(id: JsonElement?, code: Int, message: String): JsonObject =
        buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id ?: JsonNull)
            put("error", buildJsonObject {
                put("code", code)
                put("message", message)
            })
        }
}
