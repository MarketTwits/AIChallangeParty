package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

@Serializable
data class ModelComparisonResult(
    val modelName: String,
    val modelId: String,
    val response: String,
    val responseTimeMs: Long,
    val inputTokens: Int,
    val outputTokens: Int,
    val estimatedCost: Double,
    val error: String? = null,
)

@Serializable
data class ComparisonRequest(
    val query: String,
)

@Serializable
data class ComparisonResponse(
    val results: List<ModelComparisonResult>,
    val totalTimeMs: Long,
)

class HuggingFaceClient(private val apiKey: String) {
    private val logger = LoggerFactory.getLogger(HuggingFaceClient::class.java)

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
                coerceInputValues = true
            })
        }
    }

    private val models = listOf(
        ModelInfo("meta-llama/Llama-3.2-3B-Instruct", "Llama 3.2 3B Instruct", 0.0),
        ModelInfo("Qwen/Qwen2.5-72B-Instruct", "Qwen 2.5 72B Instruct", 0.0),
        ModelInfo("Sao10K/L3-8B-Lunaris-v1", "L3 8B Lunaris v1", 0.0)
    )

    data class ModelInfo(
        val id: String,
        val name: String,
        val costPerToken: Double,
    )

    suspend fun compareModels(query: String): ComparisonResponse {
        val startTime = System.currentTimeMillis()
        val results = mutableListOf<ModelComparisonResult>()

        for (model in models) {
            val result = queryModel(model, query)
            results.add(result)
        }

        val totalTime = System.currentTimeMillis() - startTime
        return ComparisonResponse(results, totalTime)
    }

    private suspend fun queryModel(model: ModelInfo, query: String): ModelComparisonResult {
        val startTime = System.currentTimeMillis()

        return try {
            logger.info("Querying model: ${model.id}")

            val requestBody = buildJsonObject {
                putJsonArray("messages") {
                    addJsonObject {
                        put("role", "user")
                        put("content", query)
                    }
                }
                put("model", "${model.id}:fastest")
            }

            val apiUrl = "https://router.huggingface.co/v1/chat/completions"
            logger.info("Sending request to: $apiUrl with model: ${model.id}")

            val apiKeyMasked = if (apiKey.length > 8) {
                "${apiKey.substring(0, 8)}...${apiKey.substring(apiKey.length - 4)}"
            } else {
                "***"
            }
            logger.info("API Key: $apiKeyMasked (length: ${apiKey.length})")
            logger.info("Full API Key (first 20 chars): ${apiKey.take(20)}...")

            val requestBodyStr = requestBody.toString()
            logger.info("Request body: $requestBodyStr")

            val response: HttpResponse = client.post(apiUrl) {
                header("Authorization", "Bearer $apiKey")
                header("Content-Type", "application/json")
                setBody(requestBodyStr)
            }

            val responseTime = System.currentTimeMillis() - startTime
            val responseBody = response.bodyAsText()

            logger.info("Response status: ${response.status.value}")
            logger.info("Response from ${model.id}: $responseBody")

            val jsonElement = Json.parseToJsonElement(responseBody)

            if (jsonElement is JsonObject && jsonElement.containsKey("error")) {
                val errorObj = jsonElement["error"]
                val errorMessage = if (errorObj is JsonObject) {
                    errorObj["message"]?.jsonPrimitive?.content ?: errorObj.toString()
                } else {
                    errorObj?.jsonPrimitive?.content ?: "Unknown error"
                }
                throw Exception(errorMessage)
            }

            if (jsonElement !is JsonObject || !jsonElement.containsKey("choices")) {
                throw Exception("Invalid response format: $responseBody")
            }

            val choices = jsonElement["choices"]?.jsonArray
            val firstChoice = choices?.firstOrNull()?.jsonObject
            val message = firstChoice?.get("message")?.jsonObject
            val generatedText = message?.get("content")?.jsonPrimitive?.content ?: "No response"

            val usage = jsonElement["usage"]?.jsonObject
            val inputTokens = usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: estimateTokens(query)
            val outputTokens =
                usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: estimateTokens(generatedText)
            val cost = (inputTokens + outputTokens) * model.costPerToken

            ModelComparisonResult(
                modelName = model.name,
                modelId = model.id,
                response = generatedText,
                responseTimeMs = responseTime,
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                estimatedCost = cost,
                error = null
            )
        } catch (e: Exception) {
            logger.error("Error querying model ${model.id}", e)
            val responseTime = System.currentTimeMillis() - startTime

            ModelComparisonResult(
                modelName = model.name,
                modelId = model.id,
                response = "",
                responseTimeMs = responseTime,
                inputTokens = 0,
                outputTokens = 0,
                estimatedCost = 0.0,
                error = e.message ?: "Unknown error"
            )
        }
    }

    private fun estimateTokens(text: String): Int {
        return (text.length / 4.0).toInt()
    }

    fun close() {
        client.close()
    }
}
