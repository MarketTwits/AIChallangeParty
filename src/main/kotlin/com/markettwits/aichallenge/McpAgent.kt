package com.markettwits.aichallenge

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

@Serializable
data class McpChatRequest(
    val message: String,
    val sessionId: String,
    val temperature: Double? = null,
    val maxContextTokens: Int? = null,
)

@Serializable
data class McpChatResponse(
    val response: String,
    val timestamp: String,
    val toolsUsed: List<String>? = null,
    val inputTokens: Int? = null,
    val outputTokens: Int? = null,
    val mcpResults: List<McpToolResult>? = null,
)

@Serializable
data class McpToolResult(
    val tool: String,
    val parameters: JsonObject,
    val result: String,
    val success: Boolean,
    val timestamp: String,
)

class McpAgent(
    private val anthropicClient: AnthropicClient,
    private val mcpServer: GitHubMcpServer = GitHubMcpServer(),
) {
    private val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true }

    suspend fun chat(
        message: String,
        sessionId: String,
        temperature: Double? = null,
        maxContextTokens: Int? = null,
    ): McpChatResponse {
        try {
            // Get available tools from MCP server
            val mcpTools = mcpServer.getAvailableTools()

            // Convert MCP tools to Claude Tool format
            val claudeTools = mcpTools.map { mcpTool ->
                val properties = mcpTool.inputSchema["properties"] as? JsonObject ?: buildJsonObject {}
                val requiredArray = mcpTool.inputSchema["required"] as? kotlinx.serialization.json.JsonArray
                val required = requiredArray?.map { it.toString() } ?: emptyList()

                Tool(
                    name = mcpTool.name,
                    description = mcpTool.description,
                    input_schema = InputSchema(
                        type = "object", // Claude API expects "object" type for tool input schemas
                        properties = properties,
                        required = required
                    )
                )
            }

            // System prompt for MCP agent
            val systemPrompt = """
                Ты - MCP агент, который помогает пользователям работать с GitHub через MCP (Model Context Protocol) сервер.
                У тебя есть доступ к следующим GitHub инструментам:

                ${mcpTools.joinToString("\n") { "- ${it.name}: ${it.description}" }}

                Когда пользователь задает вопрос о GitHub, репозиториях или хочет что-то найти на GitHub:
                1. Используй соответствующий инструмент для получения информации
                2. Проанализируй результат инструмента
                3. Дай развернутый ответ пользователю на основе полученных данных

                Твоя задача - быть полезным помощником для работы с GitHub.
                Всегда используй инструменты когда это необходимо для получения точной информации.
            """.trimIndent()

            // Create messages for Claude
            val messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentBlock(
                            type = "text",
                            text = message
                        )
                    )
                )
            )

            // Send request to Claude with MCP tools
            val anthropicResponse = anthropicClient.sendMessage(
                messages = messages,
                tools = claudeTools,
                systemPrompt = systemPrompt,
                temperature = temperature ?: 0.7
            )

            // Process tool use calls if any
            val mcpResults = mutableListOf<McpToolResult>()
            val toolsUsed = mutableListOf<String>()
            var finalResponse = anthropicResponse.content
                .filter { it.type == "text" }
                .joinToString("\n") { it.text ?: "" }

            // Process tool_use blocks
            val toolUseBlocks = anthropicResponse.content.filter { it.type == "tool_use" }

            if (toolUseBlocks.isNotEmpty()) {
                val assistantMessage = Message(
                    role = "assistant",
                    content = anthropicResponse.content
                )

                val toolResults = mutableListOf<ContentBlock>()

                for (toolBlock in toolUseBlocks) {
                    val toolName = toolBlock.name ?: continue
                    val toolInput = toolBlock.input ?: buildJsonObject {}

                    // Execute tool via MCP server
                    val parameters = mutableMapOf<String, Any>()
                    toolInput.forEach { (key, value) ->
                        parameters[key] = when {
                            value is kotlinx.serialization.json.JsonPrimitive -> value.content
                            else -> value.toString()
                        }
                    }

                    val mcpResult = runBlocking {
                        try {
                            val result = mcpServer.executeTool(toolName, parameters)
                            mcpResults.add(
                                McpToolResult(
                                    tool = toolName,
                                    parameters = toolInput,
                                    result = result,
                                    success = true,
                                    timestamp = java.time.Instant.now().toString()
                                )
                            )
                            result
                        } catch (e: Exception) {
                            mcpResults.add(
                                McpToolResult(
                                    tool = toolName,
                                    parameters = toolInput,
                                    result = "Error: ${e.message}",
                                    success = false,
                                    timestamp = java.time.Instant.now().toString()
                                )
                            )
                            "Error executing tool: ${e.message}"
                        }
                    }

                    toolResults.add(
                        ContentBlock(
                            type = "tool_result",
                            content = mcpResult,
                            tool_use_id = toolBlock.id
                        )
                    )

                    toolsUsed.add(toolName)
                }

                // Send tool results back to Claude for final response
                val finalMessages = listOf(
                    Message(role = "user", content = listOf(ContentBlock(type = "text", text = message))),
                    assistantMessage,
                    Message(role = "user", content = toolResults)
                )

                val finalAnthropicResponse = anthropicClient.sendMessage(
                    messages = finalMessages,
                    tools = null, // Don't include tools in final request
                    systemPrompt = null
                )
                finalResponse = finalAnthropicResponse.content
                    .filter { it.type == "text" }
                    .joinToString("\n") { it.text ?: "" }
            }

            return McpChatResponse(
                response = finalResponse,
                timestamp = java.time.Instant.now().toString(),
                toolsUsed = if (toolsUsed.isEmpty()) null else toolsUsed,
                inputTokens = anthropicResponse.usage.input_tokens,
                outputTokens = anthropicResponse.usage.output_tokens,
                mcpResults = if (mcpResults.isEmpty()) null else mcpResults
            )

        } catch (e: Exception) {
            return McpChatResponse(
                response = "Ошибка при обработке запроса: ${e.message}",
                timestamp = java.time.Instant.now().toString(),
                mcpResults = listOf(
                    McpToolResult(
                        tool = "error",
                        parameters = buildJsonObject {},
                        result = e.message ?: "Unknown error",
                        success = false,
                        timestamp = java.time.Instant.now().toString()
                    )
                )
            )
        }
    }

    fun close() {
        mcpServer.close()
    }
}