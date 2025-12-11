package com.markettwits.aichallenge

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

const val LOCAL_COACH_PROMPT_VERSION = "coach_v2"

@Serializable
data class LocalChatRequest(
    val sessionId: String,
    val message: String,
    val temperature: Double = 0.35,
    val model: String? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val contextMessages: Int? = null,
)

@Serializable
data class LocalChatResponse(
    val response: String,
    val sessionId: String,
    val messageCount: Int,
    val model: String? = null,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val topP: Double? = null,
    val presencePenalty: Double? = null,
    val frequencyPenalty: Double? = null,
    val contextMessages: Int? = null,
    val promptVersion: String = LOCAL_COACH_PROMPT_VERSION,
    val usage: LMStudioUsage? = null,
    val timestamp: Long = System.currentTimeMillis(),
)

@Serializable
data class LocalModelsResponse(
    val models: List<String>,
    val count: Int,
    val error: String? = null,
)

@Serializable
data class LocalStatusResponse(
    val available: Boolean,
    val sessionCount: Int = 0,
    val message: String? = null,
)

class LocalCoachAgent(private val lmStudioClient: LMStudioClient) {
    private val logger = LoggerFactory.getLogger(LocalCoachAgent::class.java)

    // Simple in-memory session storage
    private val sessions = mutableMapOf<String, MutableList<LMStudioMessage>>()

    companion object {
        private const val PROMPT_VERSION = LOCAL_COACH_PROMPT_VERSION
        private const val DEFAULT_CONTEXT_MESSAGES = 8
        private const val DEFAULT_MAX_TOKENS = 700
    }

    private val systemPrompt = """
        Ты — локальный русскоязычный тренер по бегу. Цель — давать точные и краткие рекомендации по тренировкам.

        Правила работы:
        - Всегда уточняй уровень (опыт, темп, объем, цель старта) и состояние здоровья, если данных нет.
        - Отвечай структурно: 
          1) Коротко: 1–2 предложения с основным ответом
          2) План: 3–5 пунктов конкретных действий на ближайшие тренировки
          3) Контроль: 1–2 метрики (пульс, RPE, темп мин/км)
        - Используй метрики в формате: км, мин/км, пульс, RPE. Избегай медицинских советов.
        - Если информации мало, задай 1 уточняющий вопрос и предложи минимальный шаг.

        Отвечай мотивирующе, но без лишней воды. Ты работаешь локально, поэтому действуешь быстро и бережно к ресурсам.
    """.trimIndent()

    suspend fun chat(
        sessionId: String,
        userMessage: String,
        temperature: Double = 0.35,
        modelName: String? = null,
        maxTokens: Int? = null,
        topP: Double? = null,
        presencePenalty: Double? = null,
        frequencyPenalty: Double? = null,
        contextMessages: Int? = null,
    ): LocalChatResponse {
        val usedTemperature = temperature.coerceIn(0.0, 2.0)
        val contextLimit = contextMessages?.coerceIn(2, 24) ?: DEFAULT_CONTEXT_MESSAGES
        val usedMaxTokens = maxTokens?.coerceIn(64, 4096) ?: DEFAULT_MAX_TOKENS
        val usedTopP = topP?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)
        val usedPresencePenalty = presencePenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0)
        val usedFrequencyPenalty = frequencyPenalty?.takeIf { it.isFinite() }?.coerceIn(-2.0, 2.0)

        logger.info(
            "Local coach chat - Session: $sessionId, Model: ${modelName ?: "default"}, Temp: $usedTemperature, maxTokens: $usedMaxTokens, Message: ${
                userMessage.take(
                    50
                )
            }..."
        )

        // Get or create session
        val messages = sessions.getOrPut(sessionId) {
            mutableListOf(
                LMStudioMessage(role = "system", content = systemPrompt)
            )
        }

        // Add user message
        messages.add(LMStudioMessage(role = "user", content = userMessage))

        // Get response from LM Studio
        val result = lmStudioClient.chat(
            messages = applyContextLimit(messages, contextLimit),
            temperature = usedTemperature,
            modelName = modelName,
            maxTokens = usedMaxTokens,
            topP = usedTopP,
            presencePenalty = usedPresencePenalty,
            frequencyPenalty = usedFrequencyPenalty
        )

        // Add assistant response to history
        messages.add(LMStudioMessage(role = "assistant", content = result.reply))

        // Keep only last N messages (plus system prompt) to avoid memory issues
        applyContextLimit(messages, contextLimit)

        val messagePairs = (messages.size - 1) / 2 // Subtract system message, divide by 2 for pairs

        if (result.error != null) {
            logger.warn("Local coach received error from LM Studio: ${result.error}")
        }

        return LocalChatResponse(
            response = result.reply,
            sessionId = sessionId,
            messageCount = messagePairs, // Subtract system message, divide by 2 for pairs
            model = result.modelUsed,
            temperature = usedTemperature,
            maxTokens = usedMaxTokens,
            topP = usedTopP,
            presencePenalty = usedPresencePenalty,
            frequencyPenalty = usedFrequencyPenalty,
            contextMessages = contextLimit,
            usage = result.usage
        )
    }

    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
        logger.info("Cleared session: $sessionId")
    }

    fun getSessionCount(): Int = sessions.size

    fun getMessageCount(sessionId: String): Int {
        val messages = sessions[sessionId] ?: return 0
        return (messages.size - 1) / 2 // Subtract system message
    }

    private fun applyContextLimit(
        messages: MutableList<LMStudioMessage>,
        contextLimit: Int,
    ): MutableList<LMStudioMessage> {
        if (messages.isEmpty()) return messages

        val allowedMessages = contextLimit * 2 // user + assistant pairs
        if (messages.size - 1 > allowedMessages) {
            val systemMessage = messages.first()
            val recentMessages = messages.takeLast(allowedMessages)
            messages.clear()
            messages.add(systemMessage)
            messages.addAll(recentMessages)
        }
        return messages
    }
}
