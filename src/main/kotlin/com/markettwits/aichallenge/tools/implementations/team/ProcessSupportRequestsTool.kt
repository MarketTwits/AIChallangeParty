package com.markettwits.aichallenge.tools.implementations.team

import com.markettwits.aichallenge.team.TicketProcessor
import com.markettwits.aichallenge.tools.core.Tool
import com.markettwits.aichallenge.tools.core.ToolResult
import com.markettwits.aichallenge.tools.core.ToolSchema
import com.markettwits.aichallenge.tools.core.ToolType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.slf4j.LoggerFactory

/**
 * MCP tool for processing support requests
 * Reads from support_requests.json, filters spam, assigns priorities, saves to tickets.json
 */
class ProcessSupportRequestsTool(
    private val ticketProcessor: TicketProcessor,
) : Tool {
    private val logger = LoggerFactory.getLogger(ProcessSupportRequestsTool::class.java)

    override val name: String = "process_support_requests"
    override val description: String = """
        Process pending support requests from support_requests.json.

        This tool:
        1. Reads all support requests from the file
        2. Uses AI to analyze and classify each request
        3. Filters out spam and obvious user errors
        4. Assigns priorities (CRITICAL, HIGH, MEDIUM, LOW)
        5. Saves valid tickets to tickets.json

        Use this when:
        - User asks to "process new support requests"
        - User wants to "analyze incoming tickets"
        - User asks "what new support requests do we have"

        Returns:
        - Number of requests processed
        - Number of valid tickets created
        - Number of spam filtered
        - Summary by priority and category
    """.trimIndent()

    override val type: ToolType = ToolType.MCP
    override val schema: ToolSchema = ToolSchema(
        properties = buildJsonObject {
            // No parameters - processes all pending requests
        },
        required = emptyList()
    )

    override suspend fun execute(params: JsonObject): ToolResult {
        return try {
            logger.info("Processing support requests...")

            val result = ticketProcessor.processAllRequests()

            if (result.totalProcessed == 0) {
                return ToolResult.Success(
                    data = "📭 No support requests to process.",
                    metadata = mapOf("totalProcessed" to 0)
                )
            }

            // Build result summary
            val summary = buildString {
                appendLine("✅ Support Requests Processed")
                appendLine("=".repeat(50))
                appendLine()
                appendLine("📊 Summary:")
                appendLine("   • Total requests analyzed: ${result.totalProcessed}")
                appendLine("   • Valid tickets created: ${result.validTickets}")
                appendLine("   • Spam filtered: ${result.spamFiltered}")
                appendLine("   • User errors detected: ${result.userErrorsDetected}")
                appendLine()

                if (result.tickets.isNotEmpty()) {
                    // Group by priority
                    val byPriority = result.tickets.groupingBy { it.priority }.eachCount()
                    appendLine("🎯 By Priority:")
                    byPriority.entries.sortedByDescending { (priority, _) ->
                        when (priority) {
                            "CRITICAL" -> 4
                            "HIGH" -> 3
                            "MEDIUM" -> 2
                            "LOW" -> 1
                            else -> 0
                        }
                    }.forEach { (priority, count) ->
                        val emoji = when (priority) {
                            "CRITICAL" -> "🔴"
                            "HIGH" -> "🟠"
                            "MEDIUM" -> "🟡"
                            "LOW" -> "🟢"
                            else -> "⚪"
                        }
                        appendLine("   $emoji $priority: $count")
                    }
                    appendLine()

                    // Group by category
                    val byCategory = result.tickets.groupingBy { it.classification.category }.eachCount()
                    appendLine("📁 By Category:")
                    byCategory.forEach { (category, count) ->
                        appendLine("   • $category: $count")
                    }
                    appendLine()

                    // Show critical tickets
                    val criticalTickets = result.tickets.filter { it.priority == "CRITICAL" }
                    if (criticalTickets.isNotEmpty()) {
                        appendLine("🚨 Critical Tickets Require Immediate Attention:")
                        criticalTickets.take(3).forEach { ticket ->
                            appendLine("   • #${ticket.id}: ${ticket.originalRequest.subject}")
                            appendLine("     From: ${ticket.originalRequest.userName}")
                            appendLine("     Reason: ${ticket.classification.reasoning}")
                        }
                        if (criticalTickets.size > 3) {
                            appendLine("   ... and ${criticalTickets.size - 3} more critical tickets")
                        }
                    }
                }

                appendLine()
                appendLine("💾 Tickets saved to tickets.json")
            }

            ToolResult.Success(
                data = summary,
                metadata = mapOf(
                    "totalProcessed" to result.totalProcessed,
                    "validTickets" to result.validTickets,
                    "spamFiltered" to result.spamFiltered,
                    "userErrorsDetected" to result.userErrorsDetected,
                    "ticketIds" to result.tickets.map { it.id }
                )
            )

        } catch (e: Exception) {
            logger.error("Error processing support requests", e)
            ToolResult.Error(
                message = "Failed to process support requests: ${e.message}",
                code = "PROCESSING_ERROR"
            )
        }
    }

    override fun validateParams(params: JsonObject): String? {
        // No parameters to validate
        return null
    }
}
