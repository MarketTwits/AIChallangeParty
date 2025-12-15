package com.markettwits.aichallenge.personalized

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import org.slf4j.LoggerFactory
import java.util.*

fun Application.configurePersonalizedAgentRoutes(
    personalizedAgentService: PersonalizedAgentService,
) {
    val logger = LoggerFactory.getLogger("PersonalizedAgentRoutes")

    routing {
        route("/personalized-agent") {

            // GET /personalized-agent/config - Get current configuration
            get("/config") {
                try {
                    val config = personalizedAgentService.getConfig()
                    call.respond(HttpStatusCode.OK, config)
                } catch (e: Exception) {
                    logger.error("Failed to get config", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get configuration: ${e.message}")
                    )
                }
            }

            // PUT /personalized-agent/config - Update configuration
            put("/config") {
                try {
                    val config = call.receive<PersonalAgentConfig>()
                    logger.info("Received config update: name=${config.profile.name}, role=${config.profile.role}")

                    personalizedAgentService.updateConfig(config)

                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "success" to true,
                            "message" to "Configuration updated successfully"
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Failed to update config", e)
                    e.printStackTrace()
                    call.respond(
                        HttpStatusCode.BadRequest,
                        mapOf("error" to "Failed to update configuration: ${e.message}")
                    )
                }
            }

            // GET /personalized-agent/prompt - Get compiled system prompt
            get("/prompt") {
                try {
                    val prompt = personalizedAgentService.getCompiledPrompt()
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf("prompt" to prompt)
                    )
                } catch (e: Exception) {
                    logger.error("Failed to compile prompt", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to compile prompt: ${e.message}")
                    )
                }
            }

            // POST /personalized-agent/chat - Chat with personalized agent
            post("/chat") {
                try {
                    val chatRequest = call.receive<PersonalAgentChatRequest>()

                    // Generate or use provided conversation ID
                    val conversationId = chatRequest.conversationId
                        ?: UUID.randomUUID().toString()

                    logger.info("Chat request for conversation: $conversationId")

                    val response = personalizedAgentService.chat(
                        message = chatRequest.message,
                        conversationId = conversationId
                    )

                    call.respond(HttpStatusCode.OK, response)
                } catch (e: Exception) {
                    logger.error("Failed to process chat request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to process chat: ${e.message}")
                    )
                }
            }

            // DELETE /personalized-agent/conversation/{id} - Clear conversation
            delete("/conversation/{id}") {
                try {
                    val conversationId = call.parameters["id"]
                    if (conversationId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Conversation ID is required")
                        )
                        return@delete
                    }

                    personalizedAgentService.clearConversation(conversationId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "success" to true,
                            "message" to "Conversation cleared"
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Failed to clear conversation", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to clear conversation: ${e.message}")
                    )
                }
            }

            // GET /personalized-agent/conversation/{id} - Get conversation history
            get("/conversation/{id}") {
                try {
                    val conversationId = call.parameters["id"]
                    if (conversationId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            mapOf("error" to "Conversation ID is required")
                        )
                        return@get
                    }

                    val history = personalizedAgentService.getConversation(conversationId)
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "conversationId" to conversationId,
                            "messages" to history
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Failed to get conversation", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        mapOf("error" to "Failed to get conversation: ${e.message}")
                    )
                }
            }

            // GET /personalized-agent/health - Check if agent is ready
            get("/health") {
                try {
                    val config = personalizedAgentService.getConfig()
                    call.respond(
                        HttpStatusCode.OK,
                        mapOf(
                            "status" to "ready",
                            "configVersion" to config.version,
                            "userName" to config.profile.name
                        )
                    )
                } catch (e: Exception) {
                    logger.error("Health check failed", e)
                    call.respond(
                        HttpStatusCode.ServiceUnavailable,
                        mapOf("status" to "unavailable", "error" to e.message)
                    )
                }
            }
        }
    }
}
