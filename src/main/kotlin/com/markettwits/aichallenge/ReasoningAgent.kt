package com.markettwits.aichallenge

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory
import java.time.Instant

class ReasoningAgent(private val client: AnthropicClient) {
    private val logger = LoggerFactory.getLogger(ReasoningAgent::class.java)
    private val conversationHistory = mutableMapOf<String, MutableList<Message>>()
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val maxContextTokens = mutableMapOf<String, Int?>()
    private val totalInputTokens = mutableMapOf<String, Int>()

    suspend fun chat(
        userMessage: String,
        sessionId: String,
        reasoningMode: String,
        temperature: Double? = null,
        contextLimit: Int? = null,
    ): ReasoningChatResponse {
        logger.info("Received message in reasoning mode: $reasoningMode, temperature: $temperature, contextLimit: $contextLimit")

        if (contextLimit != null && maxContextTokens[sessionId] == null) {
            maxContextTokens[sessionId] = contextLimit
            logger.info("Context limit set to $contextLimit tokens for session $sessionId")
        }

        totalInputTokens.getOrDefault(sessionId, 0)
        maxContextTokens[sessionId]

        val history = conversationHistory.getOrPut(sessionId) { mutableListOf() }

        val response = when (reasoningMode) {
            "direct" -> directResponse(userMessage, history, temperature, sessionId)
            "stepByStep" -> stepByStepResponse(userMessage, history, temperature, sessionId)
            "aiPrompt" -> aiPromptResponse(userMessage, history, temperature, sessionId)
            "experts" -> expertsResponse(userMessage, history, temperature, sessionId)
            "tokenizer" -> tokenizerResponse(userMessage, history, temperature, sessionId)
            else -> directResponse(userMessage, history, temperature, sessionId)
        }

        return response
    }

    private suspend fun directResponse(
        userMessage: String,
        history: MutableList<Message>,
        temperature: Double? = null,
        sessionId: String,
    ): ReasoningChatResponse {
        logger.info("Using direct response mode with temperature: $temperature")

        history.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val systemPrompt = "Ты - AI ассистент. Отвечай на вопросы кратко и по существу."

        val response = client.sendMessage(history, emptyList(), systemPrompt, temperature)

        totalInputTokens[sessionId] = totalInputTokens.getOrDefault(sessionId, 0) + response.usage.input_tokens

        val textResponse = response.content.firstOrNull { it.type == "text" }?.text ?: "Нет ответа"

        history.add(
            Message(
                role = "assistant",
                content = listOf(ContentBlock(type = "text", text = textResponse))
            )
        )

        return ReasoningChatResponse(
            response = textResponse,
            reasoningMode = "direct",
            timestamp = Instant.now().toString(),
            inputTokens = response.usage.input_tokens,
            outputTokens = response.usage.output_tokens,
            totalInputTokens = totalInputTokens[sessionId],
            contextLimit = maxContextTokens[sessionId]
        )
    }

    private suspend fun stepByStepResponse(
        userMessage: String,
        history: MutableList<Message>,
        temperature: Double? = null,
        sessionId: String,
    ): ReasoningChatResponse {
        logger.info("Using step-by-step response mode with temperature: $temperature")

        history.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val systemPrompt = """
            Ты - AI ассистент. Отвечай на вопросы пошагово.

            ВАЖНО: Твой ответ должен быть структурирован следующим образом:
            1. Разбей задачу на понятные шаги
            2. Опиши каждый шаг подробно
            3. Сделай вывод

            Используй формат:
            Шаг 1: [описание]
            Шаг 2: [описание]
            ...
            Вывод: [финальный ответ]
        """.trimIndent()

        val response = client.sendMessage(history, emptyList(), systemPrompt, temperature)

        val textResponse = response.content.firstOrNull { it.type == "text" }?.text ?: "Нет ответа"

        history.add(
            Message(
                role = "assistant",
                content = listOf(ContentBlock(type = "text", text = textResponse))
            )
        )

        return ReasoningChatResponse(
            response = textResponse,
            reasoningMode = "stepByStep",
            timestamp = Instant.now().toString()
        )
    }

