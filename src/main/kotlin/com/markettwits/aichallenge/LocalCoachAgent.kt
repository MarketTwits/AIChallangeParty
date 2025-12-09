package com.markettwits.aichallenge

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class LocalChatRequest(
    val sessionId: String,
    val message: String,
    val temperature: Double = 0.7,
    val model: String? = null,
)

@Serializable
data class LocalChatResponse(
    val response: String,
    val sessionId: String,
    val messageCount: Int,
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

    private val systemPrompt = """
        Ты - дружелюбный AI-тренер по бегу и спорту. Твоя задача помогать людям с тренировками и советами по бегу.

        Отвечай кратко и по существу. Давай практические советы.
        Будь позитивным и мотивирующим, но не слишком многословным.

        Если пользователь спрашивает о:
        - Тренировочном плане - предложи базовый план на основе его уровня
        - Технике бега - дай основные советы
        - Питании - дай базовые рекомендации для бегунов
        - Восстановлении - посоветуй отдых и растяжку

        Помни, что ты работаешь локально, поэтому отвечай быстро и эффективно.
    """.trimIndent()

    suspend fun chat(
        sessionId: String,
        userMessage: String,
        temperature: Double = 0.7,
        modelName: String? = null,
    ): LocalChatResponse {
        logger.info(
            "Local coach chat - Session: $sessionId, Model: ${modelName ?: "default"}, Message: ${
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
        val response = lmStudioClient.chat(messages, temperature, modelName)

        // Add assistant response to history
        messages.add(LMStudioMessage(role = "assistant", content = response))

        // Keep only last 10 messages (plus system prompt) to avoid memory issues
        if (messages.size > 21) { // system + 10 pairs
            val systemMsg = messages[0]
            val recentMessages = messages.takeLast(20)
            messages.clear()
            messages.add(systemMsg)
            messages.addAll(recentMessages)
        }

        return LocalChatResponse(
            response = response,
            sessionId = sessionId,
            messageCount = (messages.size - 1) / 2 // Subtract system message, divide by 2 for pairs
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
}
