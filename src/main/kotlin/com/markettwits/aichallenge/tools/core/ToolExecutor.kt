package com.markettwits.aichallenge.tools.core

import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/**
 * Executor for tools - handles tool execution with proper error handling
 * Follows Dependency Inversion Principle (SOLID)
 */
class ToolExecutor(private val registry: ToolRegistry) {
    private val logger = LoggerFactory.getLogger(ToolExecutor::class.java)

    /**
     * Execute a tool by name with given parameters
     * Chain: User -> tool call -> ToolExecutor -> tool result -> LLM
     */
    suspend fun execute(toolName: String, params: JsonObject): ToolResult {
        logger.info("Executing tool: $toolName")
        logger.debug("Tool params: $params")

        // Step 1: Find tool
        val tool = registry.getTool(toolName)
        if (tool == null) {
            logger.error("Tool not found: $toolName")
            return ToolResult.Error(
                message = "Unknown tool: $toolName",
                code = "TOOL_NOT_FOUND"
            )
        }

        // Step 2: Validate parameters
        val validationError = tool.validateParams(params)
        if (validationError != null) {
            logger.error("Tool parameter validation failed: $validationError")
            return ToolResult.Error(
                message = "Invalid parameters: $validationError",
                code = "INVALID_PARAMS"
            )
        }

        // Step 3: Execute tool
        return try {
            val startTime = System.currentTimeMillis()
            val result = tool.execute(params)
            val duration = System.currentTimeMillis() - startTime

            logger.info("Tool $toolName executed successfully in ${duration}ms")
            logger.debug("Tool result: ${result.toJsonString().take(200)}")

            result
        } catch (e: Exception) {
            logger.error("Tool execution failed: ${e.message}", e)
            ToolResult.Error(
                message = "Tool execution failed: ${e.message}",
                code = "EXECUTION_ERROR",
                details = mapOf("exception" to (e::class.simpleName ?: "Unknown"))
            )
        }
    }

    /**
     * Execute multiple tools in sequence
     */
    suspend fun executeMany(calls: List<Pair<String, JsonObject>>): List<ToolResult> {
        logger.info("Executing ${calls.size} tools in sequence")
        return calls.map { (name, params) ->
            execute(name, params)
        }
    }

    /**
     * Get execution statistics
     */
    fun getStats(): Map<String, Any> = mapOf(
        "availableTools" to registry.getToolCount(),
        "registryStats" to registry.getSummary()
    )
}
