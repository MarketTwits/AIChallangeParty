package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP tool for getting tickets with filtering
 */
class GetTicketsTool(
    private val ticketProcessor: TicketProcessor,
) : Tool {
    private val logger = LoggerFactory.getLogger(GetTicketsTool::class.java)

    override val name: String = "get_tickets"
    override val description: String = """
        Get tickets from tickets.json with optional filtering.

        Filters:
        - priority: Filter by priority (CRITICAL, HIGH, MEDIUM, LOW)
        - status: Filter by status (NEW, IN_PROGRESS, RESOLVED, CLOSED)
        - category: Filter by category (bug, question, feature, auth, billing, other)
        - assignee: Filter by assignee name

        Use this when:
        - User asks "show me all tickets"
        - User asks "show high priority tickets"
        - User wants to see tickets by status or category

        Returns list of tickets with details.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("priority", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by priority: CRITICAL, HIGH, MEDIUM, LOW"))
                put("enum", buildJsonArray {
                    add("CRITICAL")
                    add("HIGH")
                    add("MEDIUM")
                    add("LOW")
                })
            })
            put("status", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by status: NEW, IN_PROGRESS, RESOLVED, CLOSED"))
                put("enum", buildJsonArray {
                    add("NEW")
                    add("IN_PROGRESS")
                    add("RESOLVED")
                    add("CLOSED")
                })
            })
            put("category", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by category: bug, question, feature, auth, billing, other"))
            })
            put("assignee", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Filter by assignee name"))
            })
            put("limit", buildJsonObject {
                put("type", JsonPrimitive("integer"))
                put("description", JsonPrimitive("Maximum number of tickets to return (default: 50)"))
            })
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val filterPriority = params["priority"]?.jsonPrimitive?.contentOrNull
            val filterStatus = params["status"]?.jsonPrimitive?.contentOrNull
            val filterCategory = params["category"]?.jsonPrimitive?.contentOrNull
            val filterAssignee = params["assignee"]?.jsonPrimitive?.contentOrNull
            val limit = params["limit"]?.jsonPrimitive?.intOrNull ?: 50

            logger.info("Getting tickets with filters: priority=$filterPriority, status=$filterStatus, category=$filterCategory")

            var tickets = ticketProcessor.readTickets()

            if (tickets.isEmpty()) {
                return ToolResult.Success(
                    data = "📭 No tickets found. Run 'process_support_requests' to create tickets from support requests.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Apply filters
            if (filterPriority != null) {
                tickets = tickets.filter { it.priority.equals(filterPriority, ignoreCase = true) }
            }
            if (filterStatus != null) {
                tickets = tickets.filter { it.status.equals(filterStatus, ignoreCase = true) }
            }
            if (filterCategory != null) {
                tickets = tickets.filter { it.classification.category.equals(filterCategory, ignoreCase = true) }
            }
            if (filterAssignee != null) {
                tickets = tickets.filter { it.assignee?.equals(filterAssignee, ignoreCase = true) == true }
            }

            // Sort by priority (CRITICAL first) then by creation date
            tickets = tickets.sortedWith(
                compareByDescending<com.markettwits.aichallenge.team.Ticket> {
                    when (it.priority) {
                        "CRITICAL" -> 4
                        "HIGH" -> 3
                        "MEDIUM" -> 2
                        "LOW" -> 1
                        else -> 0
                    }
                }.thenByDescending { it.createdAt }
            )

            // Apply limit
            tickets = tickets.take(limit)

            if (tickets.isEmpty()) {
                return ToolResult.Success(
                    data = "📭 No tickets match the specified filters.",
                    metadata = mapOf("count" to 0)
                )
            }

            // Format output
            val result = buildString {
                appendLine("📋 Tickets (${tickets.size} found)")
                appendLine("=".repeat(70))
                appendLine()

                tickets.forEachIndexed { index, ticket ->
                    val priorityEmoji = when (ticket.priority) {
                        "CRITICAL" -> "🔴"
                        "HIGH" -> "🟠"
                        "MEDIUM" -> "🟡"
                        "LOW" -> "🟢"
                        else -> "⚪"
                    }

                    val statusEmoji = when (ticket.status) {
                        "NEW" -> "🆕"
                        "IN_PROGRESS" -> "⚡"
                        "RESOLVED" -> "✅"
                        "CLOSED" -> "🔒"
                        "SPAM" -> "🚫"
                        else -> "📌"
                    }

                    appendLine("${index + 1}. $priorityEmoji #${ticket.id} - ${ticket.originalRequest.subject}")
                    appendLine("   Status: $statusEmoji ${ticket.status} | Priority: ${ticket.priority}")
                    appendLine("   From: ${ticket.originalRequest.userName} (${ticket.originalRequest.userId})")
                    appendLine("   Category: ${ticket.classification.category}")

                    if (ticket.assignee != null) {
                        appendLine("   Assignee: ${ticket.assignee}")
                    }

                    if (ticket.tags.isNotEmpty()) {
                        appendLine("   Tags: ${ticket.tags.joinToString(", ")}")
                    }

                    // Show a preview of the message
                    val messagePreview = ticket.originalRequest.message.take(100).let {
                        if (ticket.originalRequest.message.length > 100) "$it..." else it
                    }
                    appendLine("   Message: $messagePreview")

                    if (ticket.classification.reasoning.isNotBlank()) {
                        appendLine("   Analysis: ${ticket.classification.reasoning}")
                    }

                    appendLine()
                }

                // Summary
                val byPriority = tickets.groupingBy { it.priority }.eachCount()
                appendLine("📊 Summary:")
                byPriority.forEach { (priority, count) ->
                    appendLine("   • $priority: $count")
                }
            }

            ToolResult.Success(
                data = result,
                metadata = mapOf(
                    "count" to tickets.size,
                    "ticketIds" to tickets.map { it.id },
                    "priorities" to tickets.groupingBy { it.priority }.eachCount()
                )
            )

        } catch (e: Exception) {
            logger.error("Error getting tickets", e)
            ToolResult.Error(
                message = "Failed to get tickets: ${e.message}",
                code = "GET_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // All parameters are optional
        return null
    }
}
