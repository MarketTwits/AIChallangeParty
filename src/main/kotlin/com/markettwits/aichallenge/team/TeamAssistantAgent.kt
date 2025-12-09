package com.markettwits.aichallenge.team

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import com.markettwits.aichallenge.Usage
import com.markettwits.aichallenge.tools.TeamToolManager
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Team Assistant Agent powered by Claude
 * Helps with project management, task tracking, and provides recommendations
 */
class TeamAssistantAgent(
    private val anthropicClient: AnthropicClient,
    private val teamToolManager: TeamToolManager,
) {
    private val logger = LoggerFactory.getLogger(TeamAssistantAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val maxIterations = 10

    private val conversationHistories = mutableMapOf<String, MutableList<Message>>()

    private fun getSystemPrompt(): String = """
        You are an AI team assistant helping with support ticket management and project coordination.

        YOUR CAPABILITIES:
        1. **Ticket Processing**: Process support requests, filter spam, assign priorities
        2. **Ticket Management**: View, update, delete, and track support tickets
        3. **Analytics**: Provide statistics and insights about ticket status
        4. **Documentation**: Search project documentation to answer questions

        AVAILABLE TOOLS:
        - process_support_requests: Process pending support requests from file, filter spam, assign priorities
        - get_tickets: List tickets with optional filters (status, priority, category)
        - update_ticket: Update ticket properties (status, priority, assignee, notes)
        - delete_ticket: Remove tickets
        - search_project_docs: Search project documentation

        GUIDELINES:
        1. When asked to "process requests" or "analyze new tickets", use process_support_requests
        2. For ticket lists, use get_tickets with appropriate filters
        3. When recommending priorities, consider:
           - CRITICAL tickets should be handled first
           - HIGH priority tickets are important
           - User errors need quick resolution
           - Spam is already filtered out

        COMMUNICATION STYLE:
        - Concise and action-oriented
        - Use bullet points for clarity
        - Use emoji indicators (🔴 Critical, 🟠 High, 🟡 Medium, 🟢 Low)
        - Provide clear next steps

        PRIORITY HANDLING:
        1. CRITICAL tickets - immediate attention (service down, data loss, security)
        2. HIGH priority - important bugs, payment issues, blocking issues
        3. MEDIUM - normal requests, questions, minor bugs
        4. LOW - suggestions, cosmetic issues

        TICKET CATEGORIES:
        - bug: Technical issues
        - question: User inquiries
        - feature: Feature requests
        - auth: Authentication/authorization issues
        - billing: Payment/subscription issues
        - other: Miscellaneous

        Always provide actionable insights and clear recommendations.
    """.trimIndent()

    private fun getConversationHistory(sessionId: String): MutableList<Message> {
        return conversationHistories.getOrPut(sessionId) { mutableListOf() }
    }

    suspend fun chat(
        userMessage: String,
        sessionId: String = "default",
    ): Triple<String, List<String>, Usage?> {
        logger.info("TeamAssistantAgent received message: $userMessage (session: $sessionId)")

        val conversationHistory = getConversationHistory(sessionId)

        // Add user message to history
        val userMessageObj = Message(
            role = "user",
            content = listOf(ContentBlock(type = "text", text = userMessage))
        )
        conversationHistory.add(userMessageObj)

        var iterations = 0
        var finalResponse = ""
        var lastUsage: Usage? = null
        val toolsUsed = mutableListOf<String>()

        // Get tools from TeamToolManager
        val tools = teamToolManager.getClaudeToolDefinitions()

        while (iterations < maxIterations) {
            iterations++
            logger.debug("Tool use iteration: $iterations")

            val response = try {
                val apiResponse = anthropicClient.sendMessage(
                    messages = conversationHistory,
                    tools = tools,
                    systemPrompt = getSystemPrompt()
                )
                lastUsage = apiResponse.usage
                apiResponse
            } catch (e: Exception) {
                logger.error("Error calling Claude API", e)
                return Triple(
                    "Sorry, I encountered an error while processing your request: ${e.message}",
                    toolsUsed,
                    null
                )
            }

            val assistantContent = mutableListOf<ContentBlock>()
            val toolResults = mutableListOf<ContentBlock>()
            var hasToolUse = false
            var textResponse = ""

            for (block in response.content) {
                when (block.type) {
                    "text" -> {
                        assistantContent.add(block)
                        textResponse = block.text ?: ""
                        // Save text response even if there are tools
                        if (textResponse.isNotEmpty()) {
                            finalResponse = textResponse
                        }
                    }

                    "tool_use" -> {
                        val toolName = block.name
                        val toolInput = block.input
                        val toolUseId = block.id

                        if (toolName == null || toolInput == null || toolUseId == null) {
                            logger.error("Invalid tool_use block: name=$toolName, input=$toolInput, id=$toolUseId")
                            continue
                        }

                        hasToolUse = true
                        assistantContent.add(block)
                        toolsUsed.add(toolName)

                        logger.info("Executing team tool: $toolName")

                        // Execute tool via TeamToolManager
                        val toolResult = try {
                            val result = teamToolManager.executeTool(toolName, toolInput)
                            result.toJsonString()
                        } catch (e: Exception) {
                            logger.error("Error executing tool $toolName", e)
                            """{"error": "Failed to execute tool: ${e.message}"}"""
                        }

                        logger.debug("Tool result: ${toolResult.take(200)}...")

                        toolResults.add(
                            ContentBlock(
                                type = "tool_result",
                                tool_use_id = toolUseId,
                                content = toolResult
                            )
                        )
                    }
                }
            }

            if (hasToolUse) {
                // Add assistant message with tool_use
                conversationHistory.add(Message(role = "assistant", content = assistantContent))

                // Add user message with tool_results
                conversationHistory.add(Message(role = "user", content = toolResults))
            } else {
                // No more tools to use, this is the final response
                conversationHistory.add(Message(role = "assistant", content = assistantContent))
                finalResponse = textResponse
                break
            }
        }

        if (iterations >= maxIterations && finalResponse.isEmpty()) {
            logger.warn("Max iterations reached with empty finalResponse")
            finalResponse = "Sorry, I was unable to complete your request after $maxIterations iterations."
        }

        logger.info("Chat completed: iterations=$iterations, response length=${finalResponse.length}, tools used: $toolsUsed")

        return Triple(finalResponse, toolsUsed, lastUsage)
    }

    /**
     * Clear conversation history for a session
     */
    fun clearHistory(sessionId: String) {
        conversationHistories.remove(sessionId)
        logger.info("Cleared conversation history for session: $sessionId")
    }

    /**
     * Get conversation history for a session
     */
    fun getHistory(sessionId: String): List<Message> {
        return conversationHistories[sessionId] ?: emptyList()
    }

    /**
     * Get all active sessions
     */
    fun getActiveSessions(): List<String> {
        return conversationHistories.keys.toList()
    }
}