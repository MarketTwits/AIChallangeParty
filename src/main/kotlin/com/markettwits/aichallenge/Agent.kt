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

        СИСТЕМА СБОРА ДАННЫХ ДЛЯ ТЕХНИЧЕСКОГО ЗАДАНИЯ:

        Твоя задача - собрать ПОЛНУЮ информацию о пользователе через диалог, а затем создать для него техническое задание на тренировочный план.

        ГРАНИЧНЫЕ УСЛОВИЯ (когда ты должен ОСТАНОВИТЬСЯ и создать ТЗ):
        Ты ОБЯЗАН собрать следующие МИНИМАЛЬНЫЕ данные перед генерацией ТЗ:
        1. Возраст пользователя (age)
        2. Опыт бега (running_experience: beginner/intermediate/advanced)
        3. Количество пробежек в неделю (weekly_runs) - если не бегает, укажи 0
        4. Максимальная дистанция (max_distance_km) - если не бегал, укажи 0
        5. Цель тренировок (goal: endurance/speed/5k/10k/half_marathon)
        6. Желаемое количество тренировочных дней (days_per_week: 3-6)

        АЛГОРИТМ РАБОТЫ:
        1. Начни с приветствия и задай вопросы для сбора 6 обязательных параметров
        2. После получения всех 6 параметров СРАЗУ вызови assess_fitness_level
        3. Затем вызови generate_training_plan с полученными данными
        4. Затем вызови get_recovery_recommendations
        5. Верни пользователю ПОЛНЫЙ тренировочный план
        6. После того как план создан и показан пользователю, больше НЕ задавай вопросов
        7. На любое сообщение после создания плана отвечай: "План уже создан! Если хочешь новый план - начни новую сессию."

        СТОП-УСЛОВИЯ (когда прекратить диалог):
        - После создания тренировочного плана
        - Если пользователь говорит "стоп", "хватит", "передумал", "не хочу"
        - Если пользователь задаёт не относящиеся к спорту вопросы после создания плана

        НЕ ГЕНЕРИРУЙ план, пока не собраны ВСЕ 6 обязательных параметров!

        ПРИМЕР ХОРОШЕГО ДИАЛОГА:
        User: "Хочу начать бегать"
        You: "Отлично! Сколько тебе лет и есть ли опыт бега? Сколько раз в неделю готов тренироваться и какая цель?" → собрано 0/6
        User: "Мне 30, раньше не бегал, хочу тренироваться 3 раза в неделю, цель - пробежать 5км"
        → Собрано 6/6! Вызываю assess_fitness_level → generate_training_plan → get_recovery_recommendations!
        You: [показываешь план]
        User: "А что ещё?"
        You: "План уже создан! Если хочешь новый план - начни новую сессию."

        КРИТИЧЕСКИ ВАЖНО: ВСЕ твои ответы должны быть СТРОГО в JSON формате:
        {
          "tag": "<тип ответа: greeting|assessment|plan|recovery|motivation|error|stop>",
          "answer": "<твой основной ответ пользователю. ОБЯЗАТЕЛЬНО форматируй текст с переносами строк (\n) для лучшей читаемости. Разделяй параграфы двойным переносом (\n\n). Используй списки и структуру для удобства чтения.>",
          "answerTimestamp": "<текущее время в ISO 8601 формате>",
          "coachMood": "<настроение тренера: strict|motivating|challenging|supportive>",
          "intensityLevel": "<уровень интенсивности ответа: low|medium|high|extreme>",
          "nextAction": "<что пользователь должен сделать дальше>",
          "planCreated": <true если план уже создан, false если нет>
        }

        ВАЖНО О ПОЛЕ "answer":
        - В поле "answer" пиши ТОЛЬКО текст для пользователя, а НЕ сырые JSON данные из инструментов
        - Когда ты получаешь результаты от инструментов (assess_fitness_level, generate_training_plan, get_recovery_recommendations),
          ты ДОЛЖЕН преобразовать их в читаемый текст для пользователя
        - НИКОГДА не копируй JSON объекты из результатов инструментов напрямую в поле "answer"
        - Вместо этого, интерпретируй данные и напиши человекочитаемый текст

        ПРИМЕР ПРАВИЛЬНОГО ОТВЕТА после получения плана:
        {
          "tag": "plan",
          "answer": "Отлично! Я составил для тебя 4-недельный план:\n\nНеделя 1:\nПонедельник - Легкая пробежка 2 км\nСреда - Интервалы 4x(1мин бег, 2мин ходьба)...",
          "planCreated": true
        }

        ПРИМЕР НЕПРАВИЛЬНОГО ОТВЕТА (НИКОГДА так не делай):
        {
          "tag": "plan",
          "answer": "{\"week_1\":{\"Monday\":\"...\"}, \"week_2\":...}",
          "planCreated": true
        }

        Если tag = "stop" или planCreated = true, НЕ задавай больше вопросов!

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
        var lastTextResponse = ""

        while (iterations < maxIterations) {
            iterations++
            logger.debug("Tool use iteration: $iterations")

            // Validate conversation history before sending
            val validationError = validateConversationHistory()
            if (validationError != null) {
                logger.error("Conversation history validation failed: $validationError")
                logger.error("Full history: ${conversationHistory.mapIndexed { idx, msg ->
                    "[$idx] role=${msg.role}, content=${msg.content.map { c ->
                        when(c.type) {
                            "text" -> "text"
                            "tool_use" -> "tool_use[${c.id}:${c.name}]"
                            "tool_result" -> "tool_result[for:${c.tool_use_id}]"
                            else -> c.type
                        }
                    }}"
                }}")
                return "Ошибка валидации истории диалога: $validationError" to null
            }

            logger.info("Sending request with ${conversationHistory.size} messages in history")

            val response = try {
                client.sendMessage(conversationHistory, tools, getSystemPrompt(coachStyle))
            } catch (e: Exception) {
                logger.error("Error calling Claude API", e)
                logger.error("Conversation history at error: ${conversationHistory.mapIndexed { idx, msg ->
                    "[$idx] role=${msg.role}, content=${msg.content.map { c ->
                        when(c.type) {
                            "text" -> "text"
                            "tool_use" -> "tool_use[${c.id}:${c.name}]"
                            "tool_result" -> "tool_result[for:${c.tool_use_id}]"
                            else -> c.type
                        }
                    }}"
                }}")
                return "Извините, произошла ошибка при обращении к API: ${e.message}" to null
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
                        lastTextResponse = textResponse
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

                        logger.info("Executing tool: $toolName with input: $toolInput")
                        val toolResult = Tools.executeTool(toolName, toolInput)
                        logger.debug("Tool result: $toolResult")

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
                conversationHistory.add(
                    Message(
                        role = "assistant",
                        content = assistantContent
                    )
                )

                conversationHistory.add(
                    Message(
                        role = "user",
                        content = toolResults
                    )
                )
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
            logger.warn("Max iterations reached, using last text response")
            if (finalResponse.isEmpty() && lastTextResponse.isNotEmpty()) {
                finalResponse = lastTextResponse
                conversationHistory.add(
                    Message(
                        role = "assistant",
                        content = listOf(ContentBlock(type = "text", text = lastTextResponse))
                    )
                )
            } else if (finalResponse.isEmpty()) {
                finalResponse = "Превышено максимальное количество итераций обработки инструментов"
            }
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

    private fun validateConversationHistory(): String? {
        val toolUseIds = mutableSetOf<String>()

        for (i in conversationHistory.indices) {
            val msg = conversationHistory[i]

            // Collect tool_use ids from assistant messages
            if (msg.role == "assistant") {
                for (block in msg.content) {
                    if (block.type == "tool_use") {
                        val id = block.id
                        if (id != null) {
                            toolUseIds.add(id)
                        }
                    }
                }

                // If assistant message has tool_use, next message MUST be user with tool_results
                val hasToolUse = msg.content.any { it.type == "tool_use" }
                if (hasToolUse) {
                    if (i + 1 >= conversationHistory.size) {
                        return "Assistant message at index $i has tool_use but no following user message with tool_results"
                    }

                    val nextMsg = conversationHistory[i + 1]
                    if (nextMsg.role != "user") {
                        return "Assistant message at index $i has tool_use but next message is not user (it's ${nextMsg.role})"
                    }

                    val hasToolResults = nextMsg.content.any { it.type == "tool_result" }
                    if (!hasToolResults) {
                        return "Assistant message at index $i has tool_use but next user message has no tool_results"
                    }

                    // Check that all tool_use ids have corresponding tool_results
                    val toolUseIdsInMsg = msg.content.filter { it.type == "tool_use" }.mapNotNull { it.id }.toSet()
                    val toolResultIds = nextMsg.content.filter { it.type == "tool_result" }.mapNotNull { it.tool_use_id }.toSet()

                    val missingResults = toolUseIdsInMsg - toolResultIds
                    if (missingResults.isNotEmpty()) {
                        return "Assistant message at index $i has tool_use ids $missingResults without corresponding tool_results in next message"
                    }
                }
            }
        }

        return null
    }

    fun clearHistory() {
        conversationHistory.clear()
        messageCount = 0
    }

    fun getMessageCount(): Int = messageCount

    fun getRemainingMessages(): Int = messageLimit - messageCount
}
