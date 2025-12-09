package com.markettwits.aichallenge.team

import com.markettwits.aichallenge.tools.TeamToolManager
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("TeamRoutes")

/**
 * Configure team assistant and support ticket management routes (Day 23)
 */
fun Application.configureTeamRoutes(
    teamAssistant: TeamAssistantAgent,
    teamToolManager: TeamToolManager,
    ticketProcessor: TicketProcessor,
) {
    routing {
        route("/team") {
            // ============ CHAT ENDPOINTS ============

            /**
             * POST /team/chat
             * Chat with team assistant
             */
            post("/chat") {
                try {
                    val request = call.receive<ChatRequest>()

                    if (request.message.isBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse(
                                error = "Message cannot be empty"
                            )
                        )
                        return@post
                    }

                    logger.info("💬 Team chat request: ${request.message.take(50)}...")

                    val (response, toolsUsed, _) = teamAssistant.chat(request.message, request.sessionId ?: "default")

                    call.respond(
                        HttpStatusCode.OK, ChatResponse(
                            response = response,
                            toolsUsed = toolsUsed,
                            sessionId = request.sessionId ?: "default"
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error in team chat", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to process message: ${e.message}"
                        )
                    )
                }
            }

            /**
             * POST /team/clear
             * Clear conversation history
             */
            post("/clear") {
                try {
                    val sessionId = call.request.queryParameters["sessionId"]

                    if (sessionId.isNullOrBlank()) {
                        call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse(
                                error = "Missing sessionId parameter"
                            )
                        )
                        return@post
                    }

                    teamAssistant.clearHistory(sessionId)
                    logger.info("🗑️ Cleared conversation history for session: $sessionId")

                    call.respond(
                        HttpStatusCode.OK, ClearHistoryResponse(
                            message = "Conversation history cleared",
                            sessionId = sessionId
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error clearing conversation history", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to clear conversation history: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /team/sessions
             * List all active sessions
             */
            get("/sessions") {
                try {
                    val sessions = teamAssistant.getActiveSessions()
                    call.respond(
                        HttpStatusCode.OK, SessionsResponse(
                            sessions = sessions,
                            count = sessions.size
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error listing sessions", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to list sessions: ${e.message}"
                        )
                    )
                }
            }

            // ============ TICKET MANAGEMENT ENDPOINTS ============

            /**
             * POST /team/tickets/process
             * Process support requests from support_requests.json
             */
            post("/tickets/process") {
                try {
                    logger.info("📨 Processing support requests...")
                    val result = ticketProcessor.processAllRequests()

                    call.respond(
                        HttpStatusCode.OK, ProcessTicketsResponse(
                            message = "Support requests processed successfully",
                            totalProcessed = result.totalProcessed,
                            validTickets = result.validTickets,
                            spamFiltered = result.spamFiltered,
                            userErrorsDetected = result.userErrorsDetected
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error processing support requests", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to process support requests: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /team/tickets
             * List tickets with optional filtering
             */
            get("/tickets") {
                try {
                    val priority = call.request.queryParameters["priority"]
                    val status = call.request.queryParameters["status"]
                    val category = call.request.queryParameters["category"]

                    var tickets = ticketProcessor.readTickets()

                    // Apply filters
                    if (priority != null) {
                        tickets = tickets.filter { it.priority.equals(priority, ignoreCase = true) }
                    }
                    if (status != null) {
                        tickets = tickets.filter { it.status.equals(status, ignoreCase = true) }
                    }
                    if (category != null) {
                        tickets = tickets.filter { it.classification.category.equals(category, ignoreCase = true) }
                    }

                    // Sort by priority
                    tickets = tickets.sortedWith(
                        compareByDescending {
                            when (it.priority) {
                                "CRITICAL" -> 4
                                "HIGH" -> 3
                                "MEDIUM" -> 2
                                "LOW" -> 1
                                else -> 0
                            }
                        }
                    )

                    logger.info("📋 Retrieved ${tickets.size} tickets via API")
                    call.respond(
                        HttpStatusCode.OK, TicketsResponse(
                            tickets = tickets,
                            count = tickets.size
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error retrieving tickets", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to retrieve tickets: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /team/tickets/{id}
             * Get a specific ticket
             */
            get("/tickets/{id}") {
                try {
                    val ticketId = call.parameters["id"]

                    if (ticketId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse(
                                error = "Invalid ticket ID"
                            )
                        )
                        return@get
                    }

                    val ticket = ticketProcessor.getTicketById(ticketId)

                    if (ticket == null) {
                        call.respond(
                            HttpStatusCode.NotFound, ErrorResponse(
                                error = "Ticket not found"
                            )
                        )
                        return@get
                    }

                    logger.info("📋 Retrieved ticket #$ticketId via API")
                    call.respond(HttpStatusCode.OK, ticket)

                } catch (e: Exception) {
                    logger.error("Error retrieving ticket", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to retrieve ticket: ${e.message}"
                        )
                    )
                }
            }

            /**
             * PUT /team/tickets/{id}
             * Update a ticket
             */
            put("/tickets/{id}") {
                try {
                    val ticketId = call.parameters["id"]

                    if (ticketId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse(
                                error = "Invalid ticket ID"
                            )
                        )
                        return@put
                    }

                    val request = call.receive<UpdateTicketRequest>()
                    val ticket = ticketProcessor.updateTicket(ticketId, request)

                    if (ticket == null) {
                        call.respond(
                            HttpStatusCode.NotFound, ErrorResponse(
                                error = "Ticket not found"
                            )
                        )
                        return@put
                    }

                    logger.info("📝 Updated ticket #$ticketId via API")
                    call.respond(HttpStatusCode.OK, ticket)

                } catch (e: Exception) {
                    logger.error("Error updating ticket", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to update ticket: ${e.message}"
                        )
                    )
                }
            }

            /**
             * DELETE /team/tickets/{id}
             * Delete a ticket
             */
            delete("/tickets/{id}") {
                try {
                    val ticketId = call.parameters["id"]

                    if (ticketId == null) {
                        call.respond(
                            HttpStatusCode.BadRequest, ErrorResponse(
                                error = "Invalid ticket ID"
                            )
                        )
                        return@delete
                    }

                    val deleted = ticketProcessor.deleteTicket(ticketId)

                    if (!deleted) {
                        call.respond(
                            HttpStatusCode.NotFound, ErrorResponse(
                                error = "Ticket not found"
                            )
                        )
                        return@delete
                    }

                    logger.info("🗑️ Deleted ticket #$ticketId via API")
                    call.respond(
                        HttpStatusCode.OK, DeleteTicketResponse(
                            message = "Ticket deleted successfully",
                            ticketId = ticketId
                        )
                    )

                } catch (e: Exception) {
                    logger.error("Error deleting ticket", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to delete ticket: ${e.message}"
                        )
                    )
                }
            }

            // ============ ANALYTICS ENDPOINTS ============

            /**
             * GET /team/statistics
             * Get ticket statistics
             */
            get("/statistics") {
                try {
                    val statistics = ticketProcessor.getStatistics()

                    logger.info("📊 Retrieved ticket statistics via API")
                    call.respond(HttpStatusCode.OK, statistics)

                } catch (e: Exception) {
                    logger.error("Error retrieving statistics", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to retrieve statistics: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /team/status
             * Get overall system status
             */
            get("/status") {
                try {
                    val statistics = ticketProcessor.getStatistics()

                    // Calculate health score
                    val healthScore = calculateHealthScore(statistics)

                    val combinedStatus = SystemStatusResponse(
                        tickets = statistics,
                        overall = OverallStatus(
                            healthScore = healthScore,
                            status = when {
                                healthScore >= 80 -> "Excellent"
                                healthScore >= 60 -> "Good"
                                healthScore >= 40 -> "Fair"
                                else -> "Poor"
                            },
                            timestamp = System.currentTimeMillis()
                        )
                    )

                    logger.info("📊 Retrieved system status via API")
                    call.respond(HttpStatusCode.OK, combinedStatus)

                } catch (e: Exception) {
                    logger.error("Error retrieving system status", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Failed to retrieve system status: ${e.message}"
                        )
                    )
                }
            }

            /**
             * GET /team/health
             * Health check endpoint
             */
            get("/health") {
                try {
                    val isRagReady = teamToolManager.isRagReady()
                    val toolCount = teamToolManager.getRegistry().getToolCount()

                    val health = HealthResponse(
                        status = "healthy",
                        timestamp = System.currentTimeMillis(),
                        services = ServicesStatus(
                            teamAssistant = "online",
                            ticketProcessor = "online",
                            ragSystem = if (isRagReady) "online" else "offline",
                            tools = ToolsStatus(
                                total = toolCount,
                                available = toolCount
                            )
                        )
                    )

                    call.respond(HttpStatusCode.OK, health)

                } catch (e: Exception) {
                    logger.error("Error in health check", e)
                    call.respond(
                        HttpStatusCode.InternalServerError, ErrorResponse(
                            error = "Health check failed: ${e.message}"
                        )
                    )
                }
            }
        }
    }
}

/**
 * Calculate system health score based on ticket metrics
 */
private fun calculateHealthScore(statistics: TicketStatistics): Int {
    var score = 100

    if (statistics.total == 0) return 100 // No tickets = healthy

    // Deduct points for critical tickets
    val criticalCount = statistics.byPriority["CRITICAL"] ?: 0
    score -= (criticalCount * 10)

    // Deduct points for high volume of new tickets
    val newCount = statistics.byStatus["NEW"] ?: 0
    if (newCount > 10) {
        score -= ((newCount - 10) * 2)
    }

    // Bonus for resolved tickets
    val resolvedCount = statistics.byStatus["RESOLVED"] ?: 0
    val resolutionRate = if (statistics.total > 0) (resolvedCount * 100 / statistics.total) else 0
    if (resolutionRate > 80) {
        score += 10
    }

    // Ensure score stays within bounds
    return score.coerceIn(0, 100)
}

/**
 * Chat request model
 */
@Serializable
data class ChatRequest(
    val message: String,
    val sessionId: String? = null,
)

/**
 * API Response models
 */
@Serializable
data class HealthResponse(
    val status: String,
    val timestamp: Long,
    val services: ServicesStatus,
)

@Serializable
data class ServicesStatus(
    val teamAssistant: String,
    val ticketProcessor: String,
    val ragSystem: String,
    val tools: ToolsStatus,
)

@Serializable
data class ToolsStatus(
    val total: Int,
    val available: Int,
)

@Serializable
data class TicketsResponse(
    val tickets: List<Ticket>,
    val count: Int,
)

@Serializable
data class ErrorResponse(
    val error: String,
)

@Serializable
data class SuccessResponse(
    val message: String,
)

@Serializable
data class ChatResponse(
    val response: String,
    val toolsUsed: List<String>,
    val sessionId: String,
)

@Serializable
data class ProcessTicketsResponse(
    val message: String,
    val totalProcessed: Int,
    val validTickets: Int,
    val spamFiltered: Int,
    val userErrorsDetected: Int,
)

@Serializable
data class ClearHistoryResponse(
    val message: String,
    val sessionId: String,
)

@Serializable
data class SessionsResponse(
    val sessions: List<String>,
    val count: Int,
)

@Serializable
data class DeleteTicketResponse(
    val message: String,
    val ticketId: String,
)

@Serializable
data class SystemStatusResponse(
    val tickets: TicketStatistics,
    val overall: OverallStatus,
)

@Serializable
data class OverallStatus(
    val healthScore: Int,
    val status: String,
    val timestamp: Long,
)
