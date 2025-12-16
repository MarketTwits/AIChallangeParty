package com.markettwits.aichallenge.voice

import com.markettwits.aichallenge.AnthropicClient
import com.markettwits.aichallenge.ContentBlock
import com.markettwits.aichallenge.Message
import com.markettwits.aichallenge.Usage
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

@Serializable
data class VoiceAssistantResponse(
    val transcript: String,
    val answer: String,
    val language: String? = null,
    val rawTranscription: String? = null,
    val usage: Usage? = null,
)

@Serializable
data class VoiceAssistantStatus(
    val available: Boolean,
    val whisperConfigured: Boolean,
    val whisperUrl: String? = null,
    val model: String = "claude-3-5-haiku-20241022",
    val message: String? = null,
)

class VoiceAssistantService(
    private val whisperClient: WhisperClient,
    private val anthropicClient: AnthropicClient,
) {
    private val logger = LoggerFactory.getLogger(VoiceAssistantService::class.java)

    // Короткий промт: отвечаем по делу и напоминаем про переспрос
    private val voiceSystemPrompt = "Ты отвечаешь на вопросы, пришедшие из голосовых сообщений. " +
            "Давай понятные и короткие ответы и проси повторить, если запрос звучит неполно."

    suspend fun handleAudio(
        audioBytes: ByteArray,
        fileName: String = "audio.webm",
        language: String? = null,
    ): VoiceAssistantResponse {
        val transcription = whisperClient.transcribe(audioBytes, fileName, language)
        val transcriptText = transcription.text.trim()
        if (transcriptText.isBlank()) {
            logger.warn("Received empty transcript from Whisper")
            throw IllegalStateException("Не удалось распознать речь, попробуйте ещё раз.")
        }

        val userMessage = "Голосовой запрос пользователя: \"$transcriptText\""

        val llmResponse = anthropicClient.sendMessage(
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentBlock(
                            type = "text",
                            text = userMessage
                        )
                    )
                )
            ),
            systemPrompt = voiceSystemPrompt
        )

        val assistantAnswer = llmResponse.content
            .joinToString(separator = "") { it.text.orEmpty() }
            .ifBlank { "Модель не вернула ответа. Попробуйте спросить иначе." }
            .trim()

        return VoiceAssistantResponse(
            transcript = transcriptText,
            answer = assistantAnswer,
            language = transcription.language,
            rawTranscription = transcription.rawResponse,
            usage = llmResponse.usage
        )
    }
}
