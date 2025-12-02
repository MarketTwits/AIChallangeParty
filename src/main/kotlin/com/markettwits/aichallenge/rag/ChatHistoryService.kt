package com.markettwits.aichallenge.rag

import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.util.concurrent.ConcurrentHashMap

/**
 * Chat message in the history
 */
@Serializable
data class ChatMessage(
    val role: String,  // "user" or "assistant"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val sources: List<RetrievedChunkInfo> = emptyList(),  // Sources used for this answer (if any)
)

/**
 * Chat session with history
 */
data class ChatSession(
    val sessionId: String,
    val messages: MutableList<ChatMessage> = mutableListOf(),
    val createdAt: Long = System.currentTimeMillis(),
    var lastActivityAt: Long = System.currentTimeMillis(),
)

/**
 * Service for managing chat history across multiple sessions
 * Each session maintains its own conversation history
 */
class ChatHistoryService(
    private val maxMessagesPerSession: Int = 20,  // Max messages to keep in memory
    private val sessionTimeoutMinutes: Long = 60,   // Session expires after 60 minutes of inactivity
) {
    private val logger = LoggerFactory.getLogger(ChatHistoryService::class.java)

    // Session storage: sessionId -> ChatSession
    private val sessions = ConcurrentHashMap<String, ChatSession>()

    /**
     * Get or create a chat session
     */
    fun getOrCreateSession(sessionId: String): ChatSession {
        cleanupExpiredSessions()

        return sessions.getOrPut(sessionId) {
            logger.info("Creating new chat session: $sessionId")
            ChatSession(sessionId = sessionId)
        }
    }

    /**
     * Add a user message to the session
     */
    fun addUserMessage(sessionId: String, message: String) {
        val session = getOrCreateSession(sessionId)
        session.messages.add(ChatMessage(role = "user", content = message))
        session.lastActivityAt = System.currentTimeMillis()

        // Trim messages if exceeds limit
        if (session.messages.size > maxMessagesPerSession) {
            val toRemove = session.messages.size - maxMessagesPerSession
            logger.info("Trimming $toRemove old messages from session $sessionId")
            session.messages.subList(0, toRemove).clear()
        }

        logger.debug("Added user message to session $sessionId (total: ${session.messages.size} messages)")
    }

    /**
     * Add an assistant message with sources to the session
     */
    fun addAssistantMessage(
        sessionId: String,
        message: String,
        sources: List<RetrievedChunkInfo> = emptyList(),
    ) {
        val session = getOrCreateSession(sessionId)
        session.messages.add(
            ChatMessage(
                role = "assistant",
                content = message,
                sources = sources
            )
        )
        session.lastActivityAt = System.currentTimeMillis()

        // Trim messages if exceeds limit
        if (session.messages.size > maxMessagesPerSession) {
            val toRemove = session.messages.size - maxMessagesPerSession
            logger.info("Trimming $toRemove old messages from session $sessionId")
            session.messages.subList(0, toRemove).clear()
        }

        logger.debug("Added assistant message to session $sessionId (total: ${session.messages.size} messages)")
    }

    /**
     * Get chat history for a session
     */
    fun getHistory(sessionId: String): List<ChatMessage> {
        val session = sessions[sessionId] ?: return emptyList()
        return session.messages.toList()
    }

    /**
     * Get chat history as formatted context for LLM
     * Returns a string with recent conversation history
     */
    fun getHistoryContext(sessionId: String, lastN: Int = 5): String {
        val session = sessions[sessionId] ?: return ""

        // Take last N messages
        val recentMessages = if (session.messages.size > lastN) {
            session.messages.takeLast(lastN)
        } else {
            session.messages
        }

        if (recentMessages.isEmpty()) {
            return ""
        }

        return buildString {
            appendLine("Previous conversation:")
            appendLine()
            recentMessages.forEach { msg ->
                appendLine("${msg.role.uppercase()}: ${msg.content}")
                appendLine()
            }
        }
    }

    /**
     * Clear history for a session
     */
    fun clearSession(sessionId: String) {
        sessions.remove(sessionId)
        logger.info("Cleared session: $sessionId")
    }

    /**
     * Get all active sessions
     */
    fun getActiveSessions(): List<String> {
        cleanupExpiredSessions()
        return sessions.keys.toList()
    }

    /**
     * Get session statistics
     */
    fun getSessionStats(sessionId: String): Map<String, Any>? {
        val session = sessions[sessionId] ?: return null

        return mapOf(
            "sessionId" to sessionId,
            "messageCount" to session.messages.size,
            "createdAt" to session.createdAt,
            "lastActivityAt" to session.lastActivityAt,
            "userMessages" to session.messages.count { it.role == "user" },
            "assistantMessages" to session.messages.count { it.role == "assistant" }
        )
    }

    /**
     * Cleanup expired sessions
     */
    private fun cleanupExpiredSessions() {
        val now = System.currentTimeMillis()
        val timeoutMs = sessionTimeoutMinutes * 60 * 1000

        val expiredSessions = sessions.entries.filter { (_, session) ->
            now - session.lastActivityAt > timeoutMs
        }

        expiredSessions.forEach { (sessionId, _) ->
            sessions.remove(sessionId)
            logger.info("Removed expired session: $sessionId")
        }
    }

    /**
     * Get total sessions count
     */
    fun getTotalSessions(): Int = sessions.size
}
