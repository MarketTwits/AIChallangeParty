package com.markettwits.aichallenge

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.transaction
import org.slf4j.LoggerFactory
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

object StacktraceTasks : Table("stacktrace_tasks") {
    val id = varchar("id", 36)
    val title = varchar("title", 255).default("Firebase stacktrace")
    val status = varchar("status", 20)
    val stacktrace = text("stacktrace")
    val stacktracePath = text("stacktrace_path").nullable()
    val model = varchar("model", 120).nullable()
    val temperature = double("temperature").nullable()
    val maxTokens = integer("max_tokens").nullable()
    val topP = double("top_p").nullable()
    val presencePenalty = double("presence_penalty").nullable()
    val frequencyPenalty = double("frequency_penalty").nullable()
    val createdAt = varchar("created_at", 50)
    val startedAt = varchar("started_at", 50).nullable()
    val completedAt = varchar("completed_at", 50).nullable()
    val report = text("report").nullable()
    val error = text("error").nullable()
    val usage = text("usage").nullable()

    override val primaryKey = PrimaryKey(id)
}

data class StacktraceTaskRecord(
    val id: String,
    val title: String,
    val status: StacktraceTaskStatus,
    val stacktrace: String,
    val stacktracePath: String?,
    val model: String?,
    val temperature: Double?,
    val maxTokens: Int?,
    val topP: Double?,
    val presencePenalty: Double?,
    val frequencyPenalty: Double?,
    val createdAt: String,
    val startedAt: String?,
    val completedAt: String?,
    val report: String?,
    val error: String?,
    val usage: LMStudioUsage?,
)

