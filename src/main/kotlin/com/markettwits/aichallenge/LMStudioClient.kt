package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

@Serializable
data class LMStudioChatRequest(
    val model: String = "local-model",
    val messages: List<LMStudioMessage>,
    val temperature: Double = 0.7,
    @SerialName("max_tokens") val maxTokens: Int = 1000,
)

@Serializable
data class LMStudioMessage(
    val role: String,
    val content: String,
)

@Serializable
data class LMStudioChatResponse(
    val id: String? = null,
    val model: String? = null,
    val choices: List<LMStudioChoice> = emptyList(),
    val usage: LMStudioUsage? = null,
    val error: LMStudioError? = null,
)

@Serializable
data class LMStudioError(
    val message: String,
    val type: String? = null,
)

@Serializable
data class LMStudioChoice(
    val index: Int = 0,
    val message: LMStudioMessage,
    @SerialName("finish_reason") val finishReason: String? = null,
)

@Serializable
data class LMStudioUsage(
    @SerialName("prompt_tokens") val promptTokens: Int? = null,
    @SerialName("completion_tokens") val completionTokens: Int? = null,
    @SerialName("total_tokens") val totalTokens: Int? = null,
)

@Serializable
data class LMStudioModelInfo(
    val name: String,
    val model: String? = null,
)

@Serializable
data class LMStudioModelsResponse(
    val data: List<LMStudioModel> = emptyList(),
    val models: List<String> = emptyList(), // Legacy format (LM Studio) - array of strings
)

@Serializable
data class LMStudioModel(
    @SerialName("id")
    val id: String,
    @SerialName("object")
    val objects: String = "model",
    @SerialName("owned_by")
    val ownedBy: String? = null,
)

class LMStudioClient(private val baseUrl: String) {
    private val logger = LoggerFactory.getLogger(LMStudioClient::class.java)

    init {
        logger.info("LMStudioClient initialized with baseUrl: $baseUrl")
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        prettyPrint = false
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(json)
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 120_000 // 2 minutes for chat completions
            connectTimeoutMillis = 30_000  // 30 seconds for connection
            socketTimeoutMillis = 120_000  // 2 minutes for socket read
        }
    }

    suspend fun listModels(): List<String> {
        return try {
            val url = "$baseUrl/v1/models"
            logger.info("Requesting models from: $url")
            val response: HttpResponse = client.get(url)

            if (response.status == HttpStatusCode.OK) {
                val responseText = response.bodyAsText()
                logger.info("Models endpoint response (${responseText.length} chars): ${responseText.take(200)}")

                try {
                    val jsonElement = json.parseToJsonElement(responseText).jsonObject

                    // Try to get models from 'data' field first (OpenAI format)
                    val dataField = jsonElement["data"]?.jsonArray
                    if (dataField != null && dataField.isNotEmpty()) {
                        val modelIds = dataField.mapNotNull { element ->
                            element.jsonObject["id"]?.jsonPrimitive?.content
                        }
                        if (modelIds.isNotEmpty()) {
                            logger.info("Found ${modelIds.size} models in 'data' field")
                            return modelIds
                        }
                    }

                    // Try 'models' field - support both array of strings (legacy) and array of objects (new)
                    val modelsField = jsonElement["models"]?.jsonArray
                    if (modelsField != null && modelsField.isNotEmpty()) {
                        val modelNames = modelsField.mapNotNull { element ->
                            when {
                                // New format: array of objects with 'name' field
                                element is JsonObject -> element["name"]?.jsonPrimitive?.content
                                // Legacy format: array of strings
                                element is JsonPrimitive -> element.contentOrNull
                                else -> null
                            }
                        }
                        if (modelNames.isNotEmpty()) {
                            logger.info("Found ${modelNames.size} models in 'models' field")
                            return modelNames
                        }
                    }

                    logger.warn("No models found in response")
                    emptyList()
                } catch (e: Exception) {
                    logger.error("Failed to parse models response: ${e.message}")
                    logger.debug("Response body: $responseText")
                    emptyList()
                }
            } else {
                logger.error("Failed to list models: ${response.status}")
                emptyList()
            }
        } catch (e: Exception) {
            logger.error("Error listing models from LM Studio", e)
            emptyList()
        }
    }

    suspend fun chat(messages: List<LMStudioMessage>, temperature: Double = 0.7, modelName: String? = null): String {
        return try {
            // Use provided model or get first available
            val selectedModel = modelName ?: run {
                val models = listModels()
                models.firstOrNull() ?: "local-model"
            }

            val url = "$baseUrl/v1/chat/completions"
            logger.info("Using model: $selectedModel for chat at $url")

            val request = LMStudioChatRequest(
                model = selectedModel,
                messages = messages,
                temperature = temperature,
                maxTokens = 2000
            )

            val response: HttpResponse = client.post(url) {
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status == HttpStatusCode.OK) {
                val chatResponse = response.body<LMStudioChatResponse>()

                // Check for error in response
                if (chatResponse.error != null) {
                    logger.error("LM Studio returned error: ${chatResponse.error.message}")
                    return "Error: ${chatResponse.error.message}"
                }

                val reply = chatResponse.choices.firstOrNull()?.message?.content
                    ?: run {
                        logger.warn("No choices in response, raw body: ${response.bodyAsText()}")
                        "No response from model (empty choices)"
                    }

                logger.info("LM Studio response received. Tokens: ${chatResponse.usage?.totalTokens ?: "unknown"}")
                reply
            } else {
                val errorBody = response.bodyAsText()
                logger.error("Failed to get chat response: ${response.status}, body: $errorBody")
                "Error: Failed to get response from local model (${response.status}): $errorBody"
            }
        } catch (e: Exception) {
            logger.error("Error communicating with LM Studio", e)
            "Error: ${e.message ?: "Failed to communicate with local model"}"
        }
    }

    suspend fun isAvailable(): Boolean {
        return try {
            val url = "$baseUrl/v1/models"
            logger.info("Checking availability at: $url")
            val response: HttpResponse = client.get(url)
            val available = response.status == HttpStatusCode.OK
            logger.info("LM Studio available: $available (status: ${response.status})")
            available
        } catch (e: Exception) {
            logger.warn("LM Studio is not available at $baseUrl: ${e.message}")
            false
        }
    }

    fun close() {
        client.close()
    }
}
