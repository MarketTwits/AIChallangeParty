package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * MCP tool for deleting tickets
 */
class DeleteTicketTool(
    private val ticketProcessor: TicketProcessor,
) : Tool {
    private val logger = LoggerFactory.getLogger(DeleteTicketTool::class.java)

    override val name: String = "delete_ticket"
    override val description: String = """
        Delete a ticket from tickets.json.

        Use this when:
        - Ticket was created by mistake
        - Ticket is a duplicate
        - Ticket needs to be permanently removed

        WARNING: This action is permanent and cannot be undone.

        Returns confirmation of deletion.
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            put("ticketId", buildJsonObject {
                put("type", JsonPrimitive("string"))
                put("description", JsonPrimitive("Ticket ID to delete (required)"))
            })
        },
        required = listOf("ticketId")
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            val ticketId = params["ticketId"]?.jsonPrimitive?.content
                ?: return ToolResult.Error("Missing required parameter: ticketId", "MISSING_PARAM")

            logger.info("Deleting ticket: $ticketId")

            // Get ticket info before deletion
            val ticket = ticketProcessor.getTicketById(ticketId)
                ?: return ToolResult.Error("Ticket not found: $ticketId", "NOT_FOUND")

            // Delete ticket
            val deleted = ticketProcessor.deleteTicket(ticketId)

            if (!deleted) {
                return ToolResult.Error("Failed to delete ticket: $ticketId", "DELETE_FAILED")
            }

            // Build result
            val result = buildString {
                appendLine("🗑️ Ticket Deleted")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("Deleted ticket: #$ticketId")
                appendLine("Subject: ${ticket.originalRequest.subject}")
                appendLine("From: ${ticket.originalRequest.userName}")
                appendLine("Priority: ${ticket.priority}")
                appendLine("Status: ${ticket.status}")
                appendLine()
                appendLine("💾 Changes saved to tickets.json")
                appendLine()
                appendLine("⚠️ This action is permanent and cannot be undone.")
            }

            ToolResult.Success(
                data = result,
                metadata = mapOf(
                    "ticketId" to ticketId,
                    "deletedTicket" to mapOf(
                        "subject" to ticket.originalRequest.subject,
                        "priority" to ticket.priority,
                        "status" to ticket.status
                    )
                )
            )

        } catch (e: Exception) {
            logger.error("Error deleting ticket", e)
            ToolResult.Error(
                message = "Failed to delete ticket: ${e.message}",
                code = "DELETE_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        if (!params.contains("ticketId")) {
            return "Missing required parameter: ticketId"
        }
        return null
    }
}
