package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

class AnthropicClient(private val apiKey: String) {
    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)
    @OptIn(ExperimentalSerializationApi::class)
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                encodeDefaults = false
                explicitNulls = false
            })
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 60000
            connectTimeoutMillis = 30000
            socketTimeoutMillis = 60000
        }
        engine {
            requestTimeout = 60000
        }
    }

    private val apiUrl = "https://api.anthropic.com/v1/messages"
    private val model = "claude-haiku-4-5-20251001"

    suspend fun sendMessage(messages: List<Message>, tools: List<Tool>? = null, systemPrompt: String? = null): AnthropicResponse {
        val request = AnthropicRequest(
            model = model,
            max_tokens = 4096,
            messages = messages,
            tools = tools,
            system = systemPrompt
        )

        logger.info("Sending request to Anthropic API with model: $model")
        logger.debug("Request: messages count=${messages.size}, tools count=${tools?.size ?: 0}")

        return try {
            val response: HttpResponse = client.post(apiUrl) {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            val responseBody = response.bodyAsText()
            logger.debug("Response status: ${response.status}")
            logger.debug("Response body: $responseBody")

            if (response.status != HttpStatusCode.OK) {
                logger.error("API error: ${response.status} - $responseBody")
                throw Exception("API error: ${response.status} - $responseBody")
            }

            response.body()
        } catch (e: Exception) {
            logger.error("Error calling Anthropic API", e)
            throw e
        }
    }

    fun close() {
        client.close()
    }
}
