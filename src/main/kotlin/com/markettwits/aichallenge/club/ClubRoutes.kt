package com.markettwits.aichallenge.club

import com.markettwits.aichallenge.tools.ClubToolManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.*

private val logger = LoggerFactory.getLogger("ClubRoutes")

@Serializable
data class ClubChatRequest(
    val message: String,
    val sessionId: String? = null,
)

@Serializable
data class ClubChatResponse(
    val response: String,
    val sessionId: String,
    val metadata: Map<String, String>,
    val usage: UsageInfo? = null,
)

@Serializable
data class UsageInfo(
    val inputTokens: Int,
    val outputTokens: Int,
)

@Serializable
data class ClubStatusResponse(
    val status: String,
    val toolsAvailable: Int,
    val ragReady: Boolean,
    val tools: List<ToolInfo>,
)

@Serializable
data class ToolInfo(
    val name: String,
    val type: String,
)

fun Application.configureClubRoutes(
    clubSupportAgent: ClubSupportAgent,
    clubToolManager: ClubToolManager,
) {
    routing {
        route("/club") {
            // Main chat endpoint
            post("/chat") {
                try {
                    val request = call.receive<ClubChatRequest>()

                    if (request.message.isBlank()) {
                        call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Message cannot be empty"))
                        return@post
                    }

                    val sessionId = request.sessionId ?: UUID.randomUUID().toString()

                    logger.info("Club chat request - Session: $sessionId, Message: ${request.message.take(50)}...")

                    val (response, usage, metadata) = clubSupportAgent.chat(request.message, sessionId)

                    logger.info("Agent response length: ${response.length}")
                    if (response.isEmpty()) {
                        logger.warn("Empty response from agent!")
                    }
                    logger.debug("Agent response preview: ${response.take(100)}")

                    val finalResponse = if (response.isBlank()) {
                        "Извините, не удалось получить ответ. Попробуйте переформулировать вопрос."
                    } else {
                        response
                    }

                    val chatResponse = ClubChatResponse(
                        response = finalResponse,
                        sessionId = sessionId,
                        metadata = metadata.mapValues { entry ->
                            when (val value = entry.value) {
                                is List<*> -> value.joinToString(", ")
                                else -> value.toString()
                            }
                        },
                        usage = usage?.let { UsageInfo(it.input_tokens, it.output_tokens) }
                    )

                    call.respond(HttpStatusCode.OK, chatResponse)
                } catch (e: Exception) {
                    logger.error("Error in club chat endpoint", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to process chat request: ${e.message}")
                    )
                }
            }

            // Clear conversation history
            post("/clear") {
                try {
                    val sessionId = call.request.queryParameters["sessionId"]
                        ?: return@post call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "sessionId query parameter is required")
                        )

                    clubSupportAgent.clearHistory(sessionId)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("message" to "Conversation history cleared", "sessionId" to sessionId)
                    )
                } catch (e: Exception) {
                    logger.error("Error clearing history", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to clear history: ${e.message}")
                    )
                }
            }

            // Get system status
            get("/status") {
                try {
                    val status = clubToolManager.getSystemStatus()

                    val response = ClubStatusResponse(
                        status = "ok",
                        toolsAvailable = status["toolsRegistered"] as Int,
                        ragReady = status["ragReady"] as Boolean,
                        tools = (status["tools"] as List<*>).map {
                            val toolMap = it as Map<*, *>
                            ToolInfo(
                                name = toolMap["name"] as String,
                                type = toolMap["type"] as String
                            )
                        }
                    )

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    logger.error("Error getting club status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get status: ${e.message}")
                    )
                }
            }

            // Get active sessions
            get("/sessions") {
                try {
                    val sessions = clubSupportAgent.getActiveSessions()

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "sessions" to sessions,
                            "count" to sessions.size
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error getting sessions", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get sessions: ${e.message}")
                    )
                }
            }

            // Health check
            get("/health") {
                call.respond(HttpStatusCode.OK, mapOf("status" to "healthy"))
            }

            // Index club documentation (manual trigger)
            post("/index") {
                try {
                    logger.info("Manual indexing of club documentation requested")

                    val projectRoot = System.getProperty("user.dir")
                    val clubDocsDir = java.io.File("$projectRoot/data/club_docs")

                    if (!clubDocsDir.exists()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Club docs directory not found: ${clubDocsDir.absolutePath}")
                        )
                        return@post
                    }

                    val files = clubDocsDir.listFiles()?.filter { it.isFile && it.extension == "md" }
                    if (files.isNullOrEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "No markdown files found in ${clubDocsDir.absolutePath}")
                        )
                        return@post
                    }

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "message" to "Please use ./scripts/index_club_data.sh to index club documentation",
                            "files" to files.map { it.name },
                            "count" to files.size
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Error starting indexing", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to start indexing: ${e.message}")
                    )
                }
            }
        }
    }
}
