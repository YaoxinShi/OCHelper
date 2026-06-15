package com.ochelper.service

import android.content.Context
import com.ochelper.capability.CapabilityManager
import com.ochelper.gateway.OCGatewayClient
import com.ochelper.mcp.MCPServer
import com.ochelper.ocnode.OCNodeClient

/** Simple singleton registry to share service instances with ViewModels. */
object ServiceRegistry {
    @Volatile var ocNodeClient: OCNodeClient? = null
    @Volatile var mcpServer: MCPServer? = null
    @Volatile var gatewayClient: OCGatewayClient? = null
    @Volatile var rtspService: RTSPService? = null

    private var capabilityManager: CapabilityManager? = null

    @Synchronized
    fun getCapabilityManager(context: Context): CapabilityManager {
        return capabilityManager ?: CapabilityManager(context).also { capabilityManager = it }
    }
}
