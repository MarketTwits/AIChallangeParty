package com.markettwits.aichallenge.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * MCP Protocol implementation following JSON-RPC 2.0 specification
 * Based on Model Context Protocol specification 2025-03-26
 */

// ============== JSON-RPC 2.0 Base Messages ==============

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: String,
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: String,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcNotification(
    val jsonrpc: String = "2.0",
    val method: String,
    val params: JsonObject? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

// ============== MCP Lifecycle Messages ==============

/**
 * Initialize request - first message from client to server
 */
@Serializable
data class InitializeRequest(
    val protocolVersion: String = "2025-03-26",
    val capabilities: ClientCapabilities,
    val clientInfo: Implementation,
)

@Serializable
data class ClientCapabilities(
    val experimental: Map<String, JsonElement>? = null,
    val sampling: Map<String, JsonElement>? = null,
    val roots: RootsCapability? = null,
)

@Serializable
data class RootsCapability(
    val listChanged: Boolean = false,
)

/**
 * Initialize response from server
 */
@Serializable
data class InitializeResult(
    val protocolVersion: String = "2025-03-26",
    val capabilities: ServerCapabilities,
    val serverInfo: Implementation,
    val instructions: String? = null,
)

@Serializable
data class ServerCapabilities(
    val experimental: Map<String, JsonElement>? = null,
    val logging: Map<String, JsonElement>? = null,
    val prompts: PromptsCapability? = null,
    val resources: ResourcesCapability? = null,
    val tools: ToolsCapability? = null,
)

@Serializable
data class PromptsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class ResourcesCapability(
    val subscribe: Boolean? = null,
    val listChanged: Boolean? = null,
)

@Serializable
data class ToolsCapability(
    val listChanged: Boolean? = null,
)

@Serializable
data class Implementation(
    val name: String,
    val version: String,
)

// ============== MCP Tools ==============

/**
 * List tools request
 */
@Serializable
data class ListToolsRequest(
    val cursor: String? = null,
)

/**
 * List tools response
 */
@Serializable
data class ListToolsResult(
    val tools: List<McpToolDefinition>,
    val nextCursor: String? = null,
)

@Serializable
data class McpToolDefinition(
    val name: String,
    val description: String? = null,
    val inputSchema: JsonObject,
)

/**
 * Call tool request
 */
@Serializable
data class CallToolRequest(
    val name: String,
    val arguments: JsonObject? = null,
)

/**
 * Call tool response
 */
@Serializable
data class CallToolResult(
    val content: List<ToolContent>,
    val isError: Boolean = false,
)

@Serializable
data class ToolContent(
    val type: String,  // "text", "image", "resource"
    val text: String? = null,
    val data: String? = null,
    val mimeType: String? = null,
)

// ============== MCP Resources ==============

@Serializable
data class ListResourcesRequest(
    val cursor: String? = null,
)

@Serializable
data class ListResourcesResult(
    val resources: List<Resource>,
    val nextCursor: String? = null,
)

@Serializable
data class Resource(
    val uri: String,
    val name: String,
    val description: String? = null,
    val mimeType: String? = null,
)

@Serializable
data class ReadResourceRequest(
    val uri: String,
)

@Serializable
data class ReadResourceResult(
    val contents: List<ResourceContent>,
)

@Serializable
data class ResourceContent(
    val uri: String,
    val mimeType: String? = null,
    val text: String? = null,
    val blob: String? = null,
)

// ============== MCP Prompts ==============

@Serializable
data class ListPromptsRequest(
    val cursor: String? = null,
)

@Serializable
data class ListPromptsResult(
    val prompts: List<Prompt>,
    val nextCursor: String? = null,
)

@Serializable
data class Prompt(
    val name: String,
    val description: String? = null,
    val arguments: List<PromptArgument>? = null,
)

@Serializable
data class PromptArgument(
    val name: String,
    val description: String? = null,
    val required: Boolean? = null,
)

// ============== MCP Server State ==============

@Serializable
enum class McpServerState {
    CREATED,
    INITIALIZING,
    READY,
    SHUTTING_DOWN,
    CLOSED,
    ERROR
}

// ============== Common MCP Methods ==============

object McpMethods {
    // Lifecycle
    const val INITIALIZE = "initialize"
    const val INITIALIZED = "notifications/initialized"
    const val SHUTDOWN = "shutdown"
    const val PING = "ping"

    // Tools
    const val TOOLS_LIST = "tools/list"
    const val TOOLS_CALL = "tools/call"

    // Resources
    const val RESOURCES_LIST = "resources/list"
    const val RESOURCES_READ = "resources/read"
    const val RESOURCES_SUBSCRIBE = "resources/subscribe"
    const val RESOURCES_UNSUBSCRIBE = "resources/unsubscribe"

    // Prompts
    const val PROMPTS_LIST = "prompts/list"
    const val PROMPTS_GET = "prompts/get"

    // Logging
    const val LOGGING_SET_LEVEL = "logging/setLevel"

    // Notifications
    const val NOTIFICATION_CANCELLED = "notifications/cancelled"
    const val NOTIFICATION_PROGRESS = "notifications/progress"
    const val NOTIFICATION_RESOURCES_UPDATED = "notifications/resources/list_changed"
    const val NOTIFICATION_TOOLS_UPDATED = "notifications/tools/list_changed"
}

// ============== MCP Error Codes ==============

object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603
}
