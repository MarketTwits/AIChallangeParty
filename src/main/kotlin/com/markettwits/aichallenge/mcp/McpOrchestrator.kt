package com.markettwits.aichallenge.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * MCP Orchestrator - manages multiple MCP servers and routes tool calls
 * Implements Day 14 requirement: organizing work with multiple MCP servers
 */
class McpOrchestrator {
    private val logger = LoggerFactory.getLogger(McpOrchestrator::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val servers = ConcurrentHashMap<String, RealMcpServer>()
    private val toolToServerMap = ConcurrentHashMap<String, String>()

    /**
     * Register an MCP server
     */
    fun registerServer(serverId: String, server: RealMcpServer) {
        logger.info("Registering MCP server: $serverId")
        servers[serverId] = server

        // Initialize the server
        initializeServer(serverId, server)
    }

    /**
     * Initialize server and build tool mapping
     */
    private fun initializeServer(serverId: String, server: RealMcpServer) {
        try {
            // Send initialize request
            val initRequest = JsonRpcRequest(
                id = "init-$serverId",
                method = McpMethods.INITIALIZE,
                params = buildJsonObject {
                    put("protocolVersion", "2025-03-26")
                    put("capabilities", buildJsonObject {})
                    put("clientInfo", buildJsonObject {
                        put("name", "mcp-orchestrator")
                        put("version", "1.0.0")
                    })
                }
            )

            val initRequestJson = json.encodeToString(JsonRpcRequest.serializer(), initRequest)
            kotlinx.coroutines.runBlocking {
                server.handleRequest(initRequestJson)
            }

            logger.info("Server $serverId initialized successfully")

            // Get tools list and build mapping
            buildToolMapping(serverId, server)

        } catch (e: Exception) {
            logger.error("Failed to initialize server $serverId", e)
        }
    }

    /**
     * Build tool-to-server mapping
     */
    private fun buildToolMapping(serverId: String, server: RealMcpServer) {
        try {
            val toolsRequest = JsonRpcRequest(
                id = "tools-$serverId",
                method = McpMethods.TOOLS_LIST
            )

            val requestJson = json.encodeToString(JsonRpcRequest.serializer(), toolsRequest)
            val responseJson = kotlinx.coroutines.runBlocking {
                server.handleRequest(requestJson)
            }

            val response = json.decodeFromString<JsonRpcResponse>(responseJson)
            if (response.result != null) {
                val toolsResult = json.decodeFromJsonElement<ListToolsResult>(response.result)
                toolsResult.tools.forEach { tool ->
                    toolToServerMap[tool.name] = serverId
                    logger.debug("Mapped tool '${tool.name}' to server '$serverId'")
                }
                logger.info("Registered ${toolsResult.tools.size} tools from server $serverId")
            }

        } catch (e: Exception) {
            logger.error("Failed to build tool mapping for server $serverId", e)
        }
    }

    /**
     * Get all available tools from all servers
     */
    suspend fun getAllTools(): List<McpToolWithServer> {
        val allTools = mutableListOf<McpToolWithServer>()

        servers.forEach { (serverId, server) ->
            try {
                val toolsRequest = JsonRpcRequest(
                    id = "list-all-$serverId",
                    method = McpMethods.TOOLS_LIST
                )

                val requestJson = json.encodeToString(JsonRpcRequest.serializer(), toolsRequest)
                val responseJson = server.handleRequest(requestJson)
                val response = json.decodeFromString<JsonRpcResponse>(responseJson)

                if (response.result != null) {
                    val toolsResult = json.decodeFromJsonElement<ListToolsResult>(response.result)
                    toolsResult.tools.forEach { tool ->
                        allTools.add(
                            McpToolWithServer(
                                tool = tool,
                                serverId = serverId,
                                serverName = server.serverInfo.name
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                logger.error("Error getting tools from server $serverId", e)
            }
        }

        logger.info("Total tools available across all servers: ${allTools.size}")
        return allTools
    }

    /**
     * Call a tool on the appropriate server
     */
    suspend fun callTool(toolName: String, arguments: JsonObject?): CallToolResult {
        val serverId = toolToServerMap[toolName]
            ?: return CallToolResult(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = "Error: Unknown tool '$toolName'. Available tools: ${toolToServerMap.keys.joinToString()}"
                    )
                ),
                isError = true
            )

        val server = servers[serverId]
            ?: return CallToolResult(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = "Error: Server '$serverId' not found"
                    )
                ),
                isError = true
            )

        logger.info("Routing tool call '$toolName' to server '$serverId'")

        return try {
            val callRequest = JsonRpcRequest(
                id = "call-${System.currentTimeMillis()}",
                method = McpMethods.TOOLS_CALL,
                params = buildJsonObject {
                    put("name", toolName)
                    if (arguments != null) {
                        put("arguments", arguments)
                    }
                }
            )

            val requestJson = json.encodeToString(JsonRpcRequest.serializer(), callRequest)
            val responseJson = server.handleRequest(requestJson)
            val response = json.decodeFromString<JsonRpcResponse>(responseJson)

            if (response.error != null) {
                CallToolResult(
                    content = listOf(
                        ToolContent(
                            type = "text",
                            text = "Error: ${response.error.message}"
                        )
                    ),
                    isError = true
                )
            } else if (response.result != null) {
                json.decodeFromJsonElement<CallToolResult>(response.result)
            } else {
                CallToolResult(
                    content = listOf(
                        ToolContent(
                            type = "text",
                            text = "Error: No result from server"
                        )
                    ),
                    isError = true
                )
            }

        } catch (e: Exception) {
            logger.error("Error calling tool $toolName on server $serverId", e)
            CallToolResult(
                content = listOf(
                    ToolContent(
                        type = "text",
                        text = "Error executing tool: ${e.message}"
                    )
                ),
                isError = true
            )
        }
    }

    /**
     * Get server status
     */
    fun getServerStatus(): Map<String, ServerStatus> {
        return servers.mapValues { (serverId, server) ->
            val toolCount = toolToServerMap.count { it.value == serverId }
            ServerStatus(
                serverId = serverId,
                serverName = server.serverInfo.name,
                version = server.serverInfo.version,
                state = server.state,
                toolCount = toolCount
            )
        }
    }

    /**
     * Get tool to server mapping
     */
    fun getToolMapping(): Map<String, String> {
        return toolToServerMap.toMap()
    }

    /**
     * Shutdown all servers
     */
    suspend fun shutdown() {
        logger.info("Shutting down all MCP servers...")
        servers.forEach { (serverId, server) ->
            try {
                val shutdownRequest = JsonRpcRequest(
                    id = "shutdown-$serverId",
                    method = McpMethods.SHUTDOWN
                )
                val requestJson = json.encodeToString(JsonRpcRequest.serializer(), shutdownRequest)
                server.handleRequest(requestJson)
                logger.info("Server $serverId shut down")
            } catch (e: Exception) {
                logger.error("Error shutting down server $serverId", e)
            }
        }
        servers.clear()
        toolToServerMap.clear()
    }
}

/**
 * Tool with server information
 */
data class McpToolWithServer(
    val tool: McpToolDefinition,
    val serverId: String,
    val serverName: String,
)

/**
 * Server status information
 */
@Serializable
data class ServerStatus(
    val serverId: String,
    val serverName: String,
    val version: String,
    val state: McpServerState,
    val toolCount: Int,
)
