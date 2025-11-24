package com.markettwits.aichallenge.rag

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val input: String,
)

@Serializable
data class OllamaBatchEmbeddingRequest(
    val model: String,
    val input: List<String>,
)

@Serializable
data class OllamaEmbeddingResponse(
    val model: String,
    val embeddings: List<List<Double>>,
)

/**
 * HTTP Client for Ollama embedding API
 * Generates embeddings using the nomic-embed-text model
 * Model runs locally at http://localhost:11434
 */
class OllamaEmbeddingClient(
    private val baseUrl: String = "http://localhost:11434",
    private val model: String = "nomic-embed-text",
    private val timeout: Long = 60000,
) {
    private val logger = LoggerFactory.getLogger(OllamaEmbeddingClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
        install(io.ktor.client.plugins.HttpTimeout) {
            requestTimeoutMillis = 300000  // 5 minutes for embeddings
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
     * Generate embedding for a single text
     * @param text Input text to embed
     * @return List of doubles representing the embedding vector (768-dimensional for nomic-embed-text)
     */
    suspend fun generateEmbedding(text: String): List<Double> {
        val maxRetries = 3
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                logger.debug("Generating embedding for text length: ${text.length} (attempt $attempt/$maxRetries)")

                val requestBody = OllamaEmbeddingRequest(
                    model = model,
                    input = text
                )

                val apiUrl = "$baseUrl/api/embed"
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
                val responseBody = Json.decodeFromString<OllamaEmbeddingResponse>(responseText)

                if (responseBody.embeddings.isEmpty()) {
                    throw Exception("No embeddings returned from Ollama")
                }

                val embedding = responseBody.embeddings[0]
                logger.debug("Embedding generated, vector size: ${embedding.size}")

                return embedding
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    logger.warn("Embedding generation failed (attempt $attempt/$maxRetries), retrying...", e)
                    Thread.sleep(2000)  // Wait 2 seconds before retry
                } else {
                    logger.error(
                        "Error generating embedding for text length: ${text.length} after $maxRetries attempts",
                        e
                    )
                    throw e
                }
            }
        }

        throw lastError ?: Exception("Unexpected error generating embedding")
    }

    /**
     * Generate embeddings for multiple texts in batch (more efficient)
     * @param texts List of texts to embed
     * @return List of embedding vectors
     */
    suspend fun generateEmbeddingsBatch(texts: List<String>): List<List<Double>> {
        val maxRetries = 3
        var lastError: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                logger.debug("Generating batch embeddings for ${texts.size} texts (attempt $attempt/$maxRetries)")

                val requestBody = OllamaBatchEmbeddingRequest(
                    model = model,
                    input = texts
                )

                val apiUrl = "$baseUrl/api/embed"
                logger.debug("Sending batch request to: $apiUrl with ${texts.size} texts")

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
                val responseBody = Json.decodeFromString<OllamaEmbeddingResponse>(responseText)

                if (responseBody.embeddings.isEmpty()) {
                    throw Exception("No embeddings returned from Ollama")
                }

                if (responseBody.embeddings.size != texts.size) {
                    logger.warn("Expected ${texts.size} embeddings but got ${responseBody.embeddings.size}")
                }

                logger.debug("Batch embeddings generated successfully, ${responseBody.embeddings.size} vectors")
                return responseBody.embeddings
            } catch (e: Exception) {
                lastError = e
                if (attempt < maxRetries) {
                    logger.warn("Batch embedding generation failed (attempt $attempt/$maxRetries), retrying...", e)
                    Thread.sleep(2000)
                } else {
                    logger.error(
                        "Error generating batch embeddings for ${texts.size} texts after $maxRetries attempts",
                        e
                    )
                    throw e
                }
            }
        }

        throw lastError ?: Exception("Unexpected error generating batch embeddings")
    }

    /**
     * Generate embeddings for multiple texts (fallback to single requests)
     * @param texts List of texts to embed
     * @return List of embedding vectors
     */
    suspend fun generateEmbeddings(texts: List<String>): List<List<Double>> {
        logger.info("Generating embeddings for ${texts.size} texts")
        return texts.map { text ->
            generateEmbedding(text)
        }
    }

    /**
     * Check if Ollama service is available
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
