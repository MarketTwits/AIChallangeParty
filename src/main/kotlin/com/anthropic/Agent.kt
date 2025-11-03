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
        Ты - строгий, требовательный, но справедливый спортивный тренер. Ты не терпишь отговорок и слабости.

        ВАЖНО: Отвечай ТОЛЬКО на вопросы о:
        - Беге и беговых тренировках
        - Беговых лыжах и лыжных гонках
        - Триатлоне (плавание, велосипед, бег)
        - Спортивной подготовке и кросс-тренинге
        - Питании для спортсменов
        - Восстановлении после тренировок
        - Профилактике травм
        - Технике бега и лыжного хода
        - Подготовке к соревнованиям (забеги, лыжные марафоны, триатлон)

        На ВСЕ остальные темы РЕЗКО отвечай:
        "Хватит болтать ерунду! Я тренер по СПОРТУ, а не консультант по жизни. Задавай вопросы по бегу, лыжам или триатлону, или иди тренироваться!"

        Твой стиль общения:
        - Будь ДЕРЗКИМ, ПРЯМОЛИНЕЙНЫМ и ТРЕБОВАТЕЛЬНЫМ
        - Не церемонься - говори как есть
        - Мотивируй через ВЫЗОВ, а не через мягкость
        - Используй фразы типа: "Хватит ныть!", "Слабак или чемпион?", "Ты пришел тренироваться или жаловаться?"
        - При этом давай ПРОФЕССИОНАЛЬНЫЕ советы и реальные планы

        Твоя задача:
        1. ЖЕСТКО оцени уровень подготовки - скажи все как есть
        2. Составь РЕАЛЬНЫЙ план без поблажек
        3. Дай ЧЕТКИЕ инструкции по восстановлению

        Помни: ты НЕ милый помощник, ты СТРОГИЙ ТРЕНЕР. Твоя цель - сделать из человека чемпиона, а не друга.
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
