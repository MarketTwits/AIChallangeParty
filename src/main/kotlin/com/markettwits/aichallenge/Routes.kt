package com.markettwits.aichallenge

import io.ktor.client.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

fun Application.configureRouting(sessionManager: SessionManager, apiKey: String, huggingFaceKey: String) {
    val logger = LoggerFactory.getLogger("Routes")
    val reasoningAgents = mutableMapOf<String, ReasoningAgent>()

    val hfKeyMasked = if (huggingFaceKey.length > 8) {
        "${huggingFaceKey.substring(0, 8)}...${huggingFaceKey.substring(huggingFaceKey.length - 4)}"
    } else {
        "NOT_SET"
    }
    logger.info("HuggingFace API Key in Routes: $hfKeyMasked (length: ${huggingFaceKey.length})")

    if (huggingFaceKey.isEmpty()) {
        logger.error("HUGGINGFACE_API_KEY is empty!")
    }

    val huggingFaceClient = HuggingFaceClient(huggingFaceKey)

    routing {
        post("/chat") {
            try {
                val request = call.receive<ChatRequest>()
                logger.info("Received chat request from session ${request.sessionId}: ${request.message}")

                val agent = sessionManager.getOrCreateSession(request.sessionId) {
                    AnthropicClient(apiKey)
                }

                val (response, structuredResponse) = agent.chat(request.message, request.coachStyle ?: "default")
                val remainingMessages = agent.getRemainingMessages()

                call.respond(ChatResponse(
                    response = response,
                    remainingMessages = remainingMessages,
                    structuredResponse = structuredResponse
                ))
            } catch (e: Exception) {
                logger.error("Error processing chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ChatResponse(response = "Error: ${e.message}")
                )
            }
        }

        post("/api/anthropic/messages") {
            try {
                val requestBody = call.receiveText()
                logger.info("Proxying request to Anthropic API")

                val proxyClient = HttpClient(CIO) {
                    install(ContentNegotiation) {
                        json(Json {
                            ignoreUnknownKeys = true
                            isLenient = true
                        })
                    }
                }

                val response: HttpResponse = proxyClient.post("https://api.anthropic.com/v1/messages") {
                    header("x-api-key", System.getenv("ANTHROPIC_API_KEY") ?: "")
                    header("anthropic-version", "2023-06-01")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }

                val responseBody = response.bodyAsText()
                logger.debug("Received response from Anthropic API")

                call.respondText(
                    text = responseBody,
                    contentType = ContentType.Application.Json,
                    status = response.status
                )

                proxyClient.close()
            } catch (e: Exception) {
                logger.error("Error proxying to Anthropic API", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/reasoning-chat") {
            try {
                val request = call.receive<ReasoningChatRequest>()
                logger.info("Received reasoning chat request from session ${request.sessionId}: ${request.message}, mode: ${request.reasoningMode}")

                val agent = reasoningAgents.getOrPut(request.sessionId) {
                    ReasoningAgent(AnthropicClient(apiKey))
                }

                val response =
                    agent.chat(request.message, request.sessionId, request.reasoningMode, request.temperature)

                call.respond(response)
            } catch (e: Exception) {
                logger.error("Error processing reasoning chat request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/reasoning-chat/clear") {
            try {
                val sessionId = call.receive<Map<String, String>>()["sessionId"] ?: ""
                reasoningAgents[sessionId]?.clearHistory(sessionId)
                reasoningAgents.remove(sessionId)
                call.respond(HttpStatusCode.OK, mapOf("status" to "cleared"))
            } catch (e: Exception) {
                logger.error("Error clearing reasoning chat session", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        post("/model-comparison") {
            try {
                val request = call.receive<ComparisonRequest>()
                logger.info("Received model comparison request: ${request.query}")

                val response = huggingFaceClient.compareModels(request.query)

                call.respond(response)
            } catch (e: Exception) {
                logger.error("Error processing model comparison request", e)
                call.respond(
                    HttpStatusCode.InternalServerError,
                    mapOf("error" to e.message)
                )
            }
        }

        get("/health") {
            call.respond(HttpStatusCode.OK, mapOf("status" to "ok"))
        }
    }
}
