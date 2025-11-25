package com.markettwits.aichallenge.rag

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory

@Serializable
data class OllamaGenerateRequest(
    val model: String,
    val prompt: String,
    val stream: Boolean = false,
    val options: Map<String, JsonElement>? = null,
)

@Serializable
data class OllamaGenerateResponse(
    val model: String,
    val response: String,
    val done: Boolean,
    val context: List<Int>? = null,
    val total_duration: Long? = null,
    val load_duration: Long? = null,
    val prompt_eval_count: Int? = null,
    val eval_count: Int? = null,
    val created_at: String? = null,
    val eval_duration: Long? = null,
    val prompt_eval_duration: Long? = null,
)

/**
 * HTTP Client for Ollama LLM API
 * Generates text completions using local Ollama models (llama2, mistral, etc.)
 * Model runs locally at http://localhost:11434
 */
class OllamaLLMClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "llama3.2", // Default model, can be changed
) {
    private val logger = LoggerFactory.getLogger(OllamaLLMClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 300000  // 5 minutes for LLM generation
            connectTimeoutMillis = 30000   // 30 seconds connection
            socketTimeoutMillis = 300000   // 5 minutes socket
        }
        engine {
            maxConnectionsCount = 10
            endpoint {
                maxConnectionsPerRoute = 5
                pipelineMaxSize = 20
                keepAliveTime = 5000
                connectTimeout = 30000
                connectAttempts = 3
            }
        }
    }

    /**
     * Generate text completion WITHOUT context (no RAG)
     * @param question User's question
     * @return LLM response text
     */
    suspend fun generateWithoutContext(question: String): String {
        return try {
            logger.info("Generating response WITHOUT context for question: $question")

            val prompt = """
                Answer the following question based on your general knowledge:

                Question: $question

                Answer:
            """.trimIndent()

            val response = sendGenerateRequest(prompt)
            logger.info("Response generated without context (length: ${response.length} chars)")
            response
        } catch (e: Exception) {
            logger.error("Error generating response without context", e)
            throw e
        }
    }

    /**
     * Generate text completion WITH context (RAG mode)
     * @param question User's question
     * @param context Retrieved context from vector database
     * @return LLM response text
     */
    suspend fun generateWithContext(question: String, context: String): String {
        return try {
            logger.info("Generating response WITH context for question: $question")
            logger.debug("Context length: ${context.length} chars")

            val prompt = """
                You are an AI assistant that answers questions based on the provided context.
                Use ONLY the information from the context below to answer the question.
                If the context doesn't contain enough information, say so.

                Context:
                $context

                Question: $question

                Answer based on the context:
            """.trimIndent()

            val response = sendGenerateRequest(prompt)
            logger.info("Response generated with context (length: ${response.length} chars)")
            response
        } catch (e: Exception) {
            logger.error("Error generating response with context", e)
            throw e
        }
    }

    /**
     * Send generate request to Ollama API
     * @param prompt The full prompt to send
     * @return Generated text
     */
    private suspend fun sendGenerateRequest(prompt: String): String {
        val maxRetries = 3
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                logger.debug("Sending generate request (attempt $attempt/$maxRetries)")

                val requestBody = OllamaGenerateRequest(
                    model = model,
                    prompt = prompt,
                    stream = false,
                    options = mapOf(
                        "temperature" to JsonPrimitive(0.7),
                        "num_predict" to JsonPrimitive(500)  // Max tokens to generate
                    )
                )

                val apiUrl = "$baseUrl/api/generate"
                logger.debug("Sending request to: $apiUrl with model: $model")

                val response: HttpResponse = client.post(apiUrl) {
                    header("Content-Type", "application/json")
                    setBody(requestBody)
                }

                logger.debug("Response status: ${response.status.value}")

                if (response.status.value !in 200..299) {
                    val errorBody = response.bodyAsText()
                    logger.error("Error response from Ollama: $errorBody")
                    throw Exception("Ollama API returned ${response.status.value}: $errorBody")
                }

                val responseText = response.bodyAsText()

                // Ollama returns JSON Lines format (multiple JSON objects separated by newlines)
                // We need to parse each line and concatenate the responses
                val fullResponse = buildString {
                    responseText.lines().forEach { line ->
                        if (line.isNotBlank()) {
                            try {
                                val chunk = Json.decodeFromString<OllamaGenerateResponse>(line)
                                append(chunk.response)
                            } catch (e: Exception) {
                                logger.warn("Failed to parse chunk: $line", e)
                            }
                        }
                    }
                }

                logger.debug("Generation completed, response length: ${fullResponse.length}")
                return fullResponse.trim()
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    logger.warn("Generate request failed (attempt $attempt/$maxRetries), retrying...", e)
                    Thread.sleep(2000)  // Wait 2 seconds before retry
                } else {
                    logger.error("Error generating response after $maxRetries attempts", e)
                    throw e
                }
            }
        }

        throw lastError ?: Exception("Unexpected error generating response")
    }

    /**
     * Check if Ollama service is available and the model is loaded
     * @return true if service is available, false otherwise
     */
    suspend fun isAvailable(): Boolean {
        return try {
            val response: HttpResponse = client.get("$baseUrl/api/tags")
            response.status.value in 200..299
        } catch (e: Exception) {
            logger.warn("Ollama service is not available: ${e.message}")
            false
        }
    }

    fun close() {
        client.close()
    }
}
