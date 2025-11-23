package com.markettwits.aichallenge.mcp

import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Base class for MCP servers implementing the Model Context Protocol
 * Follows JSON-RPC 2.0 specification
 */
abstract class RealMcpServer(
    val serverInfo: Implementation,
) {
    protected val logger = LoggerFactory.getLogger(this::class.java)
    protected val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    var state: McpServerState = McpServerState.CREATED
        protected set
    protected val requestCounter = AtomicLong(0)
    protected val pendingRequests = ConcurrentHashMap<String, Long>()

    /**
     * Get server capabilities (override in subclasses)
     */
    abstract fun getCapabilities(): ServerCapabilities

    /**
     * Get list of tools provided by this server
     */
    abstract suspend fun listTools(): List<McpToolDefinition>

    /**
     * Execute a tool call
     */
    abstract suspend fun callTool(name: String, arguments: JsonObject?): CallToolResult

    /**
     * Handle incoming JSON-RPC request
     */
    suspend fun handleRequest(requestJson: String): String {
        return try {
            val request = json.decodeFromString<JsonRpcRequest>(requestJson)
            pendingRequests[request.id] = System.currentTimeMillis()

            logger.debug("Handling request: method=${request.method}, id=${request.id}")

            val result = when (request.method) {
                McpMethods.INITIALIZE -> handleInitialize(request)
                McpMethods.TOOLS_LIST -> handleToolsList(request)
                McpMethods.TOOLS_CALL -> handleToolsCall(request)
                McpMethods.PING -> handlePing(request)
                McpMethods.SHUTDOWN -> handleShutdown(request)
                else -> createErrorResponse(
                    request.id,
                    McpErrorCodes.METHOD_NOT_FOUND,
                    "Method not found: ${request.method}"
                )
            }

            pendingRequests.remove(request.id)
            json.encodeToString(JsonRpcResponse.serializer(), result)

        } catch (e: Exception) {
            logger.error("Error handling request", e)
            val errorResponse = JsonRpcResponse(
                id = "unknown",
                error = JsonRpcError(
                    code = McpErrorCodes.INTERNAL_ERROR,
                    message = "Internal error: ${e.message}"
                )
            )
            json.encodeToString(JsonRpcResponse.serializer(), errorResponse)
        }
    }

    /**
     * Handle initialize request
     */
    private fun handleInitialize(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            if (state != McpServerState.CREATED) {
                return createErrorResponse(
                    request.id,
                    McpErrorCodes.INVALID_REQUEST,
                    "Server already initialized"
                )
            }

            val initRequest = if (request.params != null) {
                json.decodeFromJsonElement<InitializeRequest>(request.params)
            } else {
                InitializeRequest(
                    capabilities = ClientCapabilities(),
                    clientInfo = Implementation("unknown", "0.0.0")
                )
            }

            logger.info("Initializing MCP server: ${serverInfo.name} v${serverInfo.version}")
            logger.info("Client: ${initRequest.clientInfo.name} v${initRequest.clientInfo.version}")

            state = McpServerState.READY

            val result = InitializeResult(
                capabilities = getCapabilities(),
                serverInfo = serverInfo,
                instructions = "MCP Server ${serverInfo.name} ready"
            )

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            logger.error("Error in initialize", e)
            createErrorResponse(
                request.id,
                McpErrorCodes.INVALID_PARAMS,
                "Invalid initialize parameters: ${e.message}"
            )
        }
    }

    /**
     * Handle tools/list request
     */
    private suspend fun handleToolsList(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            if (state != McpServerState.READY) {
                return createErrorResponse(
                    request.id,
                    McpErrorCodes.INVALID_REQUEST,
                    "Server not ready"
                )
            }

            val tools = listTools()
            logger.debug("Listing ${tools.size} tools")

            val result = ListToolsResult(tools = tools)

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            logger.error("Error listing tools", e)
            createErrorResponse(
                request.id,
                McpErrorCodes.INTERNAL_ERROR,
                "Error listing tools: ${e.message}"
            )
        }
    }

    /**
     * Handle tools/call request
     */
    private suspend fun handleToolsCall(request: JsonRpcRequest): JsonRpcResponse {
        return try {
            if (state != McpServerState.READY) {
                return createErrorResponse(
                    request.id,
                    McpErrorCodes.INVALID_REQUEST,
                    "Server not ready"
                )
            }

            if (request.params == null) {
                return createErrorResponse(
                    request.id,
                    McpErrorCodes.INVALID_PARAMS,
                    "Missing tool call parameters"
                )
            }

            val callRequest = json.decodeFromJsonElement<CallToolRequest>(request.params)
            logger.debug("Calling tool: ${callRequest.name}")

            val result = callTool(callRequest.name, callRequest.arguments)

            JsonRpcResponse(
                id = request.id,
                result = json.encodeToJsonElement(result)
            )
        } catch (e: Exception) {
            logger.error("Error calling tool", e)
            createErrorResponse(
                request.id,
                McpErrorCodes.INTERNAL_ERROR,
                "Error calling tool: ${e.message}"
            )
        }
    }

    /**
     * Handle ping request
     */
    private fun handlePing(request: JsonRpcRequest): JsonRpcResponse {
        return JsonRpcResponse(
            id = request.id,
            result = buildJsonObject {
                put("status", "ok")
                put("server", serverInfo.name)
                put("version", serverInfo.version)
                put("state", state.name)
            }
        )
    }

    /**
     * Handle shutdown request
     */
    private fun handleShutdown(request: JsonRpcRequest): JsonRpcResponse {
        logger.info("Shutting down MCP server: ${serverInfo.name}")
        state = McpServerState.SHUTTING_DOWN

        return JsonRpcResponse(
            id = request.id,
            result = buildJsonObject {
                put("status", "shutting_down")
            }
        )
    }

    /**
     * Create error response
     */
    protected fun createErrorResponse(id: String, code: Int, message: String): JsonRpcResponse {
        return JsonRpcResponse(
            id = id,
            error = JsonRpcError(code = code, message = message)
        )
    }

    /**
     * Create success tool result
     */
    protected fun createToolResult(text: String): CallToolResult {
        return CallToolResult(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = text
                )
            ),
            isError = false
        )
    }

    /**
     * Create error tool result
     */
    protected fun createToolError(errorMessage: String): CallToolResult {
        return CallToolResult(
            content = listOf(
                ToolContent(
                    type = "text",
                    text = "Error: $errorMessage"
                )
            ),
            isError = true
        )
    }

    /**
     * Get next request ID
     */
    protected fun nextRequestId(): String {
        return "${serverInfo.name}-${requestCounter.incrementAndGet()}"
    }
}
