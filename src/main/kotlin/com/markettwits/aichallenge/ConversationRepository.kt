package com.markettwits.aichallenge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory

object ConversationMessages : Table("conversation_messages") {
    val id = integer("id").autoIncrement()
    val sessionId = varchar("session_id", 255)
    val role = varchar("role", 50)
    val contentJson = text("content_json")
    val timestamp = long("timestamp")

    override val primaryKey = PrimaryKey(id)
}

class ConversationRepository(databasePath: String = "/app/data/conversations.db") {
    private val logger = LoggerFactory.getLogger(ConversationRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    init {
        val dbFile = java.io.File(databasePath)
        val dbDir = dbFile.parentFile
        if (dbDir != null && !dbDir.exists()) {
            dbDir.mkdirs()
            logger.info("Created database directory: ${dbDir.absolutePath}")
        }

        Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")

        transaction {
            SchemaUtils.create(ConversationMessages)
        }

        logger.info("Database initialized at $databasePath")
    }

    fun saveMessage(sessionId: String, message: Message) {
        transaction {
            ConversationMessages.insert {
                it[this.sessionId] = sessionId
                it[role] = message.role
                it[contentJson] = json.encodeToString(message.content)
                it[timestamp] = System.currentTimeMillis()
            }
        }
    }

    fun loadMessages(sessionId: String): List<Message> {
        return transaction {
            ConversationMessages.select { ConversationMessages.sessionId eq sessionId }
                .orderBy(ConversationMessages.timestamp to SortOrder.ASC)
                .map { row ->
                    Message(
                        role = row[ConversationMessages.role],
                        content = json.decodeFromString(row[ConversationMessages.contentJson])
                    )
                }
        }
    }

    fun clearHistory(sessionId: String) {
        transaction {
            ConversationMessages.deleteWhere {
                ConversationMessages.sessionId.eq(sessionId)
            }
        }
        logger.info("Cleared conversation history for session: $sessionId")
    }

    fun getAllSessions(): List<String> {
        return transaction {
            ConversationMessages.slice(ConversationMessages.sessionId)
                .selectAll()
                .withDistinct()
                .map { it[ConversationMessages.sessionId] }
        }
    }

    data class SessionInfo(
        val sessionId: String,
        val messageCount: Int,
        val lastMessageTime: Long,
    )

    fun getSessionsInfo(): List<SessionInfo> {
        return transaction {
            ConversationMessages.selectAll()
                .groupBy { it[ConversationMessages.sessionId] }
                .map { (sessionId, messages) ->
                    SessionInfo(
                        sessionId = sessionId,
                        messageCount = messages.size,
                        lastMessageTime = messages.maxOf { it[ConversationMessages.timestamp] }
                    )
                }
                .sortedByDescending { it.lastMessageTime }
        }
    }
}