    private suspend fun aiPromptResponse(
        userMessage: String,
        history: MutableList<Message>,
        temperature: Double? = null,
        sessionId: String,
    ): ReasoningChatResponse {
        logger.info("Using AI prompt generation mode with temperature: $temperature")

        val promptGenerationRequest = "Создай оптимальный промпт для решения следующей задачи: $userMessage"

        val tempHistory = mutableListOf<Message>()
        tempHistory.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = promptGenerationRequest))
            )
        )

        val systemPrompt1 = """
            Ты - эксперт по созданию промптов для AI. Твоя задача - создать эффективный промпт для решения задачи пользователя.
            Ответь ТОЛЬКО промптом, без дополнительных объяснений.
        """.trimIndent()

        val promptResponse = client.sendMessage(tempHistory, emptyList(), systemPrompt1, temperature)
        val generatedPrompt = promptResponse.content.firstOrNull { it.type == "text" }?.text ?: userMessage

        logger.info("Generated prompt: $generatedPrompt")

        history.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val finalHistory = mutableListOf<Message>()
        finalHistory.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val response = client.sendMessage(finalHistory, emptyList(), generatedPrompt, temperature)

        val textResponse = response.content.firstOrNull { it.type == "text" }?.text ?: "Нет ответа"

        history.add(
            Message(
                role = "assistant",
                content = listOf(
                    ContentBlock(
                        type = "text",
                        text = "Сгенерированный промпт:\n$generatedPrompt\n\nОтвет:\n$textResponse"
                    )
                )
            )
        )

        return ReasoningChatResponse(
            response = "Сгенерированный промпт:\n$generatedPrompt\n\nОтвет:\n$textResponse",
            reasoningMode = "aiPrompt",
            timestamp = Instant.now().toString()
        )
    }

    private suspend fun expertsResponse(
        userMessage: String,
        history: MutableList<Message>,
        temperature: Double? = null,
        sessionId: String,
    ): ReasoningChatResponse {
        logger.info("Using experts panel mode with temperature: $temperature")

        history.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val experts = listOf(
            "Логик" to "Ты - эксперт по логике и критическому мышлению. Анализируй задачи структурированно и последовательно.",
            "Креативщик" to "Ты - эксперт по креативному мышлению. Предлагай нестандартные и инновационные решения.",
            "Практик" to "Ты - эксперт по практическому применению. Фокусируйся на реализуемости и эффективности решений."
        )

        val expertOpinions = mutableListOf<ExpertOpinion>()

        for ((expertName, expertPrompt) in experts) {
            val expertHistory = mutableListOf<Message>()
            expertHistory.add(
                Message(
                    role = "user",
                    content = listOf(ContentBlock(type = "text", text = userMessage))
                )
            )

            val response = client.sendMessage(expertHistory, emptyList(), expertPrompt, temperature)
            val opinion = response.content.firstOrNull { it.type == "text" }?.text ?: "Нет мнения"

            expertOpinions.add(
                ExpertOpinion(
                    expertName = expertName,
                    opinion = opinion,
                    confidence = (70..95).random()
                )
            )

            logger.info("Expert $expertName opinion received")
        }

        val synthesisPrompt = """
            Ты - главный модератор панели экспертов. Твоя задача - синтезировать мнения экспертов в единый ответ.

            Вопрос: $userMessage

            Мнения экспертов:
            ${expertOpinions.joinToString("\n\n") { "**${it.expertName}** (уверенность: ${it.confidence}%):\n${it.opinion}" }}

            Создай финальный ответ, учитывая все мнения экспертов.
        """.trimIndent()

        val synthesisHistory = mutableListOf<Message>()
        synthesisHistory.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = synthesisPrompt))
            )
        )

        val synthesisResponse =
            client.sendMessage(synthesisHistory, emptyList(), "Ты - модератор экспертной панели.", temperature)
        val finalAnswer = synthesisResponse.content.firstOrNull { it.type == "text" }?.text ?: "Нет финального ответа"

        val fullResponse = buildString {
            appendLine("🎯 ЭКСПЕРТНАЯ ПАНЕЛЬ")
            appendLine("=".repeat(50))
            appendLine()
            expertOpinions.forEach { expert ->
                appendLine("**${expert.expertName}** (Уверенность: ${expert.confidence}%)")
                appendLine(expert.opinion)
                appendLine()
                appendLine("-".repeat(50))
                appendLine()
            }
            appendLine("📊 ФИНАЛЬНЫЙ ВЫВОД:")
            appendLine(finalAnswer)
        }

        history.add(
            Message(
                role = "assistant",
                content = listOf(ContentBlock(type = "text", text = fullResponse))
            )
        )

        return ReasoningChatResponse(
            response = fullResponse,
            reasoningMode = "experts",
            timestamp = Instant.now().toString(),
            experts = expertOpinions
        )
    }

    private suspend fun tokenizerResponse(
        userMessage: String,
        history: MutableList<Message>,
        temperature: Double? = null,
        sessionId: String,
    ): ReasoningChatResponse {
        logger.info("Using tokenizer mode")

        history.add(
            Message(
                role = "user",
                content = listOf(ContentBlock(type = "text", text = userMessage))
            )
        )

        val systemPrompt = """
            Ты - AI ассистент с функцией подсчета токенов.

            ВАЖНО: После ответа на вопрос пользователя, ты ВСЕГДА должен добавить информацию о токенах:
            - Количество входных токенов (input tokens)
            - Количество выходных токенов (output tokens)
            - Общее количество токенов за этот диалог

            Отвечай на вопрос кратко и по существу, а затем добавь статистику токенов.
        """.trimIndent()

        val response = client.sendMessage(history, emptyList(), systemPrompt, temperature)

        totalInputTokens[sessionId] = totalInputTokens.getOrDefault(sessionId, 0) + response.usage.input_tokens

        val textResponse = response.content.firstOrNull { it.type == "text" }?.text ?: "Нет ответа"

        val tokenizerInfo = buildString {
            appendLine()
            appendLine("---")
            appendLine("📊 **Статистика токенов:**")
            appendLine("- Входные токены: ${response.usage.input_tokens}")
            appendLine("- Выходные токены: ${response.usage.output_tokens}")
            appendLine("- Всего токенов в запросе: ${response.usage.input_tokens + response.usage.output_tokens}")
            appendLine("- Общий контекст диалога: ${totalInputTokens[sessionId]} токенов")
            if (maxContextTokens[sessionId] != null) {
                val percentage = (totalInputTokens[sessionId]!! * 100.0 / maxContextTokens[sessionId]!!).toInt()
                appendLine("- Использовано лимита: $percentage% (${totalInputTokens[sessionId]}/${maxContextTokens[sessionId]})")
            }
        }

        val fullResponse = textResponse + tokenizerInfo

        history.add(
            Message(
                role = "assistant",
                content = listOf(ContentBlock(type = "text", text = fullResponse))
            )
        )

        return ReasoningChatResponse(
            response = fullResponse,
            reasoningMode = "tokenizer",
            timestamp = Instant.now().toString(),
            inputTokens = response.usage.input_tokens,
            outputTokens = response.usage.output_tokens,
            totalInputTokens = totalInputTokens[sessionId],
            contextLimit = maxContextTokens[sessionId]
        )
    }

    fun clearHistory(sessionId: String) {
        conversationHistory.remove(sessionId)
        totalInputTokens.remove(sessionId)
        maxContextTokens.remove(sessionId)
    }
}
