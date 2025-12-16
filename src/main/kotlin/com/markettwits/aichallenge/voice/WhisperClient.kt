package com.markettwits.aichallenge.voice

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

@Serializable
data class WhisperTranscriptionResult(
    val text: String,
    val language: String? = null,
    val rawResponse: String? = null,
)

class WhisperClient(
    private val baseUrl: String,
) {
    private val logger = LoggerFactory.getLogger(WhisperClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
        }
    }

    suspend fun transcribe(
        audioBytes: ByteArray,
        fileName: String = "audio.webm",
        language: String? = null,
    ): WhisperTranscriptionResult {
        val endpoint = baseUrl.trimEnd('/') + "/asr"
        logger.info("Sending audio (${audioBytes.size} bytes) to Whisper endpoint: $endpoint")

        val response: HttpResponse = client.post(endpoint) {
            parameter("task", "transcribe")
            parameter("encode", true)
            parameter("output", "json")
            if (!language.isNullOrBlank()) {
                parameter("language", language)
            }

            val headers = Headers.build {
                append(
                    HttpHeaders.ContentDisposition,
                    ContentDisposition.File.withParameter(ContentDisposition.Parameters.FileName, fileName).toString()
                )
                append(HttpHeaders.ContentType, ContentType.Application.OctetStream.toString())
            }

            setBody(
                MultiPartFormDataContent(
                    formData {
                        append("audio_file", audioBytes, headers)
                    }
                )
            )
        }

        val responseText = response.bodyAsText()
        logger.debug("Whisper raw response: $responseText")

        if (!response.status.isSuccess()) {
            logger.error("Whisper service returned ${response.status}: $responseText")
            throw IllegalStateException("Whisper service error: ${response.status}")
        }

        val parsed = runCatching { Json.parseToJsonElement(responseText) }.getOrNull()
        val asObject = parsed as? JsonObject

        val transcription = asObject?.get("text")?.jsonPrimitive?.contentOrNull ?: responseText.trim()
        val detectedLanguage = asObject?.get("language")?.jsonPrimitive?.contentOrNull

        if (transcription.isBlank()) {
            logger.warn("Empty transcription from Whisper response")
        }

        return WhisperTranscriptionResult(
            text = transcription,
            language = detectedLanguage,
            rawResponse = responseText
        )
    }

    fun close() {
        client.close()
    }
}
