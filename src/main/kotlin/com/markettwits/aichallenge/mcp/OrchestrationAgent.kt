package com.markettwits.aichallenge.mcp

import com.markettwits.aichallenge.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * Orchestration Agent - uses LLM to select and execute tools from multiple MCP servers
 * Implements Day 14 requirement: LLM-driven tool selection across multiple MCP servers
 */
class OrchestrationAgent(
    private val anthropicClient: AnthropicClient,
    private val orchestrator: McpOrchestrator,
) {
    private val logger = LoggerFactory.getLogger(OrchestrationAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    /**
     * Execute a user query with automatic tool selection from multiple MCP servers
     */
    suspend fun execute(userQuery: String, sessionId: String = "default"): OrchestrationResult {
        logger.info("Executing orchestration query: $userQuery")

        val executionSteps = mutableListOf<ExecutionStep>()
        val startTime = System.currentTimeMillis()

        try {
            // Step 1: Get all available tools from all MCP servers
            val allTools = orchestrator.getAllTools()
            logger.info("Available tools from all servers: ${allTools.size}")

            executionSteps.add(
                ExecutionStep(
                    step = 1,
                    action = "Discovered Tools",
                    description = "Found ${allTools.size} tools across ${orchestrator.getServerStatus().size} MCP servers",
                    toolsInvolved = allTools.map { it.tool.name },
                    success = true
                )
            )

            // Step 2: Convert MCP tools to Claude Tool format
            val claudeTools = allTools.map { toolWithServer ->
                Tool(
                    name = toolWithServer.tool.name,
                    description = "${toolWithServer.tool.description} [Server: ${toolWithServer.serverName}]",
                    input_schema = InputSchema(
                        type = "object",
                        properties = toolWithServer.tool.inputSchema["properties"] as? JsonObject
                            ?: buildJsonObject {},
                        required = (toolWithServer.tool.inputSchema["required"] as? JsonArray)
                            ?.map { it.jsonPrimitive.content } ?: emptyList()
                    )
                )
            }

            // Step 3: Create system prompt for orchestration
            val systemPrompt = buildString {
                appendLine("You are an intelligent orchestration agent with access to multiple MCP (Model Context Protocol) servers.")
                appendLine()
                appendLine("You have access to ${allTools.size} tools across ${orchestrator.getServerStatus().size} specialized servers:")
                orchestrator.getServerStatus().forEach { (_, status) ->
                    appendLine("- ${status.serverName}: ${status.toolCount} tools available")
                }
                appendLine()
                appendLine("Your task is to:")
                appendLine("1. Understand the user's request")
                appendLine("2. Select the appropriate tools from the available MCP servers")
                appendLine("3. Execute tools in the correct order (cascade/chain calls if needed)")
                appendLine("4. Combine results into a comprehensive answer")
                appendLine()
                appendLine("When you need to use multiple tools in sequence:")
                appendLine("- First tool's output can be input to the second tool")
                appendLine("- Chain tools logically to fulfill the request")
                appendLine("- Explain your reasoning for each tool selection")
                appendLine()
                appendLine("Always be helpful, accurate, and explain which servers you're using.")
            }

            // Step 4: Send request to Claude
            val messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentBlock(
                            type = "text",
                            text = userQuery
                        )
                    )
                )
            )

            val anthropicResponse = anthropicClient.sendMessage(
                messages = messages,
                tools = claudeTools,
                systemPrompt = systemPrompt,
                temperature = 0.7
            )

            // Step 5: Process tool calls (cascade pattern)
            var currentMessages = messages.toMutableList()
            var currentResponse = anthropicResponse
            var iteration = 0
            val maxIterations = 5  // Prevent infinite loops

            while (iteration < maxIterations) {
                iteration++

                val toolUseBlocks = currentResponse.content.filter { it.type == "tool_use" }

                if (toolUseBlocks.isEmpty()) {
                    // No more tool calls, extract final response
                    val finalText = currentResponse.content
                        .filter { it.type == "text" }
                        .joinToString("\n") { it.text ?: "" }

                    executionSteps.add(
                        ExecutionStep(
                            step = iteration + 1,
                            action = "Final Response",
                            description = "Generated final answer",
                            success = true
                        )
                    )

                    val elapsedTime = System.currentTimeMillis() - startTime

                    return OrchestrationResult(
                        query = userQuery,
                        response = finalText,
                        success = true,
                        executionSteps = executionSteps,
                        serversUsed = getServersUsed(executionSteps),
                        toolsExecuted = getToolsExecuted(executionSteps),
                        elapsedTimeMs = elapsedTime,
                        inputTokens = currentResponse.usage.input_tokens,
                        outputTokens = currentResponse.usage.output_tokens
                    )
                }

                // Execute tool calls via orchestrator
                val toolResults = mutableListOf<ContentBlock>()

                for (toolBlock in toolUseBlocks) {
                    val toolName = toolBlock.name ?: continue
                    val toolInput = toolBlock.input ?: buildJsonObject {}

                    logger.info("Iteration $iteration: Executing tool '$toolName' via orchestrator")

                    val result = orchestrator.callTool(toolName, toolInput)

                    val resultText = result.content.joinToString("\n") { it.text ?: "" }

                    executionSteps.add(
                        ExecutionStep(
                            step = iteration + 1,
                            action = "Tool Execution",
                            description = "Called tool: $toolName",
                            toolsInvolved = listOf(toolName),
                            result = resultText,
                            success = !result.isError
                        )
                    )

                    toolResults.add(
                        ContentBlock(
                            type = "tool_result",
                            content = resultText,
                            tool_use_id = toolBlock.id
                        )
                    )
                }

                // Add assistant message and tool results to conversation
                currentMessages.add(
                    Message(
                        role = "assistant",
                        content = currentResponse.content
                    )
                )
                currentMessages.add(
                    Message(
                        role = "user",
                        content = toolResults
                    )
                )

                // Send updated conversation back to Claude for next iteration
                currentResponse = anthropicClient.sendMessage(
                    messages = currentMessages,
                    tools = claudeTools,
                    systemPrompt = null  // Only send system prompt once
                )
            }

            // Max iterations reached
            return OrchestrationResult(
                query = userQuery,
                response = "Maximum iteration limit reached. Partial results available.",
                success = false,
                executionSteps = executionSteps,
                serversUsed = getServersUsed(executionSteps),
                toolsExecuted = getToolsExecuted(executionSteps),
                elapsedTimeMs = System.currentTimeMillis() - startTime
            )

        } catch (e: Exception) {
            logger.error("Error in orchestration execution", e)
            executionSteps.add(
                ExecutionStep(
                    step = executionSteps.size + 1,
                    action = "Error",
                    description = "Execution failed: ${e.message}",
                    success = false
                )
            )

            return OrchestrationResult(
                query = userQuery,
                response = "Error during orchestration: ${e.message}",
                success = false,
                executionSteps = executionSteps,
                serversUsed = emptyList(),
                toolsExecuted = emptyList(),
                elapsedTimeMs = System.currentTimeMillis() - startTime
            )
        }
    }

    private fun getServersUsed(steps: List<ExecutionStep>): List<String> {
        return steps
            .flatMap { it.toolsInvolved }
            .mapNotNull { toolName -> orchestrator.getToolMapping()[toolName] }
            .distinct()
    }

    private fun getToolsExecuted(steps: List<ExecutionStep>): List<String> {
        return steps
            .flatMap { it.toolsInvolved }
            .distinct()
    }
}

/**
 * Result of orchestration execution
 */
@Serializable
data class OrchestrationResult(
    val query: String,
    val response: String,
    val success: Boolean,
    val executionSteps: List<ExecutionStep>,
    val serversUsed: List<String>,
    val toolsExecuted: List<String>,
    val elapsedTimeMs: Long,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
)

/**
 * Individual execution step
 */
@Serializable
data class ExecutionStep(
    val step: Int,
    val action: String,
    val description: String,
    val toolsInvolved: List<String> = emptyList(),
    val result: String? = null,
    val success: Boolean,
)
