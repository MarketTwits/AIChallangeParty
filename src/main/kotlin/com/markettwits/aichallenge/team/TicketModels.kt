package com.markettwits.aichallenge.team

import kotlinx.serialization.Serializable

/**
 * Support request from users (входящие обращения)
 */
@Serializable
data class SupportRequest(
    val id: String,
    val userId: String,
    val userName: String,
    val email: String?,
    val subject: String,
    val message: String,
    val timestamp: Long,
    val category: String? = null, // "bug", "question", "feature_request", etc.
)

/**
 * Ticket priority after processing
 */
enum class TicketPriority(val level: Int) {
    CRITICAL(4),    // Urgent issues affecting many users
    HIGH(3),        // Important bugs or blocking issues
    MEDIUM(2),      // Normal requests
    LOW(1),         // Minor issues or suggestions
    SPAM(0);        // Spam or invalid requests (will be filtered)

    companion object {
        fun fromString(value: String): TicketPriority {
            return values().find { it.name.equals(value, ignoreCase = true) } ?: MEDIUM
        }
    }
}

/**
 * Ticket status
 */
enum class TicketStatus {
    NEW,            // Just created
    IN_PROGRESS,    // Being worked on
    RESOLVED,       // Fixed/answered
    CLOSED,         // Closed by user or support
    SPAM;           // Marked as spam

    companion object {
        fun fromString(value: String): TicketStatus {
            return values().find { it.name.equals(value, ignoreCase = true) } ?: NEW
        }
    }
}

/**
 * Classification result from AI analysis
 */
@Serializable
data class TicketClassification(
    val isSpam: Boolean,
    val isUserError: Boolean,          // Obvious user mistakes (wrong credentials, etc.)
    val category: String,              // "bug", "question", "feature", "auth", etc.
    val priority: String,              // "CRITICAL", "HIGH", "MEDIUM", "LOW"
    val reasoning: String,             // Why this classification
    val suggestedResponse: String? = null,  // Optional quick response template
)

/**
 * Processed ticket (готовый тикет после обработки)
 */
@Serializable
data class Ticket(
    val id: String,                    // Same as original request ID
    val originalRequest: SupportRequest,
    val classification: TicketClassification,
    val priority: String,              // "CRITICAL", "HIGH", "MEDIUM", "LOW"
    val status: String = "NEW",
    val assignee: String? = null,
    val tags: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    val notes: String? = null,          // Internal notes from support team
)

/**
 * Ticket statistics
 */
@Serializable
data class TicketStatistics(
    val total: Int,
    val byPriority: Map<String, Int>,
    val byStatus: Map<String, Int>,
    val byCategory: Map<String, Int>,
    val spamFiltered: Int,
    val userErrorsDetected: Int,
    val avgProcessingTime: Long? = null, // In milliseconds
)

/**
 * Ticket update request
 */
@Serializable
data class UpdateTicketRequest(
    val status: String? = null,
    val priority: String? = null,
    val assignee: String? = null,
    val tags: List<String>? = null,
    val notes: String? = null,
)

/**
 * Ticket list (для хранения в файле tickets.json)
 */
@Serializable
data class TicketList(
    val tickets: List<Ticket>,
    val lastUpdated: Long,
)

/**
 * Support requests list (для чтения из support_requests.json)
 */
@Serializable
data class SupportRequestList(
    val requests: List<SupportRequest>,
)
