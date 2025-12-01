package com.markettwits.aichallenge.tools.core

import kotlinx.serialization.json.JsonObject

/**
 * Core interface for all tools in the system
 * Follows Interface Segregation Principle (SOLID)
 */
interface Tool {
    val name: String
    val description: String
    val type: ToolType
    val schema: ToolSchema

    /**
     * Execute the tool with given parameters
     * @param params Tool input parameters as JsonObject
     * @return ToolResult containing success status and result data
     */
    suspend fun execute(params: JsonObject): ToolResult

    /**
     * Validate tool parameters before execution
     * @return null if valid, error message if invalid
     */
    fun validateParams(params: JsonObject): String?
}

/**
 * Tool types in the system
 */
enum class ToolType {
    RAG,           // Retrieval-Augmented Generation tools
    MCP,           // Model Context Protocol tools
    HTTP_API,      // External HTTP API tools
    SYSTEM,        // System/internal tools
    CUSTOM         // Custom user-defined tools
}

/**
 * Tool schema definition for Claude API
 */
data class ToolSchema(
    val type: String = "object",
    val properties: JsonObject,
    val required: List<String> = emptyList(),
)

/**
 * Result of tool execution
 */
sealed class ToolResult {
    data class Success(
        val data: String,
        val metadata: Map<String, Any> = emptyMap(),
    ) : ToolResult()

    data class Error(
        val message: String,
        val code: String? = null,
        val details: Map<String, Any> = emptyMap(),
    ) : ToolResult()

    fun toJsonString(): String = when (this) {
        is Success -> data
        is Error -> """{"error": "$message", "code": "$code"}"""
    }

    fun isSuccess(): Boolean = this is Success
}
