package com.markettwits.aichallenge.club

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import com.markettwits.aichallenge.Usage
import com.markettwits.aichallenge.tools.ClubToolManager
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Support agent for SportSauce Club
 * Handles user questions using RAG + MCP tools
 */
class ClubSupportAgent(
    private val anthropicClient: AnthropicClient,
    private val clubToolManager: ClubToolManager,
) {
    private val logger = LoggerFactory.getLogger(ClubSupportAgent::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val maxIterations = 5

    private val conversationHistories = mutableMapOf<String, MutableList<Message>>()

    private fun getSystemPrompt(): String = """
        Ты - помощник службы поддержки спортивного клуба SportSauce в Новосибирске.

        ТВОЯ РОЛЬ:
        - Отвечать на вопросы о клубе SportSauce профессионально и дружелюбно
        - Помогать пользователям найти нужную информацию о тренировках, расписании, абонементах
        - Использовать доступные инструменты для получения актуальной информации

        ДОСТУПНЫЕ ИНСТРУМЕНТЫ:

        1. search_club_docs - Поиск по документации клуба (RAG)
           Используй для: общей информации, истории клуба, мероприятий, достижений

        2. get_club_trainers - Информация о тренерах
           Используй для: списка тренеров, их контактов, специализации

        3. get_club_workouts - Типы тренировок
           Используй для: видов тренировок, описания программ

        4. get_club_schedule - Расписание тренировок
           Используй для: времени тренировок, мест проведения

        5. get_club_subscriptions - Абонементы и цены
           Используй для: стоимости, типов абонементов, условий членства

        6. get_club_faq - Часто задаваемые вопросы
           Используй для: типичных вопросов пользователей

        7. get_club_settings - Настройки и контакты клуба
           Используй для: контактной информации, общих настроек

        СТРАТЕГИЯ ОТВЕТОВ:
        1. Сначала используй search_club_docs для получения общего контекста
        2. Затем используй специфичные MCP tools для получения актуальных данных
        3. Объедини информацию из разных источников в понятный ответ
        4. Если нужной информации нет - честно скажи об этом и предложи связаться с администрацией

        СТИЛЬ ОБЩЕНИЯ:
        - Дружелюбный и профессиональный
        - Структурированные ответы (используй списки, заголовки)
        - Конкретная информация (даты, цены, имена)
        - Предлагай дополнительную помощь

        ВАЖНО:
        - НЕ выдумывай информацию - используй только данные из инструментов
        - Если информация устарела - упомяни об этом
        - При упоминании цен/дат - указывай, что нужно уточнить актуальность
        - Предлагай контакты для более детальных вопросов

        ФОРМАТ ОТВЕТА:
        - Отвечай на русском языке
        - Используй emoji для улучшения читаемости
        - Структурируй ответ с помощью параграфов и списков
        - Будь кратким, но информативным
    """.trimIndent()

    private fun getConversationHistory(sessionId: String): MutableList<Message> {
        return conversationHistories.getOrPut(sessionId) { mutableListOf() }
    }

    suspend fun chat(
        userMessage: String,
        sessionId: String = "default",
    ): Triple<String, Usage?, Map<String, Any>> {
        logger.info("ClubSupportAgent received message: $userMessage (session: $sessionId)")

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

        // Get Claude tool definitions
        val tools = clubToolManager.getClaudeToolDefinitions()

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
                    "Извините, произошла ошибка при обращении к AI: ${e.message}",
                    null,
                    mapOf("error" to true, "toolsUsed" to toolsUsed)
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

                        logger.info("Executing club tool: $toolName")

                        // Execute tool via ClubToolManager
                        val toolResult = try {
                            val result = clubToolManager.executor.execute(toolName, toolInput)
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
            finalResponse = "Извините, не удалось получить ответ после ${maxIterations} итераций."
        }

        logger.info("Chat completed: iterations=$iterations, response length=${finalResponse.length}")

        val metadata = mapOf(
            "sessionId" to sessionId,
            "iterations" to iterations,
            "toolsUsed" to toolsUsed,
            "messageCount" to conversationHistory.size
        )

        return Triple(finalResponse, lastUsage, metadata)
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
