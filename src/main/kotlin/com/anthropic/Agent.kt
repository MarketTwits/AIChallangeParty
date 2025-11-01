package com.anthropic

import org.slf4j.LoggerFactory

class Agent(private val client: AnthropicClient) {
    private val logger = LoggerFactory.getLogger(Agent::class.java)
    private val conversationHistory = mutableListOf<Message>()
    private val tools = Tools.getAllTools()
    private val maxIterations = 5
    private val messageLimit = 10
    private var messageCount = 0
    private val systemPrompt = """
        Ты - профессиональный виртуальный тренер по бегу и спортивной подготовке.

        ВАЖНО: Отвечай ТОЛЬКО на вопросы о:
        - Беге и беговых тренировках
        - Спортивной подготовке и фитнесе
        - Питании для спортсменов
        - Восстановлении после тренировок
        - Профилактике травм
        - Технике бега
        - Подготовке к забегам (5к, 10к, полумарафон, марафон)

        На ВСЕ остальные темы (политика, программирование, общие вопросы и т.д.) вежливо отвечай:
        "Извините, я специализируюсь только на вопросах бега и спортивной подготовки. Пожалуйста, задайте вопрос по этой теме."

        Твоя задача:
        1. Сначала оцени физическую подготовку пользователя
        2. Предложи персональный тренировочный план
        3. Дай практические советы по восстановлению

        Будь дружелюбным, мотивирующим и профессиональным.
        Используй доступные инструменты для анализа и создания планов.
    """.trimIndent()

    suspend fun chat(userMessage: String): String {
        logger.info("Received user message: $userMessage")

        messageCount++
        if (messageCount > messageLimit) {
            logger.info("Message limit exceeded: $messageCount/$messageLimit")
            return "LIMIT_EXCEEDED"
        }

        conversationHistory.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        var iterations = 0
        var finalResponse = ""

        while (iterations < maxIterations) {
            iterations++
            logger.debug("Tool use iteration: $iterations")

            val response = try {
                client.sendMessage(conversationHistory, tools, systemPrompt)
            } catch (e: Exception) {
                logger.error("Error calling Claude API", e)
                return "Извините, произошла ошибка при обращении к API: ${e.message}"
            }

            val assistantContent = mutableListOf<ContentBlock>()
            var hasToolUse = false
            var textResponse = ""

            for (block in response.content) {
                assistantContent.add(block)

                when (block.type) {
                    "text" -> {
                        textResponse = block.text ?: ""
                    }
                    "tool_use" -> {
                        hasToolUse = true
                        val toolName = block.name ?: continue
                        val toolInput = block.input ?: continue
                        val toolUseId = block.id ?: continue

                        logger.info("Executing tool: $toolName with input: $toolInput")
                        val toolResult = Tools.executeTool(toolName, toolInput)
                        logger.debug("Tool result: $toolResult")

                        conversationHistory.add(
                            Message(
                                role = "assistant",
                                content = assistantContent.toList()
                            )
                        )

                        conversationHistory.add(
                            Message(
                                role = "user",
                                content = listOf(
                                    ContentBlock(
                                        type = "tool_result",
                                        tool_use_id = toolUseId,
                                        content = toolResult
                                    )
                                )
                            )
                        )
                    }
                }
            }

            if (!hasToolUse) {
                conversationHistory.add(
                    Message(
                        role = "assistant",
                        content = assistantContent
                    )
                )
                finalResponse = textResponse
                logger.info("Final response generated, no more tools to use")
                break
            }
        }

        if (iterations >= maxIterations) {
            finalResponse = "Превышено максимальное количество итераций обработки инструментов"
        }

        return finalResponse
    }

    fun clearHistory() {
        conversationHistory.clear()
        messageCount = 0
    }

    fun getMessageCount(): Int = messageCount

    fun getRemainingMessages(): Int = messageLimit - messageCount
}