class StacktraceTaskRepository(
    databasePath: String = "data/stacktrace_tasks.db",
) {
    private val logger = LoggerFactory.getLogger(StacktraceTaskRepository::class.java)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val dateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

    private val db: Database = run {
        val dbFile = java.io.File(databasePath)
        val dbDir = dbFile.parentFile
        if (dbDir != null && !dbDir.exists()) {
            dbDir.mkdirs()
            logger.info("Created stacktrace tasks database directory: ${dbDir.absolutePath}")
        }

        val database = Database.connect("jdbc:sqlite:$databasePath", "org.sqlite.JDBC")
        transaction(database) {
            SchemaUtils.createMissingTablesAndColumns(StacktraceTasks)
        }
        logger.info("Stacktrace tasks database initialized at $databasePath")
        database
    }

    fun createTask(
        title: String,
        stacktrace: String,
        stacktracePath: String?,
        model: String?,
        temperature: Double?,
        maxTokens: Int?,
        topP: Double?,
        presencePenalty: Double?,
        frequencyPenalty: Double?,
    ): StacktraceTaskRecord {
        val id = UUID.randomUUID().toString()
        val now = LocalDateTime.now().format(dateTimeFormatter)
        val status = StacktraceTaskStatus.QUEUED

        transaction(db) {
            StacktraceTasks.insert {
                it[StacktraceTasks.id] = id
                it[StacktraceTasks.title] = title
                it[StacktraceTasks.status] = status.name
                it[StacktraceTasks.stacktrace] = stacktrace
                it[StacktraceTasks.stacktracePath] = stacktracePath
                it[StacktraceTasks.model] = model
                it[StacktraceTasks.temperature] = temperature
                it[StacktraceTasks.maxTokens] = maxTokens
                it[StacktraceTasks.topP] = topP
                it[StacktraceTasks.presencePenalty] = presencePenalty
                it[StacktraceTasks.frequencyPenalty] = frequencyPenalty
                it[StacktraceTasks.createdAt] = now
            }
        }

        logger.info("Created stacktrace task $id ($title) with status ${status.name}")
        return StacktraceTaskRecord(
            id = id,
            title = title,
            status = status,
            stacktrace = stacktrace,
            stacktracePath = stacktracePath,
            model = model,
            temperature = temperature,
            maxTokens = maxTokens,
            topP = topP,
            presencePenalty = presencePenalty,
            frequencyPenalty = frequencyPenalty,
            createdAt = now,
            startedAt = null,
            completedAt = null,
            report = null,
            error = null,
            usage = null
        )
    }

    fun updateStatus(
        taskId: String,
        status: StacktraceTaskStatus,
        startedAt: String? = null,
        completedAt: String? = null,
        error: String? = null,
    ) {
        transaction(db) {
            StacktraceTasks.update({ StacktraceTasks.id eq taskId }) {
                it[StacktraceTasks.status] = status.name
                it[StacktraceTasks.startedAt] = startedAt
                it[StacktraceTasks.completedAt] = completedAt
                it[StacktraceTasks.error] = error
            }
        }
        logger.info("Updated task $taskId to status ${status.name}")
    }

    fun saveResult(
        taskId: String,
        report: String,
        usage: LMStudioUsage?,
        completedAt: String,
    ) {
        transaction(db) {
            StacktraceTasks.update({ StacktraceTasks.id eq taskId }) {
                it[StacktraceTasks.report] = report
                it[StacktraceTasks.usage] = usage?.let { u -> json.encodeToString(u) }
                it[StacktraceTasks.completedAt] = completedAt
                it[StacktraceTasks.status] = StacktraceTaskStatus.COMPLETED.name
            }
        }
        logger.info("Saved result for task $taskId")
    }

    fun updateAndReset(
        taskId: String,
        title: String?,
        stacktrace: String?,
        stacktracePath: String?,
        model: String?,
        temperature: Double?,
        maxTokens: Int?,
        topP: Double?,
        presencePenalty: Double?,
        frequencyPenalty: Double?,
    ): StacktraceTaskRecord? {
        val now = LocalDateTime.now().format(dateTimeFormatter)
        var updated = 0
        transaction(db) {
            updated = StacktraceTasks.update({ StacktraceTasks.id eq taskId }) {
                if (title != null) it[StacktraceTasks.title] = title
                if (stacktrace != null) it[StacktraceTasks.stacktrace] = stacktrace
                if (stacktracePath != null) it[StacktraceTasks.stacktracePath] = stacktracePath
                if (model != null) it[StacktraceTasks.model] = model
                if (temperature != null) it[StacktraceTasks.temperature] = temperature
                if (maxTokens != null) it[StacktraceTasks.maxTokens] = maxTokens
                if (topP != null) it[StacktraceTasks.topP] = topP
                if (presencePenalty != null) it[StacktraceTasks.presencePenalty] = presencePenalty
                if (frequencyPenalty != null) it[StacktraceTasks.frequencyPenalty] = frequencyPenalty
                it[StacktraceTasks.status] = StacktraceTaskStatus.QUEUED.name
                it[StacktraceTasks.startedAt] = null
                it[StacktraceTasks.completedAt] = null
                it[StacktraceTasks.report] = null
                it[StacktraceTasks.error] = null
                it[StacktraceTasks.createdAt] = now
            }
        }
        return if (updated > 0) getTask(taskId) else null
    }

    fun getTask(taskId: String): StacktraceTaskRecord? {
        return transaction(db) {
            StacktraceTasks
                .select { StacktraceTasks.id eq taskId }
                .limit(1)
                .map { rowToRecord(it) }
                .singleOrNull()
        }
    }

    fun listTasks(limit: Int = 50): List<StacktraceTaskRecord> {
        return transaction(db) {
            StacktraceTasks
                .selectAll()
                .orderBy(StacktraceTasks.createdAt to SortOrder.DESC)
                .limit(limit)
                .map { rowToRecord(it) }
        }
    }

    fun getActiveTasks(): List<StacktraceTaskRecord> {
        return transaction(db) {
            StacktraceTasks
                .select {
                    StacktraceTasks.status inList listOf(
                        StacktraceTaskStatus.QUEUED.name,
                        StacktraceTaskStatus.RUNNING.name
                    )
                }
                .orderBy(StacktraceTasks.createdAt to SortOrder.ASC)
                .map { rowToRecord(it) }
        }
    }

    fun markFailed(taskId: String, error: String, completedAt: String) {
        transaction(db) {
            StacktraceTasks.update({ StacktraceTasks.id eq taskId }) {
                it[StacktraceTasks.status] = StacktraceTaskStatus.FAILED.name
                it[StacktraceTasks.error] = error
                it[StacktraceTasks.completedAt] = completedAt
            }
        }
        logger.warn("Marked task $taskId as FAILED: $error")
    }

    private fun rowToRecord(row: ResultRow): StacktraceTaskRecord {
        val usageJson = row[StacktraceTasks.usage]
        val usage = usageJson?.let {
            try {
                json.decodeFromString<LMStudioUsage>(it)
            } catch (e: Exception) {
                logger.warn("Failed to decode usage for task ${row[StacktraceTasks.id]}: ${e.message}")
                null
            }
        }

        return StacktraceTaskRecord(
            id = row[StacktraceTasks.id],
            title = row[StacktraceTasks.title],
            status = StacktraceTaskStatus.valueOf(row[StacktraceTasks.status]),
            stacktrace = row[StacktraceTasks.stacktrace],
            stacktracePath = row[StacktraceTasks.stacktracePath],
            model = row[StacktraceTasks.model],
            temperature = row[StacktraceTasks.temperature],
            maxTokens = row[StacktraceTasks.maxTokens],
            topP = row[StacktraceTasks.topP],
            presencePenalty = row[StacktraceTasks.presencePenalty],
            frequencyPenalty = row[StacktraceTasks.frequencyPenalty],
            createdAt = row[StacktraceTasks.createdAt],
            startedAt = row[StacktraceTasks.startedAt],
            completedAt = row[StacktraceTasks.completedAt],
            report = row[StacktraceTasks.report],
            error = row[StacktraceTasks.error],
            usage = usage
        )
    }
}
