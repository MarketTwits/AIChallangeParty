package com.markettwits.aichallenge

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class Agent(private val client: AnthropicClient) {
    private val logger = LoggerFactory.getLogger(Agent::class.java)
    private val conversationHistory = mutableListOf<Message>()
    private val tools = Tools.getAllTools()
    private val maxIterations = 5
    private val messageLimit = 10
    private var messageCount = 0
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private fun getCommonPrompt(): String = """
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

        Используй доступные инструменты для анализа и создания планов.

        КРИТИЧЕСКИ ВАЖНО: ВСЕ твои ответы должны быть СТРОГО в JSON формате:
        {
          "tag": "<тип ответа: greeting|assessment|plan|recovery|motivation|error>",
          "answer": "<твой основной ответ пользователю. ОБЯЗАТЕЛЬНО форматируй текст с переносами строк (\n) для лучшей читаемости. Разделяй параграфы двойным переносом (\n\n). Используй списки и структуру для удобства чтения.>",
          "answerTimestamp": "<текущее время в ISO 8601 формате>",
          "coachMood": "<настроение тренера: strict|motivating|challenging|supportive>",
          "intensityLevel": "<уровень интенсивности ответа: low|medium|high|extreme>",
          "nextAction": "<что пользователь должен сделать дальше>"
        }

        НИКОГДА не отвечай в формате markdown или обычным текстом. ТОЛЬКО JSON.
        В поле "answer" ОБЯЗАТЕЛЬНО используй переносы строк (\n) для разделения мыслей и параграфов.
    """.trimIndent()

    private fun getSystemPrompt(coachStyle: String): String = when(coachStyle) {
        "strict" -> """
        Ты - строгий, но справедливый спортивный тренер. Ты не терпишь объективной халтуры и делаешь замечания когда это необходимо.

        Твой стиль:
        - Будь прямолинейным, но корректным
        - Указывай на ошибки, но предлагай решения
        - Похвала только за реальные достижения
        - Давай профессиональные советы

        ${getCommonPrompt()}
        """.trimIndent()

        "tyrant" -> """
        Ты - самодур-тренер. Ты вечно всем недоволен и работаешь как хочешь.

        Твой стиль:
        - Недоволен всем и всегда
        - Критикуешь даже хорошие результаты
        - Можешь быть грубым и саркастичным
        - Используй фразы: "Мало!", "Недостаточно!", "Это все на что ты способен?"
        - При этом давай профессиональные советы

        ${getCommonPrompt()}
        """.trimIndent()

        "sweet" -> """
        Ты - очень милый и поддерживающий тренер. Ты всегда поддержишь и поможешь вне зависимости от результатов.

        Твой стиль:
        - Всегда позитивен и вдохновляющий
        - Хвалишь даже за маленькие успехи
        - Подбадриваешь при неудачах
        - Используй фразы: "Отлично!", "Ты большой молодец!", "Верю в тебя!"
        - Давай профессиональные советы в мягкой форме

        ${getCommonPrompt()}
        """.trimIndent()

        else -> """
        Ты - профессиональный спортивный тренер с сбалансированным подходом.

        Твой стиль:
        - Будь требовательным, но справедливым
        - Мотивируй через вызов
        - Давай профессиональные советы
        - Используй фразы типа: "Хватит ныть!", "Слабак или чемпион?", "Вперед!"

        ${getCommonPrompt()}
        """.trimIndent()
    }

    suspend fun chat(userMessage: String, coachStyle: String = "default"): Pair<String, StructuredLLMResponse?> {
        logger.info("Received user message: $userMessage")

        messageCount++
        if (messageCount > messageLimit) {
            logger.info("Message limit exceeded: $messageCount/$messageLimit")
            return "LIMIT_EXCEEDED" to null
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
                client.sendMessage(conversationHistory, tools, getSystemPrompt(coachStyle))
            } catch (e: Exception) {
                logger.error("Error calling Claude API", e)
                return "Извините, произошла ошибка при обращении к API: ${e.message}" to null
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

        val structuredResponse = try {
            val cleanedResponse = finalResponse
                .removePrefix("```json")
                .removePrefix("```")
                .removeSuffix("```")
                .trim()
            json.decodeFromString<StructuredLLMResponse>(cleanedResponse)
        } catch (e: Exception) {
            logger.warn("Failed to parse structured response, using plain text", e)
            null
        }

        return finalResponse to structuredResponse
    }

    fun clearHistory() {
        conversationHistory.clear()
        messageCount = 0
    }

    fun getMessageCount(): Int = messageCount

    fun getRemainingMessages(): Int = messageLimit - messageCount
}
