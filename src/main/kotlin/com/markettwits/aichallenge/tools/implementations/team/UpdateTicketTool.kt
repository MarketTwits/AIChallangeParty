package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.team.UpdateTicketRequest
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.*
import org.slf4j.LoggerFactory

/**
 * MCP tool for updating ticket properties
 */
class UpdateTicketTool(
    private val ticketProcessor: TicketProcessor,
) : Tool {
    private val logger = LoggerFactory.getLogger(UpdateTicketTool::class.java)

    override val name: String = "update_ticket"
    override val description: String = """
        Update ticket properties in tickets.json.

        Can update:
        - status: NEW, IN_PROGRESS, RESOLVED, CLOSED
        - priority: CRITICAL, HIGH, MEDIUM, LOW
        - assignee: Person assigned to handle the ticket
        - notes: Internal notes about the ticket

        Use this when:
        - User wants to change ticket status
        - User wants to reassign ticket
        - User wants to update priority
        - User wants to add notes

        Returns updated ticket information.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("ticketId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Ticket ID to update (required)"))
            })
            put("status", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("New status: NEW, IN_PROGRESS, RESOLVED, CLOSED"))
                put("enum", buildJsonArray {
                    add("NEW")
                    add("IN_PROGRESS")
                    add("RESOLVED")
                    add("CLOSED")
                })
            })
            put("priority", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("New priority: CRITICAL, HIGH, MEDIUM, LOW"))
                put("enum", buildJsonArray {
                    add("CRITICAL")
                    add("HIGH")
                    add("MEDIUM")
                    add("LOW")
                })
            })
            put("assignee", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Person to assign ticket to"))
            })
            put("notes", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Internal notes about the ticket"))
            })
        },
        required = listOf("ticketId")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val ticketId = params["ticketId"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.Error("Missing required parameter: ticketId", "MISSING_PARAM")

            val status = params["status"]?.jsonPrimitive?.contentOrNull
            val priority = params["priority"]?.jsonPrimitive?.contentOrNull
            val assignee = params["assignee"]?.jsonPrimitive?.contentOrNull
            val notes = params["notes"]?.jsonPrimitive?.contentOrNull

            logger.info("Updating ticket: $ticketId")

            // Check if ticket exists
            val existingTicket = ticketProcessor.getTicketById(ticketId)
                ?: return ToolResult.Error("Ticket not found: $ticketId", "NOT_FOUND")

            // Build update request
            val updateRequest = UpdateTicketRequest(
                status = status,
                priority = priority,
                assignee = assignee,
                notes = notes
            )

            // Update ticket
            val updatedTicket = ticketProcessor.updateTicket(ticketId, updateRequest)
                ?: return ToolResult.Error("Failed to update ticket: $ticketId", "UPDATE_FAILED")

            // Build result
            val changes = mutableListOf<String>()
            if (status != null && status != existingTicket.status) {
                changes.add("Status: ${existingTicket.status} → $status")
            }
            if (priority != null && priority != existingTicket.priority) {
                changes.add("Priority: ${existingTicket.priority} → $priority")
            }
            if (assignee != null && assignee != existingTicket.assignee) {
                changes.add("Assignee: ${existingTicket.assignee ?: "None"} → $assignee")
            }
            if (notes != null && notes != existingTicket.notes) {
                changes.add("Notes updated")
            }

            val result = buildString {
                appendLine("✏️ Ticket Updated")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Ticket: #$ticketId")
                appendLine("Subject: ${updatedTicket.originalRequest.subject}")
                appendLine()

                if (changes.isNotEmpty()) {
                    appendLine("Changes:")
                    changes.forEach { change ->
                        appendLine("   • $change")
                    }
                } else {
                    appendLine("No changes applied (values were the same)")
                }

                appendLine()
                appendLine("Current Status:")
                appendLine("   • Status: ${updatedTicket.status}")
                appendLine("   • Priority: ${updatedTicket.priority}")
                appendLine("   • Assignee: ${updatedTicket.assignee ?: "Unassigned"}")
                appendLine("   • Category: ${updatedTicket.classification.category}")

                if (updatedTicket.notes?.isNotBlank() == true) {
                    appendLine("   • Notes: ${updatedTicket.notes}")
                }

                appendLine()
                appendLine("💾 Changes saved to tickets.json")
            }

            ToolResult.Success(
                data = result,
                metadata = mapOf(
                    "ticketId" to ticketId,
                    "changesApplied" to changes.size,
                    "updatedFields" to listOfNotNull(
                        if (status != null) "status" else null,
                        if (priority != null) "priority" else null,
                        if (assignee != null) "assignee" else null,
                        if (notes != null) "notes" else null
                    )
                )
            )

        } catch (e: Exception) {
            logger.error("Error updating ticket", e)
            ToolResult.Error(
                message = "Failed to update ticket: ${e.message}",
                code = "UPDATE_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.contains("ticketId")) {
            return "Missing required parameter: ticketId"
        }

        // Validate status if provided
        val status = params["status"]?.jsonPrimitive?.contentOrNull
        if (status != null && status !in listOf("NEW", "IN_PROGRESS", "RESOLVED", "CLOSED", "SPAM")) {
            return "Invalid status: $status. Must be one of: NEW, IN_PROGRESS, RESOLVED, CLOSED"
        }

        // Validate priority if provided
        val priority = params["priority"]?.jsonPrimitive?.contentOrNull
        if (priority != null && priority !in listOf("CRITICAL", "HIGH", "MEDIUM", "LOW")) {
            return "Invalid priority: $priority. Must be one of: CRITICAL, HIGH, MEDIUM, LOW"
        }

        return null
    }
}
